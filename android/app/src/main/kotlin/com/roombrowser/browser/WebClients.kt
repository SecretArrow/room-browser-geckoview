package com.roombrowser.browser

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.roombrowser.data.db.SiteSettingEntity
import com.roombrowser.data.repo.PermissionKind
import com.roombrowser.domain.credentials.CredentialDomainMatcher
import com.roombrowser.domain.engine.FilterEngine
import com.roombrowser.domain.engine.HttpsUpgradeFallbackPolicy
import com.roombrowser.domain.engine.UrlIntelligence
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.oct.OctNavigation
import com.roombrowser.engine.EngineCertificate
import com.roombrowser.engine.EngineSession
import com.roombrowser.engine.EngineSessionListener
import com.roombrowser.engine.HttpAuthResponder
import com.roombrowser.engine.NavigationDecision
import com.roombrowser.engine.PageErrorKind
import com.roombrowser.engine.PermissionResponder
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * Privacy listener — request interception (ad/tracker/malicious blocking),
 * HTTPS upgrades, popup control, permissions, fullscreen and authentication,
 * expressed against the facade's [EngineSessionListener]. Blocking statistics
 * come exclusively from REAL events.
 *
 * WHY ONE CLASS WHERE THERE USED TO BE TWO. The WebView edition had to split
 * this policy across a `WebViewClient` and a `WebChromeClient`, because the
 * platform did. The facade deliberately allows ONE listener per session —
 * GeckoView has one delegate and registering a second replaces the first —
 * so the two halves are merged here rather than duplicated per edition.
 *
 * Site-settings lookups use a thread-safe SNAPSHOT provided by the host
 * (the resource-request callback runs on an engine background thread; Room
 * cannot be queried synchronously there).
 */
