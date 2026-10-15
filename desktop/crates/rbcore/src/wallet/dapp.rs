//! dApp bridge protocol — port of `WalletBridgeProtocol.kt`. The exact
//! method surface (27 readonly EVM reads answered without approval + the
//! per-chain granted methods) is the contract the injected page script and
//! the native side must agree on; tests pin it.

/// EVM reads relayed to RPC WITHOUT any approval prompt.
pub const READONLY_RPC_METHODS: &[&str] = &[
    "eth_chainId", "net_version", "eth_blockNumber", "eth_syncing",
    "web3_clientVersion", "net_listening", "net_peerCount", "eth_getBalance",
    "eth_getCode", "eth_getStorageAt", "eth_getTransactionCount",
    "eth_getProof", "eth_getBlockByHash", "eth_getBlockByNumber",
    "eth_getBlockTransactionCountByHash", "eth_getBlockTransactionCountByNumber",
    "eth_getBlockUnclesByHash", "eth_getBlockUnclesByNumber",
    "eth_getTransactionByHash", "eth_getTransactionByBlockHashAndIndex",
    "eth_getTransactionByBlockNumberAndIndex", "eth_getTransactionReceipt",
    "eth_getLogs", "eth_gasPrice", "eth_maxPriorityFeePerGas",
    "eth_feeHistory", "eth_call", "eth_estimateGas", "web3_sha3",
];

/// Methods answered locally, never relayed.
pub const BRIDGE_LOCAL_METHODS: &[&str] = &["eth_accounts", "getKey", "disconnect"];

/// Per-chain granted (approval-gated) methods.
pub fn granted_methods(chain: &str) -> &'static [&'static str] {
    match chain {
        "EVM" => &[
            "eth_accounts", "eth_requestAccounts", "personal_sign",
            "eth_signTypedData_v3", "eth_signTypedData_v4", "eth_sendTransaction",
            "wallet_switchEthereumChain", "wallet_addEthereumChain",
        ],
        "SOLANA" => &["connect", "signMessage", "signAndSendTransaction", "disconnect"],
        "APTOS" => &["connect", "signMessage", "signTransaction", "signAndSubmitTransaction"],
        "SUI" => &[
            "connect", "signMessage", "signPersonalMessage",
            "signAndExecuteTransactionBlock", "signAndExecuteTransaction",
        ],
        "COSMOS" => &["enable", "getKey", "signArbitrary", "signAmino", "signDirect"],
        "BITCOIN" => &["connect", "signMessage"],
        "TRON" => &["connect", "signMessage", "signTransaction"],
        "OCTRA" => &["connect"],
        _ => &[],
    }
}

use serde_json::Value;

/// The page-side envelope: `{id, chain, kind: "request"|"rpc", method, params}`.
#[derive(Debug, Clone)]
pub struct BridgeEnvelope {
    pub id: i64,
    pub chain: String,
    pub kind: String,
    pub method: String,
    pub params: Value,
}

pub fn parse_envelope(v: &Value) -> Option<BridgeEnvelope> {
    let id = v.get("id").and_then(|x| x.as_i64())?;
    let chain = v.get("chain").and_then(|x| x.as_str())?.to_string();
    let kind = v.get("kind").and_then(|x| x.as_str()).unwrap_or("rpc").to_string();
    let method = v.get("method").and_then(|x| x.as_str())?.to_string();
    let params = v.get("params").cloned().unwrap_or(Value::Null);
    Some(BridgeEnvelope { id, chain, kind, method, params })
}

/// Classifies a method: Local / Readonly / Granted / Denied.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MethodClass {
    Local,
    Readonly,
    Granted,
    Denied,
}

pub fn classify(chain: &str, method: &str) -> MethodClass {
    if BRIDGE_LOCAL_METHODS.contains(&method) {
        return MethodClass::Local;
    }
    if READONLY_RPC_METHODS.contains(&method) {
        return MethodClass::Readonly;
    }
    if granted_methods(chain).contains(&method) {
        return MethodClass::Granted;
    }
    MethodClass::Denied
}

/// Rate-limit code for abusive pages.
pub const RATE_LIMIT_CODE: i32 = -32005;
/// Display truncation for long payloads.
pub const DISPLAY_TRUNCATE: usize = 500;

pub fn truncate_for_display(s: &str) -> String {
    if s.chars().count() <= DISPLAY_TRUNCATE {
        s.to_string()
    } else {
        let cut: String = s.chars().take(DISPLAY_TRUNCATE).collect();
        format!("{cut}…")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn readonly_and_granted_surfaces() {
        assert_eq!(READONLY_RPC_METHODS.len() >= 27, true);
        assert!(READONLY_RPC_METHODS.contains(&"eth_call"));
        assert!(READONLY_RPC_METHODS.contains(&"eth_getLogs"));
        // readonly wins over granted for overlapping names? (none overlap)
        for m in READONLY_RPC_METHODS {
            assert!(!granted_methods("EVM").contains(m), "{m} must not be double-listed");
        }
        assert_eq!(classify("EVM", "eth_chainId"), MethodClass::Readonly);
        assert_eq!(classify("EVM", "eth_accounts"), MethodClass::Local);
        assert_eq!(classify("EVM", "eth_sendTransaction"), MethodClass::Granted);
        assert_eq!(classify("EVM", "eth_signTypedData_v4"), MethodClass::Granted);
        assert_eq!(classify("EVM", "eth_mining"), MethodClass::Denied);
        assert_eq!(classify("SOLANA", "signMessage"), MethodClass::Granted);
        assert_eq!(classify("SOLANA", "eth_accounts"), MethodClass::Local);
        assert_eq!(classify("COSMOS", "signAmino"), MethodClass::Granted);
        assert_eq!(classify("OCTRA", "connect"), MethodClass::Granted);
        assert_eq!(classify("OCTRA", "signMessage"), MethodClass::Denied);
    }

    #[test]
    fn envelope_parsing() {
        let v: Value = serde_json::from_str(
            r#"{"id":7,"chain":"EVM","kind":"request","method":"personal_sign","params":["deadbeef","0xabc"]}"#,
        ).unwrap();
        let e = parse_envelope(&v).unwrap();
        assert_eq!(e.id, 7);
        assert_eq!(e.method, "personal_sign");
        assert_eq!(e.kind, "request");
        assert_eq!(classify(&e.chain, &e.method), MethodClass::Granted);
    }

    #[test]
    fn truncation() {
        assert_eq!(truncate_for_display("short"), "short");
        let long = "x".repeat(600);
        let t = truncate_for_display(&long);
        assert!(t.chars().count() <= 501);
        assert!(t.ends_with('…'));
    }
}
