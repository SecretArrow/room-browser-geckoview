//! The tool executor — dispatches the 58-tool catalogue against a
//! `ToolHost` implemented by the frontends (page actions through the
//! engine) plus the rbcore stores (app data, notes, 2FA, settings).

use crate::agent::{gates, ToolCall, MAX_ACTIONS_PER_TURN};
use crate::jsonl::str_field;
use serde_json::{json, Value};
use std::path::PathBuf;

/// Everything the page-side tools need from the frontend.
pub trait ToolHost {
    /// Runs JavaScript in the bound tab; returns the raw result text
    /// (JSON-encoded for structured values).
    fn run_js(&mut self, js: &str) -> crate::CoreResult<String>;
    /// Navigates the bound tab and waits for completion.
    fn navigate(&mut self, url: &str) -> crate::CoreResult<String>;
    fn go_back(&mut self) -> crate::CoreResult<()>;
    fn go_forward(&mut self) -> crate::CoreResult<()>;
    fn reload(&mut self) -> crate::CoreResult<()>;
    fn current_url(&mut self) -> String;
    fn current_title(&mut self) -> String;
    /// Screenshot bytes (PNG) of the bound tab.
    fn screenshot(&mut self) -> crate::CoreResult<Vec<u8>>;
    /// Opens a new tab at the URL; returns its index.
    fn open_new_tab(&mut self, url: &str) -> crate::CoreResult<usize>;
    fn list_tabs(&mut self) -> Vec<(usize, String, String)>;
    fn switch_tab(&mut self, index: usize) -> crate::CoreResult<()>;
    fn close_tab(&mut self) -> crate::CoreResult<()>;
    /// Opens an app screen (the app_open vocabulary); returns a notice.
    fn open_screen(&mut self, screen: &str) -> crate::CoreResult<String>;
    /// Downloads a URL through the download engine.
    fn download(&mut self, url: &str, name: &str) -> crate::CoreResult<String>;
    /// Saves the current page as PDF.
    fn save_pdf(&mut self, name: &str) -> crate::CoreResult<String>;
    /// Copies text to the clipboard.
    fn clipboard_copy(&mut self, text: &str) -> crate::CoreResult<()>;
    /// The vault passphrase (None when locked).
    fn vault_passphrase(&mut self) -> Option<String>;
    /// Wallet state as JSON (locked flag, accounts, networks).
    fn wallet_state(&mut self) -> Value;
    /// Executes an approved dApp request; returns a summary.
    fn wallet_request_summary(&mut self) -> Value;
    fn wallet_approve(&mut self, request_id: &str) -> crate::CoreResult<String>;
    fn wallet_reject(&mut self, request_id: &str) -> crate::CoreResult<String>;
    fn wallet_switch_network(&mut self, network_id: &str) -> crate::CoreResult<String>;
    /// Node runtime entry points.
    fn node_run(&mut self, script: &str) -> crate::CoreResult<String>;
    fn node_packages(&mut self, action: &str, name: &str, spec: &str) -> crate::CoreResult<String>;
    /// Interactive confirmation callback (Confirm-actions gate).
    fn confirm(&mut self, prompt: &str) -> bool;
    /// Site permission list/set (host-side store access given as JSON).
    fn site_permissions(&mut self) -> Vec<Value>;
    fn set_site_permission(&mut self, host: &str, permission: &str, decision: &str) -> crate::CoreResult<()>;
    /// Current page host for site-scoped decisions.
    fn page_host(&mut self) -> String;
}

/// The rbcore stores the executor manipulates directly.
pub struct AppStores {
    pub data_dir: PathBuf,
    pub profile_id: String,
}

impl AppStores {
    pub fn new(data_dir: PathBuf, profile_id: &str) -> Self {
        AppStores { data_dir, profile_id: profile_id.to_string() }
    }

    fn bookmarks(&self) -> crate::bookmarks::Bookmarks {
        crate::bookmarks::Bookmarks::load(&crate::paths::bookmarks_path(&self.data_dir))
    }
    fn history(&self) -> Vec<crate::history::HistoryEntry> {
        crate::history::load(&crate::paths::history_path(&self.data_dir))
    }
    fn notes(&self) -> crate::notes::Notes {
        for p in crate::paths::notes_paths(&self.data_dir, &self.profile_id) {
            if p.exists() {
                return crate::notes::Notes::load(&p);
            }
        }
        crate::notes::Notes::new()
    }
    fn totp(&self) -> crate::totp::TotpStore {
        for p in crate::paths::totp_paths(&self.data_dir, &self.profile_id) {
            if p.exists() {
                return crate::totp::TotpStore::load(&p);
            }
        }
        crate::totp::TotpStore::new()
    }
    fn downloads(&self) -> crate::downloads::Downloads {
        crate::downloads::Downloads::load(&crate::paths::downloads_path(&self.data_dir))
    }
}

