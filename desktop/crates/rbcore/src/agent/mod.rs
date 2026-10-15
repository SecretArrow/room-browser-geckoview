//! The AI agent — port of `core/domain/.../agent/` + the app-layer gates.
//! One catalogue of 58 tools, the same layered gating, the same loop
//! contracts (MAX_CONTINUE_NUDGES=2, steps 1..200 default 75, history 40,
//! tool results capped 6000 chars).

pub mod executor;
pub mod gateway;
pub mod gates;
pub mod loop_runner;
pub mod plan;
pub mod sessions;
pub mod tasks;

use serde_json::{json, Value};

/// Agent message roles.
#[derive(Debug, Clone)]
pub struct ChatMessage {
    pub role: String, // system | user | assistant | tool
    pub content: String,
    pub tool_calls: Vec<ToolCall>,
    pub tool_call_id: String,
    pub tool_name: String,
}

impl ChatMessage {
    pub fn system(content: &str) -> Self {
        ChatMessage { role: "system".into(), content: content.into(), tool_calls: Vec::new(), tool_call_id: String::new(), tool_name: String::new() }
    }
    pub fn user(content: &str) -> Self {
        ChatMessage { role: "user".into(), content: content.into(), tool_calls: Vec::new(), tool_call_id: String::new(), tool_name: String::new() }
    }
    pub fn assistant(content: &str) -> Self {
        ChatMessage { role: "assistant".into(), content: content.into(), tool_calls: Vec::new(), tool_call_id: String::new(), tool_name: String::new() }
    }
}

#[derive(Debug, Clone)]
pub struct ToolCall {
    pub id: String,
    pub name: String,
    pub arguments: Value,
}

// ------------------------------------------------------------------
// The tool catalogue (58 tools; names are the wire contract)

pub struct ToolDef {
    pub name: &'static str,
    pub description: &'static str,
    /// JSON-schema properties, compact form.
    pub props: &'static [(&'static str, &'static str, bool)], // (name, type, required)
}

const P_URL_REQ: &[(&str, &str, bool)] = &[("url", "string", true)];
const P_QUERY_REQ: &[(&str, &str, bool)] = &[("query", "string", true)];
const P_REF_REQ: &[(&str, &str, bool)] = &[("ref", "integer", true)];
const P_NONE: &[(&str, &str, bool)] = &[];

