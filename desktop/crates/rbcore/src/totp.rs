//! 2FA/TOTP — port of `rb_totp.c` (`totp.jsonl` + RFC 4226/6238 + the
//! byte-exact `RB2FA1 ` encrypted export format).

use crate::jsonl::{int_field, load_lines, save_lines, str_field};
use hmac::{Hmac, Mac};
use serde_json::{json, Value};
use sha1::Sha1;
use sha2::Sha256;
use std::path::Path;

pub const LINE_CAP: usize = 16 * 1024;
const ITERATIONS: u32 = 120_000;
const EXPORT_MAGIC: &str = "RB2FA1"; // no space, 6 bytes for the MAC
type HmacSha256 = Hmac<Sha256>;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Algo {
    Sha1 = 0,
    Sha256 = 1,
}

impl Algo {
    pub fn as_i64(self) -> i64 {
        self as i64
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct TotpAccount {
    pub id: i64,
    pub issuer: String,
    pub label: String,
    /// RFC 4648 Base32, uppercase, unpadded (decoder is lenient on input).
    pub secret: String,
    pub algo: Algo,
    pub digits: u32,
    pub period: u32,
    pub created_at: i64,
}

#[derive(Debug, Clone, Default)]
pub struct TotpStore {
    /// id ASC (creation order).
    pub items: Vec<TotpAccount>,
    next_id: i64,
}

// ------------------------------------------------------------------
// Base32 (RFC 4648)

const B32_ALPHABET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

pub fn base32_encode(data: &[u8]) -> String {
    let mut out = String::new();
    let mut acc: u32 = 0;
    let mut bits: u32 = 0;
    for &b in data {
        acc = (acc << 8) | b as u32;
        bits += 8;
        while bits >= 5 {
            bits -= 5;
            out.push(B32_ALPHABET[((acc >> bits) & 31) as usize] as char);
        }
    }
    if bits > 0 {
        out.push(B32_ALPHABET[((acc << (5 - bits)) & 31) as usize] as char);
    }
    out
}

/// Lenient decoder: accepts lowercase, spaces, tabs, `-` and `=` padding,
/// exactly like the C reader. Returns None on invalid characters or an
/// impossible length.
pub fn base32_decode(s: &str) -> Option<Vec<u8>> {
    let mut bits = 0u32;
    let mut acc = 0u32;
    let mut out = Vec::new();
    for c in s.chars() {
        let c = match c {
            ' ' | '\t' | '-' | '=' => continue,
            'a'..='z' => c.to_ascii_uppercase(),
            'A'..='Z' | '2'..='7' => c,
            _ => return None,
        };
        let v = B32_ALPHABET.iter().position(|&x| x as char == c)? as u32;
        acc = (acc << 5) | v;
        bits += 5;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
            acc &= (1 << bits) - 1;
        }
    }
    Some(out)
}

// ------------------------------------------------------------------
// RFC 4226 / 6238

fn hmac_digest(key: &[u8], msg: &[u8], algo: Algo) -> Vec<u8> {
    match algo {
        Algo::Sha1 => {
            let mut m = <Hmac<Sha1>>::new_from_slice(key).unwrap();
            m.update(msg);
            m.finalize().into_bytes().to_vec()
        }
        Algo::Sha256 => {
            let mut m = HmacSha256::new_from_slice(key).unwrap();
            m.update(msg);
            m.finalize().into_bytes().to_vec()
        }
    }
}

/// RFC 4226 dynamic truncation + decimal mod 10^digits, zero-padded.
pub fn hotp(key: &[u8], counter: u64, digits: u32, algo: Algo) -> String {
    let msg = counter.to_be_bytes();
    let mac = hmac_digest(key, &msg, algo);
    let offset = (mac[mac.len() - 1] & 0x0f) as usize;
    let bin = ((mac[offset] as u32 & 0x7f) << 24)
        | ((mac[offset + 1] as u32) << 16)
        | ((mac[offset + 2] as u32) << 8)
        | mac[offset + 3] as u32;
    let modulus = 10u32.pow(digits);
    let value = bin % modulus;
    format!("{:0width$}", value, width = digits as usize)
}

/// RFC 6238 code for `now_ms`.
pub fn totp_now(secret_b32: &str, algo: Algo, digits: u32, period: u32, now_ms: i64) -> Option<String> {
    let key = base32_decode(secret_b32)?;
    if key.is_empty() || period == 0 {
        return None;
    }
    let counter = (now_ms / 1000) as u64 / period as u64;
    Some(hotp(&key, counter, digits, algo))
}

/// `rb_totp_seconds_remaining` = `period - (now % period)` clamped to [1, period].
pub fn seconds_remaining(period: u32, now_ms: i64) -> u32 {
    if period <= 1 {
        return 1;
    }
    let now_s = (now_ms / 1000).max(0) as u64;
    let r = period as u64 - (now_s % period as u64);
    r.clamp(1, period as u64) as u32
}

// ------------------------------------------------------------------
// Store

impl TotpStore {
    /// Loads from the first existing path (per-profile first, root fallback).
    pub fn load_first(data: &Path, profile_id: &str) -> TotpStore {
        for p in crate::paths::totp_paths(data, profile_id) {
            if p.exists() {
                return TotpStore::load(&p);
            }
        }
        TotpStore::new()
    }

