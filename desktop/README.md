# Room Browser — Desktop (Rust)

The desktop edition of Room Browser, rewritten in **Rust** from the former
C11 implementation. Same product goals: a privacy-first browser family by
**Maragung**, one codebase of shared domain logic, and one thin engine layer
per platform.

| Crate | Role |
|---|---|
| `crates/rbcore` | The whole domain core in pure Rust: profiles, tabs, history, bookmarks, notes, 2FA/TOTP, downloads, filters/shields, URL intelligence, DNS modes, IP-conflict, themes, devices, the password vault, the wallet (BIP39/BIP32/SLIP-0010, 8 chains, 42 bundled networks), the AI agent (58 tools, loop, gates, gateways, scheduled tasks), the Node runtime bridge, QR, redaction — fully unit-tested headlessly (140 tests). |
| `crates/rbchrome` | The browser chrome as one embedded HTML/CSS/JS application (tab strip, omnibox, menus, preferences, downloads, notes, 2FA, passwords, wallet, local AI, theme studio, profiles, DevTools, agent chat) — identical on both platforms. |
| `crates/rbwin` | Windows frontend: tao window + WebView2 (Chromium). One release exe (`RoomBrowser.exe`); per-profile user-data folders give true cookie/storage isolation. |
| `crates/rblinux` | Linux frontend: GTK3 + WebKitGTK 4.1. One binary (`roombrowser`); per-profile WebsiteDataManager for isolation. |

- License: **MIT** (see `LICENSE`).
- Project policy: **JavaScript is never disabled by default**.
- Default search: **DuckDuckGo** (omnibox heuristics are a byte-faithful port
  of the C `rb_url_decide`, which is itself the Android `UrlIntelligence`).

## Data compatibility

Every on-disk format the C11 edition shipped is read and written unchanged:
`profiles.jsonl` (exact writer field order), `history.jsonl`, `bookmarks.jsonl`
(`folder: null`), `downloads.jsonl` (status wire names), `notes.jsonl`
(per-profile first, root fallback), `totp.jsonl` and the `RB2FA1 ` encrypted
export blob (byte-exact PBKDF2/HMAC stream format), the session pair
`open_tabs`/`active_tab` inside profile settings, and the 18-theme palette
table. The device catalogue (1041 machines) and the bundled blocklist were
generated from the same sources as the Android edition; the C tables they
were ported from are gone, and the Rust tables are the source of truth.

## The agent

The desktop agent is the same 58-tool catalogue as Android, with the same
layered gating (plan mode → permissions → action budget → scheduled-run
refusals), the same loop contracts (`MAX_CONTINUE_NUDGES=2`, steps 1..200,
history 40, tool results capped 6000 chars) and the same Ollama
chat protocol. `node_run` / `node_packages` drive the system Node/npm with a
30-second budget and `--ignore-scripts` (lifecycle scripts never execute).

## Building

```sh
cargo test --manifest-path desktop/Cargo.toml -p rbcore -p rbchrome --lib
cargo build --manifest-path desktop/Cargo.toml --release -p rbwin    # Windows
cargo build --manifest-path desktop/Cargo.toml --release -p rblinux  # Linux
```

CI builds and releases everything (see the root README); nothing is ever
built locally.

## Regenerating generated data

The wallet snapshot tracks the Android chain tables and regenerates from
them at any time — run it after changing the Android wallet sources:

```sh
python3 desktop/scripts/gen-wallet-networks.py   # the 42-network wallet snapshot (from the Android sources)
```

The device catalogue and theme palettes were migrated once from the C11
edition's tables (`tools/gen-rust-data.py`, removed together with the C
sources it read); `devices_data.rs` and `theme_data.rs` are the source of
truth now.