class RoomSessionListener(
    private val profile: Profile,
    private val filterEngine: FilterEngine,
    private val callbacks: Callbacks
) : EngineSessionListener {

    /**
     * HTTPS-First fallback bookkeeping: upgraded navigation -> original http
     * URL. When the https version fails (connect/timeout/SSL) the original
     * URL is retried ONCE automatically — upgrades never produce dead error
     * pages on http-only sites.
     */
    private val upgradeFallbacks = HttpsUpgradeFallbackPolicy.Registry()

    /**
     * For deferring work out of an engine callback that must not be re-entered.
     *
     * The https-upgrade retry below starts a load from inside the very error
     * callback that just reported the failure. Doing that inline re-enters the
     * engine while it is still delivering the error, which is why the
     * pre-facade code always deferred it through `view.post { loadUrl(...) }`.
     * The facade has no post of its own, so the hop is kept here rather than
     * quietly dropped.
     */
    private val main = Handler(Looper.getMainLooper())

    interface Callbacks {
        /**
         * Host of the page the FIRING session is showing — the page host a
         * sub-resource block is judged against. [session] is what fired.
         *
         * WHY IT TAKES THE SESSION: the resource-request callback runs for
         * EVERY session, background tabs included. Answering with the ACTIVE
         * tab's URL (what this callback used to do, as `currentUrlHost()`)
         * judged a background tab's sub-resources against whatever page the
         * user happened to be looking at — the cross-site determination was
         * wrong and the blocked-event category recorded for that tab was wrong
         * with it.
         */
        fun pageHostFor(session: EngineSession): String?

        /** Snapshot of site settings for the given request host (thread-safe). */
        fun siteSettingFor(host: String): SiteSettingEntity?

        /** Record a real blocking event (Room insert, fire-and-forget). */
        fun recordBlockEvent(host: String, category: String)
        fun onBlocked(host: String, category: FilterEngine.FilterCategory)
        fun onHttpsUpgrade(host: String)
        fun onPopupBlocked(session: EngineSession)
        fun onSuspiciousSite(url: String, signals: List<String>)

        fun onProgress(session: EngineSession, progress: Int)
        fun onTitleChanged(session: EngineSession, title: String)

        /** [session] is the engine that fired: per-tab state must be routed to
         *  the OWNING tab's row, never to whichever tab happens to be active. */
        fun onPageStarted(session: EngineSession, url: String)

        /** [success] is false when the engine gave up on the load. */
        fun onPageFinished(session: EngineSession, url: String, title: String, success: Boolean)

        /** Live web-history state — fires on EVERY navigation (including
         *  same-document pushState/replaceState) so the UI's Back / Forward
         *  controls are never stale. */
        fun onHistoryChanged(session: EngineSession, canGoBack: Boolean, canGoForward: Boolean)

        /**
         * A main-frame load failed, for a reason that is NOT a certificate
         * failure. [kind] is the engine-neutral category (TRANSPORT covers
         * DNS failure, a refused connection and a drop, which the two engines
         * report with their own, different, numbers); [errorCode] is the
         * engine's own and is for diagnostics only.
         */
        fun onReceivedError(
            session: EngineSession,
            url: String,
            kind: PageErrorKind,
            errorCode: Int,
            description: String?
        )

        /**
         * The connection was refused because its certificate is not trusted.
         * Reported through the facade's [PageErrorKind.CERTIFICATE] rather
         * than a dedicated callback (the WebView edition's
         * `onReceivedSslError`), so [errorCode] is the ENGINE's security code
         * and not a `SslError` primary error.
         *
         * [certificate] is the certificate the connection presented, when the
         * engine could report it. Null means "the engine did not say", never
         * "there was none" — the error page prints those two differently.
         */
        fun onSslError(
            session: EngineSession,
            url: String,
            errorCode: Int,
            description: String?,
            certificate: EngineCertificate?
        )

        /**
         * A site asked for HTTP Basic/Digest credentials. The host shows a
         * prompt and answers with exactly ONE of [proceed] or [cancel] — the
         * engine's responder is single-shot, and dropping both leaves the
         * navigation hanging.
         */
        fun onHttpAuthRequest(
            session: EngineSession,
            host: String,
            realm: String,
            proceed: (String, String) -> Unit,
            cancel: () -> Unit
        )

        /**
         * The page asked for the camera and/or microphone. The host must
         * answer [responder] exactly once, and must refuse a request from
         * anything but the ACTIVE session.
         */
        fun onPermissionRequest(
            session: EngineSession,
            responder: PermissionResponder,
            kinds: Set<PermissionKind>,
            originUrl: String
        )

        /** The page asked for the user's location. [origin] may be null. */
        fun onGeolocationRequest(
            session: EngineSession,
            origin: String?,
            responder: PermissionResponder
        )

        /**
         * The session entered or left fullscreen. A STATE, not a view: the
         * engine renders fullscreen content inside its own view, so the app
         * only hides or restores its chrome.
         */
        fun onFullScreen(session: EngineSession, fullScreen: Boolean)

        /**
         * A `<input type=file>` request. The engine built the picker Intent;
         * the host launches it and answers [accept] with the chosen documents,
         * or null for a cancelled picker.
         */
        fun onFileChooserRequest(intent: Intent, accept: (Array<Uri>?) -> Unit)

        /**
         * A message from one of this session's page-world bridges. [payload]
         * is page-controlled and must be treated as hostile; the session's own
         * URL is the anchor it is validated against.
         */
        fun onPageMessage(session: EngineSession, channel: String, payload: String)

        /**
         * The page started a download, or navigated to something the engine
         * cannot render. The app owns the download queue, so it handles every
         * one of these.
         */
        fun onDownloadRequest(
            session: EngineSession,
            url: String,
            userAgent: String?,
            contentDisposition: String?,
            mimeType: String?
        )
        fun openNewWindow(session: EngineSession, url: String)
        fun openInNewTab(url: String, isPrivate: Boolean)

        /** A refused `oct://` navigation: the app's own loader opens it. */
        fun onCircleNavigation(session: EngineSession, url: String)
        fun currentUrl(): String?

        /** TRUE when [session] is the ACTIVE tab's engine. Needed because a
         *  popup request must be refused for a background tab, and this class
         *  has no other way to know which tab is on screen. */
        fun isActiveEngine(session: EngineSession): Boolean
    }

    // ------------------------------------------------------------- navigation

    /**
     * A navigation is about to start. This is where the app's navigation
     * policy lives: refusing a host on the malicious-site list, and rewriting
     * an `http://` load to `https://` when the profile asks for upgrades.
     *
     * The https upgrade is expressed as [NavigationDecision.LoadDifferent]
     * rather than as a `view.post { loadUrl(...) }` hop, which is what the
     * WebView edition had to do: the facade owns the substitution, so the
     * original navigation is never started and there is no window in which the
     * app has to remember which URL it really meant.
     */
    override fun onNavigationRequest(
        session: EngineSession,
        url: String,
        isTopLevel: Boolean,
        hasUserGesture: Boolean
    ): NavigationDecision {
        // A circle is ours to open, not the engine's; the load is posted so it does not
        // start inside this callback.
        when (val oct = OctNavigation.classify(url)) {
            is OctNavigation.Circle -> {
                main.post { callbacks.onCircleNavigation(session, oct.uri.raw) }
                return NavigationDecision.Block
            }
            OctNavigation.Malformed -> return NavigationDecision.Block
            OctNavigation.PassThrough -> Unit
        }

        val host = UrlIntelligence.hostOf(url).orEmpty()

        // Malicious-site protection for navigations.
        if (profile.settings.blockMalicious && host.isNotBlank()) {
            val category = filterEngine.blockedCategory(host)
            if (category == FilterEngine.FilterCategory.MALICIOUS) {
                callbacks.onBlocked(host, category)
                callbacks.recordBlockEvent(host, StatCategories.from(category))
                return NavigationDecision.Block
            }
            val signals = filterEngine.suspiciousSignals(url)
            if (signals.isNotEmpty()) {
                callbacks.onSuspiciousSite(url, signals)
            }
        }

        // HTTPS upgrade for main-frame http navigations (HTTPS-First with
        // automatic http fallback — see upgradeFallbacks).
        if (profile.settings.httpsUpgrade && url.startsWith("http://") && host.isNotBlank()) {
            val upgraded = UrlIntelligence.upgrade(url)
            if (upgraded.upgradedToHttps) {
                upgradeFallbacks.register(upgraded.url, url)
                callbacks.onHttpsUpgrade(host)
                callbacks.recordBlockEvent(host, StatCategories.HTTPS_UPGRADE)
                return NavigationDecision.LoadDifferent(upgraded.url)
            }
        }
        return NavigationDecision.Allow
    }

    /**
     * A sub-resource load is about to start. Return true to block it.
     *
     * THE FALLBACK IS GONE. The WebView edition recorded the main-frame URL
     * from the request that carried `isForMainFrame`; the facade makes the
     * session's own URL authoritative instead, so nothing here has to keep a
     * parallel copy of it.
     *
     * ENGINE NOTE. In THIS edition nothing calls this method for a
     * sub-resource: the WebView edition's session implementation is the only
     * caller in that build, and
     * `GeckoEngineSession` explains at its own site why it cannot supply one --
     * GeckoView 153 has no `shouldInterceptRequest` equivalent. What used to be
     * deferred to "when the extension lands" has landed: the bridge extension's
     * `blocker.js` registers a blocking `webRequest.onBeforeRequest` listener,
     * so a sub-resource is cancelled inside Gecko and reported back through
     * `EngineHost.setResourceFilter`'s sink. A block therefore still reaches
     * [Callbacks.onBlocked] and [Callbacks.recordBlockEvent] -- the same two
     * calls this method makes below, which is why the dashboard counts a block
     * identically in both editions.
     *
     * THE DECISION IS THUS WRITTEN TWICE, and that is the one duplication this
     * design accepts: here in Kotlin, and mirrored in `blocker.js`, because a
     * blocking listener must answer synchronously and cannot call into the app.
     * The RULES are not duplicated -- the app reads its one bundled list and
     * hands it over the native port -- so the two copies can disagree only
     * about the control flow, never about what is on the list.
     */
    override fun onResourceRequest(
        session: EngineSession,
        url: String,
        isForMainFrame: Boolean
    ): Boolean {
        if (isForMainFrame) return false
        val host = UrlIntelligence.hostOf(url) ?: return false
        // The OWNING session's page host, resolved from the firing session —
        // never the active tab's (see [Callbacks.pageHostFor]). The resolver
        // is total (pure map/URL lookups, no throw) and falls back to the
        // active tab's URL for a session that is not tracked, so this hot path
        // stays allocation-light and cannot fail a sub-resource load.
        val pageHost = callbacks.pageHostFor(session)
        val siteOverride = callbacks.siteSettingFor(host)
        val shieldsDisabled = siteOverride?.shieldsDisabled == true
        val s = profile.settings
        val decision = filterEngine.decide(
            requestHost = host,
            pageHost = pageHost,
            path = runCatching { java.net.URI(url).path }.getOrNull() ?: "/",
            blockAds = s.blockAds && !shieldsDisabled,
            blockTrackers = s.blockTrackers && !shieldsDisabled,
            blockCrossSite = s.blockCrossSiteTrackers && !shieldsDisabled,
            blockMalicious = s.blockMalicious
        )
        if (decision is FilterEngine.Decision.Blocked) {
            callbacks.onBlocked(host, decision.category)
            callbacks.recordBlockEvent(host, StatCategories.from(decision.category))
            return true
        }
        return false
    }

    // ----------------------------------------------------------- page events

    override fun onPageStarted(session: EngineSession, url: String?) {
        callbacks.onPageStarted(session, url.orEmpty())
    }

    override fun onPageFinished(session: EngineSession, url: String?, success: Boolean) {
        val shown = url.orEmpty()
        callbacks.onPageFinished(session, shown, session.title ?: shown, success)
    }

    override fun onTitleChanged(session: EngineSession, title: String?) {
        title?.let { callbacks.onTitleChanged(session, it) }
    }

    override fun onProgress(session: EngineSession, progress: Int) {
        callbacks.onProgress(session, progress)
    }

    /**
     * THE reliable back/forward signal, in both editions: the engine reports
     * history state whenever it changes, including the same-document
     * navigations (history.pushState) that produce no page-started or
     * page-finished event at all. Without it the navigation buttons stay grey
     * forever on SPA sites.
     */
    override fun onNavigationStateChanged(
        session: EngineSession,
        canGoBack: Boolean,
        canGoForward: Boolean
    ) {
        callbacks.onHistoryChanged(session, canGoBack, canGoForward)
    }

    // ------------------------------------------------------------ load errors

    /**
     * A load failed. Return true to have handled it.
     *
     * The two cases the WebView edition had to tell apart are now one call
     * with a [PageErrorKind]: a certificate that is bad for a SUB-RESOURCE is
     * that resource's problem and is swallowed so the page carries on, while a
     * certificate failure for the page itself gets the "this connection is not
     * secure" surface. Nothing here ever proceeds past a failed validation.
     */
    override fun onPageError(
        session: EngineSession,
        url: String?,
        kind: PageErrorKind,
        isTopLevel: Boolean,
        errorCode: Int,
        description: String?,
        certificate: EngineCertificate?
    ): Boolean {
        // HTTPS-First fallback: an https endpoint without working TLS behind
        // one of OUR upgrades -> retry the original http URL once, silently
        // (no error page flash). The registry key is the FAILING url.
        if (url != null && isTopLevel) {
            val original = upgradeFallbacks.consume(url)
            // THE RECOVERABILITY TEST BELONGS TO THE TRANSPORT CODE SPACE.
            // It is written against WebViewClient's ERROR_* codes (-1, -6, -8,
            // -11), and the facade guarantees every engine reports in that
            // space -- see EngineErrorCode, which is also where a GeckoView
            // failure is translated. It was not always so: an engine passing
            // its own numbering through would have landed every failure in
            // this test's `else` branch and the retry below would simply never
            // have fired, silently.
            //
            // On the CERTIFICATE path the code is an `SslError.SSL_*` value
            // (0..5) instead, and no SSL code can match that list -- so testing
            // it there made the fallback unreachable in precisely the case it
            // exists for: an http-only host whose https port answers with a
            // certificate we will not accept. The pre-facade code consumed the
            // registry on the certificate path with no test at all, and that is
            // what this restores. The test still guards the transport path,
            // where the code space is the one it was written for.
            val recoverable = kind == PageErrorKind.CERTIFICATE ||
                HttpsUpgradeFallbackPolicy.isRecoverable(errorCode)
            if (original != null && recoverable) {
                main.post { session.loadUri(original) }
                return true
            }
        }
        if (kind == PageErrorKind.CERTIFICATE) {
            // Sub-resource certificate failures are not the page's failure.
            if (!isTopLevel) return true
            callbacks.onSslError(session, url.orEmpty(), errorCode, description, certificate)
            return true
        }
        if (!isTopLevel) return true
        callbacks.onReceivedError(session, url.orEmpty(), kind, errorCode, description)
        return true
    }

    // -------------------------------------------------------------- popups

    /**
     * May this session open another window?
     *
     * THE GATE, and it carries no URL. That is the point rather than an
     * omission: it is asked before the engine commits anything to the popup --
     * on WebView before the throwaway transport is built, on GeckoView before
     * a session is created -- so a refusal costs no renderer. The target URL
     * arrives afterwards, at [onNewWindowResolved].
     *
     * AN EARLIER VERSION OF THIS METHOD TOOK THE URL and refused a null one,
     * which read as "there is nothing to open" and was locally reasonable. It
     * was also fatal: the engine's gate legitimately has no URL yet, so the
     * gate always answered false and EVERY popup -- every `target="_blank"`
     * link, every `window.open()` -- was refused in both editions, with
     * nothing shown to the user.
     */
    override fun onNewWindowRequest(session: EngineSession, hasUserGesture: Boolean): Boolean {
        val blocked = profile.settings.blockPopups || !hasUserGesture
        if (blocked) {
            callbacks.onPopupBlocked(session)
            return false
        }
        // A popup from a BACKGROUND session is refused outright: opening a
        // window on behalf of a page the user is not looking at is exactly the
        // cross-tab surprise this guards. DROPPED rather than queued — a window
        // opened now would be navigated whenever the user finally got to that
        // tab, with no context for why, and the usual background case (no user
        // gesture) was already refused above. The page simply sees
        // window.open() fail.
        return callbacks.isActiveEngine(session)
    }

    /**
     * The popup's target is known. Open it as a new tab.
     *
     * Only ever reached after [onNewWindowRequest] returned true for this same
     * popup, so this is the half that acts, not the half that decides --
     * which is why it has no return value.
     */
    override fun onNewWindowResolved(session: EngineSession, url: String, hasUserGesture: Boolean) {
        callbacks.openNewWindow(session, url)
    }

    // --------------------------------------------------------- permissions

    override fun onMediaPermissionRequest(
        session: EngineSession,
        origin: String,
        wantsVideo: Boolean,
        wantsAudio: Boolean,
        responder: PermissionResponder
    ) {
        val kinds = mutableSetOf<PermissionKind>()
        if (wantsVideo) kinds += PermissionKind.CAMERA
        if (wantsAudio) kinds += PermissionKind.MICROPHONE
        // Refused rather than ignored: an unanswered request hangs the page
        // for the life of its document.
        if (kinds.isEmpty()) {
            responder.deny()
            return
        }
        callbacks.onPermissionRequest(session, responder, kinds, origin)
    }

    override fun onGeolocationRequest(
        session: EngineSession,
        origin: String?,
        responder: PermissionResponder
    ) {
        // Denied rather than ignored when the engine cannot attribute the
        // request: an unanswered request hangs the page, and a null origin is
        // not a reason to grant anything.
        if (origin.isNullOrBlank()) {
            responder.deny()
            return
        }
        callbacks.onGeolocationRequest(session, origin, responder)
    }

    override fun onHttpAuthRequest(
        session: EngineSession,
        host: String,
        realm: String,
        responder: HttpAuthResponder
    ) {
        var answered = false
        callbacks.onHttpAuthRequest(
            session = session,
            host = host,
            realm = realm,
            proceed = { user, password ->
                if (!answered) {
                    answered = true
                    runCatching { responder.proceed(user, password) }
                }
            },
            cancel = {
                if (!answered) {
                    answered = true
                    runCatching { responder.cancel() }
                }
            }
        )
    }

    override fun onFullScreen(session: EngineSession, fullScreen: Boolean) {
        callbacks.onFullScreen(session, fullScreen)
    }

    override fun onFileChooserRequest(intent: Intent, accept: (Array<Uri>?) -> Unit) {
        callbacks.onFileChooserRequest(intent, accept)
    }

    override fun onPageMessage(session: EngineSession, channel: String, payload: String) {
        callbacks.onPageMessage(session, channel, payload)
    }

    /**
     * The page started a download. The app enqueues it through its own
     * download engine, so the answer is always "handled" — returning true and
     * then doing nothing would be a silently failed download.
     */
    override fun onDownloadRequest(
        session: EngineSession,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ): Boolean {
        callbacks.onDownloadRequest(session, url, userAgent, contentDisposition, mimeType)
        return true
    }
}

