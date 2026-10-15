//! Notes store — port of `rb_notes.c` (`notes.jsonl`).
//!
//! One note per line, field order `id, title, body, created_at, updated_at`;
//! body newlines ride as `\n` JSON escapes so one row = one physical line;
//! display order `updated_at DESC, id DESC`; whole-store swap on load.

use crate::jsonl::{int_field, load_lines, save_lines};
use serde_json::{json, Value};
use std::path::Path;

pub const LINE_CAP: usize = 64 * 1024;

#[derive(Debug, Clone, PartialEq)]
pub struct Note {
    pub id: i64,
    pub title: String,
    pub body: String,
    pub created_at: i64,
    pub updated_at: i64,
}

#[derive(Debug, Clone, Default)]
pub struct Notes {
    pub items: Vec<Note>,
    next_id: i64,
}

impl Notes {
    pub fn new() -> Self {
        Notes {
            next_id: 1,
            ..Default::default()
        }
    }

    /// Whole-store swap: rows that don't appear in the file must not survive.
    pub fn load(path: &Path) -> Notes {
        let mut n = Notes::new();
        for v in load_lines(path, LINE_CAP) {
            let title = v.get("title").and_then(|t| t.as_str());
            let body = v.get("body").and_then(|b| b.as_str());
            let (Some(title), Some(body)) = (title, body) else { continue };
            let mut id = int_field(&v, "id");
            if id <= 0 {
                id = n.next_id; // id<=0 rows get fresh ids
            }
            n.next_id = n.next_id.max(id + 1);
            n.items.push(Note {
                id,
                title: title.to_string(),
                body: body.to_string(),
                created_at: int_field(&v, "created_at"),
                updated_at: int_field(&v, "updated_at"),
            });
        }
        n
    }

    pub fn save(&self, path: &Path) -> std::io::Result<()> {
        let rows: Vec<Value> = self
            .items
            .iter()
            .map(|x| {
                json!({
                    "id": x.id,
                    "title": x.title,
                    "body": x.body,
                    "created_at": x.created_at,
                    "updated_at": x.updated_at,
                })
            })
            .collect();
        save_lines(path, &rows)
    }

    pub fn add(&mut self, title: &str, body: &str, now: i64) -> i64 {
        let id = self.next_id;
        self.next_id += 1;
        self.items.push(Note {
            id,
            title: title.to_string(),
            body: body.to_string(),
            created_at: now,
            updated_at: now,
        });
        id
    }

    pub fn edit(&mut self, id: i64, title: &str, body: &str, now: i64) -> bool {
        if let Some(n) = self.items.iter_mut().find(|n| n.id == id) {
            n.title = title.to_string();
            n.body = body.to_string();
            n.updated_at = now;
            true
        } else {
            false
        }
    }

    pub fn duplicate(&mut self, id: i64, now: i64) -> Option<i64> {
        let src = self.items.iter().find(|n| n.id == id)?;
        let copy = Note {
            id: self.next_id,
            title: src.title.clone(),
            body: src.body.clone(),
            created_at: now,
            updated_at: now,
        };
        self.next_id += 1;
        self.items.push(copy);
        Some(self.items.last().unwrap().id)
    }

    pub fn remove(&mut self, id: i64) -> bool {
        let before = self.items.len();
        self.items.retain(|n| n.id != id);
        before != self.items.len()
    }

    pub fn get(&self, id: i64) -> Option<&Note> {
        self.items.iter().find(|n| n.id == id)
    }

    /// Display order: `updated_at DESC, id DESC` (newest-edited first).
    pub fn display_order(&self) -> Vec<&Note> {
        let mut v: Vec<&Note> = self.items.iter().collect();
        v.sort_by(|a, b| b.updated_at.cmp(&a.updated_at).then(b.id.cmp(&a.id)));
        v
    }

    /// Live filter on title+body (case-insensitive).
    pub fn search(&self, q: &str) -> Vec<&Note> {
        let q = q.to_lowercase();
        if q.is_empty() {
            return self.display_order();
        }
        self.display_order()
            .into_iter()
            .filter(|n| n.title.to_lowercase().contains(&q) || n.body.to_lowercase().contains(&q))
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(tag: &str) -> std::path::PathBuf {
        let p = std::env::temp_dir().join(format!("rb-notes-{tag}-{}.jsonl", std::process::id()));
        let _ = std::fs::remove_file(&p);
        p
    }

    #[test]
    fn add_edit_remove_ordering() {
        let mut n = Notes::new();
        let a = n.add("First", "body one", 1000);
        let b = n.add("Second", "body two", 2000);
        n.edit(a, "First!", "edited", 5000);
        let order: Vec<i64> = n.display_order().iter().map(|x| x.id).collect();
        assert_eq!(order, vec![a, b]); // a edited most recently
        assert!(n.remove(b));
        assert!(!n.remove(b));
        assert_eq!(n.items.len(), 1);
    }

    #[test]
    fn roundtrip_one_line_per_note_with_escaped_newlines() {
        let p = tmp("rt");
        let mut n = Notes::new();
        n.add("Shopping", "milk\neggs", 1730000000000);
        n.add("Empty body", "", 1730000000001);
        n.save(&p).unwrap();
        let text = std::fs::read_to_string(&p).unwrap();
        assert_eq!(text.lines().count(), 2);
        assert!(text.contains("\"body\":\"milk\\neggs\""));
        let key = |k: &str| text.find(&format!("\"{k}\":")).unwrap();
        assert!(key("id") < key("title"));
        assert!(key("title") < key("body"));
        assert!(key("body") < key("created_at"));
        assert!(key("created_at") < key("updated_at"));
        let back = Notes::load(&p);
        assert_eq!(back.items.len(), 2);
        assert_eq!(back.items[0].body, "milk\neggs");
        let _ = std::fs::remove_file(&p);
    }

    #[test]
    fn load_edges_skip_malformed_and_repair_ids() {
        let p = tmp("edges");
        std::fs::write(
            &p,
            concat!(
                "{\"id\":1,\"title\":\"ok\",\"body\":\"b\",\"created_at\":1,\"updated_at\":1}\n",
                "{\"id\":2,\"title\":\"no body\",\"created_at\":1,\"updated_at\":1}\n",
                "garbage\n",
                "{\"id\":0,\"title\":\"fresh\",\"body\":\"b\",\"created_at\":1,\"updated_at\":1}\n"
            ),
        )
        .unwrap();
        let mut n = Notes::load(&p);
        assert_eq!(n.items.len(), 2);
        assert!(n.items.iter().all(|x| x.id > 0));
        let max_before = n.items.iter().map(|x| x.id).max().unwrap();
        let next = n.add("another", "b", 2);
        assert!(next > max_before);
        let _ = std::fs::remove_file(&p);
    }

    #[test]
    fn whole_store_swap_on_load() {
        let p = tmp("swap");
        let mut n = Notes::new();
        n.add("A", "a", 1);
        n.add("B", "b", 2);
        n.save(&p).unwrap();
        // file now holds only one note → loading must not keep the other
        std::fs::write(
            &p,
            "{\"id\":9,\"title\":\"only\",\"body\":\"x\",\"created_at\":1,\"updated_at\":1}\n",
        )
        .unwrap();
        let back = Notes::load(&p);
        assert_eq!(back.items.len(), 1);
        assert_eq!(back.items[0].title, "only");
        let _ = std::fs::remove_file(&p);
    }
}
