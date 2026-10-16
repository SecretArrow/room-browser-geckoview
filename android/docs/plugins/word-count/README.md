# Word Count — the bundled agent-tool example

A plugin with no commands and no network: what it adds is a tool the chat's
AI agent can call. It declares all three v1 permissions — `agent.tools` for
the tool itself, `chat.context` for the activation card's look at the
conversation, `storage` for the lifetime word total — and one boolean config
value, `ignore_case`.

## Reading it

- `plugin.json` — the manifest: no `commands`, no `network`, the three
  permissions, and a config schema with a boolean default.
- `main.js` — the entry: `exports.tools.count_words` carries a JSON-Schema
  `parameters` object (the same style the app's built-in agent tools use) and
  a `run(room, args)` whose string return value becomes the tool result,
  fenced as untrusted content before the model ever sees it.

## Trying it on a desktop

From the repo root (plain Node ≥ 18, no dependencies):

```sh
node android/tools/plugin_dev.js android/docs/plugins/word-count probe \
  --perms agent.tools,chat.context,storage
node android/tools/plugin_dev.js android/docs/plugins/word-count activate \
  --perms agent.tools,chat.context,storage
node android/tools/plugin_dev.js android/docs/plugins/word-count tool \
  '{"name":"count_words","argsJson":"{\"text\":\"call me maybe\"}"}' \
  --perms agent.tools,storage
```

The probe reports the tool's name, description and parameters — that is what
the app registers as an agent tool. Leave off `--perms` and the activation
card's `room.chat.history()` call fails with
`plugin permission not granted: chat.context`, which is the deny-by-default
order the app itself runs in.

## Packing it

```sh
python3 android/tools/make_plugin_zip.py android/docs/plugins/word-count out.zip
```

The full plugin reference — tool schemas, the gate behavior plugin tools ride
(confirm/plan/permission/scheduled refusals), every `room` API — is
[android/docs/PLUGINS.md](../../PLUGINS.md).
