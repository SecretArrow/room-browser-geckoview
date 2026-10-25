package com.roombrowser.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The GPU strings are the half of the fingerprint a page reads next to the
 * UA: `WEBGL_debug_renderer_info` hands out UNMASKED_VENDOR_WEBGL and
 * UNMASKED_RENDERER_WEBGL, and a renderer the handset could not have is
 * exactly the mismatch these profiles exist to avoid. The catalogue used to
 * give all 1021 devices two vendors and six strings, with every Pixel
 * labelled Qualcomm/Adreno -- a Tensor SoC has a Mali GPU, so that pair was a
 * combination no real Pixel ever sent. These tests pin the contract.
 */
class DevicesGpuTest {

    /** A renderer as Chrome actually spells it, per GPU family. */
    private val rendererFormat = Regex(
        "^Adreno \\(TM\\) [0-9]{3}[A-Z]?$" +
            "|^Mali-G[0-9]+(-Immortalis)? (MC|MP)[0-9]+$" +
            "|^Xclipse [0-9]{3}$" +
            "|^PowerVR Rogue GE[0-9]{4}$"
    )

    /** The vendor string each renderer's family ships with. */
    private val vendorForRenderer = listOf(
        "Adreno" to "Qualcomm",
        "Mali" to "ARM",
        "Xclipse" to "Samsung",
        "PowerVR" to "Imagination Technologies"
    )

    @Test
    fun `every device carries a well-formed, correctly-vendored GPU string`() {
        for (device in Devices.all) {
            assertThat(device.gpuVendor).isNotEmpty()
            assertThat(device.gpuRenderer).isNotEmpty()
            assertThat(device.gpuRenderer).matches(rendererFormat.pattern)
            val family = vendorForRenderer.single { device.gpuRenderer.startsWith(it.first) }
            assertThat(device.gpuVendor).isEqualTo(family.second)
        }
    }

    @Test
    fun `Tensor devices report ARM Mali, never a Qualcomm Adreno`() {
        val pixels = Devices.all.filter { it.brand == "Google" }
        assertThat(pixels).hasSize(9)
        for (pixel in pixels) {
            assertThat(pixel.gpuVendor).isEqualTo("ARM")
            assertThat(pixel.gpuRenderer).startsWith("Mali-")
        }
        // Google has never shipped a Qualcomm SoC in a Pixel, so no entry
        // here may claim Adreno: the UA says "Pixel 9" and the renderer says
        // "Adreno (TM) ..." would be a pair a page can rule out on sight.
        assertThat(pixels.none { it.gpuRenderer.startsWith("Adreno") }).isTrue()
    }

    @Test
    fun `no single renderer string dominates the catalogue`() {
        val total = Devices.all.size
        val byRenderer = Devices.all.groupingBy { it.gpuRenderer }.eachCount()

        // The catalogue used to put 497 of 1021 devices on one string. A page
        // that buckets on the renderer must not be able to sort the pool that
        // coarsely, so the commonest string covers well under a fifth.
        val worst = byRenderer.maxByOrNull { it.value }!!
        assertThat(worst.value.toDouble() / total).isLessThan(0.20)
        assertThat(byRenderer.size).isAtLeast(20)

        // The vendor split has to stay mixed as well; one vendor taking the
        // whole catalogue would be as coarse as one renderer taking it.
        val byVendor = Devices.all.groupingBy { it.gpuVendor }.eachCount()
        assertThat(byVendor).hasSize(4)
        for (count in byVendor.values) {
            assertThat(count.toDouble() / total).isLessThan(0.60)
        }
    }

    @Test
    fun `user agents are untouched and agree with the device they describe`() {
        // Pinned so a later edit to the GPU fields cannot quietly rewrite the
        // identity a page actually sees.
        assertThat(Devices.find("google-pixel-9")!!.userAgent).isEqualTo(
            "Mozilla/5.0 (Linux; Android 14; Pixel 9 Build/UP1A.231005.007) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36"
        )
        assertThat(Devices.find("samsung-sm-s928b")!!.userAgent).isEqualTo(
            "Mozilla/5.0 (Linux; Android 14; SM-S928B Build/UP1A.231005.007) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36"
        )
        assertThat(Devices.find("infinix-infinix-x6836")!!.userAgent).isEqualTo(
            "Mozilla/5.0 (Linux; Android 14; Infinix X6836 Build/UP1A.231005.007) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36"
        )
        // A tablet's UA carries no "Mobile"; a phone's does.
        assertThat(Devices.find("google-pixel-tablet")!!.userAgent).isEqualTo(
            "Mozilla/5.0 (Linux; Android 13; Pixel Tablet Build/TP1A.220624.014) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Safari/537.36"
        )

        for (device in Devices.all) {
            assertThat(device.userAgent).contains("Android ${device.androidVersion}")
            assertThat(device.userAgent).contains("${device.code} Build/${device.buildId}")
            assertThat(device.userAgent).contains("Chrome/${device.chromeVersion}")
            assertThat(device.userAgent.contains(" Mobile Safari/537.36"))
                .isEqualTo(device.formFactor != "tablet")
        }
    }
}
