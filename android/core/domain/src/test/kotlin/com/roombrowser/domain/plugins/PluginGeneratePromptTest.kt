package com.roombrowser.domain.plugins

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.agent.AgentJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * The generation prompt: the fixed system half and the user half rendered
 * from the plugin's structured fields. What matters HERE is the rendering
 * contract — labelled data lines, only the members that say something, the
 * §4 caps re-held — because this text is the app's whole prompt-injection
 * posture: a plugin that would rather write instructions than fill fields
 * must find its words rendered as the data they are.
 */
class PluginGeneratePromptTest {

    @Test
    fun `the system prompt is the fixed app-owned text`() {
        // Verbatim from design §4: this is the one instruction set the model
        // reads, and a word that drifts here drifts for every generation.
        assertThat(PluginGeneratePrompt.SYSTEM_PROMPT).isEqualTo(
            "You write short pieces of text for a chat plugin. The user message describes what is " +
                "needed. Answer with the finished text only - no preamble, no quotation marks, no " +
                "markdown, no explanations. Honour every style constraint exactly. If a list of " +
                "texts to avoid is given, write something clearly different from every one of them."
        )
    }

    @Test
    fun `the design's own example renders as the design shows it`() {
        val messages = PluginGeneratePrompt.messagesOf(designExample())
        assertThat(messages).hasSize(2)
        assertThat(messages[0].role).isEqualTo("system")
        assertThat(messages[0].content).isEqualTo(PluginGeneratePrompt.SYSTEM_PROMPT)
        assertThat(messages[1].role).isEqualTo("user")
        assertThat(messages[1].content).isEqualTo(
            """
            Purpose: chat-reply
            What is needed: Reply to the last message in the general channel
            Context:
            alice: anyone tried the new puzzle app
            Style:
            - language: English
            - at most 24 words
            - exactly one sentence
            - never use any of these characters: ! : - - ? ;
            - tone: casual
            Avoid repeating any of these texts:
            - i keep coming back to the puzzle app
            Extra hint: keep it casual
            """.trimIndent()
        )
    }

    @Test
    fun `only the members that say something render`() {
        val user = userMessageOf(
            JsonObject(
                mapOf(
                    "purpose" to JsonPrimitive("chat-reply"),
                    "instruction" to JsonPrimitive("Reply to the last message"),
                    "style" to JsonObject(mapOf("maxWords" to JsonPrimitive(24)))
                )
            )
        )
        assertThat(user).isEqualTo(
            "Purpose: chat-reply\nWhat is needed: Reply to the last message\n" +
                "Style:\n- at most 24 words"
        )
    }

    @Test
    fun `blank members render nothing and the renderer never throws`() {
        // Blank is not a value: an empty context is not "there is no chat",
        // and an empty style object is not "no style constraints" spelled out
        // — both are silence, so both render silence.
        val user = userMessageOf(
            JsonObject(
                mapOf(
                    "purpose" to JsonPrimitive("p"),
                    "instruction" to JsonPrimitive("i"),
                    "context" to JsonPrimitive("   "),
                    "hint" to JsonPrimitive(""),
                    "style" to JsonObject(emptyMap()),
                    "avoid" to JsonArray(emptyList())
                )
            )
        )
        assertThat(user).isEqualTo("Purpose: p\nWhat is needed: i")
    }

    @Test
    fun `the lowercase flag renders and absent flags stay off the prompt`() {
        val user = userMessageOf(
            JsonObject(
                mapOf(
                    "purpose" to JsonPrimitive("p"),
                    "instruction" to JsonPrimitive("i"),
                    "style" to JsonObject(
                        mapOf(
                            "lowercase" to JsonPrimitive(true),
                            "oneSentence" to JsonPrimitive(false)
                        )
                    )
                )
            )
        )
        assertThat(user).isEqualTo("Purpose: p\nWhat is needed: i\nStyle:\n- all lowercase")
    }

