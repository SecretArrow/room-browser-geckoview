package com.roombrowser.domain.model

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.agent.AgentPermissions
import com.roombrowser.domain.agent.AgentTools
import org.junit.Test

class ProfileModelTest {

    @Test
    fun `profile id safe suffix is 32 hex chars`() {
        val id = ProfileId.new()
        assertThat(id.safeSuffix).hasLength(32)
        assertThat(id.safeSuffix).matches("^[0-9a-f]{32}$")
        assertThat(id.safeSuffix).doesNotContain("-")
    }

    @Test
    fun `renaming does not change storage identity`() {
        val id = ProfileId.new()
        val p1 = Profile(id = id, name = "Personal", createdAt = 0)
        val p2 = p1.copy(name = "Family")
        assertThat(p2.id).isEqualTo(p1.id)
        assertThat(p2.id.safeSuffix).isEqualTo(p1.id.safeSuffix)
    }

    @Test
    fun `blank profile id rejected`() {
        var thrown = false
        try { ProfileId("") } catch (e: IllegalArgumentException) { thrown = true }
        assertThat(thrown).isTrue()
    }

    @Test
    fun `retention cutoff math`() {
        val now = 1_000_000_000L
        assertThat(NetworkRetention.ONE_DAY.cutoff(now)).isEqualTo(now - 86_400_000L)
        assertThat(NetworkRetention.SEVEN_DAYS.cutoff(now)).isEqualTo(now - 7 * 86_400_000L)
        assertThat(NetworkRetention.FOREVER.cutoff(now)).isNull()
    }

    @Test
    fun `effective user agent modes`() {
        assertThat(UserAgents.effectiveUserAgent(ProfileSettings())).isNull()
        assertThat(
            UserAgents.effectiveUserAgent(ProfileSettings(uaMode = UaMode.PRESET, uaPresetId = "chrome_android"))
        ).contains("Android")
        // A preset id that no longer exists — e.g. a stored profile that once
        // picked a desktop preset — resolves to null, so the WebView default
        // is used rather than a stale identity.
        assertThat(
            UserAgents.effectiveUserAgent(ProfileSettings(uaMode = UaMode.PRESET, uaPresetId = "chrome_windows"))
        ).isNull()
        assertThat(
            UserAgents.effectiveUserAgent(ProfileSettings(uaMode = UaMode.PRESET, uaPresetId = null))
        ).isNull()
        assertThat(
            UserAgents.effectiveUserAgent(ProfileSettings(uaMode = UaMode.CUSTOM, customUserAgent = "MyUA/1.0"))
        ).isEqualTo("MyUA/1.0")
        assertThat(
            UserAgents.effectiveUserAgent(ProfileSettings(uaMode = UaMode.CUSTOM, customUserAgent = "  "))
        ).isNull()
    }

    @Test
    fun `a device is the single control for identity`() {
        val device = Devices.all.first { it.formFactor == "phone" }
        val withDevice = ProfileSettings(deviceId = device.id)
        assertThat(UserAgents.device(withDevice)).isEqualTo(device)
        assertThat(UserAgents.effectiveUserAgent(withDevice)).isEqualTo(device.userAgent)

        // Even alongside a preset the device wins, because that is what the
        // profile was told to look like; the settings screen is what stops
        // the two from both being set in the first place.
        val both = ProfileSettings(
            deviceId = device.id,
            uaMode = UaMode.PRESET,
            uaPresetId = "firefox_android"
        )
        assertThat(UserAgents.effectiveUserAgent(both)).isEqualTo(device.userAgent)

        // Choosing a UA hands the answer back to the preset.
        val preset = both.withUserAgentPreset("firefox_android")
        assertThat(preset.deviceId).isNull()
        assertThat(UserAgents.effectiveUserAgent(preset)).contains("Firefox")

        val custom = both.withCustomUserAgent("MyUA/1.0")
        assertThat(custom.deviceId).isNull()
        assertThat(UserAgents.effectiveUserAgent(custom)).isEqualTo("MyUA/1.0")
    }

    @Test
    fun `an unknown device id is not an identity`() {
        val settings = ProfileSettings(deviceId = "no-such-device")
        assertThat(UserAgents.device(settings)).isNull()
        assertThat(UserAgents.effectiveUserAgent(settings)).isNull()
    }

    @Test
    fun `device entries describe a real handset`() {
        val byId = Devices.byId
        assertThat(byId).hasSize(Devices.all.size)
        for (d in Devices.all) {
            assertThat(d.year).isAtLeast(2022)
            assertThat(d.androidVersion).isNotEmpty()
            assertThat(d.userAgent).contains(d.code)
            assertThat(d.userAgent).contains("Android ${d.androidVersion}")
            assertThat(d.userAgent).contains("Chrome/${d.chromeVersion}")
            // A tablet's Chrome omits "Mobile"; a phone's does not.
            assertThat(d.userAgent.contains(" Mobile ")).isEqualTo(d.formFactor == "phone")
            // Chromium only ever reports a power of two here.
            assertThat(d.deviceMemoryGb).isAnyOf(1, 2, 4, 8)
            assertThat(d.hardwareConcurrency).isAtLeast(2)
        }
        assertThat(Devices.find(null)).isNull()
        assertThat(Devices.find("no-such-device")).isNull()
    }

