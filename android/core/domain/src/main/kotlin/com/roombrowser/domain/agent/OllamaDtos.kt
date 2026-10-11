package com.roombrowser.domain.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Local AI (Ollama) domain layer — pure JVM, no Android or OkHttp imports.
 *
 * Room Browser deliberately does NOT bundle llama.cpp: the app is a model
 * MANAGER for an Ollama server the user already runs (Termux on the same
 * phone, or a PC on the LAN). This file owns the complete domain vocabulary
 * for that feature so the OkHttp clients and the Compose UI can be built
 * against stable, wire-tested types:
 *
 *  1. [OllamaModelInfo] / [OllamaTagsParser] — installed-model inventory
 *     from `GET /api/tags` (flat DTO; the nested `details` block is folded
 *     into `family` / `parameterSize` / `quantizationLevel`).
 *  2. [OllamaPullEvent] / [OllamaPullParser] — the NDJSON progress stream of
 *     `POST /api/pull`, one JSON object per line.
 *  3. [OllamaModelPreset] / [OllamaModelPresets] — a curated, RAM-tiered
 *     catalog of phone-friendly models, so users never have to guess tags
 *     or discover too late that an 8 GB model OOMs a 4 GB phone.
 *     [OllamaLibraryParser] / [OllamaLibraryHeuristics] complement it with
 *     LIVE discovery against the public ollama.com/library listing, so new
 *     families published after a release still surface in the app.
 *  4. [LocalAiTuning] — the num_gpu / num_thread / num_ctx / keep_alive
 *     knobs that make local inference survivable on big.LITTLE hardware.
 *  5. [LocalAiBackup] — import/export manifest so a Termux reinstall does
 *     not mean re-typing host + tuning + model list by hand.
 *
 * Parsing follows the same philosophy as [ModelListParser]: Ollama's REST
 * shape drifts across versions and fronts (Termux build vs PC build, LAN
 * proxies), so every parser degrades to empty/null instead of throwing —
 * the UI then shows an honest "no models / retry" state, never a crash.
 */

// ---------- Chat wire shapes (POST /api/chat) ----------

/**
 * One message on Ollama's native `/api/chat` wire. Public (not private to the
 * gateway) so the images mapping is covered by a JVM test. [images] are raw
 * base64 payloads with NO `data:` prefix — Ollama's own field.
 */
@Serializable
data class OllamaChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OllamaChatToolCall>? = null,
    val images: List<String>? = null
)

/** Ollama's `tool_calls` entry: `arguments` is a real JSON OBJECT, not a string. */
@Serializable
data class OllamaChatToolCall(val function: OllamaChatFunctionCall)

@Serializable
data class OllamaChatFunctionCall(val name: String, val arguments: JsonObject)

// ---------- Installed inventory (GET /api/tags) ----------

/**
 * One installed model as listed by `GET /api/tags`.
 *
 * Flat on purpose: the wire nests `family` / `parameter_size` /
 * `quantization_level` inside a `details` object — [OllamaTagsParser] folds
 * them out so the UI can sort/filter without re-parsing JSON. [sizeBytes]
 * is the on-disk blob size (wire key `size`), NOT the download size: pulls
 * are resumable per layer and the server's blob cache may already hold
 * some layers, so progress math must use the pull stream, never this.
 */
@Serializable
data class OllamaModelInfo(
    /** repo:tag identity, e.g. "llama3.2:1b" — what /api/pull and /api/delete accept. */
    val name: String,
    /** Mirror of [name] that modern ollama also returns as `model`; empty when absent. */
    val model: String = "",
    val sizeBytes: Long = 0L,
    val digest: String = "",
    /**
     * RFC3339 timestamp kept as raw text: it is display-only, and parsing it
     * would add timezone/precision bugs for zero feature value.
     */
    val modifiedAt: String = "",
    /** GGUF family: "llama", "gemma2", "qwen2"… (feeds the tuning screen hints). */
    val family: String = "",
    /** "1.2B" — kept as text because vendors print "0.5B"/"1.0B" inconsistently. */
    val parameterSize: String = "",
    /** "Q4_K_M" etc. */
    val quantizationLevel: String = ""
)

