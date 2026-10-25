package com.roombrowser.domain.model

/**
 * User-Agent presets.
 *
 * Every preset is an Android/mobile identity: the browser runs on a phone, so
 * the curated list offers mobile browsers only, and a preset says which *mobile
 * browser* a profile claims to be. A [Device] is the richer sibling — it says
 * which handset the profile claims to be and also decides the platform
 * version, build id, memory, core count and GPU strings the profile reports —
 * so when both are configured the device wins, and the settings screen clears
 * one when the other is chosen.
 *
 * Desktop identities are deliberately NOT presets. Claiming a desktop browser
 * on a phone is a rendering-mode choice, not an identity: it goes with a wide
 * viewport and no overview zoom, and it is per site, not per profile. That is
 * what the per-site "Desktop mode" toggle is for, and the UA it applies lives
 * in [UserAgents.desktopModeUserAgent] rather than in [UserAgents.all].
 *
 * What this changes is the identity a site is told, not the machine. Screen
 * size, viewport size, devicePixelRatio and the layout that follows from them
 * always report the real device, because a page laid out for a viewport the
 * phone does not have renders wrong. See SECURITY.md for the full list of
 * what is and is not presented.
 *
 * VERSIONS. A preset is only worth having if it is a string some real browser
 * actually sends: a malformed UA is *more* fingerprintable than the WebView
 * default, which is the opposite of the point. So every token here is a real
 * one — the presets whose identity is Chrome carry a real 4-part
 * Chrome-for-Android build, the Firefox presets carry the single Gecko token
 * Firefox for Android sends, and no preset invents a vendor token no browser
 * emits. The Chromium generation those Chrome-identity presets claim is the
 * one the device catalogue ships (see [Devices]), so a profile on a preset and
 * a profile on a device do not tell the same site two different stories about
 * the same app. A vendor preset is the deliberate exception: its own release
 * token is kept real, while the Chromium build underneath it follows the
 * catalogue's current stable - the product requires that no preset claim a
 * Chromium generation below 153, refreshed with each catalogue refresh
 * (this one: Chrome 156.0.8078.48 / Firefox 158, October 2026).
 */
data class UserAgentPreset(
    val id: String,
    val label: String,
    /**
     * Always false now that the catalogue is Android-only. Kept so settings
     * UIs that tag desktop presets keep compiling; the only desktop UA left
     * is [UserAgents.desktopModeUserAgent], which is not a preset.
     */
    val isDesktop: Boolean,
    val value: String
)

object UserAgents {

