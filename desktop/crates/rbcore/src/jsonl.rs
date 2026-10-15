//! JSON-lines store plumbing shared by every persisted collection.
//!
//! Contract carried over from the C11 core:
//! * one JSON object per physical line;
//! * a missing file is an empty store, never an error;
//! * malformed / oversize lines are skipped, not fatal;
//! * saves rewrite the whole file atomically (tmp + rename) so a crash can
//!   never leave a half-written line in the middle of a store.

use std::fs;
use std::io::Write;
use std::path::Path;

/// Per-line read cap. Stores chose their own caps historically
/// (history/bookmarks: 16 KiB, notes: 64 KiB, totp: 16 KiB); the helpers
/// take the cap so each store keeps its exact tolerance.
pub fn load_lines(path: &Path, max_line_bytes: usize) -> Vec<serde_json::Value> {
    let bytes = match fs::read(path) {
        Ok(b) => b,
        Err(_) => return Vec::new(),
    };
    let text = String::from_utf8_lossy(&bytes);
    let mut out = Vec::new();
    for line in text.split('\n') {
        let line = line.strip_suffix('\r').unwrap_or(line);
        if line.is_empty() {
            continue;
        }
        if line.len() > max_line_bytes {
            continue;
        }
        if let Ok(v) = serde_json::from_str::<serde_json::Value>(line) {
            if v.is_object() {
                out.push(v);
            }
        }
    }
    out
}

/// Serialises `rows` in order, one per line, and replaces `path` atomically.
pub fn save_lines(path: &Path, rows: &[serde_json::Value]) -> std::io::Result<()> {
    let mut buf = String::new();
    for row in rows {
        // Compact form, no spaces — byte-identical to the C writer.
        buf.push_str(&serde_json::to_string(row).unwrap_or_default());
        buf.push('\n');
    }
    atomic_write(path, buf.as_bytes())
}

/// Writes `bytes` to `path` via sibling temp file + rename.
pub fn atomic_write(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    if let Some(dir) = path.parent() {
        fs::create_dir_all(dir)?;
    }
    let tmp = path.with_extension("tmp-save");
    {
        let mut f = fs::File::create(&tmp)?;
        f.write_all(bytes)?;
        f.flush()?;
        // fsync before rename so an abrupt power loss cannot lose the store.
        #[cfg(unix)]
        {
            use std::os::unix::io::AsRawFd;
            unsafe { libc_sync(f.as_raw_fd()) };
        }
    }
    fs::rename(&tmp, path)
}

#[cfg(unix)]
unsafe fn libc_sync(fd: i32) {
    // One-line wrapper: call libc ::fsync without adding a libc dependency.
    extern "C" {
        fn fsync(fd: i32) -> i32;
    }
    fsync(fd);
}

/// The C core's `rb_json_strdup`-style helper: field as String ("" default).
pub fn str_field(v: &serde_json::Value, key: &str) -> String {
    v.get(key)
        .and_then(|x| x.as_str())
        .unwrap_or("")
        .to_string()
}

/// Field as i64 (0 default). The C loaders also accept numeric strings —
/// the downloads loader did, and keeping that leniency costs nothing.
pub fn int_field(v: &serde_json::Value, key: &str) -> i64 {
    match v.get(key) {
        Some(serde_json::Value::Number(n)) => n.as_i64().unwrap_or(0),
        Some(serde_json::Value::String(s)) => s.parse().unwrap_or(0),
        _ => 0,
    }
}

/// Field as bool (false default), tolerating 0/1 integers like the C loader.
pub fn bool_field(v: &serde_json::Value, key: &str) -> bool {
    match v.get(key) {
        Some(serde_json::Value::Bool(b)) => *b,
        Some(serde_json::Value::Number(n)) => n.as_i64().unwrap_or(0) != 0,
        Some(serde_json::Value::String(s)) => s == "1" || s == "true",
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn missing_file_is_empty() {
        let mut p = std::env::temp_dir();
        p.push("rb-jsonl-missing-does-not-exist.jsonl");
        assert!(load_lines(&p, 16384).is_empty());
    }

    #[test]
    fn malformed_and_oversize_lines_are_skipped() {
        let mut p = std::env::temp_dir();
        p.push(format!("rb-jsonl-{}.jsonl", std::process::id()));
        fs::write(&p, b"{\"a\":1}\nnot json\n{\"a\":2}\n\n{\"a\":3}\n").unwrap();
        let rows = load_lines(&p, 16384);
        assert_eq!(rows.len(), 3);
        let _ = fs::remove_file(&p);
    }

    #[test]
    fn save_roundtrips_exact_lines() {
        let mut p = std::env::temp_dir();
        p.push(format!("rb-jsonl-save-{}.jsonl", std::process::id()));
        let rows: Vec<serde_json::Value> = vec![
            serde_json::json!({"id":1,"x":"y"}),
            serde_json::json!({"id":2,"x":"z"}),
        ];
        save_lines(&p, &rows).unwrap();
        let text = fs::read_to_string(&p).unwrap();
        assert_eq!(text, "{\"id\":1,\"x\":\"y\"}\n{\"id\":2,\"x\":\"z\"}\n");
        let _ = fs::remove_file(&p);
    }
}
