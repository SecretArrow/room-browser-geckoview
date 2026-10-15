//! Password vault — port of the Android `credentials` model + repos:
//! SavedCredential with canonical-host domain matching, a password
//! generator, CSV import/export and merge.
//!
//! Desktop storage: `credentials.jsonl` in the profile settings dir, one
//! object per credential with `password_enc` = sealed:v1 blob.

use crate::jsonl::{load_lines, save_lines, str_field};
use serde_json::{json, Value};
use std::path::Path;

pub const LINE_CAP: usize = 16 * 1024;

#[derive(Debug, Clone, PartialEq)]
pub struct SavedCredential {
    pub id: String,
    pub profile_id: String,
    pub domain: String,
    pub username: String,
    pub password_enc: String,
    pub title: String,
    pub created_at: i64,
    pub updated_at: i64,
}

/// Canonical host (lowercase, port/userinfo dropped, trailing dot dropped).
pub fn canonical_host(url_or_host: &str) -> String {
    let s = url_or_host.trim().to_lowercase();
    let candidate = if let Some(rest) = s.strip_prefix("https://") {
        rest
    } else if let Some(rest) = s.strip_prefix("http://") {
        rest
    } else {
        &s
    };
    let end = candidate.find(['/', '?', '#']).unwrap_or(candidate.len());
    let mut host = &candidate[..end];
    if let Some(at) = host.rfind('@') {
        host = &host[at + 1..];
    }
    if !host.starts_with('[') {
        if let Some(c) = host.find(':') {
            host = &host[..c];
        }
    }
    host.trim_end_matches('.').to_string()
}

/// Parent-domain aware matcher (a saved entry for example.com matches
/// login.example.com but not badexample.com).
pub fn domain_matches(saved_domain: &str, page_host: &str) -> bool {
    let saved = canonical_host(saved_domain);
    let page = canonical_host(page_host);
    if saved.is_empty() || page.is_empty() {
        return false;
    }
    page == saved || page.ends_with(&format!(".{saved}"))
}

/// Password generator: length 12-64, at least one of each class when the
/// policy allows it; avoids visually ambiguous characters by default.
pub fn generate_password(length: usize, symbols: bool) -> String {
    use rand::Rng;
    let len = length.clamp(12, 64);
    let lower = b"abcdefghijkmnopqrstuvwxyz";
    let upper = b"ABCDEFGHJKLMNPQRSTUVWXYZ";
    let digits = b"23456789";
    let sym = b"!@#$%^&*()-_=+[]{}:,.?/";
    let mut alphabet: Vec<u8> = Vec::new();
    alphabet.extend_from_slice(lower);
    alphabet.extend_from_slice(upper);
    alphabet.extend_from_slice(digits);
    if symbols {
        alphabet.extend_from_slice(sym);
    }
    let mut rng = rand::thread_rng();
    let mut out: Vec<u8> = Vec::with_capacity(len);
    // guarantee one of each guaranteed class first
    out.push(lower[rng.gen_range(0..lower.len())]);
    out.push(upper[rng.gen_range(0..upper.len())]);
    out.push(digits[rng.gen_range(0..digits.len())]);
    if symbols {
        out.push(sym[rng.gen_range(0..sym.len())]);
    }
    while out.len() < len {
        out.push(alphabet[rng.gen_range(0..alphabet.len())]);
    }
    // Fisher-Yates shuffle the guaranteed characters away from the front
    for i in (1..out.len()).rev() {
        let j = rng.gen_range(0..=i);
        out.swap(i, j);
    }
    String::from_utf8(out).unwrap_or_default()
}

#[derive(Debug, Clone, Default)]
pub struct CredentialStore {
    pub items: Vec<SavedCredential>,
}

