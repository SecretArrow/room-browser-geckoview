package com.roombrowser.devtools

import com.google.common.truth.Truth.assertThat
import com.roombrowser.engine.devtools.EngineSecurityInfo
import org.junit.Test

/**
 * WHAT THESE PIN. Every test here is a place the Security panel could state
 * something FALSE and still look right.
 *
 * The one that matters most is the certificate, because a missing one has two
 * opposite causes and both look identical on screen. An edition with no
 * certificate API (WebView) reports a secure connection and can never print the
 * certificate it used; an edition that has the API (GeckoView) prints one
 * whenever the connection had one and finds none when it did not. Printing the
 * same sentence for both would either blame GeckoView for an http:// page or let
 * WebView look like a page that simply had no certificate. So every certificate
 * test below comes in a pair, and the pairs assert different words.
 */
class DevToolsSecurityTextTest {

    /** A secure connection whose certificate this edition has no API to read -- the WebView shape. */
    private val secureWithoutCertificate = EngineSecurityInfo(
        secure = true,
        host = "example.com",
        protocolVersion = null,
        cipherSuite = null,
        certificate = null,
        mixedContent = null
    )

    /**
     * A connection whose certificate the engine DID read -- the GeckoView shape.
     *
     * Note what is absent and stays absent: GeckoView's SecurityInformation
     * carries no TLS version and no cipher suite, so the honest fixture has
     * neither. Filling them in here would let the formatter claim a field the
     * engine never sends.
     */
    private val secureWithCertificate = EngineSecurityInfo(
        secure = true,
        host = "example.com",
        protocolVersion = null,
        cipherSuite = null,
        certificate = EngineSecurityInfo.Certificate(
            subject = "CN=example.com",
            issuer = "C=US, O=Let's Encrypt, CN=R3",
            validFromMs = 1_767_225_600_000L,
            validToMs = 1_798_761_600_000L,
            fingerprint = "SHA-256 AA:BB:CC"
        ),
        mixedContent = false
    )

    @Test
    fun a_connection_whose_certificate_cannot_be_read_says_so_and_prints_no_fields() {
        val text = DeveloperToolsSecurityText.certificateText(
            secureWithoutCertificate,
            certificatesReadable = false
        )
        assertThat(text).contains("this edition has no way to read")
        // A blank block would read as "this page has no certificate".
        assertThat(text).doesNotContain("subject:")
        assertThat(text).doesNotContain("issuer:")
        assertThat(text).doesNotContain("fingerprint:")
    }

    @Test
    fun being_secure_never_produces_a_certificate_section_that_claims_one() {
        // The negative control for the test above: the sentence must be about
        // this EDITION, not about the page.
        val text = DeveloperToolsSecurityText.certificateText(
            secureWithoutCertificate,
            certificatesReadable = false
        )
        assertThat(text).doesNotContain("no certificate")
        assertThat(text).doesNotContain("has no certificate")
        assertThat(text).contains("this edition")
    }

    @Test
    fun an_engine_that_can_read_certificates_and_found_none_does_not_blame_itself() {
        // The pair to the two tests above, and the reason `certificatesReadable`
        // is a parameter: an http:// page on an edition that CAN read a
        // certificate is not a limit of the edition, and saying so would be a
        // lie the reader has no way to catch.
        val text = DeveloperToolsSecurityText.certificateText(
            secureWithoutCertificate.copy(secure = false),
            certificatesReadable = true
        )
        assertThat(text).contains("the engine reported no certificate for this connection")
        assertThat(text).doesNotContain("this edition")
    }

    @Test
    fun an_engine_that_reported_nothing_at_all_is_its_own_third_answer() {
        val text = DeveloperToolsSecurityText.certificateText(null, certificatesReadable = true)
        assertThat(text).contains("did not report the connection's state")
        assertThat(text).doesNotContain("this edition")
        assertThat(text).doesNotContain("presented none")
    }

