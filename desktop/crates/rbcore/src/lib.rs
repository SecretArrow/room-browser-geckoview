//! Room Browser desktop core — the Rust port of the domain layer.
//!
//! Everything in this crate is engine-independent and GUI-independent: it is
//! the same contract the Android edition implements in Kotlin
//! (`core/domain`, `core/wallet`, the agent layer) and the former C11 core
//! implemented in `src/core`. The Windows and Linux frontends embed it; the
//! tests exercise it headlessly.
//!
//! Data-format compatibility is a hard contract, not a nicety: every store
//! reads and writes the exact byte layout the C11 edition shipped in
//! v1.0.165/v1.0.166, so a user upgrading keeps their history, bookmarks,
//! notes, 2FA accounts, downloads and profiles untouched.

pub mod jsonl;
pub mod paths;
pub mod settings;
pub mod prefs;
pub mod profile;
pub mod switch;
pub mod theme;
pub mod search;
pub mod ua;
pub mod urlintel;
pub mod filters;
pub mod https;
pub mod dns;
pub mod ipconflict;
pub mod history;
pub mod bookmarks;
pub mod notes;
pub mod totp;
pub mod downloads;
pub mod tabs;
pub mod devices;
pub mod shims;
pub mod credentials;
pub mod vaultcrypto;
pub mod redaction;
pub mod qr;
pub mod wallet;
pub mod agent;
pub mod node;
pub mod devtools;
pub mod bridge;
// pub mod localai;

/// Version baked in by CI (`RB_VERSION` env at build time); the dev
/// placeholder keeps local `cargo check` builds honest.
pub fn version() -> &'static str {
    env!("RB_VERSION")
}

/// Milliseconds since the Unix epoch (the timestamp unit of every store,
/// matching the Android edition and the C11 core).
pub fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// The crate-wide error type: a plain human-readable message. It serialises
/// straight into the chrome IPC responses and keeps the core dependency-free.
pub type CoreResult<T> = Result<T, String>;

/// Writes `s` to stdout followed by a newline — the CLI surface
/// (`--version`, `--out`) uses the same path on both platforms.
pub fn println_out(s: &str) {
    use std::io::Write;
    let mut out = std::io::stdout().lock();
    let _ = writeln!(out, "{s}");
    let _ = out.flush();
}
