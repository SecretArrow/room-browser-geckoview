package com.roombrowser.domain.net

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.model.Devices
import com.roombrowser.domain.model.ProfileSettings
import com.roombrowser.domain.model.UaMode
import org.junit.Test

class NetFactsTest {

    @Test
    fun a_default_profile_reports_the_engines_own_identity() {
        val settings = ProfileSettings()
        assertThat(NetFacts.uaSource(settings)).isEqualTo(UaSource.ENGINE_DEFAULT)
        assertThat(NetFacts.uaSourceLabel(UaSource.ENGINE_DEFAULT)).isEqualTo("Engine default")
    }

    @Test
    fun a_known_preset_reports_a_preset() {
        val settings = ProfileSettings()
            .copy(uaMode = UaMode.PRESET, uaPresetId = "chrome_android")
        assertThat(NetFacts.uaSource(settings)).isEqualTo(UaSource.PRESET)
    }

    /**
     * The engine refuses an unknown preset id and falls back to its own UA
     * (UserAgents.effectiveUserAgent), so the label must fall back with it — the
     * whole value of this screen is that it names what is really sent.
     */
    @Test
    fun a_preset_id_no_longer_in_the_catalogue_reports_the_engine_default() {
        val settings = ProfileSettings()
            .copy(uaMode = UaMode.PRESET, uaPresetId = "netscape_4")
        assertThat(NetFacts.uaSource(settings)).isEqualTo(UaSource.ENGINE_DEFAULT)
    }

    @Test
    fun a_blank_custom_string_reports_the_engine_default() {
        val settings = ProfileSettings()
            .copy(uaMode = UaMode.CUSTOM, customUserAgent = "   ")
        assertThat(NetFacts.uaSource(settings)).isEqualTo(UaSource.ENGINE_DEFAULT)
    }

    @Test
    fun a_non_blank_custom_string_reports_custom() {
        val settings = ProfileSettings()
            .copy(uaMode = UaMode.CUSTOM, customUserAgent = "Mozilla/5.0 (thing)")
        assertThat(NetFacts.uaSource(settings)).isEqualTo(UaSource.CUSTOM)
    }

    @Test
    fun an_assigned_device_outranks_a_preset() {
        val settings = ProfileSettings()
            .copy(
                deviceId = Devices.all.first().id,
                uaMode = UaMode.PRESET,
                uaPresetId = "chrome_android"
            )
        assertThat(NetFacts.uaSource(settings)).isEqualTo(UaSource.DEVICE)
    }
}
