package com.roombrowser.domain.agent

import kotlin.math.roundToInt

/** Pixel dimensions of a frame after vision downscaling. */
data class PixelSize(val width: Int, val height: Int)

/**
 * The arithmetic behind a page screenshot: how big a captured frame may be
 * before it is sent, and how large its Base64 payload may grow.
 *
 * Pure, so the bounds are unit-tested on the JVM — the capture itself needs an
 * Android Bitmap and can only be exercised on a device.
 */
object AgentVisionBounds {

    /** The longest edge a frame is downscaled to. */
    const val MAX_LONG_EDGE = 1280

    /** JPEG quality of the first encode. */
    const val JPEG_QUALITY = 70

    /** JPEG quality of the single retry when the first encode is still too large. */
    const val RETRY_JPEG_QUALITY = 40

    /** The hard ceiling on the Base64 payload, in characters. */
    const val MAX_BASE64_CHARS = 1_500_000

    /**
     * The size [width] x [height] is downscaled to so its LONG edge is at most
     * [maxLongEdge], preserving aspect ratio. Never upscales, and never yields
     * a zero dimension for a non-empty frame.
     */
    fun scaledSize(width: Int, height: Int, maxLongEdge: Int = MAX_LONG_EDGE): PixelSize {
        if (width <= 0 || height <= 0) {
            return PixelSize(width.coerceAtLeast(0), height.coerceAtLeast(0))
        }
        val longEdge = maxOf(width, height)
        if (longEdge <= maxLongEdge) return PixelSize(width, height)
        val scale = maxLongEdge.toDouble() / longEdge
        return PixelSize(
            width = (width * scale).roundToInt().coerceAtLeast(1),
            height = (height * scale).roundToInt().coerceAtLeast(1)
        )
    }

    /** The Base64 length [byteCount] bytes encode to. */
    fun base64Length(byteCount: Int): Int = 4 * ((byteCount + 2) / 3)

    /** Whether an encoded payload of [byteCount] bytes fits [MAX_BASE64_CHARS]. */
    fun fits(byteCount: Int): Boolean = base64Length(byteCount) <= MAX_BASE64_CHARS
}