/**
 * The `channel` an upward page message carries: the name of the native entry
 * point that was called, which is the same string the page sees on
 * `window` (`RoomVault`, `RoomWallet`).
 *
 * WHY THE APP NAMES THESE. The facade deliberately does not fix the channel
 * vocabulary — it describes the two entry points in prose and leaves the wire
 * to the editions, because each edition's page-world script chooses what it
 * posts. The name the page already calls is the only string both the injected
 * scripts and the page-visible contract already agree on, so it is the one
 * that can be shared.
 */
object PageBridgeChannels {
    const val VAULT = "RoomVault"
    const val WALLET = "RoomWallet"
}

/** Reads a possibly-null string field out of a page-supplied envelope. */
internal fun JSONObject.stringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name, "").ifEmpty { null }

/** Category names persisted for stats (kept in one place to avoid typos). */
object StatCategories {
    const val AD = "AD"
    const val TRACKER = "TRACKER"
    const val CROSS_SITE_TRACKER = "CROSS_SITE_TRACKER"
    const val MALICIOUS = "MALICIOUS"
    const val POPUP = "POPUP"
    const val HTTPS_UPGRADE = "HTTPS_UPGRADE"
    const val COOKIE_BLOCKED = "COOKIE_BLOCKED"
    const val SCRIPT_BLOCKED = "SCRIPT_BLOCKED"

