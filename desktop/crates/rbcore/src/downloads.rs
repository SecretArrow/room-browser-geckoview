//! Downloads store — port of `rb_downloads.c` (`downloads.jsonl`).
//!
//! Wire contracts: status names QUEUED/RUNNING/PAUSED/COMPLETED/FAILED/
//! CANCELLED never change; `total_bytes` = -1 while unknown; `completed_at`
//! = 0 means never; ids from 1 preserved across loads; max 2 parallel;
//! filenames sanitized and deduped `name (1).ext`.

use crate::jsonl::{int_field, load_lines, save_lines, str_field};
use serde_json::{json, Value};
use std::path::Path;

pub const LINE_CAP: usize = 16 * 1024;
pub const MAX_PARALLEL: usize = 2;
pub const MAX_FILENAME_BYTES: usize = 120;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Status {
    Queued,
    Running,
    Paused,
    Completed,
    Failed,
    Cancelled,
}

impl Status {
    pub fn as_str(self) -> &'static str {
        match self {
            Status::Queued => "QUEUED",
            Status::Running => "RUNNING",
            Status::Paused => "PAUSED",
            Status::Completed => "COMPLETED",
            Status::Failed => "FAILED",
            Status::Cancelled => "CANCELLED",
        }
    }
    pub fn parse(s: &str) -> Status {
        match s {
            "RUNNING" => Status::Running,
            "PAUSED" => Status::Paused,
            "COMPLETED" => Status::Completed,
            "FAILED" => Status::Failed,
            "CANCELLED" => Status::Cancelled,
            _ => Status::Queued,
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct DownloadRecord {
    pub id: i64,
    pub profile_id: String,
    pub url: String,
    pub file_name: String,
    pub mime_type: String,
    pub destination: String,
    pub error: String,
    pub total_bytes: i64,
    pub downloaded_bytes: i64,
    pub status: Status,
    pub created_at: i64,
    pub completed_at: i64,
}

#[derive(Debug, Clone, Default)]
pub struct Downloads {
    /// newest-first (created_at DESC).
    pub items: Vec<DownloadRecord>,
    next_id: i64,
}

/// Filename sanitising (`rb_downloads_name`): `\ / : * ? " < > |` → `_`,
/// `..` collapsed, trimmed, ≤120 bytes, blank → `download`.
pub fn sanitize_name(name: &str) -> String {
    let mut s: String = name
        .chars()
        .map(|c| match c {
            '\\' | '/' | ':' | '*' | '?' | '"' | '<' | '>' | '|' => '_',
            _ => c,
        })
        .collect();
    while s.contains("..") {
        s = s.replace("..", "_");
    }
    let mut s = s.trim().to_string();
    if s.len() > MAX_FILENAME_BYTES {
        // cut at a char boundary
        while s.len() > MAX_FILENAME_BYTES {
            s.pop();
        }
    }
    if s.is_empty() {
        s = "download".into();
    }
    s
}

/// Dedupe against existing filenames: `name (1).ext`, `name (2).ext`…
pub fn dedupe_name(name: &str, existing: &[String]) -> String {
    if !existing.iter().any(|x| x == name) {
        return name.to_string();
    }
    let (stem, ext) = match name.rfind('.') {
        Some(i) if i > 0 => (&name[..i], &name[i..]),
        _ => (name, ""),
    };
    for n in 1..1000 {
        let candidate = format!("{stem} ({n}){ext}");
        if !existing.iter().any(|x| x == &candidate) {
            return candidate;
        }
    }
    format!("{stem} ({}){ext}", std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or(0))
}

/// `rb_downloads_format_bytes`: `0 B`, `512 B`, `1.4 MB`, `2.0 GB`.
pub fn format_bytes(bytes: i64) -> String {
    if bytes < 0 {
        return "?".into();
    }
    if bytes < 1024 {
        return format!("{bytes} B");
    }
    let kb = bytes as f64 / 1024.0;
    if kb < 1024.0 {
        return format!("{:.1} KB", kb);
    }
    let mb = kb / 1024.0;
    if mb < 1024.0 {
        return format!("{:.1} MB", mb);
    }
    format!("{:.1} GB", mb / 1024.0)
}

impl Downloads {
    pub fn new() -> Self {
        Downloads { items: Vec::new(), next_id: 1 }
    }

    pub fn load(path: &Path) -> Downloads {
        let mut d = Downloads::new();
        let rows = load_lines(path, LINE_CAP);
        for v in rows {
            let url = str_field(&v, "url");
            if url.is_empty() {
                continue;
            }
            let id = int_field(&v, "id");
            if id <= 0 {
                continue;
            }
            d.next_id = d.next_id.max(id + 1);
            d.items.push(DownloadRecord {
                id,
                profile_id: str_field(&v, "profile_id"),
                url,
                file_name: str_field(&v, "file_name"),
                mime_type: str_field(&v, "mime_type"),
                destination: str_field(&v, "destination"),
                error: str_field(&v, "error"),
                total_bytes: int_field(&v, "total_bytes"),
                downloaded_bytes: int_field(&v, "downloaded_bytes"),
                status: Status::parse(&str_field(&v, "status")),
                created_at: int_field(&v, "created_at"),
                completed_at: int_field(&v, "completed_at"),
            });
        }
        // newest first
        d.items.sort_by(|a, b| b.created_at.cmp(&a.created_at).then(b.id.cmp(&a.id)));
        d
    }

    pub fn save(&self, path: &Path) -> std::io::Result<()> {
        let rows: Vec<Value> = self
            .items
            .iter()
            .map(|x| {
                json!({
                    "id": x.id,
                    "profile_id": x.profile_id,
                    "url": x.url,
                    "file_name": x.file_name,
                    "mime_type": x.mime_type,
                    "destination": x.destination,
                    "error": x.error,
                    "total_bytes": x.total_bytes,
                    "downloaded_bytes": x.downloaded_bytes,
                    "status": x.status.as_str(),
                    "created_at": x.created_at,
                    "completed_at": x.completed_at,
                })
            })
            .collect();
        save_lines(path, &rows)
    }

    /// Scoped view of one profile's records (newest first).
    pub fn for_profile(&self, profile_id: &str) -> Vec<&DownloadRecord> {
        self.items.iter().filter(|x| x.profile_id == profile_id).collect()
    }

    /// How many records of this profile are actively transferring.
    pub fn active_count(&self, profile_id: &str) -> usize {
        self.items
            .iter()
            .filter(|x| x.profile_id == profile_id && matches!(x.status, Status::Queued | Status::Running))
            .count()
    }

    pub fn queue(&mut self, profile_id: &str, url: &str, name_hint: &str, mime: &str, now: i64) -> i64 {
        let existing: Vec<String> = self
            .items
            .iter()
            .filter(|x| x.profile_id == profile_id)
            .map(|x| x.file_name.clone())
            .collect();
        let file_name = dedupe_name(&sanitize_name(name_hint), &existing);
        let id = self.next_id;
        self.next_id += 1;
        self.items.insert(
            0,
            DownloadRecord {
                id,
                profile_id: profile_id.to_string(),
                url: url.to_string(),
                file_name,
                mime_type: mime.to_string(),
                destination: String::new(),
                error: String::new(),
                total_bytes: -1,
                downloaded_bytes: 0,
                status: Status::Queued,
                created_at: now,
                completed_at: 0,
            },
        );
        id
    }

    pub fn set_progress(&mut self, id: i64, downloaded: i64, total: i64) -> bool {
        if let Some(x) = self.items.iter_mut().find(|x| x.id == id) {
            x.downloaded_bytes = downloaded;
            x.total_bytes = total;
            true
        } else {
            false
        }
    }

    pub fn set_running(&mut self, id: i64) -> bool {
        if let Some(x) = self.items.iter_mut().find(|x| x.id == id) {
            x.status = Status::Running;
            true
        } else {
            false
        }
    }

    pub fn set_paused(&mut self, id: i64, downloaded: i64) -> bool {
        if let Some(x) = self.items.iter_mut().find(|x| x.id == id) {
            x.status = Status::Paused;
            x.downloaded_bytes = downloaded;
            true
        } else {
            false
        }
    }

    pub fn complete(&mut self, id: i64, destination: &str, now: i64) -> bool {
        if let Some(x) = self.items.iter_mut().find(|x| x.id == id) {
            x.status = Status::Completed;
            x.destination = destination.to_string();
            x.completed_at = now;
            x.error.clear();
            true
        } else {
            false
        }
    }

    pub fn fail(&mut self, id: i64, reason: &str, now: i64) -> bool {
        if let Some(x) = self.items.iter_mut().find(|x| x.id == id) {
            x.status = Status::Failed;
            x.error = reason.to_string();
            x.completed_at = now;
            true
        } else {
            false
        }
    }

    pub fn cancel(&mut self, id: i64) -> bool {
        if let Some(x) = self.items.iter_mut().find(|x| x.id == id) {
            if matches!(x.status, Status::Queued | Status::Running | Status::Paused) {
                x.status = Status::Cancelled;
                return true;
            }
        }
        false
    }

    pub fn remove(&mut self, id: i64) -> bool {
        let before = self.items.len();
        self.items.retain(|x| x.id != id);
        before != self.items.len()
    }

    /// Startup reconciliation: leftover QUEUED/RUNNING rows become FAILED
    /// with the given reason ("Interrupted" at startup, "Profile switched"
    /// on profile switch).
    pub fn reconcile(&mut self, profile_id: &str, reason: &str, now: i64) -> usize {
        let mut n = 0;
        for x in self.items.iter_mut() {
            if x.profile_id == profile_id && matches!(x.status, Status::Queued | Status::Running) {
                x.status = Status::Failed;
                x.error = reason.to_string();
                x.completed_at = now;
                n += 1;
            }
        }
        n
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(tag: &str) -> std::path::PathBuf {
        let p = std::env::temp_dir().join(format!("rb-dl-{tag}-{}.jsonl", std::process::id()));
        let _ = std::fs::remove_file(&p);
        p
    }

    #[test]
    fn sanitize_and_dedupe() {
        assert_eq!(sanitize_name("a/b\\c:d*e?f\"g<h>i|j"), "a_b_c_d_e_f_g_h_i_j");
        assert_eq!(sanitize_name("  ..x..  "), "_x_");
        assert_eq!(sanitize_name(""), "download");
        let long = "a".repeat(500);
        assert!(sanitize_name(&long).len() <= MAX_FILENAME_BYTES);
        assert_eq!(dedupe_name("file.zip", &[]), "file.zip");
        assert_eq!(dedupe_name("file.zip", &["file.zip".into()]), "file (1).zip");
        assert_eq!(
            dedupe_name("file.zip", &["file.zip".into(), "file (1).zip".into()]),
            "file (2).zip"
        );
    }

    #[test]
    fn format_bytes_contract() {
        assert_eq!(format_bytes(0), "0 B");
        assert_eq!(format_bytes(512), "512 B");
        assert_eq!(format_bytes(1024), "1.0 KB");
        assert_eq!(format_bytes(1536 * 1024), "1.5 MB");
        assert_eq!(format_bytes(-1), "?");
    }

    #[test]
    fn lifecycle_and_scoping() {
        let mut d = Downloads::new();
        let a = d.queue("p1", "https://x/f.zip", "f.zip", "application/zip", 1000);
        let b = d.queue("p1", "https://x/f.zip", "f.zip", "application/zip", 1001);
        let c = d.queue("p2", "https://y/g.zip", "g.zip", "application/zip", 1002);
        assert_eq!(d.for_profile("p1").len(), 2);
        assert_eq!(a, 1);
        assert!(d.set_running(a));
        assert!(d.set_progress(a, 50, 200));
        assert!(d.complete(a, "/dl/f.zip", 2000));
        // dedupe applied within the profile
        assert_eq!(d.items.iter().find(|x| x.id == b).unwrap().file_name, "f (1).zip");
        // cross-profile no dedupe
        assert_eq!(d.items.iter().find(|x| x.id == c).unwrap().file_name, "g.zip");
        assert!(d.fail(b, "Interrupted", 3000));
        assert!(d.reconcile("p2", "Interrupted", 4000) >= 1);
        assert_eq!(d.items.iter().find(|x| x.id == c).unwrap().status, Status::Failed);
    }

    #[test]
    fn roundtrip_wire_format() {
        let p = tmp("rt");
        let mut d = Downloads::new();
        let id = d.queue("p1", "https://x/f.zip", "f.zip", "application/zip", 1);
        d.set_running(id);
        d.set_progress(id, 10, -1);
        d.save(&p).unwrap();
        let text = std::fs::read_to_string(&p).unwrap();
        assert!(text.contains("\"status\":\"RUNNING\""));
        assert!(text.contains("\"total_bytes\":-1"));
        let key = |k: &str| text.find(&format!("\"{k}\":")).unwrap();
        assert!(key("id") < key("profile_id"));
        assert!(key("profile_id") < key("url"));
        assert!(key("completed_at") > key("created_at"));
        let back = Downloads::load(&p);
        assert_eq!(back.items.len(), 1);
        assert_eq!(back.items[0].status, Status::Running);
        assert_eq!(back.items[0].total_bytes, -1);
        // ids preserved and counter advances
        let mut d2 = back;
        let nid = d2.queue("p1", "https://x/z.zip", "z.zip", "", 2);
        assert_eq!(nid, 2);
        let _ = std::fs::remove_file(&p);
    }
}
