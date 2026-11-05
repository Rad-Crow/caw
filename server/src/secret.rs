use std::path::Path;

use aes_gcm::{
    aead::{Aead, KeyInit},
    Aes256Gcm, Nonce,
};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use hkdf::Hkdf;
use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};

// stored outside the database (env or an adjacent file), so a leaked database or backup alone is insufficient
pub struct Secret([u8; 32]);

impl Secret {
    pub fn load(db: &str) -> anyhow::Result<Self> {
        if let Ok(v) = std::env::var("CAW_SECRET") {
            let v = v.trim();
            if !v.is_empty() {
                return Ok(Self(Sha256::digest(v.as_bytes()).into()));
            }
        }
        let path = Path::new(db).parent().unwrap_or(Path::new(".")).join("secret");
        if let Ok(b) = std::fs::read(&path) {
            if let Ok(k) = <[u8; 32]>::try_from(b.as_slice()) {
                return Ok(Self(k));
            }
        }
        let k: [u8; 32] = rand::random();
        std::fs::write(&path, k)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600))?;
        }
        tracing::info!("generated server secret at {}", path.display());
        Ok(Self(k))
    }

    fn derive(&self, info: &[u8]) -> [u8; 32] {
        let mut out = [0u8; 32];
        Hkdf::<Sha256>::new(None, &self.0).expand(info, &mut out).expect("32 bytes");
        out
    }

    // stable salt for unknown usernames, so the salt lookup doesn't reveal which accounts exist
    pub fn fake_salt(&self, username: &str) -> String {
        let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(&self.derive(b"caw-salt-v1")).expect("hmac key");
        mac.update(username.as_bytes());
        URL_SAFE_NO_PAD.encode(&mac.finalize().into_bytes()[..16])
    }

    pub fn seal(&self, plain: &str) -> String {
        let c = Aes256Gcm::new_from_slice(&self.derive(b"caw-vault-v1")).expect("aes key");
        let nonce: [u8; 12] = rand::random();
        let mut out = nonce.to_vec();
        out.extend(c.encrypt(Nonce::from_slice(&nonce), plain.as_bytes()).expect("encrypt"));
        URL_SAFE_NO_PAD.encode(out)
    }

    pub fn open(&self, sealed: &str) -> Option<String> {
        let b = URL_SAFE_NO_PAD.decode(sealed).ok()?;
        if b.len() < 12 {
            return None;
        }
        let c = Aes256Gcm::new_from_slice(&self.derive(b"caw-vault-v1")).ok()?;
        String::from_utf8(c.decrypt(Nonce::from_slice(&b[..12]), &b[12..]).ok()?).ok()
    }
}
