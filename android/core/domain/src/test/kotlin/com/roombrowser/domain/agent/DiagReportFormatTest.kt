package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId
import org.junit.Test

/**
 * The shape and the promises of the diagnostic report.
 *
 * Two of those promises are cheap to break by accident and expensive to break
 * in the field — the profile's storage id must never leave the device, and the
 * text must be redacted even when the caller captured a value before the
 * redactor existed. Both are asserted here rather than trusted to review.
 */
class DiagReportFormatTest {

    private val profileId = ProfileId("11111111-2222-3333-4444-555555555555")

    private fun report(
        console: List<ConsoleLine> = emptyList(),
        network: List<NetworkRow> = emptyList(),
        dom: List<DomEvidence> = emptyList()
    ) = DiagReport(
        engineName = "Android WebView",
        engineVersion = "125.0.6422.147",
        androidRelease = "15",
        apiLevel = 35,
        appVersion = "1.2.3",
        profile = Profile(id = profileId, name = "Work profile", createdAt = 0L),
        activeUrl = "https://shop.example/checkout",
        console = console,
        network = network,
        dom = dom,
        findings = DiagnosisCorrelator.correlate(
            DiagnosisInput(console = console, network = network, dom = dom)
        ),
        generatedAtMillis = 1_700_000_000_000L
    )

    @Test
    fun `the report names the engine, the app and the page`() {
        val text = DiagReportExport.format(report())
        assertThat(text).contains("Room Browser - diagnostic report")
        assertThat(text).contains("Android WebView")
        assertThat(text).contains("125.0.6422.147")
        assertThat(text).contains("Android: 15 (API 35)")
        assertThat(text).contains("1.2.3")
        assertThat(text).contains("https://shop.example/checkout")
    }

    @Test
    fun `the profile label is printed and the profile id never is`() {
        val text = DiagReportExport.format(report())
        assertThat(text).contains("Label: Work profile")
        // The storage identity is what a profile is, and it must not leave the
        // device even in a file the user is asked to share.
        assertThat(text).doesNotContain(profileId.value)
        assertThat(text).doesNotContain(profileId.safeSuffix)
    }

    @Test
    fun `every finding is printed under one of the three labels`() {
        val correlated = report(
            console = listOf(ConsoleLine("error", "GET https://shop.example/api/stock failed")),
            network = listOf(NetworkRow("https://shop.example/api/stock", 500, "GET")),
            dom = listOf(
                DomEvidence(ref = 3, tag = "a", label = "Buy", href = "https://shop.example/api/stock")
            )
        )
        assertThat(correlated.findings).isNotEmpty()
        correlated.findings.forEach { finding ->
            assertThat(DiagnosisLabel.entries).contains(finding.label)
        }
        val text = DiagReportExport.format(correlated)
        assertThat(text).contains("OBSERVED FACT")
        assertThat(text).contains("HYPOTHESIS")
        assertThat(text).contains("RECOMMENDATION")
    }

    @Test
    fun `the report is redacted even when the caller forgot to`() {
        val text = DiagReportExport.format(
            report(
                console = listOf(ConsoleLine("error", "Authorization: Bearer SUPERSECRETTOKEN")),
                network = listOf(
                    NetworkRow("https://api.example.com/v1/me?token=SUPERSECRET", 200, "GET")
                )
            )
        )
        assertThat(text).doesNotContain("SUPERSECRETTOKEN")
        assertThat(text).doesNotContain("SUPERSECRET")
        assertThat(text).contains(SecretRedaction.MASK)
    }

    @Test
    fun `a credential behind a prefix inside one line is redacted too`() {
        // A captured line rarely starts with the header, and the prefix is what
        // an export must not let a credential hide behind.
        val text = DiagReportExport.format(
            report(
                console = listOf(ConsoleLine("error", "I/chromium: Authorization: Bearer SUPERSECRETTOKEN"))
            )
        )
        assertThat(text).doesNotContain("SUPERSECRETTOKEN")
        assertThat(text).contains("Authorization:")
    }

    @Test
    fun `an empty buffer is named as empty rather than left blank`() {
        val text = DiagReportExport.format(report())
        assertThat(text).contains("Console buffer (redacted)")
        assertThat(text).contains("Network buffer (redacted)")
        assertThat(text).contains("(empty)")
        assertThat(text).contains("(no findings")
    }

    @Test
    fun `a request with no response is printed as an error rather than as zero`() {
        val text = DiagReportExport.format(
            report(network = listOf(NetworkRow("https://api.example.com/down", 0, "POST")))
        )
        assertThat(text).contains("ERR")
        assertThat(text).contains("POST")
        assertThat(text).contains("https://api.example.com/down")
    }

    @Test
    fun `the suggested file name carries no profile identity`() {
        val name = DiagReportExport.suggestedFileName(1_700_000_000_000L)
        assertThat(name).startsWith("room-browser-diagnostic-")
        assertThat(name).doesNotContain(profileId.value)
        assertThat(name).doesNotContain("Work")
    }
}
