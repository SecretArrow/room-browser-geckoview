package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The arithmetic behind "did that click do anything".
 *
 * The cases that matter are the ones a real pair of frames produces: the same
 * page twice with compression noise, one small region repainted, a whole
 * document replaced, and a capture whose size changed under the comparison.
 */
class ScreenshotDiffTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    private fun grid(width: Int, height: Int, fill: Int): IntArray = IntArray(width * height) { fill }

    private fun withBlock(
        width: Int,
        height: Int,
        fill: Int,
        block: Int,
        left: Int,
        top: Int,
        blockWidth: Int,
        blockHeight: Int
    ): IntArray {
        val pixels = grid(width, height, fill)
        for (y in top until top + blockHeight) {
            for (x in left until left + blockWidth) pixels[y * width + x] = block
        }
        return pixels
    }

    @Test
    fun `two copies of the same frame are identical, with no box`() {
        val pixels = withBlock(20, 10, black, white, 3, 4, 2, 2)

        val result = ScreenshotDiff.compare(pixels, 20, 10, pixels.copyOf(), 20, 10)

        assertThat(result.identical).isTrue()
        assertThat(result.changedPixels).isEqualTo(0)
        assertThat(result.changedRatio).isEqualTo(0.0)
        assertThat(result.changedBox).isNull()
        assertThat(result.sameSize).isTrue()
    }

    @Test
    fun `a repainted block is reported as its own bounding box`() {
        val before = grid(20, 10, black)
        val after = withBlock(20, 10, black, white, 2, 1, 3, 2)

        val result = ScreenshotDiff.compare(before, 20, 10, after, 20, 10)

        assertThat(result.identical).isFalse()
        assertThat(result.changedPixels).isEqualTo(6)
        assertThat(result.totalPixels).isEqualTo(200)
        assertThat(result.changedBox).isEqualTo(ScreenshotDiff.Box(left = 2, top = 1, right = 4, bottom = 2))
        assertThat(result.changedBox!!.width).isEqualTo(3)
        assertThat(result.changedBox!!.height).isEqualTo(2)
    }

    @Test
    fun `a wholly replaced document changes every compared pixel`() {
        val result = ScreenshotDiff.compare(
            grid(8, 8, black), 8, 8,
            grid(8, 8, white), 8, 8
        )

        assertThat(result.changedRatio).isEqualTo(1.0)
        assertThat(result.changedBox).isEqualTo(ScreenshotDiff.Box(0, 0, 7, 7))
    }

    @Test
    fun `compression noise below the tolerance is not a change`() {
        val before = grid(10, 10, black)
        // Every pixel moved by 8 counts, which is under the default tolerance.
        val after = IntArray(100) { 0xFF000000.toInt() or 0x00080808 }

        val result = ScreenshotDiff.compare(before, 10, 10, after, 10, 10)

        assertThat(result.identical).isTrue()
        assertThat(result.changedBox).isNull()
    }

    @Test
    fun `the same noise is a change when the tolerance is zero`() {
        val before = grid(10, 10, black)
        val after = IntArray(100) { 0xFF000000.toInt() or 0x00080808 }

        val result = ScreenshotDiff.compare(before, 10, 10, after, 10, 10, tolerance = 0)

        assertThat(result.identical).isFalse()
        assertThat(result.changedPixels).isEqualTo(100)
    }

    @Test
    fun `frames of different sizes compare over the overlap and say so`() {
        val before = grid(20, 10, black)
        // The same page captured again at half the height, all of it repainted.
        val after = grid(20, 4, white)

        val result = ScreenshotDiff.compare(before, 20, 10, after, 20, 4)

        assertThat(result.sameSize).isFalse()
        assertThat(result.comparedWidth).isEqualTo(20)
        assertThat(result.comparedHeight).isEqualTo(4)
        assertThat(result.totalPixels).isEqualTo(80)
        // Never "identical" while a region went uncompared.
        assertThat(result.identical).isFalse()
        assertThat(result.changedRatio).isEqualTo(1.0)
    }

    @Test
    fun `a size change alone is not identical even when the overlap matches`() {
        val before = grid(20, 10, black)
        val after = grid(20, 4, black)

        val result = ScreenshotDiff.compare(before, 20, 10, after, 20, 4)

        assertThat(result.changedPixels).isEqualTo(0)
        assertThat(result.changedBox).isNull()
        assertThat(result.sameSize).isFalse()
        assertThat(result.identical).isFalse()
    }
}
