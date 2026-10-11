package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The vision bounds are the only thing keeping a screenshot from being sent at
 * full resolution or at megabytes of Base64. The capture itself needs an
 * Android Bitmap, so the arithmetic is factored out and pinned here.
 *
 * Every test ends on a VOID-returning Truth call: a Kotlin function whose last
 * expression has a value compiles to a non-void method, and JUnit4 then refuses
 * the whole class instead of running the rest of it.
 */
class AgentVisionBoundsTest {

    @Test
    fun `a frame wider than the cap is downscaled, not rejected`() {
        val size = AgentVisionBounds.scaledSize(2560, 1440)
        assertThat(size.width).isEqualTo(1280)
        assertThat(size.height).isEqualTo(720)
    }

    @Test
    fun `a tall frame is bounded on its long edge too`() {
        val size = AgentVisionBounds.scaledSize(1000, 2000)
        assertThat(size.width).isEqualTo(640)
        assertThat(size.height).isEqualTo(1280)
    }

    @Test
    fun `a frame already inside the cap is left alone, never upscaled`() {
        val size = AgentVisionBounds.scaledSize(320, 200)
        assertThat(size.width).isEqualTo(320)
        assertThat(size.height).isEqualTo(200)
    }

    @Test
    fun `a frame exactly on the cap is left alone`() {
        val size = AgentVisionBounds.scaledSize(1280, 800)
        assertThat(size.width).isEqualTo(1280)
        assertThat(size.height).isEqualTo(800)
    }

    @Test
    fun `an extreme ratio keeps the short edge at least one pixel`() {
        val size = AgentVisionBounds.scaledSize(4000, 3)
        assertThat(size.width).isEqualTo(1280)
        assertThat(size.height).isAtLeast(1)
    }

    @Test
    fun `base64 length follows the encoder`() {
        assertThat(AgentVisionBounds.base64Length(0)).isEqualTo(0)
        assertThat(AgentVisionBounds.base64Length(1)).isEqualTo(4)
        assertThat(AgentVisionBounds.base64Length(3)).isEqualTo(4)
        assertThat(AgentVisionBounds.base64Length(4)).isEqualTo(8)
    }

    @Test
    fun `the last payload that fits is accepted and one byte more is not`() {
        val bytesAtCap = AgentVisionBounds.MAX_BASE64_CHARS / 4 * 3
        assertThat(AgentVisionBounds.fits(bytesAtCap)).isTrue()
        assertThat(AgentVisionBounds.fits(bytesAtCap + 1)).isFalse()
    }
}
