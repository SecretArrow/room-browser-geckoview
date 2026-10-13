package com.roombrowser.domain.agent

import kotlin.math.abs

/** One captured frame's pixels, in ARGB, row-major (index = y * width + x). */
class ScreenshotFrame(
    val width: Int,
    val height: Int,
    val pixels: IntArray
)

/**
 * Pixel-level comparison of two page frames, so the agent can tell "the page
 * changed" from "the page looks the same" without shipping either screenshot
 * anywhere.
 *
 * Pure and Android-free: the capture needs a Bitmap, the arithmetic does not,
 * so it is unit-tested on the JVM. Only the overlapping region is compared, and
 * a colour channel counts as changed only when it moves by more than a
 * tolerance, so ordinary rendering jitter does not read as a page change.
 */
object ScreenshotDiff {

    /** What changed between two frames. */
    data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double)

    data class Result(
        /** True only when the two frames are the same size and every compared pixel matched. */
        val identical: Boolean,
        /** True when the two frames are not the same size. */
        val sizeChanged: Boolean,
        /** Fraction (0.0..1.0) of the COMPARED pixels that differ. */
        val changedRatio: Double,
        /** Fractional (0.0..1.0) bounds of the changed area within the compared region, or null when nothing changed. */
        val box: Box?
    )

    const val DEFAULT_TOLERANCE: Int = 12

    fun compare(
        before: ScreenshotFrame,
        after: ScreenshotFrame,
        tolerance: Int = DEFAULT_TOLERANCE
    ): Result {
        val sizeChanged = before.width != after.width || before.height != after.height
        val comparedWidth = minOf(before.width, after.width)
        val comparedHeight = minOf(before.height, after.height)

        // A zero-pixel overlap has no pixels in it, so nothing inside it can have
        // changed. Calling it "all changed" would report a change with no region
        // to act on, which is worse than reporting none.
        val comparedPixels = comparedWidth * comparedHeight
        if (comparedWidth <= 0 || comparedHeight <= 0 || comparedPixels <= 0) {
            return Result(
                identical = !sizeChanged,
                sizeChanged = sizeChanged,
                changedRatio = 0.0,
                box = null
            )
        }

        val allowed = if (tolerance < 0) 0 else tolerance
        val beforePixels = before.pixels
        val afterPixels = after.pixels
        val beforeStride = before.width
        val afterStride = after.width

        var changed = 0
        var minX = comparedWidth
        var maxX = -1
        var minY = comparedHeight
        var maxY = -1

        var y = 0
        while (y < comparedHeight) {
            var x = 0
            while (x < comparedWidth) {
                val a = beforePixels[y * beforeStride + x]
                val b = afterPixels[y * afterStride + x]
                if (channelDiffers(a, b, allowed)) {
                    changed++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                x++
            }
            y++
        }

        if (changed == 0) {
            return Result(
                identical = !sizeChanged,
                sizeChanged = sizeChanged,
                changedRatio = 0.0,
                box = null
            )
        }

        return Result(
            identical = false,
            sizeChanged = sizeChanged,
            changedRatio = changed.toDouble() / comparedPixels.toDouble(),
            box = Box(
                left = minX.toDouble() / comparedWidth,
                top = minY.toDouble() / comparedHeight,
                right = (maxX + 1).toDouble() / comparedWidth,
                bottom = (maxY + 1).toDouble() / comparedHeight
            )
        )
    }

    // Alpha is ignored: a captured bitmap's alpha is 0xFF on any normal capture
    // and carries no page information, so comparing it would only invent changes.
    private fun channelDiffers(a: Int, b: Int, tolerance: Int): Boolean {
        val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return dr > tolerance || dg > tolerance || db > tolerance
    }
}