    @Test
    fun `avoid renders only when it has texts`() {
        val base = mapOf(
            "purpose" to JsonPrimitive("p"),
            "instruction" to JsonPrimitive("i")
        )
        val empty = userMessageOf(JsonObject(base + ("avoid" to JsonArray(emptyList()))))
        assertThat(empty).doesNotContain("Avoid")
        // A blank entry is not a text to avoid; it is nothing to say.
        val blanks = userMessageOf(
            JsonObject(base + ("avoid" to JsonArray(listOf(JsonPrimitive("  ")))))
        )
        assertThat(blanks).doesNotContain("Avoid")
    }

    @Test
    fun `every field is clamped again at the renderer's own caps`() {
        // The request DTO clamps on receipt; this is the SECOND gate, so it
        // is tested with a flood no DTO ever let through: every line keeps
        // only its cap's worth, and the avoid list keeps fifty of two hundred.
        val flood = "x".repeat(9_999)
        val user = userMessageOf(
            JsonObject(
                mapOf(
                    "purpose" to JsonPrimitive(flood),
                    "instruction" to JsonPrimitive(flood),
                    "context" to JsonPrimitive(flood),
                    "hint" to JsonPrimitive(flood),
                    "avoid" to JsonArray((1..60).map { JsonPrimitive(flood) })
                )
            )
        )
        val lines = user.lineSequence().toList()
        assertThat(lines).hasSize(56)
        assertThat(lines[0]).isEqualTo("Purpose: " + "x".repeat(64))
        assertThat(lines[1]).isEqualTo("What is needed: " + "x".repeat(500))
        assertThat(lines[2]).isEqualTo("Context:")
        assertThat(lines[3]).isEqualTo("x".repeat(4_000))
        assertThat(lines[4]).isEqualTo("Avoid repeating any of these texts:")
        assertThat(lines[5]).isEqualTo("- " + "x".repeat(200))
        assertThat(lines[54]).isEqualTo("- " + "x".repeat(200))
        assertThat(lines[55]).isEqualTo("Extra hint: " + "x".repeat(200))
    }

    @Test
    fun `style members are clamped and the word count is bounded`() {
        val user = userMessageOf(
            JsonObject(
                mapOf(
                    "purpose" to JsonPrimitive("p"),
                    "instruction" to JsonPrimitive("i"),
                    "style" to JsonObject(
                        mapOf(
                            "language" to JsonPrimitive("x".repeat(99)),
                            "maxWords" to JsonPrimitive(5_000),
                            "forbiddenChars" to JsonPrimitive("x".repeat(99)),
                            "tone" to JsonPrimitive("x".repeat(99))
                        )
                    )
                )
            )
        )
        assertThat(user).isEqualTo(
            "Purpose: p\nWhat is needed: i\nStyle:\n" +
                "- language: " + "x".repeat(24) + "\n" +
                "- at most 200 words\n" +
                "- never use any of these characters: " + "x".repeat(64) + "\n" +
                "- tone: " + "x".repeat(64)
        )
    }

    // ------------------------------------------------------------ fixtures

    /** The rendered user message of [request] — the second of the two messages. */
    private fun userMessageOf(request: JsonObject): String =
        requireNotNull(PluginGeneratePrompt.messagesOf(request)[1].content)

    /** The §4 example request, exactly as the design renders it. */
    private fun designExample(): JsonObject = AgentJson.parseToJsonElement(
        """
        {
          "purpose": "chat-reply",
          "instruction": "Reply to the last message in the general channel",
          "context": "alice: anyone tried the new puzzle app",
          "hint": "keep it casual",
          "style": {
            "language": "English",
            "maxWords": 24,
            "oneSentence": true,
            "forbiddenChars": "! : - - ? ;",
            "tone": "casual"
          },
          "avoid": ["i keep coming back to the puzzle app"]
        }
        """.trimIndent()
    ).jsonObject
}
