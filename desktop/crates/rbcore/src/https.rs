//! HTTPS-First pending registry — port of `rb_https.c`.
//!
//! When a link navigation is auto-upgraded http→https, the original URL is
//! registered; if the secure load fails with a *recoverable* error, the
//! browser falls back to the original.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErrKind {
    Unknown,
    Connect,
    Timeout,
    SslHandshake,
    NameNotResolved,
    TooManyRedirects,
    OutOfMemory,
}

impl ErrKind {
    /// Port of `rb_https_is_recoverable`.
    pub fn is_recoverable(self) -> bool {
        matches!(
            self,
            ErrKind::Unknown | ErrKind::Connect | ErrKind::Timeout | ErrKind::SslHandshake
        )
    }
}

#[derive(Debug, Clone, Default)]
pub struct HttpsPending {
    entries: Vec<(String, String)>, // (upgraded, original)
}

fn trim(s: &str) -> Option<String> {
    let t = s.trim_matches(|c: char| c == ' ' || c == '\t' || c == '\r' || c == '\n');
    if t.is_empty() {
        None
    } else {
        Some(t.to_string())
    }
}

impl HttpsPending {
    pub fn new() -> Self {
        HttpsPending::default()
    }

    pub fn register(&mut self, upgraded_url: &str, original_url: &str) {
        let (Some(up), Some(orig)) = (trim(upgraded_url), trim(original_url)) else { return };
        if let Some(e) = self.entries.iter_mut().find(|(u, _)| *u == up) {
            // Re-registering (a reload) replaces the original.
            e.1 = orig;
            return;
        }
        self.entries.push((up, orig));
    }

    pub fn consume(&mut self, url: &str) -> Option<String> {
        let key = trim(url)?;
        let pos = self.entries.iter().position(|(u, _)| *u == key)?;
        Some(self.entries.remove(pos).1)
    }

    /// Port of `rb_https_retry_url`: on non-recoverable errors the entry is
    /// dropped (not returned) so it can't be consumed by an unrelated later
    /// failure of the same URL.
    pub fn retry_url(&mut self, url: &str, error: ErrKind) -> Option<String> {
        if !error.is_recoverable() {
            let _ = self.consume(url);
            return None;
        }
        self.consume(url)
    }

    pub fn clear(&mut self) {
        self.entries.clear();
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }
}

/// Upgrades an http:// URL to https:// (port of `rb_url_upgrade_to_https`'s
/// core: only explicit http:// URLs are upgraded, and only when the caller
/// — the HTTPS-First policy — allows it).
pub fn upgrade_to_https(url: &str) -> Option<String> {
    let lower = url.to_lowercase();
    if lower.starts_with("http://") {
        let mut s = String::with_capacity(url.len() + 1);
        s.push_str("https://");
        s.push_str(&url[7..]);
        Some(s)
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn register_consume_and_reload_replace() {
        let mut p = HttpsPending::new();
        p.register("https://a.com/", "http://a.com/");
        p.register("https://a.com/", "http://a.com/v2");
        assert_eq!(p.len(), 1);
        assert_eq!(p.consume("  https://a.com/ "), Some("http://a.com/v2".into()));
        assert_eq!(p.consume("https://a.com/"), None);
    }

    #[test]
    fn recoverable_vs_not() {
        let mut p = HttpsPending::new();
        p.register("https://x.com/", "http://x.com/");
        assert_eq!(p.retry_url("https://x.com/", ErrKind::SslHandshake), Some("http://x.com/".into()));

        p.register("https://y.com/", "http://y.com/");
        assert_eq!(p.retry_url("https://y.com/", ErrKind::TooManyRedirects), None);
        assert_eq!(p.consume("https://y.com/"), None);
    }

    #[test]
    fn upgrade_only_plain_http() {
        assert_eq!(upgrade_to_https("http://a.com/x"), Some("https://a.com/x".into()));
        assert_eq!(upgrade_to_https("HTTP://a.com/x"), Some("https://a.com/x".into()));
        assert_eq!(upgrade_to_https("https://a.com/"), None);
        assert_eq!(upgrade_to_https("about:blank"), None);
    }
}
