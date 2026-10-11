package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The correlation between a console error, the request it names and the element
 * that asked for it — the judgement a person makes by eye in DevTools.
 *
 * What it must never do is pretend: an error with nothing in the network log to
 * explain it is a HYPOTHESIS said out loud, not a confident story. The tests
 * below pin that, and pin the guard that keeps a same-host request from being
 * correlated just because it shares a host.
 */
class DiagnosisCorrelatorTest {

    private fun facts(findings: List<DiagnosisFinding>) =
        findings.filter { it.label == DiagnosisLabel.OBSERVED_FACT }

    private fun hypotheses(findings: List<DiagnosisFinding>) =
        findings.filter { it.label == DiagnosisLabel.HYPOTHESIS }

    private fun recommendations(findings: List<DiagnosisFinding>) =
        findings.filter { it.label == DiagnosisLabel.RECOMMENDATION }

    @Test
    fun `an error naming a request the page made is one observed fact`() {
        val findings = DiagnosisCorrelator.correlate(
            DiagnosisInput(
                console = listOf(ConsoleLine("error", "Failed to load https://api.example.com/v1/stock")),
                network = listOf(NetworkRow("https://api.example.com/v1/stock", 500, "GET"))
            )
        )
        val observed = facts(findings)
        assertThat(observed).hasSize(1)
        assertThat(observed.first().relatedRequestUrl).isEqualTo("https://api.example.com/v1/stock")
        val evidence = observed.first().evidence.joinToString(" | ")
        assertThat(evidence).contains("console:")
        assertThat(evidence).contains("request:")
    }

    @Test
    fun `a query string on the request does not stop the match`() {
        val findings = DiagnosisCorrelator.correlate(
            DiagnosisInput(
                console = listOf(ConsoleLine("error", "could not fetch https://api.example.com/data")),
                network = listOf(NetworkRow("https://api.example.com/data?v=2", 200, "GET"))
            )
        )
        assertThat(facts(findings)).hasSize(1)
    }

    @Test
    fun `a same-host request with a different path is not correlated`() {
        val findings = DiagnosisCorrelator.correlate(
            DiagnosisInput(
                console = listOf(ConsoleLine("error", "could not fetch https://api.example.com/a")),
                network = listOf(NetworkRow("https://api.example.com/b", 200, "GET"))
            )
        )
        assertThat(facts(findings)).isEmpty()
        assertThat(hypotheses(findings)).isNotEmpty()
    }

    @Test
    fun `an error naming a url that is not in the log is a hypothesis`() {
        val findings = DiagnosisCorrelator.correlate(
            DiagnosisInput(console = listOf(ConsoleLine("error", "GET https://cdn.example.com/x.js failed")))
        )
        val hypothesis = hypotheses(findings).firstOrNull()
        assertThat(hypothesis).isNotNull()
        assertThat(hypothesis?.relatedRequestUrl).isEqualTo("https://cdn.example.com/x.js")
        assertThat(hypothesis?.evidence?.joinToString(" | ")).contains("no request")
    }

    @Test
    fun `an error with nothing to explain it is still reported`() {
        val findings = DiagnosisCorrelator.correlate(
            DiagnosisInput(console = listOf(ConsoleLine("error", "Uncaught TypeError: x is not a function")))
        )
        assertThat(hypotheses(findings)).isNotEmpty()
        assertThat(facts(findings)).isEmpty()
    }

    @Test
    fun `a failed request with no console error is an observed fact`() {
        val findings = DiagnosisCorrelator.correlate(
            DiagnosisInput(network = listOf(NetworkRow("https://api.example.com/down", 0, "POST")))
        )
        val observed = facts(findings)
        assertThat(observed).hasSize(1)
        assertThat(observed.first().relatedRequestUrl).isEqualTo("https://api.example.com/down")
    }

    @Test
    fun `a request that succeeded is not reported as a failure`() {
        val findings = DiagnosisCorrelator.correlate(
            DiagnosisInput(network = listOf(NetworkRow("https://api.example.com/ok", 200, "GET")))
        )
        assertThat(findings).isEmpty()
    }

    @Test
    fun `the element behind a failure is pointed at as a hypothesis`() {
        val findings = DiagnosisCorrelator.correlate(
            DiagnosisInput(
                console = listOf(ConsoleLine("error", "GET https://shop.example/api/stock failed")),
                network = listOf(NetworkRow("https://shop.example/api/stock", 500, "GET")),
                dom = listOf(
                    DomEvidence(ref = 7, tag = "a", label = "Buy", href = "https://shop.example/api/stock")
                )
            )
        )
        val element = hypotheses(findings).firstOrNull { it.relatedElementRef != null }
        assertThat(element).isNotNull()
        assertThat(element?.relatedElementRef).isEqualTo(7)
        assertThat(element?.evidence?.joinToString(" | ")).contains("[7]")
    }

    @Test
    fun `a run that found something ends with a recommendation`() {
        val findings = DiagnosisCorrelator.correlate(
            DiagnosisInput(network = listOf(NetworkRow("https://api.example.com/down", 500, "GET")))
        )
        val advice = recommendations(findings)
        assertThat(advice).isNotEmpty()
        assertThat(advice.first().evidence).isNotEmpty()
    }

    @Test
    fun `nothing captured means no findings`() {
        assertThat(DiagnosisCorrelator.correlate(DiagnosisInput())).isEmpty()
    }

    @Test
    fun `a focus that matches an element names its reference`() {
        val findings = DiagnosisCorrelator.correlate(
            DiagnosisInput(
                dom = listOf(DomEvidence(ref = 2, tag = "button", label = "Checkout")),
                focus = "checkout"
            )
        )
        val advice = recommendations(findings).firstOrNull { it.relatedElementRef != null }
        assertThat(advice).isNotNull()
        assertThat(advice?.relatedElementRef).isEqualTo(2)
    }

    @Test
    fun `a focus that matches nothing says so rather than guessing`() {
        val findings = DiagnosisCorrelator.correlate(DiagnosisInput(focus = "the coupon box"))
        assertThat(recommendations(findings)).isNotEmpty()
        assertThat(recommendations(findings).first().evidence.joinToString(" | "))
            .contains("no element matched")
    }

    @Test
    fun `the summary counts errors, failed requests and observed facts`() {
        val input = DiagnosisInput(
            console = listOf(ConsoleLine("error", "GET https://api.example.com/a failed")),
            network = listOf(NetworkRow("https://api.example.com/a", 500, "GET"))
        )
        val findings = DiagnosisCorrelator.correlate(input)
        val summary = DiagnosisCorrelator.summary(input, findings)
        assertThat(summary).contains("1 console error(s)")
        assertThat(summary).contains("1 failed request(s)")
        assertThat(summary).contains("1 observed fact(s)")
    }
}
