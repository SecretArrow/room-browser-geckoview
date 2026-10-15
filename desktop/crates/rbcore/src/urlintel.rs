//! Omnibox URL heuristics — full port of `rb_url.c` (UrlIntelligence).

/// The schemes that count as "known" for `has_scheme`.
const SCHEMES: &[&str] = &[
    "about", "blob", "brave", "chrome", "data", "file", "ftp", "http",
    "https", "javascript", "mailto", "tel", "view-source", "ws", "wss",
];

fn is_ws(c: char) -> bool {
    matches!(c, ' ' | '\t' | '\n' | '\u{b}' | '\u{c}' | '\r')
}

fn has_ws(s: &str) -> bool {
    s.chars().any(is_ws)
}

fn ci_eq(a: &str, b: &str) -> bool {
    a.eq_ignore_ascii_case(b)
}

fn ci_starts(s: &str, prefix: &str) -> bool {
    s.len() >= prefix.len() && s[..prefix.len()].eq_ignore_ascii_case(prefix)
}

/// Length of a `scheme:` prefix (alpha first char, then alnum/+/-/. then ':'),
/// or 0.
fn scheme_prefix_len(s: &str) -> usize {
    let b = s.as_bytes();
    if b.is_empty() || !b[0].is_ascii_alphabetic() {
        return 0;
    }
    for (i, &c) in b.iter().enumerate().skip(1) {
        if c == b':' {
            return i + 1;
        }
        if !(c.is_ascii_alphanumeric() || c == b'+' || c == b'-' || c == b'.') {
            return 0;
        }
    }
    0
}

fn known_scheme(scheme: &str) -> bool {
    SCHEMES.iter().any(|x| ci_eq(scheme, x))
}

/// `scheme://` or a known bare scheme (`about:`, `mailto:`, ...).
pub fn has_scheme(s: &str) -> bool {
    let n = scheme_prefix_len(s);
    if n == 0 {
        return false;
    }
    if s[n..].starts_with("//") {
        return true;
    }
    known_scheme(&s[..n - 1])
}

/// Length of the authority component (up to / ? #).
fn auth_len(s: &str) -> usize {
    s.find(['/', '?', '#']).unwrap_or(s.len())
}

/// `rb_is_host_like`: dotted host with alphabetic tail, or a numeric-ish
/// address literal with enough separators.
fn is_host_like(s: &str) -> bool {
    let n = s.len();
    if n == 0 {
        return false;
    }
    let bytes = s.as_bytes();
    // Host part: up to ']' for bracketed IPv6, else up to ':'.
    let mut host_end = n;
    if bytes[0] == b'[' {
        if let Some(pos) = s.find(']') {
            host_end = pos;
        }
    } else if let Some(pos) = s.find(':') {
        if pos < host_end {
            host_end = pos;
        }
    }
    let host = &s[..host_end];
    if let Some(last_dot) = host.rfind('.') {
        let tail = &host[last_dot + 1..];
        if last_dot > 0 && !tail.is_empty() && tail.chars().all(|c| c.is_ascii_alphabetic()) {
            return true;
        }
    }
    // Numeric/IPv6-ish literal.
    let mut dots = 0;
    let mut colons = 0;
    let mut only_addr = true;
    for c in s.chars() {
        match c {
            '.' => dots += 1,
            ':' => colons += 1,
            '[' | ']' => {}
            _ if c.is_ascii_hexdigit() => {}
            _ => {
                only_addr = false;
                break;
            }
        }
    }
    only_addr && (dots >= 2 || colons >= 2 || (dots >= 1 && colons >= 1))
}

/// `rb_url_is_probably_url` — the pre-UrlIntelligence helper (ASCII only,
/// no interior whitespace).
pub fn is_probably_url(input: &str) -> bool {
    let t = input.trim_matches(is_ws);
    if t.is_empty() || t.len() >= 4096 {
        return false;
    }
    if !t.is_ascii() {
        return false;
    }
    if t.contains("://") {
        return true;
    }
    if has_scheme(t) {
        return true;
    }
    if ci_starts(t, "localhost") {
        let next = t[9..].chars().next();
        return matches!(next, None | Some(':') | Some('/'));
    }
    is_host_like(&t[..auth_len(t)])
}

/// `rb_url_normalize`: schemeless input gets https://, bare authority gets
/// its trailing slash, and `host?query` / `host#frag` get the slash inserted.
pub fn normalize(input: &str) -> String {
    let t = input.trim_matches(is_ws);
    if t.is_empty() {
        return String::new();
    }
    let mut out = String::new();
    if !has_scheme(t) {
        out.push_str("https://");
    }
    out.push_str(t);
    if let Some(sep) = out.find("://") {
        let after = sep + 3;
        let rest = &out[after..];
        if let Some(pos) = rest.find(['/', '?', '#']) {
            if pos == 0 {
                // already has a path/query — nothing to do
                return out;
            }
            let cut = after + pos;
            let mut fixed = String::with_capacity(out.len() + 1);
            fixed.push_str(&out[..cut]);
            fixed.push('/');
            fixed.push_str(&out[cut..]);
            return fixed;
        }
        out.push('/');
    }
    out
}

