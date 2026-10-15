//! Provider gateways — OpenAI-compatible SSE, Ollama NDJSON, Anthropic
//! messages, OpenCode session REST, and the local llama.cpp engine, plus
//! the retry decorator and the prompt-tool fallback for providers without
//! native `tools`.

use crate::agent::ToolCall;
use serde_json::{json, Value};
use std::io::BufRead;
use std::time::Duration;

#[derive(Debug, Clone, PartialEq)]
pub enum StreamPart {
    Text(String),
    Thinking(String),
    ToolCall { id: String, name: String, arguments: String },
    Done,
}

/// One turn request against a provider.
pub struct TurnRequest {
    pub messages: Vec<crate::agent::ChatMessage>,
    pub tools: Vec<Value>,
    pub temperature: f64,
    pub max_tokens: i64,
}

/// Transport-independent gateway surface (the frontends and the loop only
/// ever see this; LOCAL talks to llama.cpp, everything else to HTTP).
pub trait AgentGateway {
    fn id(&self) -> &'static str;
    fn supports_native_tools(&self) -> bool;
    fn supports_vision(&self) -> bool;
    /// Streams one turn; parts arrive in order.
    fn stream_turn(&mut self, req: &TurnRequest) -> crate::CoreResult<Vec<StreamPart>>;
    /// Lists models (GET /models | /api/tags), provider-shaped.
    fn list_models(&self) -> crate::CoreResult<Vec<String>>;
}

fn http_agent() -> ureq::Agent {
    ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(20))
        .timeout_read(Duration::from_secs(180))
        .build()
}

// ------------------------------------------------------------------
// SSE / NDJSON line parsers (pure, unit-tested)

/// Parses an SSE stream into data payloads (`data: …` lines; comments and
/// keep-alives ignored; `[DONE]` sentinel surfaced as None).
pub fn parse_sse_data_line(line: &str) -> Option<Option<String>> {
    let l = line.trim_end_matches('\r');
    if l.is_empty() || l.starts_with(':') {
        return Some(None); // keep-alive / comment
    }
    let payload = l.strip_prefix("data:")?;
    let payload = payload.strip_prefix(' ').unwrap_or(payload);
    if payload == "[DONE]" {
        return None;
    }
    Some(Some(payload.to_string()))
}

/// Extracts text + tool-call deltas from an OpenAI chat.completions chunk.
pub fn parse_openai_chunk(payload: &str) -> Vec<StreamPart> {
    let mut out = Vec::new();
    let Ok(v) = serde_json::from_str::<Value>(payload) else { return out };
    let Some(choices) = v.get("choices").and_then(|c| c.as_array()) else { return out };
    for choice in choices {
        let delta = choice.get("delta").cloned().unwrap_or(Value::Null);
        if let Some(content) = delta.get("content").and_then(|c| c.as_str()) {
            if !content.is_empty() {
                out.push(StreamPart::Text(content.to_string()));
            }
        }
        if let Some(reasoning) = delta.get("reasoning_content").and_then(|c| c.as_str()) {
            if !reasoning.is_empty() {
                out.push(StreamPart::Thinking(reasoning.to_string()));
            }
        }
        if let Some(calls) = delta.get("tool_calls").and_then(|c| c.as_array()) {
            for call in calls {
                let index = call.get("index").and_then(|i| i.as_u64()).unwrap_or(0) as usize;
                let id = call.get("id").and_then(|i| i.as_str()).unwrap_or("");
                let fname = call
                    .get("function")
                    .and_then(|f| f.get("name"))
                    .and_then(|n| n.as_str())
                    .unwrap_or("");
                let fargs = call
                    .get("function")
                    .and_then(|f| f.get("arguments"))
                    .and_then(|a| a.as_str())
                    .unwrap_or("");
                out.push(StreamPart::ToolCall {
                    id: if id.is_empty() { format!("call_{index}") } else { id.to_string() },
                    name: fname.to_string(),
                    arguments: fargs.to_string(),
                });
            }
        }
    }
    out
}

