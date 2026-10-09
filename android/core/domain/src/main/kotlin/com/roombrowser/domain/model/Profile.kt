package com.roombrowser.domain.model

import com.roombrowser.domain.proxy.ProxyMode
import com.roombrowser.domain.proxy.ProxyScheme
import com.roombrowser.domain.proxy.ProxyScope
import kotlinx.serialization.Serializable

/**
 * Immutable identity of a browser profile.
 * Storage identity is the UUID — never the profile name.
 */
@Serializable
data class Profile(
    val id: ProfileId,
    val name: String,
    val icon: String = "\uD83D\uDC64", // 👤 generic person
    val colorArgb: Long = 0xFF6750A4,
    val isLocked: Boolean = false,
    val isDefault: Boolean = false,
    val createdAt: Long,
    val lastActiveAt: Long = createdAt,
    val settings: ProfileSettings = ProfileSettings(),
    /** Full per-profile theme snapshot (RoomThemeSpec JSON). Blank = default theme. */
    val themeJson: String = ""
)

@Serializable
data class ProfileId(val value: String) {
    init {
        require(value.isNotBlank()) { "ProfileId must not be blank" }
    }

    /**
     * Safe directory / WebView data-directory suffix.
     * WebView.setDataDirectorySuffix() accepts alphanumeric, max 32 chars.
     * A UUID without dashes is exactly 32 hex chars.
     */
    val safeSuffix: String get() = value.replace("-", "").lowercase().take(32)

    override fun toString(): String = value

    companion object {
        fun new(): ProfileId = ProfileId(java.util.UUID.randomUUID().toString())
    }
}

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

enum class TabLayout { GRID, LIST }

@Serializable
enum class PermissionDecision { ASK, ALLOW, BLOCK }

@Serializable
enum class WebRtcPolicy { DEFAULT, RESTRICT_LOCAL_IP, DISABLED }

@Serializable
enum class DnsMode { SYSTEM, AUTO, DOH, DOT }

@Serializable
enum class UaMode { DEFAULT, PRESET, CUSTOM }

/**
 * What a profile reports for the size of the screen.
 *
 * [REAL] is the default and the only mode in which nothing is claimed: the
 * page is told this phone's own screen. [MANUAL] is a deliberate statement
 * that the screen is something else, and the settings screen spells out what
 * that costs, because a page can compare the claim against the viewport it is
 * actually laid out in.
 */
@Serializable
enum class ScreenSizeMode { REAL, MANUAL }

/**
 * A screen size in CSS pixels, as one profile claims it.
 *
 * CSS pixels are what a page reads from `screen.width`; on Android one CSS
 * pixel is one dp, so a 1080-px-wide, 420-dpi handset reports about 411.
 *
 * The bounds are the range the value can be *stored* in, not a claim that any
 * device ships one: 240 is narrower than any phone Chrome runs on and 4320 is
 * wider than any tablet it runs on. A stored size outside them is treated as a
 * corrupt entry and ignored — see [claimedScreen].
 */
@Serializable
data class ClaimedScreen(val widthPx: Int, val heightPx: Int) {
    /** Which way round the claim says the device is held. */
    val isLandscape: Boolean get() = widthPx > heightPx

    companion object {
        const val MIN_PX = 240
        const val MAX_PX = 4320
    }
}

@Serializable
enum class WarningBehavior { ASK_EVERY_TIME, ONCE_PER_NETWORK, ONCE_PER_SESSION, DONT_WARN }

@Serializable
enum class ConflictSeverity { INFORMATIONAL, REQUIRE_CONFIRMATION }

/** Retention window for profile network (IP) history. */
@Serializable
enum class NetworkRetention(val days: Int) {
    ONE_DAY(1),
    SEVEN_DAYS(7),
    THIRTY_DAYS(30),
    NINETY_DAYS(90),
    FOREVER(Int.MAX_VALUE);

    fun cutoff(nowMs: Long): Long? =
        if (this == FOREVER) null else nowMs - days * 24L * 60L * 60L * 1000L
}

/**
 * Per-profile configuration. Serialized to JSON in Room.
 * Contains NO secrets and NO browsing data.
 */
