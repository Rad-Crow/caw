use std::sync::Arc;

use axum::{
    extract::{Path, State},
    http::{header, HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    Json,
};
use serde::Deserialize;
use serde_json::json;

use crate::{
    auth::{self, now, AuthUser},
    error::{AppError, Result},
    settings::Settings,
    App,
};

pub async fn config(State(app): State<Arc<App>>) -> Result<Json<serde_json::Value>> {
    let s = Settings::load(&app.db).await?;
    Ok(Json(json!({
        "server_name": s.server_name,
        "registration_open": s.registration_open,
        "vapid_public_key": app.vapid.public,
        "message_ttl_default": s.message_ttl_default,
        "message_ttl_min": s.message_ttl_min,
        "message_ttl_max": s.message_ttl_max,
        "max_upload_mb": s.max_upload_mb,
        "max_group_members": s.max_group_members,
    })))
}

async fn me_by_id(app: &App, id: i64) -> Result<serde_json::Value> {
    let (username, display_name, is_admin, must_change): (String, String, bool, bool) =
        sqlx::query_as("SELECT username, display_name, is_admin, must_change FROM users WHERE id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await?;
    Ok(json!({ "id": id, "username": username, "display_name": display_name, "is_admin": is_admin, "must_change": must_change }))
}

async fn logged_in(app: &App, headers: &HeaderMap, user_id: i64) -> Result<Response> {
    let ua = headers.get(header::USER_AGENT).and_then(|v| v.to_str().ok());
    let token = auth::create_session(app, user_id, ua).await?;
    let user = me_by_id(app, user_id).await?;
    Ok((
        [(header::SET_COOKIE, auth::session_cookie(app, &token))],
        Json(json!({ "token": token, "user": user })),
    )
        .into_response())
}

// argon2id parameters the clients use to turn a password into the login key and the password key
pub const KDF: (u32, u32, u32) = (65536, 3, 1);

#[derive(Deserialize)]
pub struct SaltReq {
    username: String,
}

pub async fn salt(State(app): State<Arc<App>>, Json(req): Json<SaltReq>) -> Result<Json<serde_json::Value>> {
    let username = req.username.trim().to_lowercase();
    let salt: Option<String> = sqlx::query_scalar("SELECT salt FROM users WHERE username = ?")
        .bind(&username)
        .fetch_optional(&app.db)
        .await?;
    let (m, t, p) = KDF;
    let salt = salt.unwrap_or_else(|| app.secret.fake_salt(&username));
    Ok(Json(json!({ "salt": salt, "m": m, "t": t, "p": p })))
}

#[derive(Deserialize)]
pub struct LoginReq {
    username: String,
    login_key: String,
}

fn client_ip(headers: &HeaderMap) -> String {
    headers
        .get("x-forwarded-for")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.split(',').next())
        .map(|v| v.trim().to_string())
        .unwrap_or_default()
}

pub async fn login(State(app): State<Arc<App>>, headers: HeaderMap, Json(req): Json<LoginReq>) -> Result<Response> {
    let username = req.username.trim().to_lowercase();
    app.limits.hit(&username, "login", 10)?;
    app.limits.hit(&client_ip(&headers), "login-ip", 30)?;
    let row: Option<(i64, String, bool)> =
        sqlx::query_as("SELECT id, password_hash, disabled FROM users WHERE username = ?")
            .bind(&username)
            .fetch_optional(&app.db)
            .await?;
    let ok = auth::check_password(req.login_key, row.as_ref().map(|r| r.1.clone())).await;
    match row {
        Some((id, _, false)) if ok => logged_in(&app, &headers, id).await,
        _ => Err(AppError::Forbidden("Incorrect username or password.".into())),
    }
}

pub async fn logout(State(app): State<Arc<App>>, user: AuthUser) -> Result<Response> {
    sqlx::query("DELETE FROM sessions WHERE token_hash = ?")
        .bind(&user.token_hash)
        .execute(&app.db)
        .await?;
    Ok(([(header::SET_COOKIE, auth::clear_cookie())], StatusCode::NO_CONTENT).into_response())
}

#[derive(Deserialize)]
pub struct RegisterReq {
    username: String,
    login_key: String,
    salt: String,
    display_name: Option<String>,
    invite: Option<String>,
}

pub async fn register(
    State(app): State<Arc<App>>,
    headers: HeaderMap,
    Json(req): Json<RegisterReq>,
) -> Result<Response> {
    let username = auth::validate_username(&req.username)?;
    auth::validate_login(&req.login_key, &req.salt)?;
    let display = auth::clean_display_name(req.display_name.as_deref(), &username)?;
    let invite = req.invite.filter(|s| !s.is_empty());
    if invite.is_none() && !Settings::load(&app.db).await?.registration_open {
        return Err(AppError::Forbidden("Public registration is disabled.".into()));
    }
    let hash = auth::hash_password_async(req.login_key).await?;

    let mut tx = app.db.begin().await?;
    let is_admin = match invite {
        Some(tok) => {
            let row: Option<(i64, bool)> = sqlx::query_as(
                "SELECT id, is_admin FROM invites
                 WHERE token = ? AND revoked = 0
                   AND (expires_at IS NULL OR expires_at > ?)
                   AND (max_uses IS NULL OR uses < max_uses)",
            )
            .bind(&tok)
            .bind(now())
            .fetch_optional(&mut *tx)
            .await?;
            let (id, adm) = row.ok_or_else(|| AppError::Forbidden("Invalid or expired invitation.".into()))?;
            sqlx::query("UPDATE invites SET uses = uses + 1 WHERE id = ?")
                .bind(id)
                .execute(&mut *tx)
                .await?;
            adm
        }
        None => false,
    };
    let res = sqlx::query(
        "INSERT INTO users (username, display_name, password_hash, salt, is_admin, created_at) VALUES (?, ?, ?, ?, ?, ?)",
    )
    .bind(&username)
    .bind(&display)
    .bind(&hash)
    .bind(&req.salt)
    .bind(is_admin)
    .bind(now())
    .execute(&mut *tx)
    .await;
    let id = match res {
        Ok(r) => r.last_insert_rowid(),
        Err(sqlx::Error::Database(e)) if e.is_unique_violation() => {
            return Err(AppError::Conflict("That username is already in use.".into()))
        }
        Err(e) => return Err(e.into()),
    };
    tx.commit().await?;
    tracing::info!("registered {username} (admin: {is_admin})");
    logged_in(&app, &headers, id).await
}

pub async fn check_invite(State(app): State<Arc<App>>, Path(token): Path<String>) -> Result<StatusCode> {
    let ok: Option<i64> = sqlx::query_scalar(
        "SELECT id FROM invites WHERE token = ? AND revoked = 0
           AND (expires_at IS NULL OR expires_at > ?)
           AND (max_uses IS NULL OR uses < max_uses)",
    )
    .bind(token)
    .bind(now())
    .fetch_optional(&app.db)
    .await?;
    ok.map(|_| StatusCode::NO_CONTENT).ok_or(AppError::NotFound)
}

pub async fn me(State(app): State<Arc<App>>, user: AuthUser) -> Result<Json<serde_json::Value>> {
    Ok(Json(me_by_id(&app, user.id).await?))
}

#[derive(Deserialize)]
pub struct MeUpdate {
    display_name: Option<String>,
}

pub async fn update_me(
    State(app): State<Arc<App>>,
    user: AuthUser,
    Json(req): Json<MeUpdate>,
) -> Result<Json<serde_json::Value>> {
    if let Some(name) = req.display_name.as_deref() {
        let name = auth::clean_display_name(Some(name), &user.username)?;
        sqlx::query("UPDATE users SET display_name = ? WHERE id = ?")
            .bind(name)
            .bind(user.id)
            .execute(&app.db)
            .await?;
    }
    Ok(Json(me_by_id(&app, user.id).await?))
}

// wrapped_key is the data key under the new password key; current key required unless on a temporary password
#[derive(Deserialize)]
pub struct LoginUpdate {
    current_login_key: Option<String>,
    login_key: String,
    salt: String,
    wrapped_key: Option<String>,
}

pub async fn set_login(
    State(app): State<Arc<App>>,
    user: AuthUser,
    Json(req): Json<LoginUpdate>,
) -> Result<Json<serde_json::Value>> {
    auth::validate_login(&req.login_key, &req.salt)?;
    let (hash, must_change): (String, bool) = sqlx::query_as("SELECT password_hash, must_change FROM users WHERE id = ?")
        .bind(user.id)
        .fetch_one(&app.db)
        .await?;
    if !must_change && !auth::check_password(req.current_login_key.unwrap_or_default(), Some(hash)).await {
        return Err(AppError::Forbidden("The current password is incorrect.".into()));
    }
    let hash = auth::hash_password_async(req.login_key).await?;
    sqlx::query("UPDATE users SET password_hash = ?, salt = ?, must_change = 0 WHERE id = ?")
        .bind(hash)
        .bind(&req.salt)
        .bind(user.id)
        .execute(&app.db)
        .await?;
    if let Some(w) = req.wrapped_key {
        super::vault::store_key(&app, user.id, &w).await?;
    }
    Ok(Json(me_by_id(&app, user.id).await?))
}