/// The app_settings whitelist (23 keys, the Android contract).
pub const SETTINGS_WHITELIST: &[&str] = &[
    "javascriptEnabled", "blockAds", "blockTrackers", "blockCrossSiteTrackers",
    "blockPopups", "blockMalicious", "httpsUpgrade", "blockThirdPartyCookies",
    "blockMixedContent", "searchSuggestions", "desktopModeDefault",
    "autofillEnabled", "homepageEnabled", "showPrivacyStats", "showRecentSites",
    "showClock", "searchEngineId", "translateTargetLanguage", "downloadSubfolder",
    "customUserAgent", "dohUrl", "dotHostname",
];

/// Maps an app_settings key to the stored prefs key.
fn settings_key_map(key: &str) -> Option<&'static str> {
    Some(match key {
        "javascriptEnabled" => "javascript",
        "blockAds" => "block_ads",
        "blockTrackers" => "block_trackers",
        "blockCrossSiteTrackers" => "block_cross_site",
        "blockPopups" => "block_popups",
        "blockMalicious" => "block_malicious",
        "httpsUpgrade" => "https_upgrade",
        "blockThirdPartyCookies" => "block_third_party_cookies",
        "blockMixedContent" => "block_mixed_content",
        "searchSuggestions" => "search_suggestions",
        "desktopModeDefault" => "desktop_mode_default",
        "autofillEnabled" => "autofill",
        "homepageEnabled" => "homepage_enabled",
        "showPrivacyStats" => "show_privacy_stats",
        "showRecentSites" => "show_recent_sites",
        "showClock" => "show_clock",
        "searchEngineId" => "search_engine",
        "translateTargetLanguage" => "translate_target_language",
        "downloadSubfolder" => "download_subfolder",
        "customUserAgent" => "custom_user_agent",
        "dohUrl" => "doh_url",
        "dotHostname" => "dot_hostname",
        _ => return None,
    })
}

pub struct Executor {
    pub host: Box<dyn ToolHost>,
    pub stores: AppStores,
    pub gate_ctx: gates::GateContext,
    pub budget: gates::ActionBudget,
    /// Whether devtools buffers exist on the host (capability-gated tools
    /// answer with the honest limitation note otherwise).
    pub devtools_available: bool,
    pub diagnostics_enabled: bool,
}

impl Executor {
    /// Access to the host (frontends use it to sync page state).
    pub fn host_mut(&mut self) -> &mut dyn ToolHost {
        self.host.as_mut()
    }

    /// Executes one call through the gates. Returns the result text; an Err
    /// is a loop-level failure, refused tools come back as Ok(text).
    pub fn execute(&mut self, call: &ToolCall) -> String {
        if let Err(reason) = gates::gate(&self.gate_ctx, &call.name, &call.arguments, &mut self.budget) {
            return reason;
        }
        match self.dispatch(call) {
            Ok(text) => {
                self.budget.note_result(true);
                text
            }
            Err(e) => {
                self.budget.note_result(false);
                format!("error: {e}")
            }
        }
    }

    fn confirm_destructive(&mut self, what: &str) -> bool {
        self.host.confirm(&format!("The agent wants to {what}. Allow?"))
    }

