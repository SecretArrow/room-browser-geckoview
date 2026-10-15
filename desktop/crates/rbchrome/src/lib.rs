//! The chrome layer — a single-page HTML application extracted to the data
//! directory at startup (the same pattern the Android edition uses for
//! bridge.js) and loaded into the chrome webview by both frontends.
//!
//! RPC contract: the page calls `RoomHost.call(method, params)` which
//! posts a message through the engine's native channel
//! (`window.chrome.webview.postMessage` on WebView2, the registered script
//! message handler on WebKitGTK); the frontend replies by evaluating
//! `window.__roomReply(id, ok, json)`.

use std::path::{Path, PathBuf};

/// The chrome assets, one file each (kept flat so the extraction and the
/// file:// URLs stay trivial).
pub const CHROME_HTML: &str = include_str!("../assets/chrome.html");

/// Where the chrome is extracted: `<data>/chrome/<version>/chrome.html`.
pub fn chrome_dir(data: &Path, version: &str) -> PathBuf {
    rbcore::paths::join(data, &format!("chrome/{version}"))
}

/// Extracts the chrome assets (rewritten when the version changes, exactly
/// like Android's bridge.js copy rule).
pub fn ensure_extracted(data: &Path, version: &str) -> std::io::Result<PathBuf> {
    let dir = chrome_dir(data, version);
    let html = dir.join("chrome.html");
    let needs_write = match std::fs::read(&html) {
        Ok(existing) => existing != CHROME_HTML.as_bytes(),
        Err(_) => true,
    };
    if needs_write {
        std::fs::create_dir_all(&dir)?;
        std::fs::write(&html, CHROME_HTML)?;
    }
    Ok(html)
}

/// The file:// URL the chrome webview loads.
pub fn chrome_url(data: &Path, version: &str) -> String {
    let html = ensure_extracted(data, version).unwrap_or_else(|_| chrome_dir(data, version).join("chrome.html"));
    let s = html.to_string_lossy().replace('\\', "/");
    format!("file://{s}")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn chrome_html_is_a_complete_document() {
        assert!(CHROME_HTML.contains("<!DOCTYPE html>"));
        assert!(CHROME_HTML.contains("RoomHost"));
        assert!(CHROME_HTML.contains("__roomReply"));
        // every major surface is present
        for surface in [
            "tab-strip", "omnibox", "bookmarks-bar", "agent-panel", "prefs",
            "downloads-view", "notes-view", "totp-view", "vault-view",
            "wallet-view", "localai-view", "theme-studio", "profiles-view",
            "devtools-panel", "find-bar",
        ] {
            assert!(CHROME_HTML.contains(surface), "missing surface {surface}");
        }
    }

    #[test]
    fn extraction_is_version_stamped_and_idempotent() {
        let d = std::env::temp_dir().join(format!("rb-chrome-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        let url = chrome_url(&d, "1.2.3-test");
        assert!(url.starts_with("file://"));
        assert!(url.contains("chrome/1.2.3-test/chrome.html"));
        let p1 = chrome_dir(&d, "1.2.3-test").join("chrome.html");
        let first = std::fs::read(&p1).unwrap();
        // second call does not rewrite
        let _ = chrome_url(&d, "1.2.3-test");
        let second = std::fs::read(&p1).unwrap();
        assert_eq!(first, second);
        assert_eq!(first.as_slice(), CHROME_HTML.as_bytes());
        let _ = std::fs::remove_dir_all(&d);
    }
}
