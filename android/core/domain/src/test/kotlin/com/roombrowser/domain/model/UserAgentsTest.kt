package com.roombrowser.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the Android-only shape of the preset catalogue after the desktop
 * presets were removed (desktop identity now lives solely in
 * [UserAgents.desktopModeUserAgent] for the per-site Desktop-mode toggle),
 * and pins the other half of the contract: every preset is a string a REAL
 * Android browser sends. A malformed UA is more fingerprintable than the
 * WebView default, so a typo here is a privacy bug, not a cosmetic one.
 *
 * The assertions below are deliberately written against the strings the
 * browsers actually emit, so a "clean-up" that invents a token, doubles one,
 * or moves one fails here rather than shipping.
 */
class UserAgentsTest {

    private val removedDesktopIds = listOf(
        "chrome_windows", "chrome_macos", "chrome_linux",
        "firefox_windows", "firefox_macos", "firefox_linux",
        "edge_windows", "safari_macos"
    )

    private val chromeToken = Regex("Chrome/(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)")

    /** Presets whose identity IS Chrome, so their Chrome token is the identity. */
    private val chromeIdentityIds = listOf(
        "chrome_android", "chrome_android_tablet", "chrome_beta_android",
        "brave_android", "duckduckgo_android", "edge_android"
    )

    private val tabletIds = listOf("chrome_android_tablet", "firefox_android_tablet")

    private val phoneIds = listOf(
        "chrome_android", "chrome_beta_android", "edge_android", "samsung_android",
        "opera_android", "brave_android", "duckduckgo_android", "vivaldi_android"
    )

    private fun ua(id: String): String = UserAgents.byId(id)!!.value

    @Test
    fun `every preset is an Android or mobile identity`() {
        for (p in UserAgents.all) {
            // The webview preset is the empty "let WebView decide" default;
            // every other preset claims an Android platform.
            if (p.id == "webview") {
                assertThat(p.value).isEmpty()
            } else {
                assertThat(p.value).startsWith("Mozilla/5.0 (")
                assertThat(p.value).contains("Android")
            }
            // No desktop/other-platform token may appear in any preset.
            for (token in listOf("Windows NT", "Macintosh", "X11;", "iPhone", "iPad")) {
                assertThat(p.value).doesNotContain(token)
            }
            // No preset is a desktop preset anymore, by construction.
            assertThat(p.isDesktop).isFalse()
        }
    }

    @Test
    fun `preset ids are unique and the catalogue is the shipped one`() {
        val ids = UserAgents.all.map { it.id }
        assertThat(ids.toSet()).hasSize(ids.size)
        assertThat(ids).containsExactly(
            "chrome_android",
            "chrome_android_tablet",
            "chrome_beta_android",
            "firefox_android",
            "firefox_android_tablet",
            "edge_android",
            "samsung_android",
            "opera_android",
            "brave_android",
            "duckduckgo_android",
            "vivaldi_android",
            "webview"
        )
    }

    @Test
    fun `vendor tokens identify the new presets`() {
        // Chrome's tablet form factor is a real device model code, never the
        // word "Tablet" — that token is Firefox's convention (asserted below,
        // where it does belong).
        assertThat(ua("chrome_android_tablet")).contains("Android 14; SM-X710)")
        assertThat(ua("chrome_android_tablet")).doesNotContain("Tablet")
        assertThat(ua("firefox_android_tablet")).contains("Tablet")
        assertThat(ua("opera_android")).contains("OPR/99")
        // Brave's mobile browser is Chromium-based and ships the plain Chrome
        // mobile UA on purpose, so the two values are identical.
        assertThat(ua("brave_android")).isEqualTo(ua("chrome_android"))
        assertThat(ua("duckduckgo_android")).contains("DuckDuckGo/5")
        assertThat(ua("vivaldi_android")).contains("Vivaldi/8.2")
        // No preset invents a vendor token: "ChromeBetA" does not exist in any
        // Chrome channel's UA, and neither does anything else spelled like it.
        for (p in UserAgents.all) {
            assertThat(p.value).doesNotContain("ChromeBetA")
        }
    }

    @Test
    fun `the tablet presets drop Mobile and the phone presets keep it`() {
        // Chrome only announces " Mobile" on handsets; a tablet UA claiming it
        // contradicts the form factor the shim reports. Same rule [Devices]
        // records for every handset it lists.
        for (id in tabletIds) {
            assertThat(ua(id)).doesNotContain("Mobile")
        }
        for (id in phoneIds) {
            assertThat(ua(id)).contains("Mobile")
        }
    }