/// Extracts a complete message from an Ollama /api/chat NDJSON line.
pub fn parse_ollama_line(payload: &str) -> Vec<StreamPart> {
    let mut out = Vec::new();
    let Ok(v) = serde_json::from_str::<Value>(payload) else { return out };
    if let Some(content) = v.get("message").and_then(|m| m.get("content")).and_then(|c| c.as_str()) {
        if !content.is_empty() {
            out.push(StreamPart::Text(content.to_string()));
        }
    }
    if v.get("done").and_then(|d| d.as_bool()) == Some(true) {
        out.push(StreamPart::Done);
    }
    out
}

/// Extracts text from an Anthropic messages SSE event.
pub fn parse_anthropic_event(event: &str, payload: &str) -> Vec<StreamPart> {
    let mut out = Vec::new();
    let Ok(v) = (|| serde_json::from_str::<Value>(payload))() else { return out };
    match event {
        "content_block_delta" => {
            let delta = v.get("delta").cloned().unwrap_or(Value::Null);
            if let Some(t) = delta.get("text").and_then(|x| x.as_str()) {
                out.push(StreamPart::Text(t.to_string()));
            }
            if let Some(t) = delta.get("thinking").and_then(|x| x.as_str()) {
                out.push(StreamPart::Thinking(t.to_string()));
            }
            // input_json_delta accumulates tool arguments
            if let Some(p) = delta.get("partial_json").and_then(|x| x.as_str()) {
                out.push(StreamPart::ToolCall {
                    id: v.get("index").and_then(|i| i.as_u64()).map(|i| format!("toolu_{i}")).unwrap_or_default(),
                    name: String::new(),
                    arguments: p.to_string(),
                });
            }
        }
        "content_block_start" => {
            if let Some(tool) = v
                .get("content_block")
                .and_then(|b| b.get("name"))
                .and_then(|n| n.as_str())
            {
                out.push(StreamPart::ToolCall {
                    id: v.get("index").and_then(|i| i.as_u64()).map(|i| format!("toolu_{i}")).unwrap_or_default(),
                    name: tool.to_string(),
                    arguments: String::new(),
                });
            }
        }
        "message_stop" => out.push(StreamPart::Done),
        _ => {}
    }
    out
}

// ------------------------------------------------------------------
// OpenAI-compatible gateway (also used by Anthropic's OpenAI fallback)

pub struct OpenAiGateway {
    pub base_url: String,
    pub api_key: String,
    pub model: String,
}

impl OpenAiGateway {
    fn apply_headers(&self, req: ureq::Request) -> ureq::Request {
        let req = req.set("Content-Type", "application/json");
        if !self.api_key.is_empty() {
            req.set("Authorization", &format!("Bearer {}", self.api_key))
        } else {
            req
        }
    }
}

impl AgentGateway for OpenAiGateway {
    fn id(&self) -> &'static str {
        "openai"
    }
    fn supports_native_tools(&self) -> bool {
        true
    }
    fn supports_vision(&self) -> bool {
        true
    }
    fn stream_turn(&mut self, req: &TurnRequest) -> crate::CoreResult<Vec<StreamPart>> {
        let body = json!({
            "model": self.model,
            "messages": Value::Array(req.messages.iter().map(|m| {
                let mut o = json!({"role": m.role, "content": m.content});
                if !m.tool_calls.is_empty() {
                    o["tool_calls"] = Value::Array(m.tool_calls.iter().map(|c| json!({
                        "id": c.id, "type": "function",
                        "function": {"name": c.name, "arguments": serde_json::to_string(&c.arguments).unwrap_or_default()}
                    })).collect::<Vec<_>>());
                }
                if m.role == "tool" {
                    o["tool_call_id"] = json!(m.tool_call_id);
                }
                o
            }).collect::<Vec<_>>()),
            "tools": Value::Array(req.tools.clone()),
            "temperature": req.temperature,
            "max_tokens": req.max_tokens,
            "stream": true,
        });
        let req = http_agent()
            .post(&format!("{}/chat/completions", self.base_url.trim_end_matches('/')));
        let resp = self.apply_headers(req).send_json(body).map_err(|e| format!("gateway: {e}"))?;
        let reader = std::io::BufReader::new(resp.into_reader());
        let mut parts = Vec::new();
        let mut event = String::new();
        for line in reader.lines() {
            let line = line.map_err(|e| format!("stream read: {e}"))?;
            if let Some(name) = line.strip_prefix("event:") {
                event = name.trim().to_string();
                continue;
            }
            let Some(Some(payload)) = parse_sse_data_line(&line) else { break };
            // Anthropic-style events carry an event: line; OpenAI chunks do not.
            if event.is_empty() {
                parts.extend(parse_openai_chunk(&payload));
            } else {
                parts.extend(parse_anthropic_event(&event, &payload));
                event.clear();
            }
        }
        Ok(parts)
    }
    fn list_models(&self) -> crate::CoreResult<Vec<String>> {
        let req = http_agent()
            .get(&format!("{}/models", self.base_url.trim_end_matches('/')));
        let resp = self.apply_headers(req).call().map_err(|e| format!("models: {e}"))?;
        let v: Value = resp
            .into_json()
            .map_err(|e| format!("models body: {e}"))?;
        Ok(v
            .get("data")
            .and_then(|d| d.as_array())
            .map(|a| a.iter().filter_map(|m| m.get("id").and_then(|i| i.as_str()).map(String::from)).collect())
            .unwrap_or_default())
    }
}

