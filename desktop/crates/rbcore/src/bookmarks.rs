//! Bookmarks store — port of `rb_bookmarks.c` (`bookmarks.jsonl`).
//!
//! Exact writer field order: `id, position, created_at, url, title, folder`
//! with `folder` an explicit JSON null when there is no folder.

use crate::jsonl::{int_field, load_lines, save_lines, str_field};
use serde_json::{json, Value};
use std::path::Path;

pub const LINE_CAP: usize = 16 * 1024;

#[derive(Debug, Clone, PartialEq)]
pub struct Bookmark {
    pub id: i64,
    pub position: i64,
    pub created_at: i64,
    pub url: String,
    pub title: String,
    pub folder: Option<String>,
}

#[derive(Debug, Clone, Default)]
pub struct Bookmarks {
    pub items: Vec<Bookmark>,
    next_id: i64,
}

impl Bookmarks {
    pub fn new() -> Self {
        Bookmarks {
            next_id: 1,
            ..Default::default()
        }
    }

    pub fn load(path: &Path) -> Self {
        let mut b = Bookmarks::new();
        let mut seen_urls: Vec<String> = Vec::new();
        for v in load_lines(path, LINE_CAP) {
            let url = str_field(&v, "url");
            if url.is_empty() || seen_urls.contains(&url) {
                continue; // URL-unique on load, first row wins
            }
            let id = int_field(&v, "id");
            if id <= 0 {
                continue;
            }
            seen_urls.push(url.clone());
            b.next_id = b.next_id.max(id + 1);
            b.items.push(Bookmark {
                id,
                position: int_field(&v, "position"),
                created_at: int_field(&v, "created_at"),
                url,
                title: str_field(&v, "title"),
                folder: v.get("folder").and_then(|f| f.as_str()).map(|s| s.to_string()),
            });
        }
        b
    }

    pub fn save(&self, path: &Path) -> std::io::Result<()> {
        let rows: Vec<Value> = self
            .items
            .iter()
            .map(|x| {
                json!({
                    "id": x.id,
                    "position": x.position,
                    "created_at": x.created_at,
                    "url": x.url,
                    "title": x.title,
                    "folder": x.folder,
                })
            })
            .collect();
        save_lines(path, &rows)
    }

    /// Add with duplicate-URL refusal (returns -1 like the C `rb_bookmarks_add`).
    pub fn add(&mut self, url: &str, title: &str, folder: Option<&str>, now: i64) -> i64 {
        if url.is_empty() || self.items.iter().any(|x| x.url == url) {
            return -1;
        }
        let id = self.next_id;
        self.next_id += 1;
        let position = self.items.iter().map(|x| x.position).max().unwrap_or(-1) + 1;
        self.items.push(Bookmark {
            id,
            position,
            created_at: now,
            url: url.to_string(),
            title: title.to_string(),
            folder: folder.map(|s| s.to_string()),
        });
        id
    }

    pub fn remove_by_url(&mut self, url: &str) -> bool {
        let before = self.items.len();
        self.items.retain(|x| x.url != url);
        before != self.items.len()
    }

    pub fn has(&self, url: &str) -> bool {
        self.items.iter().any(|x| x.url == url)
    }

    /// Display order: folderless LAST, then folder name ASC, then position
    /// ASC, then created_at ASC, then id.
    pub fn display_order(&self) -> Vec<&Bookmark> {
        let mut v: Vec<&Bookmark> = self.items.iter().collect();
        v.sort_by(|a, b| {
            let fa = a.folder.as_deref().unwrap_or("");
            let fb = b.folder.as_deref().unwrap_or("");
            let na = a.folder.is_none();
            let nb = b.folder.is_none();
            na.cmp(&nb)
                .then_with(|| fa.cmp(fb))
                .then_with(|| a.position.cmp(&b.position))
                .then_with(|| a.created_at.cmp(&b.created_at))
                .then_with(|| a.id.cmp(&b.id))
        });
        v
    }

    /// Folders in stable first-seen order (for the bookmarks-bar popup).
    pub fn folders(&self) -> Vec<String> {
        let mut out: Vec<String> = Vec::new();
        for b in &self.items {
            if let Some(f) = &b.folder {
                if !out.contains(f) {
                    out.push(f.clone());
                }
            }
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(tag: &str) -> std::path::PathBuf {
        let p = std::env::temp_dir().join(format!("rb-bm-{tag}-{}.jsonl", std::process::id()));
        let _ = std::fs::remove_file(&p);
        p
    }

    #[test]
    fn add_refuses_duplicate_urls() {
        let mut b = Bookmarks::new();
        assert!(b.add("https://a.com/", "A", None, 1) > 0);
        assert_eq!(b.add("https://a.com/", "A again", None, 2), -1);
        assert_eq!(b.items.len(), 1);
    }

    #[test]
    fn roundtrip_with_null_folder() {
        let p = tmp("rt");
        let mut b = Bookmarks::new();
        b.add("https://a.com/", "A", None, 1);
        b.add("https://b.com/", "B", Some("Work"), 2);
        b.save(&p).unwrap();
        let text = std::fs::read_to_string(&p).unwrap();
        assert!(text.contains("\"folder\":null"));
        let key_order = |k: &str| text.find(&format!("\"{k}\":")).unwrap();
        assert!(key_order("id") < key_order("position"));
        assert!(key_order("position") < key_order("created_at"));
        assert!(key_order("created_at") < key_order("url"));
        assert!(key_order("url") < key_order("title"));
        assert!(key_order("title") < key_order("folder"));
        let back = Bookmarks::load(&p);
        assert_eq!(back.items.len(), 2);
        assert_eq!(back.items[0].folder, None);
        assert_eq!(back.items[1].folder.as_deref(), Some("Work"));
        // ids preserved and next advances past the highest
        let mut b2 = back;
        let nid = b2.add("https://c.com/", "C", None, 3);
        assert_eq!(nid, 3);
        let _ = std::fs::remove_file(&p);
    }

    #[test]
    fn display_order_folderless_last() {
        let mut b = Bookmarks::new();
        b.add("https://x.com/", "X", Some("Zed"), 1);
        b.add("https://y.com/", "Y", None, 2);
        b.add("https://z.com/", "Z", Some("Alpha"), 3);
        let order: Vec<&str> = b.display_order().iter().map(|x| x.title.as_str()).collect();
        assert_eq!(order, vec!["Z", "X", "Y"]);
    }
}
