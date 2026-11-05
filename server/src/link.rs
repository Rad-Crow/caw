use std::{collections::HashMap, sync::Arc, sync::Mutex};

use axum::{
    extract::FromRequestParts,
    http::{header, request::Parts},
};

use crate::{
    auth::{hash_token, now},
    error::{AppError, Result},
    App,
};

// a contact's capability to reach one account on this server
#[derive(Clone, Debug)]
pub struct Link {
    pub id: String,
    pub user_id: i64,
}

// "<link id>.<token>", from the Authorization header ("Link ...") or a ?link= query (websockets can't set headers in browsers)
fn credential(parts: &Parts) -> Option<String> {
    if let Some(v) = parts.headers.get(header::AUTHORIZATION).and_then(|v| v.to_str().ok()) {
        if let Some(c) = v.strip_prefix("Link ") {
            return Some(c.trim().to_string());
        }
    }
    parts
        .uri
        .query()?
        .split('&')
        .filter_map(|kv| kv.split_once('='))
        .find(|(k, _)| *k == "link")
        .map(|(_, v)| v.to_string())
}

pub async fn authenticate(app: &App, cred: &str) -> Result<Link> {
    let (id, token) = cred.split_once('.').ok_or(AppError::Unauthorized)?;
    let row: Option<(String, i64, String)> =
        sqlx::query_as("SELECT id, user_id, token_hash FROM links WHERE id = ?")
            .bind(id)
            .fetch_optional(&app.db)
            .await?;
    // unknown ids also return 410, so absent and removed links are indistinguishable
    let (id, user_id, token_hash) = row.ok_or_else(|| AppError::Gone("This contact is no longer available.".into()))?;
    let th = hash_token(token);
    if th.len() != token_hash.len() || th.bytes().zip(token_hash.bytes()).fold(0, |a, (x, y)| a | (x ^ y)) != 0 {
        return Err(AppError::Unauthorized);
    }
    let t = now();
    sqlx::query("UPDATE links SET last_used = ? WHERE id = ? AND last_used < ?")
        .bind(t)
        .bind(&id)
        .bind(t - 300)
        .execute(&app.db)
        .await?;
    Ok(Link { id, user_id })
}

impl FromRequestParts<Arc<App>> for Link {
    type Rejection = AppError;

    async fn from_request_parts(parts: &mut Parts, app: &Arc<App>) -> Result<Self> {
        let cred = credential(parts).ok_or(AppError::Unauthorized)?;
        authenticate(app, &cred).await
    }
}

// per key (link, username, ip), fixed one-minute windows
#[derive(Default)]
pub struct Limiter(Mutex<HashMap<String, (i64, u32)>>);

impl Limiter {
    pub fn hit(&self, link: &str, what: &str, per_min: u32) -> Result<()> {
        let mut m = self.0.lock().unwrap_or_else(|e| e.into_inner());
        let window = now() / 60;
        let e = m.entry(format!("{link}/{what}")).or_insert((window, 0));
        if e.0 != window {
            *e = (window, 0);
        }
        e.1 += 1;
        if e.1 > per_min {
            return Err(AppError::TooMany);
        }
        if m.len() > 10_000 {
            m.retain(|_, v| v.0 == window);
        }
        Ok(())
    }
}