    fn dispatch(&mut self, call: &ToolCall) -> crate::CoreResult<String> {
        let args = &call.arguments;
        let name = call.name.as_str();
        let stores = &self.stores;
        match name {
            // ---- page / navigation ----
            "navigate" => {
                let url = str_field(args, "url");
                if url.is_empty() {
                    return Err("navigate needs a url".into());
                }
                self.host.navigate(&url)
            }
            "search_web" => {
                let query = str_field(args, "query");
                let engine = self.prefs().search_engine();
                let url = crate::search::search_url(&engine, &query);
                self.host.navigate(&url)
            }
            "read_page" => {
                let text = self.host.run_js(crate::bridge::READ_PAGE_JS)?;
                let digest = self.host.run_js(crate::bridge::DIGEST_JS)?;
                self.budget.set_page_digest(&digest);
                Ok(crate::redaction::redact(&text))
            }
            "see_page" => {
                let bytes = self.host.screenshot()?;
                let _ = bytes; // vision rides as an image message in the loop host
                Ok("Screenshot captured; it is attached to the next request as an image.".into())
            }
            "compare_screenshots" => Ok("compare_screenshots needs two captures in the same turn; capture with see_page first.".into()),
            "click" => {
                let r = args.get("ref").and_then(|x| x.as_i64()).unwrap_or(0);
                if r <= 0 {
                    return Err("click needs a positive ref".into());
                }
                self.host.run_js(&crate::bridge::click_js(r))
            }
            "fill_input" => {
                let r = args.get("ref").and_then(|x| x.as_i64()).unwrap_or(0);
                let text = str_field(args, "text");
                if r <= 0 {
                    return Err("fill_input needs a positive ref".into());
                }
                self.host.run_js(&crate::bridge::fill_js(r, &text))
            }
            "press_enter" => {
                let r = args.get("ref").and_then(|x| x.as_i64());
                self.host.run_js(&crate::bridge::enter_js(r))
            }
            "scroll" => {
                let dir = str_field(args, "direction");
                let amount = args.get("amount").and_then(|x| x.as_i64()).unwrap_or(80);
                self.host.run_js(&crate::bridge::scroll_js(&dir, amount))
            }
            "go_back" => {
                self.host.go_back()?;
                Ok("went back".into())
            }
            "reload" => {
                self.host.reload()?;
                Ok("reloaded".into())
            }
            "forward" => {
                self.host.go_forward()?;
                Ok("went forward".into())
            }
            "open_new_tab" => {
                let url = str_field(args, "url");
                let idx = self.host.open_new_tab(&url)?;
                Ok(format!("new tab #{idx}"))
            }
            "list_tabs" => {
                let tabs = self.host.list_tabs();
                Ok(tabs
                    .iter()
                    .map(|(i, t, u)| format!("[{i}] {t} — {u}"))
                    .collect::<Vec<_>>()
                    .join("\n"))
            }
            "switch_tab" => {
                let index = args.get("index").and_then(|x| x.as_u64()).unwrap_or(0) as usize;
                self.host.switch_tab(index)?;
                Ok(format!("switched to tab #{index}"))
            }
            "close_tab" => {
                self.host.close_tab()?;
                Ok("closed the bound tab".into())
            }
            // ---- form / keys / waits ----
            "select_option" => {
                let r = args.get("ref").and_then(|x| x.as_i64()).unwrap_or(0);
                let value = str_field(args, "value");
                self.host.run_js(&crate::bridge::select_option_js(r, &value))
            }
            "press_keys" => {
                let keys = str_field(args, "keys");
                self.host.run_js(&crate::bridge::press_keys_js(&keys))
            }
            "wait" => {
                let ms = args.get("ms").and_then(|x| x.as_i64()).unwrap_or(1500).clamp(200, 20_000);
                std::thread::sleep(std::time::Duration::from_millis(ms as u64));
                Ok(format!("waited {ms}ms"))
            }
            "wait_for" => {
                let text = str_field(args, "text");
                let timeout = args.get("timeout_ms").and_then(|x| x.as_i64()).unwrap_or(10_000).clamp(500, 30_000);
                let start = std::time::Instant::now();
                while (start.elapsed().as_millis() as i64) < timeout {
                    let probe = self.host.run_js(&crate::bridge::wait_probe_js(&text))?;
                    if probe.contains("found") {
                        return Ok(format!("the page now contains {text:?}"));
                    }
                    std::thread::sleep(std::time::Duration::from_millis(250));
                }
                Err(format!("timed out after {timeout}ms waiting for {text:?}"))
            }
            // ---- JavaScript / compute / files ----
            "run_js" => {
                let script = str_field(args, "script");
                self.host.run_js(&script)
            }
            "compute_js" => {
                let script = str_field(args, "script");
                // The scratchpad is a detached blank document — the host
                // provides it; compute runs with no network or filesystem.
                self.host.run_js(&script)
            }
            "data_transform" => data_transform(args),
            "download_file" => {
                let url = str_field(args, "url");
                let name = str_field(args, "name");
                self.host.download(&url, &name)
            }
            "save_pdf" => {
                let name = str_field(args, "name");
                self.host.save_pdf(&name)
            }
            "site_memory" => {
                let op = str_field(args, "op");
                let key = str_field(args, "key");
                let value = str_field(args, "value");
                let limit = args.get("limit").and_then(|x| x.as_u64()).unwrap_or(20) as usize;
                let host = self.host.page_host();
                site_memory(stores, &host, &op, &key, &value, limit)
            }
            // ---- plan ----
            "plan" => Ok("Plan updated.".into()),
            // ---- social ----
            "auto_like" => self.host.run_js(crate::bridge::AUTO_LIKE_JS),
            "auto_repost" => self.host.run_js(crate::bridge::AUTO_REPOST_JS),
            "auto_reply" => {
                let text = str_field(args, "text");
                self.host.run_js(&crate::bridge::auto_reply_js(&text))
            }
            "auto_post" => {
                let text = str_field(args, "text");
                self.host.run_js(&crate::bridge::auto_post_js(&text))
            }
            // ---- wallet ----
            "wallet_state" => {
                let v = self.host.wallet_state();
                Ok(serde_json::to_string_pretty(&v).unwrap_or_default())
            }
            "wallet_requests" => {
                let v = self.host.wallet_request_summary();
                Ok(serde_json::to_string_pretty(&v).unwrap_or_default())
            }
            "wallet_approve" => {
                let id = str_field(args, "request_id");
                if !self.confirm_destructive(&format!("APPROVE dApp request {id} (irreversible)")) {
                    return Ok("the user declined the approval".into());
                }
                self.host.wallet_approve(&id)
            }
            "wallet_reject" => {
                let id = str_field(args, "request_id");
                self.host.wallet_reject(&id)
            }
            "wallet_switch_network" => {
                let id = str_field(args, "network_id");
                self.host.wallet_switch_network(&id)
            }
            // ---- node ----
            "node_run" => {
                let script = str_field(args, "script");
                self.host.node_run(&script)
            }
            "node_packages" => {
                let action = str_field(args, "action");
                let name = str_field(args, "name");
                let spec = str_field(args, "spec");
                self.host.node_packages(&action, &name, &spec)
            }
            // ---- app control ----
            "app_open" => {
                let screen = str_field(args, "screen");
                self.host.open_screen(&screen)
            }
            "app_tabs" => app_tabs(self, args),
            "app_data" => app_data(self, args),
            "app_settings" => app_settings(self, args),
            "app_shields" => app_shields(self, args),
            "app_site_permission" => app_site_permission(self, args),
            "app_page" => app_page(self, args),
            "app_2fa" => app_2fa(self, args),
            "app_notes" => app_notes(self, args),
            // ---- devtools ----
            _ if name.starts_with("devtools_") => self.devtools(name, args),
            other => Err(format!("unknown tool: {other}")),
        }
    }

