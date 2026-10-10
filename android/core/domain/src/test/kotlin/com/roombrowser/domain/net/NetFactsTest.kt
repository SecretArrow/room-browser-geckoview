package com.roombrowser.domain.net

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.model.Devices
import com.roombrowser.domain.model.ProfileSettings
import com.roombrowser.domain.model.UaMode
import com.roombrowser.domain.proxy.ProxyMode
import com.roombrowser.domain.proxy.ProxyScheme
import com.roombrowser.domain.proxy.ProxyScope
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

    @Test
    fun the_finder_being_off_is_reported_before_the_profile_is_consulted() {
        val summary = NetFacts.proxySummary(
            finderEnabled = false,
            mode = ProxyMode.MANUAL,
            host = "10.0.0.9",
            port = 8080,
            scheme = ProxyScheme.HTTP,
            scopes = setOf(ProxyScope.PAGES)
        )
        assertThat(summary).contains("off")
        assertThat(summary).doesNotContain("10.0.0.9")
    }

    @Test
    fun a_profile_that_opts_out_is_direct() {
        val summary = NetFacts.proxySummary(
            finderEnabled = true,
            mode = ProxyMode.OFF,
            host = null,
            port = 0,
            scheme = ProxyScheme.HTTP,
            scopes = setOf(ProxyScope.PAGES)
        )
        assertThat(summary).isEqualTo("Direct - this profile opts out")
    }

    @Test
    fun a_pinned_endpoint_is_named_with_the_scopes_it_carries() {
        val summary = NetFacts.proxySummary(
            finderEnabled = true,
            mode = ProxyMode.MANUAL,
            host = "10.0.0.9",
            port = 8080,
            scheme = ProxyScheme.SOCKS5,
            scopes = setOf(ProxyScope.PAGES, ProxyScope.WALLET)
        )
        assertThat(summary).isEqualTo("socks5://10.0.0.9:8080, for pages, wallet")
    }

    /** A MANUAL profile with no usable endpoint sends nothing anywhere. */
    @Test
    fun a_manual_profile_without_a_usable_endpoint_says_nothing_is_routed() {
        val cases = listOf(
            Triple("  ", 0, "blank host"),
            Triple("10.0.0.9", 0, "no port"),
            Triple("10.0.0.9", 70_000, "port out of range")
        )
        cases.forEach { (host, port, why) ->
            val summary = NetFacts.proxySummary(
                finderEnabled = true,
                mode = ProxyMode.MANUAL,
                host = host,
                port = port,
                scheme = ProxyScheme.HTTP,
                scopes = setOf(ProxyScope.PAGES)
            )
            assertThat(summary).named(why).contains("nothing is routed")
        }
    }

    @Test
    fun auto_reports_that_the_pick_happens_at_every_open() {
        val summary = NetFacts.proxySummary(
            finderEnabled = true,
            mode = ProxyMode.AUTO,
            host = null,
            port = 0,
            scheme = ProxyScheme.HTTP,
            scopes = ProxyScope.DEFAULT
        )
        assertThat(summary).contains("every open")
        assertThat(summary).contains("pages")
    }

    @Test
    fun an_empty_scope_set_routes_nothing() {
        val summary = NetFacts.proxySummary(
            finderEnabled = true,
            mode = ProxyMode.AUTO,
            host = null,
            port = 0,
            scheme = ProxyScheme.HTTP,
            scopes = emptySet()
        )
        assertThat(summary).contains("no traffic")
    }

    @Test
    fun scopes_are_listed_in_a_stable_order() {
        assertThat(NetFacts.scopeList(setOf(ProxyScope.WALLET, ProxyScope.PAGES, ProxyScope.AGENT)))
            .isEqualTo("pages, AI agent, wallet")
        assertThat(NetFacts.scopeList(emptySet())).isEmpty()
    }
}
