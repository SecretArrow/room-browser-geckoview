package com.roombrowser.browser

import com.roombrowser.engine.EngineCertificate
import java.time.Instant

/**
 * What the "Connection Not Secure" page says about the certificate the
 * connection actually presented.
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
