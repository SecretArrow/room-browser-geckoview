package com.roombrowser.devtools

/**
 * The Audit panel as text, with no score anywhere in it.
 *
 * THE CAPTION IS NOT DECORATION, IT IS THE FEATURE. Every other in-page audit a
 * person has met is Lighthouse, which loads the page in a controlled environment
 * and rates it against a scoring model. None of that is available here, and a
 * number invented to look like its number would be worse than no number at all.
 * So this panel prints facts the page reported about itself, one line each, and
 * says out loud what it is not.
 *
 * FOUR VERDICTS, AND INFO IS DOING REAL WORK. "pass" and "missing" are the two a
 * reader expects. "info" is the one that keeps the panel honest: a missing
 * `<meta name="robots">` is the ordinary case and not a defect, a missing
 * manifest is a choice rather than a fault, and a timing is a number rather than
 * a grade. Folding those into "missing" would invent failures; folding them into
 * "pass" would hide them. "not reported" is a fourth thing again -- the page did
 * not answer, which is never the page's fault.
 */
internal object DeveloperToolsAuditText {

    /**
     * The sentence that must appear on every Audit report, screen and copy alike.
     *
     * A constant rather than a string at the call site, because the e2e suite and
     * the panel both name it and a test that spelled its own copy would pass
     * while the panel said something else.
     */
    const val CAPTION: String =
        "Not Google Lighthouse. An in-page audit built only from signals the page can observe."

    /** The whole panel, as one paste. */
    fun auditReport(probe: AuditProbe?, engineName: String): String {
        val header = "Audit -- engine: $engineName"
        val body = if (probe == null) {
            listOf("The page did not answer, so nothing was checked.")
        } else {
            rows(probe).map { it.line() }
        }
        return (listOf(header, CAPTION, "") + body).joinToString("\n")
    }

    /**
     * The checks, in the order they are printed.
     *
     * Order matters to a reader: the document's own declarations first, then the
     * transport, then the things that need a request, then the timings. It is not
     * a ranking and nothing is sorted by severity, because severity is the
     * scoring model this panel does not have.
     */
    fun rows(probe: AuditProbe): List<AuditRow> = listOf(
        declaration(
            "title", probe.title,
            when {
                probe.title == null -> null
                probe.title.isBlank() -> "the document has no title"
                else -> null
            }
        ),
        declaration(
            "language", probe.lang,
            when {
                probe.lang == null -> null
                probe.lang.isBlank() -> "no lang attribute on <html>, so a screen reader has to guess"
                else -> null
            }
        ),
        AuditRow("character encoding", AuditVerdict.INFO, orAbsent(probe.charset)),
        declaration(
            "viewport", probe.viewport,
            when {
                probe.viewport == null -> null
                probe.viewport.isEmpty() -> "no viewport meta, so the page is laid out at desktop width on a phone"
                else -> null
            }
        ),
        declaration(
            "description", probe.description,
            when {
                probe.description == null -> null
                probe.description.isEmpty() -> "no meta description"
                else -> null
            }
        ),
        declared("robots", probe.robots, "indexing is left at the engine's default"),
        transport(probe),
        standardsMode(probe),
        declared("web app manifest", probe.manifestUrl, "the page cannot be installed as an app"),
        serviceWorker(probe),
        declared("content security policy", probe.cspMeta, "none in a meta tag; a policy in a response header is not readable from the page"),
        imagesWithoutAlt(probe),
        timing("time to first byte", probe.timeToFirstByteMs),
        timing("DOM content loaded", probe.domContentLoadedMs),
        timing("load", probe.loadMs)
    )

    /**
     * A check whose detail IS the value it found.
     *
     * Three outcomes, and the middle one is the reason this exists: a blank value
     * means the page declared nothing, which is a finding, while a null means the
     * page did not answer the question at all, which is not.
     */
    private fun declaration(name: String, value: String?, missingDetail: String?): AuditRow = when {
        value == null -> AuditRow(name, AuditVerdict.UNKNOWN, "(the page did not answer)")
        missingDetail != null -> AuditRow(name, AuditVerdict.FAIL, missingDetail)
        else -> AuditRow(name, AuditVerdict.PASS, value)
    }

    /** A declaration whose absence is ordinary rather than wrong. */
    private fun declared(name: String, value: String?, absentDetail: String): AuditRow = when {
        value == null -> AuditRow(name, AuditVerdict.UNKNOWN, "(the page did not answer)")
        value.isEmpty() -> AuditRow(name, AuditVerdict.INFO, "not declared -- $absentDetail")
        else -> AuditRow(name, AuditVerdict.INFO, value)
    }

