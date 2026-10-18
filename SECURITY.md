# Security

## Threat model

Room Browser defends against **casual cross-profile data mixing on one
device**: cookies, storage, history and permissions of one profile must never
leak into another. It does **not** defend against a fully compromised OS, a
malicious keyboard/screen-reader, or forensic disk analysis of an unlocked
device. It is **not** an anonymity tool.

## Controls

| Area | Implementation |
|---|---|
| Profile isolation | Per-profile WebView data directory (UUID suffix) + process restart on switch — see `PROFILE_ISOLATION.md` |
| Storage identity | Immutable UUIDs; names never used as identifiers |
| Profile lock | AndroidX BiometricPrompt (BIOMETRIC_WEAK + DEVICE_CREDENTIAL); locked profiles gate on open |
| Certificate errors | `onReceivedSslError` always cancels; the user sees an error page. Room Browser never auto-proceeds |
| Mixed content | `MIXED_CONTENT_NEVER_ALLOW` |
| File access | `allowFileAccess`, `allowContentAccess`, `allowFileAccessFromFileURLs`, `allowUniversalAccessFromFileURLs` all disabled |
| New windows | Blocked unless the user allows popups; non-gesture windows always refused |
| JavaScript | Per-profile default, per-site override |
| Cookies | Third-party cookies blockable per profile/site |
| Downloads | Filename sanitization (path traversal / separators / double-dot collapse), explicit notifications |
| Malicious sites | Bundled blocklist + heuristics (http, IP-literal URLs, punycode) surfaced as warnings/blocks |
| Agent actions | Every state-changing tool call passes a gate: **Confirm actions** (a blanket Allow/Deny prompt) and, optionally, a local Ollama decision model that judges the action first. Both are bypassed while **YOLO** is on. None of the three is a security boundary — see below |
| Agent vision | A screenshot of the current page can be sent to the model provider, signed-in content included; **off by default** (per-profile switch), refused on text-only providers and in a private tab — see *The agent's vision and site memory* below |
| Agent site memory | Per-profile, origin-keyed notes the agent keeps about a site; redaction at entry, user-visible and deletable per entry and per site, wiped with the profile — see *The agent's vision and site memory* below |
| Credentials | **Never stored by Room Browser.** Autofill is delegated to the Android autofill framework; no plaintext (or any) credential storage exists in the app |
| Secrets in repo | None. Signing comes exclusively from environment variables / CI secrets |
| Backups | `allowBackup=false` + data-extraction rules exclude the DB, profile dirs and DataStore — isolated state cannot leak into cloud backups |
| Logging | Production logs never contain URLs, cookies, credentials, page contents or PII |

## Android Keystore usage

Room Browser deliberately keeps no app-managed password/key material (no
credential storage, no sync keys). The Android Keystore is therefore not
required for the current feature set; the profile lock relies on the
platform's own Keystore-backed BiometricPrompt. If optional E2E-encrypted
sync is added later, keys MUST live in the Android Keystore and never leave
the device.

## WebView / engine limitations (no over-claiming)

- Page-load DNS for WebView traffic is resolved by the OS network stack; the
  in-app DoH/DoT configuration protects the app's own connections. Users who
  need DNS privacy for page loads should also enable Android's system-wide
  Private DNS (DoT) — the DNS settings screen explains this.
- WebRTC local-IP exposure is limited by a page script, per profile, and the
  limits are stated rather than implied. "Restrict local IP exposure" (the
  default) drops ICE candidates whose address is a real IPv4/IPv6 literal;
  mDNS host candidates — which carry no address — and STUN/TURN candidates
  are kept so calls still connect. "Disabled" removes the peer-connection
  API from the page. It is not total: document-start scripts run in the
  document, so code a page runs inside a Web Worker still reaches the
  engine's own WebRTC, and ICE statistics are not rewritten. Camera and
  microphone access remain permission-gated.
