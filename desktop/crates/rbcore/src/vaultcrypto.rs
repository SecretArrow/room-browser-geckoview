//! Vault crypto — the desktop replacement for Android's AndroidKeyStore
//! aliases (`roomvault-<suffix>` / `roomwallet-<suffix>` / `roomtotp-<suffix>`):
//! a per-profile passphrase-sealed store using Argon2id + AES-256-GCM.
//!
//! Wire format (`sealed:v1`): `sealed:v1:<base64(salt[16]||nonce[12]||ct||tag[16])>`.

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Nonce};
use argon2::Argon2;

const PREFIX: &str = "sealed:v1:";

fn derive_key(passphrase: &str, salt: &[u8]) -> [u8; 32] {
    let mut out = [0u8; 32];
    // OWASP-ish parameters; fast enough for interactive unlocks, far too
    // expensive to brute-force a stolen vault file.
    let params = argon2::Params::new(19 * 1024, 2, 1, Some(32)).unwrap();
    let a2 = Argon2::new(argon2::Algorithm::Argon2id, argon2::Version::V0x13, params);
    let _ = a2.hash_password_into(passphrase.as_bytes(), salt, &mut out);
    out
}

pub fn seal(plaintext: &[u8], passphrase: &str) -> crate::CoreResult<String> {
    use rand::RngCore;
    let mut salt = [0u8; 16];
    let mut nonce = [0u8; 12];
    rand::rngs::OsRng.fill_bytes(&mut salt);
    rand::rngs::OsRng.fill_bytes(&mut nonce);
    let key = derive_key(passphrase, &salt);
    let cipher = Aes256Gcm::new_from_slice(&key).map_err(|e| format!("aes init: {e}"))?;
    let ct = cipher
        .encrypt(Nonce::from_slice(&nonce), Payload { msg: plaintext, aad: b"rb-vault" })
        .map_err(|_| "encryption failed".to_string())?;
    let mut blob = Vec::with_capacity(16 + 12 + ct.len());
    blob.extend_from_slice(&salt);
    blob.extend_from_slice(&nonce);
    blob.extend_from_slice(&ct);
    Ok(format!("{PREFIX}{}", data_encoding::BASE64_NOPAD.encode(&blob)))
}

pub fn open(sealed: &str, passphrase: &str) -> crate::CoreResult<Vec<u8>> {
    let rest = sealed
        .strip_prefix(PREFIX)
        .ok_or_else(|| "not a sealed:v1 blob".to_string())?;
    let mut owned = rest.to_string();
    let pad = (4 - owned.len() % 4) % 4;
    owned.push_str(&"=".repeat(pad));
    let blob = data_encoding::BASE64
        .decode(owned.as_bytes())
        .map_err(|e| format!("corrupt vault blob: {e}"))?;
    if blob.len() < 16 + 12 + 16 {
        return Err("vault blob too short".into());
    }
    let salt = &blob[..16];
    let nonce: [u8; 12] = blob[16..28].try_into().unwrap();
    let ct = &blob[28..];
    let key = derive_key(passphrase, salt);
    let cipher = Aes256Gcm::new_from_slice(&key).map_err(|e| format!("aes init: {e}"))?;
    cipher
        .decrypt(Nonce::from_slice(&nonce), Payload { msg: ct, aad: b"rb-vault" })
        .map_err(|_| "wrong passphrase or corrupted vault".to_string())
}

pub fn seal_string(s: &str, passphrase: &str) -> crate::CoreResult<String> {
    seal(s.as_bytes(), passphrase)
}

pub fn open_string(sealed: &str, passphrase: &str) -> crate::CoreResult<String> {
    let bytes = open(sealed, passphrase)?;
    String::from_utf8(bytes).map_err(|_| "vault payload is not valid UTF-8".into())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn seal_open_roundtrip_and_wrong_passphrase() {
        let sealed = seal_string("hunter2 secret", "master-pass").unwrap();
        assert!(sealed.starts_with("sealed:v1:"));
        assert_eq!(open_string(&sealed, "master-pass").unwrap(), "hunter2 secret");
        assert!(open_string(&sealed, "wrong").is_err());
        // tampering breaks the GCM tag
        let mut tampered = sealed.clone();
        unsafe {
            tampered.as_bytes_mut()[20] ^= 0x01;
        }
        assert!(open_string(&tampered, "master-pass").is_err());
        // arbitrary bytes
        let bin = seal(&[0u8, 1, 2, 255, 254], "p").unwrap();
        assert_eq!(open(&bin, "p").unwrap(), vec![0u8, 1, 2, 255, 254]);
    }

    #[test]
    fn non_blobs_are_refused() {
        assert!(open("plaintext", "p").is_err());
        assert!(open("sealed:v1:QQ", "p").is_err());
    }

    #[test]
    fn fresh_salts_each_seal() {
        let a = seal_string("x", "p").unwrap();
        let b = seal_string("x", "p").unwrap();
        assert_ne!(a, b);
    }
}
