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
    link::{AnyLink, Link},
    push,
    settings::Settings,
    webpush, App,
};

const MAX_BODY: usize = 64 * 1024;
const MAX_BLOBS: usize = 10;
const MAX_LINKS: i64 = 1000;
// a pairing request's sealed name, base64
const MAX_REQUEST: usize = 1024;
// sender's and recipient's devices together
const MAX_STANZAS: usize = 40;
// a replaced group token keeps working this long, so messages already on their way arrive
const GRACE_SECS: i64 = 86400;

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

// `request` makes a pending pairing request: its expiry and the requester's sealed name
async fn new_link(app: &App, user: i64, peer_server: &str, peer_account: &str, group: Option<(i64, &str)>, request: Option<(i64, Option<&str>)>) -> Result<Value> {
    let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM links WHERE user_id = ?")
        .bind(user)
        .fetch_one(&app.db)
        .await?;
    if count >= MAX_LINKS {
        return Err(AppError::Conflict("Limit of contacts and groups reached.".into()));
    }
    let id = uuid::Uuid::new_v4().to_string();
    let token = group.map_or_else(random_token, |(_, t)| t.to_string());
    let t = now();
    sqlx::query(
        "INSERT INTO links (id, user_id, token_hash, peer_server, peer_account, created_at, last_used, kind, gen, state, expires_at, request)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(&id)
    .bind(user)
    .bind(hash_token(&token))
    .bind(peer_server)
    .bind(peer_account)
    .bind(t)
    .bind(t)
    .bind(if group.is_some() { "group" } else { "contact" })
    .bind(group.map_or(0, |(g, _)| g))
    .bind(if request.is_some() { "pending" } else { "open" })
    .bind(request.map(|(e, _)| e))
    .bind(request.and_then(|(_, r)| r))
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
    wait: Option<i64>,
    request: Option<String>,
}

// the ticket is the whole authorization; single use. The requester's server and account are not sent
pub async fn redeem_pairing(State(app): State<Arc<App>>, Json(req): Json<Redeem>) -> Result<Json<Value>> {
    if req.request.as_deref().is_some_and(|r| r.len() > MAX_REQUEST || webpush::b64(r).is_err()) {
        return Err(bad("Invalid request."));
    }
    let user: Option<i64> = sqlx::query_scalar("DELETE FROM pairings WHERE ticket_hash = ? AND expires_at > ? RETURNING user_id")
        .bind(hash_token(&req.ticket))
        .bind(now())
        .fetch_optional(&app.db)
        .await?;
    let user = user.ok_or_else(|| AppError::Gone("This code has expired or was already used.".into()))?;
    let max = Settings::load(&app.db).await?.pairing_request_hours * 3600;
    let expires_at = now() + req.wait.unwrap_or(max).clamp(60, max);
    let mut v = new_link(&app, user, "", "", None, Some((expires_at, req.request.as_deref()))).await?;
    app.hub.send_user(user, &Out::Requests);
    tokio::spawn(push::send_user(app.clone(), user, json!({ "type": "request" })));
    v["expires_at"] = json!(expires_at);
    Ok(Json(v))
}

#[derive(Deserialize)]
pub struct NewLink {
    kind: Option<String>,
    peer_server: Option<String>,
    peer_account: Option<String>,
    gen: Option<i64>,
    token: Option<String>,
}

// a group link's token is chosen by the client, which derives it from a secret the account's devices share
pub async fn create_link(State(app): State<Arc<App>>, me: AuthUser, Json(req): Json<NewLink>) -> Result<Json<Value>> {
    let mut v = match req.kind.as_deref() {
        None | Some("contact") => {
            let (server, account) = clean_peer(req.peer_server.as_deref().unwrap_or(""), req.peer_account.as_deref().unwrap_or(""))?;
            new_link(&app, me.id, &server, &account, None, None).await?
        }
        Some("group") => {
            let token = req.token.unwrap_or_default();
            check_b64(&token, 32, "token")?;
            let gen = req.gen.unwrap_or(0);
            if gen < 0 {
                return Err(bad("Invalid token generation."));
            }
            new_link(&app, me.id, "", "", Some((gen, &token)), None).await?
        }
        Some(_) => return Err(bad("Invalid link kind.")),
    };
    v["account"] = json!(me.id.to_string());
    Ok(Json(v))
}

#[derive(Serialize, sqlx::FromRow)]
pub struct LinkRow {
    id: String,
    kind: String,
    peer_server: String,
    peer_account: String,
    created_at: i64,
    last_used: i64,
    state: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    expires_at: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    request: Option<String>,
}

pub async fn my_links(State(app): State<Arc<App>>, me: AuthUser) -> Result<Json<Vec<LinkRow>>> {
    let rows = sqlx::query_as(
        "SELECT id, kind, peer_server, peer_account, created_at, last_used, state, expires_at, request FROM links
         WHERE user_id = ? AND (expires_at IS NULL OR expires_at > ?) ORDER BY created_at",
    )
    .bind(me.id)
    .bind(now())
    .fetch_all(&app.db)
    .await?;
    Ok(Json(rows))
}

// accepting lets the requester read the device list and send its card; the link opens when mail first arrives through it
pub async fn accept_request(State(app): State<Arc<App>>, me: AuthUser, Path(id): Path<String>) -> Result<StatusCode> {
    let state: Option<String> = sqlx::query_scalar(
        "UPDATE links SET state = CASE state WHEN 'pending' THEN 'accepted' ELSE state END
         WHERE id = ? AND user_id = ? AND (expires_at IS NULL OR expires_at > ?) RETURNING state",
    )
    .bind(&id)
    .bind(me.id)
    .bind(now())
    .fetch_optional(&app.db)
    .await?;
    if state.is_none() {
        return Err(AppError::Gone("This request is no longer available.".into()));
    }
    app.hub.send_user(me.id, &Out::Requests);
    Ok(StatusCode::NO_CONTENT)
}

// requests that were not accepted, or whose requester never sent its card, end at their expiry
pub async fn sweep_requests(app: &App) -> anyhow::Result<()> {
    let gone: Vec<(String, i64)> = sqlx::query_as("DELETE FROM links WHERE expires_at <= ? RETURNING id, user_id")
        .bind(now())
        .fetch_all(&app.db)
        .await?;
    for (id, user) in gone {
        app.hub.drop_link(&id);
        app.hub.send_user(user, &Out::LinkRevoked { link_id: id });
    }
    Ok(())
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

#[derive(Deserialize)]
pub struct NewToken {
    gen: i64,
    token: String,
}

// a repeated request with the same generation and token succeeds, so every device of the account can send it
pub async fn set_token(
    State(app): State<Arc<App>>,
    me: AuthUser,
    Path(id): Path<String>,
    Json(req): Json<NewToken>,
) -> Result<StatusCode> {
    check_b64(&req.token, 32, "token")?;
    let th = hash_token(&req.token);
    for _ in 0..2 {
        let row: Option<(i64, String)> =
            sqlx::query_as("SELECT gen, token_hash FROM links WHERE id = ? AND user_id = ? AND kind = 'group'")
                .bind(&id)
                .bind(me.id)
                .fetch_optional(&app.db)
                .await?;
        let (gen, current) = row.ok_or(AppError::NotFound)?;
        if req.gen == gen && th == current {
            return Ok(StatusCode::NO_CONTENT);
        }
        if req.gen <= gen {
            break;
        }
        let done = sqlx::query(
            "UPDATE links SET gen = ?, token_hash = ?, prev_token_hash = token_hash, prev_until = ? WHERE id = ? AND gen = ?",
        )
        .bind(req.gen)
        .bind(&th)
        .bind(now() + GRACE_SECS)
        .bind(&id)
        .bind(gen)
        .execute(&app.db)
        .await?;
        if done.rows_affected() == 1 {
            return Ok(StatusCode::NO_CONTENT);
        }
    }
    Err(AppError::Conflict("The token was already replaced.".into()))
}

pub async fn own_group_link(app: &App, user: i64, id: &str) -> Result<()> {
    let found: Option<String> = sqlx::query_scalar("SELECT id FROM links WHERE id = ? AND user_id = ? AND kind = 'group'")
        .bind(id)
        .bind(user)
        .fetch_optional(&app.db)
        .await?;
    found.map(|_| ()).ok_or(AppError::NotFound)
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
         WHERE l.user_id = ? AND m.expires_at > ? ORDER BY m.created_at, m.rowid",
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
    app.limits.hit(&link.id, "mail", if link.group { 120 } else { 60 })?;
    // a member's attachments stay on its own server
    if link.group && !req.blobs.is_empty() {
        return Err(bad("Attachments are not accepted through a group link."));
    }
    let mail = store_mail(&app, &link.id, link.user_id, req, true).await?;
    if link.state == "accepted" {
        sqlx::query("UPDATE links SET state = 'open', expires_at = NULL, request = NULL WHERE id = ? AND state = 'accepted'")
            .bind(&link.id)
            .execute(&app.db)
            .await?;
    }
    Ok(Json(mail))
}

// no push: the account's other devices catch up from the mailbox
pub async fn own_mail(
    State(app): State<Arc<App>>,
    me: AuthUser,
    Path(id): Path<String>,
    Json(req): Json<NewMail>,
) -> Result<Json<Mail>> {
    own_group_link(&app, me.id, &id).await?;
    app.limits.hit(&id, "own", 120)?;
    Ok(Json(store_mail(&app, &id, me.id, req, false).await?))
}

async fn store_mail(app: &Arc<App>, link_id: &str, user_id: i64, req: NewMail, push: bool) -> Result<Mail> {
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
        .bind(user_id)
        .fetch_all(&app.db)
        .await?;
    if devices.iter().any(|d| !req.keys.iter().any(|k| k.o == "to" && &k.d == d)) {
        return Err(AppError::Stale("The recipient's devices changed.".into()));
    }

    let t = now();
    let keys = json!(req.keys.iter().map(|k| json!({ "d": k.d, "k": k.k, "w": k.w, "o": k.o })).collect::<Vec<_>>());
    let mail = Mail {
        link_id: link_id.to_string(),
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
    blobs::attach(&mut tx, &req.blobs, link_id, &mail.id, mail.expires_at).await?;
    tx.commit().await?;

    app.hub.send_user(user_id, &Out::Mail(mail.clone()));
    if push {
        tokio::spawn(push::send_user(app.clone(), user_id, json!({ "type": "mail" })));
    }
    Ok(mail)
}

// the sender's own copies, still sealed
pub async fn link_mail(State(app): State<Arc<App>>, link: Link) -> Result<Json<Value>> {
    link.contact_only()?;
    let t = now();
    let rows: Vec<Mail> = sqlx::query_as(
        "SELECT link_id, id, from_device, ttl, created_at, expires_at, body, keys, sig
         FROM mail WHERE link_id = ? AND expires_at > ? ORDER BY created_at, rowid",
    )
    .bind(&link.id)
    .bind(t)
    .fetch_all(&app.db)
    .await?;
    Ok(Json(json!({ "now": t, "mail": rows })))
}

pub async fn link_state(State(app): State<Arc<App>>, AnyLink(link): AnyLink) -> Result<Json<Value>> {
    app.limits.hit(&link.id, "state", 60)?;
    Ok(Json(json!({ "state": link.state, "expires_at": link.expires_at })))
}

// the contact removes the link, or a requester withdraws its request; connected devices of the account are notified
pub async fn drop_link(State(app): State<Arc<App>>, AnyLink(link): AnyLink) -> Result<StatusCode> {
    link.contact_only()?;
    sqlx::query("DELETE FROM links WHERE id = ?").bind(&link.id).execute(&app.db).await?;
    app.hub.drop_link(&link.id);
    app.hub.send_user(link.user_id, &Out::LinkRemoved { link_id: link.id });
    Ok(StatusCode::NO_CONTENT)
}