    @Test
    fun a_reported_certificate_is_printed_field_by_field() {
        val text = DeveloperToolsSecurityText.certificateText(
            secureWithCertificate,
            certificatesReadable = true
        )
        assertThat(text).contains("subject: CN=example.com")
        assertThat(text).contains("issuer: C=US, O=Let's Encrypt, CN=R3")
        assertThat(text).contains("fingerprint: SHA-256 AA:BB:CC")
    }

    @Test
    fun a_certificate_beats_the_edition_flag_when_both_are_present() {
        // A certificate is never suppressed by a capability flag. If an engine
        // hands one over, it is printed -- the flag explains an ABSENCE, and
        // letting it hide a present fact would be the worst of both.
        val text = DeveloperToolsSecurityText.certificateText(
            secureWithCertificate,
            certificatesReadable = false
        )
        assertThat(text).contains("subject: CN=example.com")
    }

    @Test
    fun a_validity_window_is_printed_as_iso_8601_utc() {
        val text = DeveloperToolsSecurityText.certificateText(
            secureWithCertificate,
            certificatesReadable = true
        )
        assertThat(text).contains("valid: 2026-01-01T00:00:00Z to 2027-01-01T00:00:00Z")
    }

    @Test
    fun a_certificate_with_no_reported_validity_says_so_rather_than_printing_a_blank_window() {
        val info = secureWithCertificate.copy(
            certificate = EngineSecurityInfo.Certificate(subject = "CN=a.example")
        )
        val text = DeveloperToolsSecurityText.certificateText(info, certificatesReadable = true)
        assertThat(text).contains("valid: (not reported) to (not reported)")
    }

    @Test
    fun the_transport_section_reports_an_unread_protocol_version_as_unread() {
        val text = DeveloperToolsSecurityText.transportText(secureWithoutCertificate)
        assertThat(text).contains("protocol version: (not reported)")
        assertThat(text).contains("cipher suite: (not reported)")
        assertThat(text).contains("secure: yes")
    }

    @Test
    fun the_engines_own_note_is_carried_into_the_report() {
        val text = DeveloperToolsSecurityText.transportText(
            secureWithoutCertificate.copy(note = "Partial view: no certificate API")
        )
        assertThat(text).contains("note: Partial view: no certificate API")
    }

    @Test
    fun an_engine_that_reported_nothing_reads_as_unreadable_not_as_insecure() {
        val whole = DeveloperToolsSecurityText.securityReport(null, null, "Android WebView", false)
        assertThat(whole).contains("could not report")
        assertThat(DeveloperToolsSecurityText.transportText(null)).isEqualTo("Transport -- (not reported)")
        assertThat(whole).doesNotContain("secure: no")
    }

    @Test
    fun mixed_content_on_a_page_that_is_not_https_is_not_applicable_rather_than_none() {
        // Every subresource of an http:// page is already in the clear, so
        // "none came over http://" would be true by construction and useless.
        val probe = SecurityProbe(protocol = "http:", insecureSubresourceCount = 0)
        assertThat(DeveloperToolsSecurityText.pageObservableText(probe))
            .contains("not applicable, the page itself is not served over https")
    }

    @Test
    fun a_capped_mixed_content_list_says_how_many_it_showed() {
        val probe = SecurityProbe(
            protocol = "https:",
            subresourceCount = 214,
            insecureSubresourceCount = 26,
            insecureSubresources = List(DeveloperToolsSecurityScripts.SUBRESOURCE_CAP) { "http://cdn.example/$it.js" }
        )
        val text = DeveloperToolsSecurityText.pageObservableText(probe)
        assertThat(text).contains("26 of the page's subresources came over http://")
        assertThat(text).contains("(the listing shows 20 of 26)")
    }

