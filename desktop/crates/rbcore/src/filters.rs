//! Privacy filtering engine — full port of `rb_filters.c`, which is itself a
//! direct port of Android's `FilterEngine` (keyword rules + suffix matching),
//! so both editions reach the same decision for the same request.

use std::collections::HashSet;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Category {
    Ad,
    Tracker,
    CrossSiteTracker,
    Malicious,
    Popup,
    None,
}

pub fn category_name(c: Category) -> &'static str {
    match c {
        Category::Ad => "Ads",
        Category::Tracker => "Trackers",
        Category::CrossSiteTracker => "Cross-site trackers",
        Category::Malicious => "Malicious sites",
        Category::Popup => "Popups",
        Category::None => "None",
    }
}

/// Port of `DEFAULT_KEYWORD_RULES` — first-match-wins on lowercased
/// host+path.
const KEYWORD_RULES: &[(&str, Category)] = &[
    ("/ads", Category::Ad),
    ("/adserver", Category::Ad),
    ("/advert", Category::Ad),
    ("doubleclick.net", Category::Ad),
    ("googlesyndication", Category::Ad),
    ("/analytics.js", Category::Tracker),
    ("/gtag/js", Category::Tracker),
    ("google-analytics.com", Category::Tracker),
    ("connect.facebook.net", Category::Tracker),
    ("scorecardresearch", Category::Tracker),
    ("/telemetry", Category::Tracker),
    ("/beacon.gif", Category::Tracker),
    ("hotjar.com", Category::Tracker),
    ("mixpanel.com", Category::Tracker),
    ("segment.io", Category::Tracker),
    ("amplitude.com", Category::Tracker),
    ("fullstory.com", Category::Tracker),
];

#[derive(Debug, Clone, Copy)]
pub struct Options {
    pub block_ads: bool,
    pub block_trackers: bool,
    pub block_cross_site: bool,
    pub block_malicious: bool,
    pub block_popups: bool,
}

impl Default for Options {
    fn default() -> Self {
        // Security-grade default: malicious stays on.
        Options {
            block_ads: false,
            block_trackers: false,
            block_cross_site: false,
            block_malicious: true,
            block_popups: false,
        }
    }
}

impl Options {
    pub fn from_prefs(p: &crate::prefs::ProfilePrefs) -> Options {
        Options {
            block_ads: p.block_ads(),
            block_trackers: p.block_trackers(),
            block_cross_site: p.block_cross_site(),
            block_malicious: p.block_malicious(),
            block_popups: p.block_popups(),
        }
    }
}

#[derive(Debug, Default)]
pub struct FilterEngine {
    ads: HashSet<String>,
    trackers: HashSet<String>,
    malicious: HashSet<String>,
    stats: [u64; 6],
}

/// Lowercase + trim + drop trailing dots (the normalisation both editions
/// apply to hosts).
fn norm_host(host: &str) -> String {
    let s = host.trim_matches(|c: char| c == '.' || c == ' ' || c == '\t');
    if s.is_empty() {
        return String::new();
    }
    s.to_lowercase()
}

impl FilterEngine {
    pub fn new() -> Self {
        FilterEngine::default()
    }

    /// Port of `matchesSuffix`: the host itself and every parent domain.
    fn set_matches(set: &HashSet<String>, host: &str) -> bool {
        let mut p: &str = host;
        loop {
            if set.contains(p) {
                return true;
            }
            match p.find('.') {
                Some(dot) => p = &p[dot + 1..],
                None => return false,
            }
        }
    }

    /// Parses one `<category>|<host>` line; comments/blank/unknown
    /// categories ignored. Returns whether a host was added.
    fn add_line(&mut self, line: &str) -> bool {
        let line = line.trim();
        if line.is_empty() {
            return false;
        }
        let Some((cat, host)) = line.split_once('|') else { return false };
        if cat.is_empty() || cat.contains('#') {
            return false;
        }
        let host = norm_host(host);
        if host.is_empty() || host.len() >= 512 {
            return false;
        }
        match cat.to_lowercase().as_str() {
            "ad" => self.ads.insert(host),
            "tracker" => self.trackers.insert(host),
            "malicious" => self.malicious.insert(host),
            _ => false,
        }
    }

    /// Loads `<category>|<host>` text (the bundled hosts.txt format).
    pub fn load_text(&mut self, text: &str) -> usize {
        let mut added = 0;
        for line in text.split('\n') {
            if self.add_line(line) {
                added += 1;
            }
        }
        added
    }

