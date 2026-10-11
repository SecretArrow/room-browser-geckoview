package com.roombrowser.devtools

import com.google.common.truth.Truth.assertThat
import com.roombrowser.engine.devtools.DeveloperToolsCapabilities
import com.roombrowser.engine.devtools.DevToolsCapability
import org.junit.Test

/**
 * The honesty page's content is DERIVED from the capability set, so what is
 * worth pinning here is that derivation: an engine reason reaches the "not
 * possible" group, a missing reason becomes "not built yet" instead of an
 * invented explanation, and a capability the engine serves is never listed as a
 * limit at all.
 */
class DevToolsLimitsTest {

    private fun caps(
        present: Set<DevToolsCapability> = emptySet(),
        notes: Map<DevToolsCapability, String> = emptyMap()
    ) = DeveloperToolsCapabilities(capabilities = present, notes = notes)

    private fun section(limits: List<LimitsSection>, title: String): LimitsSection =
        limits.single { it.title == title }

    @Test
    fun an_engine_reason_is_rendered_under_not_possible() {
        val capabilities = caps(
            notes = mapOf(DevToolsCapability.NETWORK_RESPONSE_HEADERS to "no response is handed back")
        )
        val rows = section(capabilityLimits(capabilities), "Not possible on this engine").rows
        assertThat(rows.map { it.title }).contains("Response headers")
        assertThat(rows.single { it.title == "Response headers" }.detail)
            .isEqualTo("no response is handed back")
    }

    @Test
    fun a_capability_with_no_reason_reads_as_not_built_yet_and_carries_no_invented_cause() {
        val rows = section(capabilityLimits(caps()), "Not built yet").rows
        val debugger = rows.single { it.title == "JavaScript debugger" }
        // The detail is the capability's own description, never a guessed reason.
        assertThat(debugger.detail).contains("Breakpoints")
        assertThat(debugger.detail).doesNotContain("engine cannot")
    }

    @Test
    fun a_present_capability_is_never_listed_as_a_limit() {
        val capabilities = caps(present = setOf(DevToolsCapability.CONSOLE_CAPTURE))
        val titles = capabilityLimits(capabilities).flatMap { it.rows }.map { it.title }
        assertThat(titles).doesNotContain("Console capture")
        assertThat(titles).contains("Engine console")
    }

    @Test
    fun a_present_capability_never_surfaces_its_note() {
        val capabilities = caps(
            present = setOf(DevToolsCapability.SECURITY_INFO),
            notes = mapOf(DevToolsCapability.SECURITY_INFO to "should never appear")
        )
        val details = capabilityLimits(capabilities).flatMap { it.rows }.map { it.detail }
        assertThat(details).doesNotContain("should never appear")
    }

    @Test
    fun the_lighthouse_caption_is_quoted_verbatim_and_promises_no_score() {
        val audit = sharedLimits().flatMap { it.rows }.single { it.title == "Audit, and what it is not" }
        assertThat(audit.detail).contains(NOT_LIGHTHOUSE_CAPTION)
        assertThat(audit.detail).contains("no score")
    }

    @Test
    fun the_shared_limits_state_ios_is_inapplicable_and_puppeteer_is_not_offered() {
        val titles = sharedLimits().flatMap { it.rows }.map { it.title }
        assertThat(titles).containsAtLeast("iOS", "Puppeteer and Node export", "Memory and heap snapshots")
    }

    @Test
    fun the_page_reads_this_editions_gaps_before_the_shared_ones() {
        val titles = devToolsLimits(caps()).map { it.title }
        assertThat(titles).containsAtLeast("Not built yet", "The same on both editions")
        assertThat(titles.indexOf("Not built yet"))
            .isLessThan(titles.indexOf("The same on both editions"))
    }

    @Test
    fun the_copy_text_names_the_engine_and_carries_every_section() {
        val capabilities = caps(present = setOf(DevToolsCapability.PAGE_SCRIPTING))
        val text = devToolsLimitsText("Android WebView 999", capabilities)
        assertThat(text).contains("Android WebView 999")
        devToolsLimits(capabilities).forEach { section ->
            assertThat(text).contains("${section.title}:")
        }
    }

    @Test
    fun no_capability_at_all_still_produces_a_page() {
        val text = devToolsLimitsText("", DeveloperToolsCapabilities.NONE)
        assertThat(text).contains("Not built yet:")
        assertThat(text).isNotEmpty()
    }
}