    private val CHROME_ANDROID = UserAgentPreset(
        id = "chrome_android",
        label = "Chrome Android",
        isDesktop = false,
        // A real Chrome-for-Android build, not the `<major>.0.0.0` placeholder:
        // the three-zero form is a reduced-UA artefact, and a preset pinned to
        // it while every device in the catalogue reports a full build is a
        // mismatch between two profiles of the same app. 156.0.8078.48 is the
        // current stable build, the one build the refreshed catalogue ships.
        value = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36"
    )
    private val CHROME_ANDROID_TABLET = UserAgentPreset(
        id = "chrome_android_tablet",
        label = "Chrome Android Tablet",
        isDesktop = false,
        // Chrome puts a real device MODEL CODE in the platform slot — the word
        // "Tablet" there is Firefox's convention, not Chrome's (see
        // FIREFOX_ANDROID_TABLET below). SM-X710 is a Galaxy Tab S9, a model
        // Google Play really ships to. Form factor is expressed the way Chrome
        // expresses it: a tablet UA carries no " Mobile" token, the same rule
        // [Devices] records on every handset it lists.
        value = "Mozilla/5.0 (Linux; Android 14; SM-X710) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Safari/537.36"
    )
    private val CHROME_BETA_ANDROID = UserAgentPreset(
        id = "chrome_beta_android",
        label = "Chrome Beta Android",
        isDesktop = false,
        // Chrome Beta for Android sends the ORDINARY Chrome mobile UA: the
        // channel is a client hint (full version list), never a UA token, so
        // there is nothing extra to append. The only honest way this row
        // differs from CHROME_ANDROID is the version, and it must be a real
        // one - the current Beta-channel Chrome-for-Android build (157.0.8103.14,
        // October 2026), a later major than the stable presets carry, in the
        // same full 4-part form. Devices run stable, so this build is the one
        // preset token that is deliberately not in the catalogue.
        value = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.8103.14 Mobile Safari/537.36"
    )
    private val FIREFOX_ANDROID = UserAgentPreset(
        id = "firefox_android",
        label = "Firefox Android",
        isDesktop = false,
        // Firefox for Android sends exactly ONE Gecko token of the form
        // Gecko/<version>. "Gecko/20100101" is the desktop build stamp and
        // never appears on Android, so a preset carrying both tokens is a
        // string no browser has ever sent. rv: must equal the Firefox version.
        value = "Mozilla/5.0 (Android 14; Mobile; rv:158.0) Gecko/158.0 Firefox/158.0"
    )
    private val FIREFOX_ANDROID_TABLET = UserAgentPreset(
        id = "firefox_android_tablet",
        label = "Firefox Android Tablet",
        isDesktop = false,
        // Firefox is the one Android browser that DOES put "Tablet" in the
        // platform slot, and its tablet UA drops "Mobile" — same single Gecko
        // token as the phone preset.
        value = "Mozilla/5.0 (Android 14; Tablet; rv:158.0) Gecko/158.0 Firefox/158.0"
    )
    private val EDGE_ANDROID = UserAgentPreset(
        id = "edge_android",
        label = "Edge Android",
        isDesktop = false,
        // Edge for Android sends a real build of its own (EdgA never uses the
        // `<major>.0.0.0` placeholder) on top of the Chromium build it bundles,
        // so the Chrome token tracks the same Chromium generation as the other
        // presets while EdgA states the Edge release: 156.0.4314.42 is the current
        // Edge 156 branch build, exactly the pair a current Edge for Android
        // really sends (the EdgA token carries Edge's own branch, not Chrome's).
        value = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36 EdgA/156.0.4314.42"
    )
    private val SAMSUNG_ANDROID = UserAgentPreset(
        id = "samsung_android",
        label = "Samsung Internet",
        isDesktop = false,
        // SamsungBrowser/29.0 is the vendor's current release; its Chromium
        // token is the reduced three-zero form Samsung really sends, pinned to
        // the catalogue's current stable so no preset in this file claims a
        // Chromium generation below 153.
        value = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/29.0 Chrome/156.0.0.0 Mobile Safari/537.36"
    )
    private val OPERA_ANDROID = UserAgentPreset(
        id = "opera_android",
        label = "Opera Android",
        isDesktop = false,
        // Opera states its own release on top of the Chromium build it ships;
        // OPR/99.6.2254.2363 is the newest Opera-for-Android build family observed in
        // the wild (October 2026), and its Chromium token follows the
        // catalogue's current stable under the same 153 floor as the rest.
        value = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Mobile Safari/537.36 OPR/99.6.2254.2363"
    )
    private val BRAVE_ANDROID = UserAgentPreset(
        id = "brave_android",
        label = "Brave Android",
        isDesktop = false,
        // Brave's mobile browser is Chromium-based and intentionally reports
        // the plain Chrome mobile UA — an extra token would let sites
        // fingerprint it, so there is nothing to add.
        value = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36"
    )
    private val DUCKDUCKGO_ANDROID = UserAgentPreset(
        id = "duckduckgo_android",
        label = "DuckDuckGo Android",
        isDesktop = false,
        // DuckDuckGo splices its application component in immediately after
        // the " Mobile" token and BEFORE Safari/537.36 — that is the order its
        // own UA builder emits (app component, then the browser's Safari
        // component). Appending it after Safari is a string DuckDuckGo has
        // never sent, and a wrong token order is itself a fingerprint.
        value = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile DuckDuckGo/5 Safari/537.36"
    )
    private val VIVALDI_ANDROID = UserAgentPreset(
        id = "vivaldi_android",
        label = "Vivaldi Android",
        isDesktop = false,
        // Vivaldi states its own release on top of the Chromium build it ships:
        // Vivaldi/8.2 is the current Android release. Its upstream base
        // tracks Chromium's ESR rather than stable, so the Chromium token
        // follows the catalogue's current stable under the 153 floor instead
        // of the ESR number.
        value = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Mobile Safari/537.36 Vivaldi/8.2"
    )
    private val WEBVIEW = UserAgentPreset(
        id = "webview",
        label = "Android WebView (default)",
        isDesktop = false,
        value = ""
    )

