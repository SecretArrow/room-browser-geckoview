package com.roombrowser.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The derivation contract: stable per seed, distinct across seeds, and a null
 * seed unchanged from before seeds existed.
 */
class FingerprintProfileTest {

    private val device = Devices.all.first { it.formFactor == "phone" }

    @Test
    fun `two different seeds differ on every seeded surface`() {
        // Pinned: a known pair that differs everywhere, so a label or pool
        // change that made two seeds collide fails here.
        val a = FingerprintProfile.from("seed-1", device)
        val b = FingerprintProfile.from("seed-8", device)
        assertThat(a.platform).isNotEqualTo(b.platform)
        assertThat(a.colorDepth).isNotEqualTo(b.colorDepth)
        assertThat(a.pixelDepth).isNotEqualTo(b.pixelDepth)
        assertThat(a.maxTouchPoints).isNotEqualTo(b.maxTouchPoints)
        assertThat(a.devicePixelRatio).isNotEqualTo(b.devicePixelRatio)
        assertThat(a.plugins).isNotEqualTo(b.plugins)
        assertThat(a.mimeTypes).isNotEqualTo(b.mimeTypes)
        assertThat(a.plugins).isNotEmpty()
        assertThat(b.plugins).isNotEmpty()
    }

    @Test
    fun `the same seed yields identical values every time`() {
        val first = FingerprintProfile.from("seed-1", device)
        assertThat(FingerprintProfile.from("seed-1", device)).isEqualTo(first)
        // A device-less profile is just as stable — the case this exists for.
        assertThat(FingerprintProfile.from("seed-1", null))
            .isEqualTo(FingerprintProfile.from("seed-1", null))
        assertThat(FingerprintProfile.from("seed-1", null).isSeeded).isTrue()
    }

    @Test
    fun `a null seed reproduces the unseeded behaviour exactly`() {
        val unseeded = FingerprintProfile.from(null, device)
        assertThat(unseeded).isEqualTo(FingerprintProfile.legacy())
        // An upgraded profile keeps the platform constant and installs nothing.
        assertThat(unseeded.platform).isEqualTo(FingerprintProfile.LEGACY_PLATFORM)
        assertThat(unseeded.colorDepth).isNull()
        assertThat(unseeded.pixelDepth).isNull()
        assertThat(unseeded.maxTouchPoints).isNull()
        assertThat(unseeded.devicePixelRatio).isNull()
        assertThat(unseeded.plugins).isEmpty()
        assertThat(unseeded.mimeTypes).isEmpty()
        assertThat(unseeded.isSeeded).isFalse()
        // A blank seed cannot key an HMAC.
        assertThat(FingerprintProfile.from("   ", device)).isEqualTo(FingerprintProfile.legacy())
        assertThat(FingerprintProfile.from("", device)).isEqualTo(FingerprintProfile.legacy())
    }

    @Test
    fun `the derived values stay within the pools a real handset reports`() {
        val allowedPlatforms = setOf("Linux armv8l", "Linux armv7l", "Linux aarch64")
        for (seed in listOf("seed-1", "seed-8", "seed-13", "another-seed")) {
            val profile = FingerprintProfile.from(seed, device)
            assertThat(profile.platform).isIn(allowedPlatforms)
            assertThat(profile.colorDepth).isAnyOf(16, 24)
            assertThat(profile.pixelDepth).isEqualTo(profile.colorDepth)
            assertThat(profile.maxTouchPoints).isAnyOf(5, 10)
            assertThat(profile.devicePixelRatio).isAnyOf(1.5, 2.0, 2.625, 3.0, 3.5, 4.0)
            assertThat(profile.plugins.size).isAnyOf(3, 4, 5)
            assertThat(profile.mimeTypes.size).isAnyOf(1, 2)
            assertThat(profile.plugins.map { it.name }).isNotEmpty()
            assertThat(profile.mimeTypes.map { it.type }).isNotEmpty()
        }
    }
}