    /// Loads the bundled offline blocklist compiled into the binary — the
    /// same `assets/filters/hosts.txt` the Android APK ships.
    pub fn load_builtin(&mut self) -> usize {
        self.load_text(include_str!("../../../assets/filters/hosts.txt"))
    }

    pub fn load_file(&mut self, path: &std::path::Path) -> usize {
        match std::fs::read_to_string(path) {
            Ok(t) => self.load_text(&t),
            Err(_) => 0,
        }
    }

    pub fn host_count(&self) -> usize {
        self.ads.len() + self.trackers.len() + self.malicious.len()
    }

    /// Port of `rb_filters_decide` — the per-request decision.
    pub fn decide(
        &self,
        request_host: &str,
        page_host: &str,
        path: &str,
        opts: &Options,
    ) -> (Category, Option<&'static str>) {
        let req = norm_host(request_host);
        if req.is_empty() {
            return (Category::None, None);
        }
        if opts.block_malicious && Self::set_matches(&self.malicious, &req) {
            return (Category::Malicious, Some("host on malicious-site blocklist"));
        }
        if opts.block_ads && Self::set_matches(&self.ads, &req) {
            return (Category::Ad, Some("host on ad blocklist"));
        }
        if opts.block_trackers && Self::set_matches(&self.trackers, &req) {
            let page = norm_host(page_host);
            let cross_site = !page.is_empty() && page != req;
            if opts.block_cross_site || !cross_site {
                return (
                    if cross_site { Category::CrossSiteTracker } else { Category::Tracker },
                    Some("host on tracker blocklist"),
                );
            }
        }
        if opts.block_ads || opts.block_trackers {
            // Keyword rules run on lowercased host+path (the same string the
            // Android engine builds).
            let p = if path.is_empty() { "/" } else { path };
            let mut lowered = req.clone();
            lowered.push_str(&p.to_lowercase());
            for (pattern, cat) in KEYWORD_RULES {
                if !lowered.contains(pattern) {
                    continue;
                }
                let relevant = match cat {
                    Category::Ad => opts.block_ads,
                    Category::Tracker | Category::CrossSiteTracker => opts.block_trackers,
                    _ => true,
                };
                if relevant {
                    return (*cat, Some("keyword rule"));
                }
            }
        }
        (Category::None, None)
    }

    /// The category a host is on ANY list under (used by the malicious
    /// main-frame check, which ignores the profile switches).
    pub fn blocked_category(&self, host: &str) -> Category {
        let req = norm_host(host);
        if req.is_empty() {
            return Category::None;
        }
        if Self::set_matches(&self.malicious, &req) {
            return Category::Malicious;
        }
        if Self::set_matches(&self.ads, &req) {
            return Category::Ad;
        }
        if Self::set_matches(&self.trackers, &req) {
            return Category::Tracker;
        }
        Category::None
    }

    pub fn count_block(&mut self, cat: Category) {
        let i = match cat {
            Category::Ad => 1,
            Category::Tracker => 2,
            Category::CrossSiteTracker => 3,
            Category::Malicious => 4,
            Category::Popup => 5,
            Category::None => 0,
        };
        self.stats[i] += 1;
    }

    pub fn stat_blocked(&self, cat: Category) -> u64 {
        let i = match cat {
            Category::Ad => 1,
            Category::Tracker => 2,
            Category::CrossSiteTracker => 3,
            Category::Malicious => 4,
            Category::Popup => 5,
            Category::None => 0,
        };
        self.stats[i]
    }

    pub fn stat_total(&self) -> u64 {
        self.stats[1..].iter().sum()
    }

    pub fn reset_stats(&mut self) {
        self.stats = [0; 6];
    }
}

/// Suspicious-site signals — port of `FilterEngine.suspiciousSignals`.
pub const SUSPICIOUS_INSECURE_HTTP: u32 = 1;
pub const SUSPICIOUS_IP_HOST: u32 = 2;
pub const SUSPICIOUS_PUNYCODE: u32 = 4;

fn suspicious_is_ip_host(lowered: &str) -> bool {
    // Prefix test, mirroring Kotlin's ^-anchored Regex: four 1-3-digit
    // octets after the scheme; whatever follows is not this check's
    // business. Octets are NOT range-checked ("999.999.999.999" counts).
    let p = if let Some(x) = lowered.strip_prefix("http://") {
        x
    } else if let Some(x) = lowered.strip_prefix("https://") {
        x
    } else {
        return false;
    };
    let b = p.as_bytes();
    let mut i = 0usize;
    for octet in 0..4 {
        let start = i;
        while i < b.len() && b[i].is_ascii_digit() && i - start < 3 {
            i += 1;
        }
        if i == start {
            return false;
        }
        if octet < 3 {
            if i >= b.len() || b[i] != b'.' {
                return false;
            }
            i += 1;
        }
    }
    true
}