/**
 * Parses `GET /api/tags` bodies leniently. Accepted shapes:
 *
 *   {"models":[{...}]}   canonical Ollama
 *   [{...},{...}]         bare array (older servers / proxies)
 *
 * Entries missing `details` (or with empty strings in it) still decode —
 * the detail fields default to "". Server order is preserved so the
 * Installed list matches what the user sees in `ollama list`.
 */
object OllamaTagsParser {

    fun parse(body: String): List<OllamaModelInfo> = runCatching {
        val root = AgentJson.parseToJsonElement(body.trim())
        val items: List<JsonElement> = when (root) {
            is JsonArray -> root.toList()
            is JsonObject -> (root["models"] as? JsonArray)?.toList() ?: emptyList()
            else -> emptyList()
        }
        items.mapNotNull { modelInfoOf(it) }
    }.getOrDefault(emptyList())

    private fun modelInfoOf(element: JsonElement): OllamaModelInfo? {
        val obj = element as? JsonObject ?: return null
        // Modern ollama returns both "name" and a "model" mirror; older
        // builds only "name". Accept either as identity, skip entries with
        // neither — they are server noise, not models worth showing.
        val name = str(obj, "name") ?: str(obj, "model") ?: return null
        val details = obj["details"] as? JsonObject
        return OllamaModelInfo(
            name = name,
            model = str(obj, "model") ?: "",
            sizeBytes = longOf(obj, "size"),
            digest = str(obj, "digest") ?: "",
            modifiedAt = str(obj, "modified_at") ?: "",
            family = details?.let { str(it, "family") } ?: "",
            parameterSize = details?.let { str(it, "parameter_size") } ?: "",
            quantizationLevel = details?.let { str(it, "quantization_level") } ?: ""
        )
    }

    private fun str(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun longOf(obj: JsonObject, key: String): Long {
        val p = obj[key] as? JsonPrimitive ?: return 0L
        // longOrNull rejects string primitives; fall back to text parsing so
        // lenient fronts that quote numbers still yield a usable size.
        return p.longOrNull ?: p.contentOrNull?.toLongOrNull() ?: 0L
    }
}

// ---------- Pull progress stream (POST /api/pull, NDJSON) ----------

/**
 * One progress line from `POST /api/pull` (NDJSON: one JSON object per line).
 *
 * [completed] / [total] describe the CURRENT layer only, not the whole pull:
 * every new layer resets `completed` to a lower value, so progress UIs must
 * treat the pair per-layer (or sum layers themselves) — never as a monotonic
 * overall counter. `total` can legitimately be absent or 0 while the
 * manifest is still being fetched or verified.
 */
@Serializable
data class OllamaPullEvent(
    /**
     * "pulling manifest" | "downloading" | "verifying sha256 digest" |
     * "writing manifest" | "success" | error text (see [OllamaPullParser]).
     */
    val status: String,
    val digest: String? = null,
    /** Bytes completed for the current layer. */
    val completed: Long? = null,
    /** Total bytes for the current layer. */
    val total: Long? = null
) {
    /** Terminal marker of a finished pull; an error line is NOT terminal (the stream just ends). */
    val isTerminal: Boolean
        get() = status == "success"

    /** True only for lines that carry usable per-layer byte counters. */
    val isDownloading: Boolean
        get() = status == "downloading" && completed != null && total != null && total > 0
}

object OllamaPullParser {