    fun from(category: FilterEngine.FilterCategory): String = when (category) {
        FilterEngine.FilterCategory.AD -> AD
        FilterEngine.FilterCategory.TRACKER -> TRACKER
        FilterEngine.FilterCategory.CROSS_SITE_TRACKER -> CROSS_SITE_TRACKER
        FilterEngine.FilterCategory.MALICIOUS -> MALICIOUS
        FilterEngine.FilterCategory.POPUP -> POPUP
    }
}

/**
 * NATIVE half of the password-manager page bridge, exposed to page JS as
 * `window.RoomVault` (see [RoomVaultScript] for the injected half).
 *
 * PROTOCOL (what page JS can call — nothing else is exported):
 *  - `RoomVault.requestCredentials(location.host, location.href)` — the user
 *    focused/tapped a password field. No values are returned to the page; the
 *    native side decides whether to surface an "offer" sheet at all.
 *  - `RoomVault.reportCredential(location.host, username, password)` — a
 *    login form containing a non-empty password was submitted: the DOM
 *    submit event, a submit-control click, Enter in the password field, a
 *    scripted form submit(), or a request sent in the window after a vault
 *    fill (see [RoomVaultScript]). Observational only; nothing is stored
 *    until the user taps "Save" on the prompt sheet.
 *
 * HOW THE CALLS ARRIVE NOW. There is no `@JavascriptInterface` on this side
 * any more, in either edition: the page reaches native code through the
 * engine's own page bridge and the call surfaces as
 * [RoomSessionListener.onPageMessage], carrying the channel this object is
 * registered under ([PageBridgeChannels.VAULT]). [onMessage] unpacks the
 * envelope and routes it to the same two handlers the old interface exposed,
 * so the PAGE-VISIBLE wire — two method names, their arity, their argument
 * order — does not change by a byte. Only where the arguments come from does.
 *
 * SECURITY MODEL — the bridge never trusts the page:
 *  1. Only the two methods named in the envelope are reachable; anything else
 *     the page sends on this channel is dropped.
 *  2. Every call is validated ON THE MAIN THREAD against the session's own
 *     current URL — [EngineSession.url], the facade's documented TRUST
 *     ANCHOR, which is written only from a top-level navigation and never
 *     from the page. The host the page CLAIMS must match the host family of
 *     what the session is actually showing (CredentialDomainMatcher, equal or
 *     parent/child). The host handed to the callbacks is always the session's
 *     authoritative one, so a page can never obtain or report credentials
 *     attributed to another site. (The injected script registers in the MAIN
 *     FRAME ONLY, which is the first line of defence; this host check is the
 *     second, and it holds even against a page that reaches the bridge
 *     directly instead of going through the script.)
 *  3. Nothing here reads the vault or shows UI — that is the ViewModel's
 *     decision ([com.roombrowser.browser.BrowserViewModel]). The bridge is
 *     silent to the page either way, and a LOCKED vault never starts a
 *     biometric prompt on page focus; at most the ViewModel may show the
 *     offer sheet's locked variant, whose only action is the user's own tap.
 *  4. No argument is ever logged.
 *
 * THREADING: engine callbacks may arrive on the engine's own threads, so each
 * call hops to the main thread inside [main] before validation; the session
 * URL it validates against is readable from any thread, which is what makes
 * the hop safe. Throttle fields are therefore confined to the main thread.
 *
 * LIFETIME: the host keeps one bridge per session in a weak map, so this
 * object holds its session only through a [WeakReference] — a destroyed
 * session releases its bridge rather than being kept alive by it.
 */
