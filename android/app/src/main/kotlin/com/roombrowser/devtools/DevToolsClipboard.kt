package com.roombrowser.devtools

/**
 * The one sentence the panel and the copied block both use when no reading
 * arrived. One state, so it is never described two ways, and it names the
 * bound rather than a cause: the panel cannot tell "still coming" from "came
 * back unreadable", and a report must not carry a diagnosis we invented.
 */
internal fun noReadingLine(): String =
    "No page reading: the engine did not answer within " +
        "${InspectorSession.PROBE_TIMEOUT_MS / 1000} s."

/**
 * The "Current page" block as plain text, for pasting into a report.
 *
 * Built from the same values the panel renders and in the same order, so what
 * is copied is what was on screen. A row the engine left unanswered is written
 * as `unknown` rather than dropped: "the engine did not say" and "the page does
 * not have one" are different findings and a report must not merge them.
 */
internal fun pageFactsText(overview: PageOverview?, tabUrl: String, tabTitle: String): String {
    val lines = mutableListOf<String>()
    val title = overview?.title?.takeIf { it.isNotBlank() } ?: tabTitle.ifBlank { "No title" }
    lines += title
    lines += overview?.url ?: tabUrl
    if (overview == null) {
        lines += noReadingLine()
        return lines.joinToString("\n")
    }

    lines += ""
    lines += "Layout"
    fun line(label: String, value: String?) {
        lines += "$label: ${value ?: "unknown"}"
    }
    line("viewport width", overview.viewportWidth?.toString())
    line("viewport height", overview.viewportHeight?.toString())
    line("device pixel ratio", overview.devicePixelRatio?.let(::compactNumber))
    line("frames", overview.frames?.toString())

    lines += ""
    lines += "Document"
    line("ready state", overview.readyState)
    line("origin", overview.origin)
    line("language", overview.lang)
    line("encoding", overview.charset)
    line("content type", overview.contentType)
    line("secure context", overview.isSecureContext?.let { if (it) "yes" else "no" })
    line("manifest", overview.manifest)
    line("service worker", overview.serviceWorker)

    lines += ""
    lines += "Content"
    line("nodes", overview.nodes?.toString())
    line("scripts", overview.scripts?.toString())
    line("images", overview.images?.toString())
    line("forms", overview.forms?.toString())
    line("links", overview.links?.toString())

    lines += ""
    lines += "Storage"
    line("local storage", presence(overview.localStorage))
    line("session storage", presence(overview.sessionStorage))
    line("indexeddb", presence(overview.indexedDb))
    line("cache storage", presence(overview.caches))
    line("usage estimate", presence(overview.storageEstimate))
    line("notifications", overview.notificationPermission)

    return lines.joinToString("\n")
}

private fun presence(value: Boolean?): String? = value?.let { if (it) "present" else "not reported" }

/** Trims a whole double to its integer form, so 2.0 reads as "2". */
internal fun compactNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
