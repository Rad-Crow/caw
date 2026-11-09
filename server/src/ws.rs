use std::{
    sync::Arc,
    time::{Duration, Instant},
};

use axum::{
    extract::{
        ws::{Message, WebSocket},
        Query, State, WebSocketUpgrade,
    },
    http::{header, HeaderMap},
    response::Response,
};
use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use serde_json::Value;
use tokio::sync::mpsc;

use crate::{
    auth::{now, AuthUser},
    error::{AppError, Result},
    hub::{Out, Who},
    link::Link,
    settings::Settings,
    App,
};

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum In {
    Call { call_id: String },
    Accept { call_id: String },
    Reject { call_id: String },
    Hangup { call_id: String },
    Signal { call_id: String, to: String, data: Value },
    Ping,
}

#[derive(Deserialize)]
pub struct WsQuery {
    client: Option<String>,
}

// this server's own devices: incoming mail, ringing, answering, vault and device list changes
pub async fn handler(
    ws: WebSocketUpgrade,
    State(app): State<Arc<App>>,
    headers: HeaderMap,
    Query(q): Query<WsQuery>,
    user: AuthUser,
) -> Result<Response> {
    let bearer = headers.contains_key(header::AUTHORIZATION);
    if let Some(origin) = headers.get(header::ORIGIN).and_then(|v| v.to_str().ok()) {
        if !bearer && origin != app.cfg.public_url {
            return Err(AppError::Forbidden("Request origin not permitted.".into()));
        }
    }
    let who = Who::Owner { user: user.id };
    Ok(ws
        .max_message_size(64 * 1024)
        .on_upgrade(move |socket| run(socket, app, who, q.client, Some(user.token_hash))))
}

// a contact calling an account on this server; the link is the whole authorization, so no origin check
pub async fn guest(
    ws: WebSocketUpgrade,
    State(app): State<Arc<App>>,
    Query(q): Query<WsQuery>,
    link: Link,
) -> Result<Response> {
    link.contact_only()?;
    let who = Who::Guest {
        link: link.id,
        user: link.user_id,
    };
    Ok(ws
        .max_message_size(64 * 1024)
        .on_upgrade(move |socket| run(socket, app, who, q.client, None)))
}

// stable per-client id; a reconnect with it resumes the same seat
async fn run(socket: WebSocket, app: Arc<App>, who: Who, client: Option<String>, session: Option<String>) {
    let peer_id = client
        .filter(|c| uuid::Uuid::parse_str(c).is_ok())
        .filter(|c| app.hub.may_use(c, &who))
        .unwrap_or_else(|| uuid::Uuid::new_v4().to_string());
    let guest = matches!(who, Who::Guest { .. });
    let link_id = match &who {
        Who::Guest { link, .. } => Some(link.clone()),
        Who::Owner { .. } => None,
    };
    let (tx, mut rx) = mpsc::unbounded_channel();
    let gen = app.hub.connect(&peer_id, who, session.clone(), tx);

    let (mut sink, mut stream) = socket.split();
    let mut ping = tokio::time::interval(Duration::from_secs(25));
    let mut check = tokio::time::interval(Duration::from_secs(60));
    let mut last_rx = Instant::now();
    loop {
        tokio::select! {
            out = rx.recv() => {
                let Some(out) = out else { break };
                let Ok(txt) = serde_json::to_string(&out) else { continue };
                if sink.send(Message::Text(txt.into())).await.is_err() {
                    break;
                }
            }
            msg = stream.next() => match msg {
                Some(Ok(Message::Text(t))) => {
                    last_rx = Instant::now();
                    handle(&app, &peer_id, guest, link_id.as_deref(), &t).await;
                }
                Some(Ok(Message::Close(_))) | Some(Err(_)) | None => break,
                Some(Ok(_)) => last_rx = Instant::now(),
            },
            _ = check.tick() => {
                let alive = match (&session, &link_id) {
                    (Some(s), _) => still_signed_in(&app, s).await,
                    (None, Some(l)) => link_alive(&app, l).await,
                    _ => false,
                };
                if !alive {
                    break;
                }
            }
            _ = ping.tick() => {
                if last_rx.elapsed() > Duration::from_secs(70)
                    || sink.send(Message::Ping(Default::default())).await.is_err()
                {
                    break;
                }
            }
        }
    }
    app.hub.disconnect(&peer_id, gen);
}

// refreshes the device's last_seen; false once the session is gone (device expired or removed)
async fn still_signed_in(app: &App, token_hash: &str) -> bool {
    let res = async {
        let device: Option<Option<String>> = sqlx::query_scalar("SELECT device_id FROM sessions WHERE token_hash = ?")
            .bind(token_hash)
            .fetch_optional(&app.db)
            .await?;
        let Some(device) = device else { return anyhow::Ok(false) };
        if let Some(d) = device {
            sqlx::query("UPDATE devices SET last_seen = ? WHERE id = ?").bind(now()).bind(d).execute(&app.db).await?;
        }
        Ok(true)
    }
    .await;
    res.unwrap_or(true)
}

async fn link_alive(app: &App, link_id: &str) -> bool {
    sqlx::query_scalar::<_, i64>("SELECT 1 FROM links WHERE id = ?")
        .bind(link_id)
        .fetch_optional(&app.db)
        .await
        .map(|r| r.is_some())
        .unwrap_or(true)
}

async fn handle(app: &App, peer_id: &str, guest: bool, link_id: Option<&str>, raw: &str) {
    let msg: In = match serde_json::from_str(raw) {
        Ok(m) => m,
        Err(e) => return err(app, peer_id, None, &format!("Malformed message: {e}")),
    };
    let res = match msg {
        In::Ping => {
            app.hub.send_peer(peer_id, Out::Pong);
            Ok(())
        }
        In::Call { call_id } if guest => start_call(app, peer_id, link_id.unwrap_or_default(), &call_id)
            .await
            .map_err(|e| (Some(call_id), e)),
        In::Call { call_id } => Err((Some(call_id), "Calls are started through a contact link.".into())),
        In::Accept { call_id } => app.hub.accept(peer_id, &call_id).map_err(|e| (Some(call_id), e.to_string())),
        In::Reject { call_id } => {
            app.hub.reject(peer_id, &call_id);
            Ok(())
        }
        In::Hangup { call_id } => {
            app.hub.hangup(peer_id, &call_id);
            Ok(())
        }
        In::Signal { call_id, to, data } => app
            .hub
            .signal(peer_id, &call_id, &to, data)
            .map_err(|e| (Some(call_id), e.to_string())),
    };
    if let Err((call_id, e)) = res {
        err(app, peer_id, call_id, &e);
    }
}

async fn start_call(app: &App, peer_id: &str, link_id: &str, call_id: &str) -> std::result::Result<(), String> {
    if uuid::Uuid::parse_str(call_id).is_err() {
        return Err("Call ID must be a UUID.".into());
    }
    app.limits.hit(link_id, "call", 10).map_err(|_| "Too many calls. Try again later.".to_string())?;
    let settings = Settings::load(&app.db).await.map_err(|e| e.to_string())?;
    app.hub.start_call(peer_id, call_id, settings.ring_timeout_secs).map_err(|e| e.to_string())
}

fn err(app: &App, peer_id: &str, call_id: Option<String>, message: &str) {
    app.hub.send_peer(
        peer_id,
        Out::Error {
            message: message.to_string(),
            call_id,
        },
    );
}
