// rfc 8291 (aes128gcm payload encryption) + rfc 8292 (vapid)
use aes_gcm::{aead::Aead, Aes128Gcm, KeyInit, Nonce};
use anyhow::{anyhow, Result};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD as B64, Engine};
use hkdf::Hkdf;
use p256::{
    ecdsa::{signature::Signer, Signature, SigningKey},
    elliptic_curve::sec1::ToEncodedPoint,
    PublicKey, SecretKey,
};
use reqwest::Url;
use serde_json::json;
use sha2::Sha256;

const RECORD_SIZE: u32 = 4096;

pub fn b64(s: &str) -> Result<Vec<u8>> {
    B64.decode(s.trim().trim_end_matches('=')).map_err(|e| anyhow!("{e}"))
}

pub fn check_keys(p256dh: &str, auth: &str) -> Result<()> {
    PublicKey::from_sec1_bytes(&b64(p256dh)?).map_err(|_| anyhow!("Invalid p256dh key."))?;
    if b64(auth)?.len() != 16 {
        return Err(anyhow!("Auth secret must be 16 bytes."));
    }
    Ok(())
}

pub fn encrypt(p256dh: &str, auth: &str, plaintext: &[u8]) -> Result<Vec<u8>> {
    let sender = SecretKey::random(&mut rand::rngs::OsRng);
    encrypt_with(&b64(p256dh)?, &b64(auth)?, plaintext, &sender, rand::random())
}

fn encrypt_with(ua_public: &[u8], auth: &[u8], plaintext: &[u8], sender: &SecretKey, salt: [u8; 16]) -> Result<Vec<u8>> {
    let ua = PublicKey::from_sec1_bytes(ua_public).map_err(|_| anyhow!("Invalid p256dh key."))?;
    let as_public = sender.public_key().to_encoded_point(false);
    let shared = p256::ecdh::diffie_hellman(sender.to_nonzero_scalar(), ua.as_affine());

    let mut info = b"WebPush: info\0".to_vec();
    info.extend_from_slice(ua_public);
    info.extend_from_slice(as_public.as_bytes());
    let mut ikm = [0u8; 32];
    Hkdf::<Sha256>::new(Some(auth), shared.raw_secret_bytes())
        .expand(&info, &mut ikm)
        .map_err(|e| anyhow!("{e}"))?;

    let hk = Hkdf::<Sha256>::new(Some(&salt), &ikm);
    let mut cek = [0u8; 16];
    let mut nonce = [0u8; 12];
    hk.expand(b"Content-Encoding: aes128gcm\0", &mut cek).map_err(|e| anyhow!("{e}"))?;
    hk.expand(b"Content-Encoding: nonce\0", &mut nonce).map_err(|e| anyhow!("{e}"))?;

    let mut record = plaintext.to_vec();
    record.push(2);
    let ct = Aes128Gcm::new_from_slice(&cek)
        .map_err(|e| anyhow!("{e}"))?
        .encrypt(Nonce::from_slice(&nonce), record.as_ref())
        .map_err(|e| anyhow!("{e}"))?;

    let mut out = Vec::with_capacity(21 + 65 + ct.len());
    out.extend_from_slice(&salt);
    out.extend_from_slice(&RECORD_SIZE.to_be_bytes());
    out.push(as_public.len() as u8);
    out.extend_from_slice(as_public.as_bytes());
    out.extend_from_slice(&ct);
    Ok(out)
}

pub struct Vapid {
    key: SigningKey,
    pub public: String,
}

impl Vapid {
    pub fn from_secret(secret: &[u8]) -> Result<Self> {
        let key = SigningKey::from_slice(secret).map_err(|e| anyhow!("{e}"))?;
        let public = B64.encode(key.verifying_key().to_encoded_point(false).as_bytes());
        Ok(Self { key, public })
    }

    pub fn generate() -> (Self, String) {
        let secret = SecretKey::random(&mut rand::rngs::OsRng).to_bytes();
        let v = Self::from_secret(&secret).expect("fresh key is valid");
        (v, B64.encode(secret))
    }

