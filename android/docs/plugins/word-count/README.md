# Word Count — the bundled agent-tool example

A plugin with no network and one command: what it adds is a tool the chat's
AI agent can call, and a `/compose` command that asks that same AI for one
sentence. It declares all four v1 permissions — `agent.tools` for the tool
itself, `chat.context` for the activation card's look at the conversation,
`storage` for the lifetime word total, `agent.generate` for the compose
command — and one boolean config value, `ignore_case`.

## Reading it

- `plugin.json` — the manifest: one command, no `network`, the four
  permissions, and a config schema with a boolean default.
- `main.js` — the entry: `exports.tools.count_words` carries a JSON-Schema
  `parameters` object (the same style the app's built-in agent tools use) and
  a `run(room, args)` whose string return value becomes the tool result,
  fenced as untrusted content before the model ever sees it;
  `exports.commands.compose` is the minimal `room.agent.generate` consumer —
  it counts the words, asks THIS chat's own model for one short sentence
  about them, and shows a card either way (the failure's own words when the
  model cannot be reached, because an empty bubble is not an answer).

## Trying it on a desktop

From the repo root (plain Node ≥ 18, no dependencies):

```sh
node android/tools/plugin_dev.js android/docs/plugins/word-count probe \
  --perms agent.tools,chat.context,storage,agent.generate
node android/tools/plugin_dev.js android/docs/plugins/word-count activate \
  --perms agent.tools,chat.context,storage,agent.generate
node android/tools/plugin_dev.js android/docs/plugins/word-count tool \
  '{"name":"count_words","argsJson":"{\"text\":\"call me maybe\"}"}' \
  --perms agent.tools,storage
node android/tools/plugin_dev.js android/docs/plugins/word-count command \
  '{"name":"compose","args":"call me maybe"}' \
  --perms agent.generate
```

The probe reports the tool's name, description and parameters plus the
`compose` command — that is what the app registers. Leave off `--perms` and
the activation card's `room.chat.history()` call fails with
`plugin permission not granted: chat.context`, which is the deny-by-default
order the app itself runs in; the compose command without the grant fails
with `plugin permission not granted: agent.generate` the same way. The
desktop harness answers `room.agent.generate` with a deterministic fake
(`(composed) …` — clearly labelled, so a real model is never needed to test
the flow); `--no-gen` turns the channel off to exercise the refusal path.

## Packing it

```sh
python3 android/tools/make_plugin_zip.py android/docs/plugins/word-count out.zip
```

The full plugin reference — tool schemas, the gate behavior plugin tools ride
(confirm/plan/permission/scheduled refusals), every `room` API — is
[android/docs/PLUGINS.md](../../PLUGINS.md).
