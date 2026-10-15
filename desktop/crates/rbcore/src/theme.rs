//! Theme system — the 18 built-in palettes plus the per-profile applied
//! theme snapshot (`theme_json` = `{"id":"<id>"}` in profiles.jsonl).

mod data {
    include!("theme_data.rs");
}

pub use data::{Palette, ThemeData, THEMES};

/// Token names for the 14 palette slots, in order.
pub const TOKENS: &[&str] = &[
    "background", "surface", "surfaceAlt", "primary", "secondary",
    "textPrimary", "textSecondary", "addressBar", "tabBar", "navBar",
    "button", "border", "icon", "selection",
];

pub fn theme(id: &str) -> Option<&'static ThemeData> {
    THEMES.iter().find(|t| t.id == id)
}

pub fn default_theme() -> &'static ThemeData {
    theme("obsidian").unwrap()
}

/// Palette for a mode: AMOLED forces the true-black dark chrome.
pub fn palette_for(t: &ThemeData, mode: &str) -> Palette {
    match mode {
        "light" => t.light,
        "amoled" => {
            let mut p = t.dark;
            p.0[0] = 0xFF000000;
            p.0[1] = 0xFF000000;
            p.0[2] = 0xFF0A0A0A;
            p
        }
        _ => t.dark,
    }
}

/// The applied-theme snapshot stored in `profiles.jsonl`.
pub fn snapshot_json(id: &str) -> String {
    format!("{{\"id\":\"{id}\"}}")
}

pub fn snapshot_id(theme_json: &str) -> Option<String> {
    let v: serde_json::Value = serde_json::from_str(theme_json).ok()?;
    v.get("id").and_then(|x| x.as_str()).map(|s| s.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn eighteen_themes_and_defaults() {
        assert_eq!(THEMES.len(), 18);
        assert_eq!(default_theme().id, "obsidian");
        for id in ["arctic", "ocean", "emerald", "midnight", "aurora", "sunset",
                   "cyber", "royal", "sakura", "forest", "aqua", "crimson",
                   "golden", "slate", "lavender", "coffee", "rose"] {
            assert!(theme(id).is_some(), "missing {id}");
        }
    }

    #[test]
    fn obsidian_matches_the_android_palette() {
        let t = default_theme();
        assert_eq!(t.dark.0[0], 0xFF0B0B0F);
        assert_eq!(t.dark.0[3], 0xFFA78BFA);
        // derived tokens: button = primary, icon = textSecondary
        assert_eq!(t.dark.0[10], t.dark.0[3]);
        assert_eq!(t.dark.0[12], t.dark.0[6]);
        // selection = primary at 28 % alpha
        assert_eq!(t.dark.0[13], 0x47A78BFA);
        assert_eq!(t.light.0[0], 0xFFFAFAFC);
    }

    #[test]
    fn amoled_forces_true_black() {
        let t = default_theme();
        let p = palette_for(t, "amoled");
        assert_eq!(p.0[0], 0xFF000000);
        assert_eq!(p.0[1], 0xFF000000);
    }

    #[test]
    fn snapshot_roundtrip() {
        assert_eq!(snapshot_json("ocean"), "{\"id\":\"ocean\"}");
        assert_eq!(snapshot_id("{\"id\":\"ocean\"}"), Some("ocean".into()));
        assert_eq!(snapshot_id(""), None);
    }
}
