use std::{str::FromStr, sync::Arc};

use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use reqwest::{Method, StatusCode};
use serde_json::{json, Value};
use sqlx::sqlite::{SqliteConnectOptions, SqlitePoolOptions};

use crate::{
    api,
    auth::{hash_token, now, random_token},
    config::Config,
    hub::Hub,
    link, secret, webpush, App,
};

struct Server {
    base: String,
    http: reqwest::Client,
    app: Arc<App>,
    dir: std::path::PathBuf,
}

impl Drop for Server {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

async fn server() -> Server {
    let dir = std::env::temp_dir().join(format!("caw-test-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&dir).unwrap();
    let db_path = dir.join("caw.db").to_string_lossy().to_string();
    let opts = SqliteConnectOptions::from_str("sqlite::memory:").unwrap().foreign_keys(true);
    let db = SqlitePoolOptions::new().max_connections(1).connect_with(opts).await.unwrap();
    sqlx::migrate!().run(&db).await.unwrap();
    let cfg = Config {
        bind: "127.0.0.1:0".parse().unwrap(),
        db: db_path.clone(),
        blob_dir: dir.join("blobs").to_string_lossy().to_string(),
        static_dir: dir.to_string_lossy().to_string(),
        public_url: "http://localhost".into(),
        turn_secret: None,
        turn_urls: vec![],
        stun_urls: vec![],
        turn_ttl: 3600,
        push_allow_private: false,
        push_allowed_hosts: vec![],
    };
    let app = Arc::new(App {
        db,
        cfg,
        hub: Hub::new(tokio::sync::mpsc::unbounded_channel().0),
        http: reqwest::Client::new(),
        vapid: webpush::Vapid::generate().0,
        limits: link::Limiter::default(),
        secret: secret::Secret::load(&db_path).unwrap(),
    });
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    let router = api::router(app.clone());
    tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
    Server { base, http: reqwest::Client::new(), app, dir }
}

// an account with one device that has an encryption key; returns its session token and device id
async fn account(s: &Server, name: &str) -> (String, String) {
    let t = now();
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO users (username, display_name, password_hash, salt, created_at) VALUES (?, ?, '', '', ?) RETURNING id",
    )
    .bind(name)
    .bind(name)
    .bind(t)
    .fetch_one(&s.app.db)
    .await
    .unwrap();
    let token = random_token();
    let device = uuid::Uuid::new_v4().to_string();
    sqlx::query("INSERT INTO sessions (token_hash, user_id, device_id, created_at, last_seen) VALUES (?, ?, ?, ?, ?)")
        .bind(hash_token(&token))
        .bind(id)
        .bind(&device)
        .bind(t)
        .bind(t)
        .execute(&s.app.db)
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO devices (id, user_id, sign_pub, enc_pub, enc_created_at, enc_sig, created_at, last_seen)
         VALUES (?, ?, 'x', 'x', ?, 'x', ?, ?)",
    )
    .bind(&device)
    .bind(id)
    .bind(t)
    .bind(t)
    .bind(t)
    .execute(&s.app.db)
    .await
    .unwrap();
    (token, device)
}

fn b64(len: usize) -> String {
    URL_SAFE_NO_PAD.encode(vec![7u8; len])
}

fn mail(device: &str, blobs: &[&str]) -> Value {
    json!({
        "id": uuid::Uuid::new_v4().to_string(),
        "from_device": "sender",
        "ttl": 3600,
        "body": "sealed",
        "keys": [{ "d": device, "k": b64(32), "w": b64(64), "o": "to" }],
        "sig": b64(64),
        "blobs": blobs,
    })
}

enum Auth<'a> {
    Session(&'a str),
    Link(&'a str, &'a str),
}

async fn req(s: &Server, method: Method, path: &str, auth: Auth<'_>, body: Option<Value>) -> (StatusCode, Value) {
    let mut r = s.http.request(method, format!("{}{path}", s.base));
    r = match auth {
        Auth::Session(t) => r.header("authorization", format!("Bearer {t}")),
        Auth::Link(id, t) => r.header("authorization", format!("Link {id}.{t}")),
    };
    if let Some(b) = body {
        r = r.header("content-type", "application/json").body(b.to_string());
    }
    let resp = r.send().await.unwrap();
    let status = resp.status();
    let bytes = resp.bytes().await.unwrap();
    (status, serde_json::from_slice(&bytes).unwrap_or(Value::Null))
}

async fn group_link(s: &Server, session: &str, token: &str) -> String {
    let (st, v) = req(s, Method::POST, "/api/links", Auth::Session(session), Some(json!({ "kind": "group", "gen": 0, "token": token }))).await;
    assert_eq!(st, StatusCode::OK);
    assert_eq!(v["token"], token);
    v["link_id"].as_str().unwrap().to_string()
}

#[tokio::test]
async fn group_token_replacement() {
    let s = server().await;
    let (alice, device) = account(&s, "alice").await;
    let t0 = random_token();
    let id = group_link(&s, &alice, &t0).await;

    let (st, _) = req(&s, Method::POST, "/api/link/mail", Auth::Link(&id, &t0), Some(mail(&device, &[]))).await;
    assert_eq!(st, StatusCode::OK);

    let t1 = random_token();
    let path = format!("/api/links/{id}/token");
    let put = |gen: i64, token: &str| json!({ "gen": gen, "token": token });
    assert_eq!(req(&s, Method::PUT, &path, Auth::Session(&alice), Some(put(1, &t1))).await.0, StatusCode::NO_CONTENT);
    assert_eq!(req(&s, Method::PUT, &path, Auth::Session(&alice), Some(put(1, &t1))).await.0, StatusCode::NO_CONTENT);
    assert_eq!(req(&s, Method::PUT, &path, Auth::Session(&alice), Some(put(1, &random_token()))).await.0, StatusCode::CONFLICT);
    assert_eq!(req(&s, Method::PUT, &path, Auth::Session(&alice), Some(put(0, &t0))).await.0, StatusCode::CONFLICT);

    // the previous token keeps working during the grace period
    for t in [&t0, &t1] {
        let (st, _) = req(&s, Method::POST, "/api/link/mail", Auth::Link(&id, t), Some(mail(&device, &[]))).await;
        assert_eq!(st, StatusCode::OK);
    }
    sqlx::query("UPDATE links SET prev_until = ? WHERE id = ?").bind(now() - 1).bind(&id).execute(&s.app.db).await.unwrap();
    let (st, _) = req(&s, Method::POST, "/api/link/mail", Auth::Link(&id, &t0), Some(mail(&device, &[]))).await;
    assert_eq!(st, StatusCode::UNAUTHORIZED);
    let (st, _) = req(&s, Method::POST, "/api/link/mail", Auth::Link(&id, &t1), Some(mail(&device, &[]))).await;
    assert_eq!(st, StatusCode::OK);

    let (_, v) = req(&s, Method::GET, "/api/mail", Auth::Session(&alice), None).await;
    assert_eq!(v["mail"].as_array().unwrap().len(), 4);

    let (other, _) = account(&s, "bob").await;
    assert_eq!(req(&s, Method::PUT, &path, Auth::Session(&other), Some(put(2, &random_token()))).await.0, StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn group_link_refusals() {
    let s = server().await;
    let (alice, device) = account(&s, "alice").await;
    let token = random_token();
    let id = group_link(&s, &alice, &token).await;
    let link = Auth::Link(&id, &token);

    assert_eq!(req(&s, Method::DELETE, "/api/link", link, None).await.0, StatusCode::FORBIDDEN);
    assert_eq!(req(&s, Method::GET, "/api/link/mail", Auth::Link(&id, &token), None).await.0, StatusCode::FORBIDDEN);
    let (st, _) = req(&s, Method::POST, "/api/link/mail", Auth::Link(&id, &token), Some(mail(&device, &[&uuid::Uuid::new_v4().to_string()]))).await;
    assert_eq!(st, StatusCode::BAD_REQUEST);
    let st = s
        .http
        .post(format!("{}/api/link/blobs", s.base))
        .header("authorization", format!("Link {id}.{token}"))
        .body(vec![1u8; 16])
        .send()
        .await
        .unwrap()
        .status();
    assert_eq!(st, StatusCode::FORBIDDEN);
    let st = s
        .http
        .get(format!("{}/api/link/ws?client={}", s.base, uuid::Uuid::new_v4()))
        .header("authorization", format!("Link {id}.{token}"))
        .header("connection", "upgrade")
        .header("upgrade", "websocket")
        .header("sec-websocket-version", "13")
        .header("sec-websocket-key", "dGhlIHNhbXBsZSBub25jZQ==")
        .send()
        .await
        .unwrap()
        .status();
    assert_eq!(st, StatusCode::FORBIDDEN);

    let (st, v) = req(&s, Method::POST, "/api/links", Auth::Session(&alice), Some(json!({ "peer_server": "http://peer.example", "peer_account": "7" }))).await;
    assert_eq!(st, StatusCode::OK);
    let (cid, ctoken) = (v["link_id"].as_str().unwrap(), v["token"].as_str().unwrap());
    assert_eq!(req(&s, Method::DELETE, "/api/link", Auth::Link(cid, ctoken), None).await.0, StatusCode::NO_CONTENT);

    let (_, links) = req(&s, Method::GET, "/api/links", Auth::Session(&alice), None).await;
    assert_eq!(links[0]["kind"], "group");
    let (_, config) = req(&s, Method::GET, "/api/config", Auth::Session(&alice), None).await;
    assert_eq!(config["max_group_members"], 10);
}

#[tokio::test]
async fn group_owner_copy_and_attachment() {
    let s = server().await;
    let (alice, device) = account(&s, "alice").await;
    let token = random_token();
    let id = group_link(&s, &alice, &token).await;

    let up = s
        .http
        .post(format!("{}/api/links/{id}/blobs", s.base))
        .header("authorization", format!("Bearer {alice}"))
        .body(vec![9u8; 100])
        .send()
        .await
        .unwrap();
    assert_eq!(up.status(), StatusCode::OK);
    let blob = serde_json::from_slice::<Value>(&up.bytes().await.unwrap()).unwrap()["id"].as_str().unwrap().to_string();

    let (st, _) = req(&s, Method::POST, &format!("/api/links/{id}/mail"), Auth::Session(&alice), Some(mail(&device, &[&blob]))).await;
    assert_eq!(st, StatusCode::OK);
    let (st, _) = req(&s, Method::POST, &format!("/api/links/{id}/mail"), Auth::Session(&alice), Some(mail("someone-else", &[]))).await;
    assert_eq!(st, StatusCode::PRECONDITION_FAILED);

    let own = s.http.get(format!("{}/api/blobs/{blob}", s.base)).header("authorization", format!("Bearer {alice}")).send().await.unwrap();
    assert_eq!(own.status(), StatusCode::OK);
    let member = s
        .http
        .get(format!("{}/api/link/blobs/{blob}", s.base))
        .header("authorization", format!("Link {id}.{token}"))
        .send()
        .await
        .unwrap();
    assert_eq!(member.status(), StatusCode::OK);
    assert_eq!(member.bytes().await.unwrap().len(), 100);

    let (bob, _) = account(&s, "bob").await;
    let (st, _) = req(&s, Method::POST, &format!("/api/links/{id}/mail"), Auth::Session(&bob), Some(mail(&device, &[]))).await;
    assert_eq!(st, StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn push_endpoint_per_session() {
    let s = server().await;
    let (a, _) = account(&s, "alice").await;
    let uid: i64 = sqlx::query_scalar("SELECT user_id FROM sessions WHERE token_hash = ?")
        .bind(hash_token(&a))
        .fetch_one(&s.app.db)
        .await
        .unwrap();
    let a2 = random_token();
    sqlx::query("INSERT INTO sessions (token_hash, user_id, created_at, last_seen) VALUES (?, ?, ?, ?)")
        .bind(hash_token(&a2))
        .bind(uid)
        .bind(now())
        .bind(now())
        .execute(&s.app.db)
        .await
        .unwrap();
    for (session, ep) in [(&a, "https://1.1.1.1/one"), (&a2, "https://1.1.1.1/other"), (&a, "https://1.1.1.1/two")] {
        let body = json!({ "endpoint": ep, "p256dh": s.app.vapid.public, "auth": b64(16) });
        let (st, _) = req(&s, Method::POST, "/api/push", Auth::Session(session), Some(body)).await;
        assert_eq!(st, StatusCode::NO_CONTENT);
    }
    let left: Vec<String> = sqlx::query_scalar("SELECT endpoint FROM push_endpoints ORDER BY endpoint")
        .fetch_all(&s.app.db)
        .await
        .unwrap();
    assert_eq!(left, ["https://1.1.1.1/other", "https://1.1.1.1/two"]);
}

#[tokio::test]
async fn link_responses_are_not_cached() {
    let s = server().await;
    let (alice, _) = account(&s, "alice").await;
    let (st, v) = req(&s, Method::POST, "/api/links", Auth::Session(&alice), Some(json!({ "peer_server": "https://caw.example.com", "peer_account": "7" }))).await;
    assert_eq!(st, StatusCode::OK);
    let (id, token) = (v["link_id"].as_str().unwrap(), v["token"].as_str().unwrap());
    let devices = || s.http.get(format!("{}/api/link/devices", s.base)).header("authorization", format!("Link {id}.{token}")).send();

    let resp = devices().await.unwrap();
    assert_eq!(resp.status(), StatusCode::OK);
    assert_eq!(resp.headers()["cache-control"], "no-store");
    assert_eq!(req(&s, Method::DELETE, &format!("/api/links/{id}"), Auth::Session(&alice), None).await.0, StatusCode::NO_CONTENT);
    let resp = devices().await.unwrap();
    assert_eq!(resp.status(), StatusCode::GONE);
    assert_eq!(resp.headers()["cache-control"], "no-store");
}

async fn request(s: &Server, owner: &str, wait: i64) -> (String, String, i64) {
    let (st, v) = req(s, Method::POST, "/api/pairings", Auth::Session(owner), None).await;
    assert_eq!(st, StatusCode::OK);
    let body = json!({ "ticket": v["ticket"], "wait": wait, "request": b64(80) });
    let (st, v) = req(s, Method::POST, "/api/pairings/redeem", Auth::Session(""), Some(body)).await;
    assert_eq!(st, StatusCode::OK);
    (v["link_id"].as_str().unwrap().to_string(), v["token"].as_str().unwrap().to_string(), v["expires_at"].as_i64().unwrap())
}

#[tokio::test]
async fn pairing_request_states() {
    let s = server().await;
    let (alice, device) = account(&s, "alice").await;
    let (id, token, expires_at) = request(&s, &alice, 30 * 86400).await;
    assert!((expires_at - now() - 24 * 3600).abs() < 5, "capped at the server's maximum");
    let link = || Auth::Link(&id, &token);

    let (st, v) = req(&s, Method::GET, "/api/link", link(), None).await;
    assert_eq!((st, v["state"].as_str()), (StatusCode::OK, Some("pending")));
    for (m, path) in [(Method::GET, "/api/link/devices"), (Method::GET, "/api/link/mail")] {
        assert_eq!(req(&s, m, path, link(), None).await.0, StatusCode::FORBIDDEN, "{path} while pending");
    }
    assert_eq!(req(&s, Method::POST, "/api/link/mail", link(), Some(mail(&device, &[]))).await.0, StatusCode::FORBIDDEN);
    let (_, links) = req(&s, Method::GET, "/api/links", Auth::Session(&alice), None).await;
    let l = &links.as_array().unwrap()[0];
    assert_eq!((l["state"].as_str(), l["request"].as_str(), l["peer_server"].as_str()), (Some("pending"), Some(b64(80).as_str()), Some("")));

    assert_eq!(req(&s, Method::POST, &format!("/api/links/{id}/accept"), Auth::Session(&alice), None).await.0, StatusCode::NO_CONTENT);
    assert_eq!(req(&s, Method::GET, "/api/link", link(), None).await.1["state"], "accepted");
    assert_eq!(req(&s, Method::GET, "/api/link/devices", link(), None).await.0, StatusCode::OK);
    assert_eq!(req(&s, Method::POST, "/api/link/mail", link(), Some(mail(&device, &[]))).await.0, StatusCode::OK);
    let (_, v) = req(&s, Method::GET, "/api/link", link(), None).await;
    assert_eq!((v["state"].as_str(), v["expires_at"].as_i64()), (Some("open"), None));
    let (_, links) = req(&s, Method::GET, "/api/links", Auth::Session(&alice), None).await;
    assert!(links[0].get("request").is_none());
}

#[tokio::test]
async fn pairing_request_withdrawal_and_expiry() {
    let s = server().await;
    let (alice, _) = account(&s, "alice").await;
    let (id, token, expires_at) = request(&s, &alice, 1).await;
    assert!((expires_at - now() - 60).abs() < 5, "at least a minute");
    assert_eq!(req(&s, Method::DELETE, "/api/link", Auth::Link(&id, &token), None).await.0, StatusCode::NO_CONTENT);
    assert_eq!(req(&s, Method::GET, "/api/link", Auth::Link(&id, &token), None).await.0, StatusCode::GONE);

    let (id, token, _) = request(&s, &alice, 3600).await;
    sqlx::query("UPDATE links SET expires_at = ? WHERE id = ?").bind(now() - 1).bind(&id).execute(&s.app.db).await.unwrap();
    assert_eq!(req(&s, Method::GET, "/api/link", Auth::Link(&id, &token), None).await.0, StatusCode::GONE);
    assert_eq!(req(&s, Method::POST, &format!("/api/links/{id}/accept"), Auth::Session(&alice), None).await.0, StatusCode::GONE);
    api::sweep_links(&s.app).await.unwrap();
    let left: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM links WHERE id = ?").bind(&id).fetch_one(&s.app.db).await.unwrap();
    assert_eq!(left, 0);
}

#[tokio::test]
async fn closed_group_link() {
    let s = server().await;
    let (alice, device) = account(&s, "alice").await;
    let token = random_token();
    let id = group_link(&s, &alice, &token).await;
    let link = || Auth::Link(&id, &token);
    assert_eq!(req(&s, Method::POST, "/api/link/mail", link(), Some(mail(&device, &[]))).await.0, StatusCode::OK);
    let up = s.http.post(format!("{}/api/links/{id}/blobs", s.base)).header("authorization", format!("Bearer {alice}")).body(vec![1u8; 16]).send().await.unwrap();
    assert_eq!(up.status(), StatusCode::OK);

    assert_eq!(req(&s, Method::POST, &format!("/api/links/{id}/close"), Auth::Session(&alice), None).await.0, StatusCode::NO_CONTENT);
    assert_eq!(req(&s, Method::GET, "/api/link", link(), None).await.1["state"], "closed");
    assert_eq!(req(&s, Method::GET, "/api/link/devices", link(), None).await.0, StatusCode::OK);
    assert_eq!(req(&s, Method::POST, "/api/link/mail", link(), Some(mail(&device, &[]))).await.0, StatusCode::FORBIDDEN);
    assert_eq!(req(&s, Method::POST, &format!("/api/links/{id}/mail"), Auth::Session(&alice), Some(mail(&device, &[]))).await.0, StatusCode::NOT_FOUND);
    for table in ["mail", "blobs"] {
        let n: i64 = sqlx::query_scalar(&format!("SELECT COUNT(*) FROM {table} WHERE link_id = ?")).bind(&id).fetch_one(&s.app.db).await.unwrap();
        assert_eq!(n, 0, "{table} kept after closing");
    }
    assert!(req(&s, Method::GET, "/api/links", Auth::Session(&alice), None).await.1.as_array().unwrap().is_empty());

    sqlx::query("UPDATE links SET expires_at = ? WHERE id = ?").bind(now() - 1).bind(&id).execute(&s.app.db).await.unwrap();
    assert_eq!(req(&s, Method::GET, "/api/link/devices", link(), None).await.0, StatusCode::GONE);
    api::sweep_links(&s.app).await.unwrap();
    let n: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM links WHERE id = ?").bind(&id).fetch_one(&s.app.db).await.unwrap();
    assert_eq!(n, 0);
}