impl CredentialStore {
    pub fn load(path: &Path) -> CredentialStore {
        let mut s = CredentialStore::default();
        for v in load_lines(path, LINE_CAP) {
            let id = str_field(&v, "id");
            if id.is_empty() {
                continue;
            }
            s.items.push(SavedCredential {
                id,
                profile_id: str_field(&v, "profile_id"),
                domain: str_field(&v, "domain"),
                username: str_field(&v, "username"),
                password_enc: str_field(&v, "password_enc"),
                title: str_field(&v, "title"),
                created_at: crate::jsonl::int_field(&v, "created_at"),
                updated_at: crate::jsonl::int_field(&v, "updated_at"),
            });
        }
        s
    }

    pub fn save(&self, path: &Path) -> std::io::Result<()> {
        let rows: Vec<Value> = self
            .items
            .iter()
            .map(|c| {
                json!({
                    "id": c.id,
                    "profile_id": c.profile_id,
                    "domain": c.domain,
                    "username": c.username,
                    "password_enc": c.password_enc,
                    "title": c.title,
                    "created_at": c.created_at,
                    "updated_at": c.updated_at,
                })
            })
            .collect();
        save_lines(path, &rows)
    }

    /// Matches a page host against saved domains (parent-domain aware).
    pub fn for_host(&self, page_host: &str) -> Vec<&SavedCredential> {
        self.items
            .iter()
            .filter(|c| domain_matches(&c.domain, page_host))
            .collect()
    }

    pub fn add(&mut self, profile_id: &str, domain: &str, username: &str, password_enc: &str, title: &str, now: i64) -> String {
        // replace an existing (domain, username) pair like the Android upsert
        if let Some(existing) = self
            .items
            .iter_mut()
            .find(|c| c.domain == domain && c.username == username)
        {
            existing.password_enc = password_enc.to_string();
            existing.updated_at = now;
            return existing.id.clone();
        }
        let id = uuid::Uuid::new_v4().to_string();
        self.items.push(SavedCredential {
            id: id.clone(),
            profile_id: profile_id.to_string(),
            domain: domain.to_string(),
            username: username.to_string(),
            password_enc: password_enc.to_string(),
            title: title.to_string(),
            created_at: now,
            updated_at: now,
        });
        id
    }

    pub fn remove(&mut self, id: &str) -> bool {
        let before = self.items.len();
        self.items.retain(|c| c.id != id);
        before != self.items.len()
    }

    /// CSV export (name,url,username,password — the Bitwarden column order
    /// the Android PasswordCsv used).
    pub fn export_csv(&self, passphrase: &str) -> crate::CoreResult<String> {
        let mut out = String::from("name,url,username,password\n");
        for c in &self.items {
            let pw = crate::vaultcrypto::open_string(&c.password_enc, passphrase)?;
            out.push_str(&format!(
                "\"{}\",\"https://{}\",\"{}\",\"{}\"\n",
                c.title.replace('"', "\"\""),
                c.domain.replace('"', "\"\""),
                c.username.replace('"', "\"\""),
                pw.replace('"', "\"\"")
            ));
        }
        Ok(out)
    }

    /// CSV import with merge: (domain, username) pairs already present are
    /// counted as skipped, not duplicated.
    pub fn import_csv(&mut self, profile_id: &str, csv: &str, passphrase: &str, now: i64) -> crate::CoreResult<(usize, usize)> {
        let mut imported = 0;
        let mut skipped = 0;
        let mut lines = csv.lines();
        let _header = lines.next();
        for line in lines {
            if line.trim().is_empty() {
                continue;
            }
            let fields = split_csv_line(line);
            if fields.len() < 4 {
                continue;
            }
            let (title, url, username, password) =
                (&fields[0], &fields[1], &fields[2], &fields[3]);
            let domain = canonical_host(url);
            if domain.is_empty() || username.is_empty() {
                continue;
            }
            if self.items.iter().any(|c| c.domain == domain && c.username == *username) {
                skipped += 1;
                continue;
            }
            let enc = crate::vaultcrypto::seal_string(password, passphrase)?;
            self.add(profile_id, &domain, username, &enc, title, now);
            imported += 1;
        }
        Ok((imported, skipped))
    }
}

