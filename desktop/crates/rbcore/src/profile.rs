//! Profile registry — port of `rb_profile.c` (`profiles.jsonl`).
//!
//! Exact writer field order (the byte-compatibility contract):
//! `id, name, icon, color_argb, is_locked, is_default, created_at,
//! last_active_at, theme_json, settings`.
//! Load repairs: drop duplicate ids and duplicate case-insensitive names,
//! enforce exactly one default, migrate legacy bare `home`/`javascript`
//! keys from the old global settings.txt into the first profile.

use crate::jsonl::{load_lines, save_lines, str_field};
use crate::prefs::ProfilePrefs;
use crate::{now_ms, CoreResult};
use serde_json::{json, Value};
use std::path::Path;

pub const LINE_CAP: usize = 16 * 1024;

#[derive(Debug, Clone)]
pub struct Profile {
    pub id: String,
    pub name: String,
    pub icon: String,
    pub color_argb: i64,
    pub is_locked: bool,
    pub is_default: bool,
    pub created_at: i64,
    pub last_active_at: i64,
    pub theme_json: String,
    pub settings: ProfilePrefs,
}

impl Default for Profile {
    fn default() -> Self {
        Profile {
            id: new_id(),
            name: "Personal".into(),
            icon: "👤".into(),
            color_argb: 4285020836,
            is_locked: false,
            is_default: false,
            created_at: now_ms(),
            last_active_at: now_ms(),
            theme_json: String::new(),
            settings: ProfilePrefs::default(),
        }
    }
}

/// Fresh lowercase UUID v4 (36 chars) — same identity rules as Android.
pub fn new_id() -> String {
    uuid::Uuid::new_v4().to_string()
}

/// `rb_profile_safe_suffix`: lowercase uuid with dashes stripped — the
/// WebView2 data-directory suffix (32 hex chars).
pub fn safe_suffix(id: &str) -> String {
    id.chars().filter(|c| *c != '-').flat_map(|c| c.to_lowercase()).collect()
}

impl Profile {
    pub fn to_json(&self) -> Value {
        // Settings: every value is a JSON string, keys sorted (BTreeMap).
        let settings: serde_json::Map<String, Value> = self
            .settings
            .map
            .iter()
            .map(|(k, v)| (k.clone(), Value::String(v.clone())))
            .collect();
        json!({
            "id": self.id,
            "name": self.name,
            "icon": self.icon,
            "color_argb": self.color_argb,
            "is_locked": if self.is_locked { 1 } else { 0 },
            "is_default": if self.is_default { 1 } else { 0 },
            "created_at": self.created_at,
            "last_active_at": self.last_active_at,
            "theme_json": self.theme_json,
            "settings": settings,
        })
    }

    fn from_json(v: &Value) -> Option<Profile> {
        let id = str_field(v, "id");
        if id.is_empty() {
            return None;
        }
        let settings = v
            .get("settings")
            .and_then(|s| s.as_object())
            .map(|obj| {
                ProfilePrefs::from_map(
                    obj.iter()
                        .filter_map(|(k, v)| v.as_str().map(|s| (k.clone(), s.to_string())))
                        .collect(),
                )
            })
            .unwrap_or_default();
        Some(Profile {
            id,
            name: str_field(v, "name"),
            icon: str_field(v, "icon"),
            color_argb: crate::jsonl::int_field(v, "color_argb"),
            is_locked: crate::jsonl::bool_field(v, "is_locked"),
            is_default: crate::jsonl::bool_field(v, "is_default"),
            created_at: crate::jsonl::int_field(v, "created_at"),
            last_active_at: crate::jsonl::int_field(v, "last_active_at"),
            theme_json: str_field(v, "theme_json"),
            settings,
        })
    }
}

#[derive(Debug, Clone, Default)]
pub struct ProfileRegistry {
    pub profiles: Vec<Profile>,
    /// The profile-switch protocol keeps this id on disk separately from the
    /// running state; the C core stored it in memory + used profile ordering.
    pub active: Option<String>,
}

impl ProfileRegistry {
    pub fn load(data: &Path) -> Self {
        let rows = load_lines(&crate::paths::profiles_path(data), LINE_CAP);
        let mut reg = ProfileRegistry::default();
        let mut seen_ids: Vec<String> = Vec::new();
        let mut seen_names: Vec<String> = Vec::new();
        for v in &rows {
            let Some(p) = Profile::from_json(v) else { continue };
            if p.name.is_empty() || p.name.len() > 40 {
                continue;
            }
            if seen_ids.iter().any(|x| x == &p.id) {
                continue;
            }
            let lname = p.name.to_lowercase();
            if seen_names.iter().any(|x| x == &lname) {
                continue;
            }
            seen_ids.push(p.id.clone());
            seen_names.push(lname);
            reg.profiles.push(p);
        }
        reg.repair_default();
        if reg.profiles.is_empty() {
            // First run: "Personal", default, random device assigned by the
            // frontend when the catalogue is available.
            let mut p = Profile::default();
            p.is_default = true;
            reg.profiles.push(p);
        }
        reg.active = reg.profiles.first().map(|p| p.id.clone());
        reg
    }

