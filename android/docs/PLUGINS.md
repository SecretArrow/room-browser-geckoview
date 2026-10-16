# Room Plugins — Chat Plugin Developer Guide (API version 1)

A Room Browser **chat plugin** is a plain-JavaScript Node module you install
from a ZIP file. Enabled per chat, it runs inside the app's embedded Node
runtime when the chat does something — a message sent, a command typed, a
tool the AI agent calls — and answers with **cards** in the conversation,
its own `/commands`, and its own agent tools.

This is the reference for the whole system: the package format, the manifest,
the permissions, the `room` SDK, the events, the caps, the packaging tools and
the desktop harness. The two complete example plugins live beside this file
(`docs/plugins/hello-world`, `docs/plugins/word-count`) and are annotated at
the end of this document. The prose is dense on purpose; every number in it is
a number the implementation enforces.

An honest summary before anything else: a plugin runs code you wrote on the
user's phone, inside this app. The system's answer to that is a capability
sandbox, per-chat isolation, explicit per-chat consent, and quotas — and a
list of things the sandbox is *not*, stated in the next section rather than
left for you to discover.

## What a plugin is — guarantees and honest limits

**The guarantees:**

* **Per-chat isolation.** A plugin is enabled for one chat at a time, and
  everything it can reach — storage, config, conversation — is scoped to that
  chat. There is no API that names another chat; see "Storage and config" for
  the on-disk layout that makes this structural rather than checked.
* **A capability sandbox.** Plugin code runs in a `vm` context with no
  `fs`, `net`, `process` or any I/O module in reach. `require` is a shim over
  an allowlist of built-ins plus the plugin's own directory. Network goes
  through the host, checked against the manifest's allowlist **before** every
  request and again on every redirect. Storage is a kv store and a files
  directory under a per-chat root the plugin cannot name.
* **Explicit permissions, denied by default.** The four capabilities that can
  leave the call or read the chat — `storage`, `network`, `agent.tools`,
  `chat.context` — are off until the person enabling the plugin in that chat
  grants them, with the network hosts listed in the dialog. Everything else
  (cards, config, logs, util, console, timers) is inert by construction and
  needs no declaration.
* **Read-only code, per-call teardown.** The plugin's extracted directory is
  never writable through any API the sandbox offers, and the runtime is
  started for one event and torn down after it: timers die with the call, the
  module cache is per-call, nothing of the plugin outlives its answer.
* **Agent tools ride the agent's gates.** A plugin's tools join the agent
  catalogue under a `plugin_…` name and are gated like every other action the
  model can take: Plan mode refuses them, the profile's withheld
  `agent.plugins` permission holds them back, they spend the turn's action
  budget, and scheduled and headless runs refuse them outright. Their output
  is fenced as untrusted data before the model reads it.

**The honest limits:**

* **The `:node` process is not a security boundary.** It shares the app's UID
  and data directory (the same position `node_run` states in
  `AI_AGENTS.md`). The plugin sandbox is a *capability shell on top of* that
  process, not a second OS boundary: what stands between a plugin and the
  app's own files is the absence of APIs and the require allowlist, not a
  kernel. A plugin you did not audit is code you are running with this app's
  identity — treat enabling one the way you'd treat installing an app.
* **No native addons.** `*.node`, `*.so`, `*.dll`, `*.exe`, `*.bat`, `*.sh`,
  `*.jar` and `*.apk` entries are refused at install (and by the packer).
  Native code would load into the runtime's own process and out of the
  sandbox's reach entirely; the sandbox's promises are about JavaScript.
* **No persistent execution.** Plugins are event-driven functions. Between
  events nothing of yours runs: no timers survive, no sockets stay open, no
  background work exists. State persists only through the kv store and files.
* **Pure JavaScript, bundled dependencies only.** `node_modules` inside the
  ZIP is fine and resolved plugin-relative. Pure-JS npm packages work; see
  "Bundling npm dependencies".
* **No marketplace.** Sideload a ZIP through the system file picker; that is
  the only door.
* **API version 1 only.** A manifest declaring any other `api` is refused at
  install with the supported number in the message. Unknown manifest fields
  are errors, not surprises.
* **Android only in this iteration.** The desktop edition does not run
  plugins.

## Five-minute quickstart

You need the repo (for the tools and the example) and either an Android
device with the app or just Node ≥ 18 on a desk.

1. **Copy the example.** `android/docs/plugins/hello-world/` is two files:
   `plugin.json` (the manifest) and `main.js` (the entry). Copy the directory
   and change the `id`, `name` and `description` to yours — the id must be
   lowercase, dotted, like `com.you.greeter`.

2. **Zip it deterministically.** From the repo root:

   ```sh
   python3 android/tools/make_plugin_zip.py my-greeter out.zip
   ```

   The packer validates the manifest and the tree, and writes a
   byte-reproducible archive (every entry dated 1980-01-01, sorted, no extra
   fields). A source that breaks a rule is refused here with the reason, not
   on the phone.

3. **Install it in the app.** Open the agent panel → puzzle-piece button (or
   `/plugins`) → **Install from ZIP…**, and pick `out.zip` with the system
   file picker. Or use **Manage plugins** → **Install from ZIP**. The manager
   also ships a one-tap **Install the bundled example** (the same
   hello-world, pre-zipped in the APK).

4. **Enable it in a chat.** Type `/plugins`, flip the plugin's switch, and
   accept the permission dialog (hello-world asks for **storage**). The sheet
   closes and the plugin's activation card lands in the conversation.

5. **Use it.** Type `/greet Room` — the plugin answers with a card and a
   counter that persists in that chat's own storage. A brand-new chat starts
   with the plugin off; that is the isolation model working.

For editor support while writing `main.js`, copy
`docs/plugins/sdk/plugin-sdk.d.ts` next to it and put `// @ts-check` on the
first line — the typings describe every `room` method with its caps, and the
file's header shows the one-line annotations that light them up. Nothing
loads that file at runtime; it exists for your IDE.

Before touching a device at all, you can run every event on a desk:

```sh
node android/tools/plugin_dev.js my-greeter activate --perms storage
node android/tools/plugin_dev.js my-greeter command '{"name":"greet","args":"Room"}' --perms storage
```

See "The desktop dev harness" for the full flag set.

## The package (ZIP)

```
plugin.json          required, at the archive root, ≤ 64 KB
main.js              the entry — any relative .js path the manifest declares
…                    your own modules; a bundled node_modules/ is allowed
```