    @Test
    fun `chrome presets speak the Chrome build the device catalogue ships`() {
        // The point of the version: a profile on a preset and a profile on a
        // device must not tell the same site two different things about the
        // same app. Every stable Chrome-identity preset therefore carries the
        // real build the catalogue's handsets report, in the same full 4-part
        // form - never the `<major>.0.0.0` placeholder, whose zero build and
        // patch are what made presets and devices disagree. The Beta row is
        // the deliberate exception: devices run stable, so its newer real
        // Beta-channel build cannot also be in the catalogue; the beta test
        // below pins it.
        val shipped = Devices.all.map { it.chromeVersion }.toSet()
        for (id in chromeIdentityIds) {
            val match = chromeToken.find(ua(id))
            assertThat(match).isNotNull()
            val (major, minor, build, patch) = match!!.groupValues.drop(1)
            assertThat(minor).isEqualTo("0") // Chrome's own Android version scheme
            assertThat(build).isNotEqualTo("0")
            assertThat(patch).isNotEqualTo("0")
            if (id != "chrome_beta_android") {
                assertThat(shipped).contains("$major.$minor.$build.$patch")
            }
        }
    }

    @Test
    fun `every chromium token claims a current generation and never a stale one`() {
        // The product floor: no User-Agent this app sends may claim a Chromium
        // generation below 153, and the Firefox presets must carry the current
        // Firefox release. A preset that goes stale is caught here, at unit
        // time, instead of shipping an identity years behind the web.
        for (p in UserAgents.all) {
            val major = chromeToken.find(p.value)?.groupValues?.get(1)?.toIntOrNull()
                ?: continue // the webview preset carries no Chromium token
            assertThat(major).isAtLeast(153)
        }
        for (id in listOf("firefox_android", "firefox_android_tablet")) {
            val major = Regex("rv:([0-9]+)").find(ua(id))!!.groupValues[1].toInt()
            assertThat(major).isAtLeast(158)
        }
    }

    @Test
    fun `chrome beta is the plain chrome UA at a different version`() {
        // No Chrome channel puts a token in the UA — the channel travels in
        // the client hints' full version list — so the Beta row can only earn
        // its place by the version, and it must be a newer one.
        val beta = ua("chrome_beta_android")
        val stable = ua("chrome_android")
        assertThat(beta).doesNotContain("Beta")
        assertThat(beta).isNotEqualTo(stable)
        val betaParts = chromeToken.find(beta)!!.groupValues.drop(1)
        val stableParts = chromeToken.find(stable)!!.groupValues.drop(1)
        assertThat(betaParts[0].toInt()).isGreaterThan(stableParts[0].toInt())
        // Everything except the version is the ordinary Chrome mobile UA.
        assertThat(beta).isEqualTo(stable.replace(stableParts.joinToString("."), betaParts.joinToString(".")))
    }

    @Test
    fun `firefox presets carry the Android Gecko form`() {
        for (id in listOf("firefox_android", "firefox_android_tablet")) {
            val value = ua(id)
            // Firefox for Android sends ONE Gecko token of the form
            // Gecko/<version>; Gecko/20100101 is the desktop build stamp and
            // has never appeared on Android.
            assertThat(Regex("Gecko/").findAll(value).count()).isEqualTo(1)
            assertThat(value).doesNotContain("20100101")
            // rv: and Firefox/ must agree, or the string is internally wrong.
            val rv = Regex("rv:([0-9.]+)").find(value)!!.groupValues[1]
            assertThat(Regex("Firefox/([0-9.]+)").find(value)!!.groupValues[1]).isEqualTo(rv)
        }
    }

    @Test
    fun `duckduckgo splices its token before Safari`() {
        // DuckDuckGo's UA builder emits its application component right after
        // " Mobile", before the Safari component. A token appended after
        // Safari is a string DuckDuckGo has never sent.
        val value = ua("duckduckgo_android")
        assertThat(value.indexOf("DuckDuckGo/5")).isLessThan(value.indexOf("Safari/537.36"))
        assertThat(value.indexOf("DuckDuckGo/5")).isGreaterThan(value.indexOf("Mobile"))
    }

