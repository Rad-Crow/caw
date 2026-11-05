use std::sync::{Arc, OnceLock};

use argon2::{
    password_hash::{PasswordHash, PasswordHasher, PasswordVerifier, SaltString},
    Argon2,
};
use axum::{
    extract::FromRequestParts,
    http::{header, request::Parts},
};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use serde::Serialize;
use sha2::{Digest, Sha256};

use crate::{
    error::{AppError, Result},
    App,
};

pub const COOKIE: &str = "caw_session";
const SESSION_DAYS: i64 = 90;

pub fn now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

pub fn random_token() -> String {
    URL_SAFE_NO_PAD.encode(rand::random::<[u8; 32]>())
}

pub fn hash_token(token: &str) -> String {
    let d = Sha256::digest(token.as_bytes());
    d.iter().map(|b| format!("{b:02x}")).collect()
}

pub fn hash_password(pw: &str) -> anyhow::Result<String> {
    let salt = SaltString::encode_b64(&rand::random::<[u8; 16]>()).map_err(|e| anyhow::anyhow!("{e}"))?;
    Ok(Argon2::default()
        .hash_password(pw.as_bytes(), &salt)
        .map_err(|e| anyhow::anyhow!("{e}"))?
        .to_string())
}

pub fn verify_password(pw: &str, hash: &str) -> bool {
    PasswordHash::new(hash).is_ok_and(|h| Argon2::default().verify_password(pw.as_bytes(), &h).is_ok())
}

pub async fn hash_password_async(pw: String) -> anyhow::Result<String> {
    tokio::task::spawn_blocking(move || hash_password(&pw)).await?
}

// with no hash, still verifies against a dummy so unknown usernames take the same time
pub async fn check_password(pw: String, hash: Option<String>) -> bool {
    static DUMMY: OnceLock<String> = OnceLock::new();
    tokio::task::spawn_blocking(move || match hash {
        Some(h) => verify_password(&pw, &h),
        None => {
            let d = DUMMY.get_or_init(|| hash_password("dummy-password").unwrap_or_default());
            verify_password(&pw, d);
            false
        }
    })
    .await
    .unwrap_or(false)
}

pub fn validate_username(u: &str) -> Result<String> {
    let u = u.trim().to_lowercase();
    let ok = (3..=32).contains(&u.len())
        && u.chars().all(|c| c.is_ascii_alphanumeric() || "_.-".contains(c));
    if !ok {
        return Err(AppError::BadRequest(
            "Username must be 3-32 characters: a-z, 0-9, _ . -".into(),
        ));
    }
    Ok(u)
}

// login key and salt come from the client's argon2id derivation, not the password
pub fn validate_login(login_key: &str, salt: &str) -> Result<()> {
    let ok = |s: &str, n: usize| URL_SAFE_NO_PAD.decode(s).is_ok_and(|b| b.len() == n);
    if !ok(login_key, 32) || !ok(salt, 16) {
        return Err(AppError::BadRequest("Invalid sign-in data.".into()));
    }
    Ok(())
}

pub fn clean_display_name(name: Option<&str>, fallback: &str) -> Result<String> {
    let n = name.map(str::trim).filter(|s| !s.is_empty()).unwrap_or(fallback);
    if n.chars().count() > 64 {
        return Err(AppError::BadRequest("Display name must be at most 64 characters.".into()));
    }
    Ok(n.to_string())
}

pub async fn create_session(app: &App, user_id: i64, user_agent: Option<&str>) -> Result<String> {
    let token = random_token();
    let t = now();
    sqlx::query("INSERT INTO sessions (token_hash, user_id, user_agent, created_at, last_seen) VALUES (?, ?, ?, ?, ?)")
        .bind(hash_token(&token))
        .bind(user_id)
        .bind(user_agent.map(|s| s.chars().take(200).collect::<String>()))
        .bind(t)
        .bind(t)
        .execute(&app.db)
        .await?;
    Ok(token)
}

pub fn session_cookie(app: &App, token: &str) -> String {
    let secure = if app.cfg.secure_cookies() { "; Secure" } else { "" };
    format!(
        "{COOKIE}={token}; Path=/; HttpOnly; SameSite=Strict; Max-Age={}{secure}",
        SESSION_DAYS * 86400
    )
}

pub fn clear_cookie() -> String {
    format!("{COOKIE}=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0")
}

fn token_from_parts(parts: &Parts) -> Option<String> {
    if let Some(v) = parts.headers.get(header::AUTHORIZATION).and_then(|v| v.to_str().ok()) {
        if let Some(t) = v.strip_prefix("Bearer ") {
            return Some(t.trim().to_string());
        }
    }
    parts
        .headers
        .get_all(header::COOKIE)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .flat_map(|v| v.split(';'))
        .filter_map(|kv| kv.trim().split_once('='))
        .find(|(k, _)| *k == COOKIE)
        .map(|(_, v)| v.to_string())
}

#[derive(Clone, Debug, Serialize, sqlx::FromRow)]
pub struct AuthUser {
    pub id: i64,
    pub username: String,
    pub display_name: String,
    pub is_admin: bool,
    #[serde(skip)]
    pub token_hash: String,
}

impl FromRequestParts<Arc<App>> for AuthUser {
    type Rejection = AppError;

    async fn from_request_parts(parts: &mut Parts, app: &Arc<App>) -> Result<Self> {
        let token = token_from_parts(parts).ok_or(AppError::Unauthorized)?;
        let th = hash_token(&token);
        let t = now();
        let user: Option<AuthUser> = sqlx::query_as(
            "SELECT u.id, u.username, u.display_name, u.is_admin, s.token_hash
             FROM sessions s JOIN users u ON u.id = s.user_id
             WHERE s.token_hash = ? AND u.disabled = 0 AND s.last_seen > ?",
        )
        .bind(&th)
        .bind(t - SESSION_DAYS * 86400)
        .fetch_optional(&app.db)
        .await?;
        let user = user.ok_or(AppError::Unauthorized)?;
        sqlx::query("UPDATE sessions SET last_seen = ? WHERE token_hash = ? AND last_seen < ?")
            .bind(t)
            .bind(&th)
            .bind(t - 300)
            .execute(&app.db)
            .await?;
        Ok(user)
    }
}

pub struct AdminUser(pub AuthUser);

impl FromRequestParts<Arc<App>> for AdminUser {
    type Rejection = AppError;

    async fn from_request_parts(parts: &mut Parts, app: &Arc<App>) -> Result<Self> {
        let u = AuthUser::from_request_parts(parts, app).await?;
        if !u.is_admin {
            return Err(AppError::Forbidden("Admin only.".into()));
        }
        Ok(AdminUser(u))
    }
}
