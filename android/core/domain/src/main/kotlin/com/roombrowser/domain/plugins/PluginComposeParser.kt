package com.roombrowser.domain.plugins

import com.roombrowser.domain.agent.AgentJson
import kotlinx.serialization.json.JsonObject

/**
 * Parses what a model replied to [PluginComposePrompt] into a draft plugin,
 * or every reason the reply is not one.
 *
 * The reply is UNTRUSTED INPUT: nothing it carries renders or installs until
 * it has passed HERE, and "passed" means the same [PluginManifestValidator]
 * that gates installs — one rulebook, so a draft the chat's AI wrote is held
 * to exactly the sentences a sideloaded ZIP is. The parse itself is
 * deliberately tolerant (info strings present or absent, either block order,
 * prose around the blocks, more blocks than the two asked for): the prompt
 * demands exactly two fenced blocks, but a model that wraps them in one line
 * of courtesy still wrote a plugin, and refusing it over the courtesy would
 * spend the one corrective retry on nothing. What is NOT tolerated is any
 * ambiguity about which block is the manifest and which is the code — those
 * two identifications are the whole job.
 *
 * Refusals are sentences in the app's voice, gathered not thrown: the
 * caller shows the list to the person AND sends it back as
 * [retryNote] — the one corrective retry's entire input.
 */
object PluginComposeParser {

    /** The ceiling on the composed entry file; the transport clamp sits far above it. */
    const val MAX_CODE_BYTES = 128 * 1024

    /** At most this many fenced blocks are even considered — a rambling reply cannot bury the parse. */
    const val MAX_BLOCKS = 8

    private const val KB = 1024

    /**
     * The manifest ceiling the installer itself enforces (64 KB) — the same
     * number both sides of the one door, so a draft that would install
     * oversized is refused at the draft stage, with the reason named.
     */
    private const val MANIFEST_MAX_BYTES = 64 * KB

    /**
     * The fence the prompt demands, read leniently: an info string of word
     * characters and dashes (`json`, `js`, `javascript`, none) and a body
     * closed by the next fence — order and tagging are the parser's to work
     * out, not the reply's to get right.
     */
    private val FENCE = Regex("```[\\w-]*[ \\t]*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

    /** What a parse produced: a validated draft, or every reason there is none. */
    sealed interface Parsed {

        /** manifest: parsed AND validator-clean; entrySource: the code text, verbatim from its block. */
        data class Draft(val manifest: JsonObject, val entrySource: String) : Parsed

        /** Every reason the reply is not a draft — shown to the person, and the retry's input. */
        data class Invalid(val errors: List<String>) : Parsed
    }

    /**
     * Steps in the order design §5.5 fixes them: find the blocks, identify
     * the manifest and the code, then hold the manifest to the installer's
     * own size cap and full rulebook and the code to its own size and
     * non-emptiness — the refusals gathered, because a draft that breaks
     * three rules and is told about one is fixed three times slower.
     */
    fun parse(reply: String): Parsed {
        val blocks = FENCE.findAll(reply)
            .take(MAX_BLOCKS)
            .mapIndexed { index, match ->
                Fenced(index, match.value.drop(3).substringBefore('\n').trim(), match.groupValues[1])
            }
            .toList()
        if (blocks.isEmpty()) {
            return Parsed.Invalid(
                listOf(
                    "no fenced code blocks were found - the reply must be exactly a ```json manifest block and a code block"
                )
            )
        }

        // Every block that is a readable JSON object, with the object kept —
        // the manifest candidates, in reply order.
        val candidates = blocks.mapNotNull { block ->
            block.parsedObject()?.let { block to it }
        }

        // The manifest candidate: the first block TAGGED json that is a
        // readable object — a block tagged json whose body is not one is
        // skipped, not fatal, exactly as though it were untagged — else the
        // first readable object anywhere. No candidate at all is the refusal.
        val manifestPair = candidates.firstOrNull { (block, _) -> block.info.equals("json", ignoreCase = true) }
            ?: candidates.firstOrNull()
            ?: return Parsed.Invalid(listOf("no fenced block holding the plugin.json manifest was found"))
        val (manifestBlock, manifest) = manifestPair

        // The code candidate: among the REMAINING blocks, one tagged js or
        // javascript, else the longest — the entry file is the bulk of any
        // plugin, so when the model left the info strings off, length tells
        // the two apart where tagging cannot.
        val remaining = blocks.filterNot { it.index == manifestBlock.index }
        val codeBlock = remaining.firstOrNull {
            it.info.equals("js", ignoreCase = true) || it.info.equals("javascript", ignoreCase = true)
        }
            ?: remaining.maxByOrNull { it.body.length }
            ?: return Parsed.Invalid(listOf("no fenced code block holding the entry file was found"))

        val errors = mutableListOf<String>()
        if (manifest.toString().toByteArray(Charsets.UTF_8).size > MANIFEST_MAX_BYTES) {
            errors.add("the composed manifest is larger than ${MANIFEST_MAX_BYTES / KB} KB")
        }
        errors.addAll(PluginManifestValidator.validate(manifest))
        val entrySource = codeBlock.body
        if (entrySource.isBlank()) {
            errors.add("the entry file is empty")
        }
        if (entrySource.toByteArray(Charsets.UTF_8).size > MAX_CODE_BYTES) {
            errors.add("the composed code is larger than ${MAX_CODE_BYTES / KB} KB")
        }
        return if (errors.isEmpty()) Parsed.Draft(manifest, entrySource) else Parsed.Invalid(errors)
    }

    /**
     * The retry's user-half prefix: the header line and one `- <error>` per
     * error — the same line shape the definitive refusal notice uses, so the
     * person and the model read the same sentences.
     */
    fun retryNote(errors: List<String>): String =
        "Your previous attempt was refused for these reasons:\n" +
            errors.joinToString("\n") { "- $it" }

    /** One fenced block as the parser sees it: its position, its info string, its body verbatim. */
    private data class Fenced(val index: Int, val info: String, val body: String) {

        /** The body as a JSON object, or null when it does not read as one. */
        fun parsedObject(): JsonObject? = runCatching {
            AgentJson.parseToJsonElement(body.trim()) as? JsonObject
        }.getOrNull()
    }
}