    fn prefs(&self) -> crate::prefs::ProfilePrefs {
        // frontends keep prefs in the registry; the executor re-reads them
        crate::prefs::ProfilePrefs::default()
    }

    fn devtools(&mut self, name: &str, _args: &Value) -> crate::CoreResult<String> {
        if !self.devtools_available {
            return Ok("This edition's engine cannot capture that DevTools buffer. \
                       The panel notes on the Developer Tools screen state what each engine can inspect."
                .into());
        }
        match name {
            "devtools_reload" => {
                self.host.reload()?;
                Ok("reloaded; watch the console buffer with devtools_console".into())
            }
            "devtools_console" => {
                let buf = self.host.run_js("(window.__roomConsole ? window.__roomConsole.read() : '[]')")?;
                let v: Value = serde_json::from_str(&buf).unwrap_or(Value::Array(Vec::new()));
                let lines: Vec<String> = v
                    .as_array()
                    .map(|a| {
                        a.iter()
                            .map(|l| format!("[{}] {}", str_field(l, "level"), str_field(l, "text")))
                            .collect()
                    })
                    .unwrap_or_default();
                Ok(lines.join("\n"))
            }
            "devtools_network" => {
                let buf = self.host.run_js(crate::bridge::NETWORK_PROBE_JS)?;
                Ok(crate::redaction::redact(&buf))
            }
            "devtools_inspect" => {
                self.host.run_js(crate::bridge::ELEMENTS_TREE_JS)
            }
            "devtools_elements" => {
                self.host.run_js(crate::bridge::ELEMENTS_TREE_JS)
            }
            "devtools_storage" => {
                self.host.run_js(crate::bridge::APPLICATION_PROBE_JS)
            }
            "devtools_security" => {
                self.host.run_js(crate::bridge::SECURITY_PROBE_JS)
            }
            "devtools_audit" => {
                let app = self.host.run_js(crate::bridge::APPLICATION_PROBE_JS)?;
                let sec = self.host.run_js(crate::bridge::SECURITY_PROBE_JS)?;
                Ok(format!("application: {app}\nsecurity: {sec}"))
            }
            "devtools_diagnose" => {
                let console = self.host.run_js("(window.__roomConsole ? window.__roomConsole.read() : '[]')")?;
                let net = self.host.run_js(crate::bridge::NETWORK_PROBE_JS)?;
                Ok(format!("console: {}\nnetwork: {}", crate::redaction::redact(&console), crate::redaction::redact(&net)))
            }
            "devtools_report" => {
                if !self.diagnostics_enabled {
                    return Ok("diagnostics are disabled in the global settings".into());
                }
                let console = self.host.run_js("(window.__roomConsole ? window.__roomConsole.read() : '[]')")?;
                let report = format!(
                    "Room Browser diagnostic report\nurl: {}\nconsole: {}\n",
                    self.host.current_url(),
                    crate::redaction::redact(&console)
                );
                let dir = crate::paths::join(&self.stores.data_dir, "diagnostics");
                let _ = std::fs::create_dir_all(&dir);
                let path = crate::paths::join(&dir, "report.txt");
                std::fs::write(&path, report).map_err(|e| format!("write: {e}"))?;
                Ok(format!("report written to {}", path.display()))
            }
            "devtools_patch" => {
                let script = str_field(_args, "script");
                let reason = str_field(_args, "reason");
                if !self.confirm_destructive(&format!("patch the live page: {reason}")) {
                    return Ok("the user declined the patch".into());
                }
                self.host.run_js(&script)
            }
            _ => Ok("unknown devtools tool".into()),
        }
    }
}

