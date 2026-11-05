mod api;
mod auth;
mod config;
mod error;
mod hub;
mod link;
mod push;
mod secret;
mod settings;
mod webpush;
mod ws;

use std::{str::FromStr, sync::Arc, time::Duration};

use sqlx::{
    sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePoolOptions},
    SqlitePool,
};
use tokio::sync::mpsc;
use tower_http::trace::TraceLayer;
use tracing_subscriber::EnvFilter;

use crate::{
    auth::now,
    config::Config,
    hub::{Effect, Hub},
    settings::Settings,
};

pub struct App {
    pub db: SqlitePool,
    pub cfg: Config,
    pub hub: Hub,
    pub http: reqwest::Client,
    pub vapid: webpush::Vapid,
    pub limits: link::Limiter,
    pub secret: secret::Secret,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| "caw=info,tower_http=warn".into()))
        .init();

    let cfg = Config::from_env()?;
    let opts = SqliteConnectOptions::from_str(&format!("sqlite://{}", cfg.db))?
        .create_if_missing(true)
        .journal_mode(SqliteJournalMode::Wal)
        .foreign_keys(true)
        .pragma("secure_delete", "on")
        .busy_timeout(Duration::from_secs(5));
    let db = SqlitePoolOptions::new().max_connections(8).connect_with(opts).await?;
    sqlx::migrate!().run(&db).await?;

    let vapid = load_vapid(&db).await?;
    let (fx_tx, fx_rx) = mpsc::unbounded_channel();
    let app = Arc::new(App {
        db,
        cfg: cfg.clone(),
        hub: Hub::new(fx_tx),
        http: reqwest::Client::builder()
            .timeout(Duration::from_secs(10))
            .redirect(reqwest::redirect::Policy::none())
            .build()?,
        vapid,
        limits: link::Limiter::default(),
        secret: secret::Secret::load(&cfg.db)?,
    });

    startup(&app).await?;
    tokio::spawn(effects(app.clone(), fx_rx));
    tokio::spawn(sweeper(app.clone()));

    let router = api::router(app.clone()).layer(TraceLayer::new_for_http());
    let listener = tokio::net::TcpListener::bind(cfg.bind).await?;
    tracing::info!("listening on {}", cfg.bind);
    axum::serve(listener, router)
        .with_graceful_shutdown(async {
            let _ = tokio::signal::ctrl_c().await;
        })
        .await?;
    Ok(())
}

async fn load_vapid(db: &SqlitePool) -> anyhow::Result<webpush::Vapid> {
    let stored: Option<String> = sqlx::query_scalar("SELECT value FROM settings WHERE key = 'vapid_secret'")
        .fetch_optional(db)
        .await?;
    if let Some(s) = stored {
        return webpush::Vapid::from_secret(&webpush::b64(&s)?);
    }
    let (vapid, secret) = webpush::Vapid::generate();
    sqlx::query("INSERT INTO settings (key, value) VALUES ('vapid_secret', ?)")
        .bind(secret)
        .execute(db)
        .await?;
    Ok(vapid)
}

async fn startup(app: &App) -> anyhow::Result<()> {
    let t = now();
    sqlx::query("DELETE FROM sessions WHERE last_seen < ?")
        .bind(t - 90 * 86400)
        .execute(&app.db)
        .await?;

    let admins: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM users WHERE is_admin = 1 AND disabled = 0")
        .fetch_one(&app.db)
        .await?;
    if admins > 0 {
        return Ok(());
    }
    let existing: Option<String> = sqlx::query_scalar(
        "SELECT token FROM invites WHERE note = 'setup' AND is_admin = 1 AND revoked = 0 AND uses < max_uses",
    )
    .fetch_optional(&app.db)
    .await?;
    let token = match existing {
        Some(t) => t,
        None => {
            let token = auth::random_token();
            sqlx::query("INSERT INTO invites (token, note, is_admin, max_uses, created_at) VALUES (?, 'setup', 1, 1, ?)")
                .bind(&token)
                .bind(t)
                .execute(&app.db)
                .await?;
            token
        }
    };
    tracing::warn!("no admin yet - create one at {}/invite/{}", app.cfg.public_url, token);
    Ok(())
}

// removes expired messages, attachments, pairing codes and stale devices; checkpoints the wal so deleted pages don't persist
async fn sweeper(app: Arc<App>) {
    let mut tick = tokio::time::interval(Duration::from_secs(60));
    loop {
        tick.tick().await;
        let t = now();
        let res = async {
            sqlx::query("DELETE FROM mail WHERE expires_at <= ?").bind(t).execute(&app.db).await?;
            sqlx::query("DELETE FROM pairings WHERE expires_at <= ?").bind(t).execute(&app.db).await?;
            let s = Settings::load(&app.db).await?;
            sqlx::query("DELETE FROM device_keys WHERE created_at < ? AND enc_pub NOT IN (SELECT enc_pub FROM devices WHERE enc_pub IS NOT NULL)")
                .bind(t - s.message_ttl_max - 2 * 86400)
                .execute(&app.db)
                .await?;
            // expired devices are signed out; the next sign-in enrolls a new device
            let cutoff = t - s.device_expiry_days * 86400;
            let gone: Vec<(String, i64)> = sqlx::query_as("DELETE FROM devices WHERE last_seen < ? RETURNING id, user_id")
                .bind(cutoff)
                .fetch_all(&app.db)
                .await?;
            for (id, user) in &gone {
                let sessions: Vec<String> = sqlx::query_scalar("DELETE FROM sessions WHERE device_id = ? RETURNING token_hash")
                    .bind(id)
                    .fetch_all(&app.db)
                    .await?;
                app.hub.kick_sessions(&sessions);
                app.hub.send_user(*user, &hub::Out::Devices);
            }
            api::sweep_blobs(&app).await?;
            sqlx::query("PRAGMA wal_checkpoint(TRUNCATE)").execute(&app.db).await?;
            anyhow::Ok(())
        }
        .await;
        if let Err(e) = res {
            tracing::error!("sweeper: {e:#}");
        }
    }
}

async fn effects(app: Arc<App>, mut rx: mpsc::UnboundedReceiver<Effect>) {
    while let Some(e) = rx.recv().await {
        if let Err(err) = effect(&app, e).await {
            tracing::error!("effect failed: {err:#}");
        }
    }
}

async fn effect(app: &Arc<App>, e: Effect) -> anyhow::Result<()> {
    match e {
        Effect::Push { user_id, payload } => {
            tokio::spawn(push::send_user(app.clone(), user_id, payload));
        }
        Effect::Grace { peer_id, gen } => {
            let app = app.clone();
            tokio::spawn(async move {
                tokio::time::sleep(Duration::from_secs(hub::GRACE_SECS)).await;
                app.hub.grace_over(&peer_id, gen);
            });
        }
        Effect::RingTimer { call_id, secs } => {
            let app = app.clone();
            tokio::spawn(async move {
                tokio::time::sleep(Duration::from_secs(secs)).await;
                app.hub.ring_timeout(&call_id);
            });
        }
    }
    Ok(())
}