// ------------------------------------------------------------------
// Ollama gateway

pub struct OllamaGateway {
    pub base_url: String,
    pub api_key: String,
    pub model: String,
    pub num_ctx: i64,
    pub keep_alive_minutes: i64,
}

impl AgentGateway for OllamaGateway {
    fn id(&self) -> &'static str {
        "ollama"
    }
    fn supports_native_tools(&self) -> bool {
        true
    }
    fn supports_vision(&self) -> bool {
        true
    }
    fn stream_turn(&mut self, req: &TurnRequest) -> crate::CoreResult<Vec<StreamPart>> {
        let body = json!({
            "model": self.model,
            "messages": Value::Array(req.messages.iter().map(|m| {
                let mut o = json!({"role": m.role, "content": m.content});
                if !m.tool_calls.is_empty() {
                    o["tool_calls"] = Value::Array(m.tool_calls.iter().map(|c| json!({
                        "function": {"name": c.name, "arguments": c.arguments}
                    })).collect::<Vec<_>>());
                }
                o
            }).collect::<Vec<_>>()),
            "tools": Value::Array(req.tools.clone()),
            "stream": true,
            "options": {"temperature": req.temperature, "num_ctx": self.num_ctx},
            "keep_alive": json!(format!("{}m", self.keep_alive_minutes)),
        });
        let resp = http_agent()
            .post(&format!("{}/api/chat", self.base_url.trim_end_matches('/')))
            .set("Content-Type", "application/json")
            .set("Authorization", &format!("Bearer {}", self.api_key))
            .timeout(Duration::from_secs(240))
            .send_json(body)
            .map_err(|e| format!("ollama: {e}"))?;
        let reader = std::io::BufReader::new(resp.into_reader());
        let mut parts = Vec::new();
        for line in reader.lines() {
            let line = line.map_err(|e| format!("ndjson read: {e}"))?;
            if line.is_empty() {
                continue;
            }
            parts.extend(parse_ollama_line(&line));
        }
        Ok(parts)
    }
    fn list_models(&self) -> crate::CoreResult<Vec<String>> {
        let resp = http_agent()
            .get(&format!("{}/api/tags", self.base_url.trim_end_matches('/')))
            .call()
            .map_err(|e| format!("tags: {e}"))?;
        let v: Value = resp.into_json().map_err(|e| format!("tags body: {e}"))?;
        Ok(v
            .get("models")
            .and_then(|d| d.as_array())
            .map(|a| a.iter().filter_map(|m| m.get("name").and_then(|i| i.as_str()).map(String::from)).collect())
            .unwrap_or_default())
    }
}

// ------------------------------------------------------------------
// Anthropic messages gateway (native shape)

pub struct AnthropicGateway {
    pub base_url: String,
    pub api_key: String,
    pub model: String,
}