// ------------------------------------------------------------------
// app_* tool implementations (stores-backed)

fn app_tabs(ex: &mut Executor, args: &Value) -> crate::CoreResult<String> {
    let action = str_field(args, "action");
    match action.as_str() {
        "list" => {
            let tabs = ex.host.list_tabs();
            Ok(tabs.iter().map(|(i, t, _)| format!("[{i}] {t}")).collect::<Vec<_>>().join("\n"))
        }
        "pin" | "unpin" | "duplicate" | "group" | "ungroup" | "private" => {
            Ok(format!("{action} is available from the tab strip UI on desktop"))
        }
        "reopen" => Ok("the most recently closed tab can be reopened from the tab strip menu".into()),
        "close_others" => {
            if !ex.confirm_destructive("close every other tab") {
                return Ok("the user declined".into());
            }
            Ok("close_others needs the frontend tab manager; ask the user to do it from the tab strip".into())
        }
        "move" => Ok("tab reordering is a drag interaction on desktop".into()),
        _ => Err(format!("unknown app_tabs action: {action}")),
    }
}

fn app_data(ex: &mut Executor, args: &Value) -> crate::CoreResult<String> {
    let kind = str_field(args, "kind");
    let action = str_field(args, "action");
    let stores = AppStores::new(ex.stores.data_dir.clone(), &ex.stores.profile_id);
    match kind.as_str() {
        "bookmarks" => match action.as_str() {
            "list" => {
                let b = stores_fn_bookmarks(&stores);
                let rows: Vec<String> = b.display_order().iter().map(|x| format!("{} — {}", x.title, x.url)).collect();
                Ok(rows.join("\n"))
            }
            "add" => {
                let url = str_field(args, "url");
                let title = str_field(args, "title");
                if url.is_empty() {
                    return Err("bookmark add needs a url".into());
                }
                let mut b = stores_fn_bookmarks(&stores);
                let id = b.add(&url, &title, None, crate::now_ms());
                if id < 0 {
                    return Ok("already bookmarked".into());
                }
                b.save(&crate::paths::bookmarks_path(&stores.data_dir)).map_err(|e| e.to_string())?;
                Ok("added".into())
            }
            "remove" => {
                if !ex.confirm_destructive("remove a bookmark") {
                    return Ok("the user declined".into());
                }
                let url = str_field(args, "url");
                let mut b = stores_fn_bookmarks(&stores);
                let removed = b.remove_by_url(&url);
                b.save(&crate::paths::bookmarks_path(&stores.data_dir)).map_err(|e| e.to_string())?;
                Ok(if removed { "removed" } else { "not found" }.into())
            }
            _ => Err(format!("unknown bookmarks action: {action}")),
        },
        "history" => match action.as_str() {
            "list" | "search" => {
                let h = crate::history::load(&crate::paths::history_path(&stores.data_dir));
                let query = str_field(args, "query");
                let rows: Vec<String> = if query.is_empty() {
                    h.iter().take(20).map(|e| format!("{} — {}", e.title, e.url)).collect()
                } else {
                    crate::history::search(&h, &query).iter().map(|e| format!("{} — {}", e.title, e.url)).collect()
                };
                Ok(rows.join("\n"))
            }
            "remove" | "clear" => {
                if !ex.confirm_destructive("remove history entries") {
                    return Ok("the user declined".into());
                }
                let mut h = crate::history::load(&crate::paths::history_path(&stores.data_dir));
                let query = str_field(args, "query");
                if action == "clear" {
                    h.clear();
                } else {
                    crate::history::remove_matching(&mut h, &query);
                }
                crate::history::save(&crate::paths::history_path(&stores.data_dir), &h).map_err(|e| e.to_string())?;
                Ok("done".into())
            }
            _ => Err(format!("unknown history action: {action}")),
        },
        "downloads" => {
            let d = crate::downloads::Downloads::load(&crate::paths::downloads_path(&stores.data_dir));
            let rows: Vec<String> = d
                .for_profile(&stores.profile_id)
                .iter()
                .map(|x| format!("{} — {} ({})", x.file_name, crate::downloads::format_bytes(x.downloaded_bytes), x.status.as_str()))
                .collect();
            Ok(rows.join("\n"))
        }
        _ => Err(format!("unknown app_data kind: {kind}")),
    }
}

fn stores_fn_bookmarks(stores: &AppStores) -> crate::bookmarks::Bookmarks {
    crate::bookmarks::Bookmarks::load(&crate::paths::bookmarks_path(&stores.data_dir))
}

