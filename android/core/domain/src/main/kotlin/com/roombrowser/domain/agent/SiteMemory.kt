package com.roombrowser.domain.agent

import java.net.URI

/**
 * The pure half of the agent's per-site memory: which origin a URL files under,
 * what an entry may hold, and how a recall is rendered.
 *
 * The table lives in `:app`; everything that decides WHAT may be stored and how
 * it is bounded lives here, so the rules are covered by core tests with no Room
 * and no engine. Two of those rules are the reason this is a separate object:
 *
 *  - the origin comes from the URL the ENGINE reports, never from text the page
 *    supplied, so a page cannot name the bucket its memory is filed under;
 *  - the value is redacted before it is stored, because a page can show the
 *    agent a token and the agent may try to remember it.
 */
object SiteMemory {

    /** A key or a value longer than these is refused, not silently cut. */
    const val MAX_KEY_CHARS = 120
    const val MAX_VALUE_CHARS = 4000

    /** One origin may hold this many entries before a new key is refused. */
    const val MAX_KEYS_PER_ORIGIN = 100

    /** What recall returns by default, and the most it will ever return. */
    const val DEFAULT_RECALL_LIMIT = 20
    const val MAX_RECALL_LIMIT = 100

    /** The schemes a memory can be filed under; anything else has no origin. */
    private val SCHEMES = setOf("http", "https", "oct")

    /** One remembered entry, decoupled from the Room row that carries it. */
    data class Entry(val key: String, val value: String)

    /** Whether a key or a value may be stored, or why it may not. */
    sealed interface Check {
        data class Ok(val value: String) : Check
        data class Refused(val reason: String) : Check
    }

    /**
     * The origin a session URL files under, or null when it has none usable.
     *
     * The host is lowercased and a default port dropped, so the same site is
     * one bucket however it was spelled. about:, data: and blob: have no origin
     * of their own, so they are refused rather than guessed at.
     */
    fun originOf(url: String?): String? {
        val raw = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()?.takeIf { it in SCHEMES } ?: return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val defaultPort = when (scheme) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
        val port = uri.port
        return if (port == -1 || port == defaultPort) "$scheme://$host" else "$scheme://$host:$port"
    }

    /** The key to store under, or why it cannot be stored. */
    fun keyToStore(raw: String?): Check {
        val key = raw?.trim().orEmpty()
        if (key.isEmpty()) return Check.Refused("missing 'key' argument")
        if (key.length > MAX_KEY_CHARS) {
            return Check.Refused(
                "key is too long (${key.length} characters; the most is $MAX_KEY_CHARS)"
            )
        }
        return Check.Ok(key)
    }

    /** The redacted value to store, or why it cannot be stored. */
    fun valueToStore(raw: String?): Check {
        if (raw == null) return Check.Refused("missing 'value' argument for op=remember")
        val redacted = SecretRedaction.redact(raw).trim()
        if (redacted.isEmpty()) return Check.Refused("value is empty")
        if (redacted.length > MAX_VALUE_CHARS) {
            return Check.Refused(
                "value is too long (${redacted.length} characters; the most is $MAX_VALUE_CHARS)"
            )
        }
        return Check.Ok(redacted)
    }

    /**
     * Whether one more entry fits under an origin. Replacing a key that is
     * already stored always fits — the cap is on how many DISTINCT keys a site
     * may hold, not on how often one may be updated.
     */
    fun fitsUnderOrigin(existingCount: Int, keyAlreadyStored: Boolean): Boolean =
        keyAlreadyStored || existingCount < MAX_KEYS_PER_ORIGIN

    /** How many entries recall may return, clamped to this tool's own bounds. */
    fun recallLimit(requested: Int?): Int =
        (requested ?: DEFAULT_RECALL_LIMIT).coerceIn(1, MAX_RECALL_LIMIT)

    /** Renders a recall, bounded, and honest about an empty result. */
    fun renderRecall(origin: String, entries: List<Entry>, limit: Int): String {
        val shown = entries.take(recallLimit(limit))
        if (shown.isEmpty()) return "Nothing is remembered for $origin yet."
        val body = shown.joinToString("\n") { "- ${it.key}: ${it.value}" }
        val more = if (entries.size > shown.size) "\n(${entries.size - shown.size} more not shown)" else ""
        return "Remembered for $origin:\n$body$more"
    }

    /**
     * The refusal when the per-profile toggle is off, or null when it is on.
     * The message names the setting the user sees, so the model can tell the
     * person how to turn it on instead of retrying the same call.
     */
    fun refusalWhenDisabled(enabled: Boolean): String? =
        if (enabled) {
            null
        } else {
            "site_memory is switched off for this profile — turn on \"Let the agent remember " +
                "this site\" in AI Agent settings to let the agent keep notes about the sites it visits."
        }
}
