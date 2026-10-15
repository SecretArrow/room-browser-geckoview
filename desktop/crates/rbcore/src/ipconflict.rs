//! IP-conflict detection — port of `rb_ipconflict.c` (the Android
//! IpConflictDetector's association history + warning behaviour).

use crate::jsonl::{int_field, load_lines, save_lines, str_field};
use serde_json::{json, Value};
use std::path::Path;

pub const LINE_CAP: usize = 16 * 1024;

#[derive(Debug, Clone, PartialEq)]
pub struct IpAssociation {
    pub profile_id: String,
    pub ip: String,
    pub first_seen_at: i64,
    pub last_seen_at: i64,
}

#[derive(Debug, Clone)]
pub struct IpHistory {
    pub items: Vec<IpAssociation>,
}

impl IpHistory {
    pub fn load(path: &Path) -> IpHistory {
        let mut h = IpHistory { items: Vec::new() };
        for v in load_lines(path, LINE_CAP) {
            let ip = str_field(&v, "ip");
            let profile = str_field(&v, "profile_id");
            if ip.is_empty() || profile.is_empty() {
                continue;
            }
            h.items.push(IpAssociation {
                profile_id: profile,
                ip,
                first_seen_at: int_field(&v, "first_seen_at"),
                last_seen_at: int_field(&v, "last_seen_at"),
            });
        }
        h
    }

    pub fn save(&self, path: &Path) -> std::io::Result<()> {
        let rows: Vec<Value> = self
            .items
            .iter()
            .map(|a| {
                json!({
                    "first_seen_at": a.first_seen_at,
                    "last_seen_at": a.last_seen_at,
                    "profile_id": a.profile_id,
                    "ip": a.ip,
                })
            })
            .collect();
        save_lines(path, &rows)
    }

    /// Records (or refreshes) the profile↔IP association.
    pub fn observe(&mut self, profile_id: &str, ip: &str, now: i64) {
        if let Some(a) = self.items.iter_mut().find(|a| a.profile_id == profile_id && a.ip == ip) {
            a.last_seen_at = now;
        } else {
            self.items.push(IpAssociation {
                profile_id: profile_id.to_string(),
                ip: ip.to_string(),
                first_seen_at: now,
                last_seen_at: now,
            });
        }
    }

    /// A conflict is: this IP was last seen under a DIFFERENT profile.
    pub fn check(&self, profile_id: &str, ip: &str) -> Option<&IpAssociation> {
        self.items.iter().find(|a| a.ip == ip && a.profile_id != profile_id)
    }

    /// Retention purge (NetworkRetention; default 30 days).
    pub fn purge(&mut self, now: i64, retention_ms: i64) -> usize {
        let before = self.items.len();
        self.items.retain(|a| now - a.last_seen_at <= retention_ms);
        before - self.items.len()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WarningBehavior {
    AskEveryTime,
    OncePerNetwork,
    OncePerSession,
    DontWarn,
}

impl WarningBehavior {
    pub fn as_str(self) -> &'static str {
        match self {
            WarningBehavior::AskEveryTime => "ask_every_time",
            WarningBehavior::OncePerNetwork => "once_per_network",
            WarningBehavior::OncePerSession => "once_per_session",
            WarningBehavior::DontWarn => "dont_warn",
        }
    }
    pub fn parse(s: &str) -> WarningBehavior {
        match s {
            "once_per_network" => WarningBehavior::OncePerNetwork,
            "once_per_session" => WarningBehavior::OncePerSession,
            "dont_warn" => WarningBehavior::DontWarn,
            _ => WarningBehavior::AskEveryTime,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Severity {
    Informational,
    RequireConfirmation,
}

impl Severity {
    pub fn as_str(self) -> &'static str {
        match self {
            Severity::Informational => "informational",
            Severity::RequireConfirmation => "require_confirmation",
        }
    }
    pub fn parse(s: &str) -> Severity {
        match s {
            "require_confirmation" => Severity::RequireConfirmation,
            _ => Severity::Informational,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(tag: &str) -> std::path::PathBuf {
        let p = std::env::temp_dir().join(format!("rb-ip-{tag}-{}.jsonl", std::process::id()));
        let _ = std::fs::remove_file(&p);
        p
    }

    #[test]
    fn conflict_detection_and_retention() {
        let p = tmp("rt");
        let mut h = IpHistory::load(&p);
        h.observe("profile-a", "203.0.113.7", 1000);
        h.save(&p).unwrap();
        // same profile → no conflict
        assert!(h.check("profile-a", "203.0.113.7").is_none());
        // different profile on the same IP → conflict
        let c = h.check("profile-b", "203.0.113.7").unwrap();
        assert_eq!(c.profile_id, "profile-a");
        // refresh bumps last_seen
        h.observe("profile-a", "203.0.113.7", 2000);
        // retention purge
        assert_eq!(h.purge(2000 + 31 * 24 * 3600 * 1000, 30 * 24 * 3600 * 1000), 1);
        assert!(h.items.is_empty());
        let _ = std::fs::remove_file(&p);
    }

    #[test]
    fn enums_roundtrip() {
        assert_eq!(WarningBehavior::parse("ask_every_time"), WarningBehavior::AskEveryTime);
        assert_eq!(WarningBehavior::parse("dont_warn").as_str(), "dont_warn");
        assert_eq!(Severity::parse("require_confirmation"), Severity::RequireConfirmation);
    }
}
