package com.roombrowser.devtools

import com.roombrowser.engine.devtools.EngineSecurityInfo
import java.time.Instant

/**
 * The text every Security-panel copy control writes.
 *
 * THE ONE RULE THIS PANEL EXISTS TO KEEP: two different engines must not be made
 * to look the same. A missing certificate has two entirely different causes and
 * the panel must not blur them -- an engine with no certificate API (WebView)
 * can never fill the section in, while an engine that has one (GeckoView) simply
 * found none on a connection that is not secured. So [certificatesReadable] is
 * passed in by the panel rather than guessed here, and the two cases print
 * different sentences. A blank certificate block would read as "this page has no
 * certificate", which on WebView is the opposite of the truth.
 *
 * The engine's own state and the page's own answers are printed as separate
 * sections because they can disagree, and when they do that disagreement is the
 * finding: a page served over https whose form posts to http:// is exactly the
 * thing a security panel is for.
 */
internal object DeveloperToolsSecurityText {

    /** The whole panel, as one paste. */
    fun securityReport(
        info: EngineSecurityInfo?,
        probe: SecurityProbe?,
        engineName: String,
        certificatesReadable: Boolean
    ): String {
        val header = if (info == null) {
            "Security -- $engineName could not report the connection's state."
        } else {
            "Security -- engine: $engineName"
        }
        return listOf(
            header,
            "",
            transportText(info),
            "",
            certificateText(info, certificatesReadable),
            "",
            pageObservableText(probe)
        ).joinToString("\n").trimEnd()
    }

    /** What the ENGINE says about the connection. */
    fun transportText(info: EngineSecurityInfo?): String {
        if (info == null) return "Transport -- (not reported)"
        val lines = mutableListOf(
            "Transport",
            "  host: ${orAbsent(info.host)}",
            "  secure: ${if (info.secure) "yes" else "no"}",
            "  protocol version: ${orAbsent(info.protocolVersion)}",
            "  cipher suite: ${orAbsent(info.cipherSuite)}",
            "  mixed content: ${flag(info.mixedContent, "the engine flagged it", "the engine flagged none")}"
        )
        info.note?.takeIf { it.isNotBlank() }?.let { lines += "  note: $it" }
        return lines.joinToString("\n")
    }

    /**
     * The connection's certificate, or the sentence saying why there is none.
     *
     * THREE CASES, THREE SENTENCES, and only the first is a fault. An edition
     * with no certificate API (WebView) can never print a block, and saying so is
     * the only honest answer -- silence would read as a page without a
     * certificate. An edition that CAN read one and reported none (GeckoView on
     * an http:// document, or on a failed handshake) did its job; the honest
     * answer is that the connection presented none, not that the edition is
     * limited. And a null [info] means the engine said nothing at all, which is
     * a third thing again.
     *
     * [EngineSecurityInfo.secure] being true is NOT evidence of a certificate
     * block: WebView reports a secure connection and cannot report the
     * certificate, which is why the two are separate sections and why this
     * function never falls back to the transport state.
     */
    fun certificateText(info: EngineSecurityInfo?, certificatesReadable: Boolean): String {
        val certificate = info?.certificate
        if (certificate == null) {
            val reason = when {
                !certificatesReadable ->
                    "this edition has no way to read a connection's certificate."
                info == null -> "the engine did not report the connection's state."
                else -> "the engine reported no certificate for this connection."
            }
            return "Certificate -- $reason"
        }
        val lines = mutableListOf(
            "Certificate",
            "  subject: ${orAbsent(certificate.subject)}",
            "  issuer: ${orAbsent(certificate.issuer)}"
        )
        val from = certificate.validFromMs?.let { datetime(it) } ?: "(not reported)"
        val to = certificate.validToMs?.let { datetime(it) } ?: "(not reported)"
        lines += "  valid: $from to $to"
        lines += "  fingerprint: ${orAbsent(certificate.fingerprint)}"
        return lines.joinToString("\n")
    }

    /** What the PAGE could answer about itself. */
    fun pageObservableText(probe: SecurityProbe?): String {
        if (probe == null) return "What the page can see -- (the page did not answer)"
        val lines = mutableListOf(
            "What the page can see",
            "  protocol: ${orAbsent(probe.protocol)}",
            "  secure context: ${flag(probe.isSecureContext, "yes", "no")}",
            "  forms posting in the clear: ${flag(probe.formActionInsecure, "yes", "no")}"
        )
        lines += "  ${mixedContentLine(probe)}"
        probe.insecureSubresources.orEmpty().forEach { lines += "    $it" }
        lines += "  CSP declared in a meta tag: ${declared(probe.cspMeta)}"
        lines += "  referrer policy declared in a meta tag: ${declared(probe.referrerPolicyMeta)}"
        return lines.joinToString("\n")
    }

    /**
     * The mixed-content line, which reports a capped list as capped.
     *
     * The page only sees its own HTTPS page's plain-HTTP subresources, so on an
     * http:// document the honest answer is not "none" but "not applicable" --
     * every subresource is already in the clear.
     */
    private fun mixedContentLine(probe: SecurityProbe): String {
        if (probe.protocol != null && probe.protocol != "https:") {
            return "mixed content: not applicable, the page itself is not served over https"
        }
        val count = probe.insecureSubresourceCount ?: return "mixed content: (not reported)"
        if (count == 0) return "mixed content: none of the ${probe.subresourceCount?.toString() ?: "?"} subresources came over http://"
        val shown = probe.insecureSubresources.orEmpty().size
        val hidden = count - shown
        val tail = if (hidden > 0) " (the listing shows $shown of $count)" else ""
        return "mixed content: $count of the page's subresources came over http://$tail"
    }

    private fun datetime(epochMs: Long): String =
        runCatching { Instant.ofEpochMilli(epochMs).toString() }.getOrDefault("(not reported)")

    private fun orAbsent(value: String?): String =
        if (value.isNullOrBlank()) "(not reported)" else value

    /**
     * A value the page either declared or did not.
     *
     * This is a different question from [orAbsent]: no `<meta>` CSP is the
     * ordinary case and means "none declared", while a null means the probe
     * itself failed. Printing "(not reported)" for both would tell a reader the
     * build was broken when the page simply had none.
     */
    private fun declared(value: String?): String = when {
        value == null -> "(not reported)"
        value.isEmpty() -> "(none declared)"
        else -> value
    }

    private fun flag(value: Boolean?, yes: String, no: String): String = when (value) {
        true -> yes
        false -> no
        null -> "(not reported)"
    }
}