    @Test
    fun `removed desktop preset ids resolve to null`() {
        for (id in removedDesktopIds) {
            assertThat(UserAgents.byId(id)).isNull()
        }
        assertThat(UserAgents.byId("no-such-preset")).isNull()
        // And an unknown id in PRESET mode falls back to the WebView default.
        assertThat(
            UserAgents.effectiveUserAgent(
                ProfileSettings(uaMode = UaMode.PRESET, uaPresetId = "chrome_windows")
            )
        ).isNull()
    }

    @Test
    fun `the desktop mode UA is not a preset`() {
        assertThat(UserAgents.desktopModeUserAgent).contains("Windows NT")
        assertThat(UserAgents.desktopModeUserAgent).contains("Chrome/156")
        // Not offered as a preset by id...
        assertThat(UserAgents.all.map { it.id }).doesNotContain("chrome_windows")
        // ...and its value is not any preset's value.
        assertThat(UserAgents.all.map { it.value }).doesNotContain(UserAgents.desktopModeUserAgent)
        // The mobile subset is the whole catalogue while every preset is Android.
        assertThat(UserAgents.androidPresets).isEqualTo(UserAgents.all)
    }

    // ----------------------------------------------------- neutralising the engine

    /** A stock WebView UA, in the shape a real device reports it. */
    private val engineUa =
        "Mozilla/5.0 (Linux; Android 14; SM-S918B Build/UP1A.231005.007; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/156.0.8078.48 " +
            "Mobile Safari/537.36"

    @Test
    fun `the neutralised engine UA stops announcing itself as a WebView`() {
        val cleaned = UserAgents.webViewNeutralUserAgent(engineUa)!!

        // The two tokens sites branch on: `wv` in the platform comment and the
        // `Version/4.0` product. Either one alone gets a page the substitute
        // player, which is what stalls video sites.
        assertThat(cleaned).doesNotContain("wv")
        assertThat(cleaned).doesNotContain("Version/4.0")
        // No stray double space where the product was removed.
        assertThat(cleaned).doesNotContain("  ")
        assertThat(cleaned).isEqualTo(
            "Mozilla/5.0 (Linux; Android 14; SM-S918B Build/UP1A.231005.007) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 " +
                "Mobile Safari/537.36"
        )
    }

    @Test
    fun `neutralising keeps the engine's real Chrome version`() {
        val cleaned = UserAgents.webViewNeutralUserAgent(engineUa)!!

        // Substituting a fixed "Chrome 156" would go stale within months and
        // would fight the per-profile identity system. Only the two WebView
        // markers go.
        assertThat(cleaned).contains("Chrome/156.0.8078.48")
        assertThat(cleaned).contains("Android 14")
        assertThat(cleaned).contains("SM-S918B")
    }

    @Test
    fun `a UA without WebView markers is returned unchanged`() {
        val alreadyClean = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36"

        assertThat(UserAgents.webViewNeutralUserAgent(alreadyClean)).isEqualTo(alreadyClean)
        assertThat(UserAgents.webViewNeutralUserAgent(UserAgents.desktopModeUserAgent))
            .isEqualTo(UserAgents.desktopModeUserAgent)
    }

    @Test
    fun `a missing or blank engine UA passes through rather than inventing one`() {
        assertThat(UserAgents.webViewNeutralUserAgent(null)).isNull()
        assertThat(UserAgents.webViewNeutralUserAgent("")).isEmpty()
        assertThat(UserAgents.webViewNeutralUserAgent("   ")).isEqualTo("   ")
    }

    @Test
    fun `a UA that is nothing but WebView markers is left as the engine sent it`() {
        // Degenerate, but the alternative is handing the engine an empty
        // userAgentString, which WebView treats as "send nothing".
        val weird = "wv"
        assertThat(UserAgents.webViewNeutralUserAgent(weird)).isEqualTo(weird)
    }

    @Test
    fun `DEFAULT mode resolves to the neutralised engine string, not the stock one`() {
        val settings = ProfileSettings(uaMode = UaMode.DEFAULT)

        // effectiveUserAgent returning null is the signal "the engine decides";
        // it is the caller (ProfileEngine) that neutralises. This test pins the
        // contract the caller depends on.
        assertThat(UserAgents.effectiveUserAgent(settings)).isNull()
        assertThat(UserAgents.webViewNeutralUserAgent(engineUa)).isNotNull()
    }
}
