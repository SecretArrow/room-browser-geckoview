package com.roombrowser.domain.plugins

import com.roombrowser.domain.agent.ChatMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The prompt the app renders for one `room.agent.generate` call: a fixed
 * system half and a user half rendered from the plugin's structured fields.
 *
 * WHY THE APP OWNS THIS TEXT. The plugin supplies fields (purpose,
 * instruction, context, style, texts to avoid) — never a raw prompt — and the
 * system prompt is app-owned and fixed, so the only instructions the model
 * reads are the app's own. That is the whole prompt-injection posture: the
 * plugin's strings are clamped (the §4 caps, re-clamped here — the request
 * DTO clamps on receipt and this second gate covers a request that arrives
 * from anywhere else) and rendered as labelled data lines under a system
 * prompt that commands "answer with the finished text only", so anything a
 * plugin smuggles into a field reads as the data it is.
 *
 * The renderer never throws and never invents a field: an absent or blank
 * member renders nothing (refusing a purposeless request is the validator's
 * job, on both sides of the wire), so the model simply sees the shorter
 * description. Provider, key, URL and model stay unnamed here — the plugin
 * must never learn any of them, and this object never could.
 */
object PluginGeneratePrompt {

    /** The fixed, app-owned system prompt — design §4, verbatim. */
    const val SYSTEM_PROMPT =
        "You write short pieces of text for a chat plugin. The user message describes what is " +
            "needed. Answer with the finished text only - no preamble, no quotation marks, no " +
            "markdown, no explanations. Honour every style constraint exactly. If a list of " +
            "texts to avoid is given, write something clearly different from every one of them."

    private const val PURPOSE_CHARS = 64
    private const val INSTRUCTION_CHARS = 500
    private const val CONTEXT_CHARS = 4_000
    private const val HINT_CHARS = 200
    private const val LANGUAGE_CHARS = 24
    private const val MAX_WORDS = 200
    private const val FORBIDDEN_CHARS = 64
    private const val TONE_CHARS = 64
    private const val MAX_AVOID_ENTRIES = 50
    private const val AVOID_ENTRY_CHARS = 200

    /** The two messages one generation is sent as: the fixed system prompt and the rendered fields. */
    fun messagesOf(request: JsonObject): List<ChatMessage> {
        val blocks = mutableListOf<String>()
        request.text("purpose", PURPOSE_CHARS)?.let { blocks.add("Purpose: $it") }
        request.text("instruction", INSTRUCTION_CHARS)?.let { blocks.add("What is needed: $it") }
        request.text("context", CONTEXT_CHARS)?.let { blocks.add("Context:\n$it") }
        styleLines(request)?.let { blocks.add("Style:\n" + it.joinToString("\n")) }
        val avoid = avoidTexts(request)
        if (avoid.isNotEmpty()) {
            blocks.add("Avoid repeating any of these texts:\n" + avoid.joinToString("\n") { "- $it" })
        }
        request.text("hint", HINT_CHARS)?.let { blocks.add("Extra hint: $it") }
        return listOf(
            ChatMessage(role = "system", content = SYSTEM_PROMPT),
            ChatMessage(role = "user", content = blocks.joinToString("\n"))
        )
    }

    /**
     * The style block's lines, or null when no style member is present. Every
     * member is optional and absent members render nothing — a plugin that
     * only wants short text sends `{"maxWords":24}` and the model reads one
     * line, not five "unspecified" ones.
     */
    private fun styleLines(request: JsonObject): List<String>? {
        val style = (request["style"] as? JsonObject) ?: return null
        val lines = mutableListOf<String>()
        style.text("language", LANGUAGE_CHARS)?.let { lines.add("- language: $it") }
        (style["maxWords"] as? JsonPrimitive)?.intOrNull?.coerceIn(1, MAX_WORDS)?.let {
            lines.add("- at most $it words")
        }
        if (style.flag("oneSentence")) lines.add("- exactly one sentence")
        if (style.flag("lowercase")) lines.add("- all lowercase")
        style.text("forbiddenChars", FORBIDDEN_CHARS)?.let {
            lines.add("- never use any of these characters: $it")
        }
        style.text("tone", TONE_CHARS)?.let { lines.add("- tone: $it") }
        return lines.takeIf { it.isNotEmpty() }
    }

    /** The texts to avoid, clamped again: blank entries carry no signal and render nothing. */
    private fun avoidTexts(request: JsonObject): List<String> =
        ((request["avoid"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
            .orEmpty()
            .filter { it.isNotBlank() }
            .take(MAX_AVOID_ENTRIES)
            .map { it.take(AVOID_ENTRY_CHARS) }

    /** The member as a trimmed, non-blank string clamped to [cap], or null when it says nothing. */
    private fun JsonObject.text(key: String, cap: Int): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
            ?.trim()
            ?.take(cap)
            ?.takeIf { it.isNotEmpty() }

    /** The member as a flag: absent, non-boolean and false all read as false. */
    private fun JsonObject.flag(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
}
