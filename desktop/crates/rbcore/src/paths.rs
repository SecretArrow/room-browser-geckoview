//! Data-directory layout — port of `rb_paths.c` + the profile subtree of
//! `rb_profile.c`.
//!
//! * Windows: `%APPDATA%\RoomBrowser`
//! * Linux:   `$HOME/.config/RoomBrowser`
//!
//! Paths always join with `/` (even on Windows) so stored paths stay
//! interchangeable across editions, exactly like the C core.

use std::path::{Path, PathBuf};

pub fn data_dir() -> PathBuf {
    if cfg!(target_os = "windows") {
        let appdata = std::env::var("APPDATA").unwrap_or_else(|_| ".".into());
        PathBuf::from(appdata).join("RoomBrowser")
    } else {
        let home = std::env::var("HOME").unwrap_or_else(|_| ".".into());
        PathBuf::from(home).join(".config").join("RoomBrowser")
    }
}

/// `rb_paths_join`: always `/`, never the platform separator.
pub fn join(a: &Path, b: &str) -> PathBuf {
    let mut s = a.to_string_lossy().into_owned();
    if !s.is_empty() && !s.ends_with('/') {
        s.push('/');
    }
    s.push_str(b);
    PathBuf::from(s)
}

/// Profile subtree: `profiles/profile_<uuid>/{metadata, browser_data, cache,
/// downloads, settings}`. The uuid is the 36-char lowercase id, NOT the name
/// and NOT the dash-stripped suffix (the dash-stripped form is only used for
/// the WebView2 user-data folder, where Windows path rules make it matter).
pub fn profile_dir(data: &Path, profile_id: &str) -> PathBuf {
    join(data, &format!("profiles/profile_{profile_id}"))
}

pub fn profile_browser_data(data: &Path, profile_id: &str) -> PathBuf {
    join(&profile_dir(data, profile_id), "browser_data")
}

pub fn profile_cache(data: &Path, profile_id: &str) -> PathBuf {
    join(&profile_dir(data, profile_id), "cache")
}

pub fn profile_downloads(data: &Path, profile_id: &str) -> PathBuf {
    join(&profile_dir(data, profile_id), "downloads")
}

pub fn profile_settings(data: &Path, profile_id: &str) -> PathBuf {
    join(&profile_dir(data, profile_id), "settings")
}

/// Recursively creates the whole profile subtree (and parents). Missing
/// pieces are created lazily by the stores; the frontends call this on
/// profile creation/switch like the C code did.
pub fn ensure_profile_tree(data: &Path, profile_id: &str) -> std::io::Result<()> {
    for d in [
        profile_browser_data(data, profile_id),
        profile_cache(data, profile_id),
        profile_downloads(data, profile_id),
        profile_settings(data, profile_id),
    ] {
        std::fs::create_dir_all(d)?;
    }
    Ok(())
}

/// History / bookmarks / settings / profiles / downloads live at the root
/// and are shared across profiles (rows scope themselves by profile where
/// the C edition scoped them).
pub fn history_path(data: &Path) -> PathBuf {
    join(data, "history.jsonl")
}
pub fn bookmarks_path(data: &Path) -> PathBuf {
    join(data, "bookmarks.jsonl")
}
pub fn settings_path(data: &Path) -> PathBuf {
    join(data, "settings.txt")
}
pub fn profiles_path(data: &Path) -> PathBuf {
    join(data, "profiles.jsonl")
}
pub fn downloads_path(data: &Path) -> PathBuf {
    join(data, "downloads.jsonl")
}

/// Notes / TOTP: the Windows edition kept them per-profile under
/// `settings/`, the GTK edition at the data-dir root. Reads check the
/// per-profile file FIRST (the richer interpretation) and fall back to the
/// root file; writes always target the per-profile path so both editions
/// converge on one documented location.
pub fn notes_paths(data: &Path, profile_id: &str) -> Vec<PathBuf> {
    vec![
        join(&profile_settings(data, profile_id), "notes.jsonl"),
        join(data, "notes.jsonl"),
    ]
}
pub fn totp_paths(data: &Path, profile_id: &str) -> Vec<PathBuf> {
    vec![
        join(&profile_settings(data, profile_id), "totp.jsonl"),
        join(data, "totp.jsonl"),
    ]
}
/// The path the Rust edition writes TOTP accounts to (per-profile).
pub fn totp_path_for_rw(data: &Path, profile_id: &str) -> PathBuf {
    join(&profile_settings(data, profile_id), "totp.jsonl")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn join_always_uses_slashes() {
        let p = join(Path::new("/base"), "sub/file.txt");
        assert_eq!(p.to_string_lossy(), "/base/sub/file.txt");
        let p2 = join(Path::new("/base/"), "x");
        assert_eq!(p2.to_string_lossy(), "/base/x");
    }

    #[test]
    fn profile_tree_shape() {
        let d = profile_dir(Path::new("/data"), "01234567-89ab-cdef-0123-456789abcdef");
        assert_eq!(
            d.to_string_lossy(),
            "/data/profiles/profile_01234567-89ab-cdef-0123-456789abcdef"
        );
    }
}
