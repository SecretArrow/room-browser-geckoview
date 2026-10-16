package com.roombrowser.domain.plugins

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The compose parser, reply shape by reply shape: what a model's answer to
 * [PluginComposePrompt] must look like to become a draft, and the exact
 * sentence each way of failing earns. The validator half is relayed, not
 * re-tested here ([PluginManifestValidatorTest] holds that rulebook) — what
 * is under test is the FENCED-BLOCK identification: which block is the
 * manifest, which is the code, and the tolerance (info strings, order,
 * prose, extra blocks) that spends the one corrective retry only on real
 * rule violations, never on courtesy.
 *
 * Every test ends on a VOID-returning Truth call: a Kotlin function whose
 * last expression has a value compiles to a non-void method, and JUnit4
 * then refuses the whole class.
 */
class PluginComposeParserTest {

    @Test
    fun `a json block plus a js block is a draft`() {
        val draft = PluginComposeParser.parse(REPLY) as PluginComposeParser.Parsed.Draft
        // The manifest arrives as the object the validator read — the same
        // shape the install path re-encodes — and the code verbatim.
        assertThat(draft.manifest.keys).containsExactly(
            "id", "name", "version", "description", "api", "entry", "permissions"
        )
        assertThat(PluginManifest.parse(draft.manifest)!!.id).isEqualTo("com.room.counter")
        assertThat(PluginManifest.parse(draft.manifest)!!.permissions).containsExactly("storage")
        assertThat(draft.entrySource).isEqualTo("exports.activate = async () => {}\n")
    }

    @Test
    fun `info strings may be absent or case-variant`() {
        val reply = """
            Here is your plugin.

            ```
            $MANIFEST
            ```

            ```
            exports.commands = {}
            ```
        """.trimIndent()
        val draft = PluginComposeParser.parse(reply) as PluginComposeParser.Parsed.Draft
        assertThat(draft.entrySource).isEqualTo("exports.commands = {}\n")

        // Case-variant tags mean the same thing: JSON is json, JavaScript is js.
        val cased = "```JSON\n$MANIFEST\n```\n\n```JavaScript\nexports.x = 1\n```"
        val casedDraft = PluginComposeParser.parse(cased) as PluginComposeParser.Parsed.Draft
        assertThat(casedDraft.entrySource).isEqualTo("exports.x = 1\n")
    }

    @Test
    fun `the manifest may come after the code`() {
        val reply = "```js\nexports.activate = 1\n```\n\n```json\n$MANIFEST\n```"
        val draft = PluginComposeParser.parse(reply) as PluginComposeParser.Parsed.Draft
        assertThat(PluginManifest.parse(draft.manifest)!!.entry).isEqualTo("main.js")
        assertThat(draft.entrySource).isEqualTo("exports.activate = 1\n")
    }

    @Test
    fun `prose around the blocks is ignored`() {
        val reply = """
            Sure! Here's the plugin you asked for.

            ```json
            $MANIFEST
            ```

            ```js
            exports.activate = 2
            ```

            Hope this helps — tell me if you want changes.
        """.trimIndent()
        val draft = PluginComposeParser.parse(reply) as PluginComposeParser.Parsed.Draft
        assertThat(draft.entrySource).isEqualTo("exports.activate = 2\n")
    }

    @Test
    fun `extra blocks are tolerated and the longest remaining is the code`() {
        // No info strings at all: the first readable object is the manifest,
        // and among the rest, length tells the two apart.
        val reply = """
            ```
            $MANIFEST
            ```

            ```
            x
            ```

            ```
            exports.commands = { greet: { run: () => 'hi' } }
            ```
        """.trimIndent()
        val draft = PluginComposeParser.parse(reply) as PluginComposeParser.Parsed.Draft
        assertThat(draft.entrySource)
            .isEqualTo("exports.commands = { greet: { run: () => 'hi' } }\n")
    }

    @Test
    fun `a json tag on garbage falls back to the first readable object`() {
        // A block TAGGED json whose body is not one is skipped, not fatal —
        // the parser reads the reply the way the prompt asked for it, and
        // the fallback is the same one untagged blocks get.
        val reply = "```json\noops not json\n```\n\n```\n$MANIFEST\n```\n\n```js\nok()\n```"
        val draft = PluginComposeParser.parse(reply) as PluginComposeParser.Parsed.Draft
        assertThat(draft.entrySource).isEqualTo("ok()\n")
    }

    @Test
    fun `a reply with no fenced blocks says so exactly`() {
        val parsed = PluginComposeParser.parse("I would rather chat about plugins.")
        assertThat((parsed as PluginComposeParser.Parsed.Invalid).errors).containsExactly(
            "no fenced code blocks were found - the reply must be exactly a ```json manifest block and a code block"
        )
    }