    pub fn new() -> Self {
        TotpStore {
            next_id: 1,
            ..Default::default()
        }
    }

    pub fn load(path: &Path) -> TotpStore {
        let mut t = TotpStore::new();
        for v in load_lines(path, LINE_CAP) {
            let secret = str_field(&v, "secret");
            if base32_decode(&secret).map(|k| k.is_empty()).unwrap_or(true) {
                continue;
            }
            let digits = int_field(&v, "digits").clamp(1, 10) as u32;
            let period = int_field(&v, "period").clamp(1, 86_400) as u32;
            if period == 0 {
                continue;
            }
            let algo = if int_field(&v, "algo") == 1 { Algo::Sha256 } else { Algo::Sha1 };
            let id = int_field(&v, "id");
            if id <= 0 {
                continue;
            }
            t.next_id = t.next_id.max(id + 1);
            t.items.push(TotpAccount {
                id,
                issuer: str_field(&v, "issuer"),
                label: str_field(&v, "label"),
                secret: secret.to_uppercase(),
                algo,
                digits,
                period,
                created_at: int_field(&v, "created_at"),
            });
        }
        t
    }

    pub fn save(&self, path: &Path) -> std::io::Result<()> {
        let rows: Vec<Value> = self
            .items
            .iter()
            .map(|a| {
                json!({
                    "id": a.id,
                    "issuer": a.issuer,
                    "label": a.label,
                    "secret": a.secret,
                    "algo": a.algo as i64,
                    "digits": a.digits,
                    "period": a.period,
                    "created_at": a.created_at,
                })
            })
            .collect();
        save_lines(path, &rows)
    }

    /// Adds from raw fields; the secret is normalised to uppercase unpadded.
    /// Returns the new id, or None for an unusable secret.
    pub fn add(&mut self, issuer: &str, label: &str, secret: &str, algo: Algo, digits: u32, period: u32, created_at: i64) -> Option<i64> {
        let key = base32_decode(secret)?;
        if key.is_empty() {
            return None;
        }
        let id = self.next_id;
        self.next_id += 1;
        let secret = base32_encode(&key);
        self.items.push(TotpAccount {
            id,
            issuer: issuer.to_string(),
            label: label.to_string(),
            secret,
            algo,
            digits,
            period,
            created_at,
        });
        Some(id)
    }

    pub fn remove(&mut self, id: i64) -> bool {
        let before = self.items.len();
        self.items.retain(|a| a.id != id);
        before != self.items.len()
    }

