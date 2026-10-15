//! Wallet — port of `core/wallet` (chains, networks, storage, dApp bridge,
//! RPC failover). Key material is encrypted at rest with the same
//! Argon2id+AES-GCM vault crypto the desktop password manager uses.

pub mod bip39;
pub mod chains;
pub mod dapp;
pub mod hd;
pub mod rpc;

mod network_data {
    include!("../wallet_networks_data.rs");
}

use crate::jsonl::{atomic_write, load_lines};
use serde_json::{json, Value};
use std::path::Path;

pub use chains::{ChainType, DerivedAccount};

pub use network_data::{NetworkData, NETWORKS};

/// The runtime network config (mirrors Android's `NetworkConfig`).
#[derive(Debug, Clone)]
pub struct NetworkConfig {
    pub id: String,
    pub chain_type: ChainType,
    pub chain_id: String,
    pub name: String,
    pub rpc_urls: Vec<String>,
    pub native_symbol: String,
    pub native_decimals: u32,
    pub explorer_url: String,
    pub lcd_url: String,
    pub lcd_fallback_urls: Vec<String>,
    pub indexer_url: String,
    pub is_testnet: bool,
    pub bech32_hrp: String,
    pub coin_type: u32,
}

impl NetworkConfig {
    pub fn from_data(d: &NetworkData) -> NetworkConfig {
        NetworkConfig {
            id: d.id.to_string(),
            chain_type: ChainType::parse(d.chain_type).unwrap_or(ChainType::Evm),
            chain_id: d.chain_id.to_string(),
            name: d.name.to_string(),
            rpc_urls: d.rpc_urls.iter().map(|s| s.to_string()).collect(),
            native_symbol: d.native_symbol.to_string(),
            native_decimals: d.native_decimals,
            explorer_url: d.explorer_url.to_string(),
            lcd_url: d.lcd_url.to_string(),
            lcd_fallback_urls: d.lcd_fallback_urls.iter().map(|s| s.to_string()).collect(),
            indexer_url: d.indexer_url.to_string(),
            is_testnet: d.is_testnet,
            bech32_hrp: d.bech32_hrp.to_string(),
            coin_type: d.coin_type,
        }
    }

    pub fn to_json(&self) -> Value {
        json!({
            "id": self.id,
            "chainType": self.chain_type.as_str(),
            "chainId": self.chain_id,
            "name": self.name,
            "rpcUrls": self.rpc_urls,
            "nativeSymbol": self.native_symbol,
            "nativeDecimals": self.native_decimals,
            "explorerUrl": if self.explorer_url.is_empty() { Value::Null } else { json!(self.explorer_url) },
            "lcdUrl": if self.lcd_url.is_empty() { Value::Null } else { json!(self.lcd_url) },
            "lcdFallbackUrls": self.lcd_fallback_urls,
            "indexerUrl": if self.indexer_url.is_empty() { Value::Null } else { json!(self.indexer_url) },
            "isTestnet": self.is_testnet,
            "bech32Hrp": if self.bech32_hrp.is_empty() { Value::Null } else { json!(self.bech32_hrp) },
            "coinType": self.coin_type,
        })
    }

    pub fn from_json(v: &Value) -> Option<NetworkConfig> {
        let id = crate::jsonl::str_field(v, "id");
        if id.is_empty() {
            return None;
        }
        Some(NetworkConfig {
            id,
            chain_type: ChainType::parse(&crate::jsonl::str_field(v, "chainType"))?,
            chain_id: crate::jsonl::str_field(v, "chainId"),
            name: crate::jsonl::str_field(v, "name"),
            rpc_urls: v
                .get("rpcUrls")
                .and_then(|x| x.as_array())
                .map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).collect())
                .unwrap_or_default(),
            native_symbol: crate::jsonl::str_field(v, "nativeSymbol"),
            native_decimals: crate::jsonl::int_field(v, "nativeDecimals").clamp(0, 36) as u32,
            explorer_url: crate::jsonl::str_field(v, "explorerUrl"),
            lcd_url: crate::jsonl::str_field(v, "lcdUrl"),
            lcd_fallback_urls: v
                .get("lcdFallbackUrls")
                .and_then(|x| x.as_array())
                .map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).collect())
                .unwrap_or_default(),
            indexer_url: crate::jsonl::str_field(v, "indexerUrl"),
            is_testnet: crate::jsonl::bool_field(v, "isTestnet"),
            bech32_hrp: crate::jsonl::str_field(v, "bech32Hrp"),
            coin_type: crate::jsonl::int_field(v, "coinType") as u32,
        })
    }
}

