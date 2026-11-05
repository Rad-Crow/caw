use std::{net::IpAddr, sync::Arc};

use reqwest::{StatusCode, Url};
use serde_json::Value;

use crate::{
    auth::now,
    error::{bad, Result},
    webpush, App,
};

// endpoints belong to the session that registered them, so a signed-out device stops getting pushes
pub async fn send_user(app: Arc<App>, user_id: i64, payload: Value) {
    let rows: Vec<(i64, String, String, String)> = match sqlx::query_as(
        "SELECT p.id, p.endpoint, p.p256dh, p.auth FROM push_endpoints p JOIN sessions s ON s.token_hash = p.session
         WHERE s.user_id = ?",
    )
    .bind(user_id)
    .fetch_all(&app.db)
    .await
    {
        Ok(r) => r,
        Err(e) => return tracing::warn!("push lookup failed: {e}"),
    };
    let urgent = payload["type"] != "mail";
    let ttl = if urgent { "60" } else { "86400" };
    let body = payload.to_string();
    for (id, ep, p256dh, auth) in rows {
        let (Ok(url), Ok(sealed)) = (Url::parse(&ep), webpush::encrypt(&p256dh, &auth, body.as_bytes())) else {
            tracing::warn!("push {id}: bad endpoint or keys");
            continue;
        };
        let res = app
            .http
            .post(url.clone())
            .header("TTL", ttl)
            .header("Urgency", if urgent { "high" } else { "normal" })
            .header("Content-Encoding", "aes128gcm")
            .header("Content-Type", "application/octet-stream")
            .header("Authorization", app.vapid.header(&url, &app.cfg.public_url, now()))
            .body(sealed)
            .send()
            .await;
        match res {
            Ok(r) if r.status() == StatusCode::NOT_FOUND || r.status() == StatusCode::GONE => {
                tracing::info!("dropping dead push endpoint {id}");
                let _ = sqlx::query("DELETE FROM push_endpoints WHERE id = ?")
                    .bind(id)
                    .execute(&app.db)
                    .await;
            }
            Ok(r) if !r.status().is_success() => tracing::warn!("push {id} got {}", r.status()),
            Err(e) => tracing::warn!("push {id} failed: {e}"),
            _ => {}
        }
    }
}

pub async fn validate_endpoint(app: &App, endpoint: &str) -> Result<()> {
    if endpoint.len() > 2048 {
        return Err(bad("Push endpoint URL is too long."));
    }
    let url = Url::parse(endpoint).map_err(|_| bad("Push endpoint is not a valid URL."))?;
    if !matches!(url.scheme(), "http" | "https") {
        return Err(bad("Push endpoint must use HTTP or HTTPS."));
    }
    let host = url.host_str().unwrap_or_default().to_lowercase();
    if !app.cfg.push_allowed_hosts.is_empty() && !app.cfg.push_allowed_hosts.contains(&host) {
        return Err(bad(format!(
            "this server only accepts push from {}",
            app.cfg.push_allowed_hosts.join(", ")
        )));
    }
    if app.cfg.push_allow_private {
        return Ok(());
    }
    let host = url.host_str().ok_or_else(|| bad("Push endpoint has no host."))?;
    let port = url.port_or_known_default().unwrap_or(443);
    let addrs: Vec<IpAddr> = tokio::net::lookup_host((host.trim_matches(['[', ']']), port))
        .await
        .map_err(|_| bad("Push endpoint host not found."))?
        .map(|a| a.ip())
        .collect();
    if addrs.is_empty() || !addrs.into_iter().all(is_public) {
        return Err(bad("Push endpoint is on a private network."));
    }
    Ok(())
}

fn is_public(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => {
            let o = v4.octets();
            !(v4.is_private()
                || v4.is_loopback()
                || v4.is_link_local()
                || v4.is_unspecified()
                || v4.is_broadcast()
                || v4.is_documentation()
                || (o[0] == 100 && (o[1] & 0xc0) == 64))
        }
        IpAddr::V6(v6) => {
            if let Some(v4) = v6.to_ipv4_mapped() {
                return is_public(IpAddr::V4(v4));
            }
            let s = v6.segments()[0];
            !(v6.is_loopback() || v6.is_unspecified() || (s & 0xfe00) == 0xfc00 || (s & 0xffc0) == 0xfe80)
        }
    }
}