- User-Agent spoofing changes only the UA string — never real platform
  capabilities. No fingerprinting-bypass claims are made anywhere in the app.

### What a profile's device presents, exactly

A profile may be assigned one of the bundled real Android devices. That
assignment is a *claim*, not an emulation, and the difference is the whole
point of this section. What changes:

- the `User-Agent` header and `navigator.userAgent`
- `navigator.userAgentData` — `brands`, `mobile`, `platform`, and the
  high-entropy values `model`, `platformVersion`, `uaFullVersion`,
  `fullVersionList` and `formFactor`, which are the client hints that would
  otherwise name the real handset
- `navigator.platform` (derived from the profile's seed — see *The
  per-profile fingerprint seed* below), `navigator.deviceMemory`,
  `navigator.hardwareConcurrency`
- the WebGL `UNMASKED_VENDOR_WEBGL` / `UNMASKED_RENDERER_WEBGL` strings

What changes only when the profile asks for it, through the **Screen size** row
in its own settings:

- **The reported screen.** Off by default, and off means nothing here changes:
  a profile reports this phone's own `screen.width`, `screen.height`,
  `screen.availWidth`, `screen.availHeight` and `screen.orientation`, because
  the page really is laid out on this phone's screen. A profile may instead
  claim a size — see *Claiming a screen size* below, which is the one place in
  this document where the app deliberately introduces a disagreement.

What deliberately does **not** change, because it would be both a lie and a
detectable one:

- **The layout viewport.** `innerWidth` and `innerHeight` are the page's real
  width and height on this display. No script can move them without re-laying
  the page out at a size the screen does not have. `devicePixelRatio` stays the
  compositor's real ratio for a profile that claims no screen size; for a
  profile that *does* claim one, the seed derives it along with the claim — see
  below.
- **Real capabilities.** Camera, microphone, sensors, codecs, battery, and
  every permission stay the hardware's own.

This raises the cost of the cheap, scripted checks that compare a UA against
a handful of obvious properties. It does **not** make a profile
undetectable, and the app does not claim it does: a page that inspects
`Function.prototype.toString` on the patched accessors, times a WebGL draw
call, or correlates dozens of unrelated signals can still tell. Profiles are
for keeping separate identities separate, not for evading a determined
fingerprinter.

#### The per-profile fingerprint seed

Every profile the app creates carries a random seed. It is not shown and not
editable: it is part of the profile's identity. It exists because a device
assignment alone does not always keep two profiles apart — the imported
profile can have no device at all, and once the device catalogue is exhausted
two profiles are handed the same row — and two profiles that present the same
handset byte for byte defeat the reason for having separate profiles. The seed
varies independently of the device, so those profiles stay distinct. Exporting
a profile carries the seed in its settings, so a restore keeps the identity
rather than minting a new one; a profile stored before this field existed has
no seed and looks exactly as it did.

The seed derives a stable value for each surface below, through
HMAC-SHA-256 with one label per surface. *Stable* is the point: each value is
computed once and installed, never drawn per read, because a value that changed
between reads would be a much louder signal than an unusual but constant one.
The same seed yields the same values on every launch and in both editions, so
the derivation itself is not a way to tell the editions apart. Every value is
drawn from a pool a real Android Chrome could report:

- `navigator.platform` — one of the arm spellings Chrome for Android sends.
- `screen.colorDepth` / `screen.pixelDepth` — 16 or 24.
- `navigator.maxTouchPoints` — 5 or 10.
- `devicePixelRatio` — a ratio a real phone ships, and **only** when the
  profile claims a screen size; a profile that claims none keeps the
  compositor's real ratio.
- the shape of `navigator.plugins` and `navigator.mimeTypes` — a subset of
  Chromium's bundled PDF viewers and the matching PDF mime types. The viewer
  *names* are Chromium's; only how many are reported varies.

