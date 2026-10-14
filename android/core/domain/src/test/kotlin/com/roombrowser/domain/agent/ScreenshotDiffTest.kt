package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * [ScreenshotDiff] decides whether a page changed, so it has to be exact about
 * the tolerance edge, the overlap when sizes differ, and the fractional box.
 * None of that needs Android, so it is pinned here.
 */
class ScreenshotDiffTest {

    @Test
    fun `identical frames report identical with no box`() {
        val result = ScreenshotDiff.compare(frame(4, 4), frame(4, 4))
        assertThat(result.identical).isEqualTo(true)
        assertThat(result.sizeChanged).isEqualTo(false)
        assertWithMessage("identical frames must change no pixel")
            .that(result.changedRatio).isEqualTo(0.0)
        assertThat(result.box).isNull()
    }

    @Test
    fun `a single changed pixel is a sixteenth of a four by four frame`() {
        val after = frame(4, 4)
        after.pixels[2 * 4 + 1] = WHITE
        val result = ScreenshotDiff.compare(frame(4, 4), after)
        assertThat(result.identical).isEqualTo(false)
        assertWithMessage("one pixel of sixteen")
            .that(result.changedRatio).isEqualTo(1.0 / 16.0)
        assertThat(result.box).isNotNull()
        val box = result.box!!
        assertWithMessage("changed cell column").that(box.left).isEqualTo(0.25)
        assertWithMessage("changed cell row").that(box.top).isEqualTo(0.5)
        assertWithMessage("changed cell right edge").that(box.right).isEqualTo(0.5)
        assertWithMessage("changed cell bottom edge").that(box.bottom).isEqualTo(0.75)
    }

    @Test
    fun `a channel delta below tolerance is not a change`() {
        val before = frame(4, 4, fill = rgb(100, 100, 100))
        val after = frame(4, 4, fill = rgb(100, 100, 100))
        after.pixels[0] = rgb(105, 100, 100)
        val result = ScreenshotDiff.compare(before, after)
        assertThat(result.identical).isEqualTo(true)
        assertThat(result.changedRatio).isEqualTo(0.0)
        assertThat(result.box).isNull()
    }

    @Test
    fun `a channel delta exactly at tolerance is not a change`() {
        val before = frame(4, 4, fill = rgb(100, 100, 100))
        val after = frame(4, 4, fill = rgb(100, 100, 100))
        after.pixels[0] = rgb(112, 100, 100)
        val result = ScreenshotDiff.compare(before, after)
        assertThat(result.identical).isEqualTo(true)
        assertThat(result.changedRatio).isEqualTo(0.0)
    }

    @Test
    fun `a channel delta one above tolerance is a change`() {
        val before = frame(4, 4, fill = rgb(100, 100, 100))
        val after = frame(4, 4, fill = rgb(100, 100, 100))
        after.pixels[0] = rgb(113, 100, 100)
        val result = ScreenshotDiff.compare(before, after)
        assertThat(result.identical).isEqualTo(false)
        assertWithMessage("one pixel of sixteen")
            .that(result.changedRatio).isEqualTo(1.0 / 16.0)
    }

    @Test
    fun `a pure size change over an identical overlap reports sizeChanged with no pixel change`() {
        val result = ScreenshotDiff.compare(frame(4, 4), frame(6, 4))
        assertThat(result.sizeChanged).isEqualTo(true)
        assertThat(result.identical).isEqualTo(false)
        assertThat(result.changedRatio).isEqualTo(0.0)
        assertThat(result.box).isNull()
    }

    @Test
    fun `a size change with a changed overlap measures the ratio over the overlap only`() {
        val after = frame(6, 4)
        after.pixels[0] = WHITE
        val result = ScreenshotDiff.compare(frame(4, 4), after)
        assertThat(result.sizeChanged).isEqualTo(true)
        assertThat(result.identical).isEqualTo(false)
        assertWithMessage("one pixel of the four by four overlap")
            .that(result.changedRatio).isEqualTo(1.0 / 16.0)
        assertThat(result.box).isNotNull()
        assertWithMessage("overlap-relative right edge")
            .that(result.box!!.right).isEqualTo(0.25)
    }