Install-time guards, all enforced by the installer (and mirrored by the
packer where a source tree can prove them):

| Rule | Limit |
|---|---|
| Entries | at most **512** |
| Total uncompressed size | at most **20 MB** |
| Per entry | at most **2 MB** |
| The ZIP's own (compressed) body | at most 25 MB — padding is not content |
| Entry names | UTF-8, 1..200 chars, relative, forward slashes, no `..`, no control characters |
| Forbidden suffixes | `.node` `.so` `.dll` `.exe` `.bat` `.sh` `.jar` `.apk` |
| Symlinks / special files | refused (the central directory's unix mode is where one announces itself) |
| Manifest placement | `plugin.json` at the archive root — OR at the root of the **one** folder a zip may wrap everything in (rebasing on it); anything deeper is refused |

Extraction is atomic: a temp directory is filled, every rule is re-checked
against the bytes actually copied, and only then is it renamed into place. A
failed install leaves the previous version running and nothing else. A
reinstall of the same id replaces the row and the directory; the chats that
had it enabled keep their bindings, and a grant that no longer covers the new
manifest's permissions is re-consented at the chat, not silently widened.

## `plugin.json` — the manifest reference

```json
{
  "id": "com.example.hello",
  "name": "Hello World",
  "version": "1.0.0",
  "description": "A tiny example plugin.",
  "author": { "name": "Jane Doe", "email": "jane@example.com", "url": "https://example.com" },
  "license": "MIT",
  "api": 1,
  "entry": "main.js",
  "permissions": ["storage", "network", "agent.tools", "chat.context"],
  "network": { "allow": ["api.example.com", "*.example.com"] },
  "config": {
    "schema": [
      { "key": "greeting", "type": "string", "default": "Hello",
        "title": "Greeting word", "description": "Word to greet with" }
    ]
  },
  "commands": [ { "name": "greet", "title": "Greet", "description": "Say hello" } ],
  "hooks": ["message"]
}
```

Every field, every rule. The validator reports **all** violations at once —
the install refusal lists every reason, so a manifest that breaks three rules
is fixed in one pass, not three.

| Field | Required | Rules |
|---|---|---|
| `id` | yes | `^[a-z][a-z0-9]*(\.[a-z0-9-]+){1,4}$`, 3..64 chars. At least one dot (namespacing is forced). Lowercase only. |
| `name` | yes | 1..64 printable characters, no control characters. This is what the chat, the sheets and the grant dialog show. |
| `version` | yes | `^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$` — plain semver, an optional prerelease. |
| `description` | no | 0..500 chars. |
| `author` | no | object; `name` 1..64; `email` / `url` 0..200; `url` must start `http(s)://` when present. |
| `license` | no | 0..64 chars. |
| `api` | yes | the integer `1`, exactly. Any other number is refused with this build's version in the message. |
| `entry` | yes | relative `.js` path, 1..200 chars, no `..`, no absolute path, no backslash; must exist in the package. |
| `permissions` | no | array, unique, each one of `storage` `network` `agent.tools` `chat.context`, at most 4. |
| `network` | see rule | object with `allow`: 1..20 host patterns. **Required (non-empty) iff `permissions` contains `network`** — and refused as a surprise when it does not. |
| `config.schema` | no | 0..12 items; `key` `^[a-z][a-z0-9_]{0,31}$`, unique; `type` one of `string` `number` `boolean`; `default` (when present) must match the type — absent means the type's zero value (`""` / `0` / `false`); `title` 0..64; `description` 0..200. |
| `commands` | no | 0..12 items; `name` `^[a-z][a-z0-9_-]{0,23}$`, unique, **must not be `plugins`** (reserved by the app); `title` 0..64; `description` 0..200. |
| `hooks` | no | a subset of `["message"]`, unique, at most one entry. |
| unknown fields | — | **rejected.** The API is versioned by `api`; a manifest carrying a field this build never heard of fails loudly at install rather than silently misbehaving. |

**Network host patterns** (each is checked independently):

* lowercase only; no `/`, `:`, spaces or `\`;
* either an exact hostname (`api.example.com`) or `*.domain` — where
  `*.example.com` matches `example.com` **and any subdomain**;
* `*` may appear only as the leading `*.`; no other wildcards;
* **IP literals are rejected** — v1 matches hostnames, and a hostname
  allowlist says nothing about an address (an IPv6 literal is refused anyway
  by the `:` rule);
* **single-label hosts are rejected, `localhost` included** — the allowlist
  is for reaching the internet, and an intranet literal is not what a consent
  dialog should be naming;
* each label must be a DNS label (`a-z0-9` and interior `-`).

The hostname allowlist is not IP pinning: DNS resolution is the runtime's
own, so the guarantee is "the name you dial is one the plugin declared",
not "the address you land on". A redirect is re-checked against the same
list before it is followed.

## Permissions

| Permission | What it grants | The sentence the grant dialog shows | Default |
|---|---|---|---|
| `storage` | `room.storage` — the kv store and files under **this chat's** data root | "Store data for this chat" | denied |
| `network` | `room.net.fetch`, restricted to the manifest's allowlist | "Reach the internet (hosts: …)" — the hosts listed, cut at ~120 chars | denied |
| `agent.tools` | `exports.tools` are registered as agent tools **in this chat** | "Add tools the AI agent can call" | denied |
| `chat.context` | `room.chat.history()` / `summary()` for the **current** chat only | "Read this chat's conversation" | denied |

**Implicit — no declaration, no dialog:** `room.meta`, `room.config`,
`room.ui` (cards), `room.log`, `room.util`, `console`, timers. None of those
can leave the call, so there is nothing to consent to.

The semantics you code against:

* **Deny by default.** A plugin with no grants runs, shows cards, reads its
  config — and nothing else.
* **Namespaces exist un-granted.** `room.storage` is always an object; every
  call on it throws `Error("plugin permission not granted: storage")` until
  the chat granted it. A plugin that forgot a permission reads exactly which
  one, instead of a missing key and a guess.
* **Effective set = declared ∩ granted.** The app intersects the manifest's
  asks with what this chat's person accepted before every call, and sends
  only the intersection; the host enforces the real capability regardless. A
  stored grant naming something the manifest never declared is inert by
  construction.
* **Grants persist across off/on.** Switching a plugin off keeps its
  grants — switching off is not a re-consent, and switching back on is
  silent.
* **Re-consent only when the declared set grows.** Upgrading a plugin whose
  manifest asks for one more permission makes the next enable raise the
  dialog again, for the whole declared set. Grants that already cover the
  declaration never re-ask.

## The entry (`main.js`, CommonJS)

```js
'use strict';

// Top-level code runs on EVERY event — the module is loaded fresh per call
// (the module cache is per-call). Keep it cheap; do real work in handlers.

exports.activate = async (room) => {};            // optional — enable-time
exports.deactivate = async (room, reason) => {};  // optional — see reasons below
exports.onMessage = async (room, message) => {};  // optional — requires hooks:["message"]
exports.onConfig = async (room) => {};            // optional — after config edits

exports.commands = {                              // keys MUST match manifest command names
  greet: { run: async (room, args) => {} },       // args = the raw text after the command word
};

exports.tools = {                                 // requires the agent.tools permission
  count_words: {
    description: 'Count the words in a text',     // 1..500 chars
    parameters: {                                 // a JSON Schema object (see "Agent tools")
      type: 'object',
      properties: { text: { type: 'string' } },
      required: ['text'],
    },
    run: async (room, args) => '3 words',         // args = the parsed JSON arguments
  },
};
```

* **CommonJS only.** `module.exports` / `exports.*`; no ESM.
* **`deactivate` receives `(room, reason)`** — `reason` is today
  `"disabled"` (switched off for the chat) or `"chat-deleted"` (the chat, or
  all chats, is going). The wire vocabulary also reserves `"uninstall"`,
  which this build does not send: an uninstall deletes code and data without
  a goodbye. A handler written as `(room)` still works — the second argument
  is additive.
* **`onMessage` receives the event payload** — an object `{ text }` carrying
  the user's message (the manifest must declare `hooks: ["message"]`, and
  the probe must find `onMessage` exported, or the hook is not registered).
* **Handler return values** (everything but `tool`):

  | Return | Effect |
  |---|---|
  | nothing (`undefined`/`null`) | no card — the command path says "ran and showed nothing" |
  | a string | one text card |
  | a card object | that card (validated and clamped) |
  | an array | each element by these same rules, in order |

  A `tool` handler's return value is the **tool result itself**: a string
  passes as it is, anything else is JSON-stringified, both capped at
  20 000 chars.
* **Throwing is an answer, not a crash.** A handler that throws fails that
  call: the chat shows a one-line notice (or the tool result carries the
  failure), and nothing else breaks. The failure text is the message plus a
  trimmed stack, ≤ 4000 chars.
* **`commands` keys must match the manifest** — command routing reads the
  *manifest's* declarations (the person consented to a plugin that promised
  `/greet`), while the dispatcher looks up `exports.commands[name].run` at
  run time. A declared command with no matching export fails that one call,
  not the plugin.

## The `room` SDK

The object every handler receives. Frozen, and the same instance for the
whole call.

```
room.meta      { pluginId, pluginName, pluginVersion, sessionId, apiVersion, event }
room.config    { get(key) }
room.storage   [storage]   .kv { get, set, delete, clear, keys, size }
                           .files { read, readBytes, write, writeBytes, append, delete, exists, list, size }
room.net       [network]   .fetch(url, options) -> Promise<response>
                           .allowed(host) -> bool
room.chat      [chat.context] .history(limit?) -> [{ role, text }]
                               .summary() -> string
room.ui        { card(cardObj), text(str) }
room.log       { info(m), warn(m), error(m) }
room.util      { now(), uuid(), b64encode(str), b64decode(b64) }
```

### `room.meta`

`{ pluginId, pluginName, pluginVersion, sessionId, apiVersion, event }` —
plain strings/number, no methods. `sessionId` is the chat's session id (the
host reads it out of the injected data-root layout; it is null only in a
harness that was handed an arbitrary root). There is no `process` in the
sandbox — `room.meta` replaces it.

### `room.config`

```js
room.config.get('greeting')   // string | number | boolean — the manifest default
                              // merged with this chat's overrides; undefined
                              // for a key the schema never declared
```

Values arrive per call; the merge (schema defaults + the chat's overrides,
declared keys only) is done by the app before the call. On the `config`
event the new values are already merged before `onConfig` runs — the hook is
the chance to react, not the only way to see them.

### `room.storage.kv` — requires `storage`

```js
room.storage.kv.get(key)        // the JSON value, or null
room.storage.kv.set(key, value) // value must be JSON-serializable
room.storage.kv.delete(key)
room.storage.kv.clear()
room.storage.kv.keys()          // all keys
room.storage.kv.size()          // count
```

Caps, enforced per `set` with the whole store in mind: **values ≤ 16 KB**
(as encoded JSON), **≤ 128 keys**, **the whole store ≤ 256 KB** of JSON.
Exceeding any throws `plugin kv quota exceeded: …` with the number that
broke. The store is `kv.json` under the chat's data root, written atomically
(temp file + rename), so a crash cannot half-finish it. It survives between
calls and between app restarts, per chat.

### `room.storage.files` — requires `storage`

```js
room.storage.files.read(name)        // utf8 text, or null
room.storage.files.readBytes(name)    // base64, or null
room.storage.files.write(name, text)
room.storage.files.writeBytes(name, base64)
room.storage.files.append(name, text)
room.storage.files.delete(name)
room.storage.files.exists(name)       // bool
room.storage.files.list()             // sorted names
room.storage.files.size(name)         // bytes, or null
```

File names must match `^[A-Za-z0-9._-]{1,96}$` — no separators, no `..`; a
bad name throws before anything is touched. Caps: **≤ 256 files**,
**≤ 10 MB total** in the directory, measured from disk on every write (the
quota is about what is there, not what this call remembers). Files persist
per chat exactly like kv.

### `room.net` — requires `network`

```js
const res = await room.net.fetch('https://api.example.com/v1/x', {
  method: 'GET',            // default GET; uppercased
  headers: { Accept: 'application/json' },  // string or number values
  body: '…',                // a string; sent unless GET/HEAD
  timeoutMs: 15000,         // ≤ 15000; default 15000
});
// res: { ok, status, headers, body, json() }
//   ok       200..299
//   status   the HTTP status
//   headers  flat object, multi-values joined with ", "
//   body     the response text
//   json()   the parsed body, or null if it is not JSON

room.net.allowed('api.example.com')  // bool — the allowlist matcher, exported
                                      // for you to check before a fetch
```

The rules the host enforces:

* **http and https only** — anything else is refused before a socket opens.
* **Allowlist before the request and on every redirect.** A hop to a host
  the manifest never named is a hop refused. At most **2 redirects**
  (301/302/303/307/308); 303 always becomes GET, and 301/302 become GET for
  anything that was not a GET — the same lossy history browsers have.
* **Response body capped at 2 MB** — the response is truncated and a warn
  log says so, rather than the call failing.
* **≤ 6 MB of response bytes per call in total** across every fetch.
* **15 s timeout per request**, the ceiling and the default.
* On the device, requests carry the **profile's user agent** (the same
  identity the app's pages send — a plugin cannot announce Node). A profile
  with no identity at all makes no request: the bridge refuses an
  identity-less fetch rather than letting Node introduce itself.
* DNS is the runtime's plain resolution — the hostname allowlist is not IP
  pinning (see the manifest section).

A refusal reads `room.net.fetch refused '<host>': the plugin's network
allowlist does not include it`; a quota reads `plugin network quota
exceeded: …`.

### `room.chat` — requires `chat.context`

```js
room.chat.history()      // [{ role: 'user' | 'assistant', text }] — newest LAST
room.chat.history(20)    // the last 20; the limit caps at 200
room.chat.summary()      // "user: …\nassistant: …" — the last 4000 chars
```

What a grant buys: **the current chat's conversation only** — user and
assistant turns; tool rows and system content are the app's business, not
context. The app shapes what it sends (the last **60 entries**, whole from
the newest end, up to **12 000 chars** — the one entry left over when the
cap cannot fit two is clamped, not dropped) and sends it **only when
`chat.context` is in the effective grant set**; without the grant the field
is absent and `history()` answers `[]`, `summary()` answers `""`. Other
chats are unreachable by construction: no API names one, and the data roots
are per chat.

> **Status in this build:** the Kotlin side shapes and sends the history
> exactly as above, and the host consumes it when it arrives — but the
> bridge's `plugin` branch does not yet relay the `history` field to the
> host (one line), so on the device `room.chat` currently answers empty even
> with the grant, until that relay lands. The desktop harness (`--history`)
> and the host side are already wired; plugin_dev.js is the honest place to
> exercise chat-context code today.

### `room.ui`

```js
room.ui.card({ title, text, fields, link, style });
room.ui.text('a plain line');
```

Both push a card (see "Cards"); `text` is `card({ text })`. Max **5 cards
per call** — the sixth and beyond are dropped with one warn log, because a
card that silently never arrived looks like a bug in the app instead of the
plugin.

### `room.log`

`info`, `warn`, `error` — collected with `console.*` into the call's log
lines, capped at **50 lines / 2 KB total** (the last line that does not fit
is clipped, then silence). Logs ride the result envelope; today they are the
diagnostic channel of record for card drops and introspection warnings.

### `room.util`

`now()` (`Date.now()`), `uuid()` (`crypto.randomUUID()`), `b64encode(str)`,
`b64decode(b64)` — the small things every plugin reinvents.

### The sandbox context

Your module runs in a fresh `vm` realm with: `console` (host-collected),
`setTimeout`/`clearTimeout`/`setInterval`/`clearInterval` (the host's own,
tracked and all cleared at teardown — timers die with the call), `Buffer`,
`TextEncoder`, `TextDecoder`, `URL`, `URLSearchParams`, `global`, and the
context's own intrinsics (`Math`, `JSON`, `Date`, `Promise`, …). There is
no `process`, no `globalThis.require`, no I/O.

**`require` resolves:**

* your own modules — relative paths (`.js` / `.json`), resolved inside the
  plugin directory, symlinks resolved and **containment checked** (a link
  planted inside the plugin that points elsewhere is exactly the escape the
  check exists to catch; `'../evil'` refuses with "it resolves outside the
  plugin directory");
* bundled packages — bare names walk `node_modules` from the requiring
  module up to the plugin root, never past it; `package.json` `main` and
  `index.js`/`index.json` are honored;
* **exactly these built-ins:**

  ```
  assert  buffer  crypto  events  path  punycode  querystring
  string_decoder  url  util  zlib
  ```

  Everything else node ships is refused — the error names the allowlist,
  because the interesting names are the ones it leaves out: files, sockets,
  other processes, the process itself (`fs net tls http https http2 dgram
  child_process cluster os process vm worker_threads module inspector
  readline repl stream` — the list the design refused, and anything not on
  the allowlist meets the same refusal). `module.isBuiltin` is the honest
  test on node ≥ 18.6; an older runtime refuses the same names by list.
* absolute paths are refused outright; JSON modules parse and cache like
  node's.

## Events and budgets

One shot per event — the runtime is started for the call and torn down
after it, the same lifecycle `node_run` has. Every event reloads your entry
fresh (per-call module cache).

| Event | When | Handler invoked | Payload | Budget |
|---|---|---|---|---|
| `probe` | enable-time; catalogue rebuild | **none** — introspection only | `{}` | 30 s |
| `activate` | right after a successful enable | `exports.activate(room)` if present | `{}` | 30 s |
| `deactivate` | disable / chat deletion | `exports.deactivate(room, reason)` if present, best-effort | `{"reason": "…"}` | **10 s** |
| `command` | `/<name>` typed in the chat | `exports.commands[name].run(room, args)` | `{"name": "greet", "args": "World"}` | 30 s |
| `message` | **before the agent turn starts**, for every user message | `exports.onMessage(room, {text})` | `{"text": "hi"}` | **10 s** |
| `tool` | the model called one of your tools | `exports.tools[name].run(room, args)` | `{"name": "count_words", "argsJson": "{…}"}` | 30 s |
| `config` | after a config edit | `exports.onConfig(room)` if present | `{"values": {…}}` | 30 s |

Notes that matter:

* **The message hook runs before the model.** Every plugin of the chat with
  the hook sees the message sequentially, can push cards, and cannot fail
  the turn — a plugin that fails on it is said about and skipped. The 10 s
  budget (not the 30 s default) is deliberate: this hook sits in front of
  *every* message, so a plugin that wedges here wedges the chat itself.
* **The tool event is itself a capability.** The host refuses it outright
  with `plugin permission not granted: agent.tools` when the effective set
  does not carry the grant, whatever the manifest asked for.
* **Budgets are enforced twice**, the way `node_run`'s are: V8's own
  timeout bounds the synchronous half inside the context
  (`min(10 s, budget ÷ 3)` — a runaway loop dies there), and a timer race
  bounds the whole call. Overtime reads `the plugin was still running after
  <budget>ms and was stopped`.
* **Output cap 20 000 chars** per call (the bridge's own reply cap). Tool
  results are clipped at it; other events speak through cards, which have
  their own caps.
* **Introspection** (tools / commands / hooks reported) is filled by `probe`
  and `activate` only; every other event answers those fields empty.
* **The runtime is one client, app-wide.** The bridge accepts one
  connection and the app-side runner serializes every call — `node_run`,
  probes, tool events — under one process-wide lock. Plugins run one event
  at a time across the whole app; a slow plugin queues everyone behind it.
  A runtime that cannot start (no `libnode.so` for the device's CPU) answers
  every call `this build carries no Node runtime for this device's CPU`.

## Cards

The plugin's only output that reaches the **user** (tool output goes to the
model; cards go to the conversation).

