//! Per-profile settings — port of `rb_prefs.c`.
//!
//! Storage lives inside `profiles.jsonl` as a nested object where every
//! value is a JSON **string** (even numeric settings — `"font_scale":"100"`),
//! round-tripping unknown keys for forward compatibility.

use std::collections::BTreeMap;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DnsMode {
    System,
    Auto,
    Doh,
    Dot,
}

impl DnsMode {
    pub fn as_str(self) -> &'static str {
        match self {
            DnsMode::System => "system",
            DnsMode::Auto => "auto",
            DnsMode::Doh => "doh",
            DnsMode::Dot => "dot",
        }
    }
    pub fn parse(s: &str) -> DnsMode {
        match s {
            "auto" => DnsMode::Auto,
            "doh" => DnsMode::Doh,
            "dot" => DnsMode::Dot,
            _ => DnsMode::System,
        }
    }
}

/// Defaults per `rb_prefs.c`. (Compatibility-first: ad/tracker blocking
/// ships OFF; malicious + https-upgrade ON; JS never off by default.)
pub fn profile_defaults() -> BTreeMap<String, String> {
    let mut m = BTreeMap::new();
    for (k, v) in [
        ("theme", "system"),
        ("accent_argb", "4285020836"),
        ("font_scale", "100"),
        ("reduced_motion", "0"),
        ("high_contrast", "0"),
        ("tab_layout", "grid"),
        ("search_engine", "duckduckgo"),
        ("homepage_enabled", "1"),
        ("homepage_shortcuts", "https://youtube.com\nhttps://github.com\nhttps://google.com\nhttps://reddit.com"),
        ("show_privacy_stats", "1"),
        ("show_recent_sites", "1"),
        ("show_clock", "1"),
        ("home", "https://duckduckgo.com"),
        ("bookmarks_bar", "1"),
        ("ua_mode", "default"),
        ("ua_preset_id", ""),
        ("custom_user_agent", ""),
        ("device_id", ""),
        ("screen_size", "real"),
        ("screen_width", "0"),
        ("screen_height", "0"),
        ("dns_mode", "system"),
        ("doh_url", ""),
        ("dot_hostname", ""),
        ("block_ads", "0"),
        ("block_trackers", "0"),
        ("block_cross_site", "0"),
        ("block_popups", "0"),
        ("block_malicious", "1"),
        ("https_upgrade", "1"),
        ("block_third_party_cookies", "0"),
        ("block_mixed_content", "0"),
        ("javascript", "1"),
        ("webrtc_policy", "restrict_local_ip"),
        ("search_suggestions", "0"),
        ("desktop_mode_default", "0"),
        ("language_tag", ""),
        ("translate_target_language", "id"),
        ("never_translate_sites", ""),
        ("network_protection_use_global", "1"),
        ("network_protection_enabled", "1"),
        ("download_subfolder", "RoomBrowser"),
        ("autofill", "1"),
        // Desktop session restore (NOT the core tabs JSONL):
        ("open_tabs", ""),
        ("active_tab", "0"),
    ] {
        m.insert(k.to_string(), v.to_string());
    }
    m
}

#[derive(Debug, Clone)]
pub struct ProfilePrefs {
    pub map: BTreeMap<String, String>,
}

impl Default for ProfilePrefs {
    fn default() -> Self {
        ProfilePrefs {
            map: profile_defaults(),
        }
    }
}

impl ProfilePrefs {
    pub fn from_map(map: BTreeMap<String, String>) -> Self {
        // Defaults first, stored values over them; unknown keys survive.
        let mut merged = profile_defaults();
        for (k, v) in map {
            merged.insert(k, v);
        }
        ProfilePrefs { map: merged }
    }

    pub fn get(&self, k: &str) -> String {
        self.map.get(k).cloned().unwrap_or_default()
    }
    pub fn set(&mut self, k: &str, v: &str) {
        self.map.insert(k.to_string(), v.to_string());
    }
    pub fn bool_of(&self, k: &str, default: bool) -> bool {
        match self.map.get(k).map(|s| s.as_str()) {
            Some("1") | Some("true") => true,
            Some("0") | Some("false") => false,
            _ => default,
        }
    }
    pub fn set_bool(&mut self, k: &str, v: bool) {
        self.set(k, if v { "1" } else { "0" });
    }
    pub fn int_of(&self, k: &str, default: i64) -> i64 {
        self.map.get(k).and_then(|s| s.parse().ok()).unwrap_or(default)
    }

