package com.roombrowser.browser

import com.google.common.truth.Truth.assertThat
import com.roombrowser.engine.EngineCertificate
import org.junit.Test

/**
 * The certificate block on the "Connection Not Secure" page is the only place
 * this app shows the evidence behind a refused connection, so its two shapes are
 * pinned here: a certificate that was reported, and an honest sentence when the
 * engine reported none. A blank block would read as "this page presented no
 * certificate", which is the opposite of what the refusal means.
 */
class CertificateNoticeTest {

    private val at = 1_759_400_000_000L // 2025-10-02 10:13:20 UTC

    @Test
    fun `a reported certificate lists its subject, issuer, validity and fingerprint`() {
        val rows = CertificateNotice.rows(
            EngineCertificate(
                subject = "CN=example.test",
                issuer = "CN=Acme Inspection Proxy",
                validFromMs = at,
                validToMs = at,
                fingerprint = "SHA-256 AA:BB"
            )
        )

        assertThat(rows.map { it.label })
            .containsExactly("Subject", "Issued by", "Valid from", "Valid to", "Fingerprint")
        assertThat(rows.first { it.label == "Issued by" }.value).isEqualTo("CN=Acme Inspection Proxy")
        assertThat(rows.first { it.label == "Valid from" }.value).contains("2025-10-02")
    }

    @Test
    fun `no certificate reported yields no rows and the sentence that says so`() {
        assertThat(CertificateNotice.rows(null)).isEmpty()
        assertThat(CertificateNotice.NOT_REPORTED).contains("did not report the certificate")
    }

    @Test
    fun `fields the engine left blank are omitted rather than printed empty`() {
        val rows = CertificateNotice.rows(
            EngineCertificate(subject = "CN=example.test", issuer = "  ")
        )

        assertThat(rows.map { it.label }).containsExactly("Subject")
    }

    /**
     * The page must name the cause the user can act on without ever offering a
     * way past the refusal, and it must say what the fix actually is — the
     * Android setting, not a button in this app.
     */
    @Test
    fun `the page names an inspecting network and points at the Android setting`() {
        assertThat(CertificateNotice.WHY).contains("network that inspects encrypted traffic")
        assertThat(CertificateNotice.WHY).contains("will not bypass it for you")
        assertThat(CertificateNotice.TRUST_HINT).contains("Android Settings")
    }
}