```js
{
  title: 'Hello, Room!',           // ≤ 120 chars
  text: 'greeting #3 in this chat',// ≤ 4000 chars
  fields: [ ['plugin', 'com.example.hello'], ['greetings so far', '3'] ],
                                   // ≤ 12 pairs, each side ≤ 200 chars
  link: { label: 'Open', url: 'https://example.com' },
                                   // label ≤ 64, url http(s) ≤ 500;
                                   // the row opens the browser on tap — never auto
  style: 'success',                // 'info' (default) | 'success' | 'warn' | 'error'
}
```

Validation is clamping where a length is all that is wrong and dropping
where the shape is — either way a warn log says which card and why:

* at most **5 cards per call** (extras dropped, one log line);
* each card's JSON ≤ **4096 chars** — `text` gives way to make it fit;
  a card that still does not fit is dropped;
* a card that carries nothing to show, a non-object, a non-string title or
  text, or a non-http(s) link is dropped;
* unknown `style` means `info`.

The app parses cards a second time (same caps) before rendering, so a card
that survives the host survives the UI. Cards the chat has already shown are
persisted as transcript rows — they stay when the plugin is disabled or
uninstalled (rendered with a sanitized-name fallback), because they are part
of the conversation that happened.

## Agent tools

`exports.tools` is how a plugin extends the AI agent — the word-count
example is one tool and nothing else. It takes the `agent.tools` permission,
granted per chat.

