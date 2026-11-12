use serde::{Deserialize, Serialize};
use sqlx::SqlitePool;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    pub server_name: String,
    pub registration_open: bool,
    pub ring_timeout_secs: u64,
    pub message_ttl_default: i64,
    pub message_ttl_min: i64,
    pub message_ttl_max: i64,
    pub max_upload_mb: u64,
    pub device_expiry_days: i64,
    pub pairing_code_minutes: i64,
    pub pairing_request_hours: i64,
    pub max_group_members: i64,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            server_name: "Caw".into(),
            registration_open: false,
            ring_timeout_secs: 45,
            message_ttl_default: 86400,
            message_ttl_min: 300,
            message_ttl_max: 7 * 86400,
            max_upload_mb: 25,
            device_expiry_days: 30,
            pairing_code_minutes: 120,
            pairing_request_hours: 24,
            max_group_members: 10,
        }
    }
}

impl Settings {
    pub async fn load(db: &SqlitePool) -> anyhow::Result<Self> {
        let v: Option<String> = sqlx::query_scalar("SELECT value FROM settings WHERE key = 'settings'")
            .fetch_optional(db)
            .await?;
        Ok(v.and_then(|v| serde_json::from_str(&v).ok()).unwrap_or_default())
    }

    pub async fn save(&self, db: &SqlitePool) -> anyhow::Result<()> {
        sqlx::query("INSERT INTO settings (key, value) VALUES ('settings', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")
            .bind(serde_json::to_string(self)?)
            .execute(db)
            .await?;
        Ok(())
    }
}