pub fn tool_defs() -> Vec<Value> {
    let defs: &[ToolDef] = &[
        // ---- page / navigation (17)
        ToolDef { name: "navigate", description: "Navigate the bound tab to a URL and wait for the page to finish loading.", props: P_URL_REQ },
        ToolDef { name: "search_web", description: "Run the profile's search engine and return the results page text.", props: P_QUERY_REQ },
        ToolDef { name: "read_page", description: "Read the page: URL, title, visible text (6000-char cap) and interactive elements as [ref] numbers.", props: P_NONE },
        ToolDef { name: "see_page", description: "Capture a screenshot of the page and attach it as an image for you to look at.", props: &[("question", "string", false), ("reason", "string", false), ("full_page", "boolean", false), ("annotate", "boolean", false)] },
        ToolDef { name: "compare_screenshots", description: "Compare the latest two captures of this turn; returns the percentage of the frame that changed.", props: &[("tolerance", "integer", false)] },
        ToolDef { name: "click", description: "Click the element with the given [ref].", props: P_REF_REQ },
        ToolDef { name: "fill_input", description: "Type text into the input with the given [ref].", props: &[("ref", "integer", true), ("text", "string", true)] },
        ToolDef { name: "press_enter", description: "Press Enter, optionally on the element with the given [ref].", props: &[("ref", "integer", false)] },
        ToolDef { name: "scroll", description: "Scroll the page up or down by a percentage of the viewport (default 80%).", props: &[("direction", "string", true), ("amount", "integer", false)] },
        ToolDef { name: "go_back", description: "Go back one step in history.", props: P_NONE },
        ToolDef { name: "reload", description: "Reload the current page. Console and network buffers survive.", props: P_NONE },
        ToolDef { name: "forward", description: "Go forward one step in history.", props: P_NONE },
        ToolDef { name: "open_new_tab", description: "Open a new tab, optionally at a URL.", props: &[("url", "string", false)] },
        ToolDef { name: "list_tabs", description: "List the open tabs with their titles and URLs.", props: P_NONE },
        ToolDef { name: "switch_tab", description: "Make the tab at the given index the active (and bound) tab.", props: &[("index", "integer", true)] },
        ToolDef { name: "close_tab", description: "Close the current (bound) tab.", props: P_NONE },
        // ---- form / keys / waits (4)
        ToolDef { name: "select_option", description: "Choose an option of a select element by value or visible text.", props: &[("ref", "integer", true), ("value", "string", true)] },
        ToolDef { name: "press_keys", description: "Press a key chord, e.g. Enter, Escape, Tab, Control+a, F1-F12.", props: &[("keys", "string", true), ("ref", "integer", false)] },
        ToolDef { name: "wait", description: "Wait for a number of milliseconds (200-20000, default 1500).", props: &[("ms", "integer", false)] },
        ToolDef { name: "wait_for", description: "Wait until the page contains the given text (timeout 500-30000 ms, default 10000).", props: &[("text", "string", true), ("timeout_ms", "integer", false)] },
        // ---- JavaScript / compute / files (6)
        ToolDef { name: "run_js", description: "Run JavaScript in the live page and return the last expression's value. Gated by the profile's console approval setting.", props: &[("script", "string", true)] },
        ToolDef { name: "compute_js", description: "Run JavaScript in a detached scratchpad page with no network or filesystem. Requires the agent.compute permission.", props: &[("script", "string", true)] },
        ToolDef { name: "data_transform", description: "Pure host-side data transform: json_path, csv_rows, aggregate or diff.", props: &[("op", "string", true), ("input", "string", true), ("path", "string", false), ("column", "string", false), ("agg", "string", false), ("compare", "string", false), ("limit", "integer", false)] },
        ToolDef { name: "download_file", description: "Download a file through the browser's download engine.", props: &[("url", "string", true), ("name", "string", false)] },
        ToolDef { name: "save_pdf", description: "Save the current page as a PDF into the downloads folder.", props: &[("name", "string", false)] },
        ToolDef { name: "site_memory", description: "Remember, recall or forget per-site notes for this profile.", props: &[("op", "string", true), ("key", "string", true), ("value", "string", false), ("limit", "integer", false)] },
        // ---- plan / todos (1)
        ToolDef { name: "plan", description: "Replace the task list. Each item has text and a status of pending, in_progress or done.", props: &[("items", "array", true)] },
        // ---- social automation (4)
        ToolDef { name: "auto_like", description: "Like up to 20 visible posts using the page's own like buttons.", props: P_NONE },
        ToolDef { name: "auto_repost", description: "Repost up to 15 visible posts.", props: P_NONE },
        ToolDef { name: "auto_reply", description: "Reply with the given text to the visible post composer.", props: &[("text", "string", true)] },
        ToolDef { name: "auto_post", description: "Type the given text into the page's composer and post it.", props: &[("text", "string", true)] },
        // ---- wallet (5)
        ToolDef { name: "wallet_state", description: "Report whether the wallet is locked, its accounts, active networks and known network ids.", props: P_NONE },
        ToolDef { name: "wallet_requests", description: "List pending dApp requests (host, method, transaction or message).", props: P_NONE },
        ToolDef { name: "wallet_approve", description: "Approve a pending dApp request. IRREVERSIBLE; always asks the user first.", props: &[("request_id", "string", true)] },
        ToolDef { name: "wallet_reject", description: "Reject a pending dApp request.", props: &[("request_id", "string", true)] },
        ToolDef { name: "wallet_switch_network", description: "Switch the wallet to a known, enabled network id such as EVM:137.", props: &[("network_id", "string", true)] },
        // ---- node runtime (2)
        ToolDef { name: "node_run", description: "Run a Node.js script (the body of an async function) with a 30-second budget. Requires the agent.node permission.", props: &[("script", "string", true)] },
        ToolDef { name: "node_packages", description: "List, install or remove npm packages (public registry only; lifecycle scripts are never executed).", props: &[("action", "string", true), ("name", "string", false), ("spec", "string", false)] },
        // ---- app control (9)
        ToolDef { name: "app_open", description: "Open an app screen: home, tabs, bookmarks, history, downloads, privacy, settings, profile_settings, about, theme, agent_settings, agent_chats, ai_tasks, local_ai, passwords, wallet, devices.", props: &[("screen", "string", true)] },
        ToolDef { name: "app_tabs", description: "Manage tabs: list, pin, unpin, duplicate, reopen, close_others, move, group, ungroup, private.", props: &[("action", "string", true), ("index", "integer", false), ("position", "integer", false), ("name", "string", false)] },
        ToolDef { name: "app_data", description: "Manage bookmarks, history or downloads records.", props: &[("kind", "string", true), ("action", "string", true), ("id", "string", false), ("title", "string", false), ("url", "string", false), ("query", "string", false)] },
        ToolDef { name: "app_settings", description: "Read or write a whitelisted profile setting.", props: &[("action", "string", true), ("key", "string", false), ("value", "string", false)] },
        ToolDef { name: "app_shields", description: "Read or toggle the shields for the current site, or clear its site data.", props: &[("action", "string", true), ("enabled", "boolean", false)] },
        ToolDef { name: "app_site_permission", description: "List or set a site permission (camera, microphone, location, notifications, clipboard, bluetooth, usb, popups, downloads, sensors, autoplay).", props: &[("action", "string", true), ("permission", "string", false), ("decision", "string", false)] },
        ToolDef { name: "app_page", description: "Act on the current page: find, reader_on/off, desktop_on/off, bookmark add/remove, devtools_open.", props: &[("action", "string", true), ("query", "string", false)] },
        ToolDef { name: "app_2fa", description: "List 2FA accounts or copy/fill a current code. The setup key is never readable.", props: &[("action", "string", true), ("id", "integer", false), ("ref", "integer", false)] },
        ToolDef { name: "app_notes", description: "List, get, add, update or delete notes.", props: &[("action", "string", true), ("id", "integer", false), ("title", "string", false), ("body", "string", false)] },
        // ---- developer tools (11)
        ToolDef { name: "devtools_reload", description: "Reload the page and watch the console.", props: P_NONE },
        ToolDef { name: "devtools_console", description: "Read, clear or preserve the console buffer, optionally filtered.", props: &[("action", "string", true), ("level", "string", false), ("contains", "string", false)] },
        ToolDef { name: "devtools_network", description: "Read or clear the network buffer, optionally failures only. Headers are redacted.", props: &[("action", "string", true), ("failed_only", "boolean", false), ("contains", "string", false)] },
        ToolDef { name: "devtools_inspect", description: "Inspect one element by [ref] or CSS selector: tag, attributes, box, text.", props: &[("ref", "integer", false), ("selector", "string", false)] },
        ToolDef { name: "devtools_elements", description: "Read the Elements-panel tree by panel node ids.", props: &[("node", "integer", false), ("from", "integer", false)] },
        ToolDef { name: "devtools_storage", description: "Probe the Application panel: storage key counts, service workers, manifest, frames.", props: P_NONE },
        ToolDef { name: "devtools_security", description: "Report the transport and page-observable security state.", props: P_NONE },
        ToolDef { name: "devtools_audit", description: "Run the Audit panel's own probe plus console and failed-request counts.", props: P_NONE },
        ToolDef { name: "devtools_diagnose", description: "Correlate a symptom with console errors, failed requests and elements.", props: &[("focus", "string", false)] },
        ToolDef { name: "devtools_report", description: "Write a redacted diagnostic report file. Gated by diagnostics + export permissions.", props: P_NONE },
        ToolDef { name: "devtools_patch", description: "Apply a live JavaScript patch to the page. ALWAYS user-confirmed; refused for unattended runs.", props: &[("script", "string", true), ("reason", "string", true)] },
    ];
    defs
        .iter()
        .map(|d| {
            let mut required: Vec<&str> = Vec::new();
            let mut properties = serde_json::Map::new();
            for (name, ty, req) in d.props {
                properties.insert(
                    name.to_string(),
                    json!({"type": ty}),
                );
                if *req {
                    required.push(name);
                }
            }
            json!({
                "type": "function",
                "function": {
                    "name": d.name,
                    "description": d.description,
                    "parameters": {
                        "type": "object",
                        "properties": properties,
                        "required": required,
                    },
                },
            })
        })
        .collect()
}

