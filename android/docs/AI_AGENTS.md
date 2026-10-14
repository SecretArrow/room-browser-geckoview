# AI Agents — Autonomous Browsing (Room Agent)

Room Browser ships **Room Agent**, an autonomous browsing assistant inspired
by the agent-mode experience of chat.z.ai and the provider flexibility of
opencode: you type a task, the agent drives the real browser — navigating,
reading pages, clicking, filling forms and managing tabs — while you watch
it happen behind the chat panel.

```
┌───────────────────────────────┐
│  (the live web page)          │
│  … the agent clicks/types …   │
├───────────────────────────────┤
│ ✨ Room Agent    [z.ai·glm-4.6]│
│ ┌───────────────────────────┐ │
│ │ you: open example.com and │ │
│ │      summarize it         │ │
│ │ ⚙ Open https://example… ✓ │ │
│ │ 📖 Read current page … ✓  │ │
│ │ agent: The page says…     │ │
│ └───────────────────────────┘ │
│ [Include page] [Ask…    (➤)] │
└───────────────────────────────┘
```

## Where to find it

| Entry point | Action |
|---|---|
| Menu (⚙) → **AI Agents** | Expands the agent panel (always available) |
| Floating pill (bottom-right of the browser) | Opt-in — see **Show AI Agent button** below; once shown, tap to expand the panel, and while a task runs it streams live progress ("clicking [12] Sign in…") |
| Menu → **AI Agent settings (providers & models)** | Opens the AI settings **activity** (its own window) |
| Menu → **AI Agent chats** | Opens the per-profile chat history **activity** |
| Panel header | Model picker (provider · model), new chat, history, settings |

### Show / hide the floating agent button

The floating AI Agent button is **hidden by default** so the browser stays
minimal. Turn it on via either:

* **Browser Settings → AI Agent → Show AI Agent button**, or
* **AI Agent settings → Agent behavior → Show AI Agent button**.

While a task is actively running the pill always appears (live progress
stays visible) and hides again when the turn finishes. The agent itself
remains reachable at any time from the page menu (⚙) → *AI Agents*.

### Chats are per-tab

