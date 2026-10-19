package com.roombrowser.agent.ui

import com.google.common.truth.Truth.assertThat
import com.roombrowser.agent.AgentEntry
import org.junit.Test

/**
 * What "load older" must not move: the reader's row and the auto-scroll's
 * landing signal.
 *
 * [olderPageScrollTarget] is the arithmetic that repays the viewport's
 * anchor after a page of older rows lands in front of it — the panel's
 * no-jump promise is only as good as this function, so it is pinned here
 * the same way [transcriptKeys] is. The last two tests pin the OTHER half of
 * that promise: the auto-scroll keys on the last entry's key, and prepending
 * rows in front must leave that key alone (and must never manufacture a
 * duplicate key out of a same-millisecond row).
 */
class LoadOlderScrollTest {

    @Test
    fun `an anchored row stays put when a page lands above it`() {
        // Slot 0 is the affordance, so firstIndex 3 is the third entry; 200
        // rows land in front of it and the affordance keeps its slot.
        val target = olderPageScrollTarget(firstIndex = 3, added = 200, stillMore = true)
        assertThat(target).isEqualTo(203)
    }

    @Test
    fun `the last page lands without the affordance's slot`() {
        // The affordance leaves with the last page, so the anchored entry
        // moves one slot less than the rows that landed.
        val target = olderPageScrollTarget(firstIndex = 3, added = 200, stillMore = false)
        assertThat(target).isEqualTo(202)
    }

    @Test
    fun `the reader at the very top is not moved`() {
        // Being at the list's top IS looking at the affordance: staying put
        // shows them exactly the older rows they asked for.
        assertThat(olderPageScrollTarget(firstIndex = 0, added = 200, stillMore = true)).isNull()
        assertThat(olderPageScrollTarget(firstIndex = 0, added = 200, stillMore = false)).isNull()
    }

    @Test
    fun `nothing landing moves nothing`() {
        assertThat(olderPageScrollTarget(firstIndex = 5, added = 0, stillMore = true)).isNull()
        assertThat(olderPageScrollTarget(firstIndex = 5, added = 0, stillMore = false)).isNull()
    }

    @Test
    fun `prepending older rows leaves the last entry's key alone`() {
        // The auto-scroll's landing signal is the LAST entry's key; older
        // rows land in front of it and must not re-key it, or every "load
        // older" would yank the reader to the bottom of the chat.
        val before = listOf(
            AgentEntry.User("hi", at = 50L),
            AgentEntry.Assistant("hello", thinking = "", streaming = false, at = 51L)
        )
        val older = listOf(
            AgentEntry.User("older question", at = 10L),
            AgentEntry.Assistant("older answer", thinking = "", streaming = false, at = 11L)
        )
        val beforeKeys = transcriptKeys(before)
        val afterKeys = transcriptKeys(older + before)
        assertThat(afterKeys.last()).isEqualTo(beforeKeys.last())
        assertThat(afterKeys.first()).isNotEqualTo(beforeKeys.first())
    }

    @Test
    fun `a prepended row sharing a millisecond keeps every key unique`() {
        // Rows written within one clock tick at opposite ends of a long chat
        // can share a stem; the positional pass must still disambiguate them,
        // because a duplicate key takes the whole panel down.
        val before = listOf(
            AgentEntry.User("hi", at = 50L),
            AgentEntry.Assistant("hello", thinking = "", streaming = false, at = 51L)
        )
        val older = listOf(
            AgentEntry.User("older question", at = 50L),
            AgentEntry.Assistant("older answer", thinking = "", streaming = false, at = 11L)
        )
        val keys = transcriptKeys(older + before)
        assertThat(keys).hasSize(4)
        assertThat(keys.toSet()).hasSize(4)
    }
}
