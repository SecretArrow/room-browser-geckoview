package com.roombrowser.browser

import com.roombrowser.engine.EngineCertificate
import java.time.Instant

/**
 * The copy of the "Connection Not Secure" page: the certificate the connection
 * actually presented, what a refusal can mean, and the one way out that is not
 * a bypass.
 *
 * THE CERTIFICATE IS THE EVIDENCE, so it is shown rather than described. A
 * network that intercepts TLS presents a certificate signed by something that
 * is not a public CA, and its issuer is what makes that visible on the screen —
 * which is the only honest thing this app can do about it, since it will not
 * bypass the validation that refused the connection.
 *
 * TWO SHAPES, because there are two truths. An engine that reported a
 * certificate gets it listed; one that did not gets a sentence saying so. A
 * blank block would read as "this page presented no certificate", which is the
 * opposite of what a refused connection means. [rows] is empty exactly when
 * [NOT_REPORTED] applies, so the two can never disagree.
 *
 * Pure, and with no Android in it: the text is pinned by a JVM test rather than
 * by reading a device screen.
 */
object CertificateNotice {

    /** One labelled line of the certificate block. */
    data class Row(val label: String, val value: String)

    /** Printed in place of the block when the engine reported no certificate. */
    const val NOT_REPORTED = "The engine did not report the certificate this connection presented."

    /**
     * What a refused connection can mean, printed under the engine's own
     * reason.
     *
     * A NETWORK THAT INSPECTS ENCRYPTED TRAFFIC IS NAMED because it is the
     * cause this app can actually point at — it presents a certificate signed
     * by something that is not a public CA, and the user is usually the only
     * one who can tell whether that network is one they meant to be on. The
     * sentence lists possibilities and asserts none of them: the engine
     * refused the connection, and which of these it was is not something the
     * engine said.
     */
    const val WHY = "An expired certificate, a wrong device date and time, a public Wi-Fi " +
        "sign-in page in the way, or a network that inspects encrypted traffic all look " +
        "like this. Room Browser will not bypass it for you."

    /**
     * The way out that is not a bypass.
     *
     * Android then validates the connection itself, against an authority the
     * user installed on their own device — nothing is trusted here that they
     * did not choose to trust, and a certificate that is genuinely untrusted
     * is still refused.
     */
    const val TRUST_HINT = "If this is a network you trust, install its certificate authority " +
        "in Android Settings and this site will open."

    /** The button that takes this profile off its proxy. */
    const val DISABLE_PROXY = "Turn off proxy for this profile"

    /**
     * The line naming the proxy this profile is browsing through, or null when
     * it is going direct.
     *
     * A proxy is the one candidate cause this app can both see and undo, so it
     * is stated rather than left to be guessed at — but only when one is
     * actually in force, because a line about a proxy that is off would send
     * the user to a setting that is already where it should be.
     */
    fun proxyLine(proxy: String?): String? =
        proxy?.takeIf { it.isNotBlank() }
            ?.let { "This profile is browsing through the proxy $it, which can be the cause." }

    /** Empty when there is nothing to list — see [NOT_REPORTED]. */
    fun rows(certificate: EngineCertificate?): List<Row> {
        if (certificate == null) return emptyList()
        return listOfNotNull(
            row("Subject", certificate.subject),
            row("Issued by", certificate.issuer),
            certificate.validFromMs?.let { Row("Valid from", instant(it)) },
            certificate.validToMs?.let { Row("Valid to", instant(it)) },
            row("Fingerprint", certificate.fingerprint)
        )
    }

    private fun row(label: String, raw: String?): Row? =
        raw?.takeIf { it.isNotBlank() }?.let { Row(label, it) }

    private fun instant(epochMs: Long): String =
        runCatching { Instant.ofEpochMilli(epochMs).toString() }.getOrDefault("(not reported)")
}