    /**
     * Parses ONE NDJSON line from `POST /api/pull`.
     *
     * @return the event, or null for blank / unparseable lines — lenient by
     *         contract and never throws, because a chunked HTTP read can
     *         split a line mid-UTF8 and the stream reader retries once the
     *         rest arrives. A server-side failure arrives as
     *         `{"error":"..."}` (no `status` key, so the strict decode
     *         fails); it is surfaced as an event whose status reads
     *         `"error: <text>"` so the download row can show WHY the pull
     *         stopped instead of silently freezing at the last byte count.
     */
    fun parseLine(line: String): OllamaPullEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val direct = runCatching {
            AgentJson.decodeFromString(OllamaPullEvent.serializer(), trimmed)
        }.getOrNull()
        if (direct != null) return direct
        val error = runCatching {
            val obj = AgentJson.parseToJsonElement(trimmed) as? JsonObject
                ?: return@runCatching null
            (obj["error"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }.getOrNull()
        return error?.let { OllamaPullEvent(status = "error: $it") }
    }
}

// ---------- Curated phone-model catalog ----------

/**
 * RAM-based buckets for the phone-model catalog. The UI groups presets by
 * tier and pre-selects [OllamaModelPresets.suggestedTierForRam] so a 4 GB
 * device never opens on a 7B model that would swap the phone to death.
 * `label` / `hint` are ready-made UI strings (English, consistent with the
 * rest of the agent copy). Serializable so a preset can travel through
 * caches/intents by name — enums encode as their identifier.
 */
@Serializable
enum class OllamaPresetTier(val label: String, val hint: String) {
    ULTRALIGHT("Ultra light", "Under 1 GB — for phones with 3–4 GB RAM"),
    LIGHT("Light", "1–2 GB — comfortable on 4–6 GB RAM phones"),
    BALANCED("Balanced", "2–4 GB — best quality on 6–8 GB RAM phones"),
    HEAVY("Heavy", "4 GB+ — flagship phones with 8 GB+ RAM, cooling and patience")
}

/**
 * A curated entry of the phone-model catalog.
 *
 * [sizeMb] is the APPROXIMATE download size of the default quantization
 * (q4) as published by the ollama library — good enough to warn "this is a
 * 4.7 GB download on mobile data", never used for progress math (the pull
 * stream carries real byte counts). [contextTokens] is the native training
 * context, shown as a hint only: the effective window is whatever
 * [LocalAiTuning.contextWindow] sends as num_ctx, and llama.cpp charges
 * RAM for the FULL window upfront.
 */
@Serializable
data class OllamaModelPreset(
    /** Exact `ollama pull` tag — passed verbatim to `POST /api/pull`. */
    val tag: String,
    /** Display name, e.g. "Qwen 2.5 · 1.5B". */
    val label: String,
    /** Printed parameter count, e.g. "0.5B". */
    val params: String,
    /** Approximate download size of the default q4 tag in megabytes. */
    val sizeMb: Int,
    /** Floor of total RAM (GB) this needs to be usable at all. */
    val minRamGb: Int,
    /** Native training context length in tokens. */
    val contextTokens: Int,
    /** One-line English description of what the model is good at. */
    val strengths: String,
    val tier: OllamaPresetTier,
    /** Curators' pick for the tier — exactly one per tier. */
    val recommended: Boolean = false,
    /** Handles Indonesian well enough for ID browsing tasks (Room Browser's home market). */
    val indonesianFriendly: Boolean = false
)

/**
 * The hand-curated catalog: 19 models across 4 RAM tiers, lightest first so
 * vertical catalogs read small → large. Numbers are q4 defaults, rounded;
 * they are advisory — the app never invents presets at runtime, because a
 * wrong tag silently pulls gigabytes. Fresh families published on
 * ollama.com AFTER a release are covered by the live library refresh
 * ([OllamaLibraryParser]) instead of edits here.
 */
object OllamaModelPresets {

