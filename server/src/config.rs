use std::{env, net::SocketAddr};

#[derive(Clone, Debug)]
pub struct Config {
    pub bind: SocketAddr,
    pub db: String,
    pub blob_dir: String,
    pub static_dir: String,
    pub public_url: String,
    pub turn_secret: Option<String>,
    pub turn_urls: Vec<String>,
    pub stun_urls: Vec<String>,
    pub turn_ttl: u64,
    pub push_allow_private: bool,
    pub push_allowed_hosts: Vec<String>,
}

fn var(k: &str) -> Option<String> {
    env::var(k).ok().filter(|v| !v.trim().is_empty())
}

fn list(k: &str) -> Vec<String> {
    var(k)
        .map(|v| {
            v.split(',')
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty())
                .collect()
        })
        .unwrap_or_default()
}

impl Config {
    pub fn from_env() -> anyhow::Result<Self> {
        let db = var("CAW_DB").unwrap_or_else(|| "caw.db".into());
        let blob_dir = var("CAW_BLOB_DIR").unwrap_or_else(|| {
            let parent = std::path::Path::new(&db).parent().unwrap_or(std::path::Path::new("."));
            parent.join("blobs").to_string_lossy().into_owned()
        });
        Ok(Self {
            bind: var("CAW_BIND")
                .unwrap_or_else(|| "0.0.0.0:8080".into())
                .parse()?,
            db,
            blob_dir,
            static_dir: var("CAW_STATIC_DIR").unwrap_or_else(|| "../web/dist".into()),
            public_url: var("CAW_PUBLIC_URL")
                .unwrap_or_else(|| "http://localhost:8080".into())
                .trim_end_matches('/')
                .to_string(),
            turn_secret: var("CAW_TURN_SECRET"),
            turn_urls: list("CAW_TURN_URLS"),
            stun_urls: list("CAW_STUN_URLS"),
            turn_ttl: var("CAW_TURN_TTL")
                .map(|v| v.parse())
                .transpose()?
                .unwrap_or(12 * 3600),
            push_allow_private: var("CAW_PUSH_ALLOW_PRIVATE").is_some_and(|v| v == "true" || v == "1"),
            push_allowed_hosts: list("CAW_PUSH_ALLOWED_HOSTS").into_iter().map(|h| h.to_lowercase()).collect(),
        })
    }

    pub fn secure_cookies(&self) -> bool {
        self.public_url.starts_with("https://")
    }
}