fn split_csv_line(line: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut cur = String::new();
    let mut in_quotes = false;
    let mut chars = line.chars().peekable();
    while let Some(c) = chars.next() {
        if in_quotes {
            if c == '"' {
                if chars.peek() == Some(&'"') {
                    cur.push('"');
                    chars.next();
                } else {
                    in_quotes = false;
                }
            } else {
                cur.push(c);
            }
        } else if c == '"' {
            in_quotes = true;
        } else if c == ',' {
            out.push(cur.clone());
            cur.clear();
        } else {
            cur.push(c);
        }
    }
    out.push(cur);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(tag: &str) -> std::path::PathBuf {
        let p = std::env::temp_dir().join(format!("rb-cred-{tag}-{}.jsonl", std::process::id()));
        let _ = std::fs::remove_file(&p);
        p
    }

    #[test]
    fn canonical_host_forms() {
        assert_eq!(canonical_host("https://Login.Example.com/path"), "login.example.com");
        assert_eq!(canonical_host("http://user:pass@example.com:8080/x"), "example.com");
        assert_eq!(canonical_host("example.com."), "example.com");
        assert_eq!(canonical_host("EXAMPLE.COM"), "example.com");
    }

    #[test]
    fn domain_matching_is_parent_aware() {
        assert!(domain_matches("example.com", "login.example.com"));
        assert!(domain_matches("example.com", "example.com"));
        assert!(!domain_matches("example.com", "badexample.com"));
        assert!(!domain_matches("example.com", "example.org"));
    }

    #[test]
    fn generator_policy() {
        for &(len, sym) in &[(16usize, true), (12, false), (32, true), (64, false), (5, false)] {
            let pw = generate_password(len, sym);
            assert!(pw.len() >= 12 && pw.len() <= 64);
            assert!(pw.chars().any(|c| c.is_ascii_lowercase()));
            assert!(pw.chars().any(|c| c.is_ascii_uppercase()));
            assert!(pw.chars().any(|c| c.is_ascii_digit()));
            if !sym {
                assert!(!pw.chars().any(|c| "!@#$%^&*()-_=+[]{}:,.?/".contains(c)));
            }
        }
    }

    #[test]
    fn store_upsert_and_roundtrip() {
        let p = tmp("rt");
        let mut s = CredentialStore::default();
        let enc = crate::vaultcrypto::seal_string("pw1", "master").unwrap();
        s.add("prof", "example.com", "alice", &enc, "Example", 1000);
        let enc2 = crate::vaultcrypto::seal_string("pw2", "master").unwrap();
        s.add("prof", "example.com", "alice", &enc2, "Example", 2000);
        assert_eq!(s.items.len(), 1); // upsert, not duplicate
        s.add("prof", "example.com", "bob", &enc2, "Example", 2000);
        assert_eq!(s.items.len(), 2);
        s.save(&p).unwrap();
        let back = CredentialStore::load(&p);
        assert_eq!(back.items.len(), 2);
        assert_eq!(back.for_host("login.example.com").len(), 2);
        assert_eq!(back.for_host("other.org").len(), 0);
        let _ = std::fs::remove_file(&p);
    }

    #[test]
    fn csv_roundtrip_with_merge() {
        let p = tmp("csv");
        let mut s = CredentialStore::default();
        let (imp, skip) = s.import_csv(
            "prof",
            "name,url,username,password\nA,https://a.com/,alice,pw1\nB,https://b.com/,bob,pw2\n",
            "master",
            1000,
        )
        .unwrap();
        assert_eq!((imp, skip), (2, 0));
        // re-import same rows → all skipped
        let (imp2, skip2) = s.import_csv(
            "prof",
            "name,url,username,password\nA,https://a.com/,alice,pw1\n",
            "master",
            2000,
        )
        .unwrap();
        assert_eq!((imp2, skip2), (0, 1));
        let csv = s.export_csv("master").unwrap();
        assert!(csv.contains("alice"));
        assert!(csv.contains("pw1"));
        assert!(csv.contains("https://a.com"));
        let _ = std::fs::remove_file(&p);
    }
}
