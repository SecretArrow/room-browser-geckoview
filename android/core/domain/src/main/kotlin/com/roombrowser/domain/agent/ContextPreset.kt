package com.roombrowser.domain.agent

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A saved "default context" the user can re-apply without retyping it.
 *
 * Addressed by [id] everywhere, so two presets may share a name without one of
 * them becoming unreachable — [name] is a label for the list, nothing more.
 */
@Serializable
data class ContextPreset(
    val id: String,
    val name: String,
    val text: String
)

/**
 * The rules for the saved contexts, kept out of the activity so they can be
 * tested without a device. The whole library rides in the agent-settings blob
 * and therefore needs a bound ([MAX_PRESETS]).
 */
object ContextPresets {

    const val MAX_PRESETS = 50

    const val NAME_MAX_CHARS = 48

    private const val SNIPPET_MAX_CHARS = 96

    private val WHITESPACE = Regex("\\s+")

    fun newId(): String = UUID.randomUUID().toString()

    /**
     * A preset carrying [text], or null when there is no text to save — an
     * empty preset is not a preset. A blank [name] is derived from the text's
     * first line, because naming is the part a user abandons first.
     */
    fun of(id: String, name: String, text: String): ContextPreset? {
        val body = text.trim()
        if (body.isEmpty()) return null
        return ContextPreset(id = id.ifBlank { newId() }, name = label(name, body), text = body)
    }

    /** [name] collapsed to one line and capped, falling back to the text's first line. */
    fun label(name: String, text: String): String {
        val base = name.replace(WHITESPACE, " ").trim()
            .ifEmpty { text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty() }
            .ifEmpty { "Untitled context" }
        return if (base.length <= NAME_MAX_CHARS) base
        else base.take(NAME_MAX_CHARS).trimEnd() + "…"
    }

    /** Replaces the preset with the same [ContextPreset.id], else appends it. */
    fun upsert(presets: List<ContextPreset>, preset: ContextPreset): List<ContextPreset> {
        val index = presets.indexOfFirst { it.id == preset.id }
        if (index >= 0) return presets.toMutableList().apply { this[index] = preset }
        if (presets.size >= MAX_PRESETS) return presets
        return presets + preset
    }

    fun remove(presets: List<ContextPreset>, id: String): List<ContextPreset> =
        presets.filterNot { it.id == id }

    /** One line for a list row. */
    fun snippet(text: String): String {
        val collapsed = text.replace(WHITESPACE, " ").trim()
        return if (collapsed.length <= SNIPPET_MAX_CHARS) collapsed
        else collapsed.take(SNIPPET_MAX_CHARS).trimEnd() + "…"
    }
}
