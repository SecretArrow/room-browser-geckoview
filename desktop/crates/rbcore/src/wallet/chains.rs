//! Chain registry + address derivation for all 8 chains.
//! Port of the per-chain adapters' derivation/address logic.

use crate::wallet::hd::{bip32_master, slip10_master, Bip32Key, Ed25519Key};
use sha2::{Digest, Sha256};
use sha3::Keccak256;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ChainType {
    Evm,
    Solana,
    Aptos,
    Sui,
    Cosmos,
    Bitcoin,
    Tron,
    Octra,
}

impl ChainType {
    pub fn as_str(self) -> &'static str {
        match self {
            ChainType::Evm => "EVM",
            ChainType::Solana => "SOLANA",
            ChainType::Aptos => "APTOS",
            ChainType::Sui => "SUI",
            ChainType::Cosmos => "COSMOS",
            ChainType::Bitcoin => "BITCOIN",
            ChainType::Tron => "TRON",
            ChainType::Octra => "OCTRA",
        }
    }
    pub fn parse(s: &str) -> Option<ChainType> {
        Some(match s {
            "EVM" => ChainType::Evm,
            "SOLANA" => ChainType::Solana,
            "APTOS" => ChainType::Aptos,
            "SUI" => ChainType::Sui,
            "COSMOS" => ChainType::Cosmos,
            "BITCOIN" => ChainType::Bitcoin,
            "TRON" => ChainType::Tron,
            "OCTRA" => ChainType::Octra,
            _ => return None,
        })
    }
    /// SLIP-44 coin types (Android parity; Cosmos varies per network hrp).
    pub fn default_coin_type(self) -> u64 {
        match self {
            ChainType::Evm => 60,
            ChainType::Solana => 501,
            ChainType::Aptos => 637,
            ChainType::Sui => 784,
            ChainType::Cosmos => 118,
            ChainType::Bitcoin => 0,
            ChainType::Tron => 195,
            ChainType::Octra => 0,
        }
    }
    pub fn uses_ed25519(self) -> bool {
        matches!(self, ChainType::Solana | ChainType::Aptos | ChainType::Sui | ChainType::Octra)
    }
}

pub fn keccak256(data: &[u8]) -> [u8; 32] {
    let mut h = Keccak256::new();
    h.update(data);
    h.finalize().into()
}

pub fn sha256(data: &[u8]) -> [u8; 32] {
    let mut h = Sha256::new();
    h.update(data);
    h.finalize().into()
}

pub fn sha3_256(data: &[u8]) -> [u8; 32] {
    use sha3::Sha3_256;
    let mut h = Sha3_256::new();
    h.update(data);
    h.finalize().into()
}

pub fn blake2b256(data: &[u8]) -> [u8; 32] {
    use blake2::digest::consts::U32;
    use blake2::{Blake2b, Digest as _};
    type B256 = Blake2b<U32>;
    let mut h = B256::new();
    h.update(data);
    h.finalize().into()
}

pub fn ripemd160(data: &[u8]) -> [u8; 20] {
    use ripemd::Ripemd160;
    let mut h = Ripemd160::new();
    h.update(data);
    h.finalize().into()
}

pub fn hash160(data: &[u8]) -> [u8; 20] {
    ripemd160(&sha256(data))
}

// ---------------- bech32 (for Cosmos + Bitcoin addresses) ----------------

pub fn bech32_encode(hrp: &str, data: &[u8]) -> String {
    use bech32::ToBase32;
    bech32::encode(hrp, data.to_base32(), bech32::Variant::Bech32).unwrap_or_default()
}

// ---------------- base58check (TRON) ----------------

pub fn base58check(data: &[u8]) -> String {
    let checksum = &sha256(&sha256(data))[..4];
    let mut buf = data.to_vec();
    buf.extend_from_slice(checksum);
    bs58::encode(&buf).into_string()
}

#[derive(Debug, Clone)]
pub struct DerivedAccount {
    pub chain: ChainType,
    pub address: String,
    pub path: String,
    /// Private material: 32-byte scalar (secp chains) or seed (ed chains).
    pub private: Vec<u8>,
    pub public: Vec<u8>,
}