    @Test
    fun an_uncapped_mixed_content_list_does_not_claim_to_be_capped() {
        val probe = SecurityProbe(
            protocol = "https:",
            subresourceCount = 10,
            insecureSubresourceCount = 2,
            insecureSubresources = listOf("http://a.example/x.js", "http://b.example/y.css")
        )
        val text = DeveloperToolsSecurityText.pageObservableText(probe)
        assertThat(text).contains("2 of the page's subresources came over http://")
        assertThat(text).doesNotContain("the listing shows")
    }

    @Test
    fun a_page_question_that_went_unanswered_is_not_reported_rather_than_answered_no() {
        val probe = SecurityProbe(protocol = "https:", isSecureContext = null, formActionInsecure = null)
        val text = DeveloperToolsSecurityText.pageObservableText(probe)
        assertThat(text).contains("secure context: (not reported)")
        assertThat(text).contains("forms posting in the clear: (not reported)")
    }

    @Test
    fun a_page_with_no_meta_csp_says_none_declared_not_not_reported() {
        // The probe returns "" for "I looked and there was none", and null only
        // when it could not look. Conflating them would blame the build for the
        // page's ordinary case. Every other field is answered here so that the
        // "not reported" assertion at the end is about the CSP line alone.
        val probe = SecurityProbe(
            protocol = "https:",
            isSecureContext = true,
            formActionInsecure = false,
            subresourceCount = 3,
            insecureSubresourceCount = 0,
            insecureSubresources = emptyList(),
            cspMeta = "",
            referrerPolicyMeta = ""
        )
        val text = DeveloperToolsSecurityText.pageObservableText(probe)
        assertThat(text).contains("CSP declared in a meta tag: (none declared)")
        assertThat(text).contains("referrer policy declared in a meta tag: (none declared)")
        assertThat(text).doesNotContain("(not reported)")
    }

    @Test
    fun a_csp_the_probe_could_not_read_says_not_reported() {
        val probe = SecurityProbe(protocol = "https:", cspMeta = null)
        assertThat(DeveloperToolsSecurityText.pageObservableText(probe))
            .contains("CSP declared in a meta tag: (not reported)")
    }

    @Test
    fun a_declared_csp_is_printed_verbatim() {
        val probe = SecurityProbe(protocol = "https:", cspMeta = "default-src 'self'; img-src *")
        assertThat(DeveloperToolsSecurityText.pageObservableText(probe))
            .contains("CSP declared in a meta tag: default-src 'self'; img-src *")
    }

    @Test
    fun the_whole_report_header_names_the_engine_that_answered() {
        val whole = DeveloperToolsSecurityText.securityReport(
            secureWithCertificate,
            null,
            "GeckoView",
            true
        )
        assertThat(whole).startsWith("Security -- engine: GeckoView")
        assertThat(whole).contains("Transport")
        assertThat(whole).contains("Certificate")
        assertThat(whole).contains("What the page can see -- (the page did not answer)")
    }

    @Test
    fun the_copied_report_carries_the_same_certificate_verdict_the_section_shows() {
        // A copy control that re-derives the sentence could disagree with the
        // screen; both go through the same call, and this is what says so.
        val whole = DeveloperToolsSecurityText.securityReport(
            secureWithoutCertificate,
            null,
            "Android WebView",
            false
        )
        assertThat(whole).contains(
            DeveloperToolsSecurityText.certificateText(secureWithoutCertificate, false)
        )
    }

    @Test
    fun the_probe_script_interpolates_its_helpers_instead_of_shipping_their_names() {
        // A raw string that lost a `$` would ship the literal word to the page
        // and the probe would silently answer nothing.
        val js = DeveloperToolsSecurityScripts.securityProbeJs()
        assertThat(js).doesNotContain("\$SAFE")
        assertThat(js).doesNotContain("\$SUBRESOURCE_CAP")
        assertThat(js).contains("function __rbSafe(f)")
        assertThat(js).contains("insecure.slice(0, 20)")
    }
}