    pub fn code(&self, id: i64, now_ms: i64) -> Option<String> {
        let a = self.items.iter().find(|a| a.id == id)?;
        totp_now(&a.secret, a.algo, a.digits, a.period, now_ms)
    }

    /// Import merges lines, skipping accounts whose (issuer, label) pair
    /// already exists; fresh sequential ids.
    pub fn merge_jsonl(&mut self, text: &str) -> usize {
        let mut merged = 0;
        for line in text.split('\n') {
            let line = line.trim();
            if line.is_empty() {
                continue;
            }
            let Ok(v) = serde_json::from_str::<Value>(line) else { continue };
            let issuer = str_field(&v, "issuer");
            let label = str_field(&v, "label");
            let secret = str_field(&v, "secret");
            let algo = if int_field(&v, "algo") == 1 { Algo::Sha256 } else { Algo::Sha1 };
            let digits = int_field(&v, "digits").clamp(1, 10) as u32;
            let period = int_field(&v, "period").clamp(1, 86_400) as u32;
            let created = int_field(&v, "created_at");
            if self.items.iter().any(|a| a.issuer == issuer && a.label == label) {
                continue;
            }
            if self.add(&issuer, &label, &secret, algo, digits, period, created).is_some() {
                merged += 1;
            }
        }
        merged
    }

    /// The plaintext for export is exactly what `save` writes: one JSON line
    /// per account, each terminated by `\n`.
    pub fn export_plaintext(&self) -> String {
        let mut s = String::new();
        for a in &self.items {
            s.push_str(&serde_json::to_string(&json!({
                "id": a.id, "issuer": a.issuer, "label": a.label, "secret": a.secret,
                "algo": a.algo as i64, "digits": a.digits, "period": a.period,
                "created_at": a.created_at,
            })).unwrap_or_default());
            s.push('\n');
        }
        s
    }
}

// ------------------------------------------------------------------
// otpauth:// URI parsing

pub fn parse_otpauth(uri: &str) -> Option<(String, String, String, Algo, u32, u32)> {
    // otpauth://totp/<label>?secret=..&issuer=..&algorithm=..&digits=..&period=..
    let rest = uri.strip_prefix("otpauth://totp/")?;
    let (path, query) = match rest.split_once('?') {
        Some((p, q)) => (p, q),
        None => (rest, ""),
    };
    let mut secret = String::new();
    let mut issuer = String::new();
    let mut algo = Algo::Sha1;
    let mut digits = 6u32;
    let mut period = 30u32;
    for pair in query.split('&') {
        if pair.is_empty() {
            continue;
        }
        let (k, v) = pair.split_once('=').unwrap_or((pair, ""));
        let v = percent_decode(v);
        match k {
            "secret" => secret = v,
            "issuer" => issuer = v,
            "algorithm" => {
                algo = match v.to_lowercase().replace('-', "").as_str() {
                    "sha256" => Algo::Sha256,
                    _ => Algo::Sha1,
                }
            }
            "digits" => {
                digits = match v.as_str() {
                    "6" => 6,
                    "8" => 8,
                    _ => return None, // 6|8 only
                }
            }
            "period" => period = v.parse().ok()?,
            _ => {}
        }
    }
    if secret.is_empty() {
        return None;
    }
    let label = percent_decode(path);
    // issuer falls back to the label prefix before ':'.
    if issuer.is_empty() {
        if let Some((head, _)) = label.split_once(':') {
            issuer = head.to_string();
        }
    }
    Some((issuer, label, secret, algo, digits, period))
}

fn percent_decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            let hex = std::str::from_utf8(&bytes[i + 1..i + 3]).unwrap_or("");
            if let Ok(b) = u8::from_str_radix(hex, 16) {
                out.push(b);
                i += 3;
                continue;
            }
        }
        if bytes[i] == b'+' {
            out.push(b' ');
        } else {
            out.push(bytes[i]);
        }
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

