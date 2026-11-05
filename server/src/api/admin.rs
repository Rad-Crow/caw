use std::sync::Arc;

use axum::{
    extract::{Path, State},
    http::StatusCode,
    Json,
};
use serde::{Deserialize, Serialize};

use crate::{
    auth::{self, now, AdminUser},
    error::{bad, AppError, Result},
    settings::Settings,
    App,
};

pub async fn settings(State(app): State<Arc<App>>, _: AdminUser) -> Result<Json<Settings>> {
    Ok(Json(Settings::load(&app.db).await?))
}

#[derive(Deserialize)]
pub struct SettingsPatch {
    server_name: Option<String>,
    registration_open: Option<bool>,
    ring_timeout_secs: Option<u64>,
    message_ttl_default: Option<i64>,
    message_ttl_min: Option<i64>,
    message_ttl_max: Option<i64>,
    max_upload_mb: Option<u64>,
    device_expiry_days: Option<i64>,
    pairing_code_minutes: Option<i64>,
}

pub async fn update_settings(
    State(app): State<Arc<App>>,
    _: AdminUser,
    Json(p): Json<SettingsPatch>,
) -> Result<Json<Settings>> {
    let mut s = Settings::load(&app.db).await?;
    if let Some(n) = p.server_name {
        let n = n.trim();
        if n.is_empty() || n.chars().count() > 64 {
            return Err(bad("Server name must be 1-64 characters."));
        }
        s.server_name = n.to_string();
    }
    if let Some(r) = p.registration_open {
        s.registration_open = r;
    }
    if let Some(t) = p.ring_timeout_secs {
        if !(10..=300).contains(&t) {
            return Err(bad("Ring timeout must be between 10 and 300 seconds."));
        }
        s.ring_timeout_secs = t;
    }
    s.message_ttl_default = p.message_ttl_default.unwrap_or(s.message_ttl_default);
    s.message_ttl_min = p.message_ttl_min.unwrap_or(s.message_ttl_min);
    s.message_ttl_max = p.message_ttl_max.unwrap_or(s.message_ttl_max);
    if !(60 <= s.message_ttl_min && s.message_ttl_min <= s.message_ttl_default && s.message_ttl_default <= s.message_ttl_max)
        || s.message_ttl_max > 30 * 86400
    {
        return Err(bad("Expiry must be 1 minute to 30 days, with minimum <= default <= maximum."));
    }
    if let Some(m) = p.max_upload_mb {
        if !(1..=500).contains(&m) {
            return Err(bad("Attachment limit must be between 1 and 500 MB."));
        }
        s.max_upload_mb = m;
    }
    if let Some(d) = p.device_expiry_days {
        if !(7..=365).contains(&d) {
            return Err(bad("Device expiry must be between 7 and 365 days."));
        }
        s.device_expiry_days = d;
    }
    if let Some(m) = p.pairing_code_minutes {
        if !(1..=1440).contains(&m) {
            return Err(bad("Pairing code expiry must be between 1 and 1440 minutes."));
        }
        s.pairing_code_minutes = m;
    }
    s.save(&app.db).await?;
    Ok(Json(s))
}

#[derive(Serialize, sqlx::FromRow)]
pub struct AdminUserRow {
    id: i64,
    username: String,
    display_name: String,
    is_admin: bool,
    disabled: bool,
    created_at: i64,
    last_seen: Option<i64>,
    #[sqlx(skip)]
    online: bool,
}

pub async fn users(State(app): State<Arc<App>>, _: AdminUser) -> Result<Json<Vec<AdminUserRow>>> {
    let mut rows: Vec<AdminUserRow> = sqlx::query_as(
        "SELECT id, username, display_name, is_admin, disabled, created_at,
                (SELECT MAX(last_seen) FROM sessions s WHERE s.user_id = users.id) AS last_seen
         FROM users ORDER BY username",
    )
    .fetch_all(&app.db)
    .await?;
    let online = app.hub.online();
    for r in &mut rows {
        r.online = online.contains(&r.id);
    }
    Ok(Json(rows))
}

#[derive(Deserialize)]
pub struct CreateUser {
    username: String,
    login_key: String,
    salt: String,
    display_name: Option<String>,
    #[serde(default)]
    is_admin: bool,
}

pub async fn create_user(
    State(app): State<Arc<App>>,
    _: AdminUser,
    Json(req): Json<CreateUser>,
) -> Result<StatusCode> {
    let username = auth::validate_username(&req.username)?;
    auth::validate_login(&req.login_key, &req.salt)?;
    let display = auth::clean_display_name(req.display_name.as_deref(), &username)?;
    let hash = auth::hash_password_async(req.login_key).await?;
    // the admin knows this password, so the user must set their own before any data is encrypted with it
    let res = sqlx::query(
        "INSERT INTO users (username, display_name, password_hash, salt, must_change, is_admin, created_at) VALUES (?, ?, ?, ?, 1, ?, ?)",
    )
    .bind(&username)
    .bind(display)
    .bind(hash)
    .bind(&req.salt)
    .bind(req.is_admin)
    .bind(now())
    .execute(&app.db)
    .await;
    match res {
        Ok(_) => Ok(StatusCode::CREATED),
        Err(sqlx::Error::Database(e)) if e.is_unique_violation() => Err(AppError::Conflict("That username is already in use.".into())),
        Err(e) => Err(e.into()),
    }
}

