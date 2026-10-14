package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The scroll arithmetic behind a whole-document capture.
 *
 * It is a pure function so it can be held to these cases here rather than
 * discovered on a device: every one of them is a way to end up with a picture
 * that is not the page -- a missed bottom band, a repeated frame, a loop that
 * never advances, or a plan that claims to be complete when it is not.
 */
class ScrollStitchTest {

    @Test
    fun `a document shorter than the viewport is one strip`() {
        val plan = ScrollStitch.plan(documentHeightCss = 600, viewportHeightCss = 800)

        assertThat(plan.strips).hasSize(1)
        assertThat(plan.strips[0].scrollCss).isEqualTo(0)
        assertThat(plan.strips[0].heightCss).isEqualTo(600)
        assertThat(plan.capturedHeightCss).isEqualTo(600)
        assertThat(plan.truncated).isFalse()
    }

    @Test
    fun `a document of exactly three viewports is three strips`() {
        val plan = ScrollStitch.plan(documentHeightCss = 3000, viewportHeightCss = 1000)

        assertThat(plan.strips.map { it.scrollCss }).containsExactly(0, 1000, 2000).inOrder()
        assertThat(plan.capturedHeightCss).isEqualTo(3000)
        assertThat(plan.truncated).isFalse()
    }

    @Test
    fun `a partial last screen is reached by scrolling to the bottom, not past it`() {
        // 2500 of a 1000-tall viewport: the third strip must sit at the
        // document's own bottom offset, because a request for 2000 would be
        // clamped there anyway and planning it would make the strip look
        // shorter than the frame it yields.
        val plan = ScrollStitch.plan(documentHeightCss = 2500, viewportHeightCss = 1000)

        assertThat(plan.strips.map { it.scrollCss }).containsExactly(0, 1000, 1500).inOrder()
        assertThat(plan.strips[2].heightCss).isEqualTo(1000)
        assertThat(plan.capturedHeightCss).isEqualTo(2500)
        assertThat(plan.truncated).isFalse()
    }

    @Test
    fun `a page taller than the cap is truncated, and says so`() {
        val plan = ScrollStitch.plan(documentHeightCss = 40_000, viewportHeightCss = 1000)

        assertThat(plan.strips).hasSize(ScrollStitch.MAX_STRIPS)
        assertThat(plan.capturedHeightCss).isEqualTo(ScrollStitch.MAX_STRIPS * 1000)
        assertThat(plan.truncated).isTrue()
    }

    @Test
    fun `a truncated plan stops before the document bottom`() {
        val plan = ScrollStitch.plan(documentHeightCss = 40_000, viewportHeightCss = 1000)
        val bottom = plan.strips.last().scrollCss + plan.strips.last().heightCss

        assertThat(bottom).isEqualTo(plan.capturedHeightCss)
        assertThat(bottom).isLessThan(plan.documentHeightCss)
    }

    @Test
    fun `strips always advance, so no frame is captured twice`() {
        // A clamped request can land on the offset the previous strip already
        // used; the plan must not spend a round trip re-copying that frame.
        val plan = ScrollStitch.plan(documentHeightCss = 2200, viewportHeightCss = 1000)
        val offsets = plan.strips.map { it.scrollCss }

        assertThat(offsets).isInOrder()
        assertThat(offsets.toSet()).hasSize(offsets.size)
        // The tail strip is the clamped one, so this case is where a repeat
        // would appear if the plan did not skip an offset it had already used.
        assertThat(plan.truncated).isFalse()
    }

    @Test
    fun `an unusable viewport plans nothing rather than guessing`() {
        assertThat(ScrollStitch.plan(documentHeightCss = 5000, viewportHeightCss = 0).strips).isEmpty()
        assertThat(ScrollStitch.plan(documentHeightCss = 0, viewportHeightCss = 800).strips).isEmpty()
        assertThat(ScrollStitch.plan(documentHeightCss = -1, viewportHeightCss = 800).strips).isEmpty()
        assertThat(ScrollStitch.plan(documentHeightCss = 5000, viewportHeightCss = 800, maxStrips = 0).strips)
            .isEmpty()
    }

    @Test
    fun `an empty plan is not complete`() {
        // A refusal must never read as "the whole page fits in nothing".
        val plan = ScrollStitch.plan(documentHeightCss = 5000, viewportHeightCss = 0)

        assertThat(plan.capturedHeightCss).isEqualTo(0)
        assertThat(plan.truncated).isTrue()
    }

    @Test
    fun `a smaller cap truncates sooner`() {
        val plan = ScrollStitch.plan(documentHeightCss = 5000, viewportHeightCss = 1000, maxStrips = 2)

        assertThat(plan.strips).hasSize(2)
        assertThat(plan.capturedHeightCss).isEqualTo(2000)
        assertThat(plan.truncated).isTrue()
    }
}