pub fn suspicious_signals(url: &str) -> u32 {
    if url.is_empty() {
        return 0;
    }
    let lowered = url.to_lowercase();
    let mut signals = 0;
    if lowered.starts_with("http://") {
        signals |= SUSPICIOUS_INSECURE_HTTP;
    }
    if suspicious_is_ip_host(&lowered) {
        signals |= SUSPICIOUS_IP_HOST;
    }
    if lowered.contains("xn--") {
        signals |= SUSPICIOUS_PUNYCODE;
    }
    signals
}

pub fn suspicious_text(signals: u32) -> String {
    let mut parts = Vec::new();
    if signals & SUSPICIOUS_INSECURE_HTTP != 0 {
        parts.push("insecure http connection");
    }
    if signals & SUSPICIOUS_IP_HOST != 0 {
        parts.push("IP address used instead of a domain name");
    }
    if signals & SUSPICIOUS_PUNYCODE != 0 {
        parts.push("punycode domain (possible homograph)");
    }
    parts.join(", ")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn engine() -> FilterEngine {
        let mut f = FilterEngine::new();
        assert!(f.load_builtin() > 200); // the bundled list is not empty
        f
    }

    #[test]
    fn suffix_matching_walks_parent_domains() {
        let f = engine();
        let opts = Options {
            block_ads: true,
            block_malicious: false,
            ..Default::default()
        };
        let (cat, _) = f.decide("cdn.DoubleClick.net", "", "/x", &opts);
        assert_eq!(cat, Category::Ad);
    }

    #[test]
    fn malicious_ignores_switches() {
        let f = engine();
        assert_eq!(f.blocked_category("antivirus-scan-2024.com"), Category::Malicious);
        assert_eq!(f.blocked_category("ads-tracker.example"), Category::None);
    }

    #[test]
    fn keyword_rules_first_match_wins() {
        let f = engine();
        let opts = Options {
            block_ads: true,
            block_trackers: true,
            block_malicious: false,
            ..Default::default()
        };
        let (cat, reason) = f.decide("fresh-host.example", "", "/ads/banner.png", &opts);
        assert_eq!(cat, Category::Ad);
        assert_eq!(reason, Some("keyword rule"));
        let (cat2, _) = f.decide("fresh-host.example", "", "/gtag/js?id=G-1", &opts);
        assert_eq!(cat2, Category::Tracker);
        // ads off → the /ads rule is not relevant
        let opts2 = Options {
            block_ads: false,
            block_trackers: true,
            block_malicious: false,
            ..Default::default()
        };
        let (cat3, _) = f.decide("fresh-host.example", "", "/ads/banner.png", &opts2);
        assert_eq!(cat3, Category::None);
    }

    #[test]
    fn cross_site_requires_the_switch_or_same_host() {
        let f = engine();
        let opts = Options {
            block_trackers: true,
            block_malicious: false,
            ..Default::default()
        };
        // same-host tracker (adjust.com is on the bundled tracker list) is
        // blocked without the cross-site switch:
        let (cat, _) = f.decide("adjust.com", "adjust.com", "/", &opts);
        assert_eq!(cat, Category::Tracker);
        // cross-site tracker passes unless block_cross_site:
        let (cat, _) = f.decide("adjust.com", "news.example", "/", &opts);
        assert_eq!(cat, Category::None);
        let opts_on = Options {
            block_trackers: true,
            block_cross_site: true,
            block_malicious: false,
            ..Default::default()
        };
        let (cat, _) = f.decide("adjust.com", "news.example", "/", &opts_on);
        assert_eq!(cat, Category::CrossSiteTracker);
        // doubleclick.net rides the AD list + an AD keyword rule: with only
        // trackers on it stays allowed (same decision the C core makes).
        let (cat, _) = f.decide("doubleclick.net", "news.example", "/", &opts);
        assert_eq!(cat, Category::None);
    }

    #[test]
    fn suspicious_signals_and_text() {
        let s = suspicious_signals("http://93.184.216.34/login");
        assert_eq!(s, SUSPICIOUS_INSECURE_HTTP | SUSPICIOUS_IP_HOST);
        assert_eq!(
            suspicious_text(s),
            "insecure http connection, IP address used instead of a domain name"
        );
        assert!(suspicious_signals("https://xn--80ak6aa92e.com/") & SUSPICIOUS_PUNYCODE != 0);
        assert_eq!(suspicious_signals("https://example.com/"), 0);
    }
}
