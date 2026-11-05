use axum::{
    http::StatusCode,
    response::{IntoResponse, Response},
    Json,
};
use serde_json::json;

pub enum AppError {
    BadRequest(String),
    Unauthorized,
    Forbidden(String),
    NotFound,
    Conflict(String),
    Gone(String),
    // the sender's view of the recipient's devices is out of date
    Stale(String),
    TooMany,
    Internal(anyhow::Error),
}

pub type Result<T> = std::result::Result<T, AppError>;

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let (status, msg) = match self {
            AppError::BadRequest(m) => (StatusCode::BAD_REQUEST, m),
            AppError::Unauthorized => (StatusCode::UNAUTHORIZED, "Authentication required.".into()),
            AppError::Forbidden(m) => (StatusCode::FORBIDDEN, m),
            AppError::NotFound => (StatusCode::NOT_FOUND, "Not found.".into()),
            AppError::Conflict(m) => (StatusCode::CONFLICT, m),
            AppError::Gone(m) => (StatusCode::GONE, m),
            AppError::Stale(m) => (StatusCode::PRECONDITION_FAILED, m),
            AppError::TooMany => {
                return (
                    StatusCode::TOO_MANY_REQUESTS,
                    [(axum::http::header::RETRY_AFTER, "60")],
                    Json(json!({ "error": "Too many requests. Try again later." })),
                )
                    .into_response()
            }
            AppError::Internal(e) => {
                tracing::error!("{e:#}");
                (StatusCode::INTERNAL_SERVER_ERROR, "Internal server error.".into())
            }
        };
        (status, Json(json!({ "error": msg }))).into_response()
    }
}

impl<E: Into<anyhow::Error>> From<E> for AppError {
    fn from(e: E) -> Self {
        AppError::Internal(e.into())
    }
}

pub fn bad(m: impl Into<String>) -> AppError {
    AppError::BadRequest(m.into())
}