#[derive(Deserialize)]
pub struct UpdateUser {
    display_name: Option<String>,
    is_admin: Option<bool>,
    disabled: Option<bool>,
    login_key: Option<String>,
    salt: Option<String>,
}

pub async fn update_user(
    State(app): State<Arc<App>>,
    AdminUser(me): AdminUser,
    Path(id): Path<i64>,
    Json(req): Json<UpdateUser>,
) -> Result<StatusCode> {
    let username: String = sqlx::query_scalar("SELECT username FROM users WHERE id = ?")
        .bind(id)
        .fetch_optional(&app.db)
        .await?
        .ok_or(AppError::NotFound)?;
    if id == me.id && (req.is_admin == Some(false) || req.disabled == Some(true)) {
        return Err(bad("You cannot demote or disable yourself."));
    }
    let mut kick = false;
    if let Some(n) = req.display_name.as_deref() {
        let n = auth::clean_display_name(Some(n), &username)?;
        sqlx::query("UPDATE users SET display_name = ? WHERE id = ?")
            .bind(n)
            .bind(id)
            .execute(&app.db)
            .await?;
    }
    if let Some(a) = req.is_admin {
        sqlx::query("UPDATE users SET is_admin = ? WHERE id = ?")
            .bind(a)
            .bind(id)
            .execute(&app.db)
            .await?;
    }
    if let Some(d) = req.disabled {
        sqlx::query("UPDATE users SET disabled = ? WHERE id = ?")
            .bind(d)
            .bind(id)
            .execute(&app.db)
            .await?;
        kick |= d;
    }
    if let (Some(k), Some(salt)) = (req.login_key, req.salt) {
        auth::validate_login(&k, &salt)?;
        let hash = auth::hash_password_async(k).await?;
        sqlx::query("UPDATE users SET password_hash = ?, salt = ?, must_change = 1 WHERE id = ?")
            .bind(hash)
            .bind(&salt)
            .bind(id)
            .execute(&app.db)
            .await?;
        kick |= id != me.id;
    }
    if kick {
        sqlx::query("DELETE FROM sessions WHERE user_id = ?")
            .bind(id)
            .execute(&app.db)
            .await?;
        app.hub.kick_user(id);
    }
    Ok(StatusCode::NO_CONTENT)
}

pub async fn delete_user(
    State(app): State<Arc<App>>,
    AdminUser(me): AdminUser,
    Path(id): Path<i64>,
) -> Result<StatusCode> {
    if id == me.id {
        return Err(bad("You cannot delete your own account."));
    }
    let res = sqlx::query("DELETE FROM users WHERE id = ?").bind(id).execute(&app.db).await?;
    if res.rows_affected() == 0 {
        return Err(AppError::NotFound);
    }
    app.hub.kick_user(id);
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Serialize, sqlx::FromRow)]
pub struct InviteRow {
    id: i64,
    token: String,
    note: String,
    is_admin: bool,
    max_uses: Option<i64>,
    uses: i64,
    expires_at: Option<i64>,
    created_at: i64,
    #[sqlx(skip)]
    url: String,
}

pub async fn invites(State(app): State<Arc<App>>, _: AdminUser) -> Result<Json<Vec<InviteRow>>> {
    let mut rows: Vec<InviteRow> = sqlx::query_as(
        "SELECT id, token, note, is_admin, max_uses, uses, expires_at, created_at
         FROM invites WHERE revoked = 0 ORDER BY id DESC",
    )
    .fetch_all(&app.db)
    .await?;
    for r in &mut rows {
        r.url = format!("{}/invite/{}", app.cfg.public_url, r.token);
    }
    Ok(Json(rows))
}

#[derive(Deserialize)]
pub struct CreateInvite {
    #[serde(default)]
    note: String,
    #[serde(default)]
    is_admin: bool,
    // 0 means unlimited / never
    #[serde(default = "one")]
    max_uses: i64,
    #[serde(default = "week")]
    expires_in_hours: i64,
}

fn one() -> i64 {
    1
}

fn week() -> i64 {
    168
}

pub async fn create_invite(
    State(app): State<Arc<App>>,
    AdminUser(me): AdminUser,
    Json(req): Json<CreateInvite>,
) -> Result<StatusCode> {
    if req.note.chars().count() > 200 || req.max_uses < 0 || req.expires_in_hours < 0 {
        return Err(bad("Invalid invitation options."));
    }
    let t = now();
    sqlx::query(
        "INSERT INTO invites (token, note, is_admin, max_uses, expires_at, created_by, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(auth::random_token())
    .bind(req.note.trim())
    .bind(req.is_admin)
    .bind((req.max_uses > 0).then_some(req.max_uses))
    .bind((req.expires_in_hours > 0).then(|| t + req.expires_in_hours * 3600))
    .bind(me.id)
    .bind(t)
    .execute(&app.db)
    .await?;
    Ok(StatusCode::CREATED)
}

pub async fn revoke_invite(State(app): State<Arc<App>>, _: AdminUser, Path(id): Path<i64>) -> Result<StatusCode> {
    sqlx::query("UPDATE invites SET revoked = 1 WHERE id = ?")
        .bind(id)
        .execute(&app.db)
        .await?;
    Ok(StatusCode::NO_CONTENT)
}
