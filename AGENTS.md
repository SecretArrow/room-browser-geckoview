# AGENTS.md — Room Browser (GeckoView edition)

This repository is ONE of TWO sibling projects. Read this before touching anything.

| | This repo | Sibling repo |
|---|---|---|
| Directory | `/home/dev/room-browser/room-browser-geckoview` | `/home/dev/room-browser/room-browser-webview` |
| GitHub | `SecretArrow/room-browser-geckoview` | `SecretArrow/room-browser` |
| **Engine** | **Mozilla GeckoView** | **Android WebView** |
| Engine artifact | `org.mozilla.geckoview:geckoview` (bundled in the APK) | `android.webkit.WebView` + `androidx.webkit` (provided by the device) |

**Both projects are developed in parallel and both ship.** Neither is a scratch copy of
the other, and neither is deprecated. The owner is deliberately building one browser on
two different engines.

## The one rule that matters most

> Never edit the sibling repository from a session working in this one, and never copy a
> file between them without deciding, on purpose, whether the file is **shared** or
> **engine-specific**.

If you are asked to change this repo, change only this repo. If the change is not
engine-specific, say so and tell the owner it also belongs in the sibling — do not go and
apply it there yourself unless explicitly told to.

## Shared vs engine-specific

The long-term target is that the two repositories are **byte-identical outside the engine
module**, so a feature that has nothing to do with the engine can be applied to both by
copying files. The facade described below is what makes that true.

**Shared — must stay identical in both repos.** If you change one of these here, the same
change belongs in the sibling:

- `android/core/domain/` — pure Kotlin: profiles, devices, user agents, filters, agent
  protocol, credentials, URL intelligence.
- `android/core/wallet/` — chain adapters, HD keys, RPC transport. No engine types.
- `android/app/src/main/kotlin/com/roombrowser/wallet/` — wallet contract/engine/repo/UI.
- `android/app/src/main/kotlin/com/roombrowser/agent/` — the AI agent. It drives pages
  through the engine facade, never through a raw engine type.
- `android/app/src/main/kotlin/com/roombrowser/data/`, `theme/`, `qr/`, `ui/` (except the
  engine host), `localai/`.
- `desktop/` — the C desktop edition is engine-independent and identical in both.
- Everything under `android/app/src/main/assets/room_bridge/` that is page-side JavaScript
  with no engine API in it.

**Engine-specific — deliberately different. Do not "fix" one to match the other:**

- `android/engine/` — the entire facade *implementation*. This is the only place allowed
  to name the engine's own types.
- `android/app/src/main/kotlin/com/roombrowser/browser/engine/` — profile binding,
  settings application, storage wipe (the *shapes* match; the calls do not).
- `android/app/src/main/kotlin/com/roombrowser/browser/WebClients.kt` — request/chrome
  delegates. WebView uses `WebViewClient`/`WebChromeClient`; GeckoView uses
  `NavigationDelegate`/`ContentDelegate`/`PromptDelegate`/`PermissionDelegate`.
- `android/app/build.gradle.kts` — the engine dependency, ABI splits, native packaging.
- `android/gradle/libs.versions.toml` — `geckoview` here, `webkit` there.
- `android/app/src/main/AndroidManifest.xml` — `<queries>` (WebView provider probe) and
  `windowSoftInputMode` differ.
- Engine-shaped tests: `BrowserNavigationE2eTest`, `WalletE2eTest`, `ProfileIsolationTest`,
  the `TabsE2eTest` live-engine probe.
- `README.md`, `SECURITY.md` where they name the engine.

**The facade is the boundary.** `android/engine/` exposes app-owned interfaces
(`EngineSession`, `EngineHost`) that name **no** Mozilla type. `:app` depends on
`implementation(project(":engine"))`, so the engine's classes are not even on `:app`'s
compile classpath. A JVM guard test enforces that no `org.mozilla.geckoview` import exists
outside `:engine`; run it in the fast `quality` job, never discover it in e2e.

## Engine facts for THIS repo (verified, do not re-derive)

- Artifact id is `org.mozilla.geckoview:geckoview` — **no channel suffix**.
  `geckoview-release` does not exist; `geckoview-omni` is the variant with extra codecs.