    val PRESETS: List<OllamaModelPreset> = listOf(
        // ULTRALIGHT — under 1 GB download, runs on 3 GB RAM phones.
        OllamaModelPreset(
            tag = "smollm2:360m", label = "SmolLM 2 · 360M", params = "0.4B",
            sizeMb = 269, minRamGb = 3, contextTokens = 4096,
            strengths = "Tiny but surprisingly coherent chat",
            tier = OllamaPresetTier.ULTRALIGHT
        ),
        OllamaModelPreset(
            tag = "gemma3:270m", label = "Gemma 3 · 270M", params = "0.3B",
            sizeMb = 313, minRamGb = 3, contextTokens = 32768,
            strengths = "Google's tiniest Gemma 3 — capable for 270M",
            tier = OllamaPresetTier.ULTRALIGHT, indonesianFriendly = true
        ),
        OllamaModelPreset(
            tag = "qwen2.5:0.5b", label = "Qwen 2.5 · 0.5B", params = "0.5B",
            sizeMb = 397, minRamGb = 3, contextTokens = 32768,
            strengths = "Fastest starter with decent multilingual skills",
            tier = OllamaPresetTier.ULTRALIGHT, indonesianFriendly = true
        ),
        OllamaModelPreset(
            tag = "qwen3:0.6b", label = "Qwen 3 · 0.6B", params = "0.6B",
            sizeMb = 522, minRamGb = 3, contextTokens = 32768,
            strengths = "Newest tiny Qwen — thinking mode, decent multilingual",
            tier = OllamaPresetTier.ULTRALIGHT, indonesianFriendly = true
        ),
        OllamaModelPreset(
            tag = "tinyllama", label = "TinyLlama · 1.1B", params = "1.1B",
            sizeMb = 608, minRamGb = 3, contextTokens = 2048,
            strengths = "Classic tiny model — very fast, basic quality",
            tier = OllamaPresetTier.ULTRALIGHT
        ),
        OllamaModelPreset(
            tag = "gemma3:1b", label = "Gemma 3 · 1B", params = "1.0B",
            sizeMb = 815, minRamGb = 3, contextTokens = 32768,
            strengths = "Google's smallest — unusually strong for its size",
            tier = OllamaPresetTier.ULTRALIGHT, recommended = true, indonesianFriendly = true
        ),
        // LIGHT — 1–2 GB download, comfortable on 4–6 GB RAM phones.
        OllamaModelPreset(
            tag = "qwen2.5:1.5b", label = "Qwen 2.5 · 1.5B", params = "1.5B",
            sizeMb = 986, minRamGb = 4, contextTokens = 32768,
            strengths = "Proven speed/quality balance for phones",
            tier = OllamaPresetTier.LIGHT, indonesianFriendly = true
        ),
        OllamaModelPreset(
            tag = "qwen3:1.7b", label = "Qwen 3 · 1.7B", params = "1.7B",
            sizeMb = 1100, minRamGb = 4, contextTokens = 32768,
            strengths = "Latest compact Qwen — thinking mode, best new small model",
            tier = OllamaPresetTier.LIGHT, recommended = true, indonesianFriendly = true
        ),
        OllamaModelPreset(
            tag = "deepseek-r1:1.5b", label = "DeepSeek R1 · 1.5B", params = "1.5B",
            sizeMb = 1113, minRamGb = 4, contextTokens = 32768,
            strengths = "Tiny reasoning model with visible chain-of-thought",
            tier = OllamaPresetTier.LIGHT, indonesianFriendly = true
        ),
        OllamaModelPreset(
            tag = "llama3.2:1b", label = "Llama 3.2 · 1B", params = "1.0B",
            sizeMb = 1328, minRamGb = 4, contextTokens = 131072,
            strengths = "Meta's phone-first model, excellent multilingual",
            tier = OllamaPresetTier.LIGHT, indonesianFriendly = true
        ),
        OllamaModelPreset(
            tag = "gemma2:2b", label = "Gemma 2 · 2B", params = "2.6B",
            sizeMb = 1612, minRamGb = 4, contextTokens = 8192,
            strengths = "Balanced Google model with clean prose",
            tier = OllamaPresetTier.LIGHT, indonesianFriendly = true
        ),
        // BALANCED — 2–4 GB download, best quality on 6–8 GB RAM phones.
        OllamaModelPreset(
            tag = "qwen2.5:3b", label = "Qwen 2.5 · 3B", params = "3.1B",
            sizeMb = 1900, minRamGb = 6, contextTokens = 32768,
            strengths = "Best phone-quality trade-off; strong tool calling",
            tier = OllamaPresetTier.BALANCED, recommended = true, indonesianFriendly = true
        ),
        OllamaModelPreset(
            tag = "llama3.2:3b", label = "Llama 3.2 · 3B", params = "3.2B",
            sizeMb = 2010, minRamGb = 6, contextTokens = 131072,
            strengths = "Larger Llama 3.2 — top multilingual quality",
            tier = OllamaPresetTier.BALANCED, indonesianFriendly = true
        ),
        OllamaModelPreset(
            tag = "phi3.5", label = "Phi 3.5 · Mini", params = "3.8B",
            sizeMb = 2163, minRamGb = 6, contextTokens = 131072,
            strengths = "Microsoft's efficient long-context model",
            tier = OllamaPresetTier.BALANCED, indonesianFriendly = true
        ),
        OllamaModelPreset(
            tag = "qwen3:4b", label = "Qwen 3 · 4B", params = "4.0B",
            sizeMb = 2600, minRamGb = 6, contextTokens = 32768,
            strengths = "Newest mid-size Qwen with strong reasoning",
            tier = OllamaPresetTier.BALANCED, indonesianFriendly = true
        ),
        OllamaModelPreset(
            tag = "gemma3:4b", label = "Gemma 3 · 4B", params = "4.3B",
            sizeMb = 3336, minRamGb = 6, contextTokens = 32768,
            strengths = "Vision-capable Gemma 3 — quality jumps at 4B",
            tier = OllamaPresetTier.BALANCED, indonesianFriendly = true
        ),
        // HEAVY — 4 GB+ download, flagship phones with 8 GB+ RAM only.
        OllamaModelPreset(
            tag = "deepseek-r1:7b", label = "DeepSeek R1 · 7B", params = "7.6B",
            sizeMb = 4689, minRamGb = 8, contextTokens = 32768,
            strengths = "Full reasoning model with visible chain-of-thought",
            tier = OllamaPresetTier.HEAVY, indonesianFriendly = true
        ),
        OllamaModelPreset(
            tag = "qwen2.5:7b", label = "Qwen 2.5 · 7B", params = "7.1B",
            sizeMb = 4720, minRamGb = 8, contextTokens = 32768,
            strengths = "Flagship quality — needs a top phone and patience",
            tier = OllamaPresetTier.HEAVY, indonesianFriendly = true
        ),
        OllamaModelPreset(
            tag = "llama3.1:8b", label = "Llama 3.1 · 8B", params = "8.0B",
            sizeMb = 4930, minRamGb = 8, contextTokens = 131072,
            strengths = "Full-size assistant quality on flagship phones",
            tier = OllamaPresetTier.HEAVY, recommended = true, indonesianFriendly = true
        )
    )

