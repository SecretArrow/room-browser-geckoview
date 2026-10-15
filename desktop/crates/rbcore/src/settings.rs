//! Global settings store — port of `rb_settings.c`.
//!
//! `key=value` lines; split at the FIRST `=` (values may contain `=`); the
//! value side escapes `\\` -> `\\\\`, LF -> `\\n`, CR -> `\\r` and leaves
//! unrecognised escapes alone on read (so a pre-escaping Windows path such
//! as `C:\new` keeps every byte it had).

use crate::jsonl::atomic_write;
use std::collections::BTreeMap;
use std::path::Path;

#[derive(Debug, Clone, Default)]
pub struct Settings {
    map: BTreeMap<String, String>,
}

/// The global defaults (`rb_prefs_global_defaults`), same order and values.
pub fn global_defaults() -> BTreeMap<String, String> {
    let mut m = BTreeMap::new();
    for (k, v) in [
        ("dns_mode", "system"),
        ("doh_url", ""),
        ("dot_hostname", ""),
        ("network_protection_enabled", "1"),
        ("show_previous_profile_name", "1"),
        ("show_last_seen_time", "1"),
        ("offer_network_change_options", "1"),
        ("offer_airplane_mode_shortcut", "1"),
        ("network_retention_days", "30"),
        ("warning_behavior", "ask_every_time"),
        ("conflict_severity", "informational"),
        ("telemetry_enabled", "0"),
        ("diagnostics_enabled", "0"),
    ] {
        m.insert(k.to_string(), v.to_string());
    }
    m
}

impl Settings {
    pub fn new() -> Self {
        Settings {
            map: global_defaults(),
        }
    }

    pub fn load(path: &Path) -> Self {
        let mut s = Settings::new();
        if let Ok(text) = std::fs::read_to_string(path) {
            for line in text.split('\n') {
                let line = line.strip_suffix('\r').unwrap_or(line);
                if line.is_empty() {
                    continue;
                }
                let Some(eq) = line.find('=') else { continue };
                let key = line[..eq].trim_matches(|c| c == ' ' || c == '\t');
                if key.is_empty() {
                    continue;
                }
                s.map
                    .insert(key.to_string(), unescape(&line[eq + 1..]));
            }
        }
        s
    }

    pub fn save(&self, path: &Path) -> std::io::Result<()> {
        let mut buf = String::new();
        for (k, v) in &self.map {
            buf.push_str(k);
            buf.push('=');
            buf.push_str(&escape(v));
            buf.push('\n');
        }
        atomic_write(path, buf.as_bytes())
    }

    pub fn get(&self, key: &str) -> Option<&str> {
        self.map.get(key).map(|s| s.as_str())
    }

    pub fn get_or<'a>(&'a self, key: &str, default: &'a str) -> &'a str {
        self.map.get(key).map(|s| s.as_str()).unwrap_or(default)
    }

    pub fn get_bool(&self, key: &str, default: bool) -> bool {
        match self.map.get(key).map(|s| s.as_str()) {
            Some("1") | Some("true") => true,
            Some("0") | Some("false") => false,
            _ => default,
        }
    }

    pub fn get_i64(&self, key: &str, default: i64) -> i64 {
        self.map
            .get(key)
            .and_then(|s| s.parse().ok())
            .unwrap_or(default)
    }

    pub fn set(&mut self, key: &str, value: &str) {
        self.map.insert(key.to_string(), value.to_string());
    }

    pub fn set_bool(&mut self, key: &str, value: bool) {
        self.set(key, if value { "1" } else { "0" });
    }

    pub fn set_i64(&mut self, key: &str, value: i64) {
        self.set(key, &value.to_string());
    }
}

fn escape(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for c in value.chars() {
        match c {
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            _ => out.push(c),
        }
    }
    out
}

fn unescape(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    let mut it = value.chars();
    while let Some(c) = it.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        match it.next() {
            Some('\\') => out.push('\\'),
            Some('n') => out.push('\n'),
            Some('r') => out.push('\r'),
            // Unrecognised escape: keep the backslash AND the character.
            Some(other) => {
                out.push('\\');
                out.push(other);
            }
            None => out.push('\\'),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn escape_roundtrip() {
        for v in ["plain", "a\nb", "c\\d", "c:\\new", "x\r\ny", "a=b", ""] {
            assert_eq!(unescape(&escape(v)), v, "roundtrip failed for {v:?}");
        }
        // Unrecognised escape left alone.
        assert_eq!(unescape("a\\tb"), "a\\tb");
    }

    #[test]
    fn split_at_first_equals() {
        let mut p = std::env::temp_dir();
        p.push(format!("rb-settings-{}.txt", std::process::id()));
        let mut s = Settings::new();
        s.set("url", "https://x/?a=1&b=2");
        s.set("multi", "l1\nl2");
        s.save(&p).unwrap();
        let loaded = Settings::load(&p);
        assert_eq!(loaded.get("url"), Some("https://x/?a=1&b=2"));
        assert_eq!(loaded.get("multi"), Some("l1\nl2"));
        let _ = std::fs::remove_file(&p);
    }

    #[test]
    fn defaults_match_contract() {
        let d = global_defaults();
        assert_eq!(d["dns_mode"], "system");
        assert_eq!(d["network_retention_days"], "30");
        assert_eq!(d["telemetry_enabled"], "0");
        assert_eq!(d["diagnostics_enabled"], "0");
    }
}
