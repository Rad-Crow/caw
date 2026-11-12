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

// a capability to reach one account on this server: a contact's, or a group's shared by its members
#[derive(Clone, Debug)]
pub struct Link {
    pub id: String,
    pub user_id: i64,
    pub group: bool,
    pub state: String,
    pub expires_at: Option<i64>,
}

impl Link {
    // operations reserved for a contact link; a group token is held by every member
    pub fn contact_only(&self) -> Result<()> {
        if self.group {
            return Err(AppError::Forbidden("Not available for group links.".into()));
        }
        Ok(())
    }
}

#[derive(sqlx::FromRow)]
struct Row {
    id: String,
    user_id: i64,
    token_hash: String,
    kind: String,
    prev_token_hash: Option<String>,
    prev_until: Option<i64>,
    state: String,
    expires_at: Option<i64>,
}

fn same(a: &str, b: &str) -> bool {
    a.len() == b.len() && a.bytes().zip(b.bytes()).fold(0, |acc, (x, y)| acc | (x ^ y)) == 0
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
    let row: Option<Row> =
        sqlx::query_as("SELECT id, user_id, token_hash, kind, prev_token_hash, prev_until, state, expires_at FROM links WHERE id = ?")
            .bind(id)
            .fetch_optional(&app.db)
            .await?;
    // unknown ids also return 410, so absent and removed links are indistinguishable
    let t = now();
    let row = row
        .filter(|r| r.expires_at.is_none_or(|e| e > t))
        .ok_or_else(|| AppError::Gone("This contact is no longer available.".into()))?;
    let th = hash_token(token);
    let previous = row.prev_token_hash.is_some_and(|p| same(&th, &p)) && row.prev_until.is_some_and(|u| u > t);
    if !same(&th, &row.token_hash) && !previous {
        return Err(AppError::Unauthorized);
    }
    sqlx::query("UPDATE links SET last_used = ? WHERE id = ? AND last_used < ?")
        .bind(t)
        .bind(&row.id)
        .bind(t - 300)
        .execute(&app.db)
        .await?;
    Ok(Link {
        group: row.kind == "group",
        id: row.id,
        user_id: row.user_id,
        state: row.state,
        expires_at: row.expires_at,
    })
}

// any link, including a pairing request its owner has not accepted; that one only reports its state and can be withdrawn
pub struct AnyLink(pub Link);

impl FromRequestParts<Arc<App>> for AnyLink {
    type Rejection = AppError;

    async fn from_request_parts(parts: &mut Parts, app: &Arc<App>) -> Result<Self> {
        let cred = credential(parts).ok_or(AppError::Unauthorized)?;
        Ok(AnyLink(authenticate(app, &cred).await?))
    }
}

impl FromRequestParts<Arc<App>> for Link {
    type Rejection = AppError;

    async fn from_request_parts(parts: &mut Parts, app: &Arc<App>) -> Result<Self> {
        let AnyLink(link) = AnyLink::from_request_parts(parts, app).await?;
        if link.state == "pending" {
            return Err(AppError::Forbidden("This request has not been accepted.".into()));
        }
        Ok(link)
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