    /**
     * "397 MB" under 1000, else "1.3 GB" with one decimal (truncated).
     * Integer math on purpose: no locale-dependent decimal separator and no
     * float rounding drift — the catalog must render byte-identically in
     * unit tests and on every device locale (id-ID prints ',' as decimal).
     */
    fun formatSizeMb(mb: Int): String {
        if (mb < 1000) return "$mb MB"
        val whole = mb / 1000
        val tenth = (mb % 1000) / 100
        return "$whole.$tenth GB"
    }

    /** Presets of one tier; lightest-first order preserved. */
    fun byTier(tier: OllamaPresetTier): List<OllamaModelPreset> =
        PRESETS.filter { it.tier == tier }

    /** Exact (trimmed, case-sensitive) tag lookup — ollama never case-folds tags. */
    fun find(tag: String): OllamaModelPreset? =
        PRESETS.firstOrNull { it.tag == tag.trim() }

    /**
     * RAM-based suggestion for the catalog header: <4 GB → ULTRALIGHT,
     * <6 → LIGHT, <8 → BALANCED, else HEAVY. Deliberately conservative —
     * Android itself eats ~2 GB, so a "6 GB" phone behaves like a 4 GB one
     * once a resident LLM and the browser process both want RAM.
     */
    fun suggestedTierForRam(totalRamGb: Long): OllamaPresetTier = when {
        totalRamGb < 4L -> OllamaPresetTier.ULTRALIGHT
        totalRamGb < 6L -> OllamaPresetTier.LIGHT
        totalRamGb < 8L -> OllamaPresetTier.BALANCED
        else -> OllamaPresetTier.HEAVY
    }
}

// ---------- Live library discovery (ollama.com/library) ----------

/**
 * One model FAMILY scraped from the PUBLIC ollama.com/library listing —
 * the fuel of the "Find new models" refresh: the curated presets are frozen
 * at release time, while the library keeps receiving new families.
 *
 * Shape (validated against the real listing): each entry is an `<li>` block
 * with an `href="/library/<name>"` anchor, a description paragraph, indigo
 * capability badges (tools / thinking / vision / audio / embedding) and
 * BLUE PARAMETER-SIZE badges whose text IS a pullable tag suffix
 * ("1.5b", "270m"…). [OllamaLibraryParser] folds that block into this flat
 * DTO; page order (newest-first for `?sort=newest`) is preserved.
 */
@Serializable
data class OllamaLibraryEntry(
    /** Family name, e.g. "qwen3.5" — the part before ":" in a pull tag. */
    val name: String,
    /** One-line blurb from the listing; HTML entities unescaped; "" when absent. */
    val description: String = "",
    /** "2 weeks ago" style freshness label straight from the page; "" when absent. */
    val updatedAt: String = "",
    /** Indigo capability badges: "tools", "thinking", "vision", "audio", "embedding". */
    val capabilities: List<String> = emptyList(),
    /** Blue parameter-size badges in page order: ["0.8b", "2b", "27b", "122b"]. */
    val sizeTags: List<String> = emptyList()
)

/**
 * Lenient scraper for the ollama.com/library listing (works for the default
 * popularity order AND `?sort=newest`). Regex-based on purpose: no HTML
 * parser dependency, and any markup drift degrades to fewer fields (empty
 * description / no badges) instead of throwing — the same contract as
 * [OllamaTagsParser]. Verified shape (2026):
 *
 * ```html
 * <li class="flex items-baseline border-b …">
 *   <a href="/library/deepseek-r1" class="group w-full space-y-5">
 *     <div title="deepseek-r1" …>…<span …>deepseek-r1</span>…</div>
 *     <p class="max-w-lg …">DeepSeek-R1 is a family of open reasoning…</p>
 *     … <span class="…text-indigo-600…">tools</span>
 *     … <span class="…text-blue-600…">1.5b</span> …
 *     … <span class="hidden sm:flex">Updated&nbsp;</span><span >1 year ago</span>
 *   </a>
 * </li>
 * ```
 */
object OllamaLibraryParser {

