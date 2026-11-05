use std::sync::Arc;

use axum::{
    extract::{Path, State},
    http::StatusCode,
    Json,
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use super::blobs;
use crate::{
    auth::{hash_token, now, random_token, AuthUser},
    error::{bad, AppError, Result},
    hub::{Mail, Out},
    link::Link,
    push,
    settings::Settings,
    webpush, App,
};

const MAX_BODY: usize = 64 * 1024;
const MAX_BLOBS: usize = 10;
const MAX_LINKS: i64 = 1000;
// sender's and recipient's devices together
const MAX_STANZAS: usize = 40;

fn check_b64(s: &str, len: usize, what: &str) -> Result<()> {
    match webpush::b64(s) {
        Ok(b) if b.len() == len => Ok(()),
        _ => Err(bad(format!("Invalid {what}."))),
    }
}

fn clean_peer(server: &str, account: &str) -> Result<(String, String)> {
    let server = server.trim().trim_end_matches('/');
    if server.len() > 255 || !(server.starts_with("https://") || server.starts_with("http://")) {
        return Err(bad("Invalid server address."));
    }
    if account.len() > 64 || account.is_empty() {
        return Err(bad("Invalid account."));
    }
    Ok((server.to_string(), account.to_string()))
}

async fn new_link(app: &App, user: i64, peer_server: &str, peer_account: &str) -> Result<Value> {
    let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM links WHERE user_id = ?")
        .bind(user)
        .fetch_one(&app.db)
        .await?;
    if count >= MAX_LINKS {
        return Err(AppError::Conflict("Contact limit reached.".into()));
    }
    let id = uuid::Uuid::new_v4().to_string();
    let token = random_token();
    let t = now();
    sqlx::query(
        "INSERT INTO links (id, user_id, token_hash, peer_server, peer_account, created_at, last_used)
         VALUES (?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(&id)
    .bind(user)
    .bind(hash_token(&token))
    .bind(peer_server)
    .bind(peer_account)
    .bind(t)
    .bind(t)
    .execute(&app.db)
    .await?;
    Ok(json!({ "link_id": id, "token": token }))
}

pub async fn create_pairing(State(app): State<Arc<App>>, me: AuthUser) -> Result<Json<Value>> {
    let s = Settings::load(&app.db).await?;
    let ticket = random_token();
    let expires = now() + s.pairing_code_minutes * 60;
    sqlx::query("INSERT INTO pairings (ticket_hash, user_id, expires_at) VALUES (?, ?, ?)")
        .bind(hash_token(&ticket))
        .bind(me.id)
        .bind(expires)
        .execute(&app.db)
        .await?;
    Ok(Json(json!({ "ticket": ticket, "expires_at": expires, "account": me.id.to_string() })))
}

#[derive(Deserialize)]
pub struct Redeem {
    ticket: String,
    peer_server: String,
    peer_account: String,
}

// the ticket is the whole authorization; single use
pub async fn redeem_pairing(State(app): State<Arc<App>>, Json(req): Json<Redeem>) -> Result<Json<Value>> {
    let (peer_server, peer_account) = clean_peer(&req.peer_server, &req.peer_account)?;
    let user: Option<i64> = sqlx::query_scalar("DELETE FROM pairings WHERE ticket_hash = ? AND expires_at > ? RETURNING user_id")
        .bind(hash_token(&req.ticket))
        .bind(now())
        .fetch_optional(&app.db)
        .await?;
    let user = user.ok_or_else(|| AppError::Gone("This code has expired or was already used.".into()))?;
    Ok(Json(new_link(&app, user, &peer_server, &peer_account).await?))
}

#[derive(Deserialize)]
pub struct NewLink {
    peer_server: String,
    peer_account: String,
}

pub async fn create_link(State(app): State<Arc<App>>, me: AuthUser, Json(req): Json<NewLink>) -> Result<Json<Value>> {
    let (peer_server, peer_account) = clean_peer(&req.peer_server, &req.peer_account)?;
    let mut v = new_link(&app, me.id, &peer_server, &peer_account).await?;
    v["account"] = json!(me.id.to_string());
    Ok(Json(v))
}

#[derive(Serialize, sqlx::FromRow)]
pub struct LinkRow {
    id: String,
    peer_server: String,
    peer_account: String,
    created_at: i64,
    last_used: i64,
}

pub async fn my_links(State(app): State<Arc<App>>, me: AuthUser) -> Result<Json<Vec<LinkRow>>> {
    let rows = sqlx::query_as("SELECT id, peer_server, peer_account, created_at, last_used FROM links WHERE user_id = ? ORDER BY created_at")
        .bind(me.id)
        .fetch_all(&app.db)
        .await?;
    Ok(Json(rows))
}

pub async fn revoke_link(State(app): State<Arc<App>>, me: AuthUser, Path(id): Path<String>) -> Result<StatusCode> {
    sqlx::query("DELETE FROM links WHERE id = ? AND user_id = ?")
        .bind(&id)
        .bind(me.id)
        .execute(&app.db)
        .await?;
    app.hub.drop_link(&id);
    app.hub.send_user(me.id, &Out::LinkRevoked { link_id: id });
    Ok(StatusCode::NO_CONTENT)
}

// everything sent to this account, across its links
pub async fn my_mail(State(app): State<Arc<App>>, me: AuthUser) -> Result<Json<Value>> {
    let t = now();
    sqlx::query("UPDATE devices SET last_seen = ? WHERE id = (SELECT device_id FROM sessions WHERE token_hash = ?)")
        .bind(t)
        .bind(&me.token_hash)
        .execute(&app.db)
        .await?;
    let rows: Vec<Mail> = sqlx::query_as(
        "SELECT m.link_id, m.id, m.from_device, m.ttl, m.created_at, m.expires_at, m.body, m.keys, m.sig
         FROM mail m JOIN links l ON l.id = m.link_id
         WHERE l.user_id = ? AND m.expires_at > ? ORDER BY m.created_at",
    )
    .bind(me.id)
    .bind(t)
    .fetch_all(&app.db)
    .await?;
    Ok(Json(json!({ "now": t, "mail": rows })))
}

#[derive(Deserialize)]
pub struct Sent {
    contact: String,
}

// notifies the account's other devices of a send via a contact's server; contact is an opaque client-side id
pub async fn sent(State(app): State<Arc<App>>, me: AuthUser, Json(req): Json<Sent>) -> Result<StatusCode> {
    if req.contact.len() > 64 {
        return Err(bad("Invalid contact."));
    }
    app.limits.hit(&me.id.to_string(), "sent", 120)?;
    app.hub.send_user(me.id, &Out::Sent { contact: req.contact });
    Ok(StatusCode::NO_CONTENT)
}

// --- link token routes

#[derive(Serialize, sqlx::FromRow)]
pub struct LinkedDevice {
    id: String,
    sign_pub: String,
    enc_pub: String,
    enc_created_at: i64,
    enc_sig: String,
}

// the account's devices, as this server reports them; senders seal for all of them
pub async fn link_devices(State(app): State<Arc<App>>, link: Link) -> Result<Json<Value>> {
    let devices: Vec<LinkedDevice> = sqlx::query_as(
        "SELECT id, sign_pub, enc_pub, enc_created_at, enc_sig FROM devices
         WHERE user_id = ? AND enc_pub IS NOT NULL ORDER BY created_at",
    )
    .bind(link.user_id)
    .fetch_all(&app.db)
    .await?;
    Ok(Json(json!({ "account": link.user_id.to_string(), "devices": devices })))
}

#[derive(Deserialize)]
pub struct Stanza {
    d: String,
    k: String,
    w: String,
    o: String,
}

#[derive(Deserialize)]
pub struct NewMail {
    id: String,
    from_device: String,
    ttl: i64,
    body: String,
    keys: Vec<Stanza>,
    sig: String,
    #[serde(default)]
    blobs: Vec<String>,
}

pub async fn send_mail(State(app): State<Arc<App>>, link: Link, Json(req): Json<NewMail>) -> Result<Json<Mail>> {
    app.limits.hit(&link.id, "mail", 60)?;
    uuid::Uuid::parse_str(&req.id).map_err(|_| bad("Message ID must be a UUID."))?;
    // retention is clamped to this server's bounds; ttl is stored as signed
    let s = Settings::load(&app.db).await?;
    if req.ttl <= 0 {
        return Err(bad("Invalid expiry."));
    }
    let keep = req.ttl.clamp(s.message_ttl_min, s.message_ttl_max);
    if req.body.len() > MAX_BODY {
        return Err(bad("Message exceeds the maximum size."));
    }
    if req.blobs.len() > MAX_BLOBS {
        return Err(bad("Too many attachments."));
    }
    if req.keys.is_empty() || req.keys.len() > MAX_STANZAS || req.from_device.len() > 64 {
        return Err(bad("Invalid recipients."));
    }
    for k in &req.keys {
        check_b64(&k.k, 32, "key id")?;
        check_b64(&k.w, 64, "wrapped key")?;
        if k.d.len() > 64 || !(k.o == "to" || k.o == "from") {
            return Err(bad("Invalid recipients."));
        }
    }
    check_b64(&req.sig, 64, "sig")?;
    // every listed device must be covered; extra keys (e.g. a just-removed device) are accepted
    let devices: Vec<String> = sqlx::query_scalar("SELECT id FROM devices WHERE user_id = ? AND enc_pub IS NOT NULL")
        .bind(link.user_id)
        .fetch_all(&app.db)
        .await?;
    if devices.iter().any(|d| !req.keys.iter().any(|k| k.o == "to" && &k.d == d)) {
        return Err(AppError::Stale("The recipient's devices changed.".into()));
    }

    let t = now();
    let keys = json!(req.keys.iter().map(|k| json!({ "d": k.d, "k": k.k, "w": k.w, "o": k.o })).collect::<Vec<_>>());
    let mail = Mail {
        link_id: link.id.clone(),
        id: req.id,
        from_device: req.from_device,
        ttl: req.ttl,
        created_at: t,
        expires_at: t + keep,
        body: req.body,
        keys,
        sig: req.sig,
    };
    let mut tx = app.db.begin().await?;
    let res = sqlx::query(
        "INSERT INTO mail (link_id, id, from_device, ttl, created_at, expires_at, body, keys, sig)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(&mail.link_id)
    .bind(&mail.id)
    .bind(&mail.from_device)
    .bind(mail.ttl)
    .bind(mail.created_at)
    .bind(mail.expires_at)
    .bind(&mail.body)
    .bind(mail.keys.to_string())
    .bind(&mail.sig)
    .execute(&mut *tx)
    .await;
    match res {
        Err(sqlx::Error::Database(e)) if e.is_unique_violation() => {
            return Err(AppError::Conflict("A message with this ID already exists.".into()))
        }
        r => {
            r?;
        }
    }
    blobs::attach(&mut tx, &req.blobs, &link.id, &mail.id, mail.expires_at).await?;
    tx.commit().await?;

    app.hub.send_user(link.user_id, &Out::Mail(mail.clone()));
    tokio::spawn(push::send_user(app.clone(), link.user_id, json!({ "type": "mail" })));
    Ok(Json(mail))
}

// the sender's own copies, still sealed
pub async fn link_mail(State(app): State<Arc<App>>, link: Link) -> Result<Json<Value>> {
    let t = now();
    let rows: Vec<Mail> = sqlx::query_as(
        "SELECT link_id, id, from_device, ttl, created_at, expires_at, body, keys, sig
         FROM mail WHERE link_id = ? AND expires_at > ? ORDER BY created_at",
    )
    .bind(&link.id)
    .bind(t)
    .fetch_all(&app.db)
    .await?;
    Ok(Json(json!({ "now": t, "mail": rows })))
}

// the contact removes the link; connected devices of the account are notified
pub async fn drop_link(State(app): State<Arc<App>>, link: Link) -> Result<StatusCode> {
    sqlx::query("DELETE FROM links WHERE id = ?").bind(&link.id).execute(&app.db).await?;
    app.hub.drop_link(&link.id);
    app.hub.send_user(link.user_id, &Out::LinkRemoved { link_id: link.id });
    Ok(StatusCode::NO_CONTENT)
}
