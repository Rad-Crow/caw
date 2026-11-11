use std::{
    collections::HashSet,
    path::PathBuf,
    sync::Arc,
    time::{Duration, SystemTime},
};

use axum::{
    body::Body,
    extract::{Path, State},
    http::header,
    response::IntoResponse,
    Json,
};
use futures_util::StreamExt;
use serde_json::{json, Value};
use tokio::io::AsyncWriteExt;
use tokio_util::io::ReaderStream;

use crate::{
    api::contacts::own_group_link,
    auth::{now, AuthUser},
    error::{bad, AppError, Result},
    link::Link,
    settings::Settings,
    App,
};

// unsent uploads are dropped after this
const PENDING_SECS: i64 = 3600;
const MAX_PENDING: i64 = 20;
// clients wrap the file in a nonce and gcm tag
const OVERHEAD: u64 = 64;

pub fn path(app: &App, id: &str) -> PathBuf {
    std::path::Path::new(&app.cfg.blob_dir).join(id)
}

fn check_id(id: &str) -> Result<()> {
    uuid::Uuid::parse_str(id).map(|_| ()).map_err(|_| AppError::NotFound)
}

// uploaded by the sender, through the link, to the recipient's server
pub async fn upload(State(app): State<Arc<App>>, link: Link, body: Body) -> Result<Json<Value>> {
    link.contact_only()?;
    store(&app, &link.id, body).await
}

pub async fn own_upload(State(app): State<Arc<App>>, me: AuthUser, Path(id): Path<String>, body: Body) -> Result<Json<Value>> {
    own_group_link(&app, me.id, &id).await?;
    store(&app, &id, body).await
}

async fn store(app: &App, link_id: &str, body: Body) -> Result<Json<Value>> {
    app.limits.hit(link_id, "blob", 20)?;
    let s = Settings::load(&app.db).await?;
    let limit = s.max_upload_mb * 1024 * 1024 + OVERHEAD;
    let pending: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM blobs WHERE link_id = ? AND mail_id IS NULL")
        .bind(link_id)
        .fetch_one(&app.db)
        .await?;
    if pending >= MAX_PENDING {
        return Err(AppError::Conflict("Too many unsent attachments. Try again later.".into()));
    }

    tokio::fs::create_dir_all(&app.cfg.blob_dir).await?;
    let id = uuid::Uuid::new_v4().to_string();
    let part = path(app, &format!("{id}.part"));
    let mut size = 0u64;
    let res = async {
        let mut f = tokio::fs::File::create(&part).await?;
        let mut stream = body.into_data_stream();
        while let Some(chunk) = stream.next().await {
            let chunk = chunk.map_err(|_| bad("Upload interrupted."))?;
            size += chunk.len() as u64;
            if size > limit {
                return Err(bad(format!("Attachment exceeds the {} MB limit.", s.max_upload_mb)));
            }
            f.write_all(&chunk).await?;
        }
        f.sync_all().await?;
        if size == 0 {
            return Err(bad("Empty attachment."));
        }
        Ok(())
    }
    .await;
    if let Err(e) = res {
        let _ = tokio::fs::remove_file(&part).await;
        return Err(e);
    }

    let t = now();
    sqlx::query("INSERT INTO blobs (id, link_id, size, created_at, expires_at) VALUES (?, ?, ?, ?, ?)")
        .bind(&id)
        .bind(link_id)
        .bind(size as i64)
        .bind(t)
        .bind(t + PENDING_SECS)
        .execute(&app.db)
        .await?;
    tokio::fs::rename(&part, path(app, &id)).await?;
    Ok(Json(json!({ "id": id })))
}

// the recipient, as the owner of the linked device
pub async fn download(State(app): State<Arc<App>>, me: AuthUser, Path(id): Path<String>) -> Result<impl IntoResponse> {
    check_id(&id)?;
    let size: Option<i64> = sqlx::query_scalar(
        "SELECT b.size FROM blobs b JOIN links l ON l.id = b.link_id
         WHERE b.id = ? AND l.user_id = ? AND b.mail_id IS NOT NULL AND b.expires_at > ?",
    )
    .bind(&id)
    .bind(me.id)
    .bind(now())
    .fetch_optional(&app.db)
    .await?;
    serve(&app, &id, size).await
}

// the sender, through the same link it uploaded with
pub async fn link_download(State(app): State<Arc<App>>, link: Link, Path(id): Path<String>) -> Result<impl IntoResponse> {
    check_id(&id)?;
    let size: Option<i64> = sqlx::query_scalar("SELECT size FROM blobs WHERE id = ? AND link_id = ? AND expires_at > ?")
        .bind(&id)
        .bind(&link.id)
        .bind(now())
        .fetch_optional(&app.db)
        .await?;
    serve(&app, &id, size).await
}

async fn serve(app: &App, id: &str, size: Option<i64>) -> Result<impl IntoResponse> {
    let size = size.ok_or(AppError::NotFound)?;
    let f = tokio::fs::File::open(path(app, id)).await.map_err(|_| AppError::NotFound)?;
    Ok((
        [
            (header::CONTENT_TYPE, "application/octet-stream".to_string()),
            (header::CONTENT_LENGTH, size.to_string()),
            (header::CACHE_CONTROL, "private, no-store".to_string()),
        ],
        Body::from_stream(ReaderStream::new(f)),
    ))
}

// ties uploads to the message so they expire with it
pub async fn attach(tx: &mut sqlx::SqliteConnection, ids: &[String], link_id: &str, mail_id: &str, expires_at: i64) -> Result<()> {
    for id in ids {
        check_id(id).map_err(|_| bad("Attachment not found."))?;
        let res = sqlx::query("UPDATE blobs SET mail_id = ?, expires_at = ? WHERE id = ? AND link_id = ? AND mail_id IS NULL")
        .bind(mail_id)
        .bind(expires_at)
        .bind(id)
        .bind(link_id)
        .execute(&mut *tx)
        .await?;
        if res.rows_affected() != 1 {
            return Err(bad("Attachment not found."));
        }
    }
    Ok(())
}

pub async fn sweep(app: &App) -> anyhow::Result<()> {
    let gone: Vec<String> = sqlx::query_scalar("DELETE FROM blobs WHERE expires_at <= ? RETURNING id")
        .bind(now())
        .fetch_all(&app.db)
        .await?;
    for id in gone {
        let _ = tokio::fs::remove_file(path(app, &id)).await;
    }

    // files left behind by removed links or a crash mid-upload
    let Ok(mut dir) = tokio::fs::read_dir(&app.cfg.blob_dir).await else {
        return Ok(());
    };
    let known: HashSet<String> = sqlx::query_scalar::<_, String>("SELECT id FROM blobs")
        .fetch_all(&app.db)
        .await?
        .into_iter()
        .collect();
    let stale = SystemTime::now() - Duration::from_secs(PENDING_SECS as u64);
    while let Some(e) = dir.next_entry().await? {
        let name = e.file_name().to_string_lossy().into_owned();
        let old = e.metadata().await.ok().and_then(|m| m.modified().ok()).is_some_and(|m| m < stale);
        let orphan = match name.strip_suffix(".part") {
            Some(_) => old,
            None => !known.contains(&name),
        };
        if orphan {
            let _ = tokio::fs::remove_file(e.path()).await;
        }
    }
    Ok(())
}