    /** The curated preset catalogue — Android/mobile identities only. */
    val all: List<UserAgentPreset> = listOf(
        CHROME_ANDROID, CHROME_ANDROID_TABLET, CHROME_BETA_ANDROID,
        FIREFOX_ANDROID, FIREFOX_ANDROID_TABLET, EDGE_ANDROID,
        SAMSUNG_ANDROID, OPERA_ANDROID, BRAVE_ANDROID,
        DUCKDUCKGO_ANDROID, VIVALDI_ANDROID, WEBVIEW
    )

    /**
     * The mobile subset of [all]. Every preset is mobile now, so this is the
     * whole catalogue; the property stays because settings UIs ask for the
     * mobile subset explicitly and should not silently grow desktop entries
     * if presets are ever widened again.
     */
    val androidPresets: List<UserAgentPreset> = all

    /**
     * The UA the per-site "Desktop mode" toggle applies.
     *
     * Not a preset and never part of [all]: desktop mode is a rendering-mode
     * feature (wide viewport, no overview zoom) toggled per site, not a
     * profile identity to choose in settings. Chrome on Windows is the least
     * remarkable desktop UA a site sees, which is the point.
     */
    const val desktopModeUserAgent: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36"

    fun byId(id: String): UserAgentPreset? = all.firstOrNull { it.id == id }

    /**
     * The device this profile presents itself as, or null when it was never
     * assigned one (a profile created before devices existed, or one whose
     * settings were imported).
     */
    fun device(settings: ProfileSettings): Device? = Devices.find(settings.deviceId)

    /**
     * Resolve the effective UA for a profile. Null means "use WebView default".
     *
     * A device, when one is assigned, is the single control: it supplies the
     * UA, so the preset and custom fields are not consulted. In PRESET mode an
     * unknown preset id — e.g. a stored profile that once picked a preset that
     * has since been removed from the catalogue — also resolves to null, so
     * the WebView default is used rather than a stale identity.
     */
    fun effectiveUserAgent(settings: ProfileSettings): String? {
        device(settings)?.let { return it.userAgent }
        return when (settings.uaMode) {
            UaMode.DEFAULT -> null
            UaMode.PRESET -> settings.uaPresetId?.let { byId(it)?.value?.ifEmpty { null } }
            UaMode.CUSTOM -> settings.customUserAgent?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * The engine's OWN user agent with its WebView markers removed — what the
     * DEFAULT mode actually sends.
     *
     * A WebView announces itself twice in its stock UA: the `wv` token and the
     * `Version/4.0` product. Sites read both, and a growing number of them
     * answer the WebView identity with a code path that is not the one a
     * browser gets — most visibly the video sites, where the substitute player
     * is the one that stalls on "initializing", loads slowly or never starts at
     * all. That is not a bug in the page: it is the page taking the engine at
     * its word.
     *
     * WHY STRIP RATHER THAN SUBSTITUTE A FIXED CHROME UA: the rest of this
     * string is the honest answer — the Chrome version the device's engine
     * actually is, and the platform it actually runs on. Replacing it wholesale
     * with a hard-coded "Chrome 131" would make every profile claim a version
     * that will be years stale, and it would fight the per-profile identity
     * system, which is where a chosen UA belongs (PRESET, CUSTOM, and the
     * device list all supply their own and are not touched by this). Removing
     * two tokens leaves a UA that is true about everything it says and silent
     * about the one thing that changes how a site behaves.
     *
     * A string without those tokens — a desktop profile, an already-clean UA,
     * or anything unrecognisable — is returned unchanged.
     */
    fun webViewNeutralUserAgent(engineUserAgent: String?): String? {
        val raw = engineUserAgent?.takeIf { it.isNotBlank() } ?: return engineUserAgent
        val cleaned = raw
            .replace("; wv)", ")")
            .replace(" wv)", ")")
            .replace("Version/4.0 ", "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        return cleaned.ifBlank { raw }
    }
}
