package com.roombrowser.domain.agent

/** One viewport-sized frame of a scrolled capture, in CSS pixels. */
data class ScrollStrip(val scrollCss: Int, val heightCss: Int)

/**
 * Which scroll offsets a whole-document capture visits, and how much document
 * they add up to.
 */
data class ScrollStitchPlan(
    val strips: List<ScrollStrip>,
    val capturedHeightCss: Int,
    val documentHeightCss: Int
) {
    /**
     * True when the plan stops before the bottom of the document.
     *
     * THIS IS THE WHOLE POINT OF THE TYPE. A page can be arbitrarily tall and
     * each strip costs a scroll, a frame and a copy, so the plan is bounded --
     * and a bounded plan that did not say it was bounded would hand the model a
     * picture of the top of a page under the name "the whole page".
     */
    val truncated: Boolean get() = capturedHeightCss < documentHeightCss
}

/**
 * Plans a scroll-and-stitch capture of a document taller than its viewport.
 *
 * The capture cannot be one engine call here: the only Android API that returns
 * a document-sized image in one shot draws synchronously on the main thread,
 * and this project has already watched that deadlock a WebView. So the frame is
 * assembled from viewport captures at increasing scroll offsets, and the
 * arithmetic of where to scroll is what this object owns.
 *
 * [MAX_STRIPS] bounds the number of round trips. It is a TIME bound, not a
 * memory one: each strip is copied, drawn into the output and recycled, so the
 * peak allocation is one viewport frame plus the output image, whatever the
 * document height.
 */
object ScrollStitch {

    /** How many viewport captures one whole-document shot may cost. */
    const val MAX_STRIPS = 6

    fun plan(
        documentHeightCss: Int,
        viewportHeightCss: Int,
        maxStrips: Int = MAX_STRIPS
    ): ScrollStitchPlan {
        if (documentHeightCss <= 0 || viewportHeightCss <= 0 || maxStrips <= 0) {
            return ScrollStitchPlan(emptyList(), 0, documentHeightCss.coerceAtLeast(0))
        }
        // The bottom-most offset the page allows: the last viewport is flush
        // with the end of the document, so a document that is not a whole
        // number of viewports tall is covered without a short final strip.
        val maxScrollCss = (documentHeightCss - viewportHeightCss).coerceAtLeast(0)
        val strips = ArrayList<ScrollStrip>(maxStrips)
        var requested = 0
        while (requested < documentHeightCss && strips.size < maxStrips) {
            val at = minOf(requested, maxScrollCss)
            // A clamped request can repeat the previous offset; visiting it
            // twice would copy the same frame again and never reach the bottom.
            if (strips.lastOrNull()?.scrollCss != at) {
                strips.add(ScrollStrip(at, minOf(viewportHeightCss, documentHeightCss - at)))
            }
            requested += viewportHeightCss
        }
        val captured = strips.lastOrNull()?.let { it.scrollCss + it.heightCss } ?: 0
        return ScrollStitchPlan(strips, captured, documentHeightCss)
    }
}