What the seed deliberately does **not** do: it adds no canvas or audio noise,
no font, timezone or language manipulation, and no per-read jitter. Random
per-read noise is itself a fingerprint signal — a value that cannot be read the
same way twice is not a value a real device produced — and it breaks pages.
Timezone and language overrides stay off unless the user asks for them.

#### What this still does not cover

- **Worker realms.** A document-start script runs in the document. Code a page
  runs in a Web Worker, Shared Worker, Service Worker or any other realm sees
  the engine's own values, both for the WebRTC policy and for every surface
  above.
- **Anything that has to be patched before `document_start`.** A surface the
  engine reads before the first page script runs, or one a page reads from a
  realm this script is not injected into, cannot be reached from here.
- **Surfaces the shim does not touch.** Canvas and audio rendering, font
  metrics, TLS and HTTP/2 fingerprints, battery, sensors, codecs, WebGPU and
  `navigator.connection` are the hardware's own, and a page that correlates
  dozens of unrelated signals can still single a profile out. The shim raises
  the cost of the cheap checks; it is not and does not claim to be
  undetectable.

#### Claiming a screen size

The Screen size row is per profile and off by default. Turning it on replaces
`screen.*` and derives `screen.orientation` from the shape claimed, so a profile
claiming a landscape screen does not also answer `portrait-primary` — that
pairing is the contradiction the shim exists to remove.

It cannot move the viewport, for the reason above. A claimed size that differs
from this display's screen therefore leaves `screen.width` and `innerWidth`
disagreeing, and that disagreement is exactly the sort of thing a fingerprinting
script looks for. The settings row says so where the choice is made, and the
fields open on the real size so that the first thing the user sees is the truth.

It exists because leaving it out is not neutrality. A profile presenting a
Galaxy S24 Ultra already reports a screen that handset never had; the setting is
how that becomes a choice the user made and can see, rather than an accident the
app never mentioned, and it is the only way to make the claim match the device.
Set it to the size the presented handset actually has. If you do not know that
size, leave it on the real screen: claiming nothing is safer than claiming a
guess.

The two desktop editions offer the same row with the same two modes, and the
claim behaves the same way there, with three differences that come from the
platform rather than from policy:

- The **available** rectangle is derived from the real one rather than set equal
  to the claimed screen. A desktop has a taskbar or a dock, and Chrome subtracts
  it; the shim measures that inset from the real display at document start and
  carries it over, so the claimed screen has an available area that differs from
  it by as much as a real one does. A claim of no inset at all would be its own
  tell.
- `screen.orientation.angle` stays `0`. The angle is the device's rotation, not
  the display's shape: a phone held sideways is 90, and a monitor is 0 whatever
  its aspect ratio, because nothing rotated it. The shape still decides
  `orientation.type`.
- The two numbers are the profile's own, so they are what a desktop browser
  window would have to be resized to for the page to agree with them. A desktop
  window can be any size, so `screen.width` disagreeing with the viewport is
  ordinary here in a way it is not on a phone — which makes the claim a weaker
  signal, not a stronger one.

#### The desktop editions

Both desktop editions present a machine from the same idea, with the rules a
desktop needs rather than the ones a phone does. A profile may be assigned
one of 1041 real laptops, desktops and workstations (2022–2025, Windows,
macOS and Linux), and what it presents is the same list as above, with these
differences:

- `mobile` is always `false` and `formFactor` is `"Desktop"`.
- `model` is the **empty string**. Chrome reports no model on a desktop, so
  a profile that invented one would be the only desktop in the world with a
  model name.
- `architecture` and `bitness` follow the machine's CPU: Apple Silicon
  reports `arm`, everything else `x86`, always 64-bit.
- `navigator.platform` and `userAgentData.platform` follow the OS —
  `Win32`/`Windows`, `MacIntel`/`macOS`, `Linux x86_64`/`Linux`.
- The User-Agent carries the **major** Chrome version only
  (`Chrome/131.0.0.0`), which is the reduced form Chrome has sent since
  2022; the build number lives in the `uaFullVersion` client hint.