/// The bundled network list (identical to the Android snapshot, endpoints
/// included — curation decides where a user starts).
pub fn bundled_networks() -> Vec<NetworkConfig> {
    NETWORKS.iter().map(NetworkConfig::from_data).collect()
}

/// A stored wallet + its accounts + the network rows that belong to the
/// profile. Desktop storage: `wallets.jsonl` under the profile settings dir,
/// one object per profile, fields:
/// `{"id","profile_id","label","mnemonic_enc","created_at","accounts":[…],
///   "networks":[…],"active_networks":{…}}`
#[derive(Debug, Clone)]
pub struct WalletRecord {
    pub id: String,
    pub profile_id: String,
    pub label: String,
    /// Argon2id+AES-GCM sealed BIP39 mnemonic (base64).
    pub mnemonic_enc: String,
    pub created_at: i64,
    pub accounts: Vec<WalletAccount>,
    pub networks: Vec<NetworkConfig>,
    /// chain type → network id
    pub active_networks: std::collections::BTreeMap<String, String>,
}

#[derive(Debug, Clone)]
pub struct WalletAccount {
    pub chain: ChainType,
    pub address: String,
    pub label: String,
    pub path: String,
    /// "DERIVED" or "IMPORTED".
    pub source: String,
    /// Imported private keys only (sealed, base64); derived keys are
    /// re-derived from the mnemonic on use and never stored.
    pub private_key_enc: Option<String>,
}

pub fn wallet_path(data: &Path, profile_id: &str) -> PathBuf2 {
    crate::paths::join(&crate::paths::profile_settings(data, profile_id), "wallets.jsonl")
}
type PathBuf2 = std::path::PathBuf;

pub fn load_wallet(data: &Path, profile_id: &str) -> Option<WalletRecord> {
    let rows = load_lines(&wallet_path(data, profile_id), 16 * 1024);
    for v in rows {
        if crate::jsonl::str_field(&v, "profile_id") == profile_id {
            return wallet_from_json(&v);
        }
    }
    None
}

pub fn wallet_from_json(v: &Value) -> Option<WalletRecord> {
    let id = crate::jsonl::str_field(v, "id");
    if id.is_empty() {
        return None;
    }
    let accounts = v
        .get("accounts")
        .and_then(|x| x.as_array())
        .map(|a| {
            a.iter()
                .filter_map(|x| {
                    Some(WalletAccount {
                        chain: ChainType::parse(&crate::jsonl::str_field(x, "chain"))?,
                        address: crate::jsonl::str_field(x, "address"),
                        label: crate::jsonl::str_field(x, "label"),
                        path: crate::jsonl::str_field(x, "path"),
                        source: crate::jsonl::str_field(x, "source"),
                        private_key_enc: x.get("private_key_enc").and_then(|p| p.as_str()).map(String::from),
                    })
                })
                .collect()
        })
        .unwrap_or_default();
    let networks = v
        .get("networks")
        .and_then(|x| x.as_array())
        .map(|a| a.iter().filter_map(NetworkConfig::from_json).collect())
        .unwrap_or_default();
    let mut active = std::collections::BTreeMap::new();
    if let Some(map) = v.get("active_networks").and_then(|x| x.as_object()) {
        for (k, val) in map {
            if let Some(s) = val.as_str() {
                active.insert(k.clone(), s.to_string());
            }
        }
    }
    Some(WalletRecord {
        id,
        profile_id: crate::jsonl::str_field(v, "profile_id"),
        label: crate::jsonl::str_field(v, "label"),
        mnemonic_enc: crate::jsonl::str_field(v, "mnemonic_enc"),
        created_at: crate::jsonl::int_field(v, "created_at"),
        accounts,
        networks,
        active_networks: active,
    })
}