class RoomVaultBridge(
    session: EngineSession,
    private val callbacks: Callbacks
) {

    private val sessionRef = WeakReference(session)
    private val main = Handler(Looper.getMainLooper())

    /** Anti-spam state (a hostile page can reach the bridge directly,
     *  bypassing the injected script's own cooldowns). Main-thread only. */
    private var lastRequestAt = 0L
    private var lastReportKey: String? = null
    private var lastReportAt = 0L

    interface Callbacks {
        /** The user focused a login form on [session] (authoritative [host]). */
        fun onCredentialsRequested(session: EngineSession, host: String, href: String)

        /** A login form submitted on [session] (authoritative [host]). */
        fun onCredentialReported(
            session: EngineSession,
            host: String,
            username: String,
            password: String
        )
    }

    /**
     * One upward page message for this channel. The payload is page-controlled
     * and is treated as hostile: it is parsed defensively, and anything that is
     * not one of the two known methods is dropped without a trace.
     */
    fun onMessage(payload: String) {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
        when (json.optString("method")) {
            METHOD_REQUEST_CREDENTIALS ->
                requestCredentials(json.stringOrNull("host"), json.stringOrNull("href"))
            METHOD_REPORT_CREDENTIAL ->
                reportCredential(
                    json.stringOrNull("host"),
                    json.stringOrNull("username"),
                    json.stringOrNull("password")
                )
        }
    }

    private fun requestCredentials(host: String?, href: String?) {
        val claimedHost = host ?: return
        main.post {
            val session = sessionRef.get() ?: return@post
            val pageHost = validatedHost(session, claimedHost) ?: return@post
            val now = SystemClock.elapsedRealtime()
            if (now - lastRequestAt < REQUEST_COOLDOWN_MS) return@post
            lastRequestAt = now
            callbacks.onCredentialsRequested(session, pageHost, href.orEmpty())
        }
    }

    private fun reportCredential(host: String?, username: String?, password: String?) {
        val claimedHost = host ?: return
        val reportedUsername = username.orEmpty()
        val reportedPassword = password.orEmpty()
        // The password lives only in this posted lambda and the callback —
        // never in a log, cache or field beyond the prompt state.
        if (reportedPassword.isEmpty()) return
        main.post {
            val session = sessionRef.get() ?: return@post
            val pageHost = validatedHost(session, claimedHost) ?: return@post
            val now = SystemClock.elapsedRealtime()
            if (now - lastReportAt < REPORT_MIN_GAP_MS) return@post
            val key = pageHost + '\n' + reportedUsername + '\n' + reportedPassword
            if (key == lastReportKey && now - lastReportAt < REPORT_SAME_KEY_COOLDOWN_MS) {
                return@post
            }
            lastReportKey = key
            lastReportAt = now
            callbacks.onCredentialReported(session, pageHost, reportedUsername, reportedPassword)
        }
    }

    /**
     * The host a page claims, accepted only when it names the same site as
     * the session's CURRENT URL (equal or parent/child domain). Returns the
     * session's own (authoritative) host, or null when the claim fails.
     */
    private fun validatedHost(session: EngineSession, claimedHost: String): String? {
        val url = session.url ?: return null
        val currentHost = UrlIntelligence.hostOf(url) ?: return null
        val claimed = CredentialDomainMatcher.normalize(claimedHost)
        if (claimed.isEmpty()) return null
        if (!CredentialDomainMatcher.matches(claimed, currentHost)) return null
        return currentHost
    }

    companion object {
        /** JS object name the injected script (and, defensively, pages) see —
         *  which is also the channel the engine's page bridge reports it on. */
        const val JS_INTERFACE_NAME = PageBridgeChannels.VAULT

        /** Method names the page can reach, as the injected script spells them. */
        private const val METHOD_REQUEST_CREDENTIALS = "requestCredentials"
        private const val METHOD_REPORT_CREDENTIAL = "reportCredential"

        /** Focus events on the same page arrive in bursts — coalesce them. */
        private const val REQUEST_COOLDOWN_MS = 500L

        /** A hostile page may call the bridge directly — rate-limit hard. */
        private const val REPORT_MIN_GAP_MS = 1_000L

        /** An identical (host, username, password) report is not re-prompted. */
        private const val REPORT_SAME_KEY_COOLDOWN_MS = 30_000L
    }
}

/**
 * INJECTED half of the password-manager page bridge — a SEPARATE
 * document-start script (installed by ProfileEngine.configure, independent of
 * the device shim) that provides the page-side detection and fill logic the
 * native [RoomVaultBridge] calls back into.
 *
 * Scope, by design:
 *  - MAIN FRAME ONLY (`window.top === window.self`): embedded third-party
 *    login widgets inside iframes are out of scope — the native host check
 *    would compare an iframe's host against the top page anyway, and filling
 *    across frame boundaries is a phishing vector we simply do not open.
 *    Cross-origin iframe logins are therefore NOT detected, by choice.
 *  - OFFER = focus/click on an `input[type=password]`, OR focus on a
 *    username-candidate field while the page shows a visible password field
 *    (the user starts at the username — the offer must too, or it arrives
 *    after the whole form is already typed).
 *  - SAVE = a login form being submitted, whatever path the page uses:
 *      1. the DOM `submit` event — native submits and `form.requestSubmit()`;
 *      2. a capture-phase `click` on a submit control (`input[type=submit]`,
 *         `input[type=image]`, `button[type=submit]`, and a `<button>` with
 *         no type attribute, whose default IS submit) inside a form — the
 *         shape of nearly every "Sign in" button, including the ones whose
 *         form handler preventDefaults and posts with fetch;
 *      3. `Enter` in a password field (SPAs routinely consume the key and
 *         post the form themselves, so no DOM event ever reaches us);
 *      4. the form's own `submit()` / `requestSubmit()` (a scripted
 *         `submit()` bypasses the submit event entirely);
 *      5. `fetch` and `XMLHttpRequest.prototype.send` WHILE a password field
 *         we filled is still recent — the SPA login that reads the field and
 *         POSTs JSON, with no DOM signal at all;
 *      6. a click on ANY button-shaped control (BUTTON of any type, INPUT
 *         button kinds, role=button) while a visible password field holds a
 *         value — the formless React/Vue login screen, whose "Log in" div
 *         has no form, no submit type and sometimes no button tag at all.
 *         Paired with this, the network path 5 window is now ALSO armed by
 *         the user TYPING into a password field (not only by a vault fill),
 *         gated to non-GET requests — the formless SPA's actual login POST.
 *
 * Why 2/3/5/6 are deliberately narrow: `click`, `keydown`, `input` and
 * `fetch` fire for everything on a page (toggles, search-as-you-type,
 * analytics), so the in-form click path accepts only genuine submit controls,
 * the formless click path additionally requires a filled visible password
 * field, and the typed network window requires a non-GET request. Paths 1
 * and 4 are exact signals and are never gated.
 *
 * Dismissal reset: `window.__roomVaultReset()`, called by the host when the
 * user taps "Not now" — an early report (an eye-toggle click on a formless
 * layout is the canonical case) must not have its 30-second same-key
 * suppression swallow the REAL submit that follows the dismissal.
 *
 * STILL NOT DETECTED — the honest list, so nobody mistakes this for total
 * coverage: cross-origin iframe logins; canvas/WebGL-drawn login UIs (there
 * are no input elements to observe); a page that collects the password
 * without any of the six signals above (e.g. keeps keystrokes in a variable
 * and posts from a Web Worker or WebSocket); a page that replaces
 * `window.fetch` / `XMLHttpRequest.prototype.send` AFTER this script has run
 * (we wrap once, at document start — re-wrapping on every assignment would be
 * an arms race we lose anyway); and a submit from a password field that was
 * never focused, typed into, clicked or filled through anything we can see.
 *
 * Robustness rules: everything is wrapped in try/catch — a broken page must
 * still load; nothing is ever written to the console (no spam); the script is
 * idempotent under re-injection (a reconfigure replaces the document-start
 * handler, and the install guard makes a double injection a no-op anyway —
 * which is also why the prototype/`fetch` wrappers need no second guard).
 *
 * Username heuristics: within the password field's form (falling back to the
 * whole document), the text/email/tel inputs BEFORE the password field are
 * ranked — autocomplete/name/id/placeholder hints like "username", "email",
 * "login", "account", plus a type=email bonus — and the best-scoring one is
 * remembered as the username field for the fill.
 *
 * Fill protocol: `window.__roomVaultFill(payload)` where payload is a JSON
 * string `{"u": username, "p": password}` (JSON-quoted by the native side —
 * values are never naively interpolated into JS). The fill writes through
 * the input prototype's native value setter and dispatches input/change
 * events so framework-driven pages (React et al.) register the values, and it
 * starts the post-fill window that arms detection path 5. The element
 * references captured at request time are used when still attached;
 * otherwise detection re-runs against the live document.
 */