- `deviceMemory` is never above 8, because that is where Chromium caps it —
  a 64 GB workstation honestly reports 8, exactly as a real one does.

The catalogue is also **all-distinct**: no two machines in it present the
same fingerprint, and a curated machine that could not be told apart from
one already listed was left out rather than shipped as a second name for the
same identity. Two profiles assigned such a pair would present the same
machine, which is the thing the catalogue exists to prevent. Real capabilities
are excluded here for the same reasons as on Android, and so is screen geometry
by default — but the Screen size row above is offered by both editions, so a
desktop profile may claim one, under the rules stated there.

- Safe Browsing status follows the system WebView component.

#### The agent's action gate (Android)

Room Agent drives the real browser, so the app has to answer a question no
sandbox can: *should this click happen?* Three rules apply to every
state-changing tool call — clicking, typing, submitting, and the four social
`auto_*` actions — in this order:

0. **YOLO** — every action runs. Nothing below is consulted, neither the
   model nor the user. It is first because it is not a decision at all: it is
   the user having said they do not want to be asked.
1. **The local decision gate** — an optional Ollama decision model asked to
   choose `allow`, `confirm` or `deny` for the action, given the action's
   label, the page's URL and title, and the user's own policy text. It runs
   on the user's own machine (`POST /v1/systemone`, Ollama 0.35+), so the
   action text never leaves it.
2. **The session memory** — an answer the user already gave for this tool on
   this site, in this chat ("Allow on this site"). It sits below the local
   gate, so a policy denial still denies; a deny never interrupts anyone, so
   remembering an answer cannot take one away. It is offered only where it can
   honestly be kept: never for an action that cannot be undone, never for a
   wallet request, never for a tool whose own prompt is the point (a live
   authenticator code typed into a login form), and never for a page with no
   origin to file it under. It is cleared when the chat changes.
3. **Confirm actions** — the blanket Allow/Deny prompt, off by default. It
   has a third answer, **Always allow**, which turns rule 0 on, and the
   narrower **Allow on this site**, which is written into rule 2.

The gate only ever **adds** a decision. An `allow` lets an action the blanket
rule would have asked about proceed unasked; a `deny` refuses it and returns
the reason to the model as the tool result, so it changes course instead of
retrying; a `confirm` — and every failure — falls through to rule 3.

**None of these is a security boundary, and this document will not pretend
otherwise.** Confirm actions is a UI prompt: it protects the user from an
agent they did not intend, not from an attacker, and a user who taps Allow
without reading has given the agent the run of the machine. The decision gate
is weaker still in kind if not in degree — a 9B model reading prose can be
argued out of a decision by the page it is judging, and its `confidence`
field measures how concentrated its probabilities are, not how likely it is
to be right. What the gate buys is fewer interruptions, which is what makes
Confirm actions usable enough to leave on.

**YOLO is the absence of both checks**, and it is the one setting in this app
whose "on" state is indistinguishable from the app working normally: no
prompt appears, so nothing on screen says the checks are gone. It is
deliberately reachable from the approval prompt itself — the moment a person
wants it is the moment they are being interrupted — and it is persisted, so
it outlives the turn it was chosen in. Because it is invisible in use, the
app says it in the chat the first time it is turned on, on every subsequent
turn while it is on, and in a warning paragraph on the settings screen that
renders only while the switch is on. Turning it off restores rules 1 and 2
exactly as they were.

One consequence is worth stating plainly, because it is invisible from the
switch: **with Confirm actions off, a decision gate that cannot be reached
means actions run ungated**, exactly as they did before the feature existed.
That is deliberate — a browser that stops working because Ollama is down
would be a worse failure — and the app says so where the choice is made,
posts a notice in the chat the first time it happens in a turn, and leaves
Confirm actions as the switch that gives a hard guarantee.

