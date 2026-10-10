package com.roombrowser.devtools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * WHAT THESE PIN. Every test here is a place the Audit panel could state
 * something FALSE and still look right.
 *
 * The two that matter most are both about NOT inventing a finding. A page with
 * no `<meta name="robots">` is the ordinary case, not a defect, and an image with
 * `alt=""` has said "I am decorative" and is not an accessibility failure -- a
 * formatter that folded either into `missing` would report every well-built page
 * on the web as broken. The third is the caption: this panel is not Lighthouse,
 * and the sentence saying so is the feature rather than a disclaimer.
 *
 * The fourth is `not reported`. A null field means the page never answered, and
 * printing that as `missing` would blame the page for the probe's silence.
 */
class DevToolsAuditTextTest {

    /** A page that answers everything and has nothing wrong with it. */
    private val healthy = AuditProbe(
        title = "Example Domain",
        lang = "en",
        charset = "UTF-8",
        viewport = "width=device-width, initial-scale=1",
        description = "An example page",
        robots = "",
        cspMeta = "",
        protocol = "https:",
        isSecureContext = true,
        compatMode = "CSS1Compat",
        manifestUrl = "",
        serviceWorker = "supported-uncontrolled",
        imageCount = 3,
        missingAltCount = 0,
        missingAltSamples = emptyList(),
        timeToFirstByteMs = 42,
        domContentLoadedMs = 310,
        loadMs = 480
    )

    /** Every field null: the page did not answer any question. */
    private val silent = AuditProbe()

    private fun rows(probe: AuditProbe) = DeveloperToolsAuditText.rows(probe).associateBy { it.name }

    private fun report(probe: AuditProbe?) = DeveloperToolsAuditText.auditReport(probe, "TestEngine")

    @Test
    fun the_report_always_carries_the_caption_that_says_what_this_is_not() {
        // The caption is the deliverable, not a footnote: it is what stops a
        // reader from taking these lines for a Lighthouse score.
        assertThat(report(healthy)).contains(DeveloperToolsAuditText.CAPTION)
        assertThat(report(null)).contains(DeveloperToolsAuditText.CAPTION)
        assertThat(DeveloperToolsAuditText.CAPTION)
            .isEqualTo("Not Google Lighthouse. An in-page audit built only from signals the page can observe.")
    }

    @Test
    fun no_report_line_anywhere_carries_a_score_or_a_percentage() {
        // A number that looked like Lighthouse's would be the single most
        // misleading thing this panel could print, so its absence is asserted
        // rather than assumed.
        val text = report(healthy)
        assertThat(text).doesNotContain("%")
        assertThat(text).doesNotContain("/100")
        assertThat(text).doesNotContain("score")
    }

    @Test
    fun a_page_that_answered_nothing_reports_every_check_as_not_reported() {
        val rows = rows(silent)
        assertThat(rows.values.map { it.verdict }.toSet())
            .containsExactly(AuditVerdict.UNKNOWN)
        assertThat(report(silent)).contains("not reported")
    }

    @Test
    fun an_unanswered_probe_says_so_instead_of_printing_an_empty_table() {
        val text = report(null)
        assertThat(text).contains("The page did not answer, so nothing was checked.")
        assertThat(text).doesNotContain("pass")
    }

    @Test
    fun a_missing_robots_meta_is_info_and_never_missing() {
        // Absence of a robots directive is how most of the web is written; a
        // panel that called it a defect would cry wolf on nearly every page.
        val row = rows(healthy)["robots"]!!
        assertThat(row.verdict).isEqualTo(AuditVerdict.INFO)
        assertThat(row.detail).contains("indexing is left at the engine's default")
    }

    @Test
    fun images_that_all_carry_an_alt_attribute_pass() {
        val row = rows(healthy)["images without alt text"]!!
        assertThat(row.verdict).isEqualTo(AuditVerdict.PASS)
        assertThat(row.detail).contains("all 3 images")
    }

    @Test
    fun an_image_with_no_alt_attribute_at_all_is_the_only_one_counted() {
        // The count comes from the page and the samples are the page's own
        // answer, so this asserts the formatter renders the count it was given
        // rather than recomputing anything from the samples.
        val row = rows(healthy.copy(missingAltCount = 4, missingAltSamples = listOf("a.png", "b.png")))["images without alt text"]!!
        assertThat(row.verdict).isEqualTo(AuditVerdict.FAIL)
        assertThat(row.detail).contains("4 of 3 images")
        assertThat(row.detail).contains("showing 2 of 4")
        assertThat(row.detail).contains("a.png, b.png")
    }

