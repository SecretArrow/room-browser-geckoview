//! UA presets — port of `rb_ua.c` (13 presets, same strings; a new profile
//! picks randomly from the 8 desktop ones).

pub struct UaPreset {
    pub id: &'static str,
    pub label: &'static str,
    pub ua: &'static str,
    pub mobile: bool,
}

impl std::fmt::Debug for UaPreset {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("UaPreset").field("id", &self.id).finish()
    }
}

pub const PRESETS: &[UaPreset] = &[
    UaPreset { id: "chrome_android", label: "Chrome Android", ua: "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36", mobile: true },
    UaPreset { id: "firefox_android", label: "Firefox Android", ua: "Mozilla/5.0 (Android 14; Mobile; rv:127.0) Gecko/127.0 Gecko/20100101 Firefox/127.0", mobile: true },
    UaPreset { id: "edge_android", label: "Edge Android", ua: "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 EdgA/124.0.0.0", mobile: true },
    UaPreset { id: "samsung_android", label: "Samsung Internet", ua: "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/25.0 Chrome/121.0.0.0 Mobile Safari/537.36", mobile: true },
    UaPreset { id: "webview", label: "Engine default", ua: "", mobile: false },
    UaPreset { id: "chrome_windows", label: "Chrome Windows", ua: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36", mobile: false },
    UaPreset { id: "chrome_macos", label: "Chrome macOS", ua: "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36", mobile: false },
    UaPreset { id: "chrome_linux", label: "Chrome Linux", ua: "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36", mobile: false },
    UaPreset { id: "firefox_windows", label: "Firefox Windows", ua: "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:127.0) Gecko/20100101 Firefox/127.0", mobile: false },
    UaPreset { id: "firefox_macos", label: "Firefox macOS", ua: "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:127.0) Gecko/20100101 Firefox/127.0", mobile: false },
    UaPreset { id: "firefox_linux", label: "Firefox Linux", ua: "Mozilla/5.0 (X11; Linux x86_64; rv:127.0) Gecko/20100101 Firefox/127.0", mobile: false },
    UaPreset { id: "edge_windows", label: "Edge Windows", ua: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0", mobile: false },
    UaPreset { id: "safari_macos", label: "Safari macOS", ua: "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15", mobile: false },
];

pub fn preset(id: &str) -> Option<&'static UaPreset> {
    PRESETS.iter().find(|p| p.id == id)
}

/// The ids a NEW desktop profile randomly picks from (the 8 desktop UAs).
pub const DESKTOP_PICKS: &[&str] = &[
    "chrome_windows",
    "chrome_macos",
    "chrome_linux",
    "firefox_windows",
    "firefox_macos",
    "firefox_linux",
    "edge_windows",
    "safari_macos",
];

pub fn random_desktop_pick() -> &'static str {
    use rand::Rng;
    DESKTOP_PICKS[rand::thread_rng().gen_range(0..DESKTOP_PICKS.len())]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn catalogue_shape() {
        assert_eq!(PRESETS.len(), 13);
        assert!(preset("webview").unwrap().ua.is_empty());
        assert!(preset("nope").is_none());
        assert_eq!(DESKTOP_PICKS.len(), 8);
    }
}