The gate is refused for every provider that cannot serve it: `/v1/systemone`
scores answer tokens directly and Ollama serves it only for local GGUF
weights, never for cloud, MLX or Safetensors models. Only `OLLAMA`-protocol
providers appear in the picker. YOLO is not a gate feature and is not
restricted that way — it is a local decision, so it applies to every
provider, Ollama included.

## The agent's vision and site memory (Android)

Two of the agent's newer capabilities touch data that leaves the device or
accumulates in the profile, so their limits are stated here rather than left in
the user guide alone. Both are per-profile and both are **off on every profile,
new and old**, carried by a defaulted Boolean (`agentVisionEnabled`,
`agentSiteMemoryEnabled`) rather than by a withheld permission — that set is a
deny-list, so a permission added later is granted by default and cannot express
"off". Airplane Mode is the newest — the one place this app runs a root shell,
pinned to two fragments and verified by reading the state back.

### Vision (a screenshot to the provider)

`see_page` captures a screenshot of the current page and sends it to the model
provider. That is the one change in this document with a direct privacy
consequence, and it is not mitigated:

- **A picture of the page leaves the device, and it includes anything you are
  signed in to.** The image is not the page's text — it is everything the
  engine renders, which is exactly why it is useful and exactly why it is
  dangerous. If the page is a bank, an inbox or a dashboard, that is what the
  provider receives.
- **The app cannot redact pixels.** There is no redaction pass over the image:
  a token, a one-time code, a private key or a secret drawn into a canvas is
  sent as rendered. The redaction that protects the console and network buffers
  does not apply here, and this document does not claim it does.
- **The capture is the engine's frame.** The app's own Compose UI is not part
  of it. On the WebView edition `EngineSession.capturePixels` crops the
  Activity window to the engine's bounds, so a panel that overlaps those bounds
  can appear in a frame; the claim that the Developer Tools overlay is *never*
  in the frame is not verified here.
- **Unavailable on text-only providers.** The on-device `LOCAL` engine and the
  `OPENCODE` server take text prompts only, and the tool is refused for them
  **before any capture**, with that reason (`AgentVisionSupport`) — a request
  whose image would be silently dropped is never sent. [OI]-compatible,
  Anthropic and Ollama providers can carry one.
- **Refused in a private tab.** When the tab the agent is working on is private,
  `see_page` refuses before capture, the same rule that disables the Developer
  Tools panels there. The refusal is in the tool, so it holds whether the switch
  is on or off.
- **Off by default.** `agentVisionEnabled` is false on every profile, and the
  setting's own description states the consequence in full.

The honest summary: while vision is on, the agent can hand a provider a picture
of whatever is on the screen, and nothing in the app stands between the page's
pixels and the request. That is a deliberate trade for a capability the user
opts into, not a sandbox.

### Site memory (an origin-keyed memory)

Site memory lets the agent keep notes about a site so it does not relearn the
same thing every turn. It is a new place where secrets could accumulate, and it
is bounded as follows:

- **Origin comes from the session URL, never from page-supplied text.** A page
  cannot choose the origin its notes are filed under, so it cannot read or
  overwrite another site's memory by naming it.
- **Redaction is applied at entry**, the same pass that protects the console and
  network buffers, so a credential the agent copies out of a header or a URL is
  masked before it is stored.
- **The user can see and delete what is remembered**, per entry and per site —
  memory the user cannot inspect is memory the user cannot audit.
- **Wiped with the profile.** Site memory is profile state, not device state;
  deleting the profile removes it with the rest.

What is not mitigated: redaction is name-based and bounded to the values it
knows (the same header and query names listed under Developer Tools below), so a
secret that reaches a note under some other shape is not caught. Site memory is
stored in the profile's own database, in the same file as the rest of the
profile's state, and is **off unless the profile opts in**.

### The root shell, and its one job

`airplane_mode action=set` — and the `/airplane on|off` chat command, which
rides the same bridge — is the only place this app runs `su`. The shell is not
a capability the agent holds; it is one command with two pinned spellings:

