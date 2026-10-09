package com.roombrowser.domain.oct

/**
 * An `oct://` address: an Octra Circle and a canonical path inside it.
 *
 * The grammar is the one the reference client enforces, and it is strict on purpose —
 * `oct://` names a content-addressed store, so a path that the node would normalize
 * differently from us is a path we would fetch the wrong object for.
 */
data class OctUri(val circleId: String, val path: String) {

    /** The spelling to show and to store in a tab: scheme, id, canonical path. */
    val raw: String get() = "$PREFIX$circleId$path"

    companion object {
        const val SCHEME = "oct"
        const val PREFIX = "oct://"

        const val DEFAULT_PATH = "/index.html"

        /** A path longer than this is refused rather than fetched. */
        const val MAX_PATH_LENGTH = 1024

        /** `oct` plus 44 base58 characters — 47 in all. */
        private val CIRCLE_ID = Regex("^oct[1-9A-HJ-NP-Za-km-z]{44}$")

        fun isCircleId(value: String?): Boolean = value != null && CIRCLE_ID.matches(value)

        /**
         * Parse [uri], or null when it is not an `oct://` address at all.
         *
         * Query and fragment are dropped before anything else, so `oct://<id>/?x` and
         * `oct://<id>/#y` name the same circle. Decoding happens before the split, which
         * is why a path is canonicalized after it: `%2F` is a separator by then.
         */
        fun parse(uri: String?): OctUri? {
            if (uri == null) return null
            val decoded = percentDecode(uri.trim()) ?: return null
            if (!decoded.lowercase().startsWith(PREFIX)) return null
            val rest = decoded.substring(PREFIX.length).substringBeforeQueryOrFragment()
            val slash = rest.indexOf('/')
            val circleId = if (slash < 0) rest else rest.substring(0, slash)
            if (!isCircleId(circleId)) return null
            val path = canonicalPath(if (slash < 0) DEFAULT_PATH else rest.substring(slash)) ?: return null
            return OctUri(circleId, path)
        }

        /**
         * The path a fetch may ask for, or null when it may not be asked for at all.
         *
         * Empty segments and `.` are dropped; `..` is a rejection rather than a
         * collapse, because a traversing path is not a path this app should be
         * resolving on the reader's behalf.
         */
        fun canonicalPath(raw: String?): String? {
            if (raw == null) return null
            val decoded = percentDecode(raw.trim()) ?: return null
            val absolute = if (decoded.startsWith("/")) decoded else "/$decoded"
            val segments = absolute.split('/').filter { it.isNotEmpty() && it != "." }
            if (segments.any { it == ".." }) return null
            val canonical = if (segments.isEmpty()) "/" else segments.joinToString("/", prefix = "/")
            return canonical.takeIf { it.length <= MAX_PATH_LENGTH }
        }

        /**
         * [reference] as it is written inside a document whose own path is [base], as a
         * canonical circle path — or null when it does not name a fetch into this circle.
         *
         * This is relative-reference resolution, so unlike [canonicalPath] it has to *collapse*
         * `..`: inside a document `../x` is an ordinary reference and refusing it would break
         * well-formed circles. What still fails is `..` that survives collapsing — `%2e%2e`,
         * which decodes only later — because that is a path climbing out of the document root.
         */
        fun resolve(base: String, reference: String): String? {
            val trimmed = reference.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
            val spec = trimmed.substringBeforeQueryOrFragment()
            if (spec.isEmpty() || spec.startsWith("//")) return null
            val colon = spec.indexOf(':')
            val slash = spec.indexOf('/')
            if (colon >= 0 && (slash < 0 || colon < slash)) return null
            val merged = if (spec.startsWith("/")) spec else directoryOf(base) + spec
            return canonicalPath(collapseDotSegments(merged))
        }

        private fun directoryOf(base: String): String {
            val at = base.lastIndexOf('/')
            return if (at < 0) "/" else base.substring(0, at + 1)
        }

        private fun collapseDotSegments(path: String): String {
            val kept = ArrayList<String>()
            path.split('/').forEach { segment ->
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (kept.isNotEmpty()) kept.removeAt(kept.size - 1)
                    else -> kept.add(segment)
                }
            }
            return if (kept.isEmpty()) "/" else kept.joinToString("/", prefix = "/")
        }

        private fun String.substringBeforeQueryOrFragment(): String {
            val at = indexOfFirst { it == '?' || it == '#' }
            return if (at < 0) this else substring(0, at)
        }

        /**
         * Percent-decode the way `decodeURIComponent` does: `+` is a literal plus, and
         * an escape that is truncated or not valid UTF-8 is a failure rather than a
         * replacement character.
         */
        private fun percentDecode(value: String): String? {
            if ('%' !in value) return value
            val bytes = java.io.ByteArrayOutputStream(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '%') {
                    if (i + 2 >= value.length) return null
                    val high = value[i + 1].hexDigit() ?: return null
                    val low = value[i + 2].hexDigit() ?: return null
                    bytes.write((high shl 4) or low)
                    i += 3
                } else {
                    bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                    i += 1
                }
            }
            return runCatching {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray()))
                    .toString()
            }.getOrNull()
        }

        private fun Char.hexDigit(): Int? = when (this) {
            in '0'..'9' -> this - '0'
            in 'a'..'f' -> this - 'a' + 10
            in 'A'..'F' -> this - 'A' + 10
            else -> null
        }
    }
}