    @Test
    fun `a reply with no manifest block says so exactly`() {
        val parsed = PluginComposeParser.parse("```js\nexports.activate = 1\n```")
        assertThat((parsed as PluginComposeParser.Parsed.Invalid).errors).containsExactly(
            "no fenced block holding the plugin.json manifest was found"
        )
    }

    @Test
    fun `a reply with no code block says so exactly`() {
        val parsed = PluginComposeParser.parse("```json\n$MANIFEST\n```")
        assertThat((parsed as PluginComposeParser.Parsed.Invalid).errors).containsExactly(
            "no fenced code block holding the entry file was found"
        )
    }

    @Test
    fun `only the first eight blocks are considered`() {
        // The manifest rides in block nine: past MAX_BLOCKS, invisible.
        val padding = (1..8).joinToString("\n") { "```js\nfiller $it\n```" }
        val parsed = PluginComposeParser.parse("$padding\n```json\n$MANIFEST\n```")
        assertThat((parsed as PluginComposeParser.Parsed.Invalid).errors).containsExactly(
            "no fenced block holding the plugin.json manifest was found"
        )
    }

    @Test
    fun `validator errors are relayed verbatim and all at once`() {
        // Two broken rules in one manifest: the bad id and the network
        // permission without its allowlist — both listed, exactly as the
        // validator worded them.
        val manifest = """
            {"id":"Bad","name":"X","version":"0.1.0","api":1,
             "entry":"main.js","permissions":["network"]}
        """.trimIndent()
        val parsed = PluginComposeParser.parse("```json\n$manifest\n```\n\n```js\nok()\n```")
        assertThat((parsed as PluginComposeParser.Parsed.Invalid).errors).containsExactly(
            "id \"Bad\" is not valid: 3..64 chars, lowercase, at least one dot, " +
                "segments like com.example.hello",
            "network.allow is required when the network permission is declared: " +
                "a plugin may reach only the hosts it lists"
        ).inOrder()
    }

    @Test
    fun `an oversized manifest says so`() {
        // A manifest past the installer's own 64 KB cap is refused before its
        // rules are even read — the same number both sides of the door. (A
        // manifest this large necessarily breaks field caps too; both errors
        // are the honest answer.)
        val manifest = """{"id":"com.room.big","name":"B","version":"0.1.0","api":1,""" +
            """"entry":"main.js","description":"${"x".repeat(70_000)}"}"""
        val parsed = PluginComposeParser.parse("```json\n$manifest\n```\n\n```js\nok()\n```")
        val errors = (parsed as PluginComposeParser.Parsed.Invalid).errors
        assertThat(errors).contains("the composed manifest is larger than 64 KB")
        assertThat(errors).contains("description is longer than 500 characters")
    }

    @Test
    fun `an empty entry file says so exactly`() {
        val parsed = PluginComposeParser.parse("```json\n$MANIFEST\n```\n\n```js\n   \n```")
        assertThat((parsed as PluginComposeParser.Parsed.Invalid).errors).containsExactly(
            "the entry file is empty"
        )
    }

    @Test
    fun `oversized code says so exactly`() {
        val code = "x".repeat(PluginComposeParser.MAX_CODE_BYTES / 1024 * 1024 + 1)
        assertThat(code.toByteArray(Charsets.UTF_8).size).isGreaterThan(PluginComposeParser.MAX_CODE_BYTES)
        val parsed = PluginComposeParser.parse("```json\n$MANIFEST\n```\n\n```js\n$code\n```")
        assertThat((parsed as PluginComposeParser.Parsed.Invalid).errors).containsExactly(
            "the composed code is larger than 128 KB"
        )
    }

    @Test
    fun `retryNote renders the header and one line per error`() {
        assertThat(PluginComposeParser.retryNote(listOf("one", "two"))).isEqualTo(
            "Your previous attempt was refused for these reasons:\n- one\n- two"
        )
        assertThat(PluginComposeParser.retryNote(emptyList())).isEqualTo(
            "Your previous attempt was refused for these reasons:"
        )
    }

    // ------------------------------------------------------------ fixtures

    private val MANIFEST =
        """{"id":"com.room.counter","name":"Counter","version":"0.1.0",""" +
            """"description":"Counts greetings.","api":1,"entry":"main.js",
            "permissions":["storage"]}"""

    /** The happy-path reply: exactly what the prompt demands, nothing else. */
    private val REPLY =
        "```json\n$MANIFEST\n```\n\n```js\nexports.activate = async () => {}\n```"
}
