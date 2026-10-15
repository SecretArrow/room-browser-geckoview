//! Search-engine registry — port of `rb_search.c`.

pub struct Engine {
    pub id: &'static str,
    pub label: &'static str,
    pub template: &'static str,
    /// Optional suggestion endpoint (DDG's list API, Google's complete API).
    pub suggest: Option<&'static str>,
}

pub const ENGINES: &[Engine] = &[
    Engine { id: "google", label: "Google", template: "https://www.google.com/search?q={query}", suggest: Some("https://www.google.com/complete/search?client=chrome&q={query}") },
    Engine { id: "bing", label: "Bing", template: "https://www.bing.com/search?q={query}", suggest: None },
    Engine { id: "duckduckgo", label: "DuckDuckGo", template: "https://duckduckgo.com/?q={query}", suggest: Some("https://duckduckgo.com/ac/?q={query}&type=list") },
    Engine { id: "brave", label: "Brave Search", template: "https://search.brave.com/search?q={query}", suggest: None },
    Engine { id: "startpage", label: "Startpage", template: "https://www.startpage.com/sp/search?query={query}", suggest: None },
    Engine { id: "ecosia", label: "Ecosia", template: "https://www.ecosia.org/search?q={query}", suggest: None },
];

pub fn engine(id: &str) -> Option<&'static Engine> {
    ENGINES.iter().find(|e| e.id == id)
}

pub fn default_engine() -> &'static Engine {
    engine("duckduckgo").unwrap()
}

/// RFC 3986 percent-encoding of the query (everything outside unreserved
/// gets encoded, matching the C encoder's behaviour for non-ASCII).
pub fn encode_query(query: &str) -> String {
    let mut out = String::with_capacity(query.len());
    for b in query.as_bytes() {
        let c = *b as char;
        if c.is_ascii_alphanumeric() || matches!(c, '-' | '.' | '_' | '~') {
            out.push(c);
        } else {
            out.push_str(&format!("%{:02X}", b));
        }
    }
    out
}

pub fn build_url(template: &str, query: &str) -> String {
    template.replace("{query}", &encode_query(query))
}

pub fn search_url(engine_id: &str, query: &str) -> String {
    build_url(engine(engine_id).unwrap_or(default_engine()).template, query)
}

pub fn suggest_url(engine_id: &str, query: &str) -> Option<String> {
    engine(engine_id)
        .and_then(|e| e.suggest)
        .map(|t| build_url(t, query))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_and_lookup() {
        assert_eq!(default_engine().id, "duckduckgo");
        assert_eq!(engine("google").unwrap().label, "Google");
    }

    #[test]
    fn encoding_and_templates() {
        assert_eq!(encode_query("how to boil water"), "how%20to%20boil%20water");
        assert_eq!(encode_query("a+b&c=d"), "a%2Bb%26c%3Dd");
        assert_eq!(
            search_url("duckduckgo", "cat pics"),
            "https://duckduckgo.com/?q=cat%20pics"
        );
        assert!(suggest_url("duckduckgo", "x").unwrap().contains("type=list"));
        assert!(suggest_url("bing", "x").is_none());
    }
}
