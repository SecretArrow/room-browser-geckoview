# Room Browser (Android)

> This directory holds the **Android app** of the Room Browser monorepo.
> The desktop (Windows/Ubuntu) edition lives in [`../desktop/`](../desktop/);
> repo-wide CI/CD lives in [`../.github/workflows/`](../.github/workflows/).

**One Android browser, multiple completely isolated profiles.**

Room Browser is a privacy-focused Android web browser built around a
desktop-style **profile system**: every profile behaves like an independent
browser installation with its own cookies, storage, history, bookmarks,
downloads, permissions, search engine, user-agent and privacy settings.

```
Personal   →  4f4e…uuid  →  own cookie jar / storage / history
Work       →  92ac…uuid  →  own cookie jar / storage / history
Research   →  1b7d…uuid  →  own cookie jar / storage / history
```

## Feature highlights

- **Real profile isolation** — each profile binds the browser engine to its
  own WebView data directory; switching profiles restarts the engine process.
  No shared cookie jar, no shared localStorage/IndexedDB/cache/service workers.
- **Profile manager** — create / open / edit / duplicate / delete / rename /
  re-style / lock (biometric) / set-default / reset / export & import settings.
- **Privacy shields** — ad, tracker, cross-site tracker, popup and malicious-site
  blocking (bundled offline blocklist — never phones home), HTTPS upgrades,
  third-party-cookie control, per-site overrides. Ad/tracker/cross-site/popup
  shields are **off by default** (compatibility-first); enable per profile.
- **Privacy dashboard** — statistics recorded exclusively from real blocking events.
- **Tabs** — grid/list, private tabs, groups, pin, move, duplicate, reopen closed,
  per-profile persistence and lazy restore.
- **Bookmarks / History / Downloads** — fully profile-scoped; the download
  manager pauses, resumes, retries and cancels from either the list or the
  progress notification, copies the source link, and shows per-item details
  (size on disk vs. total, percentage, live speed and ETA, MIME type, the
  saved path, timestamps). Resume is HTTP-Range and verified against the
  server's `Content-Range` — see "Resume correctness" below. Storage is
  scoped-correct throughout: MediaStore on API 29+, the legacy public
  Downloads directory on API 28.
- **DNS privacy** — per-profile and global DoH/DoT configuration for app
  connections (see honest limitations below).
- **Device identity manager** — every profile presents a real Android handset
  from the bundled 1021-device catalogue (UA, client hints, platform version,
  memory, cores, GPU strings); presets and a custom UA remain available, and
  choosing one steps the device aside so a profile never carries two
  identities. See SECURITY.md for exactly what is and is not presented.
- **Profile Network Conflict Protection** — optional, informational warning when
  a profile is opened from a public IP previously associated with another profile.
- **Omnibox intelligence** — URL/host/IP/localhost/file-URI detection, search,
  optional search suggestions, QR scan, voice input.
- **Reader mode, find-in-page, translate, desktop mode, save page as PDF,
  add-to-home-screen, share sheets**.
- **AI Agents (autonomous browsing)** — Room Agent drives the real browser
  for you: navigate, read pages, click, fill forms and manage tabs while
  you watch. Chat panel with streaming answers, tool-step cards, session
  history and Allow/Deny action approvals — and an optional **local
  decision gate** that asks an Ollama decision model (`nimble`, `tev1`)
  whether each action is routine, so Confirm actions only interrupts you
  when it should. Every prompt also offers **Always allow** (YOLO), which
  stops the asking entirely — a deliberate, loudly-announced removal of
  both checks rather than a quiet setting. The composer supports
  **file attachments** (attach icon next to "Include page", which turns
  green with a check icon when the page is included) — text files
  are inlined into the prompt so the agent can work with them. **Social automation tools**
  (`auto_like`, `auto_repost`, `auto_reply`, `auto_post`) complete
  like/repost/reply/post tasks on any social feed, and turns **keep
  running in the background** (foreground service + wake lock, with a
  progress notification and Stop action) when you leave the app or turn
  the screen off. Any OpenAI-compatible provider
  works (Z.ai, OpenAI, OpenRouter, Groq, DeepSeek, Mistral, Together,
  Ollama, LM Studio or custom) with **manual provider input** and
  **model lists fetched live from the provider's `/models` endpoint** — or
  point it at your own **OpenCode server** (`opencode serve`, LAN/VPN only)
  and pick a model from its live `/provider` list.
  Provider management, agent settings and chat history run as **dedicated
  activities** (their own windows with correct insets and keyboard
  handling), and the floating agent button is **hidden by default** —
  toggle it from Browser Settings → AI Agent → *Show AI Agent button*.
  Every chat message has a **copy icon** — tap it to put a previously sent
  prompt or a finished answer back on the clipboard, ready to paste into the
  composer and re-process. API keys are encrypted with AndroidKeyStore.
  See `docs/AI_AGENTS.md`.
