# Room Browser

Privacy-first browser family by **Maragung** — one repository, two platforms.

| Edition | Path | Stack | CI artifacts |
|---|---|---|---|
| **Android** | [`android/`](android/) | Kotlin · Jetpack Compose · Room · on-device llama.cpp engine | signed per-ABI APKs + universal + AAB |
| **Windows / Ubuntu desktop** | [`desktop/`](desktop/) | **C11** — Win32 + WebView2 (Windows) · GTK3 + WebKitGTK (Ubuntu) | `RoomBrowser-<ver>-windows-x64.zip` · `roombrowser_<ver>_amd64.deb` · `RoomBrowser-<ver>-linux-x64.tar.gz` |

## Highlights

- **Android** — desktop-style multi-profile isolation (per-profile cookie jar,
  storage, history, permissions), privacy shields with a bundled offline
  blocklist, DoH, QR sharing, theme studio, a Developer Tools inspector that
  states per edition what its engine can and cannot inspect (and why), and an
  AI-agent layer that talks to
  Ollama/OpenCode endpoints **or runs llama.cpp fully on-device**. See
  [`android/README.md`](android/README.md).
- **Desktop** — a Brave-inspired dark browser chrome written in pure C: tab
  strip with close buttons, omnibox (type URLs or search terms), back /
  forward / reload / home, bookmarks, history and settings persisted to disk,
  and an embedded system web view per platform. JavaScript is **never disabled
  by default** on either edition. See [`desktop/README.md`](desktop/README.md).

## CI/CD (single pipeline, both editions)

`.github/workflows/ci.yml` runs on every push to `main` and on `v*` tags:

1. **Auto-fix** — Gradle `lintFix`; any automatic fixes are committed & re-run.
2. **Quality** — Android Lint, unit tests (domain + app), debug build.
3. **E2E** — instrumented tests on a real Android emulator.
4. **Desktop** — the C core's unit tests, then release builds for
   Windows x64 (MSVC + WebView2 loader, statically linked → standalone exe)
   and Ubuntu x64 (GTK3 + WebKitGTK → deb + tar.gz).
5. **Auto-release** — when everything is green: Android APKs/AAB **plus** the
   desktop zip/deb/tar.gz and `checksums.sha256`, published as a GitHub
   Release. Version numbering continues from the highest existing `v1.0.x` tag.

All builds happen on GitHub-hosted runners — nothing is ever built locally.

## Repository layout

```
android/        Android app (Gradle project: app/, core/, tools/, docs/)
desktop/        Desktop browser in C11 (src/core, src/win, src/gtk, scripts, assets)
.github/        CI/CD workflows
SECURITY.md     Responsible-disclosure policy
PRIVACY.md      What the app collects (nothing) and what can leave the device
```

## License

Provided as-is for this project. All bundled filter lists are compiled from
publicly documented blocklist entries; no Brave/Google proprietary assets,
branding or code are included. The desktop edition similarly implements a
Brave-*inspired* UI without any Brave code or assets.