- Repository: `https://maven.mozilla.org/maven2/`, declared with a group filter in
  `android/settings.gradle.kts` (the project uses `FAIL_ON_PROJECT_REPOS`).
- **`minCompileSdk` is a hard gate and it moves.** The AAR declares it in
  `META-INF/com/android/build/gradle/aar-metadata.properties`. The project is on
  `compileSdk = 35`, so:
  - `153.0.20260810162159` → `minCompileSdk=1` — **the newest usable version.**
  - `154.0.20260824154132` and later → `minCompileSdk=37` — these require raising
    `compileSdk` to 37, which drags AGP and Gradle with it. Do not bump the engine past
    153 without the owner deciding to take that on.
- The AAR is large and carries one native engine per ABI: `arm64-v8a` ~167 MB,
  `armeabi-v7a` ~130 MB, `x86_64` ~185 MB. **There is no 32-bit `x86`.** The ABI split
  list in `app/build.gradle.kts` must not name `x86`, and a build-time `require` fails
  the build rather than shipping an APK that dies in `System.loadLibrary`.
- Expect APKs far larger than the WebView edition's ~16 MB. That is inherent to bundling
  an engine, not a defect.

## Build, test and release — CI only

**Never build, test, assemble or release locally.** All of it happens in GitHub Actions:

```bash
git push                      # main or a branch
gh run list --limit 5         # find the run
gh run watch <id>             # follow it
```

Push with the workaround the stale credential store requires:

```bash
HOME=/tmp/rbhome GH_CONFIG_DIR=/home/dev/.config/gh git push origin <branch>
```

Work that is not ready to ship goes on a branch with a Pull Request: `pull_request` runs
`quality` + `e2e` but **not** `release`. A green push to `main` **auto-releases** (see the
`if:` on the `release` job), so do not push half-finished engine work to `main`.

Keep CI fast — it is the only build loop there is:

- Docs-only changes are already skipped by `paths-ignore`; keep `.github/**` out of that
  list so pipeline edits are validated by the pipeline.
- `.github/workflows/ci.yml` runs `autofix`, `quality` and `e2e` concurrently, not in a
  chain. Do not add `needs:` edges into them.
- The NDK/CMake install block is duplicated across the Android jobs and is copied
  verbatim on purpose; keep the copies identical.
- A failure in the fast jobs (`autofix`, `quality`) is worth far more than one in `e2e` —
  it costs a minute instead of an hour. Put engine-independent assertions in JVM unit
  tests so they fail there.

## Secrets, credentials and safety

- The GitHub token is used from the environment; it is **never** committed, echoed,
  pasted into a file, or sent anywhere. Do not print it.
- `~/room-browser-signing-backup/PASSPHRASE.txt` is never printed. The signing backup
  archive IS tracked and **both repos are public**.
- When inspecting `~/.git-credentials` or `~/.netrc`, usernames only.
- Never disable TLS or certificate validation globally, and never add a certificate-error
  bypass. GeckoView's advantage here is structural — it exposes no "proceed anyway" path
  for a bad certificate, and the code must not build one.
- Never upload wallet files or private keys anywhere.
- Do not introduce artificial delays or aggressive blocking.
- Fix root causes. A comment explaining a workaround for a defect is a reason to check
  whether the defect still exists after an engine change, not a reason to keep the code.

## Repo conventions

- Reply to the owner in Indonesian. **No Chinese characters in terminal output** — their
  terminal cannot render them.
- Commit messages end with `Co-Authored-By: Claude Code <noreply@anthropic.com>`.
- PR descriptions end with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- `androidTest` method names must be **snake_case only**. minSdk 28 means DEX below 040,
  and a backticked name with spaces dies at dexing. A JVM test guards this in `quality`.
- JUnit4 tests that assert with Truth must end in `runBlocking<Unit>` or an explicit
  statement — a Truth assertion as the last expression makes the method non-void and
  JUnit then runs none of the class.
- Only `Exactly`/`AtLeast` take `...ElementsIn`; the none/any/all families are
  `containsNoneIn`/`containsAnyIn`/`containsAllIn`.
