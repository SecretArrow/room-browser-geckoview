package com.roombrowser.domain.agent

/**
 * Two frames of the same page, compared.
 *
 * This exists to close the loop an agent otherwise cannot close: a click is
 * reported by the engine as "the element was found and clicked", which says
 * nothing about whether anything HAPPENED. The only honest answer to "did that
 * work" is that the page looks different now — and that question is arithmetic
 * over pixels, so it lives here, away from Android, where it can be tested.
 *
 * PURE. The caller owns the bitmaps and hands over their pixels; nothing here
 * allocates a Bitmap, touches a canvas, or knows what an engine is.
 *
 * A TOLERANCE IS NOT CHEATING. Two captures of a page that has not changed are
 * never byte-identical: JPEG and the compositor's own antialiasing move a
 * channel by a few counts, and a live page's clock, cursor or video moves it by
 * more. A zero-tolerance comparison therefore reports "everything changed" on a
 * static page and is useless for the one job it has. [DEFAULT_TOLERANCE] is the
 * per-channel difference below which a pixel counts as the same; a caller that
 * needs strictness can pass 0 and get the raw answer.
 */
object ScreenshotDiff {

    /**
     * The per-channel difference below which two pixels count as the same.
     *
     * Chosen against compression and antialiasing noise rather than a real
     * content change: a rendered glyph edge moves a channel by tens of counts,
     * while re-encoding the same frame moves it by single digits.
     */
    const val DEFAULT_TOLERANCE = 12

    /** A rectangle, in pixels of the compared grid. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left + 1
        val height: Int get() = bottom - top + 1
    }

    /**
     * What the comparison found.
     *
     * [sameSize] is reported rather than hidden: when the two frames differ in
     * size the comparison runs over the region they share, so a ratio computed
     * over a cropped overlap means something narrower than "the page". A caller
     * that says "nothing changed" without mentioning a size change is making a
     * claim the numbers do not support, which is why the flag travels with them.
     *
     * [changedBox] is null exactly when nothing changed, so a caller never has
     * to test a rectangle for emptiness.
     */
    data class Result(
        val sameSize: Boolean,
        val comparedWidth: Int,
        val comparedHeight: Int,
        val changedPixels: Int,
        val totalPixels: Int,
        val changedRatio: Double,
        val changedBox: Box?,
        val identical: Boolean
    )

    /**
     * Compares [before] with [after], each as row-major ARGB pixels.
     *
     * The two arrays may describe different sizes; the comparison covers the
     * smaller width and height and [Result.sameSize] reports whether anything
     * was left out.
     */
    fun compare(
        before: IntArray,
        beforeWidth: Int,
        beforeHeight: Int,
        after: IntArray,
        afterWidth: Int,
        afterHeight: Int,
        tolerance: Int = DEFAULT_TOLERANCE
    ): Result {
        require(beforeWidth > 0 && beforeHeight > 0) { "before frame has no pixels" }
        require(afterWidth > 0 && afterHeight > 0) { "after frame has no pixels" }
        require(before.size >= beforeWidth * beforeHeight) { "before frame is smaller than its size" }
        require(after.size >= afterWidth * afterHeight) { "after frame is smaller than its size" }

        val width = minOf(beforeWidth, afterWidth)
        val height = minOf(beforeHeight, afterHeight)

        var changed = 0
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1

        for (y in 0 until height) {
            val beforeRow = y * beforeWidth
            val afterRow = y * afterWidth
            for (x in 0 until width) {
                if (differs(before[beforeRow + x], after[afterRow + x], tolerance)) {
                    changed++
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }

        val total = width * height
        val sameSize = beforeWidth == afterWidth && beforeHeight == afterHeight
        return Result(
            sameSize = sameSize,
            comparedWidth = width,
            comparedHeight = height,
            changedPixels = changed,
            totalPixels = total,
            changedRatio = if (total == 0) 0.0 else changed.toDouble() / total.toDouble(),
            changedBox = if (changed == 0) null else Box(left, top, right, bottom),
            identical = changed == 0 && sameSize
        )
    }

    /**
     * Whether two ARGB pixels differ by more than [tolerance] on any colour
     * channel.
     *
     * Alpha is deliberately ignored: both frames come from the same capture
     * path, so an alpha difference is not something a page can do, and counting
     * it would only add noise.
     */
    private fun differs(a: Int, b: Int, tolerance: Int): Boolean {
        if (a == b) return false
        val dr = kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = kotlin.math.abs((a and 0xFF) - (b and 0xFF))
        val worst = maxOf(dr, dg, db)
        return worst > tolerance
    }
}
