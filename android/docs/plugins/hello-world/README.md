# Hello World — the bundled example plugin

A complete plugin in two files: a manifest and an entry. It asks for the
`storage` permission, shows a card when it is enabled in a chat, and answers
`/greet <name>` with a card whose counter lives in that chat's own kv store.

## Reading it

- `plugin.json` — the manifest: identity, the one permission, the `greet`
  command, and a `greeting` config value defaulting to "Hello".
- `main.js` — the entry: `exports.activate` emits the welcome card, and
  `exports.commands.greet.run` reads and writes
  `room.storage.kv` and returns the reply card.

## Trying it on a desktop

From the repo root (plain Node ≥ 18, no dependencies):

```sh
node android/tools/plugin_dev.js android/docs/plugins/hello-world activate --perms storage
node android/tools/plugin_dev.js android/docs/plugins/hello-world command \
  '{"name":"greet","args":"Room"}' --perms storage
node android/tools/plugin_dev.js android/docs/plugins/hello-world command \
  '{"name":"greet","args":"Room"}' --perms storage --config '{"greeting":"Howdy"}'
```

The counter climbs across runs because the harness keeps one data root per
plugin under the OS temp dir; drop `--data-root /tmp/fresh` to start over.

## Packing it

```sh
python3 android/tools/make_plugin_zip.py android/docs/plugins/hello-world out.zip
```

The archive is byte-reproducible, which is how the copy bundled in the app
(`assets/node/plugins/hello-world.zip`) stays diffable.

The full plugin reference — every manifest field, every `room` API, the
sandbox rules — is [android/docs/PLUGINS.md](../../PLUGINS.md).