    /// "Exactly one default": first profile becomes default if none;
    /// extras demoted.
    pub fn repair_default(&mut self) {
        let has_default = self.profiles.iter().any(|p| p.is_default);
        if !has_default {
            if let Some(first) = self.profiles.first_mut() {
                first.is_default = true;
            }
        } else {
            let mut seen = false;
            for p in self.profiles.iter_mut() {
                if p.is_default {
                    if seen {
                        p.is_default = false;
                    } else {
                        seen = true;
                    }
                }
            }
        }
    }

    pub fn save(&self, data: &Path) -> CoreResult<()> {
        let rows: Vec<Value> = self.profiles.iter().map(|p| p.to_json()).collect();
        save_lines(&crate::paths::profiles_path(data), &rows)
            .map_err(|e| format!("profiles save failed: {e}"))
    }

    pub fn get(&self, id: &str) -> Option<&Profile> {
        self.profiles.iter().find(|p| p.id == id)
    }
    pub fn get_mut(&mut self, id: &str) -> Option<&mut Profile> {
        self.profiles.iter_mut().find(|p| p.id == id)
    }
    pub fn active_profile(&self) -> Option<&Profile> {
        self.active.as_ref().and_then(|id| self.get(id))
    }

    /// Adds a profile with a unique trimmed name (≤40 bytes, unique
    /// case-insensitively). Returns the new id.
    pub fn add(&mut self, name: &str, icon: &str) -> CoreResult<String> {
        let name = name.trim();
        if name.is_empty() || name.len() > 40 {
            return Err("profile name must be 1-40 bytes".into());
        }
        let lname = name.to_lowercase();
        if self.profiles.iter().any(|p| p.name.to_lowercase() == lname) {
            return Err(format!("a profile named {name:?} already exists"));
        }
        let mut p = Profile::default();
        p.name = name.to_string();
        if !icon.is_empty() {
            p.icon = icon.to_string();
        }
        let id = p.id.clone();
        self.profiles.push(p);
        Ok(id)
    }

    /// The `theme_json` repair pass: a theme id mistakenly stored in the
    /// `theme` *setting* moves into the snapshot and the setting resets to
    /// `"system"`.
    pub fn repair_theme_setting(&mut self) {
        for p in self.profiles.iter_mut() {
            let t = p.settings.get("theme");
            if !p.theme_json.is_empty() {
                continue;
            }
            if t != "system" && t != "light" && t != "dark" && t != "amoled" && !t.is_empty() {
                p.theme_json = format!("{{\"id\":\"{}\"}}", t);
                p.settings.set("theme", "system");
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmpdir(tag: &str) -> std::path::PathBuf {
        let d = std::env::temp_dir().join(format!("rb-profile-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn first_run_creates_personal_default() {
        let d = tmpdir("first");
        let reg = ProfileRegistry::load(&d);
        assert_eq!(reg.profiles.len(), 1);
        assert_eq!(reg.profiles[0].name, "Personal");
        assert!(reg.profiles[0].is_default);
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn writer_field_order_is_the_contract() {
        let p = Profile::default();
        let s = serde_json::to_string(&p.to_json()).unwrap();
        let expect_prefix = "{\"id\":\"";
        assert!(s.starts_with(expect_prefix));
        // Relative order of the keys after id:
        let idx = |k: &str| s.find(&format!("\"{k}\":")).unwrap();
        assert!(idx("name") < idx("icon"));
        assert!(idx("icon") < idx("color_argb"));
        assert!(idx("color_argb") < idx("is_locked"));
        assert!(idx("is_locked") < idx("is_default"));
        assert!(idx("is_default") < idx("created_at"));
        assert!(idx("created_at") < idx("last_active_at"));
        assert!(idx("last_active_at") < idx("theme_json"));
        assert!(idx("theme_json") < idx("settings"));
    }

    #[test]
    fn roundtrip_and_repairs() {
        let d = tmpdir("rt");
        let mut reg = ProfileRegistry::load(&d);
        let a = reg.add("Work", "💼").unwrap();
        reg.save(&d).unwrap();
        let mut back = ProfileRegistry::load(&d);
        assert_eq!(back.profiles.len(), 2);
        assert!(back.get(&a).is_some());
        // duplicate id + duplicate name rows dropped on load
        let mut dup = back.profiles[0].clone();
        dup.name = back.profiles[1].name.clone();
        let mut rows = vec![back.profiles[0].to_json(), back.profiles[1].to_json(), dup.to_json()];
        rows.push(back.profiles[1].to_json());
        save_lines(&crate::paths::profiles_path(&d), &rows).unwrap();
        let dedup = ProfileRegistry::load(&d);
        assert_eq!(dedup.profiles.len(), 2);
        // exactly one default
        assert_eq!(dedup.profiles.iter().filter(|p| p.is_default).count(), 1);
        // theme repair
        let mut fix = dedup;
        let pid = fix.profiles[0].id.clone();
        fix.profiles[0].settings.set("theme", "ocean");
        fix.repair_theme_setting();
        assert_eq!(fix.get(&pid).unwrap().theme_json, "{\"id\":\"ocean\"}");
        assert_eq!(fix.get(&pid).unwrap().settings.get("theme"), "system");
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn safe_suffix_matches_android() {
        assert_eq!(
            safe_suffix("01234567-89ab-cdef-0123-456789abcdef"),
            "0123456789abcdef0123456789abcdef"
        );
    }
}
