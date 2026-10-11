package com.roombrowser.devtools

import com.roombrowser.engine.devtools.DeveloperToolsCapabilities
import com.roombrowser.engine.devtools.DevToolsCapability

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
 * What a value reads as when the engine did not answer, on the panel and on the
 * clipboard alike. One word for one state: a row that says `unknown` here and
 * something else in the copied block would read as two different findings.
 */
internal const val NO_READING = "unknown"

/**
 * What a page with no title reads as.
 *
 * Bracketed on purpose. An unbracketed "No title" pasted into a report is
 * indistinguishable from a page literally titled that, and a report must never
 * hand the reader a placeholder that looks like a measurement.
 */
internal const val NO_TITLE = "(no title reported)"

/** What a page with no URL reads as. Bracketed for the same reason as [NO_TITLE]. */
internal const val NO_URL = "(no URL reported)"

/**
 * The "Current page" block as plain text, for pasting into a report.
 *
 * The same values the panel renders, grouped the way a report is read rather
 * than the way a phone-width panel has to wrap them -- the panel draws one
 * summary "Storage" row where this block names each flag, because a report
 * reader needs to know WHICH store was present. A row the engine left
 * unanswered is written as [NO_READING] rather than dropped: "the engine did not
 * say" and "the page does not have one" are different findings and a report must
 * not merge them.
 */