/// `^(?:\d{1,3}\.){3}\d{1,3}(:\d+)?$` with octets ≤ 255.
fn looks_like_ipv4(s: &str) -> bool {
    let b = s.as_bytes();
    let mut i = 0;
    for part in 0..4 {
        let start = i;
        let mut value: u32 = 0;
        while i < b.len() && b[i].is_ascii_digit() && i - start < 3 {
            value = value * 10 + (b[i] - b'0') as u32;
            i += 1;
        }
        let digits = i - start;
        if digits == 0 || value > 255 {
            return false;
        }
        if part < 3 {
            if i >= b.len() || b[i] != b'.' {
                return false;
            }
            i += 1;
        }
    }
    if i < b.len() && b[i] == b':' {
        i += 1;
        if i >= b.len() || !b[i].is_ascii_digit() {
            return false;
        }
        while i < b.len() && b[i].is_ascii_digit() {
            i += 1;
        }
    }
    i == b.len()
}

/// `^[0-9a-fA-F:]+:[0-9a-fA-F:.]+$` (callers already found a ':').
fn looks_like_ipv6(s: &str) -> bool {
    let run = s
        .chars()
        .take_while(|c| c.is_ascii_hexdigit() || *c == ':')
        .count();
    for j in (1..=run).rev() {
        if s.as_bytes()[j - 1] != b':' {
            continue;
        }
        let tail = &s[j..];
        if !tail.is_empty() && tail.chars().all(|c| c.is_ascii_hexdigit() || c == ':' || c == '.') {
            return true;
        }
    }
    false
}

/// `^(localhost|127\.0\.0\.1|\[::1\])(:\d+)?(/.*)?$` case-insensitive.
fn looks_like_localhost(s: &str) -> bool {
    for name in ["localhost", "127.0.0.1", "[::1]"] {
        if !ci_eq(&s[..s.len().min(name.len())], name) {
            continue;
        }
        let mut p = &s[name.len()..];
        if p.starts_with(':') {
            p = &p[1..];
            if p.is_empty() || !p.as_bytes()[0].is_ascii_digit() {
                return false;
            }
            p = p.trim_start_matches(|c: char| c.is_ascii_digit());
        }
        if p.starts_with('/') {
            return true;
        }
        return p.is_empty();
    }
    false
}

/// `^[a-zA-Z0-9-]+(\.[a-zA-Z0-9-]+)+(:\d+)?(/.*)?$`
fn looks_like_domain(s: &str) -> bool {
    let b = s.as_bytes();
    let mut i = 0;
    let mut dots = 0;
    loop {
        let start = i;
        while i < b.len() && (b[i].is_ascii_alphanumeric() || b[i] == b'-') {
            i += 1;
        }
        if i == start {
            return false;
        }
        if i < b.len() && b[i] == b'.' {
            i += 1;
            dots += 1;
            continue;
        }
        break;
    }
    if dots == 0 {
        return false;
    }
    if i < b.len() && b[i] == b':' {
        i += 1;
        if i >= b.len() || !b[i].is_ascii_digit() {
            return false;
        }
        while i < b.len() && b[i].is_ascii_digit() {
            i += 1;
        }
    }
    i >= b.len() || b[i] == b'/'
}

/// Port of `rb_url_classify` / Android `UrlIntelligence.classify`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Input {
    /// `Web(url, upgraded_to_https)`
    Web(String, bool),
    /// `Search(query, url)`
    Search(String, String),
    /// blank input stays blank
    Blank,
}

pub fn classify(input: &str, engine_id: &str) -> Input {
    let raw = input.trim_matches(is_ws).to_string();
    if raw.is_empty() {
        return Input::Blank;
    }
    // file:// loads as-is.
    if ci_starts(&raw, "file://") {
        return Input::Web(raw, false);
    }
    // Interior whitespace → search.
    if has_ws(&raw) {
        let url = crate::search::search_url(engine_id, &raw);
        return Input::Search(raw, url);
    }
    // Address literals / localhost → http, never https.
    if looks_like_ipv4(&raw) {
        return Input::Web(format!("http://{raw}"), false);
    }
    if raw.contains(':') && looks_like_ipv6(&raw) {
        return Input::Web(format!("http://[{raw}]"), false);
    }
    if looks_like_localhost(&raw) {
        return Input::Web(format!("http://{raw}"), false);
    }
    if scheme_prefix_len(&raw) > 0 {
        if ci_starts(&raw, "http://") || ci_starts(&raw, "https://") {
            // Explicitly typed scheme is explicit user intent.
            return Input::Web(raw, false);
        }
        // about:/data:/javascript: typed into the omnibox are searched, not run.
        let url = crate::search::search_url(engine_id, &raw);
        return Input::Search(raw, url);
    }
    if looks_like_domain(&raw) {
        let https = format!("https://{raw}");
        let upgraded = crate::https::upgrade_to_https(&https).unwrap_or(https.clone());
        let flag = is_https(&upgraded);
        return Input::Web(upgraded, flag);
    }
    let url = crate::search::search_url(engine_id, &raw);
    Input::Search(raw, url)
}

