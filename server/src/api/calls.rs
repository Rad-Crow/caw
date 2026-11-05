use std::sync::Arc;

use axum::{extract::State, Json};
use base64::{engine::general_purpose::STANDARD, Engine};
use hmac::{Hmac, Mac};
use serde_json::{json, Value};
use sha1::Sha1;

use crate::{
    auth::{now, AuthUser},
    error::Result,
    App,
};

pub async fn turn(State(app): State<Arc<App>>, me: AuthUser) -> Result<Json<Value>> {
    let cfg = &app.cfg;
    let mut servers = vec![];
    if !cfg.stun_urls.is_empty() {
        servers.push(json!({ "urls": cfg.stun_urls }));
    }
    if let (Some(secret), false) = (&cfg.turn_secret, cfg.turn_urls.is_empty()) {
        let username = format!("{}:{}", now() + cfg.turn_ttl as i64, me.id);
        let mut mac = Hmac::<Sha1>::new_from_slice(secret.as_bytes()).map_err(|e| anyhow::anyhow!("{e}"))?;
        mac.update(username.as_bytes());
        let credential = STANDARD.encode(mac.finalize().into_bytes());
        servers.push(json!({ "urls": cfg.turn_urls, "username": username, "credential": credential }));
    }
    Ok(Json(json!({ "ice_servers": servers, "ttl": cfg.turn_ttl })))
}