    private val BLOCK = Regex(
        """<li[^>]*flex items-baseline.*?</li>""",
        RegexOption.DOT_MATCHES_ALL
    )
    // NB: no trailing `"` in the regex — a Kotlin raw string cannot end with
    // a quote char (the closing delimiter would swallow it), and the capture
    // is identical anyway: the char class stops at the attribute's quote.
    private val NAME = Regex("""href="/library/([a-z0-9._-]+)""")
    private val DESCRIPTION = Regex("""<p class="max-w-lg[^"]*">([^<]*)</p>""")
    private val SIZE_BADGE = Regex("""text-blue-600[^>]*>\s*([^<]+?)\s*</span>""")
    private val CAPABILITY = Regex("""text-indigo-600[^>]*>\s*([^<]+?)\s*</span>""")
    private val UPDATED = Regex("""Updated[^<]*</span>\s*<span[^>]*>\s*([^<]+?)\s*</span>""")

    /**
     * Parses the listing page; NEVER throws (garbage/empty → empty list).
     * Entries are deduped by name and keep page order.
     */
    fun parse(html: String): List<OllamaLibraryEntry> = runCatching {
        BLOCK.findAll(html)
            .mapNotNull { match ->
                val block = match.value
                val name = NAME.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
                OllamaLibraryEntry(
                    name = name,
                    description = matchGroup(DESCRIPTION, block)?.let(::unescapeEntities)?.trim() ?: "",
                    updatedAt = matchGroup(UPDATED, block)?.trim() ?: "",
                    capabilities = CAPABILITY.findAll(block)
                        .mapNotNull { it.groupValues[1].trim().takeIf { badge -> badge.isNotEmpty() } }
                        .distinct()
                        .toList(),
                    sizeTags = SIZE_BADGE.findAll(block)
                        .mapNotNull { it.groupValues[1].trim().takeIf { badge -> badge.isNotEmpty() } }
                        .distinct()
                        .toList()
                )
            }
            .distinctBy { it.name }
            .toList()
    }.getOrDefault(emptyList())

