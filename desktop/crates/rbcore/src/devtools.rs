//! Developer Tools capabilities — the per-edition honesty matrix (what each
//! engine can and cannot inspect, and why), ported from
//! `DeveloperToolsCapabilities`.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Capability {
    pub id: &'static str,
    /// WebView2 (Chromium) answer.
    pub webview: &'static str,
    /// WebKitGTK answer.
    pub webkit: &'static str,
}

pub const CAPABILITIES: &[Capability] = &[
    Capability {
        id: "CONSOLE_CAPTURE",
        webview: "yes — CDP Runtime domain + the injected capture script",
        webkit: "yes — the injected capture script on every frame",
    },
    Capability {
        id: "ENGINE_CONSOLE",
        webview: "yes — the WebView2 DevTools protocol can be called in-process",
        webkit: "limited — WebKitGTK's inspector is interactive-only; the panel reads the injected capture instead",
    },
    Capability {
        id: "NETWORK_REQUEST_LINE",
        webview: "yes — CDP Network domain sees every request line",
        webkit: "partial — performance resource entries carry name/type/size, not headers",
    },
    Capability {
        id: "NETWORK_RESPONSE_HEADERS",
        webview: "yes — CDP Network domain (values redacted on capture)",
        webkit: "no — WebKitGTK exposes no response headers to the embedding app",
    },
    Capability {
        id: "PAGE_SCRIPTING",
        webview: "yes — ExecuteScript with results",
        webkit: "yes — run_javascript with results",
    },
    Capability {
        id: "SECURITY_INFO",
        webview: "yes — CDP Security domain + NavigationCompleted IsHttps",
        webkit: "partial — scheme and mixed-content probe from the page",
    },
    Capability {
        id: "SECURITY_CERTIFICATE",
        webview: "yes — CDP Security domain certificate details",
        webkit: "no — certificate details are not exposed by WebKitGTK to embedders",
    },
];

/// The eight panels, in DeveloperToolsPanels order.
pub const PANELS: &[&str] = &[
    "OVERVIEW", "ELEMENTS", "CONSOLE", "NETWORK", "APPLICATION", "SECURITY", "AUDIT", "RECORDER",
];

pub fn capability(id: &str) -> Option<&'static Capability> {
    CAPABILITIES.iter().find(|c| c.id == id)
}

/// The per-edition text block the Overview panel renders.
pub fn edition_notes(engine: &str) -> Vec<String> {
    let is_webkit = engine.eq_ignore_ascii_case("webkit");
    CAPABILITIES
        .iter()
        .map(|c| {
            let answer = if is_webkit { c.webkit } else { c.webview };
            format!("{}: {}", c.id, answer)
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn capability_matrix_is_complete() {
        assert_eq!(CAPABILITIES.len(), 7);
        assert_eq!(PANELS.len(), 8);
        for id in ["CONSOLE_CAPTURE", "NETWORK_RESPONSE_HEADERS", "SECURITY_CERTIFICATE", "PAGE_SCRIPTING"] {
            assert!(capability(id).is_some());
        }
        // the two editions must differ somewhere (the whole point of the matrix)
        let differing = CAPABILITIES.iter().filter(|c| c.webview != c.webkit).count();
        assert!(differing >= 3);
        let webkit_notes = edition_notes("webkit");
        assert!(webkit_notes.iter().any(|n| n.starts_with("NETWORK_RESPONSE_HEADERS: no")));
        let webview_notes = edition_notes("webview2");
        assert!(webview_notes.iter().any(|n| n.starts_with("NETWORK_RESPONSE_HEADERS: yes")));
    }
}