Each browser tab keeps **its own conversation**. Switching tabs switches the
chat in the panel; opening a fresh tab starts an empty one. A chat is written
to the database on the first message sent from a tab, and it stays that tab's
chat — so coming back to a tab brings its conversation back with it, and
closing then **reopening a tab** (which reuses the tab's id) restores the chat
too.

* **New chat (+)** gives the tab up rather than deleting anything: the current
  conversation moves to the history list and the tab starts a fresh one.
* **A new message continues the chat**, it does not start one: the follow-up is
  written to the same session, and the step list that chat last wrote is shown
  to the model again, so it carries on where it left off rather than beginning
  from nothing. Only **New chat (+)** — or adopting another chat from History —
  starts a conversation that knows nothing about the last one.
* **Allow on this site** lasts as long as the chat it was given in: switching to
  another tab and back is still that chat, so the approval is still there.
* **History** lists every chat of the profile. Tapping one **adopts it into
  the current tab**, so a conversation started elsewhere can be picked up
  here; the chat this tab was showing goes back to the list.
* **While a turn runs**, the panel stays with that turn even if you switch
  tabs — the header then reads *Running in another tab* — and it returns to
  the current tab's chat when the turn finishes. One conversation is live at
  a time; running several turns at once is not supported yet.
* An unsent draft (and any attached files) belongs to the tab it was typed
  in, and is dropped when you switch away rather than being filed under
  another tab's chat.

Chats written before per-tab chats existed are unbound: they are in the
history list as before, but no tab claims them.

## Providers & models (manual input, like opencode)

Any **OpenAI-compatible** endpoint works. In *AI Agent settings → Add
provider*:

1. **Pick a preset or type a custom base URL** — Z.ai, OpenAI, OpenRouter,
   Groq, DeepSeek, Mistral, Together, Ollama (`http://localhost:11434/v1`),
   LM Studio (`http://localhost:1234/v1`) or any custom gateway.
2. **Paste your API key** (optional for local servers). Keys are encrypted
   with an **AndroidKeyStore AES-256-GCM** key and stored only on this
   device.
3. **Fetch models** — the model list is retrieved live from the provider's
   `GET {baseUrl}/models` endpoint and shown as selectable chips. A
   **Search models** field above the chips filters long lists live (type
   e.g. `glm` or `mini`), shows "N of M models" and can be cleared with one
   tap. If a provider doesn't expose `/models`, type the model id manually
   (always available).
4. **Save** — the provider becomes selectable in the panel's model picker.

Wire protocol used: `POST {baseUrl}/chat/completions` with
`tools` (function calling) and `stream: true` (SSE), including
`reasoning_content` passthrough for reasoning models. Non-streaming JSON
responses are accepted as a fallback automatically.

## What the agent can do (tool catalogue)

EVERY tool acts on the chat's own tab — the tab the turn was started from —
and never on "whatever is on screen". A turn therefore survives you switching
tabs, and cannot be retargeted by it. Tools that READ or navigate a page are
listed below; the two that must have their tab on screen are marked.

| Tool | Effect |
|---|---|
| `navigate(url)` | Load a URL in this chat's tab (waits for page finish) — *needs the tab on screen* |
| `search_web(query)` | Runs the profile's search engine — *needs the tab on screen* |
| `read_page()` | Extracts URL, title, visible text and every interactive element with a `[ref]` number |
| `click(ref)` | Clicks element `[ref]` (scrolls it into view first) — *needs the tab on screen* |
| `fill_input(ref, text)` | Types into inputs/textareas — uses the native value setter so React/Vue forms register it — *needs the tab on screen* |
| `press_enter(ref?)` | Submits the focused / given form — *needs the tab on screen* |
| `scroll(direction, amount?)` | Scrolls the page |
| `go_back()` | History back — *needs the tab on screen* |
| `open_new_tab(url?)`, `list_tabs()`, `switch_tab(index)`, `close_tab()` | Tab management. `list_tabs` marks which tab is *this chat* and which is *on screen*; `close_tab` closes the chat's own tab |
| `auto_like()` | Likes/upvotes the posts **currently visible** on the page (X, Facebook, Reddit, Tumblr, LinkedIn…) — up to 20 per call; scroll then repeat to continue — *needs the tab on screen* |
| `auto_repost()` | Reposts/retweets/reblogs/shares the visible posts — up to 15 per call — *needs the tab on screen* |
| `auto_reply(text)` | Types the text into the visible reply box and submits it — *needs the tab on screen* |
| `auto_post(text)` | Opens the composer, types a new post/status/tweet and submits it — *needs the tab on screen* |
| `wait(ms)` | Waits for post-submit animations / infinite-scroll loading before reading again |
| `compute_js(script)` | Evaluates JavaScript in a **detached scratchpad page**, never the user's page — for local calculation and text work (see **Local compute, vision, site memory** below) |
| `data_transform(op, input, …)` | Pure host-side transform of data the agent already has — `json_path`, `csv_rows`, `aggregate`, `diff` — with no page and no engine (see below) |
| `node_run(script)` | Runs a JavaScript program in the **embedded Node.js runtime**, in a process of its own, with Node's real APIs behind it — *not about the open page; refused to a scheduled run and in Plan mode* (see below) |
| `node_packages(action, name?, spec?)` | Manages the npm packages installed beside that runtime — `list`, `install`, `remove` — for `node_run` to `require`. It never executes package code — *refused to a scheduled run and in Plan mode* (see below) |
| `see_page(question?, full_page?, annotate?)` | Screenshots this chat's tab and sends the image to the provider; `full_page` stitches the whole document (scaled down) and `annotate` draws the `[ref]` numbers onto the elements — *off unless the profile enables it; refused on text-only providers and in a private tab* (see below) |
| `compare_screenshots(tolerance?)` | Captures the page and reports how much of it changed, and where, against the last screenshot taken in this turn — the cheap "did that click do anything?" check that sends the provider no second image |
| `site_memory(op, key, …)` | Reads, writes or forgets a per-site note kept for this profile — *off unless the profile enables it* (see below) |
| `plan(items)` | The agent's own step list, shown to you as a checklist card with what it has finished and what it is on now. It sends the **whole list every call** (it replaces the previous one) and touches no page; the app keeps the last list on the chat, so the card comes back when the chat is reopened — see **The step list** below |
| `download_file(url, name?)` | Hands a link that leads to a **file** (an invoice PDF, a CSV export, an image) to the browser's own download engine, so it lands in Downloads/RoomBrowser and the transfer outlives the turn. It queues the file and returns — the file is not ready when the call answers, so the agent checks `app_data kind=downloads`. Confirmed like a click, refused in plan mode, and refused in a scheduled run (see below) |
| `save_pdf(name?)` | Keeps the **page itself** — a receipt, a ticket, a statement — as a PDF in Downloads/RoomBrowser. Use it when the user wants the page they are on kept, and `download_file` when a link already points at a file. Queues the same way a download does, confirmed like a click, refused in plan mode, and refused where there is no page on screen to render (see below) |

A tool marked *needs the tab on screen* waits up to 8 seconds for its tab to
come to the front, then refuses with an explanation. The reason is concrete:
the browser hosts a WebView engine only while its tab is on screen, so
starting a page load on a background tab can wedge that tab permanently. The
tools without the mark are pure page-script or bookkeeping calls and are safe
on a background engine, which is what lets a turn keep working while you read
something else.

Element interaction uses the numbered-reference model (every visible
interactive element is tagged `data-agent-ref` by injected JS — the same
family of techniques used by WebVoyager-style browser agents, adapted to
Android WebView).

### Social automation ("auto selesaikan task")

The four `auto_*` tools are heuristics that work across social sites by
matching visible button labels (EN + ID: *like/suka, repost/bagikan ulang,
reply/balas, post/tweet…*) and typing into the visible composer with the
React/Vue-safe native value setter. Anything they cannot solve directly,
the model still solves with the generic tools (`read_page` → `click` →
`fill_input` → `press_enter`) — so phrased tasks like *"balas semua DM
yang bilang halo"* or *"like 50 post tentang AI"* get decomposed into
scroll → `auto_like` → verify loops. Automation tools are part of the
**Confirm actions** gate, so you can require an Allow/Deny tap before the
agent likes/posts anything.

### Background execution

Agent turns keep running when you leave the app or turn the screen off:
while a turn is active, `AgentKeepAliveService` (a `dataSync` foreground
service in the `:browser` process) holds a partial wake lock and shows an
ongoing progress notification (current step + a **Stop** action). The
service starts when you hit Send and stops itself when the turn finishes.
Note the honest limits: swiping the app away from Recents kills the
process (standard Android behaviour), and the system caps `dataSync`
services at ~6 hours per day on Android 14+ — far beyond any realistic
agent task.

## Agent permissions, and the new capabilities

### The permission model (a withheld-set)

Every tool declares the permission it needs, and a profile stores the set of
permissions it **withholds** (`ProfileSettings.agentWithheldPermissions`, per
profile). A tool whose permission is withheld is refused **by name** — the
refusal says which tool and which setting — so the model reports what is
missing instead of probing for another route. The check is a decorator
(`AgentPermissionGate`) that wraps the executor, so the visible tab, a hidden
page and a scheduled run all pass through it, and the withheld set is read live,
so a permission granted mid-turn is seen by the next call.

The names are wire strings (`devtools.read`, `devtools.network`,
`devtools.storage`, `devtools.security`, `devtools.cookies`,
`agent.exportDiagnostics`, `agent.compute`), not an enum: a profile that stored a
name this build does not know simply carries an inert entry.

**Off by default, where it matters.** The withheld set cannot express "off": it
is a deny-list, so a permission added later is granted until the profile
withholds it, and an empty set (a fresh or upgraded profile) grants everything.
So the two capabilities that leave the device or persist new data — vision
(`see_page`) and site memory (`site_memory`) — are instead gated by a
per-profile Boolean (`agentVisionEnabled`, `agentSiteMemoryEnabled`) with its
own settings row: site memory is **false on every profile, new and old**, and
vision is **true**, because a page that reads as nothing is a task the agent
would otherwise give up on. The two
local ones — `compute_js` and `data_transform` — are governed by the withheld
`agent.compute` permission and are therefore on until the profile withholds it;
`node_run` and `node_packages` have a permission of their own, `agent.node`, for
the same reason — they reach wider than a scratchpad in every direction, so they
should not ride another tool's switch.

### Tool catalogue shape

`AgentTools` declares each tool as a `ToolDef` — a name, a description and a
JSON-schema for its arguments. Two named sets sit alongside it:
`INTERACTIVE_TOOLS` are the state-changing calls the Confirm-actions /
decision-gate / YOLO rules cover, and `DEVTOOLS_TOOLS` are refused wholesale on
unattended runs (a scheduled or headless run has no Console panel to drive and
no user to confirm a patch). `AgentTools.describeTool` renders the one-line
label the chat cards show.

### `compute_js` — a detached scratchpad

Evaluates JavaScript in a scratchpad engine session created for the turn: a
blank document, never attached to a view, so a script there cannot appear on
screen or touch the page the user is on. It is deliberately **not** the chat's
own tab, which is live and may have navigated.

Honest limits:

* **It is not the page.** It cannot see or change the user's page; the page
  cannot observe it either.
* **File access is off.** Every session is created with `allowFileAccess`,
  `allowFileAccessFromFileURLs` and `allowUniversalAccessFromFileURLs` false,
  so a script cannot read the device's files.
* **It is not a network sandbox.** The app does not block network loads on the
  scratchpad: a script there can still attempt an outbound request. The
  scratchpad has no page and no site origin, but it is a separate context, not
  a firewall — do not treat it as a place to run code you do not trust.
* **Synchronous only.** The value of the last expression comes back as text; a
  returned `Promise` is not awaited.
* **One scratchpad per turn**, closed with the turn. On a scheduled or headless
  run the executor has no scratchpad and the tool refuses rather than running
  the script somewhere it does not belong.
* Gated by the per-profile `agent.compute` permission.

### `data_transform` — pure host-side transforms

Four operations computed in this process on data the agent already holds — no
JavaScript, no engine, no page:

| `op` | What it does |
|---|---|
| `json_path` | The value at a dotted path (`a.b.0.c`) in JSON; a blank path reads the whole document |
| `csv_rows` | The rows of a CSV text |
| `aggregate` | `count`, `sum`, `min`, `max` or `avg` over a column |
| `diff` | Two texts compared |

Honest limits:

* Output is capped at `limit` characters (default and maximum 4000); a
  truncated answer says so rather than being cut silently.
* **Malformed input is an error, never an empty success** — a caller that could
  not tell "no rows" from "not CSV" would answer the user with the wrong thing.
* It shares the `agent.compute` permission with `compute_js`.

### `node_run` — the embedded Node.js runtime

A Node runtime ships inside the APK (`libnode.so`, arm64-v8a / armeabi-v7a /
x86_64) and `node_run` sends it one JavaScript program, as the body of an async
function: `return` sends a value back, `await` works, and `require` resolves
whatever is installed beside the runtime. It runs in a **process of its own**
(`:node`), started for the call and gone when the call ends, so a chat that
never runs a script never pays for a runtime.

The identity is the whole point of the plumbing. Node's own client announces
`node` — the one thing on this device that is not the profile — so every request
a script makes carries the identity the profile's pages send: the profile's own
user agent, or the engine's default when the profile chose none. A script cannot
supply one of its own — the profile's identity replaces whatever it sets, on
`fetch`, on `http`/`https`, and on the request's own header setters alike. The
rule covers those APIs and not the layer under them: a script that opens a raw
`net`/`tls` socket, writes HTTP by hand, or calls the request classes'
prototypes directly is outside the wrappers, so it is a rule rather than a
sandbox. A profile with no identity at all makes no request: the tool refuses
before the script runs rather than letting Node introduce itself.

Honest limits — these are the feature, not a footnote:

* **It is not a sandbox.** The `:node` process has its own memory and can be
  killed on its own, but it shares this app's UID and data directory, so a
  program it runs can read and write this profile's own files. That is a limit
  to state, not to paper over.
* **Node 18, which is end-of-life.** There is no newer nodejs-mobile release;
  the runtime reports its own version and the honest answer is v18.20.4.
* **No native modules.** A package that needs to compile C++ cannot be built
  for Android here: it installs, and then fails when a script requires it, with a
  message rather than a hang.
* **A script gets a budget** (30s by default). The runtime stops it itself and
  says so, so a wedged script arrives as a readable answer rather than a
  connection that went quiet.
* **Its output is data.** What a program prints can be a server's answer or a
  package's own words, so it is fenced as untrusted content exactly like page
  text.
* **Never a scheduled run**, and never in Plan mode. It is governed by the
  per-profile `agent.node` permission, and it is on the Confirm-actions /
  YOLO axis like every other state-changing call: in YOLO it runs without
  asking.

**Status in this build:** implemented — `node_run` runs a script and returns
its result, with the per-profile identity attached to every request, a budget
the runtime enforces itself, and the `agent.node` permission and settings row.

### `node_packages` — the packages `node_run` can `require`

`node_run`'s `require` resolves whatever is installed beside the runtime, and
`node_packages` is how a package gets there: `action=list` shows what is
installed, `install` fetches a package and its dependencies from the public npm
registry, and `remove` deletes one. It prepares what a later `node_run` will
run — it never runs code itself.

Honest limits:

* **Nothing from a package is ever executed.** No `scripts` field is read and no
  lifecycle script runs, at install time or afterwards; a package's `postinstall`
  is inert here. That is a deliberate difference from `npm install`, and it is
  why a package that needs a build step will not work.
* **Plain JavaScript only.** A package with native code installs but fails to
  load, because there is no way to build C++ for Android here — the same limit
  `node_run` states.
* **Only `dependencies` are installed.** A package's `devDependencies`,
  `optionalDependencies` and `peerDependencies` are left alone, and the result
  says which were skipped. Dependencies are nested under their parent rather
  than hoisted, so two packages can each keep their own copy of a dependency and
  a version conflict cannot arise.
* **One registry.** Packuments and tarballs come from `registry.npmjs.org`
  alone; a tarball from any other host is refused and redirects are not
  followed. Each tarball is checked against the registry's own digest before it
  is unpacked.
* **One shared tree.** The packages live in the app's own data directory, which
  every profile shares, so a package installed under one profile is visible to
  every profile's agent. That is the directory `node_run` requires from.
* **Never a scheduled run**, and never in Plan mode. It rides the `agent.node`
  permission, and it is on the Confirm-actions / YOLO axis: the user is asked
  before a package is installed or removed — with the package named in the
  prompt — and in YOLO it happens without asking. `list` only reads the
  directory, so it does not ask.

**Status in this build:** implemented — `node_packages` lists, installs and
removes packages, with the settings screen's "Installed packages" row to see
and delete them.

### Vision (`see_page`) — a screenshot to the provider

Captures a screenshot of the current page and sends it to the model provider.

The honest limits are the point of the feature, not a footnote:

* **It sends a picture of the page — including anything you are signed in to —
  to the provider.** That is a real privacy consequence and it is stated in
  `SECURITY.md`, not only here.
* **The engine's view.** It captures the engine's frame; the app's own Compose
  UI is not part of it. On the WebView edition `EngineSession.capturePixels`
  crops the Activity window to the engine's bounds, so whether a docked panel
  that overlaps those bounds can appear is not something this page can promise
  (see `SECURITY.md`).
* **It cannot redact pixels.** Anything rendered on screen — including a secret
  drawn into a canvas — goes with the image. There is no redaction pass over
  the pixels.
* **Refused in a private tab.** The capture is refused before it happens when the
  tab the agent is working on is private, the same rule that disables the
  Developer Tools panels there.
* **Unavailable on text-only providers.** The on-device `LOCAL` engine and the
  `OPENCODE` server take text prompts only, so the tool is refused for them
  **before capture**, with the reason (`AgentVisionSupport`). [OI]-compatible,
  Anthropic and Ollama providers can carry an image.
* **On by default.** `agentVisionEnabled` is true on every profile, because a
  page that reads as nothing is a task the agent would otherwise give up on.
  The setting's own description states the consequence in full, and turning it
  off takes `see_page` away.

#### Full page, numbered elements, comparing two captures

`see_page` takes two optional flags, and `compare_screenshots` is a second tool
built on the same capture. All three live **above the engine facade**, so there
is no engine-specific code in any of them.

* **`full_page=true`** stitches the whole document from viewport captures. It is
  deliberately **not** a single document-sized draw, because the engine this
  edition pins cannot make one: its screenshot builder has no whole-page mode and
  every size it accepts is bounded by the viewport the display renders (the
  verified detail is in `AGENTS.md`). So the tool scrolls, captures each strip
  through the engine's asynchronous `capturePixels`, and stitches them with pure
  arithmetic (`ScrollStitch` in `:core:domain`, JVM-tested). Honest limits: at
  most **6 strips** per shot, so a very long page is **truncated and says so** in
  the result rather than passing as complete; the stitched image is **scaled
  down** to the size that is sent, so treat it as an overview and take a plain
  viewport shot when you need to read text; the page's own scroll position is
  restored afterwards.
* **`annotate=true`** draws `read_page`'s `[ref]` numbers onto the elements they
  belong to, as a fixed overlay capped at **120 labels** so a dense page does not
  become a wall of digits. The overlay is installed before the capture and
  removed in a `finally` block, so the page is left as the user had it even when
  the capture fails. The result states how many labels were drawn — otherwise
  "no numbers in the picture" and "numbers drawn then removed" look identical to
  the model.
* **`compare_screenshots`** captures the page and compares it against the last
  screenshot taken **in this turn** — the history is scoped to the turn, so a
  comparison can never reach back to a page from an earlier conversation — and
  reports the changed fraction plus a coarse location ("upper left area"). The
  **first** call in a turn has nothing to compare against; it records the frame,
  says so, and becomes the baseline, so the sequence is screenshot → act →
  compare. The comparison runs on the pixels that were actually sent, so it
  measures what the model was shown. `tolerance` (default 12, coerced to 0–255)
  is the per-channel difference below which a pixel is not counted as changed.
* `compare_screenshots` shares the vision gate: it is refused when
  `agentVisionEnabled` is off, on a text-only provider and in a private tab,
  because it is the same capture.

**Status in this build:** implemented — the `see_page` tool with its
`full_page` / `annotate` flags, `compare_screenshots`, the `agentVisionEnabled`
switch and its settings row, the per-gateway image bodies and the text-only
refusal are all present.

### Site memory (`site_memory`) — an origin-keyed memory

A per-profile memory the agent reads and writes so it can learn how a site
works, keyed by origin.

Honest limits:

* **Origin comes from the session URL, never from page-supplied text** — a page
  cannot name the origin its notes are filed under.
* **Redaction is applied at entry**, like the console and network buffers.
* **The user can see and delete what is remembered**, per entry and per site.
* **Wiped with the profile.**
* **Off by default.**

**Status in this build:** implemented — the `site_memory` tool reads and writes a
per-profile table keyed by the tab's real origin, `AgentMemoryActivity` lists what
has been kept with per-entry and per-site delete, and the whole store stays off
until the user turns on "Let the agent remember this site".

### The step list (`plan`) — what a long run is doing

A long autonomous run is otherwise a scroll of tool cards: it says what
happened, and nothing says what the agent set out to do. `plan` is the agent's
own checklist, rendered in the panel as a card with each step marked done,
in progress or pending. The agent is told to write it before it starts, and a
turn that would change something before the list exists is answered with a
reminder instead of being carried out (twice at most, so a model that will not
plan still finishes the task).

Honest limits:

* **The stored copy is a claim, not a verification.** The last list a chat
  wrote is kept on that chat's row, so the card is there when the chat is
  reopened and the model is shown its own list again on the next turn. But
  every status in it is the model's own account of its work: a step marked done
  is the model saying so, and a turn that ends with steps still open is
  announced by a notice that repeats the count rather than proving anything.
  Nothing checks the page to confirm a step was really carried out.
* **Every call carries the whole list** — the tool REPLACES the previous one.
  A call that cannot be read (no items, an unknown status word, more than 24
  steps) is refused and the list the user is watching stays as it was, rather
  than being cleared by a malformed call.
* **A stored list that cannot be read opens as no list at all** rather than
  failing the chat: the column is one string per chat, and a chat must still
  open on a version that wrote a shape this build does not know.
* **Nothing here is page-derived**, so the card is not fenced: the steps are the
  model's own sentences about the task.
* **A refusal names the words that work**, so a model that misspells a status
  fixes it instead of retrying the same call.

**Status in this build:** implemented — the tool is in the catalogue for chat
turns and scheduled runs alike, and the card is `PlanCard` in the agent panel.
Chat turns require a list before they act; a **scheduled run does not**, because
there is no one watching a checklist and its budget is spent on the task.

### Downloads (`download_file`) — a link that is a file

When a task ends in a file rather than a page — an invoice PDF, a CSV export,
an image — the agent hands the link to the **browser's own download engine**
rather than trying to read the bytes itself. The file lands where your own
downloads do (Downloads/RoomBrowser), the transfer survives the turn, and it
appears in the downloads list with pause, resume and open.

Honest limits:

* **It queues, it does not fetch.** The call returns as soon as the download is
  queued, so the file is not on disk when the tool answers. The tool's own
  result says so, and the agent is told to check `app_data kind=downloads`
  before reporting the file as saved.
* **A write, not a read.** It goes through the same confirmation chain as a
  click (Confirm actions, or YOLO), and plan mode refuses it, because it carries
  work out rather than proposing it.
* **Refused in a scheduled run.** A run has one hidden page and no download
  engine, so it cannot queue a file; the refusal says so and names the route
  that works. A headless **chat** is different — the browser is up behind it, so
  a chat turn on a hidden page does queue the file normally.
* **Only `http(s)`.** A URL that is not one is refused rather than handed to the
  engine.

### Saving a page (`save_pdf`) — the page itself, not a link

For a receipt, a ticket, a statement or any page worth keeping as it looked,
`save_pdf` renders the page in this chat's tab and publishes the result through
the same download engine, so it lands in Downloads/RoomBrowser beside a
retrieved file. It takes an optional `name`; the browser names the file from the
page when the model suggests nothing.

The one to reach for is decided by the user's wording: `download_file` when a
link already points at a file, `save_pdf` when the page itself is what is being
kept.

Honest limits:

* **It renders, it does not fetch.** Like a download, the call returns as soon
  as the work is queued, so the tool's own result says the file is not there yet
  and tells the agent to check `app_data kind=downloads` first.
* **A write, not a read.** It goes through the same confirmation chain as a
  click (Confirm actions, or YOLO), and plan mode refuses it.
* **Refused in a scheduled run and in a headless chat.** Rendering a PDF needs a
  page that is on screen; a turn driving a hidden page has nothing to render, so
  the refusal says so and names the route that works.
* **Engine-dependent.** GeckoView renders the page itself and the file is real.
  WebView has no PDF writer of its own, so the tool refuses there and names the
  route that does work — the **Save as PDF** row in the page-actions sheet,
  which opens the system print dialog with Save as PDF among its destinations.

### Deeper Developer Tools reach

New agent tools that read the **same buffers the Developer Tools panels
display**, not a second capture: `devtools_storage`, `devtools_security`,
`devtools_elements`, and headers in `devtools_network`. The existing
`devtools_console`, `devtools_network`, `devtools_inspect`, `devtools_diagnose`
and `devtools_report` already read the panel's own inspector session, which is
what keeps the agent and the panel from disagreeing — the agent sees what the
panel shows, redaction included.

Honest limits:

* **On the WebView edition, response status and headers are not observable.**
  WebView reports a request to `shouldInterceptRequest` before it is sent and
  never hands back the response, so response headers stay absent here (see
  `SECURITY.md`). Any header the agent can read on this edition is a *request*
  header, redacted at entry.
* **The agent's storage report has no cookie section.** Cookies are a section of
  the Application panel, but reading them is a separate permission
  (`devtools.cookies`) that names no tool yet, so the tool prints the panel's
  report without that section rather than claiming the cookies were examined.
* **`devtools_security` has its own permission** (`devtools.security`), so it can
  be withheld on its own. The certificate section says which edition it is on:
  WebView cannot read a certificate and prints that, GeckoView prints the real
  one.
* **`devtools_report` is gated on the browser-wide "Enable network diagnostics"
  setting, which is off by default.** With it off the tool is refused and
  collects nothing, so turn it on in Browser Settings → Diagnostics first.

**Status in this build:** implemented — `devtools_storage`, `devtools_security`
and `devtools_elements` read the panels' own inspector session, and
`devtools_network` prints the entry-time-redacted request and response headers.

## File attachments in the composer

The "Include page" chip turns green and shows a check icon while the
current page is included in the next turn. Next to the chip there is an
attach (📎) button: pick any files from the device (SAF picker,
multi-select). Text-like files (txt, md, json, csv, xml, yaml, html,
source files… up to 256 KB each) are read and inlined into the turn's
prompt (capped at 20 000 chars per file and ~60 000 chars per turn);
binary files contribute name/size metadata only. Attached chips can be
removed before sending; the user bubble and the persisted chat row list
the file names. Content-URIs are not persisted across process death —
attachments live until sent.

## Copy any chat message (re-use prompts)

Every bubble in the conversation carries a small copy icon (⧉) underneath:
tap it and the bubble's exact text lands on the system clipboard, with the
icon flipping to a check plus a tiny "Copied" label as feedback (~1.8 s).
This works for **your own previously sent prompts** — copy one, paste it back
into the composer and re-process it with the agent (optionally on a different
provider/model) — and for **finished assistant answers** (summaries, extracted
data, generated text) you want to reuse elsewhere. Icons are deliberately not
shown on half-streamed answers, so you can never copy a truncated reply.
Long-press selection is not needed; the affordance is one visible, tappable
icon per message.

## Agent settings (own activity)

AI settings, the provider editor and the chat history each run as their own
**activity** — a real window with its own back-stack entry, keyboard
handling and edge-to-edge insets (nothing ever overlaps the system
Back / Home / Recents buttons). They run in the default process (no WebView)
and share state with the live agent in the `:browser` process through the
Room database (multi-instance invalidation) — every provider or setting
change is picked up by the running browser instantly.

* **Add / edit provider** — its own activity (`AgentProviderEditorActivity`):
  presets, manual base URL + API key, live model discovery from `/models`.
* **Show AI Agent button** — visibility of the floating button (hidden by
  default; also switchable from Browser Settings).
* **Confirm actions** — require Allow/Deny approval before every click,
  type or submit (off by default = fully autonomous within the step budget).
  The prompt also offers **Allow on this site** — this tool, on this origin,
  until the chat ends — and **Always allow**, which turns YOLO on.
* **Local decision gate** — hand the *which* actions need a human to a local
  Ollama decision model, so Confirm actions stops interrupting you for
  routine work. See below.
* **YOLO: always allow** — never ask. This is not a faster policy, it is the
  absence of one: the decision gate is not consulted and no prompt appears,
  on **every** provider, Ollama included. See below for what it costs you.
* **Include current page by default** — attaches a page snapshot to the
  first message of each turn (the switch shows green when on, matching the
  panel chip's included state).
* **Temperature** (0–1) and **max steps per turn** (1–200, default 75,
  typed rather than slid) — the step budget bounds cost and runaway loops;
  when exhausted the agent is asked once more, without tools, to produce a
  final answer. The stored value is clamped by the loop, so the ceiling is a
  guard rather than a preference.
* **System prompt override** — replace the built-in browsing-agent prompt.
* **Data & privacy** — delete all agent chats (every profile; providers
  are kept).

## Local decision gate (Ollama decision models)

The approval control used to be one switch: confirm *everything*, or confirm
*nothing*. Neither is a policy — confirming everything trains you to tap
Allow without reading, and confirming nothing gives an autonomous agent the
run of the machine. The **Local decision gate** answers the question the
switch cannot: *which* actions need a human.

It does that with an Ollama **decision model** (`nimble`, `tev1`,
`tev1:0.8b`), through the `POST /v1/systemone` endpoint added in **Ollama
0.35**. A decision model is not a chat model: it has no reasoning step, no
streaming and no tools, and it never writes prose. You give it a piece of
text and a typed question, and it answers with a choice and a probability.
Ollama's own figures put a decision at ~91 ms once the model is loaded,
which is fast enough to sit in front of every action the agent takes.

What the app sends it, for each action:

| Field | Value |
|---|---|
| `state.agent_action` | the action's human label, e.g. `Click [12] Sign in` |
| `state.page_url` / `state.page_title` | where the agent is (both capped at 300 chars) |
| `questions.action` | a `choice`: `allow`, `confirm` or `deny` |
| the question's `instructions` | **your policy text**, or the built-in one |

The three answers mean three different things:

* **allow** → the action runs. This is the point of the feature: an action
  the blanket rule would have stopped to ask about now simply happens.
* **confirm** → the model declined to decide alone; you get the normal
  Allow/Deny prompt.
* **deny** → the action is refused, and the *reason* travels back to the
  model as the tool result, so it changes course instead of repeating the
  same call.

Anything the gate cannot use — no answer, an option it does not recognise, a
probability under 60%, an unreachable server, a server older than 0.35 —
becomes the Allow/Deny prompt. It never becomes an allow.

### What it is not

**It is not a security boundary.** The judge is a 9B model reading prose you
wrote. A page can try to talk it out of a decision, it has no idea what "the
account" actually refers to, and `confidence` in its answer measures how
*concentrated* the probabilities are, not how likely the answer is to be
right — the gate uses the chosen option's own probability for that reason.
The gate reduces how often you are interrupted. The guarantee comes from
Confirm actions, which is why the two switches sit next to each other.

**It needs a local Ollama.** `/v1/systemone` only exists on a server holding
the weights: the endpoint scores answer tokens directly, so Ollama refuses
cloud, MLX and Safetensors models for it. A hosted provider — Z.ai, OpenAI,
OpenRouter, AgentRouter, Anthropic — therefore *cannot* serve the gate, and
only `OLLAMA`-protocol providers are offered in the picker. Point it at the
Ollama 0.35+ on the same phone (Termux) or on your LAN, and pull a decision
model first:

```bash
ollama pull nimble      # 9B, Bespoke Labs — the strongest of the three
ollama pull tev1        # 4B, Together AI (experimental)
ollama pull tev1:0.8b   # 0.8B — for a small phone
```

**With Confirm actions off, an unreachable gate means actions run ungated**,
exactly as they did before this feature existed. That is a deliberate
choice — a browser that stops working because Ollama is down would be worse
— but it is a real consequence, so it is stated here, on the settings screen
and in `SECURITY.md`. If you want a hard guarantee, turn Confirm actions on:
the two rules compose, and the gate only ever *adds* a decision.

The action text and the page's URL and title are the only things sent, and
they go to your own machine. Nothing about the gate leaves the device.

## YOLO: always allow

Every prompt in the agent has a third answer next to Allow and Deny:
**Always allow**. It is not "allow this one too" — it switches the asking
off, for every action, from that moment until you turn it back on.

Between the two sits **Allow on this site**: the same tool, on the same
origin, until the chat ends. It is what a person means when the same prompt
has arrived for the third time on the same form, and it is deliberately
narrower than YOLO. It is withheld for an action that cannot be undone, for
a wallet request, for a live authenticator code, and for a page with no
origin — the cases where "this site" would be a promise about nothing.

It exists because the alternative is worse. A person who is being interrupted
by a prompt they do not want to read has two options without it: tap Allow
without reading every time (which is what the prompt was supposed to prevent),
or go into settings and turn Confirm actions off, which also stops the gate
that was doing useful work. YOLO is the honest version of "stop asking me",
said once, at the moment the person actually means it.

What it does, precisely: **both** rules are bypassed. The local decision gate
is not consulted — no request goes to Ollama — and the Confirm actions prompt
is not shown. Every click, keystroke, form submission and `auto_*` post runs
immediately, on any site, including ones where you are signed in.

Three things make it visible, because a setting whose "on" state looks
identical to the app working normally is the dangerous kind:

* the chat says so the moment it is turned on;
* the chat says so again at the start of **every** turn while it is on;
* the settings screen shows a red paragraph under the switch, rendered only
  while the switch is on.

It applies to **every** provider, Ollama included, because it is a local
decision and needs no model. That is the difference between it and the
decision gate, which only Ollama can serve.

Turning it off restores both rules exactly as they were; nothing else about
the agent changes. `SECURITY.md` states plainly that it is the one setting
here with no guarantee attached to it at all — the guarantee was the prompt,
and YOLO is what you get when you ask for the prompt to stop.

## Privacy model (honest)

* Agent requests go **directly** from the device to the configured
  provider. Room Browser adds **no proxy, no telemetry, no analytics**.
* API keys are encrypted at rest (AndroidKeyStore) and are only ever sent
  as the `Authorization: Bearer` header **to the provider you configured**.
* Chat history is stored locally in Room, **scoped to the profile** that
  produced it (switching profiles switches agent history too).
* Page snapshots sent to the provider contain visible page text and links —
  decide for yourself whether that is acceptable for the sites you visit.
* The agent inherits the profile's ad/tracker blocking: blocked requests
  never reach the provider or the page.
* `node_run` is the one tool that runs a program rather than reading a page,
  and its process is not a sandbox: it shares this app's UID and data
  directory. What it sends to the network is whatever the script chooses,
  under this profile's identity — so the honest statement is that it can reach
  anything this app can, not that it is contained.
* `node_packages` installs what that program can `require`. It talks to the
  public npm registry and nothing else, never runs a package's own install
  script, and writes into the app's shared data directory rather than a
  per-profile one.

## Architecture

```
core:domain (pure JVM, 100% unit-tested)
├── AgentDtos          OpenAI-compatible DTOs + ModelListParser
├── OpenCodeDtos       opencode /provider + text tool-call parsers + wire bodies
├── SseParser          line-oriented SSE parsing
├── AgentTools         tool catalogue + JSON schemas + snapshot formatter
├── AgentPrompts       built-in system prompt
├── AgentLoop          plan → act → observe → repeat (bounded by maxSteps)
└── SystemOne          decision-model wire (POST /v1/systemone) + ActionGate

app (:browser process — owns the rendering engine)
├── OkHttpAgentGateway     SSE streaming, tool-call delta assembly, /models
├── OpenCodeAgentGateway   `opencode serve` bridge (sessions + polling)
├── AgentGateways          picks the transport from the provider protocol
├── SystemOneClient        the decision endpoint (no streaming, no tools)
├── PageInjector           JS: element tagging, snapshot, click, fill, enter
├── AgentToolExecutor      tools against the live BrowserViewModel engine
├── BrowserAgentController chat state, sessions, approvals, persistence
├── KeyStoreCrypto         AES-256-GCM for API keys
└── agent/ui/*             AgentPanel (pill + chat)

app (default process — agent settings activities, no rendering engine)
├── AgentProviderStore     shared provider validation + encryption
├── AgentSettingsController settings/providers persistence via Room
└── agent/ui/*             AgentSettingsActivity, AgentProviderEditorActivity,
                          AgentSessionsActivity (own windows)

node (libnode.so + the bridge; its own `:node` process at run time)
├── NodeBridge             JNI entry into node::Start
├── NodeRuntime            copies the entry script out of the APK and runs it
├── NodeClient             starts the process, connects, one JSON line per call
├── NodeRunner             one call at a time, as this profile's identity
└── assets/node/bridge.js  the runtime side: status + run, and the UA rule
```

`node/` names no engine type, so it is shared between the editions byte for
byte; only the packaging in `app/build.gradle.kts` is engine-specific.

Room schema **v3** added the `protocol` column to `agent_providers`
(`OPENAI` | `OPENCODE`, default `OPENAI`); **v10** added
`agent_sessions.tab_id` plus its `(profile_id, tab_id)` index, which is what
makes chats per-tab. All of these are lossless additive migrations (v1→v2
agent tables, v2→v3 protocol, v9→v10 tab chats) — existing chats get the `""`
tab and stay in the history list. Providers are app-global credentials;
sessions are scoped to a profile and a tab.

## Tests

* **Domain (JVM)**: `AgentLoopTest` (loop semantics, tool failures, step
  limit, history trimming), `SseParserTest`, `AgentDtosTest`
  (wire format, model-list shapes, snapshot formatting),
  `SystemOneTest` (the decision wire: the three question shapes, lenient
  parsing of every answer type, and the verdict rules — including that an
  unsure, missing, mistyped or unrecognised answer asks the user rather than
  allowing the action),
  `OpenCodeParsersTest` (text tool-call extraction: fenced/bare/actions
  forms, non-tool JSON passthrough; `/provider` model flattening; wire
  bodies).
* **App (JVM)**: `AgentGatewayTest` — real MockWebServer round-trips for
  SSE streaming, tool-call delta assembly, reasoning passthrough,
  non-stream fallback, auth headers, `/models` shapes and error mapping.
  `SystemOneClientTest` — the same for the decision endpoint: the request the
  gate sends, each answer type read into a verdict, and that a 404, a 413, a
  200 with nothing readable and an unreachable server all *throw* instead of
  arriving as "no objections".
  `OpenCodeAgentGatewayTest` — session creation, delta-only messaging,
  polled assistant replies with tool-call extraction, `/provider` model
  listing with endpoint fallback, timeout mapping.
* **Node (JVM)**: `NodeProtocolTest` — the wire format, hand-written in the
  shape `bridge.js` writes it rather than produced by the encoder under test:
  the identity travelling with every command and a blank one being left out
  rather than sent empty, the runtime's own refusal raised with its words, an
  answer to another request being refused, and a script that threw arriving
  inside a *successful* reply, because the envelope's outcome is the runtime's
  and the script's is its own.
* **E2E (emulator)**: `AgentSettingsE2eTest` — drives the real app UI
  across both processes: opens the agent panel from the page menu (the
  pill is hidden by default), adds a provider via the settings/editor
  activities against a local MockWebServer, fetches its model list,
  verifies the saved selection in the panel, exercises the Show/Hide AI
  Agent button toggle (off → pill hidden, on → pill shown, off → hidden),
  flips the Local decision gate and YOLO switches and checks that YOLO's
  warning paragraph actually renders while it is on,
  and opens the chat-history activity.
  `AgentTabSessionTest` — the per-tab chat queries against a real Room
  database: a tab resolving to its OWN conversation and not another's, a
  detached chat staying in the history list, adopting a chat moving it off
  the tab that had it, the same tab id under two profiles staying two
  conversations, and a blank tab id never resolving to an unbound chat.
  `NodeRuntimeE2eTest` — the embedded runtime in the APK that ships it: the
  process starts and boots, it echoes the identity it was handed, a script's
  value comes back, a throw arrives as a readable failure rather than a broken
  runtime, a script that outlives its budget is stopped and says so, and a
  script's own HTTP request to a loopback server inside the script carries the
  profile's user agent on the wire. Nothing there asserts on an ABI the APK
  carries no `libnode.so` for — the release ships arm64-v8a, armeabi-v7a and
  x86_64, so a 32-bit x86 device gets the honest "unavailable" answer.

## Tips

* Start with cheap/fast models (e.g. `glm-4-flash`, `groq/llama-3.1-8b-instant`,
  local Ollama models) — autonomous browsing spends many turns.
* Enable **Confirm actions** the first time you let it log in anywhere.
* Ask for sources — the built-in prompt requires the agent to cite URLs.

## OpenCode backend (`opencode serve`)

Besides any OpenAI-compatible API, an AI provider can be an **OpenCode
server**: run `opencode serve` on your own PC/NAS/VPS (default port 4096)
and add it in *Add AI provider* with type **OpenCode server** (or pick the
"OpenCode (opencode serve)" preset). The model list is fetched live from
the server's `GET /provider` endpoint and shown as `providerID/modelID`
chips (e.g. `anthropic/claude-sonnet-4`).

How the bridge works (`OpenCodeAgentGateway`):

* Each agent turn creates an opencode session (`POST /session`) and posts
  only the messages not yet sent (`POST /session/{id}/message`) — the
  server-side session keeps the context, so nothing is re-sent; tool
  results travel as labelled `TOOL RESULT` messages.
* Replies are read by polling `GET /session/{id}/message`; newly appearing
  assistant text is streamed into the chat UI in deltas, so long
  generations still feel live.
* opencode has no browser tools, so the first message of a session teaches
  the model a **text tool-call protocol**: it replies with a fenced JSON
  block (`{"tool_calls":[...]}`), which the gateway parses into real tool
  calls — the same `AgentLoop` then executes them against the WebView
  (navigate/click/fill/…, auto_reply/like/repost/post). One loop, two
  transports.
* Endpoint shapes are parsed leniently and fall back across opencode
  versions (`/provider` → `/config/providers` → `/models`), so minor API
  drift degrades to a clear error instead of a crash.

Security notes:

* Keep `opencode serve` on a **private LAN or VPN (Tailscale/WireGuard)** —
  never expose port 4096 directly to the internet.
* The optional API key is stored encrypted (AndroidKeyStore) and sent as
  `Authorization: Bearer` to your server only.
* The agent executes browser actions **on the phone**; opencode's own
  terminal/file tools run on the machine hosting `opencode serve` — Room
  Browser never exposes them to the model.
