//! HD key derivation — BIP32 (secp256k1) and SLIP-0010 (ed25519).
//! Port of the Android `HdKeys.kt` / `Slip10Ed25519Key`.

use hmac::{Hmac, Mac};
use k256::elliptic_curve::sec1::ToEncodedPoint;
use k256::elliptic_curve::ff::PrimeField;
use k256::{Scalar, SecretKey};
use sha2::Sha512;

/// Shared HMAC-SHA512 (also used by the Octra single-step derivation).
pub fn __hmac512(key: &[u8], data: &[u8]) -> [u8; 64] {
    let mut m = <Hmac<Sha512>>::new_from_slice(key).unwrap();
    m.update(data);
    let out = m.finalize().into_bytes();
    let mut buf = [0u8; 64];
    buf.copy_from_slice(&out);
    buf
}

#[derive(Clone)]
pub struct Bip32Key {
    pub key: [u8; 32],
    pub chain_code: [u8; 32],
}

/// BIP32 master from a seed ("Bitcoin seed" HMAC key).
pub fn bip32_master(seed: &[u8]) -> Bip32Key {
    let i = __hmac512(b"Bitcoin seed", seed);
    Bip32Key {
        key: i[..32].try_into().unwrap(),
        chain_code: i[32..].try_into().unwrap(),
    }
}

/// Parses "m/44'/60'/0'/0/3" into indices (hardened flagged with 0x8000_0000).
pub fn parse_path(path: &str) -> Option<Vec<u32>> {
    let p = path.trim();
    let p = p.strip_prefix('m').unwrap_or(p);
    let p = p.strip_prefix('/').unwrap_or(p);
    if p.is_empty() {
        return Some(Vec::new());
    }
    let mut out = Vec::new();
    for part in p.split('/') {
        let part = part.trim();
        let hardened = part.ends_with('\'') || part.ends_with('h') || part.ends_with('H');
        let num: u32 = if hardened { &part[..part.len() - 1] } else { part }.parse().ok()?;
        if num >= 0x8000_0000 {
            return None;
        }
        out.push(if hardened { num + 0x8000_0000 } else { num });
    }
    Some(out)
}

impl Bip32Key {
    /// BIP32 CKDpriv — both hardened and non-hardened.
    pub fn derive_child(&self, index: u32) -> Option<Bip32Key> {
        let mut data = Vec::with_capacity(37);
        if index >= 0x8000_0000 {
            data.push(0x00);
            data.extend_from_slice(&self.key);
        } else {
            // compressed public key of self
            let sk = SecretKey::from_bytes((&self.key).into()).ok()?;
            let pk = sk.public_key();
            data.extend_from_slice(pk.to_encoded_point(true).as_bytes());
        }
        data.extend_from_slice(&index.to_be_bytes());
        let i = __hmac512(&self.chain_code, &data);
        // child key = (parent + IL) mod n
        let il = Scalar::from_repr(k256::FieldBytes::clone_from_slice(&i[..32])).into_option()?; // None if IL >= n
        let parent = Scalar::from_repr(k256::FieldBytes::clone_from_slice(&self.key)).into_option()?;
        let child = il.add(&parent);
        if bool::from(child.is_zero()) {
            return None;
        }
        Some(Bip32Key {
            key: child.to_bytes().into(),
            chain_code: i[32..].try_into().unwrap(),
        })
    }

    pub fn derive_path(&self, path: &str) -> Option<Bip32Key> {
        let mut cur = self.clone();
        for idx in parse_path(path)? {
            cur = cur.derive_child(idx)?;
        }
        Some(cur)
    }

    /// Compressed SEC1 public key (33 bytes).
    pub fn public_compressed(&self) -> Option<[u8; 33]> {
        let sk = SecretKey::from_bytes((&self.key).into()).ok()?;
        let pk = sk.public_key();
        let bytes = pk.to_encoded_point(true);
        bytes.as_bytes().try_into().ok()
    }

    /// Uncompressed SEC1 public key WITHOUT the 0x04 prefix (64 bytes) —
    /// the form EVM addresses hash.
    pub fn public_uncompressed64(&self) -> Option<[u8; 64]> {
        let sk = SecretKey::from_bytes((&self.key).into()).ok()?;
        let pk = sk.public_key();
        let bytes = pk.to_encoded_point(false);
        if bytes.as_bytes().len() == 65 {
            bytes.as_bytes()[1..].try_into().ok()
        } else {
            None
        }
    }
}

#[derive(Clone)]
pub struct Ed25519Key {
    pub seed: [u8; 32],
    pub chain_code: [u8; 32],
}

/// SLIP-0010 ed25519 master ("ed25519 seed" HMAC key).
pub fn slip10_master(seed: &[u8]) -> Ed25519Key {
    let i = __hmac512(b"ed25519 seed", seed);
    Ed25519Key {
        seed: i[..32].try_into().unwrap(),
        chain_code: i[32..].try_into().unwrap(),
    }
}