    private fun transport(probe: AuditProbe): AuditRow = when {
        probe.protocol == null -> AuditRow("transport", AuditVerdict.UNKNOWN, "(the page did not answer)")
        probe.protocol == "https:" && probe.isSecureContext == false ->
            AuditRow("transport", AuditVerdict.FAIL, "served over https but the page is not a secure context")
        probe.protocol == "https:" -> AuditRow("transport", AuditVerdict.PASS, "https, and the page is a secure context")
        else -> AuditRow("transport", AuditVerdict.FAIL, "served over ${probe.protocol}, so anything typed here travels in the clear")
    }

    /**
     * Quirks mode, which is the one rendering signal a page can read about itself.
     *
     * `document.compatMode` is the page's own report of which mode it was laid out
     * in, so this needs no engine API and is the same answer on both editions.
     */
    private fun standardsMode(probe: AuditProbe): AuditRow = when (probe.compatMode) {
        null -> AuditRow("rendering mode", AuditVerdict.UNKNOWN, "(the page did not answer)")
        "" -> AuditRow("rendering mode", AuditVerdict.UNKNOWN, "(the page did not answer)")
        "CSS1Compat" -> AuditRow("rendering mode", AuditVerdict.PASS, "standards mode")
        else -> AuditRow("rendering mode", AuditVerdict.FAIL, "${probe.compatMode}: the page is laid out in quirks mode")
    }

    private fun serviceWorker(probe: AuditProbe): AuditRow = when (probe.serviceWorker) {
        null -> AuditRow("service worker", AuditVerdict.UNKNOWN, "(the page did not answer)")
        "controlling" -> AuditRow("service worker", AuditVerdict.PASS, "registered and controlling this page")
        "supported-uncontrolled" ->
            AuditRow("service worker", AuditVerdict.INFO, "supported, but this page is not controlled by one")
        "unsupported" -> AuditRow("service worker", AuditVerdict.INFO, "not supported on this engine")
        else -> AuditRow("service worker", AuditVerdict.INFO, probe.serviceWorker)
    }

    /**
     * Images with no `alt` attribute at all.
     *
     * An EMPTY alt is not counted, and that is the whole subtlety: `alt=""` is how
     * a page says "this image is decorative", which is a correct answer. Counting
     * it would report every well-built page as broken.
     */
    private fun imagesWithoutAlt(probe: AuditProbe): AuditRow {
        val missing = probe.missingAltCount ?: return AuditRow(
            "images without alt text",
            AuditVerdict.UNKNOWN,
            "(the page did not answer)"
        )
        val total = probe.imageCount
        if (missing == 0) {
            return AuditRow(
                "images without alt text",
                AuditVerdict.PASS,
                if (total == 0) "the page has no images" else "all $total images carry an alt attribute"
            )
        }
        val shown = probe.missingAltSamples.orEmpty()
        val tail = if (shown.isEmpty()) "" else " -- ${shown.joinToString(", ")}"
        val capped = if (total != null && shown.size < missing) " (showing ${shown.size} of $missing)" else ""
        return AuditRow(
            "images without alt text",
            AuditVerdict.FAIL,
            "$missing of ${total ?: "?"} images have no alt attribute$capped$tail"
        )
    }

    private fun timing(name: String, value: Int?): AuditRow = if (value == null) {
        AuditRow(name, AuditVerdict.UNKNOWN, "(not measured -- the event has not fired)")
    } else {
        AuditRow(name, AuditVerdict.INFO, "$value ms")
    }

    private fun orAbsent(value: String?): String =
        if (value.isNullOrBlank()) "(not reported)" else value
}

/** What the audit found, on a four-value scale that has no numbers on it. */
internal enum class AuditVerdict(val label: String) {
    PASS("pass"),
    FAIL("missing"),
    INFO("info"),
    UNKNOWN("not reported")
}

/**
 * One check's outcome.
 *
 * [line] pads the verdict to the width of the longest label, so the checks read
 * as a column and a reader can scan for `missing` without reading every word.
 */
internal data class AuditRow(val name: String, val verdict: AuditVerdict, val detail: String) {
    fun line(): String = "${verdict.label.padStart(LABEL_WIDTH)}  $name: $detail"

    private companion object {
        /** `not reported` is the longest label; the column is set by it. */
        const val LABEL_WIDTH = 12
    }
}