    private fun matchGroup(regex: Regex, block: String): String? =
        regex.find(block)?.groupValues?.get(1)

    /**
     * Minimal HTML-entity unescape for descriptions: numeric (`&#39;`,
     * `&#x27;`) plus the handful of named entities ollama.com actually uses.
     * Unknown entities are left verbatim — never a crash.
     */
    private fun unescapeEntities(text: String): String {
        if (!text.contains('&')) return text
        val named = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"",
            "apos" to "'", "nbsp" to " "
        )
        return Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);").replace(text) { match ->
            val token = match.groupValues[1]
            when {
                token.length > 2 && token[0] == '#' && (token[1] == 'x' || token[1] == 'X') ->
                    token.substring(2).toIntOrNull(16)?.toChar()?.toString() ?: match.value
                token.startsWith("#") ->
                    token.substring(1).toIntOrNull()?.toChar()?.toString() ?: match.value
                else -> named[token.lowercase()] ?: match.value
            }
        }
    }
}

/**
 * Phone-suitability rules for DISCOVERED families — mirrors the curatorial
 * judgment baked into the static presets, applied at runtime to whatever
 * the live library returned:
 *
 *  - a size badge is phone-usable when its parameter count is ≤ [PHONE_PARAMS_MAX]
 *    (4B ≈ 2.7 GB q4 download — beyond that a phone thrashes or OOMs);
 *  - MoE "e"-badges (effective params, e.g. "e4b") and "128x17b"-style total×active
 *    badges carry NO honest download estimate → informational only, not installable;
 *  - embedding-only families (bge-*, nomic-embed…) cannot chat → not candidates.
 */
object OllamaLibraryHeuristics {

    /** Params ceiling for a phone-usable discovered tag (billions). */
    const val PHONE_PARAMS_MAX = 4.0

    private val PLAIN_SIZE = Regex("""^[0-9]+(?:\.[0-9]+)?[bm]$""")
    private val PARAMS = Regex("""^e?([0-9]+(?:\.[0-9]+)?)([bm])$""")

    /**
     * Parameter count of a badge: "1.5b" → 1.5, "270m" → 0.27, "e4b" → 4.0
     * (MoE EFFECTIVE params). "128x17b", "latest", "q4_0" → null.
     */
    fun badgeParams(badge: String): Double? {
        val match = PARAMS.find(badge.trim().lowercase()) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        return if (match.groupValues[2] == "m") value / 1000.0 else value
    }

    /** Install-able badge: a PLAIN params tag whose download size is estimable. */
    fun isPlainSizeBadge(badge: String): Boolean =
        PLAIN_SIZE.matches(badge.trim().lowercase())

    /** The entry's size badges that are phone-usable, in page order. */
    fun phoneSizes(entry: OllamaLibraryEntry): List<String> =
        entry.sizeTags.filter { badge ->
            isPlainSizeBadge(badge) && (badgeParams(badge) ?: Double.MAX_VALUE) <= PHONE_PARAMS_MAX
        }

    /** Chat-able: families whose ONLY capability is "embedding" are excluded. */
    fun isChatCandidate(entry: OllamaLibraryEntry): Boolean =
        entry.capabilities.isEmpty() || entry.capabilities.any { it != "embedding" }

    /**
     * Live families the curated presets do NOT cover yet: newest page order
     * preserved, chat-able, at least one phone-usable size badge.
     */
    fun discoverPhoneModels(
        entries: List<OllamaLibraryEntry>,
        knownFamilies: Set<String>
    ): List<OllamaLibraryEntry> = entries.filter { entry ->
        entry.name !in knownFamilies && isChatCandidate(entry) && phoneSizes(entry).isNotEmpty()
    }