// ------------------------------------------------------------------
// RB2FA1 encrypted export — byte-exact wire format

fn derive_keys(passphrase: &str, salt: &[u8], iters: u32) -> ([u8; 32], [u8; 32]) {
    let mut out = [0u8; 64];
    pbkdf2::pbkdf2_hmac::<Sha256>(passphrase.as_bytes(), salt, iters, &mut out);
    let mut enc = [0u8; 32];
    let mut mac = [0u8; 32];
    enc.copy_from_slice(&out[..32]);
    mac.copy_from_slice(&out[32..]);
    (enc, mac)
}

fn keystream_xor(key_enc: &[u8; 32], nonce: &[u8; 16], plaintext: &[u8]) -> Vec<u8> {
    let mut out = plaintext.to_vec();
    for (block_idx, chunk) in plaintext.chunks(32).enumerate() {
        let mut msg = Vec::with_capacity(24);
        msg.extend_from_slice(nonce);
        msg.extend_from_slice(&(block_idx as u64).to_be_bytes());
        let mut m = HmacSha256::new_from_slice(key_enc).unwrap();
        m.update(&msg);
        let ks = m.finalize().into_bytes();
        for i in 0..chunk.len() {
            out[block_idx * 32 + i] ^= ks[i];
        }
    }
    out
}

fn export_tag(key_mac: &[u8; 32], nonce: &[u8; 16], ct: &[u8]) -> [u8; 32] {
    let mut m = HmacSha256::new_from_slice(key_mac).unwrap();
    m.update(EXPORT_MAGIC.as_bytes());
    m.update(nonce);
    m.update(ct);
    let t = m.finalize().into_bytes();
    let mut tag = [0u8; 32];
    tag.copy_from_slice(&t);
    tag
}
/// Produces the single-line blob: `RB2FA1 <unpadded base64(salt‖be32(iters)‖nonce‖ct‖tag)>`.
pub fn export_encrypted(store: &TotpStore, passphrase: &str) -> Option<String> {
    use rand::RngCore;
    let plaintext = store.export_plaintext().into_bytes();
    let mut salt = [0u8; 16];
    let mut nonce = [0u8; 16];
    rand::rngs::OsRng.fill_bytes(&mut salt);
    rand::rngs::OsRng.fill_bytes(&mut nonce);
    let (key_enc, key_mac) = derive_keys(passphrase, &salt, ITERATIONS);
    let ct = keystream_xor(&key_enc, &nonce, &plaintext);
    let tag = export_tag(&key_mac, &nonce, &ct);
    let mut blob = Vec::with_capacity(16 + 4 + 16 + ct.len() + 32);
    blob.extend_from_slice(&salt);
    blob.extend_from_slice(&ITERATIONS.to_be_bytes());
    blob.extend_from_slice(&nonce);
    blob.extend_from_slice(&ct);
    blob.extend_from_slice(&tag);
    let b64 = data_encoding::BASE64.encode(&blob);
    let b64 = b64.trim_end_matches('=');
    Some(format!("RB2FA1 {b64}"))
}

