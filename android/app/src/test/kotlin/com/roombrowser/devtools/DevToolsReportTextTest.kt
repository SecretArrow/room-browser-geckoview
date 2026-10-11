package com.roombrowser.devtools

import com.google.common.truth.Truth.assertThat
import com.roombrowser.engine.devtools.DeveloperToolsCapabilities
import com.roombrowser.engine.devtools.DevToolsCapability
import org.junit.Test

/**
 * The report-shaped copy controls: whole-feed blocks, the header that states a
 * block's bound, and the capability inventory. These are the texts the owner
 * asked for a copy icon on, and the only part of that affordance a JVM test can
 * reach.
 */
class DevToolsReportTextTest {

    @Test
    fun one_console_row_copies_exactly_as_the_feed_writes_it() {
        val entry = ConsoleEntry(level = "error", text = "boom", source = "a.js", line = 7)
        assertThat(consoleLine(entry)).isEqualTo(consoleText(listOf(entry)))
    }

    @Test
    fun a_feed_header_states_its_scope_its_cap_and_any_loss() {
        assertThat(feedHeaderText("Console", "all levels", 3, 2000, 0))
            .isEqualTo("Console — 3 entries (all levels, feed keeps 2000)")
        assertThat(feedHeaderText("Network", "FAILED", 1, 2000, 0))
            .isEqualTo("Network — 1 entry (FAILED, feed keeps 2000)")
        val lost = feedHeaderText("Console", "all levels", 3, 2000, 57)
        assertThat(lost).contains("57 older entries were dropped from the feed")
        // The bound is only worth stating when there is one.
        assertThat(feedHeaderText("Console", "all levels", 3, 0, 0)).isEqualTo("Console — 3 entries (all levels)")
    }

    @Test
    fun a_filter_that_matched_nothing_says_so_instead_of_pasting_an_empty_page() {
        val text = consoleReport(emptyList(), level = "error", cap = 2000, dropped = 0)
        assertThat(text).contains("Console — 0 entries (error, feed keeps 2000)")
        assertThat(text).contains("(nothing captured for this filter)")
    }

    @Test
    fun a_network_report_names_the_kind_it_was_filtered_to() {
        val text = networkReport(
            listOf(NetworkEntry(kind = "FAILED", url = "https://example.com/gone")),
            filter = "FAILED",
            cap = 2000,
            dropped = 0
        )
        assertThat(text).contains("Network — 1 entry (FAILED, feed keeps 2000)")
        assertThat(text).contains("FAILED")
        assertThat(text).doesNotContain("nothing captured")
    }

    @Test
    fun a_single_row_copy_keeps_the_facts_the_row_draws() {
        val text = networkRowText(
            NetworkEntry(
                kind = "RESOURCE",
                url = "https://example.com/app.js",
                method = "GET",
                status = 200,
                resourceType = "script",
                transferSize = 4096.0,
                durationMs = 12.0,
                isForMainFrame = false
            )
        )
        val lines = text.lines()
        assertThat(lines[0]).isEqualTo("RESOURCE GET 200 https://example.com/app.js")
        assertThat(lines[1]).contains("type script")
        assertThat(lines[1]).contains("subresource")
        assertThat(lines[1]).contains("size 4096 B")
        assertThat(lines[1]).contains("took 12 ms")
    }

    @Test
    fun a_row_whose_sizes_were_hidden_still_says_so_when_copied_alone() {
        val text = networkRowText(
            NetworkEntry(kind = "RESOURCE", url = "https://cdn.example.net/f.woff2", sizesHidden = true)
        )
        assertThat(text.lines()[0]).contains("[sizes hidden: no Timing-Allow-Origin]")
    }

    @Test
    fun the_capability_text_separates_cannot_from_not_built_yet() {
        val text = capabilitiesText(
            "WebView",
            DeveloperToolsCapabilities(
                capabilities = setOf(DevToolsCapability.PAGE_SCRIPTING),
                notes = mapOf(DevToolsCapability.CPU_PROFILER to "This engine exposes no sampler to the app.")
            )
        )
        val lines = text.lines()
        assertThat(lines[0]).isEqualTo("Developer tools — WebView")
        assertThat(lines).contains("Available:")
        assertThat(lines).contains("  Page scripting — ${capabilityDetail(DevToolsCapability.PAGE_SCRIPTING)}")
        assertThat(lines.indexOf("Not possible on this engine:")).isLessThan(lines.indexOf("Not built yet:"))
        assertThat(lines).contains("  ${capabilityLabel(DevToolsCapability.CPU_PROFILER)} — This engine exposes no sampler to the app.")
        assertThat(lines).contains("  ${capabilityLabel(DevToolsCapability.JS_DEBUGGER)} — ${capabilityDetail(DevToolsCapability.JS_DEBUGGER)}")
    }

    @Test
    fun an_edition_with_no_capability_says_that_rather_than_listing_groups() {
        val text = capabilitiesText("GeckoView", DeveloperToolsCapabilities.NONE)
        assertThat(text).contains("No inspector capability at all")
        assertThat(text).doesNotContain("Available:")
    }

    @Test
    fun a_storage_row_the_engine_never_answered_is_absent_rather_than_none_reported() {
        assertThat(storageRowValue(PageOverview())).isNull()
        assertThat(storageRowValue(PageOverview(localStorage = false, caches = false)))
            .isEqualTo("none present")
        assertThat(storageRowValue(PageOverview(localStorage = true, indexedDb = true)))
            .isEqualTo("local, indexeddb")
    }
}
