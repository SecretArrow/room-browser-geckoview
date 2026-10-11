package com.roombrowser.domain.agent

/** How strongly a diagnosis finding is claimed. The three labels are the whole point. */
enum class DiagnosisLabel {
    /** Something that was directly read: a console line, a response status. */
    OBSERVED_FACT,

    /** An inference from the facts, offered as a suggestion and not as truth. */
    HYPOTHESIS,

    /** Something to try next. */
    RECOMMENDATION
}

/**
 * One line of the page's console, as the correlator needs it. Deliberately not
 * the DevTools layer's own entry type: this is a pure module and must not depend
 * on `:app`, so the caller maps into these.
 */
data class ConsoleLine(val level: String, val text: String) {
    val isError: Boolean get() = level.equals("error", ignoreCase = true)
    val isWarning: Boolean get() = level.equals("warning", ignoreCase = true)
}

/** One row of the page's network log, reduced to what correlation uses. */
data class NetworkRow(
    val url: String,
    val status: Int = 0,
    val method: String = "GET"
) {
    val failed: Boolean get() = status == 0 || status >= 400
}

/** A page element the snapshot numbered, used to point at what triggered a request. */
data class DomEvidence(
    val ref: Int,
    val tag: String,
    val label: String = "",
    val href: String? = null
)

/** Everything the correlator reads. Anything omitted is simply not available. */
data class DiagnosisInput(
    val console: List<ConsoleLine> = emptyList(),
    val network: List<NetworkRow> = emptyList(),
    val dom: List<DomEvidence> = emptyList(),
    /** Optional text the user pointed at, e.g. a button label or a symptom. */
    val focus: String = ""
)

/** One result of correlation, always carrying its label and its evidence. */
data class DiagnosisFinding(
    val label: DiagnosisLabel,
    val title: String,
    val evidence: List<String>,
    val relatedRequestUrl: String? = null,
    val relatedElementRef: Int? = null
)

/**
 * Correlates the console, the network log and the page's elements into findings,
 * each labelled as fact, hypothesis or recommendation.
 *
 * The job it does is the one a person does by eye in DevTools: a console error
 * names a URL, that URL is a request the page made, and an element on the page
 * is what asked for it. It is pure — no clocks, no engine, no I/O — so it is
 * testable without an emulator and cannot itself change a page.
 *
 * What it will not do is pretend. An error with no request to explain it is
 * reported as exactly that, with the hypothesis label, rather than dropped or
 * given a confident story.
 */
object DiagnosisCorrelator {

    private val URL = Regex("""https?://[^\s"'<>)\]}]+""")