@Serializable
data class ProfileSettings(
    // Appearance
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val accentArgb: Long = 0xFF6750A4,
    val fontScale: Float = 1.0f,
    val reducedMotion: Boolean = false,
    val highContrast: Boolean = false,
    val tabLayout: TabLayout = TabLayout.GRID,
    // Search & homepage
    val searchEngineId: String = "duckduckgo",
    val homepageEnabled: Boolean = true,
    val homepageShortcuts: List<String> = defaultShortcuts,
    val showPrivacyStats: Boolean = true,
    val showRecentSites: Boolean = true,
    val showClock: Boolean = true,
    // User agent
    //
    // The device is the single control for browser identity: when [deviceId]
    // names a device, that handset's UA is the one the profile sends and the
    // two fields below are not consulted. Choosing a preset or a custom UA in
    // settings clears [deviceId] instead, so a profile never carries two
    // contradictory identities.
    val deviceId: String? = null,
    val uaMode: UaMode = UaMode.DEFAULT,
    val uaPresetId: String? = null,
    val customUserAgent: String? = null,
    // Minted by ProfileManager on create; duplicate and export/import keep it.
    // Null (a profile stored before this field existed) derives nothing.
    val fingerprintSeed: String? = null,
    // Screen size
    //
    // The device decides *what the profile is*; this decides what a page is
    // told about the screen it is drawn on. Real by default, which means
    // nothing is claimed and nothing is overridden.
    //
    // MANUAL is the one setting in this file that deliberately introduces a
    // disagreement: the layout viewport is the page's real width on this
    // display and cannot be moved without re-laying the page out, so a claimed
    // screen that differs from the phone's is a mismatch a script can find.
    // That is the trade the settings row states where the choice is made; the
    // point of the option is that the user gets to choose it on purpose rather
    // than carry the accidental one, where a profile claims a Galaxy S24 Ultra
    // and reports a screen that handset never had. See SECURITY.md.
    val screenSizeMode: ScreenSizeMode = ScreenSizeMode.REAL,
    val screenWidthPx: Int = 0,
    val screenHeightPx: Int = 0,
    // DNS
    val dnsMode: DnsMode = DnsMode.SYSTEM,
    val dohUrl: String? = null,
    val dotHostname: String? = null,
    // Privacy
    //
    // Compatibility-first defaults (2026-09 revision): the annoyance shields —
    // ad blocking, tracker blocking, cross-site-tracker blocking, popup
    // blocking and malicious-site blocking — are now OFF out of the box so
    // sites render exactly as their authors intended (aggressive blocking
    // broke layouts, login flows and embedded players on many real sites).
    // Users who want the stricter behavior can enable each shield per profile
    // in Settings; the features are NOT removed, only default-off for fresh
    // installs and newly created profiles (profiles that already stored an
    // explicit value keep it).
    //
    // What stays ON out of the box is httpsUpgrade, which falls back to http
    // when the secure version is unreachable and therefore never breaks an
    // http-only site.
    val blockAds: Boolean = false,
    val blockTrackers: Boolean = false,
    val blockCrossSiteTrackers: Boolean = false,
    val blockPopups: Boolean = false,
    val blockMalicious: Boolean = false,
    // HTTPS-First: upgrade http navigations to https, then FALL BACK to the
    // original http URL automatically when the secure version is unreachable
    // (HttpsUpgradeFallbackPolicy) — upgrades no longer break http-only sites.
    val httpsUpgrade: Boolean = true,
    // Compatibility defaults (2026-09): third-party cookies are ALLOWED and
    // mixed content runs in compatibility mode out of the box. The previous
    // strict defaults blanked login flows and media on many real sites;
    // users who want the strict behavior can flip both switches in settings.
    val blockThirdPartyCookies: Boolean = false,
    val blockMixedContent: Boolean = false,
    val javascriptEnabled: Boolean = true,
    val webRtcPolicy: WebRtcPolicy = WebRtcPolicy.RESTRICT_LOCAL_IP,
    val searchSuggestions: Boolean = false,
    // Desktop mode default
    val desktopModeDefault: Boolean = false,
    // Language
    val translateTargetLanguage: String = "id",
    val neverTranslateSites: List<String> = emptyList(),
    // Network protection (profile IP conflict warning)
    val networkProtectionUseGlobal: Boolean = true,
    val networkProtectionEnabled: Boolean = true,
    // Proxy (this profile)
    //
    // [ProxyMode.AUTO] by default, because the master switch is NOT here: turning the
    // finder on has to make every profile work immediately, including the ones that
    // already exist, or the setting only applies to profiles created after it. OFF is
    // the per-profile opt-out.
    //
    // The scope list defaults to pages alone: a free proxy is an untrusted middlebox, so
    // it starts where it hides page content from the operator and not on the traffic that
    // carries API keys and wallet RPC payloads. AUTO resolves on every bind, so switching
    // a profile is what re-applies its proxy.
    val proxyMode: ProxyMode = ProxyMode.AUTO,
    val proxyHost: String? = null,
    val proxyPort: Int = 0,
    val proxyScheme: ProxyScheme = ProxyScheme.HTTP,
    val proxyScopes: Set<ProxyScope> = ProxyScope.DEFAULT,
    val proxyPinnedId: String? = null,
    // Downloads
    val downloadSubfolder: String = "RoomBrowser",
    // Autofill
    val autofillEnabled: Boolean = true
) {
    companion object {
        val defaultShortcuts = listOf(
            "https://www.youtube.com",
            "https://github.com",
            "https://www.google.com",
            "https://www.reddit.com"
        )
    }
}

