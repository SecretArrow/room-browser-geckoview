package com.roombrowser.devtools

import com.roombrowser.engine.devtools.EngineConsoleMessage
import com.roombrowser.engine.devtools.EngineNetworkSignal
import kotlinx.serialization.Serializable

/** One header, kept as a pair so a repeated name is not silently collapsed. */
@Serializable
data class HeaderEntry(val name: String, val value: String)

/** One console entry as the panel holds it. */
@Serializable
data class ConsoleEntry(
    val level: String,
    val text: String,
    val source: String? = null,
    val line: Int? = null,
    val timestampMs: Long = 0L,
    val fromEngine: Boolean = false
)

/**
 * One network row as the panel holds it.
 *
 * The same shape carries a request the engine reported and a resource the page
 * reported through `performance`, so a row read from either source renders the
 * same way. [sizesHidden] is the page-probe's verdict that a cross-origin entry
 * withheld its sizes — rendered as hidden, never as a zero.
 *
 * [documentUrl] is present only for an engine that reports it, which is what
 * makes a browser-wide capture readable: a row says which document it belongs
 * to instead of being presented as this tab's.
 */
@Serializable
data class NetworkEntry(
    val kind: String,
    val url: String,
    val method: String? = null,
    val status: Int? = null,
    val requestHeaders: List<HeaderEntry> = emptyList(),
    val responseHeaders: List<HeaderEntry> = emptyList(),
    val isForMainFrame: Boolean? = null,
    val resourceType: String? = null,
    val timestampMs: Long = 0L,
    val durationMs: Double? = null,
    val transferSize: Double? = null,
    val sizesHidden: Boolean = false,
    val note: String? = null,
    val documentUrl: String? = null
)

/** One `PerformanceResourceTiming` as the page probe reports it. */
@Serializable
data class ResourceTiming(
    val name: String,
    val initiatorType: String? = null,
    val startTime: Double? = null,
    val duration: Double? = null,
    val transferSize: Double? = null,
    val encodedBodySize: Double? = null,
    val decodedBodySize: Double? = null,
    val responseStatus: Int? = null,
    val nextHopProtocol: String? = null,
    val crossOrigin: Boolean = false
)

internal const val REDACTED = "<redacted>"

/** The kind a page-timing row carries, so a [ResourceTiming] is told apart from an engine signal. */
internal const val RESOURCE_KIND = "RESOURCE"

internal const val KIND_ALL = "all"
internal const val KIND_PAGE = "page timing"

/**
 * The filter chips for the kinds actually in the feed, never a fixed list.
 *
 * The two engines do not emit the same set -- WebView has no completion signal
 * at all -- so a hardcoded row would offer a filter that can only ever come
 * back empty, which reads as a broken panel rather than as a capability this
 * edition does not have.
 */
internal fun kindOptions(entries: List<NetworkEntry>): List<String> {
    val present = entries.mapTo(LinkedHashSet()) { it.kind }
    return buildList {
        add(KIND_ALL)
        present.filterTo(this) { it != RESOURCE_KIND }
        if (RESOURCE_KIND in present) add(KIND_PAGE)
    }
}

/**
 * Header names whose VALUE must never reach the buffer.
 *
 * The name is kept verbatim: that a request carried an `Authorization` header
 * is exactly the fact a bug report needs, and it is not the secret.
 */
private val SECRET_HEADERS = setOf(
    "authorization",
    "proxy-authorization",
    "cookie",
    "set-cookie",
    "x-api-key",
    "api-key",
    "x-auth-token",
    "x-csrf-token",
    "www-authenticate"
)

private val SECRET_QUERY_KEYS = setOf(
    "access_token",
    "token",
    "api_key",
    "key",
    "secret",
    "password"
)

internal fun redactHeaders(headers: Map<String, String>): List<HeaderEntry> =
    headers.entries.map { (name, value) ->
        HeaderEntry(name, if (name.lowercase() in SECRET_HEADERS) REDACTED else value)
    }

/**
 * Masks the secrets that live in a URL itself: the `user:pass@` userinfo and
 * the query parameters that carry credentials.
 *
 * Everything else — host, path, every other parameter — is kept, because those
 * are what make a report worth pasting.
 */