    pub fn header(&self, endpoint: &Url, subject: &str, now: i64) -> String {
        let head = B64.encode(br#"{"typ":"JWT","alg":"ES256"}"#);
        let claims = json!({
            "aud": endpoint.origin().ascii_serialization(),
            "exp": now + 12 * 3600,
            "sub": subject,
        });
        let input = format!("{head}.{}", B64.encode(claims.to_string()));
        let sig: Signature = self.key.sign(input.as_bytes());
        format!("vapid t={input}.{}, k={}", B64.encode(sig.to_bytes()), self.public)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use p256::ecdsa::{signature::Verifier, VerifyingKey};

    // rfc 8291 appendix a
    #[test]
    fn rfc8291_vector() {
        let ua_public = b64("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4").unwrap();
        let auth = b64("BTBZMqHH6r4Tts7J_aSIgg").unwrap();
        let sender = SecretKey::from_slice(&b64("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw").unwrap()).unwrap();
        let salt: [u8; 16] = b64("DGv6ra1nlYgDCS1FRnbzlw").unwrap().try_into().unwrap();
        let body = encrypt_with(&ua_public, &auth, b"When I grow up, I want to be a watermelon", &sender, salt).unwrap();
        assert_eq!(
            B64.encode(body),
            "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN"
        );
    }

    #[test]
    fn vapid_signs() {
        let (v, _) = Vapid::generate();
        let h = v.header(&Url::parse("https://push.example.com/upABC?up=1").unwrap(), "https://caw.example.com", 1000);
        let t = h.strip_prefix("vapid t=").unwrap().split(", k=").next().unwrap();
        let (input, sig) = t.rsplit_once('.').unwrap();
        let claims: serde_json::Value = serde_json::from_slice(&b64(input.split('.').nth(1).unwrap()).unwrap()).unwrap();
        assert_eq!(claims["aud"], "https://push.example.com");
        let vk = VerifyingKey::from_sec1_bytes(&b64(&v.public).unwrap()).unwrap();
        let sig = Signature::from_slice(&b64(sig).unwrap()).unwrap();
        vk.verify(input.as_bytes(), &sig).unwrap();
    }
}

// prints a known-good hpke vector for the client crypto tests: cargo test hpke_vector -- --nocapture
#[cfg(test)]
mod hpke_vector {
    use base64::{engine::general_purpose::URL_SAFE_NO_PAD as B64, Engine};
    use hpke::{aead::AesGcm128, kdf::HkdfSha256, kem::X25519HkdfSha256, Deserializable, Kem, OpModeS, Serializable};
    use rand::{rngs::StdRng, SeedableRng};

    #[test]
    fn hpke_vector() {
        let mut rng = StdRng::seed_from_u64(7);
        let (sk, pk) = X25519HkdfSha256::gen_keypair(&mut rng);
        let pt = [7u8; 16];
        let (enc, ct) = hpke::single_shot_seal::<AesGcm128, HkdfSha256, X25519HkdfSha256, _>(
            &OpModeS::Base,
            &pk,
            b"caw-message-key-v1",
            &pt,
            b"message-id",
            &mut rng,
        )
        .unwrap();
        let sk2 = <X25519HkdfSha256 as Kem>::PrivateKey::from_bytes(&sk.to_bytes()).unwrap();
        let back = hpke::single_shot_open::<AesGcm128, HkdfSha256, X25519HkdfSha256>(
            &hpke::OpModeR::Base,
            &sk2,
            &enc,
            b"caw-message-key-v1",
            &ct,
            b"message-id",
        )
        .unwrap();
        assert_eq!(back, pt);
        let mut w = enc.to_bytes().to_vec();
        w.extend_from_slice(&ct);
        println!(
            "VECTOR skR={} pkR={} w={} pt={}",
            B64.encode(sk.to_bytes()),
            B64.encode(pk.to_bytes()),
            B64.encode(w),
            B64.encode(pt)
        );
    }
}