```js
exports.tools = {
  count_words: {                          // your own name, ^[a-z][a-z0-9_-]{0,23}$
    description: 'Count the words in a text',   // 1..500 chars
    parameters: {
      type: 'object',
      properties: { text: { type: 'string', description: 'The text whose words are counted' } },
      required: ['text'],
    },
    run: async (room, args) => '3 words',
  },
};
```

What happens to that, mechanically:

* **The catalogue name is `plugin_<sanitized-id>_<tool>`** — the plugin's
  identity is in the name because two plugins may define the same tool name
  and the catalogue is one flat namespace; the sanitized id (dots to
  underscores) is also the directory name on disk, so the name a tool
  arrives under names the code that will run. The whole name must be
  **≤ 60 chars** — a name that cannot survive the re-spelling (over the cap,
  or characters the round trip cannot survive) is **dropped, not renamed**:
  a tool the plugin never defined is worse than a tool missing.
* **The description the model reads is `[<plugin name>] <your
  description>`** — the plugin names itself at the front, so a model
  choosing between two word-counters can tell whose it is calling. An empty
  description becomes "a tool provided by this chat's plugin".
* **`parameters` is a JSON Schema object**, the same style the app's
  built-in tools use. Write nothing (or an empty object) and the catalogue
  offers the minimal `{"type":"object"}` — a no-argument tool still needs a
  schema providers accept.