pub fn tool_names() -> Vec<&'static str> {
    // (kept in one place so the catalogue and the name list cannot drift)
    const NAMES: &[&str] = &[
        "navigate", "search_web", "read_page", "see_page", "compare_screenshots",
        "click", "fill_input", "press_enter", "scroll", "go_back", "reload",
        "forward", "open_new_tab", "list_tabs", "switch_tab", "close_tab",
        "select_option", "press_keys", "wait", "wait_for",
        "run_js", "compute_js", "data_transform", "download_file", "save_pdf", "site_memory",
        "plan",
        "auto_like", "auto_repost", "auto_reply", "auto_post",
        "wallet_state", "wallet_requests", "wallet_approve", "wallet_reject", "wallet_switch_network",
        "node_run", "node_packages",
        "app_open", "app_tabs", "app_data", "app_settings", "app_shields",
        "app_site_permission", "app_page", "app_2fa", "app_notes",
        "devtools_reload", "devtools_console", "devtools_network", "devtools_inspect",
        "devtools_elements", "devtools_storage", "devtools_security", "devtools_audit",
        "devtools_diagnose", "devtools_report", "devtools_patch",
    ];
    NAMES.to_vec()
}

/// Tools the Confirm-actions switch applies to.
pub const INTERACTIVE_TOOLS: &[&str] = &[
    "click", "fill_input", "press_enter", "select_option", "press_keys",
    "auto_like", "auto_repost", "auto_reply", "auto_post",
    "wallet_approve", "wallet_switch_network",
    "app_open", "app_tabs", "app_data", "app_settings", "app_shields",
    "app_site_permission", "app_page", "app_2fa", "app_notes",
    "download_file", "save_pdf", "devtools_patch", "node_run", "node_packages",
];

