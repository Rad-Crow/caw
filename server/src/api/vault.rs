use std::sync::Arc;

use axum::{extract::State, http::StatusCode, Json};
use serde::Deserialize;
use serde_json::{json, Value};

use crate::{
    auth::{now, AuthUser},
    error::{bad, AppError, Result},
    hub::Out,
    App,
};

const MAX_BLOB: usize = 1_500_000;

pub async fn get(State(app): State<Arc<App>>, me: AuthUser) -> Result<Json<Value>> {
    let row: Option<(i64, Option<String>, Option<String>)> =
        sqlx::query_as("SELECT version, blob, wrapped_key FROM vaults WHERE user_id = ?")
            .bind(me.id)
            .fetch_optional(&app.db)
            .await?;
    let (version, blob, key) = row.unwrap_or((0, None, None));
    let open = |s: Option<String>| s.and_then(|s| app.secret.open(&s));
    Ok(Json(json!({ "version": version, "blob": open(blob), "wrapped_key": open(key) })))
}

#[derive(Deserialize)]
pub struct Put {
    version: i64,
    blob: String,
}

// compare-and-swap on version; a stale version returns 409 and the client re-merges
pub async fn put(State(app): State<Arc<App>>, me: AuthUser, Json(req): Json<Put>) -> Result<Json<Value>> {
    if req.blob.len() > MAX_BLOB {
        return Err(bad("Contact list is too large."));
    }
    app.limits.hit(&me.id.to_string(), "vault", 60)?;
    let sealed = app.secret.seal(&req.blob);
    let t = now();
    let next = req.version + 1;
    let res = if req.version == 0 {
        sqlx::query(
            "INSERT INTO vaults (user_id, version, blob, updated_at) VALUES (?, 1, ?, ?)
             ON CONFLICT (user_id) DO UPDATE SET version = 1, blob = excluded.blob, updated_at = excluded.updated_at
             WHERE vaults.version = 0",
        )
        .bind(me.id)
        .bind(&sealed)
        .bind(t)
        .execute(&app.db)
        .await?
    } else {
        sqlx::query("UPDATE vaults SET version = ?, blob = ?, updated_at = ? WHERE user_id = ? AND version = ?")
            .bind(next)
            .bind(&sealed)
            .bind(t)
            .bind(me.id)
            .bind(req.version)
            .execute(&app.db)
            .await?
    };
    if res.rows_affected() == 0 {
        return Err(AppError::Conflict("The contact list changed. Try again.".into()));
    }
    app.hub.send_user(me.id, &Out::Vault { version: next });
    Ok(Json(json!({ "version": next })))
}

pub async fn store_key(app: &App, user: i64, wrapped: &str) -> Result<()> {
    if wrapped.len() > 512 {
        return Err(bad("Invalid key."));
    }
    sqlx::query(
        "INSERT INTO vaults (user_id, wrapped_key, updated_at) VALUES (?, ?, ?)
         ON CONFLICT (user_id) DO UPDATE SET wrapped_key = excluded.wrapped_key, updated_at = excluded.updated_at",
    )
    .bind(user)
    .bind(app.secret.seal(wrapped))
    .bind(now())
    .execute(&app.db)
    .await?;
    Ok(())
}

#[derive(Deserialize)]
pub struct Key {
    wrapped_key: String,
}

pub async fn put_key(State(app): State<Arc<App>>, me: AuthUser, Json(req): Json<Key>) -> Result<StatusCode> {
    store_key(&app, me.id, &req.wrapped_key).await?;
    Ok(StatusCode::NO_CONTENT)
}