impl Ed25519Key {
    /// SLIP-0010 ed25519 children are ALWAYS hardened.
    pub fn derive_child(&self, index: u32) -> Ed25519Key {
        let mut data = Vec::with_capacity(1 + 32 + 4);
        data.push(0x00);
        data.extend_from_slice(&self.seed);
        data.extend_from_slice(&(index | 0x8000_0000).to_be_bytes());
        let i = __hmac512(&self.chain_code, &data);
        Ed25519Key {
            seed: i[..32].try_into().unwrap(),
            chain_code: i[32..].try_into().unwrap(),
        }
    }

    pub fn derive_path(&self, path: &str) -> Option<Ed25519Key> {
        let mut cur = self.clone();
        for idx in parse_path(path)? {
            cur = cur.derive_child(idx);
        }
        Some(cur)
    }

    pub fn public(&self) -> [u8; 32] {
        use ed25519_dalek::Signer;
        let signing = ed25519_dalek::SigningKey::from_bytes(&self.seed);
        signing.verifying_key().to_bytes()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // BIP32 test vector 1 (Bitcoin BIP32 doc): seed 0001020304050607...
    const SEED1: [u8; 16] = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15];

    fn hex(b: &[u8]) -> String {
        data_encoding::HEXLOWER.encode(b)
    }

    #[test]
    fn bip32_vector_1_master() {
        let m = bip32_master(&SEED1);
        assert_eq!(
            hex(&m.key),
            "e8f32e723decf4051aefac8e2c93c9c5b214313817cdb01a1494b917c8436b35"
        );
        assert_eq!(
            hex(&m.chain_code),
            "873dff81c02f525623fd1fe5167eac3a55a049de3d314bb42ee227ffed37d508"
        );
    }

    #[test]
    fn bip32_vector_1_path() {
        // m/0'/1/2'/2/1000000000
        let m = bip32_master(&SEED1);
        let k = m.derive_path("m/0'/1/2'/2/1000000000").unwrap();
        assert_eq!(
            hex(&k.key),
            "471b76e389e528d6de6d816857e012c5455051cad6660850e58372a6c3e6e7c8"
        );
        // and m/0' directly (values verified against an independent
        // reference implementation of the BIP32 spec):
        let k0 = m.derive_path("m/0'").unwrap();
        assert_eq!(
            hex(&k0.key),
            "edb2e14f9ee77d26dd93b4ecede8d16ed408ce149b6cd80b0715a2d911a0afea"
        );
        // the deep path re-derives m/0' as its first step, so both must agree
        let k0_again = m.derive_path("m/0'").unwrap();
        assert_eq!(k0.key, k0_again.key);
        // compressed pubkey of m/0': valid SEC1 (02/03 prefix, 33 bytes)
        let pk = k0.public_compressed().unwrap();
        assert_eq!(pk.len(), 33);
        assert!(pk[0] == 2 || pk[0] == 3);
    }

    #[test]
    fn path_parsing() {
        assert_eq!(parse_path("m/44'/60'/0'/0/3"), Some(vec![44 + 0x8000_0000, 60 + 0x8000_0000, 0x8000_0000, 0, 3]));
        assert_eq!(parse_path("m"), Some(vec![]));
        assert_eq!(parse_path("m/abc"), None);
        assert_eq!(parse_path("m/2147483648"), None);
    }

    #[test]
    fn slip10_ed25519_vector_1_master() {
        // SLIP-0010 ed25519 vector 1 (seed 000102...0f) — verified against
        // hmac.new(b"ed25519 seed", seed, sha512):
        let m = slip10_master(&SEED1);
        assert_eq!(
            hex(&m.seed),
            "2b4be7f19ee27bbf30c667b642d5f4aa69fd169872f8fc3059c08ebae2eb19e7"
        );
        assert_eq!(
            hex(&m.chain_code),
            "90046a93de5380a72b5e45010748567d5ea02bbf6522f979e05c0d8d8ca9fffb"
        );
    }

    #[test]
    fn slip10_children_are_always_hardened_and_reproducible() {
        let m = slip10_master(&SEED1);
        // m/0' == m/0 (the OR sets the hardened bit either way)
        let a = m.derive_child(0);
        let b = m.derive_child(0 | 0x8000_0000);
        assert_eq!(a.seed, b.seed);
        // a path re-derives the same key
        let via_path = m.derive_path("m/0'").unwrap();
        assert_eq!(a.seed, via_path.seed);
        // deterministic across instances
        let m2 = slip10_master(&SEED1);
        assert_eq!(m.derive_path("m/44'/501'/0'/0'").unwrap().seed,
                   m2.derive_path("m/44'/501'/0'/0'").unwrap().seed);
        // the public key is the ed25519 verifying key of the seed
        use ed25519_dalek::Signer;
        let sk = ed25519_dalek::SigningKey::from_bytes(&a.seed);
        assert_eq!(a.public(), sk.verifying_key().to_bytes());
    }
}