    @Test
    fun a_page_with_no_images_at_all_passes_rather_than_reporting_zero_of_zero() {
        val row = rows(healthy.copy(imageCount = 0))["images without alt text"]!!
        assertThat(row.verdict).isEqualTo(AuditVerdict.PASS)
        assertThat(row.detail).contains("no images")
    }

    @Test
    fun a_secure_page_that_is_not_a_secure_context_is_reported_as_the_contradiction_it_is() {
        val row = rows(healthy.copy(isSecureContext = false))["transport"]!!
        assertThat(row.verdict).isEqualTo(AuditVerdict.FAIL)
        assertThat(row.detail).contains("not a secure context")
    }

    @Test
    fun a_plain_http_page_is_a_failure_rather_than_a_missing_field() {
        val row = rows(healthy.copy(protocol = "http:", isSecureContext = false))["transport"]!!
        assertThat(row.verdict).isEqualTo(AuditVerdict.FAIL)
        assertThat(row.detail).contains("travels in the clear")
    }

    @Test
    fun quirks_mode_is_reported_and_standards_mode_is_not_prose_about_a_failure() {
        assertThat(rows(healthy)["rendering mode"]!!.verdict).isEqualTo(AuditVerdict.PASS)
        val quirks = rows(healthy.copy(compatMode = "BackCompat"))["rendering mode"]!!
        assertThat(quirks.verdict).isEqualTo(AuditVerdict.FAIL)
        assertThat(quirks.detail).contains("quirks mode")
    }

    @Test
    fun a_probe_that_never_measured_a_timing_says_not_measured_rather_than_zero_milliseconds() {
        // Navigation Timing reports 0 until the event fires, so a formatter that
        // printed the number it was handed would call a still-loading page fast.
        assertThat(rows(silent)["load"]!!.detail).contains("not measured")
        assertThat(rows(healthy)["load"]!!.detail).isEqualTo("480 ms")
    }

    @Test
    fun a_missing_viewport_is_a_failure_because_this_is_a_phone_browser() {
        val row = rows(healthy.copy(viewport = ""))["viewport"]!!
        assertThat(row.verdict).isEqualTo(AuditVerdict.FAIL)
        assertThat(row.detail).contains("desktop width")
    }

    @Test
    fun an_absent_manifest_is_info_and_not_a_defect() {
        val row = rows(healthy)["web app manifest"]!!
        assertThat(row.verdict).isEqualTo(AuditVerdict.INFO)
        assertThat(row.detail).contains("cannot be installed as an app")
    }

    @Test
    fun a_blank_title_is_a_failure_while_an_unanswered_one_is_not() {
        assertThat(rows(healthy.copy(title = ""))["title"]!!.verdict).isEqualTo(AuditVerdict.FAIL)
        assertThat(rows(silent)["title"]!!.verdict).isEqualTo(AuditVerdict.UNKNOWN)
    }

    @Test
    fun the_copied_report_is_the_report_the_panel_prints() {
        // The panel hands the same string to the screen and to the clipboard, so
        // this pins that there is only one rendering of it.
        val text = report(healthy)
        assertThat(text).startsWith("Audit -- engine: TestEngine")
        DeveloperToolsAuditText.rows(healthy).forEach { row ->
            assertThat(text).contains(row.line())
        }
    }

    @Test
    fun every_verdict_label_is_padded_to_one_column_width() {
        // The labels are different lengths, so a reader scans the column only if
        // they all end at the same place. The width is derived from the labels
        // themselves rather than restated as a number, because a literal here
        // would pass while the column it describes was ragged.
        val width = AuditVerdict.values().maxOf { it.label.length }
        val markers = rows(healthy).values.map { it.line().take(width) }
        assertThat(markers.map { it.length }.toSet()).hasSize(1)
        assertThat(markers.map { it.trim() }).contains("pass")
        // Right-justified, not merely padded: the label must be the tail of the
        // column, and it must be the verdict's own label and no other.
        rows(healthy).values.forEach { row ->
            assertThat(row.line().take(width).trim()).isEqualTo(row.verdict.label)
        }
    }

    @Test
    fun the_timing_checks_are_never_counted_as_findings() {
        val timings = listOf("time to first byte", "DOM content loaded", "load")
        rows(healthy).filterKeys { it in timings }.forEach { (_, row) ->
            assertThat(row.verdict).isEqualTo(AuditVerdict.INFO)
        }
    }
}