/// The single omnibox decider: returns the URL to load.
pub fn decide(input: &str, engine_id: &str) -> String {
    match classify(input, engine_id) {
        Input::Web(url, _) | Input::Search(_, url) => url,
        Input::Blank => String::new(),
    }
}

pub fn is_http(url: &str) -> bool {
    url.len() >= 7 && url[..7].eq_ignore_ascii_case("http://")
}

pub fn is_https(url: &str) -> bool {
    url.len() >= 8 && url[..8].eq_ignore_ascii_case("https://")
}

/// Host of a `scheme://...` URL (userinfo dropped, brackets kept for IPv6,
/// port dropped). None when there is no authority.
pub fn host_of(url: &str) -> Option<String> {
    let sep = url.find("://")?;
    let auth = &url[sep + 3..];
    let end = auth.find(['/', '?', '#']).unwrap_or(auth.len());
    let mut host = &auth[..end];
    if host.is_empty() {
        return None;
    }
    if let Some(at) = host.rfind('@') {
        host = &host[at + 1..];
    }
    if host.is_empty() {
        return None;
    }
    // Strip port unless IPv6-bracketed.
    if !host.starts_with('[') {
        if let Some(colon) = host.find(':') {
            host = &host[..colon];
        }
    } else if let Some(close) = host.find(']') {
        host = &host[..=close];
    }
    Some(host.to_string())
}

/// Display form for the omnibox: hide scheme (keep it when non-http(s)),
/// hide userinfo, keep host+path+query.
pub fn display_url(url: &str) -> String {
    if is_https(url) {
        return url[8..].to_string();
    }
    if is_http(url) {
        return url[7..].to_string();
    }
    url.to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn web(input: &str) -> String {
        decide(input, "duckduckgo")
    }

    #[test]
    fn searches_and_urls() {
        assert_eq!(web("how to boil water"), "https://duckduckgo.com/?q=how%20to%20boil%20water");
        // classify keeps the bare-authority URL exactly as the C core's
        // rb_url_classify does (rb_url_normalize's slash-insertion is a
        // separate helper the engines don't need).
        assert_eq!(web("example.com"), "https://example.com");
        assert_eq!(web("example.com/path?q=1"), "https://example.com/path?q=1");
        // The domain regex only admits path slashes after the host — a bare
        // '?' or '#' makes it search text, exactly like the C/Kotlin core.
        assert_eq!(web("example.com?q=1"), "https://duckduckgo.com/?q=example.com%3Fq%3D1");
        assert_eq!(web("example.com#frag"), "https://duckduckgo.com/?q=example.com%23frag");
        assert_eq!(web("http://plain.example"), "http://plain.example");
        assert_eq!(web("https://sec.example/x"), "https://sec.example/x");
        assert_eq!(web("192.168.1.10"), "http://192.168.1.10");
        // With a path appended, the anchored IPv4 regex no longer matches and
        // the dotted name lands in the domain branch → https, exactly like C.
        assert_eq!(web("192.168.1.10:8080/x"), "https://192.168.1.10:8080/x");
        assert_eq!(web("::1"), "http://[::1]");
        assert_eq!(web("localhost"), "http://localhost");
        assert_eq!(web("localhost:9000/x"), "http://localhost:9000/x");
        assert_eq!(web("file:///home/x.html"), "file:///home/x.html");
        // about:/javascript: typed are searched, not executed.
        assert_eq!(web("about:blank"), "https://duckduckgo.com/?q=about%3Ablank");
        // parens are percent-encoded like every other reserved byte
        assert_eq!(web("javascript:alert(1)"), "https://duckduckgo.com/?q=javascript%3Aalert%281%29");
        // single word = search text
        assert_eq!(web("rumah"), "https://duckduckgo.com/?q=rumah");
        // "1.5" is a two-label domain and "12:30" matches the loose IPv6
        // heuristic — both classify as Web, exactly like the C/Kotlin core
        // (the "stays search" comments in the C file are about
        // rb_url_is_probably_url, a different helper).
        assert!(matches!(classify("1.5", "duckduckgo"), Input::Web(_, _)));
        assert!(matches!(classify("12:30", "duckduckgo"), Input::Web(_, _)));
        // 999.999.999.999 passes the loose ip check like the Kotlin one
        assert!(matches!(classify("999.999.999.999", "duckduckgo"), Input::Web(_, _)));
    }

    #[test]
    fn is_probably_url_contract() {
        assert!(is_probably_url("example.com"));
        assert!(is_probably_url("https://a.com"));
        assert!(!is_probably_url("how to x"));
        assert!(!is_probably_url("satu dua"));
    }

    #[test]
    fn host_extraction() {
        assert_eq!(host_of("https://user:pass@Example.com:8443/x?q"), Some("Example.com".into()));
        assert_eq!(host_of("http://[::1]:99/x"), Some("[::1]".into()));
        assert_eq!(host_of("about:blank"), None);
    }
}