internal fun pageFactsText(overview: PageOverview?, tabUrl: String, tabTitle: String): String {
    val lines = mutableListOf<String>()
    // A blank title or URL is written as a bracketed marker, never as a
    // plausible-looking value: "No title" and "" both paste into a report as
    // though the page had reported them.
    lines += overview?.title?.takeIf { it.isNotBlank() }
        ?: tabTitle.takeIf { it.isNotBlank() }
        ?: NO_TITLE
    val url = overview?.url?.takeIf { it.isNotBlank() } ?: tabUrl.takeIf { it.isNotBlank() }
    lines += url ?: NO_URL
    if (overview == null) {
        lines += noReadingLine()
        return lines.joinToString("\n")
    }

    lines += ""
    lines += "Layout"
    fun line(label: String, value: String?) {
        lines += "$label: ${value ?: NO_READING}"
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

/**
 * The panel's one-line "Storage" summary.
 *
 * `null` when the engine answered none of the flags, so the row renders as "—"
 * and gets no copy control. That distinction is the point: "the engine did not
 * say" and "the page has none" are different findings, and a copyable
 * `none reported` on the first would hand a report reader a line that looks
 * measured and measured nothing. Here the whole-block text says `unknown` for
 * the same state, so the two spellings agree.
 */
internal fun storageRowValue(overview: PageOverview): String? {
    val flags = listOf(
        "local" to overview.localStorage,
        "session" to overview.sessionStorage,
        "indexeddb" to overview.indexedDb,
        "cache" to overview.caches,
        "quota" to overview.storageEstimate
    )
    if (flags.all { it.second == null }) return null
    return flags.filter { it.second == true }.joinToString(", ") { it.first }.ifBlank { "none present" }
}

/**
 * The console feed as plain text, for a bug report.
 *
 * The engine's own messages are tagged, because "the page logged this" and "the
 * engine complained about this" are different findings and a report that merged
 * them would send the reader to the wrong place.
 */
internal fun consoleText(entries: List<ConsoleEntry>): String =
    entries.joinToString("\n", transform = ::consoleLine)

/** One console entry, written the way the whole-feed block writes it. */
internal fun consoleLine(entry: ConsoleEntry): String {
    val tag = if (entry.fromEngine) "engine" else "page"
    val at = entry.source?.let { source ->
        if (entry.line != null && entry.line > 0) " ($source:${entry.line})" else " ($source)"
    } ?: ""
    return "[${entry.level}/$tag]$at ${entry.text}"
}

/** The network feed as plain text, with the header values redaction left alone. */
internal fun networkText(entries: List<NetworkEntry>): String =
    entries.joinToString("\n", transform = ::networkRowText)

/**
 * One network row, written the way the whole-feed block writes it.
 *
 * Every fact the panel draws on the row is here too. A request copied without
 * its duration, its size or the note that says a fact was withheld is the half
 * of the row a reader actually needs, and the panel offering a copy control per
 * row makes the two spellings of one row a thing that must not drift.
 */
internal fun networkRowText(entry: NetworkEntry): String {
    val head = listOfNotNull(
        entry.kind,
        entry.method,
        entry.status?.toString() ?: "status unknown",
        entry.url
    ).joinToString(" ")
    val lines = mutableListOf(
        if (entry.sizesHidden) "$head  [sizes hidden: no Timing-Allow-Origin]" else head
    )
    val facts = listOfNotNull(
        entry.resourceType?.let { "type $it" },
        entry.isForMainFrame?.let { if (it) "main frame" else "subresource" },
        entry.transferSize?.let { "size ${compactNumber(it)} B" },
        entry.durationMs?.let { "took ${compactNumber(it)} ms" },
        entry.note?.takeIf { it.isNotBlank() }
    )
    if (facts.isNotEmpty()) lines += "    " + facts.joinToString(" · ")
    entry.documentUrl?.let { lines += "    in $it" }
    entry.requestHeaders.forEach { lines += "    > ${it.name}: ${it.value}" }
    entry.responseHeaders.forEach { lines += "    < ${it.name}: ${it.value}" }
    return lines.joinToString("\n")
}

/**
 * The line a copied feed starts with, stating the bound the text is under.
 *
 * The panel draws at most [DeveloperToolsConsolePanel] rows at a time out of a
 * ring that keeps thousands, so a copied block that began straight at the first
 * row would silently be a window onto a longer feed -- the reader could not tell
 * a quiet page from a truncated paste. The header names the count, the scope the
 * user filtered to, the ring's cap, and any loss the ring has already reported.
 */
internal fun feedHeaderText(title: String, scope: String, count: Int, cap: Int, dropped: Int): String {
    val noun = if (count == 1) "entry" else "entries"
    val bound = if (cap > 0) ", feed keeps $cap" else ""
    val lines = mutableListOf("$title — $count $noun ($scope$bound)")
    if (dropped > 0) {
        val was = if (dropped == 1) "older entry was" else "older entries were"
        lines += "$dropped $was dropped from the feed and are not in this text."
    }
    return lines.joinToString("\n")
}

/** A whole console feed as one pasteable block: the bound first, then the rows. */
internal fun consoleReport(
    entries: List<ConsoleEntry>,
    level: String?,
    cap: Int,
    dropped: Int
): String {
    val head = feedHeaderText("Console", level ?: "all levels", entries.size, cap, dropped)
    val body = if (entries.isEmpty()) "(nothing captured for this filter)" else consoleText(entries)
    return "$head\n$body"
}

/** A whole network feed as one pasteable block: the bound first, then the rows. */
internal fun networkReport(
    entries: List<NetworkEntry>,
    filter: String?,
    cap: Int,
    dropped: Int
): String {
    val head = feedHeaderText("Network", filter ?: "all kinds", entries.size, cap, dropped)
    val body = if (entries.isEmpty()) "(nothing captured for this filter)" else networkText(entries)
    return "$head\n$body"
}

/**
 * What this edition can and cannot inspect, as plain text.
 *
 * The capability list IS the panel's own honesty statement, so a report about
 * "why can this build not show me X" has to be able to carry it verbatim. The
 * two absent groups are kept apart here exactly as they are on screen, because
 * "This engine cannot" and "Nobody has built it yet" send a reader to different
 * places.
 */
internal fun capabilitiesText(engineName: String, capabilities: DeveloperToolsCapabilities): String {
    val lines = mutableListOf<String>()
    lines += if (engineName.isBlank()) "Developer tools" else "Developer tools — $engineName"
    if (capabilities.capabilities.isEmpty()) {
        lines += "No inspector capability at all: every panel is absent rather than empty."
        return lines.joinToString("\n")
    }
    lines += "Available:"
    DevToolsCapability.entries.filter { capabilities.has(it) }.forEach { capability ->
        lines += "  ${capabilityLabel(capability)} — ${capabilityDetail(capability)}"
    }
    val absent = DevToolsCapability.entries.filterNot { capabilities.has(it) }
    listOf(
        "Not possible on this engine" to absent.filter { capabilities.noteFor(it) != null },
        "Not built yet" to absent.filter { capabilities.noteFor(it) == null }
    ).forEach { (group, rows) ->
        if (rows.isEmpty()) return@forEach
        lines += "$group:"
        rows.forEach { capability ->
            lines += "  ${capabilityLabel(capability)} — " +
                (capabilities.noteFor(capability) ?: capabilityDetail(capability))
        }
    }
    return lines.joinToString("\n")
}

/** Trims a whole double to its integer form, so 2.0 reads as "2". */
internal fun compactNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