    @Test
    fun `random respects the devices already taken`() {
        val taken = Devices.all.take(Devices.all.size - 1).map { it.id }.toSet()
        val last = Devices.all.last()
        repeat(5) { assertThat(Devices.random(taken).id).isEqualTo(last.id) }
        // An exhausted catalogue falls back to the whole pool rather than
        // failing: one shared device beats no device at all.
        val all = Devices.all.map { it.id }.toSet()
        repeat(5) { assertThat(Devices.random(all).id).isIn(all) }
    }

    @Test
    fun `compatibility defaults`() {
        val s = ProfileSettings()
        // Annoyance shields — including the malicious-site block — are OFF by
        // default (opt-in via Settings); the features stay, only the default
        // changed, and profiles that stored an explicit value keep it.
        assertThat(s.blockAds).isFalse()
        assertThat(s.blockTrackers).isFalse()
        assertThat(s.blockCrossSiteTrackers).isFalse()
        assertThat(s.blockPopups).isFalse()
        assertThat(s.blockMalicious).isFalse()
        // Compatibility behavior stays on.
        assertThat(s.blockThirdPartyCookies).isFalse()
        assertThat(s.javascriptEnabled).isTrue()
        assertThat(s.httpsUpgrade).isTrue()
    }

    @Test
    fun `a screen size is claimed only when one was asked for`() {
        assertThat(ProfileSettings().screenSizeMode).isEqualTo(ScreenSizeMode.REAL)
        assertThat(ProfileSettings().claimedScreen()).isNull()
        assertThat(
            ProfileSettings(
                screenSizeMode = ScreenSizeMode.MANUAL,
                screenWidthPx = 393,
                screenHeightPx = 852
            ).claimedScreen()
        ).isEqualTo(ClaimedScreen(393, 852))
        // Half a size is no size: a profile mid-edit reports the truth rather
        // than a screen the user has not finished describing.
        assertThat(
            ProfileSettings(
                screenSizeMode = ScreenSizeMode.MANUAL,
                screenWidthPx = 393,
                screenHeightPx = 0
            ).claimedScreen()
        ).isNull()
        // And so is an impossible one — a corrupt entry falls back to the truth
        // rather than telling a page about a display that cannot exist.
        assertThat(
            ProfileSettings(
                screenSizeMode = ScreenSizeMode.MANUAL,
                screenWidthPx = ClaimedScreen.MAX_PX + 1,
                screenHeightPx = 852
            ).claimedScreen()
        ).isNull()
        assertThat(
            ProfileSettings(
                screenSizeMode = ScreenSizeMode.MANUAL,
                screenWidthPx = 393,
                screenHeightPx = ClaimedScreen.MIN_PX - 1
            ).claimedScreen()
        ).isNull()
        // Left on REAL the stored numbers are inert, so switching the setting
        // off and on again cannot resurrect a size the user has left behind.
        assertThat(
            ProfileSettings(
                screenSizeMode = ScreenSizeMode.REAL,
                screenWidthPx = 393,
                screenHeightPx = 852
            ).claimedScreen()
        ).isNull()
    }

    @Test
    fun `a claimed screen agrees with the orientation it implies`() {
        assertThat(ClaimedScreen(393, 852).isLandscape).isFalse()
        assertThat(ClaimedScreen(852, 393).isLandscape).isTrue()
        // A square claim is not landscape; the only shape that is, is one wider
        // than it is tall.
        assertThat(ClaimedScreen(600, 600).isLandscape).isFalse()
    }

    @Test
    fun `every preset has a distinct id and a non-blank value but webview`() {
        assertThat(UserAgents.all.map { it.id }.toSet()).hasSize(UserAgents.all.size)
        for (p in UserAgents.all) {
            if (p.id == "webview") {
                assertThat(p.value).isEmpty()
            } else {
                assertThat(p.value).isNotEmpty()
            }
            assertThat(p.label).isNotEmpty()
        }
        // The catalogue is Android/mobile only; the desktop UA lives in
        // desktopModeUserAgent (covered by UserAgentsTest).
        assertThat(UserAgents.androidPresets).isEqualTo(UserAgents.all)
        assertThat(UserAgents.byId("no-such-preset")).isNull()
    }

    @Test
    fun `search url encoding`() {
        val url = SearchEngines.buildSearchUrl("duckduckgo", "a b&c")
        assertThat(url).isEqualTo("https://duckduckgo.com/?q=a+b%26c")
    }

    @Test
    fun `search engine fallback for unknown id`() {
        assertThat(SearchEngines.byId("nope").id).isEqualTo("duckduckgo")
    }

    @Test
    fun `the agent vision switch is off on a profile that predates it`() {
        // A field, not a withheld permission: that set is a deny-list, where a
        // permission added later is granted by default and so cannot express
        // "off". A defaulted field is off for a profile written before the field
        // existed, with no migration.
        assertThat(ProfileSettings().agentVisionEnabled).isFalse()
        // And the tool carries no permission, so this switch is its only gate.
        assertThat(AgentPermissions.required(AgentTools.SEE_PAGE)).isEmpty()
    }
}