internal fun redactUrl(url: String): String {
    var out = url
    val authorityStart = out.indexOf("://").let { if (it < 0) -1 else it + 3 }
    if (authorityStart > 0) {
        val authorityEnd = out.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
            .let { if (it < 0) out.length else it }
        val at = out.lastIndexOf('@', authorityEnd - 1)
        if (at > authorityStart) {
            out = out.substring(0, authorityStart) + REDACTED + "@" + out.substring(at + 1)
        }
    }
    val queryStart = out.indexOf('?')
    if (queryStart >= 0) {
        val queryEnd = out.indexOf('#', queryStart).let { if (it < 0) out.length else it }
        val masked = out.substring(queryStart + 1, queryEnd).split('&').joinToString("&") { param ->
            val eq = param.indexOf('=')
            if (eq <= 0 || param.substring(0, eq).lowercase() !in SECRET_QUERY_KEYS) {
                param
            } else {
                "${param.substring(0, eq)}=$REDACTED"
            }
        }
        out = out.substring(0, queryStart + 1) + masked + out.substring(queryEnd)
    }
    return out
}

/**
 * Just the host of a URL, for a row that has to say which document it came
 * from without spending the whole line on it. Null when there is no host to
 * read, so the caller renders nothing rather than a fragment.
 */
internal fun urlHost(url: String): String? {
    val start = url.indexOf("://").let { if (it < 0) return null else it + 3 }
    val rest = url.substring(start)
    val end = rest.indexOfAny(charArrayOf('/', '?', '#'))
    val authority = if (end < 0) rest else rest.substring(0, end)
    val host = authority.substringAfterLast('@')
    return host.takeIf { it.isNotEmpty() }
}

/**
 * Whether a resource entry's sizes and status have to be rendered as hidden.
 *
 * Without `Timing-Allow-Origin` a cross-origin entry reports zero sizes, and a
 * zero there is indistinguishable from a genuinely cached response — so a
 * cross-origin zero is reported as hidden and a same-origin zero is reported as
 * the real zero it is.
 */
internal fun sizesAreHidden(crossOrigin: Boolean, sizes: List<Double?>): Boolean =
    crossOrigin && sizes.all { it == null || it == 0.0 }

/**
 * Redaction happens HERE, at data entry, so the buffers never hold a secret.
 *
 * The console TEXT is deliberately left as the page wrote it: this panel is the
 * user's own view of their own page, and rewriting a page's own log output
 * would make the panel lie about what the page said. The structural places a
 * secret actually travels — header values, URL credentials — are masked.
 */
internal fun EngineConsoleMessage.toEntry(): ConsoleEntry =
    ConsoleEntry(
        level = level,
        text = text,
        source = source?.let(::redactUrl),
        line = line,
        timestampMs = timestampMs,
        fromEngine = fromEngine
    )

internal fun EngineNetworkSignal.toEntry(): NetworkEntry =
    NetworkEntry(
        kind = kind.name,
        url = redactUrl(url),
        method = method,
        status = status,
        requestHeaders = redactHeaders(requestHeaders),
        responseHeaders = redactHeaders(responseHeaders),
        isForMainFrame = isForMainFrame,
        resourceType = resourceType,
        timestampMs = timestampMs,
        documentUrl = documentUrl?.let(::redactUrl)
    )

internal fun ResourceTiming.toEntry(): NetworkEntry = NetworkEntry(
    kind = RESOURCE_KIND,
    url = redactUrl(name),
    method = null,
    status = responseStatus,
    isForMainFrame = false,
    resourceType = initiatorType,
    timestampMs = startTime?.toLong() ?: 0L,
    durationMs = duration,
    transferSize = transferSize,
    sizesHidden = sizesAreHidden(crossOrigin, listOf(transferSize, encodedBodySize, decodedBodySize)),
    note = nextHopProtocol
)

/** A byte count at one decimal, in the unit a person would say it in. */
internal fun formatBytes(bytes: Double): String {
    val units = listOf("B", "kB", "MB", "GB")
    var value = bytes
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val rounded = kotlin.math.round(value * 10) / 10
    val whole = rounded == rounded.toLong().toDouble()
    return (if (whole) rounded.toLong().toString() else rounded.toString()) + " " + units[unit]
}