impl AgentGateway for AnthropicGateway {
    fn id(&self) -> &'static str {
        "anthropic"
    }
    fn supports_native_tools(&self) -> bool {
        true
    }
    fn supports_vision(&self) -> bool {
        true
    }
    fn stream_turn(&mut self, req: &TurnRequest) -> crate::CoreResult<Vec<StreamPart>> {
        // system goes top-level; tool results become user blocks.
        let mut system = String::new();
        let mut messages: Vec<Value> = Vec::new();
        for m in &req.messages {
            match m.role.as_str() {
                "system" => system.push_str(&m.content),
                "assistant" => messages.push(json!({"role": "assistant", "content": m.content})),
                "tool" => messages.push(json!({"role": "user", "content": [
                    {"type": "tool_result", "tool_use_id": m.tool_call_id, "content": m.content}
                ]})),
                _ => messages.push(json!({"role": "user", "content": m.content})),
            }
        }
        let tools: Vec<Value> = req
            .tools
            .iter()
            .map(|t| {
                let f = t.get("function").cloned().unwrap_or(Value::Null);
                json!({
                    "name": f.get("name"),
                    "description": f.get("description"),
                    "input_schema": f.get("parameters"),
                })
            })
            .collect();
        let mut body = json!({
            "model": self.model,
            "max_tokens": req.max_tokens,
            "temperature": req.temperature,
            "stream": true,
            "messages": messages,
            "tools": tools,
        });
        if !system.is_empty() {
            body["system"] = json!(system);
        }
        let resp = http_agent()
            .post(&format!("{}/messages", self.base_url.trim_end_matches('/')))
            .set("Content-Type", "application/json")
            .set("x-api-key", &self.api_key)
            .set("Authorization", &format!("Bearer {}", self.api_key))
            .set("anthropic-version", "2023-06-01")
            .send_json(body)
            .map_err(|e| format!("anthropic: {e}"))?;
        let reader = std::io::BufReader::new(resp.into_reader());
        let mut parts = Vec::new();
        let mut event = String::new();
        for line in reader.lines() {
            let line = line.map_err(|e| format!("stream read: {e}"))?;
            if let Some(name) = line.strip_prefix("event:") {
                event = name.trim().to_string();
                continue;
            }
            let Some(Some(payload)) = parse_sse_data_line(&line) else { break };
            if event.is_empty() {
                parts.extend(parse_openai_chunk(&payload)); // OpenAI-shaped fallback
            } else {
                parts.extend(parse_anthropic_event(&event, &payload));
                event.clear();
            }
        }
        Ok(parts)
    }
    fn list_models(&self) -> crate::CoreResult<Vec<String>> {
        // Anthropic is probed through the OpenAI /models shape, exactly like
        // the Android edition does before falling back.
        let g = OpenAiGateway {
            base_url: self.base_url.clone(),
            api_key: self.api_key.clone(),
            model: self.model.clone(),
        };
        g.list_models()
    }
}

// ------------------------------------------------------------------
// Prompt-tool decorator: providers without native tools get the catalogue
/// rendered into the system prompt and tool calls parsed from fenced JSON
/// blocks (OpenCodeToolCallParser's contract).

pub fn render_prompt_tools(tools: &[Value]) -> String {
    let mut s = String::from(
        "You can act by emitting a tool call as a fenced block on its own line:\n\
         ```tool\n{\"name\": \"<tool>\", \"arguments\": { ... }}\n```\n\
         Available tools:\n",
    );
    for t in tools {
        let f = t.get("function").cloned().unwrap_or(Value::Null);
        let name = f.get("name").and_then(|n| n.as_str()).unwrap_or("");
        let desc = f.get("description").and_then(|d| d.as_str()).unwrap_or("");
        s.push_str(&format!("- {name}: {desc}\n"));
    }
    s.push_str("Emit at most one tool block per reply. After the tool runs, its result arrives as the next user message.\n");
    s
}

/// Parses ```tool fenced JSON blocks out of assistant text.
pub fn parse_tool_blocks(text: &str) -> (Vec<ToolCall>, String) {
    let mut calls = Vec::new();
    let mut rest = String::new();
    let mut remaining = text;
    while let Some(start) = remaining.find("```tool") {
        let after = &remaining[start + 7..];
        if let Some(end) = after.find("```") {
            let block = &after[..end];
            if let Ok(v) = serde_json::from_str::<Value>(block.trim()) {
                calls.push(ToolCall {
                    id: format!("call_{}", calls.len()),
                    name: v.get("name").and_then(|n| n.as_str()).unwrap_or("").to_string(),
                    arguments: v.get("arguments").cloned().unwrap_or(Value::Null),
                });
            }
            rest.push_str(&remaining[..start]);
            remaining = &after[end + 3..];
            continue;
        }
        break;
    }
    rest.push_str(remaining);
    (calls, rest.trim().to_string())
}

