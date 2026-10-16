package com.roombrowser.domain.plugins

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The composition prompt: the fixed system half (design §5.4, verbatim — the
 * whole plugin contract in one app-owned text) and the user half rendered
 * from the person's description. What matters HERE is the same thing that
 * mattered for [PluginGeneratePromptTest]: the rendering contract — the
 * description clamped and under its label, the retry note riding above it —
 * because this text is the /make_plugins flow's whole prompt-injection
 * posture: the only instructions the model reads are the app's own.
 */
class PluginComposePromptTest {

    @Test
    fun `the system prompt is the fixed app-owned text`() {
        // Verbatim from design §5.4: a word that drifts here drifts for every
        // composition, and the parser on the other end is built for exactly
        // these fences, rules and names.
        assertThat(PluginComposePrompt.SYSTEM_PROMPT).isEqualTo(
            """
            You write ONE Room Browser chat plugin as two files. The user message is a
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

            Write complete, working JavaScript - no placeholders, no TODOs, no truncation.
            """.trimIndent()
        )
    }

    @Test
    fun `the system prompt carries the whole plugin contract`() {
        // The summary the parser and the validator hold it to: both fence
        // demands, the permission vocabulary, the network rule, both reserved
        // words, and the CommonJS/room-SDK half.
        val prompt = PluginComposePrompt.SYSTEM_PROMPT
        assertThat(prompt).contains("```json")
        assertThat(prompt).contains("```js")
        PluginPermissions.PERMISSIONS.forEach { permission ->
            assertThat(prompt).contains("\"$permission\"")
        }
        assertThat(prompt).contains("REQUIRED when permissions")
        assertThat(prompt).contains("no IP")
        assertThat(prompt).contains("never \"plugins\" and never \"make_plugins\"")
        assertThat(prompt).contains("CommonJS only")
        assertThat(prompt).contains("exports.activate")
        assertThat(prompt).contains("room.storage.kv")
        assertThat(prompt).contains("room.net.fetch")
        assertThat(prompt).contains("room.agent.generate")
        assertThat(prompt).contains("room.chat.history()")
        assertThat(prompt).contains("room.ui.card()")
        assertThat(prompt).contains("30 second budget")
        assertThat(prompt).contains("Write complete, working JavaScript")
    }

    @Test
    fun `messagesOf renders the description under the system prompt`() {
        val messages = PluginComposePrompt.messagesOf("a tiny plugin that counts greetings per chat")
        assertThat(messages).hasSize(2)
        assertThat(messages[0].role).isEqualTo("system")
        assertThat(messages[0].content).isEqualTo(PluginComposePrompt.SYSTEM_PROMPT)
        assertThat(messages[1].role).isEqualTo("user")
        assertThat(messages[1].content).isEqualTo(
            "Write the plugin for this description:\n\n" +
                "a tiny plugin that counts greetings per chat"
        )
    }

    @Test
    fun `the retry note rides above the same description`() {
        val note = PluginComposeParser.retryNote(listOf("the entry file is empty"))
        val messages = PluginComposePrompt.messagesOf("a counter plugin", note)
        assertThat(messages).hasSize(2)
        assertThat(messages[1].content).isEqualTo(
            "Your previous attempt was refused for these reasons:\n" +
                "- the entry file is empty\n" +
                "\n" +
                "Write the plugin for this description:\n\n" +
                "a counter plugin"
        )
        // And with no note, nothing of it renders.
        assertThat(PluginComposePrompt.messagesOf("a counter plugin")[1].content)
            .doesNotContain("refused")
    }

    @Test
    fun `the description is clamped to 1000 characters`() {
        assertThat(PluginComposePrompt.DESCRIPTION_CHARS).isEqualTo(1000)
        val flood = "x".repeat(2_000)
        val user = PluginComposePrompt.messagesOf(flood)[1].content!!
        assertThat(user).endsWith("x".repeat(1000))
        assertThat(user).hasLength("Write the plugin for this description:\n\n".length + 1000)
        // The cap's edge is inclusive: exactly 1000 characters travel whole.
        val edge = "y".repeat(1000)
        assertThat(PluginComposePrompt.messagesOf(edge)[1].content)
            .endsWith(edge)
    }
}