object RoomVaultScript {

    // A Kotlin raw string: no "$" may appear anywhere in this script (a
    // dollar would be read as Kotlin interpolation), so plain string
    // concatenation is used throughout, and no JS template literals.
    const val SCRIPT = """
(function () {
  'use strict';
  try {
    if (window.top !== window.self) return;
    if (window.__roomVaultInstalled) return;
    window.__roomVaultInstalled = true;

    var REQUEST_COOLDOWN_MS = 800;
    var REPORT_SAME_KEY_MS = 30000;
    // How long after a vault fill the network wrappers keep watching. Long
    // enough for a round-trip login, short enough that later unrelated
    // requests are not mistaken for one.
    var RECENT_FILL_MS = 15000;
    // The same window for a password the user TYPED (no vault fill involved):
    // the formless SPA login reads the field and POSTs with fetch/XHR, and
    // without this window that login is invisible — no form, no submit
    // control, no fill. A login POST leaves within seconds of the last
    // keystroke, so fifteen seconds covers it without stretching to the
    // page's later unrelated traffic.
    var RECENT_TYPE_MS = 15000;
    var lastRequestAt = 0;
    var lastReportKey = '';
    var lastReportAt = 0;
    var lastFillAt = 0;
    var lastTypeAt = 0;
    var userField = null;
    var passField = null;

    function visible(el) {
      try {
        var r = el.getBoundingClientRect();
        return r.width > 0 && r.height > 0;
      } catch (e) {
        return true;
      }
    }

    function isPassword(el) {
      try {
        return !!el && el.tagName === 'INPUT' &&
          String(el.type || '').toLowerCase() === 'password';
      } catch (e) {
        return false;
      }
    }

    function isUsernameCandidate(el) {
      if (!el || el.tagName !== 'INPUT') return false;
      var t = String(el.type || '').toLowerCase();
      if (t !== 'text' && t !== 'email' && t !== 'tel') return false;
      try {
        if (el.disabled || el.readOnly) return false;
      } catch (e) {}
      return true;
    }

    function score(el) {
      var hint = ((el.name || '') + ' ' + (el.id || '') + ' ' +
        (el.autocomplete || '') + ' ' + (el.placeholder || '')).toLowerCase();
      var s = 0;
      if (String(el.type || '').toLowerCase() === 'email') s += 3;
      if (hint.indexOf('username') >= 0) s += 4;
      if (hint.indexOf('email') >= 0) s += 3;
      if (hint.indexOf('user') >= 0) s += 2;
      if (hint.indexOf('login') >= 0) s += 2;
      if (hint.indexOf('account') >= 0) s += 2;
      return s;
    }

    function bestUsername(passEl) {
      var scopes = [];
      try {
        if (passEl.form && passEl.form.querySelectorAll) scopes.push(passEl.form);
      } catch (e) {}
      scopes.push(document);
      for (var s = 0; s < scopes.length; s++) {
        var before = [];
        var after = [];
        var seen = false;
        try {
          var list = scopes[s].querySelectorAll('input');
          for (var i = 0; i < list.length; i++) {
            var el = list[i];
            if (el === passEl) { seen = true; continue; }
            if (!isUsernameCandidate(el) || !visible(el)) continue;
            if (seen) { after.push(el); } else { before.push(el); }
          }
        } catch (e) {}
        // The username is normally ABOVE the password field; fall back to
        // below-only when the form has no leading candidate at all.
        var pool = before.length ? before : after;
        if (!pool.length) continue;
        var best = null;
        var bestScore = -1;
        for (var j = 0; j < pool.length; j++) {
          var sc = score(pool[j]);
          if (sc > bestScore) { best = pool[j]; bestScore = sc; }
        }
        if (best) return best;
      }
      return null;
    }

    function setValue(el, value) {
      try {
        var proto = (el.tagName === 'TEXTAREA')
          ? window.HTMLTextAreaElement.prototype
          : window.HTMLInputElement.prototype;
        var d = Object.getOwnPropertyDescriptor(proto, 'value');
        if (d && d.set) { d.set.call(el, value); } else { el.value = value; }
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
      } catch (e) {
        try { el.value = value; } catch (e2) {}
      }
    }

    function anyPassword() {
      try {
        var list = document.querySelectorAll('input');
        for (var i = 0; i < list.length; i++) {
          if (isPassword(list[i]) && visible(list[i])) return list[i];
        }
      } catch (e) {}
      return null;
    }

    function firstPassword(scope) {
      try {
        if (!scope || !scope.querySelectorAll) return null;
        var list = scope.querySelectorAll('input');
        for (var i = 0; i < list.length; i++) {
          if (isPassword(list[i])) return list[i];
        }
      } catch (e) {}
      return null;
    }

    function closestForm(el) {
      try {
        var n = el;
        while (n && n.nodeType === 1) {
          if (n.tagName === 'FORM') return n;
          n = n.parentNode;
        }
      } catch (e) {}
      return null;
    }

    function isSubmitControl(el) {
      try {
        if (!el || (el.tagName !== 'BUTTON' && el.tagName !== 'INPUT')) return false;
        var t = String(el.type || '').toLowerCase();
        if (el.tagName === 'INPUT') return t === 'submit' || t === 'image';
        if (t === 'submit') return true;
        // A BUTTON with no type attribute defaults to type=submit; an
        // explicit type=button is a toggle or other control and is ignored,
        // which is what keeps "show password" buttons from looking like logins.
        return !el.hasAttribute('type');
      } catch (e) {
        return false;
      }
    }

    // Path 6's net: ANY button-shaped element — a BUTTON whatever its type
    // (a React onClick submit button does not need type=submit), an INPUT of
    // the button kinds, or anything the page has dressed in role=button.
    // Wider than isSubmitControl on purpose: the formless path only fires
    // when a visible password field already holds a value, so the extra
    // breadth costs at most a dismissible prompt, never a missed login.
    function isButtonish(el) {
      try {
        if (!el || el.nodeType !== 1) return false;
        var tag = el.tagName;
        if (tag === 'BUTTON') return true;
        if (tag === 'INPUT') {
          var t = String(el.type || '').toLowerCase();
          return t === 'submit' || t === 'button' || t === 'image';
        }
        var role = '';
        try { role = el.getAttribute('role') || ''; } catch (e2) {}
        return role === 'button';
      } catch (e) {
        return false;
      }
    }

    function recentlyFilled() {
      return lastFillAt > 0 && (Date.now() - lastFillAt) < RECENT_FILL_MS;
    }

    function recentlyTyped() {
      return lastTypeAt > 0 && (Date.now() - lastTypeAt) < RECENT_TYPE_MS;
    }

    // True when the page's visible password field holds something — the
    // formless-SPA click path's gate, and the username-focus offer's gate.
    function pageHasFilledPassword() {
      var pf = anyPassword();
      return !!(pf && pf.value);
    }

    window.__roomVaultFill = function (payload) {
      try {
        var data = (typeof payload === 'string') ? JSON.parse(payload) : payload;
        if (!data) return;
        // isPassword guard: a username-field focus calls request() with the
        // PAGE's password field, but a page that re-rendered between the
        // focus and this fill can leave a stale element here — writing the
        // password into a text field is the one mistake this cannot make.
        var pf = (passField && document.contains(passField) && isPassword(passField))
          ? passField : anyPassword();
        if (!pf) return;
        passField = pf;
        if (data.p !== undefined && data.p !== null) setValue(pf, String(data.p));
        var uf = (userField && document.contains(userField)) ? userField : bestUsername(pf);
        if (uf && data.u !== undefined && data.u !== null) setValue(uf, String(data.u));
        // Open the post-fill window: on an SPA the next fetch/XHR is very
        // likely the login submit, and it sends no DOM event we could see.
        lastFillAt = Date.now();
      } catch (e) {}
    };

    function detect(passEl) {
      passField = passEl;
      userField = bestUsername(passEl);
    }

    function request(passEl) {
      var now = Date.now();
      if (now - lastRequestAt < REQUEST_COOLDOWN_MS) return;
      lastRequestAt = now;
      detect(passEl);
      try {
        if (window.RoomVault && typeof window.RoomVault.requestCredentials === 'function') {
          window.RoomVault.requestCredentials(
            String(location.host || ''), String(location.href || ''));
        }
      } catch (e) {}
    }

    // Reports ONE password field: reads its own (and its username field's)
    // value, applies the duplicate suppression, and hands the payload to the
    // native bridge — the one place every detection path funnels through.
    // Returns true when something was actually reported.
    function reportElement(pw) {
      try {
        if (!pw || !isPassword(pw) || !pw.value) return false;
        var uf = bestUsername(pw);
        var username = uf ? String(uf.value || '') : '';
        var password = String(pw.value || '');
        var key = String(location.host || '') + '\n' + username + '\n' + password;
        var now = Date.now();
        if (key === lastReportKey && now - lastReportAt < REPORT_SAME_KEY_MS) return false;
        lastReportKey = key;
        lastReportAt = now;
        if (window.RoomVault && typeof window.RoomVault.reportCredential === 'function') {
          window.RoomVault.reportCredential(String(location.host || ''), username, password);
        }
        return true;
      } catch (e) {
        return false;
      }
    }

    // Called by the native side when the user dismisses the save prompt
    // without saving: the report suppression above must not swallow the REAL
    // submit that follows a "Not now" (the eye-toggle click reported early,
    // the actual login button reports for real — same values, thirty seconds
    // apart — and the second one is the one the user meant). Clearing the
    // key here re-arms every detection path for that submit.
    window.__roomVaultReset = function () {
      try {
        lastReportKey = '';
        lastReportAt = 0;
        lastFillAt = 0;
        lastTypeAt = 0;
      } catch (e) {}
    };

    function report(scope) {
      try {
        var pw = firstPassword(scope);
        if (pw) reportElement(pw);
      } catch (e) {}
    }

    // The password field we last saw, when it is still in the document and
    // holds something; otherwise the first one on the page. Used by the paths
    // that have no form argument (network wrappers, the fill window).
    function reportBest() {
      try {
        if (passField && document.contains(passField) && isPassword(passField) && passField.value) {
          return reportElement(passField);
        }
        var pf = anyPassword();
        if (pf && pf.value) return reportElement(pf);
      } catch (e) {}
      return false;
    }

    // Armed by the fetch/XHR wrappers AND by the user's own typing: the fill
    // window keeps its old any-request behaviour, while the typed window is
    // gated to non-GET requests — a manually typed password followed by an
    // analytics GET is not a login, and the one thing that makes a request
    // look like a submit without a form is that it WRITES. [methodHint] is
    // uppercase, or null when the wrapper could not tell (in which case the
    // typed window still reports — unknown must not mean suppressed, or a
    // send() through an exotic wrapper is invisible again).
    function maybeReportOnSubmit(methodHint) {
      var filled = recentlyFilled();
      var typed = recentlyTyped();
      if (!filled && !typed) return;
      if (!filled && methodHint === 'GET') return;
      if (reportBest()) {
        lastFillAt = 0;
        lastTypeAt = 0;
      }
    }

    // The request method a fetch call is making, when it can be told:
    // fetch(input, options) where input is a URL string, or a Request object
    // of our own. A bare fetch(url) IS a GET — the default — so it says 'GET'
    // rather than null: the typed window must be able to skip analytics
    // pings that never bothered writing a method down.
    function fetchMethodHint(args) {
      try {
        if (!args || !args.length) return 'GET';
        var a0 = args[0];
        if (a0 && typeof a0 === 'object' && typeof a0.method === 'string') {
          return String(a0.method || '').toUpperCase() || 'GET';
        }
        if (args.length > 1 && args[1] && typeof args[1] === 'object' &&
            typeof args[1].method === 'string') {
          return String(args[1].method || '').toUpperCase() || 'GET';
        }
        return 'GET';
      } catch (e) {}
      return null;
    }

    // Path 4: a scripted form.submit() skips the submit event entirely, and
    // requestSubmit() fires it (reporting twice is harmless — the key
    // suppression above dedupes). Wrapped once, at document start; a page
    // that replaces these later defeats the wrap (documented above).
    function hookFormMethods() {
      try {
        var proto = window.HTMLFormElement && window.HTMLFormElement.prototype;
        if (!proto) return;
        var origSubmit = proto.submit;
        if (typeof origSubmit === 'function') {
          proto.submit = function () {
            try { report(this); } catch (e) {}
            return origSubmit.apply(this, arguments);
          };
        }
        var origRequestSubmit = proto.requestSubmit;
        if (typeof origRequestSubmit === 'function') {
          proto.requestSubmit = function () {
            try { report(this); } catch (e) {}
            return origRequestSubmit.apply(this, arguments);
          };
        }
      } catch (e) {}
    }

    // Path 5: the SPA login that reads the field and POSTs, with no DOM
    // signal at all. Both wrappers report BEFORE the request leaves, so the
    // values are still in the page. The window is armed by a vault fill AND
    // by the user typing into a password field (see RECENT_TYPE_MS); XHR's
    // method comes from an open() hook, fetch's from its arguments.
    function hookNetwork() {
      try {
        if (typeof window.fetch === 'function') {
          var origFetch = window.fetch;
          window.fetch = function () {
            try { maybeReportOnSubmit(fetchMethodHint(arguments)); } catch (e) {}
            return origFetch.apply(this, arguments);
          };
        }
      } catch (e) {}
      try {
        var xhrProto = window.XMLHttpRequest && window.XMLHttpRequest.prototype;
        if (xhrProto && typeof xhrProto.send === 'function') {
          var origOpen = xhrProto.open;
          xhrProto.open = function () {
            try { this.__roomMethod = String(arguments[0] || '').toUpperCase() || null; } catch (e) {}
            return origOpen.apply(this, arguments);
          };
          var origSend = xhrProto.send;
          xhrProto.send = function () {
            try { maybeReportOnSubmit(this.__roomMethod || null); } catch (e) {}
            return origSend.apply(this, arguments);
          };
        }
      } catch (e) {}
    }

    document.addEventListener('focusin', function (ev) {
      try {
        var t = ev.target;
        if (isPassword(t)) { request(t); return; }
        // A username field in a login form is where the user STARTS — waiting
        // for the password field to be focused before offering logins means
        // the offer arrives after the username is already typed, which is
        // exactly the "saved passwords are never suggested" experience.
        // request() takes the PASSWORD element (it anchors the fill's field
        // references), so the page's password field is what is passed — not
        // the focused username field, which would anchor the fill to a text
        // input. Gate on a visible password field existing, so a search box
        // alone never asks.
        if (isUsernameCandidate(t)) {
          var pf = anyPassword();
          if (pf) request(pf);
        }
      } catch (e) {}
    }, true);

    // The user typing into a password field arms the network window — see
    // RECENT_TYPE_MS. An input event, not keydown: paste, autofill from the
    // keyboard's own manager, and IME compositions all count as "the user
    // put a password in this field", and a keydown listener sees none of
    // them.
    document.addEventListener('input', function (ev) {
      try {
        var t = ev.target;
        if (isPassword(t)) lastTypeAt = Date.now();
      } catch (e) {}
    }, true);

    document.addEventListener('click', function (ev) {
      try {
        var t = ev.target;
        if (isPassword(t)) { request(t); return; }
        // Path 2: walk up from the click target (it is usually a child of
        // the control) to a genuine submit control inside a form.
        var el = t;
        while (el && el.nodeType === 1 && el !== document && !isSubmitControl(el)) {
          el = el.parentNode;
        }
        if (isSubmitControl(el)) {
          var form = closestForm(el);
          if (!form && el.form && el.form.tagName === 'FORM') form = el.form;
          if (form) {
            var pw = firstPassword(form);
            if (pw && pw.value) reportElement(pw);
            return;
          }
          // A submit control with no form around it: fall through to the
          // formless path below rather than returning — the control's whole
          // purpose was still a submit.
        }
        // Path 6: the formless SPA login button — a BUTTON (of any type; a
        // React onClick handler does not need type=submit), an INPUT
        // button/submit, or anything wearing role=button, clicked while a
        // VISIBLE password field on the page holds a value. Nearly every
        // hand-rolled React/Vue login screen is shaped exactly like this, and
        // without this path their successful logins are never offered a save.
        // False-positive cost (e.g. a "show password" eye toggle in a
        // formless layout): one prompt the user dismisses with "Not now" —
        // and the dismissal reset (window.__roomVaultReset) re-arms the
        // real submit right behind it.
        var node = t;
        while (node && node.nodeType === 1 && node !== document && !isButtonish(node)) {
          node = node.parentNode;
        }
        if (isButtonish(node) && pageHasFilledPassword()) {
          var pf = anyPassword();
          if (pf) reportElement(pf);
        }
      } catch (e) {}
    }, true);

    // Path 3: Enter in a password field. Deferred one tick so a page that
    // consumes the key and posts the form itself has run first; the values
    // are already in the field either way.
    document.addEventListener('keydown', function (ev) {
      try {
        var key = ev.key;
        if (key !== 'Enter' && ev.keyCode !== 13) return;
        var t = ev.target;
        if (!isPassword(t)) return;
        var form = closestForm(t);
        setTimeout(function () {
          try {
            var pw = form ? firstPassword(form) : t;
            if (pw && pw.value) reportElement(pw);
          } catch (e) {}
        }, 0);
      } catch (e) {}
    }, true);

    // Path 1: the DOM submit event (native submits, requestSubmit()).
    document.addEventListener('submit', function (ev) {
      try {
        var f = ev.target;
        if (f && f.tagName === 'FORM') report(f);
      } catch (e) {}
    }, true);

    hookFormMethods();
    hookNetwork();
  } catch (e) {
    // A page must still load even when the bridge cannot install.
  }
})();
"""
}