    @Test
    fun `a zero-width overlap compares nothing and reports no change`() {
        val result = ScreenshotDiff.compare(frame(0, 4), frame(4, 4))
        assertWithMessage("a degenerate overlap must not divide by zero")
            .that(result.changedRatio).isEqualTo(0.0)
        assertThat(result.changedRatio.isNaN()).isEqualTo(false)
        assertThat(result.box).isNull()
        assertThat(result.sizeChanged).isEqualTo(true)
        assertThat(result.identical).isEqualTo(false)
    }

    @Test
    fun `a zero-height frame does not crash or produce NaN`() {
        val result = ScreenshotDiff.compare(frame(4, 0), frame(4, 0))
        assertThat(result.changedRatio.isNaN()).isEqualTo(false)
        assertThat(result.changedRatio).isEqualTo(0.0)
        assertThat(result.box).isNull()
        assertThat(result.sizeChanged).isEqualTo(false)
        assertThat(result.identical).isEqualTo(true)
    }

    @Test
    fun `a negative tolerance is coerced to zero instead of making every pixel differ`() {
        val identical = ScreenshotDiff.compare(frame(4, 4), frame(4, 4), tolerance = -5)
        assertThat(identical.identical).isEqualTo(true)

        val before = frame(4, 4, fill = rgb(100, 100, 100))
        val after = frame(4, 4, fill = rgb(100, 100, 100))
        after.pixels[0] = rgb(101, 100, 100)
        val oneOff = ScreenshotDiff.compare(before, after, tolerance = -5)
        assertWithMessage("tolerance 0 counts a delta of 1 as a change")
            .that(oneOff.changedRatio).isEqualTo(1.0 / 16.0)
    }

    @Test
    fun `differing alpha with matching rgb is not a change`() {
        val before = frame(4, 4, fill = rgb(100, 100, 100, a = 0xFF))
        val after = frame(4, 4, fill = rgb(100, 100, 100, a = 0x00))
        val result = ScreenshotDiff.compare(before, after)
        assertThat(result.identical).isEqualTo(true)
        assertThat(result.changedRatio).isEqualTo(0.0)
        assertThat(result.box).isNull()
    }

    @Test
    fun `a change confined to one corner reports that corner, not the whole frame`() {
        val after = frame(4, 4)
        for (y in 2 until 4) {
            for (x in 2 until 4) {
                after.pixels[y * 4 + x] = WHITE
            }
        }
        val result = ScreenshotDiff.compare(frame(4, 4), after)
        assertWithMessage("four pixels of sixteen")
            .that(result.changedRatio).isEqualTo(4.0 / 16.0)
        assertThat(result.box).isNotNull()
        val box = result.box!!
        assertWithMessage("corner left").that(box.left).isEqualTo(0.5)
        assertWithMessage("corner top").that(box.top).isEqualTo(0.5)
        assertWithMessage("corner right").that(box.right).isEqualTo(1.0)
        assertWithMessage("corner bottom").that(box.bottom).isEqualTo(1.0)
    }

    @Test
    fun `the box stays within zero and one for a change on the very last pixel`() {
        val after = frame(4, 4)
        after.pixels[3 * 4 + 3] = WHITE
        val box = ScreenshotDiff.compare(frame(4, 4), after).box!!
        assertWithMessage("last column left").that(box.left).isEqualTo(0.75)
        assertWithMessage("last row top").that(box.top).isEqualTo(0.75)
        assertWithMessage("the +1 makes the right edge exactly one")
            .that(box.right).isEqualTo(1.0)
        assertWithMessage("the +1 makes the bottom edge exactly one")
            .that(box.bottom).isEqualTo(1.0)
        assertThat(box.left in 0.0..1.0).isEqualTo(true)
        assertThat(box.top in 0.0..1.0).isEqualTo(true)
        assertThat(box.right in 0.0..1.0).isEqualTo(true)
        assertThat(box.bottom in 0.0..1.0).isEqualTo(true)
    }

    private fun frame(width: Int, height: Int, fill: Int = BLACK): ScreenshotFrame =
        ScreenshotFrame(width, height, IntArray(width * height) { fill })

    private fun rgb(r: Int, g: Int, b: Int, a: Int = 0xFF): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    private companion object {
        val BLACK = 0xFF000000.toInt()
        val WHITE = 0xFFFFFFFF.toInt()
    }
}
