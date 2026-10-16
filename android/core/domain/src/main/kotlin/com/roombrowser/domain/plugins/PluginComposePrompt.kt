package com.roombrowser.domain.plugins

import com.roombrowser.domain.agent.ChatMessage

/**
 * The prompt the app renders for one `/make_plugins` composition: a fixed
 * system half that carries the whole plugin contract (the manifest rules,
 * the entry-file rules, the room SDK summary — design §5.4, verbatim) and a
 * user half that carries the person's description as a labelled data line.
 *
 * WHY THE APP OWNS THIS TEXT — [PluginGeneratePrompt]'s posture, again. The
 * user's description is clamped ([DESCRIPTION_CHARS]) and rendered under a
 * system prompt that commands "Reply with exactly two fenced code blocks
 * and nothing else", so anything a description tries to smuggle reads as
 * the data it is; the only instructions the model reads are the app's own.
 * The reply is untrusted input either way — [PluginComposeParser] validates
 * it with the same validator that gates installs before anything renders or
 * installs — and on a retry the parser's own [PluginComposeParser.retryNote]
 * rides above the description, so the corrective instruction is the app's
 * too. Provider, key, URL and model stay unnamed here, same rule as the
 * generation prompt: the composer is resolved per call by the caller.
 */
object PluginComposePrompt {

    /** The cap on a `/make_plugins` description; the interceptor's grammar branch reads it. */
    const val DESCRIPTION_CHARS = 1000

    /** The fixed, app-owned system prompt — design §5.4, verbatim. */
    const val SYSTEM_PROMPT = """You write ONE Room Browser chat plugin as two files. The user message is a
plain-language description of what the plugin should do. Reply with exactly
two fenced code blocks and nothing else - no preamble, no explanations:

```json
{ ...the complete plugin.json manifest... }
```

```js
{ ...the complete main.js entry file... }
```

plugin.json rules - every one is enforced at install, and a violation is
refused:
- id: lowercase, dotted, 3..64 characters, like "com.room.<slug>" - invent a
  short slug from the description.
- name: 1..64 printable characters.
- version: "0.1.0" - a first draft.
- api: 1 - the integer.
- entry: "main.js".
- permissions: an array whose entries come ONLY from "storage", "network",
  "agent.tools", "chat.context", "agent.generate". Ask for the least the
  plugin needs; omit the array when it needs none.
- network: {"allow": ["host", "*.domain"]} - REQUIRED when permissions
  contains "network", refused without it. Host patterns: lowercase, exact
  hosts or "*.<domain>" (which covers the domain and its subdomains), no IP
  literals, no single-label hosts, at most 20.
- commands: optional, at most 12, each {"name", "title", "description"} with
  a lowercase name of at most 24 characters - never "plugins" and never
  "make_plugins" (both are reserved by the app).
- config.schema: optional, at most 12 items, each
  {"key", "type", "default", "title", "description"} with type "string",
  "number" or "boolean".
- hooks: ["message"] only if the plugin must read every user message; omit
  otherwise.
- description: what the plugin does, at most 500 characters.
- Unknown fields are REFUSED: send only the fields above.

main.js rules:
- CommonJS only: exports.activate, exports.commands, exports.tools - no ESM.
- Every handler receives `room` and returns a card object, a string, an
  array of those, or nothing. A handler that throws fails only that call.
- The `room` SDK: room.meta (identity), room.config.get(key),
  room.storage.kv (get/set/delete/clear/keys/size - needs "storage"),
  room.storage.files (read/write/append/... - needs "storage"),
  room.net.fetch(url, opts) (needs "network"; only the allowlist's hosts),
  room.chat.history()/summary() (needs "chat.context"),
  room.agent.generate(options) (needs "agent.generate"),
  room.ui.card()/text() (at most 5 cards per call), room.log, room.util.
- Every event is one shot with a 30 second budget. Top-level code runs on
  EVERY event - keep it cheap, do real work in handlers.
- A command the manifest declares must have exports.commands[name].run.

Write complete, working JavaScript - no placeholders, no TODOs, no truncation."""

    /**
     * The two messages one composition is sent as: the fixed system prompt
     * and the description (clamped to [DESCRIPTION_CHARS]) under its label.
     * On the one corrective retry, [retryNote]'s own lines ride above the
     * same label — the same two-message shape [PluginGeneratePrompt.messagesOf]
     * renders for a generation.
     */
    fun messagesOf(description: String, retryNote: String? = null): List<ChatMessage> {
        val user = buildString {
            retryNote?.let {
                append(it)
                append("\n\n")
            }
            append("Write the plugin for this description:\n\n")
            append(description.take(DESCRIPTION_CHARS))
        }
        return listOf(
            ChatMessage(role = "system", content = SYSTEM_PROMPT),
            ChatMessage(role = "user", content = user)
        )
    }
}