pub const DEVTOOLS_TOOLS: &[&str] = &[
    "devtools_reload", "devtools_console", "devtools_network", "devtools_inspect",
    "devtools_elements", "devtools_storage", "devtools_security", "devtools_audit",
    "devtools_diagnose", "devtools_report", "devtools_patch",
];

pub const WALLET_TOOLS: &[&str] = &[
    "wallet_state", "wallet_requests", "wallet_approve", "wallet_reject",
    "wallet_switch_network",
];

/// Loop contracts (part of the product behaviour, not tuning knobs).
pub const MAX_CONTINUE_NUDGES: usize = 2;
pub const MAX_PLAN_REFUSALS: usize = 2;
pub const MAX_PLAN_NUDGES: usize = 1;
pub const MAX_HISTORY: usize = 40;
pub const MAX_TOOL_RESULT_CHARS: usize = 6000;
pub const MAX_STEPS_DEFAULT: usize = 75;
pub const MAX_ACTIONS_PER_TURN: usize = 60;
pub const MAX_ACTIONS_PER_TOOL: usize = 15;
pub const IDENTICAL_ACTION_MIN_INTERVAL_MS: i64 = 900;
pub const MAX_CONSECUTIVE_FAILURES: usize = 3;

#[derive(Debug, Clone)]
pub struct AgentConfig {
    pub steps: usize,
    pub temperature: f64,
    pub plan_before_actions: bool,
    pub plan_is_the_answer: bool,
}

impl Default for AgentConfig {
    fn default() -> Self {
        AgentConfig {
            steps: MAX_STEPS_DEFAULT.clamp(1, 200),
            temperature: 0.2,
            plan_before_actions: true,
            plan_is_the_answer: false,
        }
    }
}

/// Loop events surfaced to the UI (the Android event stream).
#[derive(Debug, Clone)]
pub enum AgentEvent {
    AssistantText(String),
    AssistantThinking(String),
    ToolStarted { name: String, args: Value },
    ToolFinished { name: String, result: String },
    PlanUpdated(Value),
    FinalAnswer(String),
    AgentError(String),
    Notice(String),
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_catalogue_has_58_tools_with_unique_names() {
        let defs = tool_defs();
        assert_eq!(defs.len(), 58, "the Android catalogue has exactly 58 tools");
        let names = tool_names();
        let mut sorted = names.clone();
        sorted.sort();
        sorted.dedup();
        assert_eq!(sorted.len(), names.len(), "no duplicate tool names");
        // the headline members are all present
        for want in [
            "navigate", "read_page", "see_page", "click", "fill_input", "plan",
            "run_js", "compute_js", "data_transform", "site_memory",
            "auto_like", "wallet_approve", "node_run", "node_packages",
            "app_open", "app_tabs", "app_data", "app_settings", "app_shields",
            "app_page", "app_2fa", "app_notes", "devtools_patch",
        ] {
            assert!(names.contains(&want), "missing tool {want}");
        }
    }

    #[test]
    fn tool_json_shape_is_openai_compatible() {
        let defs = tool_defs();
        let first = &defs[0];
        assert_eq!(first["type"], "function");
        assert_eq!(first["function"]["name"], "navigate");
        assert_eq!(first["function"]["parameters"]["type"], "object");
        assert_eq!(first["function"]["parameters"]["required"][0], "url");
        // a tool with no params has an empty required list
        let read_page = defs.iter().find(|d| d["function"]["name"] == "read_page").unwrap();
        assert_eq!(read_page["function"]["parameters"]["required"].as_array().unwrap().len(), 0);
    }

    #[test]
    fn gate_sets_are_subsets_of_the_catalogue() {
        let names = tool_names();
        for set in [INTERACTIVE_TOOLS, DEVTOOLS_TOOLS, WALLET_TOOLS] {
            for t in set {
                assert!(names.contains(t), "{t} not in catalogue");
            }
        }
    }
}