* **Only a probe can know a tool's schema**, so tools come from the probe's
  introspection; a tool that does not hold up (missing `run`, description
  out of 1..500, parameters not an object, name over 60 chars) is **left
  out with a warn log rather than failing the plugin** — a catalogue that
  skips one sloppy tool beats a plugin that never installs.
* **What the model reads back** is your `run` return value, **fenced as
  untrusted data** — the same fence a page's words get, because a plugin's
  words are equally not instructions. A failure returns your error text
  unfenced, the way `node_run` carries one. Cards you push while the tool
  runs (`room.ui.card`) go to the conversation for the user, beside the tool
  step — not into the model's result.

The gates plugin tools ride — the same stack every action the model takes
passes through, with one honest exception noted below:

| Gate | Behavior |
|---|---|
| Plan mode | refused (the `plugin_…` prefix check): the turn proposes work, it does not run plugin code |
| Withheld agent permissions | the profile's `agent.plugins` permission ("Plugin tools") withheld → refused by name, with the setting to change |
| Action budget | every plugin tool call spends the turn's step budget like a click does |
| Scheduled runs | refused first: "…it runs a chat plugin's code … A scheduled run has nobody to ask, so run it as a chat." |
| Headless chat turns | the same refusal, first — before the permission map it also rides |
| A tool whose plugin went away | failed result telling the model the plugin is "disabled, uninstalled, or not granted agent tools — check the chat's plugins rather than retrying" |