pub fn save_wallet(data: &Path, w: &WalletRecord) -> crate::CoreResult<()> {
    let path = wallet_path(data, &w.profile_id);
    // single-row store per profile
    let row = json!({
        "id": w.id,
        "profile_id": w.profile_id,
        "label": w.label,
        "mnemonic_enc": w.mnemonic_enc,
        "created_at": w.created_at,
        "accounts": w.accounts.iter().map(|a| json!({
            "chain": a.chain.as_str(),
            "address": a.address,
            "label": a.label,
            "path": a.path,
            "source": a.source,
            "private_key_enc": a.private_key_enc,
        })).collect::<Vec<_>>(),
        "networks": w.networks.iter().map(|n| n.to_json()).collect::<Vec<_>>(),
        "active_networks": w.active_networks,
    });
    atomic_write(&path, format!("{}\n", serde_json::to_string(&row).unwrap_or_default()).as_bytes())
        .map_err(|e| format!("wallet save failed: {e}"))
}

/// Creates a wallet from a (validated) mnemonic: seals it and derives the
/// standard account set (EVM/SOL/BTC/TRON + COSMOS/APTOS/SUI/OCTRA).
pub fn create_wallet(
    data: &Path,
    profile_id: &str,
    label: &str,
    mnemonic: &str,
    vault_passphrase: &str,
    now: i64,
) -> crate::CoreResult<WalletRecord> {
    if !bip39::validate(mnemonic) {
        return Err("invalid mnemonic (checksum or wordlist)".into());
    }
    let seed = bip39::seed_from_mnemonic(mnemonic);
    let mnemonic_enc = crate::vaultcrypto::seal_string(mnemonic, vault_passphrase)?;
    let mut accounts = Vec::new();
    for chain in [
        ChainType::Evm, ChainType::Solana, ChainType::Bitcoin, ChainType::Tron,
        ChainType::Cosmos, ChainType::Aptos, ChainType::Sui, ChainType::Octra,
    ] {
        let hrp = None;
        if let Some(acct) = chains::derive_account(&seed, chain, 0, hrp) {
            accounts.push(WalletAccount {
                chain: acct.chain,
                address: acct.address,
                label: format!("{} 1", acct.chain.as_str()),
                path: acct.path,
                source: "DERIVED".into(),
                private_key_enc: None,
            });
        }
    }
    let mut active = std::collections::BTreeMap::new();
    let networks = bundled_networks();
    for n in &networks {
        let key = n.chain_type.as_str().to_string();
        // first (mainnet) network per chain type wins the active slot
        if !n.is_testnet && !active.contains_key(&key) {
            active.insert(key, n.id.clone());
        }
    }
    let w = WalletRecord {
        id: uuid::Uuid::new_v4().to_string(),
        profile_id: profile_id.to_string(),
        label: label.to_string(),
        mnemonic_enc,
        created_at: now,
        accounts,
        networks,
        active_networks: active,
    };
    save_wallet(data, &w)?;
    Ok(w)
}

/// Unseals the mnemonic for signing/derivation.
pub fn open_mnemonic(w: &WalletRecord, vault_passphrase: &str) -> crate::CoreResult<String> {
    crate::vaultcrypto::open_string(&w.mnemonic_enc, vault_passphrase)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bundled_networks_match_android_snapshot() {
        let nets = bundled_networks();
        assert_eq!(nets.len(), 42);
        let evm: Vec<&NetworkConfig> = nets.iter().filter(|n| n.chain_type == ChainType::Evm).collect();
        assert_eq!(evm.len(), 23);
        let eth = nets.iter().find(|n| n.id == "EVM:1").unwrap();
        assert_eq!(eth.name, "Ethereum Mainnet");
        assert_eq!(eth.native_symbol, "ETH");
        assert!(eth.rpc_urls.contains(&"https://ethereum-rpc.publicnode.com".to_string()));
        let sol = nets.iter().find(|n| n.id == "SOLANA:mainnet-beta").unwrap();
        assert_eq!(sol.rpc_urls[0], "https://api.mainnet-beta.solana.com");
        let oct = nets.iter().find(|n| n.id == "OCTRA:mainnet").unwrap();
        assert_eq!(oct.chain_type, ChainType::Octra);
        // json roundtrip of a network
        let back = NetworkConfig::from_json(&eth.to_json()).unwrap();
        assert_eq!(back.id, eth.id);
        assert_eq!(back.rpc_urls, eth.rpc_urls);
    }
}
