package com.roombrowser.devtools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The bound the panels promise in the caption: the newest N kept, the oldest
 * dropped, and a count of what was dropped so "2000 max" is never mistaken for
 * "nothing was lost".
 */
class DeveloperToolsRingTest {

    @Test
    fun a_ring_keeps_the_newest_up_to_its_capacity() {
        val ring = DeveloperToolsRing<Int>(3)
        listOf(1, 2, 3, 4, 5).forEach(ring::add)
        assertThat(ring.snapshot()).containsExactly(3, 4, 5).inOrder()
        assertThat(ring.dropped).isEqualTo(2)
    }

    @Test
    fun nothing_is_counted_as_dropped_before_the_ring_fills() {
        val ring = DeveloperToolsRing<Int>(3)
        listOf(1, 2, 3).forEach(ring::add)
        assertThat(ring.snapshot()).containsExactly(1, 2, 3).inOrder()
        assertThat(ring.dropped).isEqualTo(0)
    }

    @Test
    fun a_snapshot_is_a_copy_and_does_not_change_under_the_panel() {
        val ring = DeveloperToolsRing<Int>(2)
        ring.add(1)
        val first = ring.snapshot()
        ring.add(2)
        ring.add(3)
        assertThat(first).containsExactly(1)
        assertThat(ring.snapshot()).containsExactly(2, 3).inOrder()
    }

    @Test
    fun clearing_drops_the_loss_count_with_the_entries() {
        val ring = DeveloperToolsRing<Int>(1)
        listOf(1, 2, 3).forEach(ring::add)
        assertThat(ring.dropped).isEqualTo(2)
        ring.clear()
        assertThat(ring.snapshot()).isEmpty()
        // The user asked for a fresh feed, so the old loss is no longer theirs
        // to explain.
        assertThat(ring.dropped).isEqualTo(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun a_ring_that_holds_nothing_is_refused() {
        DeveloperToolsRing<Int>(0)
    }
}
