package com.roombrowser.devtools

import com.google.common.truth.Truth.assertThat
import com.roombrowser.engine.devtools.DevToolsPanelId
import org.junit.Test

/**
 * The panel strip's naming contract.
 *
 * A chip carries the same word as the section header the panel draws below it,
 * so "Console" alone names two different nodes in the accessibility tree. The
 * chip's own description is what separates them -- for a screen reader, and for
 * the e2e suite, which selects panels by this exact string. A test that built
 * its own spelling would keep passing while selecting nothing, so the spelling
 * is asserted here, once.
 */
class DevToolsPanelStripTest {

    @Test
    fun every_chip_description_is_distinct_from_the_header_it_shares_a_word_with() {
        DevToolsPanelId.entries.forEach { id ->
            assertThat(panelChipDescription(id)).isNotEqualTo(id.title)
            assertThat(panelChipDescription(id)).contains(id.title)
        }
    }

    @Test
    fun no_two_panels_announce_themselves_the_same_way() {
        assertThat(DevToolsPanelId.entries.map { panelChipDescription(it) }).containsNoDuplicates()
    }

    @Test
    fun the_chips_the_e2e_suite_selects_by_keep_their_spelling() {
        // These three strings are literals in DevToolsConsoleNetworkE2eTest. It
        // compiles in no job but `e2e`, so a rename here would cost a full
        // cycle to discover -- this is the cheap half of that contract.
        assertThat(panelChipDescription(DevToolsPanelId.OVERVIEW)).isEqualTo("Overview panel")
        assertThat(panelChipDescription(DevToolsPanelId.CONSOLE)).isEqualTo("Console panel")
        assertThat(panelChipDescription(DevToolsPanelId.NETWORK)).isEqualTo("Network panel")
    }
}
