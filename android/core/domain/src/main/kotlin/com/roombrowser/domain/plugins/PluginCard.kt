package com.roombrowser.domain.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray

/** The tint a card asks for; [INFO] is what an unspecified style means. */
enum class PluginCardStyle {
    INFO,
    SUCCESS,
    WARN,
    ERROR
}

/** A card's optional link row: a label and an http(s) URL, nothing else. */
data class PluginCardLink(
    val label: String,
    val url: String
)

/**
 * One card a plugin asked the chat to show — the plugin's only output that
 * reaches the USER rather than the model.
 *
 * This is the app-side model of the wire card (design §6): the host already
 * validated and clamped it, and this parse clamps again. Two honest gates are
 * better than one because the second one also covers hand-written replies and
 * future hosts: a card that arrives malformed is dropped here rather than
 * rendered, and a card that arrives overlong is cut to the caps the UI can
 * actually lay out.
 */
data class PluginCard(
    /** ≤ 120 chars; null when the card has no title. */
    val title: String? = null,
    /** ≤ 4000 chars; null when the card has no body. */
    val text: String? = null,
    /** ≤ 12 label/value pairs, each side ≤ 200 chars. */
    val fields: List<Pair<String, String>> = emptyList(),
    /** Dropped entirely unless the URL is http(s) and ≤ 500 chars. */
    val link: PluginCardLink? = null,
    val style: PluginCardStyle = PluginCardStyle.INFO
) {
    companion object {
        /** The cap the host and the UI agree on: more than this is noise, not content. */
        const val MAX_CARDS = 5

        private const val TITLE_CHARS = 120
        private const val TEXT_CHARS = 4000
        private const val FIELD_CHARS = 200
        private const val MAX_FIELDS = 12
        private const val LINK_LABEL_CHARS = 64
        private const val LINK_URL_CHARS = 500

        /**
         * Every card of a reply's `cards` array, clamped and capped at
         * [MAX_CARDS]. Never throws: an element that is not an object, or an
         * object with nothing renderable in it, is dropped — the plugin's logs
         * (kept by the host) say which, and a chat that cannot render a row
         * must not lose the rows after it.
         */
        fun parseList(element: JsonElement?): List<PluginCard> {
            val array = (element as? JsonArray) ?: return emptyList()
            return array.jsonArray.mapNotNull { parse(it as? JsonObject) }.take(MAX_CARDS)
        }

        /**
         * One card, or null when [obj] has nothing a card can show. Clamping
         * over a blank is a no-op: a blank title becomes null rather than an
         * empty bold line.
         */
        fun parse(obj: JsonObject?): PluginCard? {
            if (obj == null) return null
            val title = obj.string("title")?.trim()?.takeIf { it.isNotEmpty() }?.take(TITLE_CHARS)
            val text = obj.string("text")?.take(TEXT_CHARS)
            val fields = (obj["fields"] as? JsonArray)
                ?.jsonArray
                ?.mapNotNull { entry ->
                    val pair = entry as? JsonArray ?: return@mapNotNull null
                    val values = pair.jsonArray.map { (it as? JsonPrimitive)?.contentOrNull ?: "" }
                    if (values.size != 2) return@mapNotNull null
                    values[0].take(FIELD_CHARS) to values[1].take(FIELD_CHARS)
                }
                ?.take(MAX_FIELDS)
                .orEmpty()
            val link = (obj["link"] as? JsonObject)?.let { link ->
                val url = link.string("url")?.takeIf {
                    it.length <= LINK_URL_CHARS && (it.startsWith("http://") || it.startsWith("https://"))
                }
                // A link that is not http(s) is not opened by anything in this
                // app, so it is dropped rather than shown as a dead row.
                url?.let { PluginCardLink(label = link.string("label")?.take(LINK_LABEL_CHARS).orEmpty(), url = it) }
            }
            if (title == null && text.isNullOrBlank() && fields.isEmpty() && link == null) return null
            return PluginCard(
                title = title,
                text = text?.takeIf { it.isNotEmpty() },
                fields = fields,
                link = link,
                style = styleOf(obj.string("style"))
            )
        }

        /** The style a wire card asked for; unknown or absent means [PluginCardStyle.INFO]. */
        fun styleOf(raw: String?): PluginCardStyle = when (raw) {
            "success" -> PluginCardStyle.SUCCESS
            "warn" -> PluginCardStyle.WARN
            "error" -> PluginCardStyle.ERROR
            else -> PluginCardStyle.INFO
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull
    }
}

/**
 * The card back as the wire shape it arrived in — the same spelling [PluginCard.parse]
 * reads, so a card stored in a transcript row comes back identical to the one
 * the chat showed.
 */
fun PluginCard.toJson(): JsonObject = JsonObject(
    buildMap {
        title?.let { put("title", JsonPrimitive(it)) }
        text?.let { put("text", JsonPrimitive(it)) }
        if (fields.isNotEmpty()) {
            put(
                "fields",
                JsonArray(
                    fields.map { pair ->
                        JsonArray(listOf(JsonPrimitive(pair.first), JsonPrimitive(pair.second)))
                    }
                )
            )
        }
        link?.let {
            put(
                "link",
                JsonObject(
                    mapOf(
                        "label" to JsonPrimitive(it.label),
                        "url" to JsonPrimitive(it.url)
                    )
                )
            )
        }
        put("style", JsonPrimitive(style.wireName))
    }
)

/** The wire spelling of a style; the identity of [PluginCard.parse]'s input. */
private val PluginCardStyle.wireName: String
    get() = when (this) {
        PluginCardStyle.INFO -> "info"
        PluginCardStyle.SUCCESS -> "success"
        PluginCardStyle.WARN -> "warn"
        PluginCardStyle.ERROR -> "error"
    }
