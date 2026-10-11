package com.roombrowser.domain.agent

import com.roombrowser.domain.model.Profile

/**
 * Everything a diagnostic report is made from.
 *
 * It carries the whole [Profile] rather than a label string so the formatter is
 * the one place that decides what about a profile is printed — it reads
 * `profile.name` and never `profile.id`. A test hands it a profile with a
 * recognisable id and proves the id does not reach the text.
 */
data class DiagReport(
    val engineName: String,
    val engineVersion: String,
    val androidRelease: String,
    val apiLevel: Int,
    val appVersion: String,
    val profile: Profile,
    val activeUrl: String,
    val console: List<ConsoleLine> = emptyList(),
    val network: List<NetworkRow> = emptyList(),
    val dom: List<DomEvidence> = emptyList(),
    val findings: List<DiagnosisFinding> = emptyList(),
    val generatedAtMillis: Long = 0L
)

/**
 * Formats a [DiagReport] into the text a user can keep or send to a site's
 * developer. Pure: no clock, no engine, no I/O — the caller supplies the facts
 * and receives a string.
 *
 * Two promises it keeps by construction:
 *  - the profile's id is never printed, only its label, because the formatter
 *    reads the label and nothing else identifies the profile;
 *  - every dynamic value goes through [SecretRedaction] here as well as at
 *    entry, so a report is redacted even for something captured before the
 *    redactor existed or by a route that forgot to call it.
 *
 * Findings are printed under their three labels — OBSERVED FACT, HYPOTHESIS,
 * RECOMMENDATION — so the reader can tell what was seen from what was guessed.
 */
object DiagReportExport {

    fun format(report: DiagReport): String = buildString {
        appendLine("Room Browser - diagnostic report")
        appendLine(SEPARATOR)
        appendLine()

        appendLine("Engine")
        appendLine("  Name:    ${r(report.engineName)}")
        appendLine("  Version: ${r(report.engineVersion)}")
        appendLine()

        appendLine("Locale and app")
        appendLine("  Android: ${r(report.androidRelease)} (API ${report.apiLevel})")
        appendLine("  App:     ${r(report.appVersion)}")
        if (report.generatedAtMillis > 0L) {
            appendLine("  Generated (UTC): ${stamp(report.generatedAtMillis)}")
        }
        appendLine()

        // The label, never the id.
        appendLine("Profile")
        appendLine("  Label: ${r(report.profile.name.ifBlank { "(unnamed)" })}")
        appendLine()

        appendLine("Page")
        appendLine("  URL: ${r(report.activeUrl)}")
        appendLine()

        appendLine("Summary")
        appendLine("  ${DiagnosisCorrelator.summary(inputOf(report), report.findings)}")
        appendLine()

        appendLine("Findings")
        if (report.findings.isEmpty()) {
            appendLine("  (no findings - the console and network buffers held nothing to correlate)")
        } else {
            DiagnosisLabel.entries.forEach { label ->
                val group = report.findings.filter { it.label == label }
                if (group.isEmpty()) return@forEach
                appendLine("  ${labelText(label)}")
                group.forEach { finding ->
                    appendLine("    - ${r(finding.title)}")
                    finding.evidence.forEach { appendLine("        ${r(it)}") }
                    finding.relatedElementRef?.let { appendLine("        element: [$it]") }
                }
            }
        }
        appendLine()

        appendLine("Console buffer (redacted)")
        if (report.console.isEmpty()) {
            appendLine("  (empty)")
        } else {
            report.console.forEach { appendLine("  [${r(it.level)}] ${r(it.text)}") }
        }
        appendLine()

        appendLine("Network buffer (redacted)")
        if (report.network.isEmpty()) {
            appendLine("  (empty)")
        } else {
            report.network.forEach {
                appendLine("  ${statusOf(it)}  ${r(it.method)} ${r(it.url)}")
            }
        }
        appendLine()

        appendLine("DOM evidence (correlated elements)")
        if (report.dom.isEmpty()) {
            appendLine("  (none captured)")
        } else {
            report.dom.forEach {
                appendLine("  [${it.ref}] <${r(it.tag)}> \"${r(it.label.take(80))}\"" +
                    (it.href?.let { href -> " -> ${r(href)}" } ?: ""))
            }
        }
        appendLine()
        appendLine(SEPARATOR)
        appendLine("Secrets are redacted at the source and again here. Absence of a secret is not proof none existed.")
    }

    /** A pointer at the report for a file name; the profile label is deliberately absent. */
    fun suggestedFileName(generatedAtMillis: Long): String =
        "room-browser-diagnostic-${if (generatedAtMillis > 0L) generatedAtMillis else 0L}.txt"

    // ---- internals ----

    private const val SEPARATOR = "========================================"

    private fun inputOf(report: DiagReport) =
        DiagnosisInput(console = report.console, network = report.network, dom = report.dom)

    private fun labelText(label: DiagnosisLabel): String = when (label) {
        DiagnosisLabel.OBSERVED_FACT -> "OBSERVED FACT"
        DiagnosisLabel.HYPOTHESIS -> "HYPOTHESIS"
        DiagnosisLabel.RECOMMENDATION -> "RECOMMENDATION"
    }

    private fun statusOf(row: NetworkRow): String =
        if (row.status == 0) "ERR " else row.status.toString()

    /** The single redaction point for every value that did not come from a constant. */
    private fun r(value: String): String = SecretRedaction.redact(value)

    private fun stamp(epochMillis: Long): String =
        java.time.Instant.ofEpochMilli(epochMillis).toString()
}