    // ---- typed views used across the app -------------------------------
    pub fn theme_mode(&self) -> String { self.get("theme") }
    pub fn font_scale(&self) -> i64 { self.int_of("font_scale", 100).clamp(50, 250) }
    pub fn search_engine(&self) -> String { self.get("search_engine") }
    pub fn homepage(&self) -> String { self.get("home") }
    pub fn homepage_enabled(&self) -> bool { self.bool_of("homepage_enabled", true) }
    pub fn bookmarks_bar(&self) -> bool { self.bool_of("bookmarks_bar", true) }
    pub fn javascript(&self) -> bool { self.bool_of("javascript", true) }
    pub fn https_upgrade(&self) -> bool { self.bool_of("https_upgrade", true) }
    pub fn block_ads(&self) -> bool { self.bool_of("block_ads", false) }
    pub fn block_trackers(&self) -> bool { self.bool_of("block_trackers", false) }
    pub fn block_cross_site(&self) -> bool { self.bool_of("block_cross_site", false) }
    pub fn block_popups(&self) -> bool { self.bool_of("block_popups", false) }
    pub fn block_malicious(&self) -> bool { self.bool_of("block_malicious", true) }
    pub fn block_third_party_cookies(&self) -> bool { self.bool_of("block_third_party_cookies", false) }
    pub fn dns_mode(&self) -> DnsMode { DnsMode::parse(&self.get("dns_mode")) }
    pub fn doh_url(&self) -> String { self.get("doh_url") }
    pub fn dot_hostname(&self) -> String { self.get("dot_hostname") }
    pub fn translate_target_language(&self) -> String { self.get("translate_target_language") }
    pub fn download_subfolder(&self) -> String { self.get("download_subfolder") }
    pub fn autofill(&self) -> bool { self.bool_of("autofill", true) }
    pub fn device_id(&self) -> String { self.get("device_id") }
    pub fn ua_mode(&self) -> String { self.get("ua_mode") }
    pub fn ua_preset_id(&self) -> String { self.get("ua_preset_id") }
    pub fn custom_user_agent(&self) -> String { self.get("custom_user_agent") }
    pub fn screen_size_manual(&self) -> bool { self.get("screen_size") == "manual" }
    pub fn screen_width(&self) -> i64 { self.int_of("screen_width", 0).clamp(0, 4320) }
    pub fn screen_height(&self) -> i64 { self.int_of("screen_height", 0).clamp(0, 4320) }
    pub fn search_suggestions(&self) -> bool { self.bool_of("search_suggestions", false) }
    pub fn reduced_motion(&self) -> bool { self.bool_of("reduced_motion", false) }
    pub fn high_contrast(&self) -> bool { self.bool_of("high_contrast", false) }
    pub fn accent_argb(&self) -> u32 {
        self.get("accent_argb").parse().unwrap_or(4285020836)
    }
    pub fn webrtc_policy(&self) -> String { self.get("webrtc_policy") }

    // ---- session restore ----------------------------------------------
    /// `open_tabs` is `\n`-separated URLs; `active_tab` indexes into it.
    pub fn open_tabs(&self) -> Vec<String> {
        self.get("open_tabs")
            .split('\n')
            .filter(|s| !s.trim().is_empty())
            .map(|s| s.trim().to_string())
            .collect()
    }
    pub fn active_tab(&self) -> usize {
        self.int_of("active_tab", 0).max(0) as usize
    }
    pub fn set_session(&mut self, urls: &[String], active: usize) {
        self.set("open_tabs", &urls.join("\n"));
        self.set("active_tab", &active.to_string());
    }

    /// Effective UA string for the engine: device > preset > custom > "".
    /// Returns `None` when the engine default should apply.
    pub fn effective_user_agent(&self) -> Option<String> {
        let custom = self.custom_user_agent();
        match self.ua_mode().as_str() {
            "preset" => {
                let id = self.ua_preset_id();
                if id.is_empty() {
                    None
                } else {
                    crate::ua::preset(&id).map(|p| p.ua.to_string())
                }
            }
            "custom" if !custom.is_empty() => Some(custom),
            _ => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_are_the_contract() {
        let p = ProfilePrefs::default();
        assert!(p.javascript());
        assert!(p.block_malicious());
        assert!(p.https_upgrade());
        assert!(!p.block_ads());
        assert_eq!(p.search_engine(), "duckduckgo");
        assert_eq!(p.download_subfolder(), "RoomBrowser");
        assert_eq!(p.translate_target_language(), "id");
        assert_eq!(p.dns_mode(), DnsMode::System);
        assert_eq!(p.webrtc_policy(), "restrict_local_ip");
    }

    #[test]
    fn unknown_keys_and_string_values_roundtrip() {
        let mut m = BTreeMap::new();
        m.insert("font_scale".into(), "130".into());
        m.insert("future_key".into(), "kept".into());
        let p = ProfilePrefs::from_map(m);
        assert_eq!(p.font_scale(), 130);
        assert_eq!(p.get("future_key"), "kept");
    }

    #[test]
    fn session_encode_decode() {
        let mut p = ProfilePrefs::default();
        p.set_session(
            &["https://a.com/".into(), "https://b.org/x".into()],
            1,
        );
        assert_eq!(p.open_tabs().len(), 2);
        assert_eq!(p.active_tab(), 1);
    }
}
