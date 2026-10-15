//! Tabs — port of `rb_tabs.c` (the JSONL session format) plus the profile
//! settings pair (`open_tabs`/`active_tab`) the desktop really persists.
//! Private tabs are never written; closed rows persist 30 days for reopen.

use crate::jsonl::{int_field, load_lines, save_lines, str_field};
use serde_json::{json, Value};
use std::path::Path;

pub const LINE_CAP: usize = 16 * 1024;
pub const CLOSED_RETENTION_MS: i64 = 30 * 24 * 3600 * 1000;

#[derive(Debug, Clone, PartialEq)]
pub struct TabRecord {
    pub id: i64,
    pub position: i64,
    pub is_pinned: bool,
    pub is_private: bool,
    pub created_at: i64,
    pub last_viewed_at: i64,
    pub closed_at: i64,
    pub title: String,
    pub url: String,
    pub group_name: String,
}

#[derive(Debug, Clone, Default)]
pub struct Tabs {
    /// Positional order.
    pub items: Vec<TabRecord>,
    next_id: i64,
}

impl Tabs {
    pub fn new() -> Self {
        Tabs { items: Vec::new(), next_id: 1 }
    }

    pub fn load(path: &Path) -> Tabs {
        let mut t = Tabs::new();
        for v in load_lines(path, LINE_CAP) {
            let url = str_field(&v, "url");
            if url.is_empty() {
                continue;
            }
            let id = int_field(&v, "id");
            if id <= 0 {
                continue;
            }
            t.next_id = t.next_id.max(id + 1);
            t.items.push(TabRecord {
                id,
                position: int_field(&v, "position"),
                is_pinned: crate::jsonl::bool_field(&v, "is_pinned"),
                is_private: crate::jsonl::bool_field(&v, "is_private"),
                created_at: int_field(&v, "created_at"),
                last_viewed_at: int_field(&v, "last_viewed_at"),
                closed_at: int_field(&v, "closed_at"),
                title: str_field(&v, "title"),
                url,
                group_name: str_field(&v, "group_name"),
            });
        }
        t
    }

    /// Private tabs are NEVER written.
    pub fn save(&self, path: &Path) -> std::io::Result<()> {
        let rows: Vec<Value> = self
            .items
            .iter()
            .filter(|x| !x.is_private)
            .map(|x| {
                json!({
                    "id": x.id,
                    "position": x.position,
                    "is_pinned": if x.is_pinned { 1 } else { 0 },
                    "is_private": 0,
                    "created_at": x.created_at,
                    "last_viewed_at": x.last_viewed_at,
                    "closed_at": x.closed_at,
                    "title": x.title,
                    "url": x.url,
                    "group_name": x.group_name,
                })
            })
            .collect();
        save_lines(path, &rows)
    }

    pub fn add(&mut self, url: &str, title: &str, now: i64) -> i64 {
        let id = self.next_id;
        self.next_id += 1;
        let position = self.items.iter().map(|x| x.position).max().unwrap_or(-1) + 1;
        self.items.push(TabRecord {
            id,
            position,
            is_pinned: false,
            is_private: false,
            created_at: now,
            last_viewed_at: now,
            closed_at: 0,
            title: title.to_string(),
            url: url.to_string(),
            group_name: String::new(),
        });
        id
    }

    /// Close (kept for reopen) vs remove entirely.
    pub fn close(&mut self, id: i64, now: i64) -> bool {
        if let Some(x) = self.items.iter_mut().find(|x| x.id == id) {
            x.closed_at = now;
            true
        } else {
            false
        }
    }

    pub fn remove(&mut self, id: i64) -> bool {
        let before = self.items.len();
        self.items.retain(|x| x.id != id);
        before != self.items.len()
    }

    /// Reopen the most recently closed tab within retention.
    pub fn reopen_closed(&mut self, now: i64) -> Option<i64> {
        let best = self
            .items
            .iter()
            .filter(|x| x.closed_at > 0 && now - x.closed_at <= CLOSED_RETENTION_MS)
            .max_by_key(|x| x.closed_at)?;
        let id = best.id;
        if let Some(x) = self.items.iter_mut().find(|x| x.id == id) {
            x.closed_at = 0;
            x.last_viewed_at = now;
        }
        Some(id)
    }

    pub fn open_tabs(&self) -> Vec<&TabRecord> {
        let mut v: Vec<&TabRecord> = self.items.iter().filter(|x| x.closed_at == 0).collect();
        v.sort_by(|a, b| a.position.cmp(&b.position));
        v
    }

    pub fn set_url(&mut self, id: i64, url: &str, title: &str, now: i64) -> bool {
        if let Some(x) = self.items.iter_mut().find(|x| x.id == id) {
            x.url = url.to_string();
            x.title = title.to_string();
            x.last_viewed_at = now;
            true
        } else {
            false
        }
    }

    /// Prune closed rows older than the 30-day retention.
    pub fn prune_closed(&mut self, now: i64) -> usize {
        let before = self.items.len();
        self.items.retain(|x| x.closed_at == 0 || now - x.closed_at <= CLOSED_RETENTION_MS);
        before - self.items.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(tag: &str) -> std::path::PathBuf {
        let p = std::env::temp_dir().join(format!("rb-tabs-{tag}-{}.jsonl", std::process::id()));
        let _ = std::fs::remove_file(&p);
        p
    }

    #[test]
    fn private_tabs_never_written() {
        let p = tmp("priv");
        let mut t = Tabs::new();
        t.add("https://a.com/", "A", 1);
        let b = t.add("https://b.com/", "B", 2);
        if let Some(x) = t.items.iter_mut().find(|x| x.id == b) {
            x.is_private = true;
        }
        t.save(&p).unwrap();
        let back = Tabs::load(&p);
        assert_eq!(back.items.len(), 1);
        assert_eq!(back.items[0].url, "https://a.com/");
        let _ = std::fs::remove_file(&p);
    }

    #[test]
    fn close_reopen_retention() {
        let mut t = Tabs::new();
        let a = t.add("https://a.com/", "A", 1000);
        let b = t.add("https://b.com/", "B", 1000);
        assert!(t.close(a, 2000));
        assert!(t.close(b, 5000));
        assert_eq!(t.open_tabs().len(), 0);
        // reopen most recent
        let got = t.reopen_closed(6000).unwrap();
        assert_eq!(got, b);
        // expired closed row is not reopenable
        assert!(t.close(a, 1000));
        assert_eq!(t.reopen_closed(1000 + CLOSED_RETENTION_MS + 1), None);
        assert_eq!(t.prune_closed(1000 + CLOSED_RETENTION_MS + 1), 1);
    }
}