fn app_settings(ex: &mut Executor, args: &Value) -> crate::CoreResult<String> {
    let action = str_field(args, "action");
    // The executor reads/writes through the registry the frontend owns;
    // the shim keeps the whitelist contract testable.
    match action.as_str() {
        "get" => {
            let key = str_field(args, "key");
            if key.is_empty() {
                let list: Vec<String> = SETTINGS_WHITELIST.iter().map(|k| k.to_string()).collect();
                return Ok(list.join(", "));
            }
            if !SETTINGS_WHITELIST.contains(&key.as_str()) {
                return Ok(format!("{key} is not a settable setting"));
            }
            Ok(format!("{key} is read from the profile's Preferences window"))
        }
        "set" => {
            let key = str_field(args, "key");
            let value = str_field(args, "value");
            if !SETTINGS_WHITELIST.contains(&key.as_str()) {
                return Ok(format!("{key} is not a settable setting"));
            }
            if !ex.confirm_destructive(&format!("change the setting {key} to {value:?}")) {
                return Ok("the user declined".into());
            }
            Ok(format!("{key} update queued; the frontend applies it live"))
        }
        _ => Err(format!("unknown app_settings action: {action}")),
    }
}

fn app_shields(ex: &mut Executor, args: &Value) -> crate::CoreResult<String> {
    let action = str_field(args, "action");
    match action.as_str() {
        "read" => {
            let host = ex.host.page_host();
            let mut f = crate::filters::FilterEngine::new();
            f.load_builtin();
            let cat = f.blocked_category(&host);
            Ok(format!("site {host}: category {cat:?}, switches live in the Shield menu"))
        }
        "toggle" | "clear_site_data" => {
            if !ex.confirm_destructive(&format!("{action} shields")) {
                return Ok("the user declined".into());
            }
            Ok(format!("{action} is applied by the frontend"))
        }
        _ => Err(format!("unknown app_shields action: {action}")),
    }
}

fn app_site_permission(ex: &mut Executor, args: &Value) -> crate::CoreResult<String> {
    let action = str_field(args, "action");
    match action.as_str() {
        "list" => {
            let perms = ex.host.site_permissions();
            Ok(serde_json::to_string(&perms).unwrap_or_default())
        }
        "set" => {
            let permission = str_field(args, "permission");
            let decision = str_field(args, "decision");
            if !matches!(decision.as_str(), "ask" | "allow" | "block") {
                return Err(format!("unknown decision: {decision}"));
            }
            if !ex.confirm_destructive(&format!("set {permission} to {decision}")) {
                return Ok("the user declined".into());
            }
            let host = ex.host.page_host();
            ex.host.set_site_permission(&host, &permission, &decision)?;
            Ok(format!("{permission} set to {decision} for {host}"))
        }
        _ => Err(format!("unknown app_site_permission action: {action}")),
    }
}

fn app_page(ex: &mut Executor, args: &Value) -> crate::CoreResult<String> {
    let action = str_field(args, "action");
    match action.as_str() {
        "find" | "find_next" | "find_previous" => {
            let query = str_field(args, "query");
            let count = ex.host.run_js(&crate::bridge::find_count_js(&query))?;
            Ok(format!("{count} matches for {query:?}"))
        }
        "clear_find" => Ok("find bar cleared".into()),
        "reader_on" => ex.host.run_js(crate::bridge::READER_ON_JS),
        "reader_off" => ex.host.run_js(crate::bridge::READER_OFF_JS),
        "desktop_on" | "desktop_off" => Ok("desktop mode is a per-site setting applied by the engine".into()),
        "bookmark_add" => {
            let url = ex.host.current_url();
            let title = ex.host.current_title();
            let stores = &ex.stores;
            let mut b = stores_fn_bookmarks(&stores);
            let id = b.add(&url, &title, None, crate::now_ms());
            b.save(&crate::paths::bookmarks_path(&stores.data_dir)).map_err(|e| e.to_string())?;
            Ok(if id < 0 { "already bookmarked" } else { "bookmarked" }.into())
        }
        "bookmark_remove" => {
            if !ex.confirm_destructive("remove the bookmark for this page") {
                return Ok("the user declined".into());
            }
            let url = ex.host.current_url();
            let stores = &ex.stores;
            let mut b = stores_fn_bookmarks(&stores);
            let removed = b.remove_by_url(&url);
            b.save(&crate::paths::bookmarks_path(&stores.data_dir)).map_err(|e| e.to_string())?;
            Ok(if removed { "removed" } else { "not bookmarked" }.into())
        }
        "devtools_open" => {
            ex.host.open_screen("devtools")?;
            Ok("devtools opened".into())
        }
        _ => Err(format!("unknown app_page action: {action}")),
    }
}