/**
 * The identity fields answer one question — what does this profile claim to
 * be — so at most one of them is ever set. These two helpers are how a screen
 * hands that choice over: picking a preset or a custom UA drops the device,
 * because the UA is then the whole answer. The other direction is
 * [ProfileManager.setDevice], which drops the UA fields.
 */
fun ProfileSettings.withUserAgentPreset(presetId: String?): ProfileSettings =
    copy(deviceId = null, uaMode = UaMode.PRESET, uaPresetId = presetId)

fun ProfileSettings.withCustomUserAgent(value: String?): ProfileSettings =
    copy(deviceId = null, uaMode = UaMode.CUSTOM, customUserAgent = value)

/**
 * The screen size this profile claims, or null to report the phone's own.
 *
 * Null is the answer for every profile that has not asked for an override, and
 * it is also the answer for a profile whose stored numbers are outside
 * [ClaimedScreen.MIN_PX] .. [ClaimedScreen.MAX_PX]. A size no screen has is a
 * corrupt entry, and the truthful reading of a corrupt entry is the phone's
 * real screen rather than a page laid out for a display that cannot exist.
 */
fun ProfileSettings.claimedScreen(): ClaimedScreen? {
    if (screenSizeMode != ScreenSizeMode.MANUAL) return null
    if (screenWidthPx !in ClaimedScreen.MIN_PX..ClaimedScreen.MAX_PX) return null
    if (screenHeightPx !in ClaimedScreen.MIN_PX..ClaimedScreen.MAX_PX) return null
    return ClaimedScreen(screenWidthPx, screenHeightPx)
}

/** Global (browser-wide) settings, independent of any profile. */
@Serializable
data class BrowserGlobalSettings(
    val dnsMode: DnsMode = DnsMode.SYSTEM,
    val dohUrl: String? = null,
    val dotHostname: String? = null,
    val networkProtectionEnabled: Boolean = true,
    val showPreviousProfileName: Boolean = true,
    val showLastSeenTime: Boolean = true,
    val offerNetworkChangeOptions: Boolean = true,
    val offerAirplaneModeShortcut: Boolean = true,
    val retention: NetworkRetention = NetworkRetention.THIRTY_DAYS,
    val warningBehavior: WarningBehavior = WarningBehavior.ASK_EVERY_TIME,
    val conflictSeverity: ConflictSeverity = ConflictSeverity.INFORMATIONAL,
    val telemetryEnabled: Boolean = false, // OFF by default; no data is collected anyway
    val diagnosticsEnabled: Boolean = false,
    // The app-wide master switch for the proxy finder. A profile that opted in still
    // gets no proxy while this is off, so one control can turn the whole feature off.
    val proxyEnabled: Boolean = false
)