    fun correlate(input: DiagnosisInput): List<DiagnosisFinding> {
        val findings = mutableListOf<DiagnosisFinding>()
        val focus = input.focus.trim()

        val errors = input.console.filter { it.isError }
        val mentioned = errors.flatMap { urlsIn(it.text) }.map { it.lowercase() }.toSet()

        // A console error, related to the request it names when one matches.
        errors.forEach { err ->
            val urls = urlsIn(err.text)
            val matched = input.network.firstOrNull { row -> urls.any { sameTarget(it, row.url) } }
            if (matched != null) {
                findings += DiagnosisFinding(
                    label = DiagnosisLabel.OBSERVED_FACT,
                    title = "A console error and a request the page made are the same failure",
                    evidence = listOf(
                        "console: ${clip(err.text)}",
                        "request: ${matched.method} ${clip(matched.url)} -> ${statusText(matched)}"
                    ),
                    relatedRequestUrl = matched.url,
                    relatedElementRef = triggeredBy(matched.url, input.dom)
                )
            } else if (urls.isNotEmpty()) {
                findings += DiagnosisFinding(
                    label = DiagnosisLabel.HYPOTHESIS,
                    title = "A console error names a URL that is not in the network log",
                    evidence = listOf(
                        "console: ${clip(err.text)}",
                        "no request in the buffer matches ${clip(urls.first())}"
                    ),
                    relatedRequestUrl = urls.first()
                )
            } else {
                findings += DiagnosisFinding(
                    label = DiagnosisLabel.HYPOTHESIS,
                    title = "A console error with nothing in the network log to explain it",
                    evidence = listOf("console: ${clip(err.text)}")
                )
            }
        }

        // A request that failed, when no console error already named it.
        input.network
            .filter { it.failed && mentioned.none { m -> sameTarget(m, it.url) } }
            .forEach { row ->
                findings += DiagnosisFinding(
                    label = DiagnosisLabel.OBSERVED_FACT,
                    title = "A request failed",
                    evidence = listOf("${row.method} ${clip(row.url)} -> ${statusText(row)}"),
                    relatedRequestUrl = row.url,
                    relatedElementRef = triggeredBy(row.url, input.dom)
                )
            }

        // The element behind a failure, when one can be pointed at.
        val firstFailure = findings.firstOrNull { it.relatedRequestUrl != null && it.relatedElementRef != null }
        if (firstFailure != null) {
            val ref = firstFailure.relatedElementRef
            val el = input.dom.firstOrNull { it.ref == ref }
            findings += DiagnosisFinding(
                label = DiagnosisLabel.HYPOTHESIS,
                title = "The element most likely behind the failure",
                evidence = buildList {
                    add("element [" + ref + "] <" + (el?.tag ?: "?") + "> \"" + clip(el?.label ?: "") + "\"")
                    firstFailure.relatedRequestUrl?.let { add("requested ${clip(it)}") }
                },
                relatedRequestUrl = firstFailure.relatedRequestUrl,
                relatedElementRef = ref
            )
        }

        // What to do about it, if anything was found at all.
        if (findings.isNotEmpty()) {
            findings += DiagnosisFinding(
                label = DiagnosisLabel.RECOMMENDATION,
                title = "Where to look next",
                evidence = listOf(
                    if (firstFailure != null) {
                        "Re-read the element above and the request it made with devtools_inspect and devtools_network; the two together usually name the cause."
                    } else {
                        "Re-read the console with the level filter set to error and check whether the page set the state the handler expects."
                    }
                )
            )
        }

        if (focus.isNotBlank()) {
            val hit = input.dom.firstOrNull { it.label.contains(focus, ignoreCase = true) }
            val anchor = if (hit != null) {
                "the element the user pointed at is [" + hit.ref + "] <" + hit.tag + "> \"" + clip(hit.label) + "\""
            } else {
                "no element matched \"" + clip(focus) + "\""
            }
            findings += DiagnosisFinding(
                label = DiagnosisLabel.RECOMMENDATION,
                title = "Focus: " + clip(focus),
                evidence = listOf(anchor),
                relatedElementRef = hit?.ref
            )
        }

        return findings
    }

    /** A short summary line for the top of a report, never empty-lying. */
    fun summary(input: DiagnosisInput, findings: List<DiagnosisFinding>): String {
        val errors = input.console.count { it.isError }
        val failed = input.network.count { it.failed }
        return buildString {
            append(errors).append(" console error(s), ")
            append(failed).append(" failed request(s) in the buffer; ")
            append(findings.count { it.label == DiagnosisLabel.OBSERVED_FACT }).append(" observed fact(s)")
        }
    }

    internal fun urlsIn(text: String): List<String> =
        URL.findAll(text).map { it.value.trimEnd('.', ',', ';', ':') }.toList()

    /**
     * Whether a console-mentioned URL and a logged request are the same target.
     *
     * Exact match, or one is the other with the query string removed — a console
     * error often names a bare URL while the log carries the query that was
     * actually sent. A same-host substring match would be too loose and would
     * correlate a page's unrelated request, so it is not used.
     */
    internal fun sameTarget(a: String, b: String): Boolean {
        val x = a.lowercase()
        val y = b.lowercase()
        if (x == y) return true
        return x.substringBefore('?').substringBefore('#') == y.substringBefore('?').substringBefore('#')
    }

    /** The element whose href or label points at [url], if the snapshot has one. */
    internal fun triggeredBy(url: String, dom: List<DomEvidence>): Int? {
        val bare = url.lowercase().substringBefore('?').substringBefore('#')
        val path = bare.substringAfter("//").substringAfter('/', "")
        return dom.firstOrNull { el ->
            val href = el.href?.lowercase()?.substringBefore('?')?.substringBefore('#')
            (href != null && (href == bare || href.contains(bare) || bare.contains(href))) ||
                (path.isNotBlank() && el.href != null && el.href.lowercase().contains(path)) ||
                (el.label.isNotBlank() && url.lowercase().contains(el.label.lowercase()) && el.label.length >= 4)
        }?.ref
    }

    private fun statusText(row: NetworkRow): String =
        if (row.status == 0) "no response" else row.status.toString()

    private fun clip(text: String, max: Int = 240): String {
        val flat = text.replace('\n', ' ').trim()
        return if (flat.length <= max) flat else flat.take(max) + "…"
    }
}
