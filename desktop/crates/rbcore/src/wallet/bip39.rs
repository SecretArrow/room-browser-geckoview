//! BIP39 — English wordlist, 24 words for new wallets (12/15/18/21 accepted
//! on import), empty passphrase seed. Port of the Android `Mnemonics.kt`.

use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256, Sha512};

const WORDLIST: &str = include_str!("../wallet_wordlist.txt");

pub fn word(index: usize) -> &'static str {
    WORDLIST.lines().nth(index).unwrap_or("")
}

pub fn wordlist() -> Vec<&'static str> {
    WORDLIST.lines().collect()
}

fn word_index(w: &str) -> Option<usize> {
    WORDLIST.lines().position(|x| x == w)
}

/// 11 bits per word; the last word carries 7 bits + 4-bit checksum (24 words).
pub fn generate_mnemonic() -> String {
    use rand::RngCore;
    let mut entropy = [0u8; 32]; // 256 bits → 24 words
    rand::rngs::OsRng.fill_bytes(&mut entropy);
    mnemonic_from_entropy(&entropy).expect("entropy size is valid")
}

pub fn mnemonic_from_entropy(entropy: &[u8]) -> Option<String> {
    match entropy.len() {
        16 | 20 | 24 | 28 | 32 => {}
        _ => return None,
    }
    let checksum = Sha256::digest(entropy);
    let checksum_bits = entropy.len() * 8 / 32;
    let mut bits: Vec<bool> = Vec::with_capacity(entropy.len() * 8 + checksum_bits);
    for b in entropy {
        for i in (0..8).rev() {
            bits.push((b >> i) & 1 == 1);
        }
    }
    for i in 0..checksum_bits {
        bits.push((checksum[0] >> (7 - i)) & 1 == 1);
    }
    let mut words = Vec::new();
    for chunk in bits.chunks(11) {
        let mut idx = 0usize;
        for b in chunk {
            idx = (idx << 1) | (*b as usize);
        }
        words.push(word(idx));
    }
    Some(words.join(" "))
}

/// Validates word membership, count and the embedded checksum.
pub fn validate(mnemonic: &str) -> bool {
    let words: Vec<&str> = mnemonic.split_whitespace().collect();
    match words.len() {
        12 | 15 | 18 | 21 | 24 => {}
        _ => return false,
    }
    let mut indices = Vec::with_capacity(words.len());
    for w in &words {
        match word_index(w) {
            Some(i) => indices.push(i),
            None => return false,
        }
    }
    let total_bits = words.len() * 11;
    let ent_bits = total_bits / 33 * 32;
    let mut bits: Vec<bool> = Vec::with_capacity(total_bits);
    for idx in indices {
        for i in (0..11).rev() {
            bits.push((idx >> i) & 1 == 1);
        }
    }
    let mut entropy = vec![0u8; ent_bits / 8];
    for (i, b) in bits[..ent_bits].iter().enumerate() {
        if *b {
            entropy[i / 8] |= 1 << (7 - (i % 8));
        }
    }
    let checksum = Sha256::digest(&entropy);
    let checksum_bits = ent_bits / 32;
    for i in 0..checksum_bits {
        let expected = (checksum[0] >> (7 - i)) & 1 == 1;
        if bits[ent_bits + i] != expected {
            return false;
        }
    }
    true
}

/// BIP39 seed: PBKDF2-HMAC-SHA512(mnemonic, "mnemonic" + passphrase, 2048).
/// The app always uses an empty passphrase (Android parity).
pub fn seed_from_mnemonic(mnemonic: &str) -> [u8; 64] {
    // Normalise per RFC: the app accepts only ASCII wordlists.
    let m = mnemonic.split_whitespace().collect::<Vec<_>>().join(" ");
    let mut out = [0u8; 64];
    pbkdf2_sha512(m.as_bytes(), format!("mnemonic").as_bytes(), 2048, &mut out);
    out
}

/// Minimal PBKDF2-HMAC-SHA512 (the pbkdf2 crate's hmac feature covers
/// SHA-256; SHA-512 seeds need it spelled out once).
pub fn pbkdf2_sha512(password: &[u8], salt: &[u8], rounds: u32, out: &mut [u8]) {
    let mut block = salt.to_vec();
    for idx in 0..out.len().div_ceil(64) {
        block.truncate(salt.len());
        block.extend_from_slice(&(idx as u32 + 1).to_be_bytes());
        let mut m = <Hmac<Sha512>>::new_from_slice(password).unwrap();
        m.update(&block);
        let u = m.finalize().into_bytes();
        let mut t = [0u8; 64];
        t.copy_from_slice(&u);
        let mut acc = t;
        for _ in 1..rounds {
            let mut m2 = <Hmac<Sha512>>::new_from_slice(password).unwrap();
            m2.update(&t);
            t.copy_from_slice(&m2.finalize().into_bytes());
            for k in 0..64 {
                acc[k] ^= t[k];
            }
        }
        let take = 64.min(out.len() - idx * 64);
        out[idx * 64..idx * 64 + take].copy_from_slice(&acc[..take]);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // BIP39 official trezor vector — empty passphrase (verified against
    // hashlib.pbkdf2_hmac('sha512', mnemonic, b'mnemonic', 2048)).
    const VECTOR: (&str, &str) = (
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
    );

    #[test]
    fn wordlist_shape() {
        let wl = wordlist();
        assert_eq!(wl.len(), 2048);
        assert_eq!(wl[0], "abandon");
        assert_eq!(wl[2047], "zoo");
    }

    #[test]
    fn validate_and_roundtrip() {
        let m = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        assert!(validate(m));
        assert!(validate(&generate_mnemonic()));
        // bad checksum
        let bad = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon";
        assert!(!validate(bad));
        // bad word
        assert!(!validate("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon zzz"));
        // entropy roundtrip
        let e = [0u8; 16];
        let m2 = mnemonic_from_entropy(&e).unwrap();
        assert!(validate(&m2));
        assert_eq!(m2.split(' ').count(), 12);
        let e32 = [0xffu8; 32];
        let m3 = mnemonic_from_entropy(&e32).unwrap();
        assert_eq!(m3.split(' ').count(), 24);
    }

    #[test]
    fn official_seed_vector() {
        let seed = seed_from_mnemonic(VECTOR.0);
        assert_eq!(data_encoding::HEXLOWER.encode(&seed), VECTOR.1);
    }
}