The exception: there is **no interactive Allow/Deny prompt** for a plugin
tool in a normal chat turn in this build — the consent moment is the
enable-time grant dialog ("Add tools the AI agent can call"), and after it
the tool runs when the model calls it. A plugin tool name that arrives at
an executor chain built without the plugin executor (which is what a
scheduled run's chain is) is asked about under the confirm gate — the one
axis that would have asked — and then refused honestly, so the model stops
instead of hunting for a route that does not exist.

## Storage and config — the on-disk layout

```
<filesDir>/plugins/<sanitized>/                  plugin CODE (extracted ZIP; read-only
                                                 at runtime — no API writes there)
<filesDir>/plugin-data/<sessionId>/<sanitized>/  this chat's data for the plugin
    kv.json
    files/
```

`<sanitized>` is the manifest id with `.` → `_` (`com.example.hello` →
`com_example_hello`) — one spelling for the code directory, the data root,
and the tool names. Both absolute roots are built by the app and injected
per call; the sandbox has no path API to name either, and no API that could
address a different chat's root. The layout is the isolation.

Lifecycle of that data: **disable** keeps it (hooks and tools stop, the
store stays). **Chat deleted** cascades the binding row and an orphan sweep
removes the data directory. **Uninstall** removes the code directory and the
plugin's data in *every* chat — the confirm dialog says so, because the code
comes back with a reinstall and the chats' kv does not.

**Config** is per chat: the manifest's schema defaults, overridden by
anything this chat stored (only declared keys survive the merge — a value
the schema never asked for is dropped rather than handed to `room.config`
as a surprise). The `config` event (`onConfig`) is implemented end to end —
protocol, host, payload — but this build ships no chat-side settings surface
to edit overrides yet, so on the device chats run on the manifest defaults;
the `--config` flag of the desktop harness and the data layer's
`setConfig` are the ways to vary them today.

## Commands in chat

Typed in the composer, intercepted **before** any agent turn (a plugin
command never sends a turn, and `/plugins` is the plugin system's own
surface, not the model's):

| Input | What happens |
|---|---|
| `/plugins` | opens the chat-plugins sheet (also the composer's puzzle-piece button) |
| `/plugins <id-or-name>` | toggles that plugin for the chat — exact id or exact name; enabling raises the grant dialog when consents are missing; a notice answers either way |
| `/plugins <no match>` | a notice listing what *is* installed (or the "install one from a ZIP" line when nothing is) |
| `/<name> <args…>` | dispatched when **exactly one** enabled plugin's *manifest* declares `<name>` — the command event runs, cards land, no turn starts |
| `/<name>` declared by 2+ enabled plugins | an ambiguity notice naming both — enable one, or switch the other off |
| anything else | falls through to the agent as ordinary chat (a leading slash is not an error) |

`plugins` is reserved and a manifest may not declare it. A brand-new chat
with no session row yet still works: the first enable or plugin command
creates the chat's row bound to the panel's tab — which needs an AI provider
to be configured ("Configure an AI provider first (Agent → settings)"),
since a chat row names one.

## Packaging

### `make_plugin_zip.py` — the deterministic packer

```sh
python3 android/tools/make_plugin_zip.py <sourceDir> <out.zip>
```

Validates the source directory against the package rules (the manifest
reference above, forbidden binaries, size caps, entry-name rules, symlinks
refused outright) and writes a DEFLATE archive whose every entry is dated
1980-01-01 00:00:00, carries no extra fields, and sits in sorted order with
unix 0644 attributes — **two runs over the same tree produce the same
bytes**, so a rebuilt example shows up as an empty diff rather than a
shuffled one (this is how the APK's bundled `hello-world.zip` stays
diffable). A source that fails any rule is refused with every reason on
stderr and exit 1; success prints `wrote N entries to <out.zip>` and exits 0.
`plugin.json` must sit at the source directory's root (the single wrapping
folder is a device-install tolerance, not a packer one).

### Bundling npm dependencies

Your ZIP may carry a `node_modules/` — resolved by the require shim,
plugin-relative, never past the plugin root:

```sh
cd my-plugin/
npm install --omit=dev        # dependencies only, no dev/optional/peer trees
python3 ../tools/make_plugin_zip.py . ../out.zip
```

The package caps apply to what you bundle: ≤ 512 entries, ≤ 20 MB total,
≤ 2 MB per entry — a dependency tree can hit all three, so check the
packer's answer before wondering why the phone refuses it. **Pure
JavaScript only**: a package with native code (`*.node`, `*.so`, …) is
rejected at install, because native code would load into the runtime's own
process and out of the sandbox's reach entirely — the sandbox's promises are
about JavaScript. A package that needs a build step will not work either;
nothing from a package is ever executed at install time.

## The desktop dev harness (`plugin_dev.js`)

```sh
node android/tools/plugin_dev.js <pluginDir> <event> [payloadJson]
                   [--config {json}] [--perms storage,agent.tools,chat.context,network]
                   [--net api.example.com,*.example.com] [--timeout 30000]
                   [--data-root <dir>] [--history [{"role":"user","text":"hi"}]]
```

It plays the part the app plays on a device: reads the manifest, builds the
request, and hands it to the **same `plugin_host.js` the runtime ships** —
what a plugin does here is what it does there, and what it cannot do here
(fs, sockets, the process) it cannot do there either.

The real flags:

| Flag | Meaning |
|---|---|
| `--perms` | the grant decision — a comma list from `storage,agent.tools,chat.context,network`. **Defaults to NONE**, the same deny-by-default order the app runs in; pass what a user would have accepted. |
| `--config` | a JSON object of config overrides (merged over the manifest defaults). |
| `--net` | the network allowlist, comma-separated. Defaults to the manifest's `network.allow` when present — unlike `--perms`, the harness assumes you meant what you declared. |
| `--timeout` | the call's budget in ms (default 30000; deactivate 10000). |
| `--data-root` | an explicit data root. Default is deterministic per plugin under the OS temp dir — `<tmp>/room-plugin-dev/plugin-data/1/<sanitized>` — so a second run sees the first run's kv; point it somewhere fresh to start over. |
| `--history` | a JSON array of `{role, text}` — what a `chat.context` grant would deliver on-device. |
| `--help` / `-h` | the usage block. |

Payloads per event (third positional, JSON):
`command {"name":"greet","args":"World"}`,
`tool {"name":"count_words","argsJson":"{\"text\":\"hi\"}"}`,
`deactivate {"reason":"disabled"}`,
`config {"values":{…}}` — `probe`/`activate`/`message` take `{}` (message's
text rides the payload: `{"text":"hi"}`).

It prints one line to stderr (`plugin_dev: <id> (<dir>) event <event>, data
root <root>`), the **full result envelope as JSON on stdout** — `ok`,
`output`, `cards`, `logs`, `tools`, `commands`, `hooks`, `ms` — and exits 0
when `result.ok`, 1 when the call failed, 2 on a usage error. That envelope
is the whole debugging surface: cards you expected but do not see are
usually in `logs` ("a card was dropped: …"), a permission you forgot is the
`output` throw, and `tools`/`commands`/`hooks` are exactly what the app
would register from your probe.

## API versioning

`"api": 1` — the only version this build speaks, and a manifest declaring
anything else is refused at install with the supported number in the
message. The strictness (unknown manifest fields are errors, not surprises)
is the other half of the policy: a manifest from a future version must fail
loudly at the door, not silently half-work behind it.

What would force an `api: 2`: a **breaking** change to the entry contract
(handler signatures, return semantics), to the `room` SDK's namespaces, to
the permission set's meanings, or to the wire envelope. Additive changes
stay in api 1 — `deactivate` gaining the `reason` argument is the precedent:
a `(room)` handler kept working, so no version moved. The re-validation
story on upgrade: a plugin reinstalled under a newer manifest is re-validated
from scratch at install (the strictness is what makes that safe — an old
build refuses a new manifest it cannot read, and a new build accepts an old
one it fully understands), and a chat whose stored grants no longer cover
the new declared set re-consents at the next enable rather than being
silently widened.

## The bundled examples, annotated

### Hello World — cards, a command, storage

`docs/plugins/hello-world/` — the smallest honest plugin: an activation
card, one `/greet` command, and a counter that survives between calls in
the chat's own kv store. It asks for one permission (`storage`) and defines
one config value.

```json
{
  "id": "com.example.hello",
  "name": "Hello World",
  "version": "1.0.0",
  "description": "A tiny example plugin.",
  "author": { "name": "Jane Doe", "email": "jane@example.com", "url": "https://example.com" },
  "license": "MIT",
  "api": 1,
  "entry": "main.js",
  "permissions": ["storage"],
  "config": {
    "schema": [
      { "key": "greeting", "type": "string", "default": "Hello",
        "title": "Greeting word", "description": "Word to greet with" }
    ]
  },
  "commands": [ { "name": "greet", "title": "Greet", "description": "Say hello" } ]
}
```

The manifest says everything the app needs before any code runs: one
permission to consent to, one command to route, one config knob with a
default — so `/greet` works the moment the plugin is enabled, whether or
not the code ever loaded (command routing reads the manifest; the *command
event* needs the code).

```js
exports.activate = async (room) => {
  room.ui.card({
    title: room.meta.pluginName + ' is running',
    text: 'Type /greet <name> in this chat and it answers with a card; every greeting ' +
      'is counted in this chat\'s own storage.',
    style: 'info',
  });
};

exports.commands = {
  greet: {
    run: async (room, args) => {
      const who = (args || '').trim() || 'world';
      const greeting = room.config.get('greeting') || 'Hello';
      const count = (room.storage.kv.get('greetings') || 0) + 1;
      room.storage.kv.set('greetings', count);
      return {
        title: greeting + ', ' + who + '!',
        text: 'greeting #' + count + ' in this chat',
        style: 'success',
        fields: [['plugin', room.meta.pluginId], ['greetings so far', String(count)]],
      };
    },
  },
};
```

Reading it: `activate` pushes one card — enable-time is the moment to say
"I'm here and here's how to use me". The command handler defaults its
arguments (`args` is the raw text after `/greet`, possibly empty), reads
config with a fallback, and uses kv the way a counter works — `get` (null
becomes 0), `set`. The card it *returns* (rather than pushing) takes the
same path through validation; returning is just the shorter spelling. The
counter is per chat by construction — the same plugin in another chat
starts from zero.

Try it: the README beside it has the exact commands; the interesting one is
the config override —

```sh
node android/tools/plugin_dev.js android/docs/plugins/hello-world command \
  '{"name":"greet","args":"Room"}' --perms storage --config '{"greeting":"Howdy"}'
```

— and the counter climbs across runs because the harness's data root is
deterministic per plugin.

### Word Count — an agent tool, three permissions

`docs/plugins/word-count/` — no commands, no network: what it adds is a
tool the chat's AI agent can call. It declares all three load-bearing v1
permissions — `agent.tools` for the tool itself, `chat.context` for the
activation card's look at the conversation, `storage` for the lifetime word
total — and one boolean config value, `ignore_case`.

```json
{
  "id": "com.example.wordcount",
  "name": "Word Count",
  "version": "1.0.0",
  "description": "Adds a word-counting tool to the chat's AI agent.",
  "author": { "name": "Room Browser" },
  "license": "MIT",
  "api": 1,
  "entry": "main.js",
  "permissions": ["agent.tools", "chat.context", "storage"],
  "config": {
    "schema": [
      { "key": "ignore_case", "type": "boolean", "default": true,
        "title": "Ignore case", "description": "Count words case-insensitively" }
    ]
  }
}
```

```js
exports.activate = async (room) => {
  const messages = room.chat.history().length;
  room.ui.card({
    title: 'Word Count is running',
    text: 'The AI agent in this chat can call its count_words tool. Every word it ' +
      'counts adds to a lifetime total kept in this chat\'s own storage.',
    fields: [['messages in this chat', String(messages)]],
    style: 'info',
  });
};

exports.tools = {
  count_words: {
    description: 'Count the words in a text',
    parameters: {
      type: 'object',
      properties: {
        text: { type: 'string', description: 'The text whose words are counted' },
      },
      required: ['text'],
    },
    run: async (room, args) => {
      const text = args && typeof args.text === 'string' ? args.text : '';
      const ignoreCase = room.config.get('ignore_case') !== false;
      const body = ignoreCase ? text.toLowerCase() : text;
      const words = body.split(/\s+/).filter((word) => word.length > 0).length;
      const lifetime = (room.storage.kv.get('lifetime_words') || 0) + words;
      room.storage.kv.set('lifetime_words', lifetime);
      return words + ' words (lifetime total ' + lifetime + ')';
    },
  },
};
```

Reading it: the activation card's `room.chat.history()` is the
`chat.context` permission earning its keep — leave `--perms` off and that
line throws `plugin permission not granted: chat.context`, which is the
deny-by-default order the app itself runs in (and why the probe reports the
tool while the activation fails: permissions gate *calls*, not
registration). The tool's `parameters` is the JSON Schema the provider is
offered, prefixed by the app to `[Word Count] Count the words in a text` and
re-spelled `plugin_com_example_wordcount_count_words` in the catalogue —
under 60 characters, or it would have been dropped. `run` returns a string,
so the model reads exactly those words, fenced as untrusted data. The
lifetime total is kv again — per chat, so "lifetime" means this
conversation's.

Try it:

```sh
node android/tools/plugin_dev.js android/docs/plugins/word-count probe \
  --perms agent.tools,chat.context,storage
node android/tools/plugin_dev.js android/docs/plugins/word-count tool \
  '{"name":"count_words","argsJson":"{\"text\":\"call me maybe\"}"}' \
  --perms agent.tools,storage
```

The probe's `tools` array is what the app registers; the tool run's
`output` is what the model would read back.

## Troubleshooting / FAQ

**`Error: plugin permission not granted: <name>`** — the chat that ran this
call did not grant `<name>`. Either the manifest forgot to declare it, or
the person enabled the plugin and accepted a dialog that predates a newer
manifest's bigger ask (re-consent happens on the next enable). The
namespace exists on purpose so the error names the exact permission; there
is no way to "try" past it — the host enforces the effective set.

**`plugin kv quota exceeded` / `plugin files quota exceeded` /
`plugin network quota exceeded`** — you hit a cap (16 KB per kv value, 128
keys, 256 KB store; 256 files, 10 MB directory; 2 MB per response body,
6 MB per call; see the SDK section for all of them). The message names the
number that broke. kv and files quotas are measured from disk, so deleting
before writing is the honest fix, not bookkeeping.

**"… is on, but it could not be loaded: …"** — the probe failed: your entry
threw at load time (a syntax error arrives as the SyntaxError). The plugin
stays enabled but contributes no tools and no hooks until it loads; fix the
code and toggle off/on to re-probe. "… its start-up failed: …" is the same
story one step later — `activate` itself threw.

**"\"/<name>\" is no longer available in this chat."** — the manifest used
to declare it and the chat remembers typing it, but no enabled plugin
declares it now (disabled, uninstalled, or the command came from a manifest
that never had it).

**A plugin sees no history even with `chat.context`** — two honest causes.
Without the permission the history field is absent by design (absent means
"not granted", never "granted and empty"). With it, on this build the
bridge does not yet relay the field to the host — see the "Status in this
build" note under `room.chat`; exercise chat-context code with the harness's
`--history` until that lands.

**Everything is slow / calls queue behind each other** — the runtime is one
client app-wide: probes, tool events and `node_run` all serialize under one
lock, one event at a time across every chat. A plugin that spends its whole
30 s budget makes the next plugin wait. Keep handlers short; the message
hook's 10 s cap exists precisely because it sits in front of every message.

**`require` refuses a module** — read the error: it names the allowlist when
you asked for a built-in that is not one (`fs`, `net`, `process`, …), says
"not a package bundled in the plugin" when the bare name has no
`node_modules` match, or "resolves outside the plugin directory" when a
relative path or a planted symlink tries to climb out. Bundling means
`npm install --omit=dev` *into the plugin directory* and zipping it in.

**A card never appeared** — check the `logs` in the result envelope (the
harness prints them; the drop reasons live there): over 5 cards, over 4 KB
of JSON, nothing to show, or a bad shape. Lengths are clamped with the
cap named; shapes are dropped.

**`room.net.fetch refused '<host>'`** — the allowlist does not cover that
host, including on a redirect hop (re-checked). `*.domain` covers the domain
itself and subdomains; IP literals and single-label hosts are refused at
install, not matched later.

**It works in `plugin_dev.js` but not on the phone** — the harness runs the
same host with the same rules, so the difference is the environment: the
device's profile user agent applies to fetches, the app intersects grants
(you passed `--perms`, a user accepted a dialog), the runtime must exist
for that CPU, and a chat must exist (a provider configured) for commands in
a brand-new chat. The result envelope's `ms` and `logs` are the same on
both.

**Why did my plugin run its top level again?** — it does that on every
event; the module cache is per-call. Top-level work is paid per message,
per command, per tool call. Initialize lazily inside handlers or accept the
cost knowingly.

## Where the code lives

For the curious, and for filing issues where they belong:

| Piece | File |
|---|---|
| Sandbox host + `room` SDK | `android/node/src/main/assets/node/plugin_host.js` |
| Bridge delegation (the `plugin` command) | `android/node/src/main/assets/node/bridge.js` |
| Wire DTOs (request/result/history) | `android/node/src/main/kotlin/com/roombrowser/node/NodeProtocol.kt` |
| One-call runner (lock, start, teardown) | `android/node/src/main/kotlin/com/roombrowser/node/NodeRunner.kt`; assets sync in `NodeRuntime.kt` |
| Manifest model + validator | `android/core/domain/src/main/kotlin/com/roombrowser/domain/plugins/` (`PluginManifest.kt`, `PluginManifestValidator.kt`) |
| Permissions + labels, command grammar, tool naming, card model, `API_VERSION` | same directory (`PluginPermissions.kt`, `PluginCommandParser.kt`, `PluginToolNaming.kt`, `PluginCard.kt`, `PluginApi.kt`) |
| ZIP installer (guards, atomic extract) | `android/app/src/main/kotlin/com/roombrowser/data/plugins/PluginZipInstaller.kt` |
| Rows, bindings, sweep, config | `android/app/src/main/kotlin/com/roombrowser/data/repo/PluginRepository.kt`; tables in `data/db/` (Room v17) |
| App-side engine (request build, grants, fencing, budgets, history shaping) | `android/app/src/main/kotlin/com/roombrowser/agent/AgentPluginRuntime.kt` |
| Per-chat catalogue (probe cache, tool defs, command owners) | `android/app/src/main/kotlin/com/roombrowser/agent/AgentPluginCatalog.kt` |
| Tool interception (innermost executor) | `android/app/src/main/kotlin/com/roombrowser/agent/PluginToolExecutor.kt` |
| Chat surface (commands, grants, enable/disable) | `android/app/src/main/kotlin/com/roombrowser/agent/PluginUiState.kt`; turn wiring in `BrowserAgentController.kt` |
| UI (sheet, grant dialog, cards, manager) | `android/app/src/main/kotlin/com/roombrowser/agent/ui/` (`PluginSheets.kt`, `PluginPermissionDialog.kt`, `PluginCardView.kt`, `PluginManagerActivity.kt`) |
| Agent-side gates (plan/permission/unattended) | `domain/agent/PlanModePolicy.kt`, `AgentPermissions.kt`; `agent/HeadlessToolExecutor.kt`, `ScheduledRunToolGate.kt`; `domain/task/AiTaskPermissions.kt` |
| Packer + dev harness | `android/tools/make_plugin_zip.py`, `android/tools/plugin_dev.js` |
| Example sources | `android/docs/plugins/hello-world/`, `android/docs/plugins/word-count/` (pre-zipped copies ship in `android/node/src/main/assets/node/plugins/`) |
| SDK typings (editor support) | `android/docs/plugins/sdk/plugin-sdk.d.ts` |
