package com.roombrowser.browser.engine

import android.content.Context
import com.roombrowser.browser.RoomVaultScript
import com.roombrowser.browser.wallet.dapp.RoomWalletScript
import com.roombrowser.domain.model.ClaimedScreen
import com.roombrowser.domain.model.Device
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.model.ProfileSettings
import com.roombrowser.domain.model.UserAgents
import com.roombrowser.domain.model.WebRtcPolicy
import com.roombrowser.domain.model.claimedScreen
import com.roombrowser.engine.BlockedResourceSink
import com.roombrowser.engine.EngineHost
import com.roombrowser.engine.EngineOption
import com.roombrowser.engine.EnginePageScripts
import com.roombrowser.engine.EngineRuntime
import com.roombrowser.engine.EngineSession
import com.roombrowser.engine.ResourceFilter

/**
 * Profile engine — configures engine sessions for exactly ONE profile per
 * process, ON TOP OF the engine-agnostic facade [EngineHost].
 *
 * WHY THIS FILE IS STILL HERE AT ALL. Everything below the facade — which
 * concrete engine, how its settings are spelled, what a per-profile data
 * directory is called — is the engine adapter's business now. What is left
 * here is the part that is PRODUCT POLICY: which scripts this app installs
 * into every page, how desktop mode interacts with the device shim, and the
 * once-per-process binding contract the rest of the app calls. That policy is
 * written once, against the facade, and both editions get it.
 *
 * ISOLATION MODEL (see PROFILE_ISOLATION.md for the full write-up):
 *  - [bindProcessToProfile] is called ONCE, before the first session is
 *    created in the ':browser' process. This gives every profile its own
 *    on-disk cookie jar, localStorage, IndexedDB, service workers and HTTP
 *    cache. How that is enforced differs per edition — a WebView data
 *    directory suffix in one, a GeckoRuntime's context id in the other — and
 *    that difference no longer appears here: the facade's [EngineHost.bind]
 *    carries the same once-per-process contract both editions implement.
 *  - The suffix is derived from the immutable profile UUID (never the name).
 *  - Switching profiles destroys the engine tree and RESTARTS the ':browser'
 *    process (ProfileSwitchExecutor) because the binding is process-wide and
 *    cannot be changed at runtime.
 */
object ProfileEngine {

    /**
     * The application context, captured once so the binding call can keep the
     * single-argument shape the rest of the app (and the instrumented profile
     * isolation test) calls it with.
     *
     * The facade's `bind` needs a `Context`, and this is the only place in the
     * app that has to supply one without being handed it: it is an application
     * context, so holding it for the life of the process leaks nothing.
     */
    @Volatile
    private var appContext: Context? = null

    private val host: EngineHost get() = EngineRuntime.host()

    /**
     * Capture the application context. Called once from `RoomBrowserApp`, in
     * every process, before anything can bind.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Human-readable engine identity for the diagnostics screen.
     *
     * The WebView edition used to answer this itself (current provider package
     * + version, with a probe chain for the pre-selection window); the facade
     * answers it per edition, which is the only place the answer can be
     * correct, since a bundled engine names itself and a system-provided one
     * has to be asked.
     */
    fun engineName(context: Context): String = host.engineName(context)

    /**
     * Every engine provider this device offers, current one flagged. Feeds
     * the Settings → Engine dropdown.
     *
     * Read-only diagnostics — the platform manages which provider is active;
     * this only reports. Never throws.
     */
    fun installedEngines(context: Context): List<EngineOption> =
        runCatching { host.engineOptions(context) }
            .getOrElse { listOf(EngineOption("?", "?", true)) }

    /**
     * Bind this process to [profile]. MUST be called before the first
     * session is created. Returns false when the process is already bound to
     * a DIFFERENT profile (the caller must restart the process).
     *
     * Kept as a single-argument call even though the facade's `bind` takes a
     * context, because that is the shape the whole app calls it with and the
     * context is process state this object already holds.
     */
    fun bindProcessToProfile(profile: ProfileId): Boolean {
        val context = appContext
            ?: error("ProfileEngine.init(context) must run before the process can bind")
        val suffix = profile.safeSuffix
        require(suffix.length in 1..32 && suffix.all { it.isLetterOrDigit() }) {
            "Invalid profile data directory suffix"
        }
        return host.bind(context, profile)
    }

    fun boundProfile(): ProfileId? = host.boundProfile()

    /**
     * Create and configure a session for the bound profile.
     *
     * [sessionId] is the app's own identity for the tab (its row id) — the
     * facade carries it through so an engine callback can be traced back to
     * its tab, and the app owns the value.
     *
     * [isPrivate] is the tab's own privacy, and it is passed straight through
     * rather than looked up here: this is called while a tab is being
     * (re)created, so the tab manager may not hold it yet, and a lookup that
     * silently answered `false` would put a private tab in the profile's
     * on-disk cookie jar.
     */
    fun createSession(
        context: Context,
        profile: Profile,
        sessionId: String,
        isPrivate: Boolean
    ): EngineSession {
        val session = host.createSession(context, profile, sessionId, isPrivate)
        configure(session, profile)
        return session
    }