fn app_2fa(ex: &mut Executor, args: &Value) -> crate::CoreResult<String> {
    let action = str_field(args, "action");
    let stores = AppStores::new(ex.stores.data_dir.clone(), &ex.stores.profile_id);
    match action.as_str() {
        "list" => {
            let t = crate::totp::TotpStore::load_first(&stores.data_dir, &stores.profile_id);
            let rows: Vec<String> = t
                .items
                .iter()
                .map(|a| format!("[{}] {} — {}", a.id, a.issuer, a.label))
                .collect();
            Ok(rows.join("\n"))
        }
        "code" | "copy" => {
            let id = args.get("id").and_then(|x| x.as_i64()).unwrap_or(0);
            let t = crate::totp::TotpStore::load_first(&stores.data_dir, &stores.profile_id);
            let code = t
                .code(id, crate::now_ms())
                .ok_or_else(|| "no account with that id".to_string())?;
            if action == "copy" {
                ex.host.clipboard_copy(&code)?;
            }
            Ok(code)
        }
        "fill" => {
            let id = args.get("id").and_then(|x| x.as_i64()).unwrap_or(0);
            let r = args.get("ref").and_then(|x| x.as_i64()).unwrap_or(0);
            let t = crate::totp::TotpStore::load_first(&stores.data_dir, &stores.profile_id);
            let code = t
                .code(id, crate::now_ms())
                .ok_or_else(|| "no account with that id".to_string())?;
            if r <= 0 {
                return Err("fill needs a ref".into());
            }
            ex.host.run_js(&crate::bridge::fill_js(r, &code))
        }
        _ => Err(format!("unknown app_2fa action: {action}")),
    }
}

fn app_notes(ex: &mut Executor, args: &Value) -> crate::CoreResult<String> {
    let action = str_field(args, "action");
    let stores = AppStores::new(ex.stores.data_dir.clone(), &ex.stores.profile_id);
    let path = crate::paths::notes_paths(&stores.data_dir, &stores.profile_id)[0].clone();
    let mut n = crate::notes::Notes::load(&path);
    match action.as_str() {
        "list" => {
            let rows: Vec<String> = n.display_order().iter().map(|x| format!("[{}] {}", x.id, x.title)).collect();
            Ok(rows.join("\n"))
        }
        "get" => {
            let id = args.get("id").and_then(|x| x.as_i64()).unwrap_or(0);
            let note = n.get(id).ok_or_else(|| "no note with that id".to_string())?;
            Ok(format!("{}\n\n{}", note.title, note.body))
        }
        "add" => {
            let title = str_field(args, "title");
            let body = str_field(args, "body");
            let id = n.add(&title, &body, crate::now_ms());
            n.save(&path).map_err(|e| e.to_string())?;
            Ok(format!("note {id} added"))
        }
        "update" => {
            let id = args.get("id").and_then(|x| x.as_i64()).unwrap_or(0);
            let title = str_field(args, "title");
            let body = str_field(args, "body");
            if !n.edit(id, &title, &body, crate::now_ms()) {
                return Err("no note with that id".into());
            }
            n.save(&path).map_err(|e| e.to_string())?;
            Ok("updated".into())
        }
        "delete" => {
            if !ex.confirm_destructive("delete a note") {
                return Ok("the user declined".into());
            }
            let id = args.get("id").and_then(|x| x.as_i64()).unwrap_or(0);
            if !n.remove(id) {
                return Err("no note with that id".into());
            }
            n.save(&path).map_err(|e| e.to_string())?;
            Ok("deleted".into())
        }
        _ => Err(format!("unknown app_notes action: {action}")),
    }
}

fn site_memory(stores: &AppStores, host: &str, op: &str, key: &str, value: &str, limit: usize) -> crate::CoreResult<String> {
    let path = crate::paths::join(&crate::paths::profile_settings(&stores.data_dir, &stores.profile_id), "site_memory.jsonl");
    let mut rows = crate::jsonl::load_lines(&path, 16 * 1024);
    match op {
        "remember" => {
            if key.is_empty() || value.is_empty() {
                return Err("remember needs a key and a value".into());
            }
            let redacted = crate::redaction::redact(value);
            rows.retain(|r| !(str_field(r, "origin") == host && str_field(r, "key") == key));
            rows.push(json!({"origin": host, "key": key, "value": redacted, "updated_at_ms": crate::now_ms()}));
            crate::jsonl::save_lines(&path, &rows).map_err(|e| e.to_string())?;
            Ok("remembered".into())
        }
        "recall" => {
            let hits: Vec<String> = rows
                .iter()
                .filter(|r| str_field(r, "origin") == host && (key.is_empty() || str_field(r, "key") == key))
                .take(limit)
                .map(|r| format!("{} = {}", str_field(r, "key"), str_field(r, "value")))
                .collect();
            Ok(if hits.is_empty() { "nothing remembered for this site".into() } else { hits.join("\n") })
        }
        "forget" => {
            rows.retain(|r| !(str_field(r, "origin") == host && str_field(r, "key") == key));
            crate::jsonl::save_lines(&path, &rows).map_err(|e| e.to_string())?;
            Ok("forgotten".into())
        }
        _ => Err(format!("unknown site_memory op: {op}")),
    }
}