// ------------------------------------------------------------------
// Retry decorator

pub struct RetryPolicy {
    pub max_attempts: usize,
    pub delay_ms: u64,
}

impl Default for RetryPolicy {
    fn default() -> Self {
        RetryPolicy { max_attempts: 2, delay_ms: 1500 }
    }
}

pub fn retry_stream<G: AgentGateway>(
    gateway: &mut G,
    req: &TurnRequest,
    policy: &RetryPolicy,
) -> crate::CoreResult<Vec<StreamPart>> {
    let mut last_err = String::new();
    for attempt in 1..=policy.max_attempts {
        match gateway.stream_turn(req) {
            Ok(parts) => return Ok(parts),
            Err(e) => {
                last_err = e;
                if attempt < policy.max_attempts {
                    std::thread::sleep(Duration::from_millis(policy.delay_ms));
                }
            }
        }
    }
    Err(last_err)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sse_line_parsing() {
        assert_eq!(parse_sse_data_line("data: {\"x\":1}"), Some(Some("{\"x\":1}".into())));
        assert_eq!(parse_sse_data_line("data:[DONE]"), None);
        assert_eq!(parse_sse_data_line(": keep-alive"), Some(None));
        assert_eq!(parse_sse_data_line(""), Some(None));
        // an event-name line is not a data line at all
        assert_eq!(parse_sse_data_line("event: message"), None);
    }

    #[test]
    fn openai_chunk_shapes() {
        let parts = parse_openai_chunk(
            r#"{"choices":[{"delta":{"content":"hi"}}]}"#,
        );
        assert_eq!(parts, vec![StreamPart::Text("hi".into())]);
        let parts = parse_openai_chunk(
            r#"{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"click","arguments":"{\"re"}}]}}]}"#,
        );
        assert!(matches!(&parts[0], StreamPart::ToolCall { name, arguments, .. } if name == "click" && arguments == "{\"re"));
        // id-less calls get the call_N form
        let parts = parse_openai_chunk(
            r#"{"choices":[{"delta":{"tool_calls":[{"index":2,"function":{"name":"read_page","arguments":""}}]}}]}"#,
        );
        assert!(matches!(&parts[0], StreamPart::ToolCall { id, .. } if id == "call_2"));
    }

    #[test]
    fn ollama_line_shapes() {
        let parts = parse_ollama_line(r#"{"message":{"content":"ok"},"done":false}"#);
        assert_eq!(parts, vec![StreamPart::Text("ok".into())]);
        let parts = parse_ollama_line(r#"{"done":true}"#);
        assert!(parts.contains(&StreamPart::Done));
    }

    #[test]
    fn anthropic_event_shapes() {
        let parts = parse_anthropic_event("content_block_delta", r#"{"delta":{"text":"hey"}}"#);
        assert_eq!(parts, vec![StreamPart::Text("hey".into())]);
        let parts = parse_anthropic_event("content_block_start", r#"{"index":0,"content_block":{"type":"tool_use","name":"click"}}"#);
        assert!(matches!(&parts[0], StreamPart::ToolCall { name, .. } if name == "click"));
        let parts = parse_anthropic_event("message_stop", "{}");
        assert!(parts.contains(&StreamPart::Done));
    }

    #[test]
    fn prompt_tool_roundtrip() {
        let tools = crate::agent::tool_defs();
        let rendered = render_prompt_tools(&tools);
        assert!(rendered.contains("- click: Click the element"));
        let text = "I'll do it.\n```tool\n{\"name\":\"click\",\"arguments\":{\"ref\":3}}\n```\nDone thinking.";
        let (calls, rest) = parse_tool_blocks(text);
        assert_eq!(calls.len(), 1);
        assert_eq!(calls[0].name, "click");
        assert_eq!(calls[0].arguments["ref"], 3);
        assert_eq!(rest, "I'll do it.\n\nDone thinking.");
        // plain text passes through untouched
        let (none, same) = parse_tool_blocks("no tools here");
        assert!(none.is_empty());
        assert_eq!(same, "no tools here");
    }
}
