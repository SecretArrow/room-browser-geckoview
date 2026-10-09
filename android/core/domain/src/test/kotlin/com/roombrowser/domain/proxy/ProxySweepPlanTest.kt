package com.roombrowser.domain.proxy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProxySweepPlanTest {

    private fun candidates(n: Int) = (1..n).map { ProxyCandidate(host = "10.0.0.$it", port = 8080) }

    @Test
    fun `a slice is bounded and starts where it is told`() {
        val all = candidates(10)
        assertThat(ProxySweepPlan.slice(all, startIndex = 0, max = 3).map { it.host })
            .containsExactly("10.0.0.1", "10.0.0.2", "10.0.0.3").inOrder()
        assertThat(ProxySweepPlan.slice(all, startIndex = 4, max = 3).map { it.host })
            .containsExactly("10.0.0.5", "10.0.0.6", "10.0.0.7").inOrder()
    }

    @Test
    fun `a slice past the end wraps instead of coming back empty`() {
        // The cursor is persisted across sweeps, so it runs off the end routinely.
        val all = candidates(10)
        assertThat(ProxySweepPlan.slice(all, startIndex = 8, max = 4).map { it.host })
            .containsExactly("10.0.0.9", "10.0.0.10", "10.0.0.1", "10.0.0.2").inOrder()
    }

    @Test
    fun `a slice never repeats a candidate when it fits`() {
        val all = candidates(10)
        val slice = ProxySweepPlan.slice(all, startIndex = 3, max = 5)
        assertThat(slice.map { it.id }.toSet()).hasSize(5)
    }

    @Test
    fun `a slice larger than the catalogue is the whole catalogue`() {
        val all = candidates(4)
        assertThat(ProxySweepPlan.slice(all, startIndex = 2, max = 400).map { it.host })
            .containsExactly("10.0.0.3", "10.0.0.4", "10.0.0.1", "10.0.0.2").inOrder()
    }

    @Test
    fun `degenerate inputs are empty rather than a crash`() {
        assertThat(ProxySweepPlan.slice(emptyList(), 0, 10)).isEmpty()
        assertThat(ProxySweepPlan.slice(candidates(3), 0, 0)).isEmpty()
    }

    @Test
    fun `the next cursor advances by what was probed`() {
        val all = candidates(10)
        assertThat(ProxySweepPlan.nextIndex(all, startIndex = 4, probed = 3)).isEqualTo(7)
        // Two turns round the list, expressed as one number.
        assertThat(ProxySweepPlan.nextIndex(all, startIndex = 9, probed = 3)).isEqualTo(12)
        assertThat(ProxySweepPlan.nextIndex(all, startIndex = 12, probed = 1)).isEqualTo(3)
    }
}