    /**
     * Apply the profile's settings to a live session.
     *
     * WHAT IS APPLIED HERE VERSUS BELOW THE FACADE. The page-world scripts are
     * this app's own text and this app's own policy — which shim, under which
     * identity, plus the two bridges — so they are composed here and handed
     * down through [EngineSession.setPageScripts]. Everything else (user
     * agent, JavaScript, cookies, mixed content, zoom, autoplay) is a profile
     * field the facade takes wholesale in `EngineHost.configure`, so no engine
     * setting is spelled out on this side any more.
     *
     * The scripts are a REPLACEMENT, not an addition: every configure re-
     * applies the whole set, which is why the call is unconditional. The
     * WebView edition had to remove the previous document-start handler
     * before adding the new one for exactly this reason; the facade makes
     * replacement the contract and the handler bookkeeping disappears.
     */
    fun configure(session: EngineSession, profile: Profile) {
        val settings: ProfileSettings = profile.settings
        session.setPageScripts(
            EnginePageScripts(
                deviceShim = deviceShimScript(settings),
                vaultBridge = RoomVaultScript.SCRIPT,
                walletProvider = RoomWalletScript.SCRIPT
            )
        )
        host.configure(session, profile)
    }

    /**
     * Desktop-site toggle for a specific session (per-tab / per-site).
     *
     * The user agent and the viewport flags are the profile's business and go
     * down through the facade. What stays here is the shim rule, which is a
     * product decision: a desktop UA with an Android client-hint set
     * underneath it is a contradiction, so the device half of the shim comes
     * off while desktop mode is on and goes back when it is turned off. The
     * screen claim is not an Android client hint and stays — a desktop browser
     * window on a screen of a stated size is an ordinary thing — and the WebRTC
     * policy has nothing to do with which identity is being presented, so it
     * stays too.
     */
    fun applyDesktopMode(session: EngineSession, profile: Profile, desktop: Boolean) {
        val settings = profile.settings
        val screen = settings.claimedScreen()
        val shim = if (desktop) {
            deviceShimOrNull(device = null, screen = screen, webRtc = settings.webRtcPolicy)
        } else {
            deviceShimScript(settings)
        }
        session.setPageScripts(
            EnginePageScripts(
                deviceShim = shim,
                vaultBridge = RoomVaultScript.SCRIPT,
                walletProvider = RoomWalletScript.SCRIPT
            )
        )
        host.applyDesktopMode(session, profile, desktop)
    }

    /**
     * The device shim for one profile, or null when the profile has nothing
     * to install.
     *
     * The empty case is the whole of the three, not of the device alone: a
     * profile with no device, no screen claim and a non-default WebRTC policy
     * still has something to install. Making the test about the device would
     * silently drop the policy for every profile that never picked one.
     */
    private fun deviceShimScript(settings: ProfileSettings): String? =
        deviceShimOrNull(
            device = UserAgents.device(settings),
            screen = settings.claimedScreen(),
            webRtc = settings.webRtcPolicy
        )

    private fun deviceShimOrNull(
        device: Device?,
        screen: ClaimedScreen?,
        webRtc: WebRtcPolicy
    ): String? {
        if (device == null && screen == null && webRtc == WebRtcPolicy.DEFAULT) return null
        return DeviceShim.scriptFor(device, screen, webRtc)
    }

    /**
     * Clear ALL engine storage for the given profile. Must be called from a
     * process bound to that profile (the storage is per-profile).
     */
    fun clearEngineStorage(context: Context, profileId: ProfileId) {
        host.clearBrowsingData(context, profileId)
    }

    /**
     * Hand the sub-resource filter to the engine that decides sub-resources in
     * its own process, and tell it where to report what it blocked.
     *
     * The app builds [filter] from the one bundled rule list plus the profile's
     * switches, and re-sends it whenever any of those change -- so this is a
     * REPLACEMENT, not an addition, and calling it twice with the same filter
     * changes nothing. The WebView edition ignores it entirely: it decides
     * sub-resources in the app and reports them itself. See
     * [EngineHost.setResourceFilter].
     */
    fun setResourceFilter(filter: ResourceFilter, blocked: BlockedResourceSink) {
        host.setResourceFilter(filter, blocked)
    }

    /**
     * Diagnostic: compute per-profile engine storage footprint. The exact
     * directory layout is an engine implementation detail; the facade reports
     * the number, and this is only the call site.
     */
    fun profileStorageBytes(context: Context, profileId: ProfileId): Long =
        host.storageBytes(context, profileId)

    /**
     * Commit the engine's buffered state to disk before the process dies.
     *
     * This is the last act of a profile switch, and the process is killed
     * immediately afterwards (`Process.killProcess`), so there is no later
     * opportunity to save anything. Under WebView the cookie store lives in
     * memory until something flushes it, which is why the app used to call
     * `CookieManager.flush()` here; under GeckoView the profile data is
     * already on disk and the engine says so by doing nothing.
     *
     * A failure is swallowed on purpose: the caller is about to kill the
     * process either way, and an exception here would abort the switch and
     * strand the user on a profile they asked to leave.
     */
    fun flushProfileState(context: Context) {
        runCatching { host.flush(context) }
    }
}
