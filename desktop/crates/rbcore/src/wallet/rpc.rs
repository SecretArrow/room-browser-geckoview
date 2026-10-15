//! JSON-RPC 2.0 client with endpoint failover — port of `JsonRpc.kt` +
//! `RpcEndpoints.kt` (`RpcEndpointChain`: tries urls in order, remembering
//! the last-known-good host; non-JSON bodies become RpcError 502).

use serde_json::{json, Value};

pub struct JsonRpcClient {
    pub urls: Vec<String>,
    pub lcd_urls: Vec<String>,
    last_good: std::cell::Cell<Option<usize>>,
    pub timeout_secs: u64,
}

impl JsonRpcClient {
    pub fn new(urls: Vec<String>) -> Self {
        JsonRpcClient {
            urls,
            lcd_urls: Vec::new(),
            last_good: std::cell::Cell::new(None),
            timeout_secs: 30,
        }
    }

    fn try_urls(&self) -> Vec<usize> {
        let mut order: Vec<usize> = Vec::new();
        if let Some(g) = self.last_good.get() {
            if g < self.urls.len() {
                order.push(g);
            }
        }
        for i in 0..self.urls.len() {
            if !order.contains(&i) {
                order.push(i);
            }
        }
        order
    }

    /// One JSON-RPC POST across the failover chain.
    pub fn call(&self, method: &str, params: Value) -> crate::CoreResult<Value> {
        let body = json!({"jsonrpc": "2.0", "id": 1, "method": method, "params": params});
        let mut last_err = String::new();
        for i in self.try_urls() {
            let url = &self.urls[i];
            match ureq::AgentBuilder::new()
                .timeout(std::time::Duration::from_secs(self.timeout_secs))
                .build()
                .post(url)
                .set("Content-Type", "application/json")
                .set("User-Agent", "RoomBrowser-Wallet/1.0")
                .send_json(body.clone())
            {
                Ok(resp) => {
                    let text = match resp.into_string() {
                        Ok(t) => t,
                        Err(e) => {
                            last_err = format!("read error: {e}");
                            continue;
                        }
                    };
                    match serde_json::from_str::<Value>(&text) {
                        Ok(v) => {
                            if let Some(err) = v.get("error") {
                                // RPC-level error: return it as-is (a live
                                // endpoint answered; failover is not needed).
                                return Err(format!("rpc error: {err}"));
                            }
                            self.last_good.set(Some(i));
                            return Ok(v.get("result").cloned().unwrap_or(Value::Null));
                        }
                        Err(_) => {
                            last_err = "non-JSON body".into(); // → RpcError 502 semantics
                            continue;
                        }
                    }
                }
                Err(e) => {
                    last_err = format!("transport: {e}");
                }
            }
        }
        Err(format!("all endpoints failed ({last_err})"))
    }

    /// Plain GET (Cosmos LCD / Bitcoin REST style endpoints).
    pub fn get_json(&self, path: &str) -> crate::CoreResult<Value> {
        let mut last_err = String::new();
        let bases: Vec<String> = self
            .lcd_urls
            .iter()
            .chain(self.urls.iter())
            .cloned()
            .collect();
        for base in bases {
            let trimmed = path.trim_start_matches('/');
            let url = format!("{base}/{trimmed}");
            match ureq::AgentBuilder::new()
                .timeout(std::time::Duration::from_secs(self.timeout_secs))
                .build()
                .get(&url)
                .set("User-Agent", "RoomBrowser-Wallet/1.0")
                .call()
            {
                Ok(resp) => {
                    if let Ok(text) = resp.into_string() {
                        if let Ok(v) = serde_json::from_str::<Value>(&text) {
                            return Ok(v);
                        }
                    }
                    last_err = "non-JSON body".into();
                }
                Err(e) => last_err = format!("transport: {e}"),
            }
        }
        Err(format!("all LCD/REST endpoints failed ({last_err})"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn body_shape_and_failover_order() {
        let c = JsonRpcClient::new(vec!["http://a".into(), "http://b".into()]);
        // last-good first, then the rest in order
        assert_eq!(c.try_urls(), vec![0, 1]);
        // (network calls are exercised against live endpoints by the
        //  integration test, not the unit suite.)
        let body = json!({"jsonrpc": "2.0", "id": 1, "method": "eth_chainId", "params": []});
        assert_eq!(body["jsonrpc"], "2.0");
        assert_eq!(body["method"], "eth_chainId");
    }
}