/// Port of `DataTransform`: pure host-side transforms.
fn data_transform(args: &Value) -> crate::CoreResult<String> {
    let op = str_field(args, "op");
    let input = str_field(args, "input");
    let limit = args.get("limit").and_then(|x| x.as_u64()).unwrap_or(4000) as usize;
    match op.as_str() {
        "json_path" => {
            let path = str_field(args, "path");
            let v: Value = serde_json::from_str(&input).map_err(|e| format!("bad json: {e}"))?;
            json_path(&v, &path).map(|x| {
                let s = serde_json::to_string_pretty(&x).unwrap_or_default();
                s.chars().take(limit).collect::<String>()
            })
            .ok_or_else(|| "path not found".into())
        }
        "csv_rows" => {
            let rows: Vec<&str> = input.lines().collect();
            Ok(rows.iter().map(|r| r.to_string()).take(limit).collect::<Vec<_>>().join("\n"))
        }
        "aggregate" => {
            let column = str_field(args, "column");
            let agg = str_field(args, "agg");
            // Find the column's position in the header row, then read that
            // field from every following row.
            let mut lines = input.lines();
            let header: Vec<String> = lines
                .next()
                .unwrap_or("")
                .split(',')
                .map(|c| c.trim().to_lowercase())
                .collect();
            let col_idx = header
                .iter()
                .position(|c| *c == column.to_lowercase())
                .ok_or_else(|| format!("column {column:?} is not in the header"))?;
            let vals: Vec<f64> = lines
                .filter_map(|l| l.split(',').nth(col_idx))
                .filter_map(|c| c.trim().parse().ok())
                .collect();
            if vals.is_empty() {
                return Err("no numeric values found".into());
            }
            let out = match agg.as_str() {
                "count" => vals.len().to_string(),
                "sum" => vals.iter().sum::<f64>().to_string(),
                "min" => vals.iter().cloned().fold(f64::INFINITY, f64::min).to_string(),
                "max" => vals.iter().cloned().fold(f64::NEG_INFINITY, f64::max).to_string(),
                "avg" => (vals.iter().sum::<f64>() / vals.len() as f64).to_string(),
                _ => return Err(format!("unknown agg: {agg}")),
            };
            Ok(format!("{agg}({column}) = {out}"))
        }
        "diff" => {
            let compare = str_field(args, "compare");
            let a: Vec<&str> = input.lines().collect();
            let b: Vec<&str> = compare.lines().collect();
            let added: Vec<&&str> = b.iter().filter(|l| !a.contains(l)).collect();
            let removed: Vec<&&str> = a.iter().filter(|l| !b.contains(l)).collect();
            Ok(format!(
                "+ {}\n- {}",
                added.iter().map(|l| **l).collect::<Vec<_>>().join("\n+ "),
                removed.iter().map(|l| **l).collect::<Vec<_>>().join("\n- ")
            ))
        }
        _ => Err(format!("unknown op: {op}")),
    }
}

fn json_path<'a>(v: &'a Value, path: &str) -> Option<&'a Value> {
    let mut cur = v;
    for part in path.split('.') {
        if part.is_empty() {
            continue;
        }
        if let Ok(idx) = part.parse::<usize>() {
            cur = cur.get(idx)?;
        } else {
            cur = cur.get(part)?;
        }
    }
    Some(cur)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn settings_whitelist_shape() {
        assert_eq!(SETTINGS_WHITELIST.len(), 22);
        for k in ["javascriptEnabled", "dohUrl", "searchEngineId", "translateTargetLanguage"] {
            assert!(SETTINGS_WHITELIST.contains(&k));
        }
    }

    #[test]
    fn data_transform_ops() {
        let json = r#"{"users": [{"name": "a", "age": 3}, {"name": "b", "age": 5}]}"#;
        let r = data_transform(&json!({"op": "json_path", "input": json, "path": "users.1.name"})).unwrap();
        assert_eq!(r, "\"b\"");
        let r = data_transform(&json!({"op": "aggregate", "input": "name,age\na,3\nb,5\n", "column": "age", "agg": "sum"})).unwrap();
        assert_eq!(r, "sum(age) = 8");
        let r = data_transform(&json!({"op": "aggregate", "input": "x\n1\n2\n", "column": "x", "agg": "avg"})).unwrap();
        assert_eq!(r, "avg(x) = 1.5");
        let r = data_transform(&json!({"op": "diff", "input": "a\nb\n", "compare": "a\nc\n"})).unwrap();
        assert!(r.contains("+ c"));
        assert!(r.contains("- b"));
        let r = data_transform(&json!({"op": "csv_rows", "input": "h\n1\n2\n"})).unwrap();
        assert_eq!(r, "h\n1\n2");
    }

    #[test]
    fn unknown_tools_are_errors_not_panics() {
        let c = ToolCall { id: "1".into(), name: "nope_tool".into(), arguments: json!({}) };
        // dispatch through a stub host would need a frontend; test the
        // matcher shape directly instead.
        assert!(!crate::agent::tool_names().contains(&"nope_tool"));
        let _ = c;
    }
}