- **Exactly two fragments ever run.** The primary is
  `cmd connectivity airplane-mode enable|disable`; the fallback, as one
  invocation, is `settings put global airplane_mode_on 0|1 && am broadcast -a
  android.intent.action.AIRPLANE_MODE --ez state true|false`. NOTHING else is
  ever interpolated into the command — no user text, no model output, no
  state read off the device — so there is nothing to inject and no route to
  widen it into anything else.
- **Every run is verified by reading the state back.** Android lets any app
  READ `Settings.Global.AIRPLANE_MODE_ON` without a permission, so success is
  the state matching, not the command's exit code: a command that exits 0
  without changing anything is reported as a failure ("did not take
  effect"), never as a switch that happened.
- **A phone that is not rooted executes nothing at all.** No su binary, no
  shell, no attempt — the guided hand-off (the system Airplane Mode settings
  screen opened, a Yes/No confirmation, and a poll that continues the chat by
  itself the moment the phone matches) is the whole flow there, and for the
  tool the Confirm-actions prompt is the person's yes/no.
- **The whole feature is behind one master switch.** The browser-wide
  "Offer Airplane Mode shortcut" setting gates the command and the tool
  alike; with it off, both refuse and nothing is executed on any phone.

## Developer Tools (Android)

Developer Tools is a docked inspector that reads the **live** engine session.
That makes it a privileged surface — it can see the page's own requests, and the
URLs they carry can include the user's authenticated session — so its rules are
stated here rather than left only in the app.

- **It is app UI, never reachable from page content.** The panel composes above
  the engine view in the app's own Compose tree; page script has no handle to
  it. Because it sits above the engine view, it is also absent from
  `EngineSession.capturePixels`, which captures the engine view only — a page
  that screenshots itself cannot capture the panel.
- **Redaction happens at data entry.** Before a console or network entry reaches
  a buffer, a URL's `user:pass@` userinfo and credential query parameters
  (`access_token`, `token`, `api_key`, `key`, `secret`, `password`) are masked,
  and the same shared pass replaces the values of the header names
  `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `x-api-key`,
  `api-key`, `x-auth-token`, `x-csrf-token` and `www-authenticate` with
  `<redacted>` wherever a header value is present — on this edition the network
  feed carries no headers, so the URL masking is what a reader will actually see.
  A page's own console TEXT is left as the page wrote it: this is the user's own
  view of their own page, and rewriting it would make the panel lie about what
  the page said.
- **No remote-debugging endpoint is exposed.** The app opens no debug socket and
  never enables GeckoView's remote-debugging support: nothing in the source sets
  the Gecko runtime up to accept a debugger connection, and no inspector of this
  app's pages is reachable over the network or from another app. This is a
  property of the source as it stands — no such call exists anywhere in it — and
  not merely a runtime toggle.
- **Unavailable in private tabs.** The Developer Tools row is not offered on a
  private tab and no inspector session is created for one; if a private tab is
  opened while the panel is showing, the panel closes.
- **The diagnostic report is gated on a setting that is off by default.** The
  agent's `devtools_report` refuses unless the browser-wide "Enable network
  diagnostics" setting is on, and it collects nothing before that refusal — so
  no report is produced at all until the user turns it on.

### What the panel does not claim

Its honesty is structural rather than editorial: the panel renders only the
capabilities its engine actually reports, and each absent capability is
explained with the reason the capability layer carries — where none exists, it
says the capability is simply not built, never an invented cause. Its
"Not Google Lighthouse" wording is pinned verbatim by a test, and no score or
invented category number is produced. On GeckoView, the network feed is a
WebExtension observer rather than a debugger: it reports each request's method,
URL and frame together with the response status, completion and failure, but no
header names or values — the observers are registered without requesting the
header blocks, so headers are absent by design rather than by an engine limit,
and the panel claims none. Heap snapshots are not shipped on either edition.

## Reporting

Security issues should be reported privately to the repository owner. Please
do not open public issues for vulnerabilities.
