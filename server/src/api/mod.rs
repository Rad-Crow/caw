mod account;
mod admin;
mod blobs;
mod calls;
mod contacts;
mod devices;
mod vault;

use std::{path::Path, sync::Arc};

use axum::{
    extract::DefaultBodyLimit,
    http::{header, HeaderValue, Method},
    middleware,
    response::Response,
    routing::{any, delete, get, patch, post, put},
    Router,
};
use tower_http::{
    cors::{Any, CorsLayer},
    services::{ServeDir, ServeFile},
};

use crate::{error::AppError, ws, App};

pub use blobs::sweep as sweep_blobs;

// cross-origin routes authorized by a link token; bearer only, no cookies
fn link_routes() -> Router<Arc<App>> {
    let cors = CorsLayer::new()
        .allow_origin(Any)
        .allow_methods([Method::GET, Method::POST, Method::DELETE])
        .allow_headers([header::AUTHORIZATION, header::CONTENT_TYPE]);
    Router::new()
        .route("/api/pairings/redeem", post(contacts::redeem_pairing))
        .route("/api/link", delete(contacts::drop_link))
        .route("/api/link/devices", get(contacts::link_devices))
        .route("/api/link/mail", get(contacts::link_mail).post(contacts::send_mail))
        .route("/api/link/blobs", post(blobs::upload).layer(DefaultBodyLimit::disable()))
        .route("/api/link/blobs/{id}", get(blobs::link_download))
        .route("/api/link/ws", get(ws::guest))
        .layer(cors)
}

// link requests differ only in their credential, so a cached response would answer for another link
async fn no_store(mut res: Response) -> Response {
    res.headers_mut().entry(header::CACHE_CONTROL).or_insert(HeaderValue::from_static("no-store"));
    res
}

pub fn router(app: Arc<App>) -> Router {
    let dir = Path::new(&app.cfg.static_dir);
    let spa = ServeDir::new(dir).fallback(ServeFile::new(dir.join("index.html")));

    Router::new()
        .route("/api/health", get(|| async { "ok" }))
        .route("/api/config", get(account::config))
        .route("/api/auth/salt", post(account::salt))
        .route("/api/auth/login", post(account::login))
        .route("/api/auth/logout", post(account::logout))
        .route("/api/auth/register", post(account::register))
        .route("/api/invites/{token}", get(account::check_invite))
        .route("/api/me", get(account::me).patch(account::update_me))
        .route("/api/me/login", put(account::set_login))
        .route("/api/vault", get(vault::get).put(vault::put))
        .route("/api/vault/key", put(vault::put_key))
        .route("/api/sent", post(contacts::sent))
        .route("/api/devices", get(devices::my_devices).post(devices::add_device))
        .route("/api/devices/{id}", delete(devices::remove_device))
        .route("/api/devices/{id}/key", put(devices::set_device_key))
        .route("/api/pairings", post(contacts::create_pairing))
        .route("/api/links", get(contacts::my_links).post(contacts::create_link))
        .route("/api/links/{id}", delete(contacts::revoke_link))
        .route("/api/links/{id}/token", put(contacts::set_token))
        .route("/api/links/{id}/mail", post(contacts::own_mail))
        .route("/api/links/{id}/blobs", post(blobs::own_upload).layer(DefaultBodyLimit::disable()))
        .route("/api/mail", get(contacts::my_mail))
        .route("/api/blobs/{id}", get(blobs::download))
        .route("/api/turn", get(calls::turn))
        .route("/api/push", post(devices::push_register).delete(devices::push_unregister))
        .route("/api/admin/settings", get(admin::settings).patch(admin::update_settings))
        .route("/api/admin/users", get(admin::users).post(admin::create_user))
        .route("/api/admin/users/{id}", patch(admin::update_user).delete(admin::delete_user))
        .route("/api/admin/invites", get(admin::invites).post(admin::create_invite))
        .route("/api/admin/invites/{id}", delete(admin::revoke_invite))
        .merge(link_routes())
        .route("/api/{*rest}", any(|| async { AppError::NotFound }))
        .route("/ws", get(ws::handler))
        .layer(middleware::map_response(no_store))
        .fallback_service(spa)
        .with_state(app)
}