    /**
     * Rough q4 DOWNLOAD estimate in MB: 150 MB fixed + 650 MB per parameter
     * billion (integer math; renders through [OllamaModelPresets.formatSizeMb]).
     * Calibrated: 0.27B→325 (real 313), 0.8B→680 (real 660), 1.7B→1255 (real
     * 1100), 4B→2750 (real 2600) — always labeled "est." in the UI; the pull
     * stream shows the real bytes.
     */
    fun estimatedDownloadMb(params: Double): Int = (150 + 650 * params).toInt()
}

// ---------- Tuning + backup ----------

/**
 * The tuning knobs the Local AI screen exposes. Field → Ollama
 * `POST /api/chat` `options` mapping (native OLLAMA protocol, not /v1):
 *
 *   gpuLayers        → num_gpu    (null = AUTO: server/llama.cpp decides offload)
 *   cpuThreads       → num_thread (null = AUTO)
 *   contextWindow    → num_ctx    (llama.cpp pre-allocates the KV cache for
 *                                  the FULL window — 16k on a 3B model ≈ +1 GB)
 *   keepAliveMinutes → keep_alive "Nm" (how long the model stays resident
 *                                  after the last reply; 0 = unload instantly)
 *
 * [host] defaults to localhost because the flagship setup is ollama inside
 * Termux on the same phone; a LAN PC is a one-line edit in the UI.
 */
@Serializable
data class LocalAiTuning(
    val host: String = "http://localhost:11434",
    val gpuLayers: Int? = null,
    val cpuThreads: Int? = null,
    val contextWindow: Int = 2048,
    val keepAliveMinutes: Int = 5
) {
    /**
     * Clamps to sane phone ranges: ctx 512..16384, keepAlive 0..60 minutes,
     * gpu 0..99 layers, threads 1..16 (big.LITTLE cores, plus headroom for
     * PC servers on LAN). AUTO (null) survives as null — clamping must
     * never silently turn "server decides" into a guess.
     */
    fun clampToSanity(): LocalAiTuning = copy(
        host = host.trim(),
        gpuLayers = gpuLayers?.coerceIn(0, 99),
        cpuThreads = cpuThreads?.coerceIn(1, 16),
        contextWindow = contextWindow.coerceIn(512, 16384),
        keepAliveMinutes = keepAliveMinutes.coerceIn(0, 60)
    )
}

/**
 * SAF export payload for "Import / Export setup". Deliberately minimal and
 * additive: `kind` + `version` gate the format, [models] is the repo:tag
 * list captured at export time (a hint of what to re-pull on a fresh Termux
 * install — the blob files themselves are NOT exported; they are
 * gigabytes and belong to the server's blob cache).
 */
@Serializable
data class LocalAiBackupManifest(
    val kind: String = "room-browser-local-ai",
    val version: Int = 1,
    val host: String,
    val tuning: LocalAiTuning,
    /** Installed model names (repo:tag) at export time. */
    val models: List<String>,
    val exportedAtEpochMs: Long
)

/**
 * Encodes/decodes the backup manifest for SAF import/export (the file
 * extension and picking UI belong to the app layer). Decode is strict on
 * PURPOSE: a wrong `kind`, an unknown `version`, or a blank host must
 * yield null — silently accepting an unrelated JSON file would import
 * garbage into the Local AI settings.
 */
object LocalAiBackup {
    private const val KIND = "room-browser-local-ai"
    private const val VERSION = 1

    fun encode(manifest: LocalAiBackupManifest): String =
        AgentJson.encodeToString(LocalAiBackupManifest.serializer(), manifest)

    /** @return the manifest, or null on ANY parse/validation failure. Never throws. */
    fun decode(text: String): LocalAiBackupManifest? = runCatching {
        val manifest = AgentJson.decodeFromString(LocalAiBackupManifest.serializer(), text)
        manifest.takeIf { it.kind == KIND && it.version == VERSION && it.host.isNotBlank() }
    }.getOrNull()
}