- **Developer Tools** — a docked inspector over the live page, opened from the
  Page Actions menu (or **F12** / **Ctrl+Shift+I** on an external keyboard).
  It states up front, per edition, exactly what its engine can and cannot
  inspect — "this engine cannot" (carrying the engine's own reason) is kept
  apart from "not built yet" (carrying only what the capability would be) —
  with a **"What this cannot do, and why"** page carrying the same statement
  per edition plus the limits that hold on both.
  What is served is honest and live: a **Console** feed (the page's own
  `console.*`, `onerror` and `unhandledrejection` — GeckoView exposes no engine
  console callback, so this is the page's own output only) and a **Network**
  feed (each request's method, URL and frame, plus the response status,
  completion and failure) with URL credentials masked before they reach a
  buffer. The panel composes above the engine view, so it is never part of a
  page screenshot, and it is **unavailable in private tabs**. See `SECURITY.md`
  for the security posture and the limits it states rather than hides.
- **Local AI (Ollama)** — menu khusus untuk AI di perangkat sendiri:
  kelola model di server Ollama (Termux di ponsel ini atau PC di LAN) —
  koneksi + status server, katalog 19 preset model terbaik untuk ponsel
  (4 tier berdasarkan RAM), tombol **Find new models** yang me-refresh
  **library live ollama.com** untuk menemukan famili model baru yang cocok
  untuk ponsel (badge ukuran jadi tombol Install, famili embedding
  disembunyikan), unduh model dengan **pause/resume** (cache layer di sisi
  server), **tuning GPU/CPU/konteks/keep-alive** yang diterapkan ke chat
  protokol Ollama natif, serta **import/export setup** sebagai manifest
  JSON kecil. Plus sejak task 13: **mesin on-device tertanam
  (tanpa Termux)** — llama.cpp berjalan di dalam aplikasi di CPU ponsel;
  bobot model `.gguf` diimpor/diekspor bebas (SAF) atau diunduh per-URL
  dengan Range-resume, dan tombol **Try** membuktikan stack natifnya
  langsung di layar. Kartu provider **Select model** kini 3 baris (Nama → Model →
  Base URL) agar tidak tumpang tindih. Semua trafik lokal — tanpa cloud,
  tanpa telemetry; model TIDAK dibundel di APK (Room Browser adalah klien
  manajemen). Entry: AI Agent Settings → *Local AI (Ollama)* atau chip
  *Ollama native* / *On-device* di provider editor. Lihat `docs/LOCAL_AI.md`.
- **Biometric profile lock** — fingerprint/face or device credential.
- **Material 3 UI (2026 redesign)** — premium Brave-inspired-but-original
  design language: floating pill omnibox that owns the full toolbar width,
  glass bottom bar with tab-count badge, rounded cards everywhere,
  modern tab management, smooth animations, responsive layouts and
  excellent dark-mode support.
- **Working bottom-bar navigation** — Back / Forward / Refresh (doubles as
  Stop while a page loads) / Tabs / Share / More. Back/forward state is
  live-tracked via `doUpdateVisitedHistory` (SPA pushState included), so
  the buttons light up exactly when history exists. Every tab owns its OWN
  WebView engine — web history never leaks across tabs, switching tabs
  never reloads a still-live page, and a live-engine budget (LRU, max 4)
  keeps memory bounded; evicted tabs rebuild lazily on return.
- **Layout & interaction polish pass** — every activity audited and
  tidied: long model names/URLs/errors ellipsize instead of pushing rows
  off-screen, the agent model-picker sheet and profile-picker sheet now
  scroll instead of clipping, locked profiles are biometric-gated even
  when opened via external links, the profile icon picker shows all 12
  icons, the reader toolbar and bottom bar fit 320dp-class screens, the
  find-bar "next match" icon no longer looks like Close, "Shields" in the
  page-actions sheet actually opens the shields sheet, per-card tab
  Duplicate duplicates THAT tab, destructive actions (clear history,
  delete chat) ask for confirmation first, and touch targets meet the
  40dp floor everywhere (copy buttons, color swatches, theme menu).
- **Per-profile Theme System** — 18 hand-tuned built-in themes (Obsidian,
  Arctic, Ocean, Emerald, Midnight, Aurora, Sunset, Cyber, Royal, Sakura,
  Forest, Aqua, Crimson, Golden, Slate, Lavender, Coffee, Rose), each with
  light + dark palettes and Light/Dark/AMOLED/Auto modes. The **Theme
  Studio** (own activity) edits background/surface/accents/text/address
  bar/tab bar/navigation bar/button/border/icon/selection colors, gradient
  style + direction, corner radius, transparency, blur and contrast with a
  **live preview**, plus save/duplicate/rename/reset and JSON
  import/export. Every profile owns a FULL independent theme snapshot — see
  `docs/THEMES.md`.
- **Compatibility-first privacy defaults** — mixed content runs in
  compatibility mode, third-party cookies are allowed, JavaScript is on,
  HTTPS upgrades fall back to http automatically when the secure version is
  unreachable, and tag-manager domains are not blocked (they gate page
  render on many sites). Ad, tracker, cross-site-tracker and popup blocking
  are **OFF out of the box** (opt-in per profile in settings) so pages
  render exactly as their authors intended; malicious-site blocking and
  HTTPS-First-with-fallback stay on. Every NEW profile also automatically
  presents itself as a **different real Android device**, drawn from a
  bundled catalogue of 1021 handsets (Samsung, Google, Xiaomi, OnePlus,
  OPPO, vivo, realme, Motorola, Sony, ASUS, Nothing and more, 2022-2025),
  so two profiles never present the same fingerprint — change the device,
  or pick one no other profile is using, any time in Profile settings.
  Strict modes stay one toggle away in settings.
- **Proper system-UI integration** — edge-to-edge with explicit
  `WindowInsets` handling: the toolbar, omnibox, sheets and dialogs never
  overlap the 3-button navigation bar (Back / Home / Recents) or the status
  bar on any API level. The system **Back** button follows browser rules:
  exit fullscreen video → close reader/find bar → collapse the agent panel →
  return from sub-screens → walk web history → **ask before leaving** →
  background the app. A page with no back history left never kicks you out
  silently: an "Exit Room Browser?" confirmation offers Exit (engine and
  tabs stay alive), Back to start page, or Cancel. On the homepage, Back
  backgrounds the app instantly. JavaScript is NEVER disabled by default
  (platform-default-off is explicitly overridden from profile settings,
  whose own default is on — guarded by a unit test).

## Download resume correctness

A resumed download is only ever spliced onto bytes the server actually sent.
`DownloadPlanner` reads the response before a single byte is written, and the
part file is opened with the append decision already made — the previous
behaviour opened the file for truncation first and decided afterwards, so a
"resume" silently emptied the file it was continuing. Specifically:

- a `200` to a Range request means the server ignored the range and is
  sending the whole file, so the transfer restarts from zero rather than
  appending a second copy;
- a `206` whose `Content-Range` start is not the offset requested is
  refetched, never appended — the bytes on disk and the bytes in the
  response would not join;
- a `206` with a missing or unparsable `Content-Range` is refetched rather
  than trusted, because the append offset cannot be known without it.

The rules live in `core/domain` (`DownloadPlanner`) with a dedicated test per
rule, so the corruption cannot return unnoticed, and the total is taken from
`Content-Range` when the server states it — `Content-Length` of a partial
response is the remainder, not the file.

## Honest limitations (no false claims)

- WebView page-load DNS uses the Android OS resolver; DoH/DoT in Room Browser
  protects the app's own connections (public-IP checks, suggestions, downloads).
- A normal third-party app cannot fully control WebRTC IP handling; camera and
  microphone remain under explicit permission control.
- Private tabs share the profile's cookie jar while open; session cookies are
  purged when private tabs close (documented in `PRIVACY.md`).
- Developer Tools is not a full desktop inspector. It shows only what this
  edition's engine can actually serve, and each panel names what it cannot:
  GeckoView's network feed is a WebExtension observer, not a debugger, so it
  reports each request's method, URL and frame with the response status,
  completion and failure but never a header name or value — the observers are
  registered without requesting the header blocks. **Heap snapshots are not
  shipped** on either edition: a snapshot of a phone-sized page is hundreds of
  megabytes. Its audit is **not Google Lighthouse** — it is an in-page audit
  built only from signals the page can observe, with no score and no invented
  category numbers.
- **Room Browser is not an anonymity tool.**

See `PROFILE_ISOLATION.md`, `SECURITY.md` and `PRIVACY.md` for full details.

## Repository layout (relative to `android/`)

```
android/
├── app/                 Android application (Compose UI, browser engine, Room DB)
├── core/domain/         Pure-Kotlin domain logic (JVM-testable, no Android deps)
├── tools/               profile-test-site + GitHub-secret helper scripts
├── docs/                feature docs (AI agents, local AI, themes)
└── BUILD.md RELEASE.md TESTING.md …
```

The CI/CD pipeline (quality gates + signed per-ABI release builds) lives at the
repo root: `../.github/workflows/ci.yml`.

## Building

Requirements: JDK 17+, Android SDK (platform 35, build-tools 35.0.0).

```bash
./gradlew assembleDebug          # debug APKs (all ABIs)
./gradlew testDebugUnitTest      # JVM unit tests
./gradlew lint                   # static analysis
./gradlew assembleRelease        # release APKs (signed via env vars, see BUILD.md)
./gradlew bundleRelease          # AAB
```

Full instructions: `BUILD.md` · release artifacts & signing: `RELEASE.md` ·
testing guide: `TESTING.md`.

## License

Provided as-is for this project. All bundled filter lists are compiled from
publicly documented blocklist entries; no Brave/Google proprietary assets,
branding or code are included.
