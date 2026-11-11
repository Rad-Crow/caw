use std::sync::Arc;

use axum::{
    extract::{Path, State},
    http::StatusCode,
    Json,
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::{
    auth::{now, AuthUser},
    error::{bad, AppError, Result},
    hub::Out,
    push, webpush, App,
};

const MAX_DEVICES: i64 = 10;

// binds the session to its device, so removing the device signs it out
async fn bind_session(app: &App, me: &AuthUser, device: &str) -> Result<()> {
    sqlx::query(
        "UPDATE sessions SET device_id = ?1 WHERE token_hash = ?2
         AND EXISTS (SELECT 1 FROM devices WHERE id = ?1 AND user_id = ?3)",
    )
    .bind(device)
    .bind(&me.token_hash)
    .bind(me.id)
    .execute(&app.db)
    .await?;
    Ok(())
}

fn check_b64(s: &str, len: usize, what: &str) -> Result<()> {
    match webpush::b64(s) {
        Ok(b) if b.len() == len => Ok(()),
        _ => Err(bad(format!("Invalid {what}."))),
    }
}

#[derive(Serialize, sqlx::FromRow)]
pub struct Device {
    id: String,
    label: String,
    sign_pub: String,
    enc_pub: Option<String>,
    enc_created_at: Option<i64>,
    enc_sig: Option<String>,
    created_at: i64,
    last_seen: i64,
    // current and recent encryption keys
    #[sqlx(skip)]
    keys: Vec<String>,
}

pub async fn my_devices(State(app): State<Arc<App>>, me: AuthUser) -> Result<Json<Vec<Device>>> {
    let mut rows: Vec<Device> = sqlx::query_as(
        "SELECT id, label, sign_pub, enc_pub, enc_created_at, enc_sig, created_at, last_seen
         FROM devices WHERE user_id = ? ORDER BY created_at",
    )
    .bind(me.id)
    .fetch_all(&app.db)
    .await?;
    let keys: Vec<(String, String)> = sqlx::query_as(
        "SELECT k.device_id, k.enc_pub FROM device_keys k JOIN devices d ON d.id = k.device_id
         WHERE d.user_id = ? ORDER BY k.created_at",
    )
    .bind(me.id)
    .fetch_all(&app.db)
    .await?;
    for (device, key) in keys {
        if let Some(d) = rows.iter_mut().find(|d| d.id == device) {
            d.keys.push(key);
        }
    }
    Ok(Json(rows))
}

#[derive(Deserialize)]
pub struct NewDevice {
    #[serde(default)]
    label: String,
    sign_pub: String,
}

pub async fn add_device(
    State(app): State<Arc<App>>,
    me: AuthUser,
    Json(req): Json<NewDevice>,
) -> Result<Json<Value>> {
    check_b64(&req.sign_pub, 32, "sign_pub")?;
    let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM devices WHERE user_id = ?")
        .bind(me.id)
        .fetch_one(&app.db)
        .await?;
    if count >= MAX_DEVICES {
        return Err(AppError::Conflict("Device limit reached. Remove one under Account.".into()));
    }
    let id = uuid::Uuid::new_v4().to_string();
    let t = now();
    sqlx::query(
        "INSERT INTO devices (id, user_id, label, sign_pub, created_at, last_seen) VALUES (?, ?, ?, ?, ?, ?)",
    )
    .bind(&id)
    .bind(me.id)
    .bind(req.label.chars().take(64).collect::<String>())
    .bind(&req.sign_pub)
    .bind(t)
    .bind(t)
    .execute(&app.db)
    .await?;
    bind_session(&app, &me, &id).await?;
    app.hub.send_user(me.id, &Out::Devices);
    Ok(Json(json!({ "id": id })))
}

#[derive(Deserialize)]
pub struct EncKey {
    enc_pub: String,
    created_at: i64,
    sig: String,
}

pub async fn set_device_key(
    State(app): State<Arc<App>>,
    me: AuthUser,
    Path(id): Path<String>,
    Json(req): Json<EncKey>,
) -> Result<StatusCode> {
    check_b64(&req.enc_pub, 32, "enc_pub")?;
    check_b64(&req.sig, 64, "sig")?;
    let before: Option<Option<String>> = sqlx::query_scalar("SELECT enc_pub FROM devices WHERE id = ? AND user_id = ?")
        .bind(&id)
        .bind(me.id)
        .fetch_optional(&app.db)
        .await?;
    let res = sqlx::query(
        "UPDATE devices SET enc_pub = ?, enc_created_at = ?, enc_sig = ?, last_seen = ? WHERE id = ? AND user_id = ?",
    )
    .bind(&req.enc_pub)
    .bind(req.created_at)
    .bind(&req.sig)
    .bind(now())
    .bind(&id)
    .bind(me.id)
    .execute(&app.db)
    .await?;
    if res.rows_affected() == 0 {
        return Err(AppError::NotFound);
    }
    bind_session(&app, &me, &id).await?;
    sqlx::query("INSERT OR IGNORE INTO device_keys (device_id, enc_pub, created_at) VALUES (?, ?, ?)")
        .bind(&id)
        .bind(&req.enc_pub)
        .bind(req.created_at)
        .execute(&app.db)
        .await?;
    if before.flatten().as_deref() != Some(req.enc_pub.as_str()) {
        app.hub.send_user(me.id, &Out::Devices);
    }
    Ok(StatusCode::NO_CONTENT)
}

pub async fn remove_device(State(app): State<Arc<App>>, me: AuthUser, Path(id): Path<String>) -> Result<StatusCode> {
    let sessions: Vec<String> = sqlx::query_scalar("DELETE FROM sessions WHERE device_id = ? AND user_id = ? RETURNING token_hash")
        .bind(&id)
        .bind(me.id)
        .fetch_all(&app.db)
        .await?;
    app.hub.kick_sessions(&sessions);
    let res = sqlx::query("DELETE FROM devices WHERE id = ? AND user_id = ?")
        .bind(&id)
        .bind(me.id)
        .execute(&app.db)
        .await?;
    if res.rows_affected() == 0 {
        return Err(AppError::NotFound);
    }
    app.hub.send_user(me.id, &Out::Devices);
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
pub struct PushReq {
    endpoint: String,
    p256dh: String,
    auth: String,
}

#[derive(Deserialize)]
pub struct PushRemove {
    endpoint: String,
}

pub async fn push_register(
    State(app): State<Arc<App>>,
    me: AuthUser,
    Json(req): Json<PushReq>,
) -> Result<StatusCode> {
    push::validate_endpoint(&app, &req.endpoint).await?;
    webpush::check_keys(&req.p256dh, &req.auth).map_err(|e| bad(e.to_string()))?;
    sqlx::query(
        "INSERT INTO push_endpoints (user_id, endpoint, p256dh, auth, session, created_at) VALUES (?, ?, ?, ?, ?, ?)
         ON CONFLICT(endpoint) DO UPDATE SET user_id = excluded.user_id, p256dh = excluded.p256dh,
           auth = excluded.auth, session = excluded.session, created_at = excluded.created_at",
    )
    .bind(me.id)
    .bind(&req.endpoint)
    .bind(&req.p256dh)
    .bind(&req.auth)
    .bind(&me.token_hash)
    .bind(now())
    .execute(&app.db)
    .await?;
    sqlx::query("DELETE FROM push_endpoints WHERE session = ? AND endpoint != ?")
        .bind(&me.token_hash)
        .bind(&req.endpoint)
        .execute(&app.db)
        .await?;
    sqlx::query(
        "DELETE FROM push_endpoints WHERE user_id = ?1 AND id NOT IN
         (SELECT id FROM push_endpoints WHERE user_id = ?1 ORDER BY created_at DESC, id DESC LIMIT 10)",
    )
    .bind(me.id)
    .execute(&app.db)
    .await?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn push_unregister(
    State(app): State<Arc<App>>,
    me: AuthUser,
    Json(req): Json<PushRemove>,
) -> Result<StatusCode> {
    sqlx::query("DELETE FROM push_endpoints WHERE user_id = ? AND endpoint = ?")
        .bind(me.id)
        .bind(&req.endpoint)
        .execute(&app.db)
        .await?;
    Ok(StatusCode::NO_CONTENT)
}