/// Parses + verifies + decrypts an `RB2FA1` blob, returning the plaintext
/// JSONL. Wrong passphrase or tampering → None (tag verified BEFORE any
/// decryption; any iteration count other than 120 000 is rejected).
pub fn import_encrypted(line: &str, passphrase: &str) -> Option<String> {
    let rest = line.strip_prefix("RB2FA1 ")?;
    let b64 = rest.trim();
    let pad = b64.len() % 4;
    if pad == 1 {
        return None;
    }
    let mut owned = b64.to_string();
    if pad > 0 {
        owned.push_str(&"=".repeat(4 - pad));
    }
    if !owned.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'+' || c == b'/' || c == b'=') {
        return None;
    }
    let blob = data_encoding::BASE64.decode(owned.as_bytes()).ok()?;
    if blob.len() < 16 + 4 + 16 + 32 {
        return None;
    }
    let salt: &[u8] = &blob[..16];
    let iters = u32::from_be_bytes([blob[16], blob[17], blob[18], blob[19]]);
    if iters != ITERATIONS {
        return None;
    }
    let mut nonce_arr = [0u8; 16];
    nonce_arr.copy_from_slice(&blob[20..36]);
    let _nonce: &[u8] = &blob[20..36];
    let ct: &[u8] = &blob[36..blob.len() - 32];
    let tag = &blob[blob.len() - 32..];
    let (key_enc, key_mac) = derive_keys(passphrase, salt, iters);
    let expect = export_tag(&key_mac, &nonce_arr, ct);
    // constant-time-ish compare
    let mut diff = 0u8;
    for (a, b) in tag.iter().zip(expect.iter()) {
        diff |= a ^ b;
    }
    if diff != 0 {
        return None;
    }
    let pt = keystream_xor(&key_enc, &nonce_arr, ct);
    Some(String::from_utf8_lossy(&pt).into_owned())
}

#[cfg(test)]
mod tests {
    use super::*;

    // RFC 6238 test vector secret ("12345678901234567890") in base32.
    const RFC_SECRET: &str = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    fn rfc_key(algo: Algo) -> Vec<u8> {
        match algo {
            Algo::Sha1 => b"12345678901234567890".to_vec(),
            Algo::Sha256 => b"12345678901234567890123456789012".to_vec(), // 32-byte seed
        }
    }

