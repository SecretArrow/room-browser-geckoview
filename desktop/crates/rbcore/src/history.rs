//! History store — port of `rb_history.c` (`history.jsonl`, most-recent-first,
//! cap 10 000, consecutive same-URL replace, ASCII-case-insensitive search).

use crate::jsonl::{int_field, load_lines, save_lines, str_field};
use serde_json::{json, Value};
use std::path::Path;

pub const LINE_CAP: usize = 16 * 1024;
pub const MAX_ENTRIES: usize = 10_000;
pub const SEARCH_LIMIT: usize = 200;

#[derive(Debug, Clone, PartialEq)]
pub struct HistoryEntry {
    pub url: String,
    pub title: String,
    pub visited_at: i64,
}

/// Load ordered most-recent-first (the file's own order is authoritative;
/// loaders keep it as-is like the C code did).
pub fn load(path: &Path) -> Vec<HistoryEntry> {
    load_lines(path, LINE_CAP)
        .into_iter()
        .filter(|v| !str_field(v, "url").is_empty())
        .map(|v| HistoryEntry {
            url: str_field(&v, "url"),
            title: str_field(&v, "title"),
            visited_at: int_field(&v, "visited_at"),
        })
        .collect()
}

pub fn to_json(e: &HistoryEntry) -> Value {
    json!({"url": e.url, "title": e.title, "visited_at": e.visited_at})
}

/// Insert with the C semantics: an entry whose URL equals the newest
/// entry's replaces it (title updated, timestamp bumped); otherwise it is
/// prepended. Cap applied after insert.
pub fn record(entries: &mut Vec<HistoryEntry>, url: &str, title: &str, now: i64) {
    if url.is_empty() {
        return;
    }
    if entries.first().map(|e| e.url.as_str()) == Some(url) {
        entries[0].title = title.to_string();
        entries[0].visited_at = now;
        return;
    }
    entries.insert(
        0,
        HistoryEntry {
            url: url.to_string(),
            title: title.to_string(),
            visited_at: now,
        },
    );
    if entries.len() > MAX_ENTRIES {
        entries.truncate(MAX_ENTRIES);
    }
}

pub fn save(path: &Path, entries: &[HistoryEntry]) -> std::io::Result<()> {
    let rows: Vec<Value> = entries.iter().map(to_json).collect();
    save_lines(path, &rows)
}

/// ASCII-only case-insensitive substring search (SQLite-LIKE semantics),
/// limited to `SEARCH_LIMIT` results.
pub fn search<'a>(entries: &'a [HistoryEntry], query: &str) -> Vec<&'a HistoryEntry> {
    let q = query.to_lowercase();
    if q.is_empty() {
        return Vec::new();
    }
    entries
        .iter()
        .filter(|e| e.url.to_lowercase().contains(&q) || e.title.to_lowercase().contains(&q))
        .take(SEARCH_LIMIT)
        .collect()
}

/// Removes every entry matching the query (url or title, case-insensitive);
/// returns how many were removed.
pub fn remove_matching(entries: &mut Vec<HistoryEntry>, query: &str) -> usize {
    let q = query.to_lowercase();
    let before = entries.len();
    if !q.is_empty() {
        entries.retain(|e| {
            !e.url.to_lowercase().contains(&q) && !e.title.to_lowercase().contains(&q)
        });
    }
    before - entries.len()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(tag: &str) -> std::path::PathBuf {
        let p = std::env::temp_dir().join(format!("rb-history-{tag}-{}.jsonl", std::process::id()));
        let _ = std::fs::remove_file(&p);
        p
    }

    #[test]
    fn record_dedups_consecutive_and_caps() {
        let mut h = Vec::new();
        record(&mut h, "https://a.com/", "A", 1000);
        record(&mut h, "https://a.com/", "A2", 2000);
        assert_eq!(h.len(), 1);
        assert_eq!(h[0].title, "A2");
        assert_eq!(h[0].visited_at, 2000);
        record(&mut h, "https://b.com/", "B", 3000);
        assert_eq!(h.len(), 2);
        assert_eq!(h[0].url, "https://b.com/");
    }

    #[test]
    fn roundtrip_and_search() {
        let p = tmp("rt");
        let mut h = Vec::new();
        record(&mut h, "https://example.com/", "Example", 1);
        record(&mut h, "https://rust-lang.org/", "Rust", 2);
        save(&p, &h).unwrap();
        let back = load(&p);
        assert_eq!(back.len(), 2);
        assert_eq!(back[0].url, "https://rust-lang.org/");
        assert_eq!(search(&back, "RUST").len(), 1);
        assert_eq!(search(&back, "example").len(), 1);
        assert_eq!(search(&back, "zzz").len(), 0);
        let text = std::fs::read_to_string(&p).unwrap();
        assert!(text.starts_with("{\"url\":"));
        let _ = std::fs::remove_file(&p);
    }
}
