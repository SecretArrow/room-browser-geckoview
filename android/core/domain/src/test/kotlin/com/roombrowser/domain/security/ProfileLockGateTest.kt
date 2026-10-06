package com.roombrowser.domain.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The three-way profile-lock gate choice, pinned without a device: device
 * credential first, then the PIN, and the warning banner only on a positive
 * "no PIN" report.
 */
class ProfileLockGateTest {

    @Test
    fun `the device credential wins whenever the device has one`() {
        assertThat(ProfileLockGate.choose(biometricsAvailable = true, pinConfigured = false))
            .isEqualTo(ProfileLockGate.DEVICE_CREDENTIAL)
        assertThat(ProfileLockGate.choose(biometricsAvailable = true, pinConfigured = true))
            .isEqualTo(ProfileLockGate.DEVICE_CREDENTIAL)
        assertThat(ProfileLockGate.choose(biometricsAvailable = true, pinConfigured = null))
            .isEqualTo(ProfileLockGate.DEVICE_CREDENTIAL)
    }

    @Test
    fun `with no device credential a configured pin gates the screen`() {
        assertThat(ProfileLockGate.choose(biometricsAvailable = false, pinConfigured = true))
            .isEqualTo(ProfileLockGate.PIN)
    }

    @Test
    fun `with neither credential the banner leg runs`() {
        assertThat(ProfileLockGate.choose(biometricsAvailable = false, pinConfigured = false))
            .isEqualTo(ProfileLockGate.UNPROTECTED)
    }

    @Test
    fun `an unreadable record fails closed onto the pin pane`() {
        // null is "cannot determine", never "no PIN": only a positive false
        // may open the screen behind the banner.
        assertThat(ProfileLockGate.choose(biometricsAvailable = false, pinConfigured = null))
            .isEqualTo(ProfileLockGate.PIN)
    }
}