    #[test]
    fn fips_digests() {
        use sha2::Digest;
        // SHA-256 of "abc"
        let d = Sha256::digest(b"abc");
        assert_eq!(
            data_encoding::HEXLOWER.encode(&d),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
        // HMAC-SHA256 vectors (RFC 4231 case 1 & 2)
        let mut m = HmacSha256::new_from_slice(&[0x0b; 20]).unwrap();
        m.update(b"Hi There");
        assert_eq!(
            data_encoding::HEXLOWER.encode(&m.finalize().into_bytes()),
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"
        );
    }

    #[test]
    fn sha1_vectors() {
        use sha1::Digest as _;
        let mut h = Sha1::new();
        h.update(b"abc");
        assert_eq!(
            data_encoding::HEXLOWER.encode(&h.finalize()),
            "a9993e364706816aba3e25717850c26c9cd0d89d"
        );
        let mut h2 = Sha1::new();
        h2.update(b"abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq");
        assert_eq!(
            data_encoding::HEXLOWER.encode(&h2.finalize()),
            "84983e441c3bd26ebaae4aa1f95129e5e54670f1"
        );
        let mut h3 = Sha1::new();
        h3.update(&vec![b'a'; 1_000_000]);
        assert_eq!(
            data_encoding::HEXLOWER.encode(&h3.finalize()),
            "34aa973cd4c4daa4f61eeb2bdbad27316534016f"
        );
    }

    #[test]
    fn rfc6238_vectors() {
        // 8-digit, T=59 → 94287082 (SHA1); 46119246 (SHA256)
        assert_eq!(hotp(&rfc_key(Algo::Sha1), 59 / 30, 8, Algo::Sha1), "94287082");
        assert_eq!(hotp(&rfc_key(Algo::Sha256), 59 / 30, 8, Algo::Sha256), "46119246");
        // 6-digit SHA1 rows (the RFC's 8-digit values mod 10^6, zero-padded):
        // T=1111111109 → 07081804 → 081804; T=1234567890 → 89005924 → 005924;
        // T=2000000000 → 69279037 → 279037.
        assert_eq!(hotp(&rfc_key(Algo::Sha1), 1111111109 / 30, 6, Algo::Sha1), "081804");
        assert_eq!(hotp(&rfc_key(Algo::Sha1), 1234567890 / 30, 6, Algo::Sha1), "005924");
        assert_eq!(hotp(&rfc_key(Algo::Sha1), 2_000_000_000 / 30, 6, Algo::Sha1), "279037");
        // totp_now path: T=59s → 94287082 (SHA1, 8 digits)
        assert_eq!(
            totp_now(RFC_SECRET, Algo::Sha1, 8, 30, 59_000),
            Some("94287082".into())
        );
    }

    #[test]
    fn seconds_remaining_contract() {
        assert_eq!(seconds_remaining(30, 59_000), 1);
        assert_eq!(seconds_remaining(30, 30_000), 30);
        assert_eq!(seconds_remaining(30, 61_000), 29);
        assert_eq!(seconds_remaining(30, 0), 30);
    }

    #[test]
    fn base32_roundtrip_and_leniency() {
        // "Hello" → the canonical JBSWY3DP; every lenient spelling decodes
        // back; invalid characters are refused.
        assert_eq!(base32_encode(b"Hello"), "JBSWY3DP");
        assert_eq!(base32_decode("JBSWY3DP"), Some(b"Hello".to_vec()));
        assert_eq!(base32_decode("jbswy 3dp"), Some(b"Hello".to_vec()));
        assert_eq!(base32_decode("JBSW-Y3-DP"), Some(b"Hello".to_vec()));
        assert_eq!(base32_decode("JBSWY3DP===="), Some(b"Hello".to_vec()));
        // arbitrary bytes roundtrip through encode→decode
        for data in [b"".as_slice(), b"a", b"ab", b"abc", b"Hello!".as_slice(), b"\xde\xad\xbe\xef\xca\xfe".as_slice()] {
            let enc = base32_encode(data);
            assert_eq!(base32_decode(&enc), Some(data.to_vec()), "roundtrip {data:?}");
            assert!(!enc.contains('='));
        }
        // the canonical README secret decodes and re-encodes to itself
        assert_eq!(base32_encode(&base32_decode("JBSWY3DPEHPK3PXP").unwrap()), "JBSWY3DPEHPK3PXP");
        // invalid character
        assert_eq!(base32_decode("JBSWY3DP1"), None); // '1' not in alphabet
    }

    fn tmp(tag: &str) -> std::path::PathBuf {
        let p = std::env::temp_dir().join(format!("rb-totp-{tag}-{}.jsonl", std::process::id()));
        let _ = std::fs::remove_file(&p);
        p
    }

    #[test]
    fn store_roundtrip_field_order() {
        let p = tmp("rt");
        let mut t = TotpStore::new();
        t.add("GitHub", "alice@example.com", "JBSWY3DPEHPK3PXP", Algo::Sha1, 6, 30, 1730000000000).unwrap();
        t.save(&p).unwrap();
        let text = std::fs::read_to_string(&p).unwrap();
        let key = |k: &str| text.find(&format!("\"{k}\":")).unwrap();
        assert!(key("id") < key("issuer"));
        assert!(key("issuer") < key("label"));
        assert!(key("label") < key("secret"));
        assert!(key("secret") < key("algo"));
        assert!(key("algo") < key("digits"));
        assert!(key("digits") < key("period"));
        assert!(key("period") < key("created_at"));
        assert!(text.contains("\"algo\":0"));
        let back = TotpStore::load(&p);
        assert_eq!(back.items.len(), 1);
        assert_eq!(back.items[0].secret, "JBSWY3DPEHPK3PXP");
        // code path through the store
        let code = back.code(back.items[0].id, 59_000).unwrap();
        assert_eq!(code.len(), 6);
        let _ = std::fs::remove_file(&p);
    }

    #[test]
    fn otpauth_parsing() {
        let (issuer, label, secret, algo, digits, period) = parse_otpauth(
            "otpauth://totp/alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=GitHub&algorithm=SHA256&digits=8&period=30",
        )
        .unwrap();
        assert_eq!(issuer, "GitHub");
        assert_eq!(label, "alice@example.com");
        assert_eq!(secret, "JBSWY3DPEHPK3PXP");
        assert_eq!(algo, Algo::Sha256);
        assert_eq!(digits, 8);
        assert_eq!(period, 30);
        // issuer falls back to label prefix
        let (i2, _, _, a2, d2, p2) =
            parse_otpauth("otpauth://totp/Acme:bob?secret=JBSWY3DPEHPK3PXP").unwrap();
        assert_eq!(i2, "Acme");
        assert_eq!(a2, Algo::Sha1);
        assert_eq!(d2, 6);
        assert_eq!(p2, 30);
        // digits 7 is refused
        assert!(parse_otpauth("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&digits=7").is_none());
        // missing secret refused
        assert!(parse_otpauth("otpauth://totp/x?issuer=A").is_none());
        // SHA-1 spellings
        let (_, _, _, a3, _, _) =
            parse_otpauth("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&algorithm=SHA-1").unwrap();
        assert_eq!(a3, Algo::Sha1);
    }

    #[test]
    fn rb2fa1_roundtrip_wrong_pass_tamper_and_dedup() {
        let mut t = TotpStore::new();
        t.add("GitHub", "alice", "JBSWY3DPEHPK3PXP", Algo::Sha1, 6, 30, 1).unwrap();
        t.add("AWS", "bob", "JBSWY3DPEHPK3PXP", Algo::Sha256, 8, 30, 2).unwrap();
        let blob = export_encrypted(&t, "hunter2").unwrap();
        assert!(blob.starts_with("RB2FA1 "));
        assert!(!blob.contains('='));

        // wrong passphrase → None, nothing merged
        assert!(import_encrypted(&blob, "hunter3").is_none());

        // flipped payload byte → tag mismatch → None
        let mut flipped = blob.clone();
        let b64part = &blob[7..];
        let flip_at = b64part.len() / 2;
        let c = b64part.as_bytes()[flip_at];
        let flipped_char = if c == b'A' { b'B' } else { b'A' };
        flipped.replace_range(7 + flip_at..7 + flip_at + 1, &(flipped_char as char).to_string());
        assert!(import_encrypted(&flipped, "hunter2").is_none());

        // roundtrip → same plaintext → merge skips existing (issuer,label)
        let pt = import_encrypted(&blob, "hunter2").unwrap();
        let mut t2 = TotpStore::new();
        t2.add("GitHub", "alice", "JBSWY3DPEHPK3PXP", Algo::Sha1, 6, 30, 9).unwrap();
        assert_eq!(t2.merge_jsonl(&pt), 1); // only AWS merged
        assert_eq!(t2.items.len(), 2);
        assert!(t2.items.iter().any(|a| a.issuer == "AWS" && a.digits == 8));

        // iteration count other than 120000 rejected
        let mut fake = blob.clone();
        let _ = &mut fake;
        // craft: decode, patch iters, re-encode
        let rest = &blob[7..];
        let pad = (4 - rest.len() % 4) % 4;
        let mut owned = rest.to_string();
        owned.push_str(&"=".repeat(pad));
        let mut raw = data_encoding::BASE64.decode(owned.as_bytes()).unwrap();
        raw[16..20].copy_from_slice(&60_000u32.to_be_bytes());
        let re = data_encoding::BASE64.encode(&raw);
        let re = re.trim_end_matches('=');
        assert!(import_encrypted(&format!("RB2FA1 {re}"), "hunter2").is_none());

        // 1-char tail rejected
        assert!(import_encrypted("RB2FA1 QQ", "x").is_none());
        // non-alphabet chars rejected
        assert!(import_encrypted("RB2FA1 AAAA*AAA", "x").is_none());
        // empty store still round-trips
        let empty = export_encrypted(&TotpStore::new(), "x").unwrap();
        assert_eq!(import_encrypted(&empty, "x").unwrap(), "");
    }
}