/// Derives account `index` on `chain` from the BIP39 seed.
pub fn derive_account(seed: &[u8; 64], chain: ChainType, index: u32, hrp: Option<&str>) -> Option<DerivedAccount> {
    match chain {
        ChainType::Evm => {
            let path = format!("m/44'/60'/0'/0/{index}");
            let key = bip32_master(seed).derive_path(&path)?;
            let pub64 = key.public_uncompressed64()?;
            let addr = format!("0x{}", &data_encoding::HEXLOWER.encode(&keccak256(&pub64)[12..]));
            Some(DerivedAccount {
                chain,
                address: addr,
                path,
                private: key.key.to_vec(),
                public: key.public_compressed()?.to_vec(),
            })
        }
        ChainType::Tron => {
            let path = format!("m/44'/195'/0'/0/{index}");
            let key = bip32_master(seed).derive_path(&path)?;
            let pub64 = key.public_uncompressed64()?;
            let mut payload = vec![0x41u8];
            payload.extend_from_slice(&keccak256(&pub64)[12..]);
            Some(DerivedAccount {
                chain,
                address: base58check(&payload),
                path,
                private: key.key.to_vec(),
                public: key.public_compressed()?.to_vec(),
            })
        }
        ChainType::Cosmos => {
            let coin = 118u64;
            let path = format!("m/44'/{coin}'/0'/0/{index}");
            let key = bip32_master(seed).derive_path(&path)?;
            let compressed = key.public_compressed()?;
            let hash = ripemd160(&sha256(&compressed));
            let hrp = hrp.unwrap_or("cosmos");
            Some(DerivedAccount {
                chain,
                address: bech32_encode(hrp, &hash),
                path,
                private: key.key.to_vec(),
                public: compressed.to_vec(),
            })
        }
        ChainType::Bitcoin => {
            // Native segwit p2wpkh, mainnet path (testnet coin 1).
            let path = format!("m/84'/0'/0'/0/{index}");
            let key = bip32_master(seed).derive_path(&path)?;
            let compressed = key.public_compressed()?;
            let h = hash160(&compressed);
            // Segwit v0: data = [witness version 0 (u5)] ++ convertbits(hash160, 8→5)
            use bech32::ToBase32;
            let mut data: Vec<bech32::u5> = vec![bech32::u5::try_from_u8(0).expect("0 is a u5")];
            data.extend(h.to_base32());
            let address = bech32::encode("bc", data, bech32::Variant::Bech32)
                .unwrap_or_default();
            Some(DerivedAccount {
                chain,
                address,
                path,
                private: key.key.to_vec(),
                public: compressed.to_vec(),
            })
        }
        ChainType::Solana => {
            let path = format!("m/44'/501'/{index}'/0'");
            let key: Ed25519Key = slip10_master(seed).derive_path(&path)?;
            let pub32 = key.public();
            Some(DerivedAccount {
                chain,
                address: bs58::encode(&pub32).into_string(),
                path,
                private: key.seed.to_vec(),
                public: pub32.to_vec(),
            })
        }
        ChainType::Aptos => {
            let path = format!("m/54'/6'/0'/0'/{index}'");
            let key: Ed25519Key = slip10_master(seed).derive_path(&path)?;
            let pub32 = key.public();
            let mut data = pub32.to_vec();
            data.push(0x00);
            Some(DerivedAccount {
                chain,
                address: format!("0x{}", data_encoding::HEXLOWER.encode(&sha3_256(&data))),
                path,
                private: key.seed.to_vec(),
                public: pub32.to_vec(),
            })
        }
        ChainType::Sui => {
            let path = format!("m/44'/784'/0'/0'/{index}'");
            let key: Ed25519Key = slip10_master(seed).derive_path(&path)?;
            let pub32 = key.public();
            let mut data = vec![0x00u8];
            data.extend_from_slice(&pub32);
            Some(DerivedAccount {
                chain,
                address: format!("0x{}", data_encoding::HEXLOWER.encode(&blake2b256(&data))),
                path,
                private: key.seed.to_vec(),
                public: pub32.to_vec(),
            })
        }
        ChainType::Octra => {
            if index != 0 {
                return None; // single account per wallet, like the adapter
            }
            // privateSeed = hmac512("Octra seed", bip39Seed)[0..32] — the
            // same single-HMAC scheme the Android OctraAdapter uses.
            let hd_key: &[u8] = b"Octra seed";
            let i = crate::wallet::hd::__hmac512(hd_key, seed);
            let seed32: [u8; 32] = i[..32].try_into().ok()?;
            let key = ed25519_dalek::SigningKey::from_bytes(&seed32);
            let pub32 = key.verifying_key().to_bytes();
            let body = bs58::encode(&sha256(&pub32)).into_string();
            let body = format!("{:0<width$}", body, width = 44).replace('0', "1");
            Some(DerivedAccount {
                chain,
                address: format!("oct{body}"),
                path: "octra/0".into(),
                private: seed32.to_vec(),
                public: pub32.to_vec(),
            })
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wallet::bip39;

    fn seed() -> [u8; 64] {
        bip39::seed_from_mnemonic(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        )
    }

    #[test]
    fn evm_address_matches_known_vector() {
        // The canonical "abandon…about" mnemonic, path m/44'/60'/0'/0/0:
        // 0x9858EfFD232B4033E47d90003D41EC34EcaEda94 (widely published).
        let acct = derive_account(&seed(), ChainType::Evm, 0, None).unwrap();
        assert_eq!(acct.address.to_lowercase(), "0x9858effd232b4033e47d90003d41ec34ecaeda94");
        assert_eq!(acct.path, "m/44'/60'/0'/0/0");
    }

    #[test]
    fn cosmos_bech32_address() {
        let acct = derive_account(&seed(), ChainType::Cosmos, 0, Some("cosmos")).unwrap();
        assert!(acct.address.starts_with("cosmos1"));
        assert_eq!(acct.address.len(), 45);
    }

    #[test]
    fn bitcoin_p2wpkh_address() {
        let acct = derive_account(&seed(), ChainType::Bitcoin, 0, None).unwrap();
        assert!(acct.address.starts_with("bc1"));
        assert_eq!(acct.address.len(), 42);
    }

    #[test]
    fn tron_address_shape() {
        let acct = derive_account(&seed(), ChainType::Tron, 0, None).unwrap();
        assert!(acct.address.starts_with('T'));
        assert_eq!(acct.address.len(), 34);
    }

    #[test]
    fn solana_address_is_base58_32_bytes() {
        let acct = derive_account(&seed(), ChainType::Solana, 0, None).unwrap();
        assert_eq!(acct.path, "m/44'/501'/0'/0'");
        assert_eq!(bs58::decode(&acct.address).into_vec().unwrap().len(), 32);
    }

    #[test]
    fn aptos_sui_octra_shapes() {
        let aptos = derive_account(&seed(), ChainType::Aptos, 0, None).unwrap();
        assert!(aptos.address.starts_with("0x"));
        assert_eq!(aptos.address.len(), 66);
        let sui = derive_account(&seed(), ChainType::Sui, 0, None).unwrap();
        assert!(sui.address.starts_with("0x"));
        assert_eq!(sui.address.len(), 66);
        let octra = derive_account(&seed(), ChainType::Octra, 0, None).unwrap();
        assert!(octra.address.starts_with("oct"));
        assert_eq!(octra.address.len(), 3 + 44);
        assert!(derive_account(&seed(), ChainType::Octra, 1, None).is_none());
    }

    #[test]
    fn derivation_is_deterministic() {
        let a = derive_account(&seed(), ChainType::Evm, 5, None).unwrap();
        let b = derive_account(&seed(), ChainType::Evm, 5, None).unwrap();
        assert_eq!(a.address, b.address);
        let c = derive_account(&seed(), ChainType::Evm, 6, None).unwrap();
        assert_ne!(a.address, c.address);
    }

    #[test]
    fn bech32_known_vector() {
        // Verified against the reference python `bech32` package:
        // bech32_encode("abcdef", convertbits([0,1,2], 8, 5)).
        assert_eq!(bech32_encode("abcdef", b"\x00\x01\x02"), "abcdef1qqqsysk52tv");
        assert_eq!(bech32_encode("abcdef", b""), "abcdef1hu3qdg");
        // 20 zero bytes under hrp "cos" (the shape of a cosmos address body)
        assert_eq!(
            bech32_encode("cos", &[0u8; 20]),
            "cos1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqjynfly"
        );
    }
}
