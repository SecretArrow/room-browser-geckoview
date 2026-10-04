package com.roombrowser.browser

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.createBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roombrowser.RoomBrowserApp
import com.roombrowser.agent.BrowserAgentController
import com.roombrowser.browser.engine.DnsMonitor
import com.roombrowser.browser.engine.DownloadEngine
import com.roombrowser.browser.engine.NetworkIdentity
import com.roombrowser.browser.engine.ProfileEngine
import com.roombrowser.data.db.BookmarkEntity
import com.roombrowser.data.db.DownloadEntity
import com.roombrowser.data.db.HistoryEntity
import com.roombrowser.data.db.SiteSettingEntity
import com.roombrowser.data.db.TabEntity
import com.roombrowser.data.repo.BrowserRepository
import com.roombrowser.data.repo.PendingNetDecision
import com.roombrowser.data.repo.PermissionKind
import com.roombrowser.domain.credentials.CredentialDomainMatcher
import com.roombrowser.domain.credentials.SavedCredential
import com.roombrowser.domain.engine.FilterEngine
import com.roombrowser.domain.engine.UrlIntelligence
import com.roombrowser.domain.model.BrowserGlobalSettings
import com.roombrowser.domain.model.Device
import com.roombrowser.domain.model.Devices
import com.roombrowser.domain.model.PermissionDecision
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.model.ProfileSettings
import com.roombrowser.domain.theme.BuiltInThemes
import com.roombrowser.domain.theme.RoomThemeSpec
import com.roombrowser.domain.theme.ThemeJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/** Current page presentation state (drives the omnibox + error pages). */
data class PageState(
    val url: String = "about:home",
    val title: String = "",
    val progress: Int = 0,
    val loading: Boolean = false,
    val secure: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isHomepage: Boolean = true,
    val isPrivate: Boolean = false,
    val desktopMode: Boolean = false
)

sealed interface PageError {
    data class NoInternet(val url: String) : PageError
    data class Ssl(val url: String, val message: String) : PageError
    data class DnsFailure(val url: String) : PageError
    data class Generic(val url: String, val message: String?) : PageError
}

/** Navigation lifecycle events (consumed by the AI agent to await loads). */
sealed interface PageEvent {
    data class Started(val url: String, val at: Long) : PageEvent
    data class Finished(val url: String, val title: String, val at: Long) : PageEvent
}

/** Site-shield snapshot for the current page. */
data class ShieldsState(
    val host: String = "",
    val adsBlocked: Int = 0,
    val trackersBlocked: Int = 0,
    val httpsUpgrades: Int = 0,
    val shieldsDisabled: Boolean = false
)

/**
 * BrowserViewModel — owns the tab model, the active WebView, filtering,
 * downloads, DNS state and network-identity warnings for exactly ONE
 * profile (the process-bound one).
 */
class BrowserViewModel(
    application: Application,
    val profileId: ProfileId
) : AndroidViewModel(application) {

    private val graph = (application as RoomBrowserApp).graph
    private val browserRepo: BrowserRepository = graph.browserRepo
    private val appState = graph.appState

    var profile by mutableStateOf(Profile(id = profileId, name = "", createdAt = 0))
        private set

    /** This profile's ACTIVE theme — the whole engine UI re-themes from it. */
    var themeSpec by mutableStateOf(BuiltInThemes.default())
        private set

    private val tabManager = TabManager()
    private val dnsMonitor = DnsMonitor()
    val networkIdentity = NetworkIdentity(appState, browserRepo, graph.ipConflictDetector)

    /**
     * Per-WebView dApp bridges (window.ethereum & friends). Weak keys: a
     * destroyed engine's bridge must not outlive it — GC reclaims both. Used
     * to push accountsChanged/chainChanged events to every live page after
     * the user switches networks or accounts in the wallet dashboard.
     */
    private val walletBridges = java.util.WeakHashMap<WebView, com.roombrowser.browser.wallet.dapp.WalletBridge>()

    /** Last-seen wallet state, so event collectors only emit on CHANGE. */
    private var lastWalletChainIds: Map<com.roombrowser.domain.wallet.model.ChainType, com.roombrowser.domain.wallet.model.NetworkConfig> = emptyMap()
    private var lastWalletEvmAddresses: List<String> = emptyList()

    lateinit var downloadEngine: DownloadEngine
        private set

    private var httpClient: OkHttpClient = OkHttpClient()

    var pageState by mutableStateOf(PageState())
        private set
    var pageError by mutableStateOf<PageError?>(null)
        private set

    /**
     * Short identity for THIS ViewModel, carried on every RoomNav line.
     *
     * WHY (CI 36949239247/36944983033): one BrowserActivity lifecycle can
     * build two or three ViewModels back to back — a CLEAR_TASK relaunch, the
     * self-restart for a profile rebind, a launcher start racing the test's
     * own — and they all log under the same tag from the same pid. Reading
     * an interleaved trace, "selectTab" and "openNewTab" looked like one
     * instance contradicting itself, when they were two instances with
     * different tabs. The id makes the owner of every line explicit.
     */
    val navId: String = Integer.toHexString(System.identityHashCode(this)).takeLast(4)
    var shieldsState by mutableStateOf(ShieldsState())
        private set
    var tabs by mutableStateOf<List<TabEntity>>(emptyList())
        private set
    var activeTabId by mutableStateOf<String?>(null)
        private set
    var bookmarks by mutableStateOf<List<BookmarkEntity>>(emptyList())
        private set
    var recentHistory by mutableStateOf<List<HistoryEntity>>(emptyList())
        private set
    var downloads by mutableStateOf<List<DownloadEntity>>(emptyList())
        private set

    /**
     * Live transfer rates, id -> bytes per second. Not persisted and not part
     * of the download record: a speed is a measurement of a moment, and a stored
     * one would be replayed to the user as if it were current.
     */
    var downloadSpeeds by mutableStateOf<Map<Long, Long>>(emptyMap())
        private set
    var globalSettings by mutableStateOf(BrowserGlobalSettings())
        private set
    var dnsState by mutableStateOf<DnsMonitor.DnsState>(DnsMonitor.DnsState.System)
        private set
    var netState by mutableStateOf<NetworkIdentity.NetState>(NetworkIdentity.NetState.Idle)
        private set
    var privacyStats by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    var readerContent by mutableStateOf<ReaderContent?>(null)
        private set
    /** All profiles (for the quick switcher). */
    var allProfiles by mutableStateOf<List<Profile>>(emptyList())
        private set

    /** Transient UI messages consumed by the Compose layer. */
    val snackbar = MutableStateFlow<String?>(null)

    /** Set once the persisted-tab restore finished — incoming navigation
     *  requests (EXTRA_INITIAL_URL, QR, share-intents) wait for it so they
     *  can never be overridden by the restore picking the first tab. */
    private val restored = MutableStateFlow(false)

    /**
     * True while a profile-network warning decision is PENDING: no URL may
     * load. Armed from a fresh [NetworkIdentity.checkOnOpen] conflict or
     * from the persisted pending decision (process death can never bypass
     * it); released only by an explicit user decision in
     * NetworkWarningActivity.
     */
    private val networkGate = MutableStateFlow(false)

    /** Gate as observable state (BrowserActivity re-launches the warning
     *  activity while it stands — system Back can never dismiss it). */
    val networkGateState: StateFlow<Boolean> = networkGate

    /** Persisted conflict payload behind the pending decision — feeds the
     *  NetworkWarningActivity intent extras. */
    var pendingNetWarning: PendingNetDecision? = null
        private set

    /** One-shot counter: >0 asks BrowserScreen to (re)open the profile
     *  quick switcher ("Switch Profile" decision on the network warning). */
    val quickSwitcherSignal = MutableStateFlow(0)

    /** The live WebView of the ACTIVE tab — each tab owns its own engine
     *  (kept alive in its TabManager session while in the background).
     *  Compose state, so WebViewHost swaps the attached engine the moment
     *  this changes (never into a stale parent, never a missed reattach). */
    var activeWebView: WebView? by mutableStateOf<WebView?>(null)
        private set

    /**
     * The tab an in-flight agent turn is working on, or null when no turn is
     * running. See [pinTabForAgent] for why it is held here rather than in
     * the controller.
     */
    private var agentTabId: String? = null

    /** Latest page-load event (see [PageEvent]) — for the agent's nav waiting. */
    @Volatile
    var lastPageEvent: PageEvent? = null
        private set

    /** AI agent controller (chat + autonomous browsing) for this profile. */
    val agent: BrowserAgentController = BrowserAgentController(
        application, profileId, this, OkHttpClient()
    )

    /** Site-settings snapshot for the interception thread (thread-safe). */
    @Volatile
    private var siteSettingsSnapshot: Map<String, SiteSettingEntity> = emptyMap()

    lateinit var webViewClient: RoomWebViewClient
        private set

    /** Fullscreen media view state. */
    var customView by mutableStateOf<View?>(null)
        private set
    private var customViewCallback: android.webkit.WebChromeClient.CustomViewCallback? = null

    /** Pending permission requests from the web engine. */
    var pendingPermission by mutableStateOf<PendingPermission?>(null)
        private set

    /**
     * A camera/microphone request the page is waiting on.
     *
     * [requesterOrigin] is `request.origin` — the origin that ASKED, which is
     * a frame's origin when a cross-origin iframe asked, not the hosting
     * page's. The sheet names this one; naming [pageUrl] instead would pin a
     * third-party frame's camera request on the site the user is reading,
     * which is the same misattribution the wallet sheets refuse when they
     * name the WebView-verified host rather than the page's claim.
     */
    data class PendingPermission(
        val request: PermissionRequest,
        val kinds: Set<PermissionKind>,
        val pageUrl: String,
        val requesterOrigin: String
    )

    var pendingGeolocation by mutableStateOf<PendingGeolocation?>(null)
        private set

    data class PendingGeolocation(
        val origin: String?,
        val callback: android.webkit.GeolocationPermissions.Callback,
        val host: String
    )

    /**
     * An outstanding HTTP Basic/Digest challenge. Carries an identity guard
     * ([pageUrl]) for the same reason the permission prompts do: a second
     * page's challenge must never be answered by the dialog the user is
     * looking at.
     */
    var pendingHttpAuth by mutableStateOf<PendingHttpAuth?>(null)
        private set

    data class PendingHttpAuth(
        val host: String,
        val realm: String,
        val pageUrl: String,
        val proceed: (String, String) -> Unit,
        val cancel: () -> Unit
    )

    /** Answers the outstanding challenge with credentials. */
    fun submitHttpAuth(user: String, password: String) {
        val pending = pendingHttpAuth ?: return
        pendingHttpAuth = null
        pending.proceed(user, password)
    }

    /**
     * Refuses the outstanding challenge. The engine MUST be answered — a
     * dropped handler leaves the navigation hanging forever — so dismissing
     * the dialog is a cancel, and the 401 that follows renders normally.
     */
    fun dismissHttpAuth() {
        val pending = pendingHttpAuth ?: return
        pendingHttpAuth = null
        pending.cancel()
    }

    /** File-chooser bridge for <input type=file>. */
    var fileChooserCallback: ValueCallback<Array<Uri>>? = null
        private set

    private val clientCallbacks = object : RoomWebViewClient.Callbacks {
        /**
         * The page host a sub-resource of [view] is judged against.
         *
         * shouldInterceptRequest fires for every engine, background tabs
         * included, so the answer has to come from the engine that fired.
         * The active engine is the one case where there is a fresher source
         * than the reverse index: `pageState.url` is updated the moment its
         * page commits, while the index is refreshed on the persistence
         * funnel. For any other engine the owner's indexed URL is the only
         * correct answer — the active tab's URL is a different page.
         *
         * The `?: pageState.url` is a deliberate fallback, not a preference:
         * an engine that owns no session (destroyed, or mid-teardown) has no
         * indexed URL, and every sub-resource it is still fetching must keep
         * being judged rather than silently unblocked.
         */
        override fun pageHostFor(view: WebView): String? {
            val url = if (view === activeWebView) {
                pageState.url
            } else {
                tabManager.pageUrlFor(view) ?: pageState.url
            }
            return UrlIntelligence.hostOf(url)
        }
        override fun siteSettingFor(host: String): SiteSettingEntity? = siteSettingsSnapshot[host]
        override fun recordBlockEvent(host: String, category: String) {
            recordBlock(host, category)
        }
        override fun onBlocked(host: String, category: FilterEngine.FilterCategory) {
            refreshShields()
        }
        override fun onHttpsUpgrade(host: String) {
            emitMessage("Upgraded to HTTPS: $host")
        }
        override fun onPopupBlocked() {
            recordBlock(UrlIntelligence.hostOf(pageState.url) ?: "", StatCategories.POPUP)
            emitMessage("Popup blocked")
        }
        override fun onSuspiciousSite(url: String, signals: List<String>) {
            emitMessage("Caution: ${signals.joinToString()}")
        }
        override fun onPageStarted(view: WebView, url: String) {
            // [view] is the engine that fired: everything below belongs to
            // the tab that OWNS it. pageState is the ACTIVE tab's view state
            // and is only ever written while the firing engine IS the active
            // one (a background tab's load must never repaint this tab).
            val owner = tabManager.idFor(view) ?: return
            Log.d(NAV_TAG, "vm=$navId onPageStarted url=$url active=${view === activeWebView}")
            if (view !== activeWebView) {
                // A background tab navigating: the URL belongs to ITS row
                // (title stays as stored — the new document has none yet).
                persistTab(owner, url)
                return
            }
            lastPageEvent = PageEvent.Started(url, SystemClock.elapsedRealtime())
            pageError = null
            pageState = pageState.copy(url = url, loading = true, progress = 5, isHomepage = false)
            // A navigation retires the vault offer: the login field it was
            // collected for belonged to the outgoing document. The save
            // prompt deliberately SURVIVES navigation — a form submit is
            // itself a navigation, and the user must still be able to save.
            invalidateVaultOfferOnNavigation()
            // Same reasoning for a pending camera/mic/location sheet, minus
            // the exception: nothing about the new document can justify the
            // old one's grant, so it is refused rather than carried over.
            invalidateWebPermissionRequests()
        }
        override fun onPageFinished(view: WebView, url: String, title: String) {
            // ROUTING: the finish lands on the tab that OWNS the firing
            // engine. pageState / pageError / thumbnails / shields belong to
            // the ACTIVE tab only — a background tab's load must never write
            // into them nor into the active tab's row (the cross-tab
            // contamination: A finishing while B was active stamped A's
            // url/title onto B and flipped the omnibox back to A).
            val owner = tabManager.idFor(view) ?: return
            val active = view === activeWebView
            // A freshly created WebView can fire a LATE finish for its
            // INITIAL about:blank commit at first attach — AFTER a real
            // navigation already started. (CI 75822ed: the artifact finish
            // flagged the tab as a homepage mid-load and the page content
            // swapped away; the real navigation never completed on screen.)
            // The artifact is ONLY meaningful while the tab still belongs
            // to the start page or to an EXPLICIT about:blank navigation.
            // The committed URL must be THIS tab's — pageState.url is the
            // ACTIVE tab's and says nothing about another tab's commit.
            // The check is deliberately TITLE-AGNOSTIC: WebClients passes
            // `view.title ?: url`, so the artifact can carry "about:blank"
            // as its title and a title-based test would never fire.
            // Otherwise it is a stale artifact: drop it and let the real
            // page's finish land.
            val committed = if (active) {
                pageState.url
            } else {
                tabs.firstOrNull { it.id == owner }?.url ?: ""
            }
            if (url == "about:blank" &&
                committed != "about:home" && committed != "about:blank"
            ) {
                Log.d(NAV_TAG, "vm=$navId dropped stale about:blank finish (committed=$committed)")
                return
            }
            if (!active) {
                // A background tab finished: persist into ITS OWN row (the
                // row is the only store that outlives the engine) and record
                // the visit under ITS privacy flag — never the active tab's.
                Log.d(NAV_TAG, "vm=$navId onPageFinished (background) url=$url title=$title")
                persistTab(owner, url, title)
                if (tabs.firstOrNull { it.id == owner }?.isPrivate != true) {
                    recordVisit(url, title)
                }
                return
            }
            lastPageEvent = PageEvent.Finished(url, title, SystemClock.elapsedRealtime())
            Log.d(NAV_TAG, "vm=$navId onPageFinished url=$url title=$title")
            pageState = pageState.copy(
                url = url,
                title = title,
                loading = false,
                progress = 100,
                secure = url.startsWith("https://"),
                isHomepage = url == "about:home" || (url == "about:blank" && title.isBlank())
            )
            // Routed by the engine's OWNER, never by "whatever is active": the
            // two coincide here, but the id comes from the view that fired.
            persistTab(owner, url, title, touch = true)
            if (!pageState.isPrivate) recordVisit(url, title)
            captureThumbnail()
            refreshShields()
            refreshStats()
        }
        override fun onHistoryChanged(view: WebView, canGoBack: Boolean, canGoForward: Boolean) {
            // The single source of truth for the Back / Forward buttons and
            // the system-Back web-history branch. Without this the nav bar
            // stayed grey forever (canGoBack was never reported).
            // Only the ACTIVE engine owns that UI state: a background tab's
            // history lives inside its own WebView.
            if (view !== activeWebView) return
            if (pageState.canGoBack != canGoBack || pageState.canGoForward != canGoForward) {
                pageState = pageState.copy(canGoBack = canGoBack, canGoForward = canGoForward)
            }
        }
        override fun onReceivedHttpError(view: WebView, url: String, statusCode: Int) {
            // An HTTP error is NOT an engine error: no onReceivedError fires,
            // onPageFinished still arrives, the URL is right — and the screen
            // shows an empty document. Without this line a 404 and a rendered
            // page look identical in the trace, which is exactly the
            // ambiguity that stalled the geolocation/camera suite.
            Log.d(
                NAV_TAG,
                "vm=$navId onReceivedHttpError url=$url code=$statusCode " +
                    "active=${view === activeWebView}"
            )
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            // The first frame the engine is willing to show, and the earliest
            // point at which a layout pass has certainly run — so the size
            // here is real, unlike the one taken when the deferred load fires.
            // A 0x0 or shown=false at THIS moment is the difference between
            // "the page is slow" and "the page has nowhere to draw": the two
            // look identical from the device side, because a WebView with no
            // area still loads, still reports onPageFinished, and publishes no
            // accessibility nodes for UiAutomator to find.
            Log.d(
                NAV_TAG,
                "vm=$navId onPageCommitVisible url=$url " +
                    "size=${view.width}x${view.height} shown=${view.isShown} " +
                    "active=${view === activeWebView}"
            )
        }

        override fun onReceivedError(view: WebView, url: String, errorCode: Int, description: String?) {
            // The error surface belongs to the ACTIVE tab — a background
            // failure must not paint an error page over the page on screen.
            if (view !== activeWebView) return
            Log.d(NAV_TAG, "vm=$navId onReceivedError url=$url code=$errorCode")
            pageError = when (errorCode) {
                android.webkit.WebViewClient.ERROR_HOST_LOOKUP -> PageError.DnsFailure(url)
                android.webkit.WebViewClient.ERROR_CONNECT,
                android.webkit.WebViewClient.ERROR_TIMEOUT -> PageError.NoInternet(url)
                else -> PageError.Generic(url, description)
            }
            pageState = pageState.copy(loading = false)
        }
        override fun onSslError(view: WebView, url: String, error: SslError) {
            if (view !== activeWebView) return
            pageError = PageError.Ssl(url, sslErrorText(error))
            pageState = pageState.copy(loading = false)
        }
        override fun onHttpAuthRequest(
            view: WebView,
            host: String,
            realm: String,
            proceed: (String, String) -> Unit,
            cancel: () -> Unit
        ) {
            // A BACKGROUND engine must not raise a credential prompt over the
            // page the user is reading — and it cannot be left unanswered
            // either, so it is refused outright. The same rule the popup
            // transport follows.
            if (view !== activeWebView) {
                cancel()
                return
            }
            // One challenge at a time. A second one arriving while a dialog is
            // up is refused rather than silently replacing the first, which
            // would strand the first handler.
            if (pendingHttpAuth != null) {
                cancel()
                return
            }
            pendingHttpAuth = PendingHttpAuth(
                host = host,
                realm = realm,
                pageUrl = view.url.orEmpty(),
                proceed = proceed,
                cancel = cancel
            )
        }
        override fun openInNewTab(url: String, isPrivate: Boolean) {
            viewModelScope.launch { openNewTab(url, isPrivate) }
        }
    }

    private val chromeCallbacks = object : RoomWebChromeClient.ChromeCallbacks {
        override fun onProgress(view: WebView, progress: Int) {
            // Progress is pure ACTIVE-tab UI state — a background engine's
            // progress must not drive the bar the user is watching.
            if (view !== activeWebView) return
            pageState = pageState.copy(progress = progress, loading = progress < 100)
        }
        override fun onTitleChanged(view: WebView, title: String) {
            // Only the ACTIVE engine's title paints the omnibox. A background
            // tab's title is NOT lost: its onPageFinished persists url+title
            // in ONE write to its own row — writing it here as well would be
            // a second, racing read-modify-write on that same row (it could
            // land after the finish and stamp the PRE-navigation URL back).
            if (view !== activeWebView) return
            pageState = pageState.copy(title = title)
        }
        override fun onShowCustomView(view: View, callback: android.webkit.WebChromeClient.CustomViewCallback) {
            customView = view
            customViewCallback = callback
        }
        override fun onHideCustomView() {
            customView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
        }
        override fun onPermissionRequest(
            view: WebView?,
            request: PermissionRequest,
            kinds: Set<PermissionKind>,
            originUrl: String
        ) {
            // A background engine must not raise a grant sheet over the tab the
            // user is looking at. Answered with an explicit DENY rather than
            // left alone: an unanswered PermissionRequest leaves the page's
            // request pending for the life of the document.
            if (view == null || view !== activeWebView) {
                request.deny()
                return
            }
            val origin = request.origin
            // A request always carries an origin; the fallback keeps the sheet
            // naming the page rather than an empty line if one ever does not.
            val requesterOrigin = if (origin == null) originUrl else origin.toString()
            viewModelScope.launch {
                answerOrRaise(request, kinds, originUrl, requesterOrigin, view)
            }
        }
        override fun onGeolocationPermissions(
            view: WebView?,
            origin: String?,
            callback: android.webkit.GeolocationPermissions.Callback
        ) {
            // Same rule as permissions: deny for a background engine instead of
            // leaving the callback un-invoked.
            if (view == null || view !== activeWebView) {
                callback.invoke(origin, false, false)
                return
            }
            val host = origin?.let { UrlIntelligence.hostOf(it) } ?: ""
            viewModelScope.launch {
                if (view !== activeWebView) {
                    callback.invoke(origin, false, false)
                    return@launch
                }
                when (storedDecisionFor(host, setOf(PermissionKind.LOCATION))) {
                    PermissionDecision.BLOCK -> callback.invoke(origin, false, false)
                    PermissionDecision.ALLOW -> callback.invoke(origin, true, false)
                    // No stored answer: ask. `retain = false` on the way out
                    // (see respondGeolocation) — the WebView's own per-origin
                    // store is not where a decision the user can neither see
                    // nor revoke belongs.
                    else -> {
                        retirePendingGeolocation()
                        pendingGeolocation = PendingGeolocation(origin, callback, host)
                    }
                }
            }
        }
        override fun onFileChooserIntent(
            view: WebView?,
            intent: android.content.Intent,
            callback: RoomWebChromeClient.FileChooserResult
        ) {
            // A file input waiting on a picker that will never open is worse
            // than one told no, so a background engine's request is cancelled.
            if (view == null || view !== activeWebView) {
                callback.onResult(null)
                return
            }
            fileChooserLauncherIntent = intent
            fileChooserResult = callback
        }
        override fun openNewWindow(view: WebView?, url: String) {
            // The second of two checks: onCreateWindow already refuses a popup
            // from a background engine before the transport is built, but the
            // active tab can change while the popup's URL is being resolved, so
            // ownership is re-checked here against the tab that actually asked.
            if (view == null || view !== activeWebView) return
            viewModelScope.launch { openNewTab(url, isPrivate = false) }
        }
        override fun onPopupBlocked(view: WebView?) {
            // handled by the WebViewClient path
        }
        override fun currentUrl(): String? = pageState.url
        override fun isActiveEngine(view: WebView): Boolean = view === activeWebView
    }

    var fileChooserLauncherIntent: android.content.Intent? = null
        private set
    var fileChooserResult: RoomWebChromeClient.FileChooserResult? = null
        private set

    // ------------------------------------------------------------------
    // Answers to the engine's permission requests.
    //
    // The engine's callbacks (onPermissionRequest / onGeolocationPermissions)
    // are the entry point; the sheet is the only caller of the methods below,
    // and it runs on the UI thread — which is the thread the WebView requires:
    // a PermissionRequest settled off it is ignored and the page waits for the
    // life of the document.
    //
    // The ONE thing that can answer without the sheet is a decision already
    // stored for the site. Nothing in the app writes one — the sheet
    // deliberately does not persist, so it can honestly say "allowed for this
    // visit only" — but a restored profile backup carries the table with it
    // (ProfileRepositoryImpl.importBackup), and re-asking would silently throw
    // away an answer the user carried across. A stored decision is therefore
    // read and honoured, and "Clear site data" revokes it for the host.
    // ------------------------------------------------------------------

    /**
     * Answers [request] from the store when the site has a decision for every
     * one of [kinds], and otherwise raises the sheet.
     *
     * The store read is a suspend hop, so the answer is applied after one: the
     * engine may have been replaced while it ran, and a request from a page
     * that is gone must not be granted.
     */
    private suspend fun answerOrRaise(
        request: PermissionRequest,
        kinds: Set<PermissionKind>,
        pageUrl: String,
        requesterOrigin: String,
        engine: WebView
    ) {
        if (engine !== activeWebView) {
            request.deny()
            return
        }
        val host = UrlIntelligence.hostOf(requesterOrigin) ?: ""
        when (storedDecisionFor(host, kinds)) {
            PermissionDecision.BLOCK -> request.deny()
            PermissionDecision.ALLOW -> request.grant(request.resources)
            else -> {
                // The sheet shows one request at a time. A request still on
                // screen when a second arrives is refused rather than
                // overwritten: overwriting would leave the first page's promise
                // pending for the life of its document.
                retirePendingPermission()
                pendingPermission = PendingPermission(
                    request = request,
                    kinds = kinds,
                    pageUrl = pageUrl,
                    requesterOrigin = requesterOrigin
                )
            }
        }
    }

    /**
     * The decision stored for [host] covering EVERY one of [kinds], or null
     * when the site has not answered for all of them.
     *
     * A single BLOCK answers for the whole request: the WebView's grant is
     * wholesale, so a request for camera+microphone cannot be half-granted.
     *
     * Deliberately fail-open. An unreadable store means "no stored answer",
     * and the sheet then decides — the one outcome that cannot be wrong, and
     * the one that keeps a database failure from leaving a page hanging.
     */
    private suspend fun storedDecisionFor(
        host: String,
        kinds: Set<PermissionKind>
    ): PermissionDecision? {
        if (host.isBlank() || kinds.isEmpty()) return null
        val decisions = try {
            kinds.map { browserRepo.permissionFor(profileId, host, it) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unreadable: Exception) {
            return null
        }
        return when {
            decisions.any { it == PermissionDecision.BLOCK } -> PermissionDecision.BLOCK
            decisions.all { it == PermissionDecision.ALLOW } -> PermissionDecision.ALLOW
            else -> null
        }
    }

    /** Refuses a request still on screen so a replacement cannot orphan it. */
    private fun retirePendingPermission() {
        val previous = pendingPermission ?: return
        pendingPermission = null
        previous.request.deny()
    }

    private fun retirePendingGeolocation() {
        val previous = pendingGeolocation ?: return
        pendingGeolocation = null
        previous.callback.invoke(previous.origin, false, false)
    }

    /**
     * Retires both pending requests with a refusal when the active page
     * navigates. They were asked for by the document being left: answering
     * them afterwards would hand a capability to a page that is gone, and
     * the outgoing document's promise is already moot. Refused rather than
     * dropped, for the reason above.
     */
    private fun invalidateWebPermissionRequests() {
        denyPendingPermission()
        respondGeolocation(false)
    }

    /** A one-line message for the UI's snackbar (public entry point). */
    fun showMessage(message: String) = emitMessage(message)

    init {
        webViewClient = RoomWebViewClient(profile, graph.filterEngine, clientCallbacks)
        viewModelScope.launch { initialize() }
        observeFlows()
    }

    private suspend fun initialize() {
        try {
            // A pending network decision survives process death: arm the gate
            // BEFORE anything — the tab restore included — can load a URL.
            val pendingDecision = appState.pendingNetDecision()
            if (pendingDecision != null) {
                if (pendingDecision.profileId == profileId.value) {
                    pendingNetWarning = pendingDecision
                    networkGate.value = true
                } else {
                    // Stale flag from another profile's context (a switch
                    // raced the decision) — this profile is not gated by it.
                    appState.clearPendingNetDecision()
                }
            }
            profile = graph.profileRepo.getProfile(profileId) ?: profile
            themeSpec = BuiltInThemes.resolveOrDefault(profile.themeJson)
            webViewClient = RoomWebViewClient(profile, graph.filterEngine, clientCallbacks)
            globalSettings = appState.globalSettingsSnapshot()
            httpClient = dnsMonitor.apply(globalSettings, profile)
            agent.updateClient(httpClient)
            agent.start()
            downloadEngine = DownloadEngine(getApplication(), browserRepo, httpClient, profileId)
            downloadEngine.ensureChannels()
            // Re-queue anything the previous engine left mid-flight and restart
            // the queue — without this a download interrupted by a profile
            // switch stayed at RUNNING forever and blocked every later one.
            downloadEngine.recover()
            viewModelScope.launch {
                downloadEngine.speeds.collect { downloadSpeeds = it }
            }
            appState.setActiveProfile(profileId.value)
            graph.profileRepo.touch(profileId, System.currentTimeMillis())
            loadSiteSettingsSnapshot()

            // Wallet engine binding — DEFERRED off the startup path. The
            // first wallet access class-loads the whole crypto stack
            // (web3j/bouncycastle/jackson ≈ 10 MB of dex); doing that inside
            // initialize() stalled the ':browser' process's first frames on
            // low-end devices (the CI emulator never composed the browser
            // chrome within 40 s). Binding 2.5 s after restore, on a
            // background dispatcher, keeps engine boot at pre-wallet speed;
            // the classes load while the user reads the start page. A dApp
            // request in that window settles DISCONNECTED once and the page
            // retries — pages always do.
            // NOT unbound in onCleared: the ':browser' process is
            // one-profile-per-process and dies with this ViewModel's
            // activity — an unbind here could yank the session out from
            // under WalletActivity, which shares the same engine instance.
            viewModelScope.launch(Dispatchers.Default) {
                delay(2_500)
                // Heavy class load (web3j/BC/jackson) happens HERE, on a
                // worker thread; bind()'s own state mutations and the event
                // collectors stay on Main (the engine is main-thread
                // confined by contract). observeWalletEvents is started
                // HERE — reading the engine's flows at startup would trigger
                // the same lazy class load the delay exists to avoid.
                val engine = graph.walletEngine
                withContext(Dispatchers.Main) {
                    engine.bind(profileId)
                    observeWalletEvents()
                }
            }

            // Restore persisted tabs: the first tab's engine is built RIGHT HERE
            // via selectTab (lazily, with a reload) — previously the restored
            // tab showed its URL in the omnibox but never got a live engine,
            // leaving a blank surface until the next navigation.
            val open = browserRepo.openTabs(profileId)
            tabManager.restore(open)
            tabs = open
            // The ACTIVE tab is persisted per profile via last_viewed_at
            // (touched on every selectTab): restore the most-recently-viewed
            // open tab, falling back to the first one in display order.
            val resumeTab = open.maxByOrNull { it.lastViewedAt } ?: open.firstOrNull()
            activeTabId = resumeTab?.id
            if (resumeTab != null) {
                selectTab(resumeTab.id)
            }
            if (open.isEmpty()) {
                openNewTab("about:home", isPrivate = false)
            }

            val profiles = graph.profileRepo.profiles()
            allProfiles = profiles
            networkIdentity.checkOnOpen(httpClient, profile, globalSettings, profiles)
            refreshStats()
            refreshBookmarks()
        } finally {
            // Release the navigation gate even on failure — a broken restore
            // must not leave incoming URLs waiting forever.
            restored.value = true
        }
    }

    suspend fun refreshAllProfiles() {
        allProfiles = graph.profileRepo.profiles()
    }

    private fun observeFlows() {
        viewModelScope.launch {
            browserRepo.observeTabs(profileId).collect { list -> tabs = list }
        }
        viewModelScope.launch {
            // Live per-profile theming: the Theme Studio (default process)
            // writes profiles.theme_json; multi-instance invalidation delivers
            // the change here and the whole browser recomposes.
            graph.profileRepo.observeProfile(profileId).collect { p ->
                if (p != null) {
                    val settingsChanged = p.settings != profile.settings
                    profile = p
                    themeSpec = BuiltInThemes.resolveOrDefault(p.themeJson)
                    if (settingsChanged) {
                        webViewClient = RoomWebViewClient(profile, graph.filterEngine, clientCallbacks)
                        reconfigureAllWebViews()
                    }
                }
            }
        }
        viewModelScope.launch {
            browserRepo.observeBookmarks(profileId).collect { bookmarks = it }
        }
        viewModelScope.launch {
            browserRepo.observeRecentHistory(profileId).collect { recentHistory = it }
        }
        viewModelScope.launch {
            browserRepo.observeDownloads(profileId).collect { downloads = it }
        }
        viewModelScope.launch {
            appState.globalSettings.collect { globalSettings = it }
        }
        viewModelScope.launch {
            networkIdentity.netState.collect { state ->
                netState = state
                if (state is NetworkIdentity.NetState.Conflict) {
                    armNetworkWarning(state)
                }
            }
        }
        viewModelScope.launch {
            dnsMonitor.state.collect { dnsState = it }
        }
    }

    // ---------- Navigation ----------

    fun onOmniBoxInput(input: String) {
        val settings = profileSettings()
        val (_, url) = UrlIntelligence.classify(input, settings.searchEngineId)
        if (url.isNotBlank()) loadUrl(url)
    }

    fun loadUrl(url: String, newTab: Boolean = false, isPrivate: Boolean = false) {
        if (url == "about:home" && !newTab) {
            // SAME-tab return to the start page. newTab=true must NEVER take
            // this branch: it used to reset the CURRENT tab's page state
            // instead of opening a tab — the "New Tab overwrote my tab" bug.
            pageState = PageState(isPrivate = isPrivate)
            return
        }
        viewModelScope.launch {
            // Serialize against the persisted-tab restore (an incoming
            // initial/QR/share URL must land AFTER the restore chose the
            // active tab, never before) and against a pending network
            // decision: while the warning stands, the suspended coroutine
            // IS the queue — it resumes and loads the moment the user
            // decides (see resolveNetworkWarning).
            restored.first { it }
            networkGate.first { !it }
            val targetId = activeTabId
            if (newTab || targetId == null) {
                openNewTab(url, isPrivate)
            } else {
                val webView = activeWebView ?: createWebView().also { engine ->
                    activeWebView = engine
                    // The engine belongs to THIS tab: its session must exist
                    // before attaching, or the engine goes untracked (leak +
                    // state loss on reselect).
                    tabs.firstOrNull { it.id == targetId }?.let { tabManager.ensureSession(it) }
                    tabManager.attachWebView(targetId, engine)
                }
                // CI 36833914913 (Tabs + Wallet, both omnibox loads on a
                // start-page tab): the navigation used to start on an engine
                // that was NOT yet attached to the window — pageState stayed
                // on the homepage until onPageStarted arrived (~400 ms
                // later, AFTER the navigation had already begun on the
                // parentless view), so WebViewHost was not composed at all.
                // On the WebView 83 stack such a navigation wedges
                // permanently when the view attaches mid-flight: renderer
                // spawned, onPageStarted fired, then total silence — no
                // commit, no finish, no error, the page never rendered. The
                // openNewTab path never hits this because it flips pageState
                // BEFORE the engine exists (CI-green via
                // BrowserNavigationE2eTest). Mirror that proven ordering
                // here, and start the load through [runWhenAttached] so the
                // navigation ALWAYS begins on an attached, laid-out view.
                pageState = pageState.copy(
                    url = url,
                    title = "",
                    loading = true,
                    progress = 5,
                    isHomepage = false
                )
                pageError = null
                Log.d(NAV_TAG, "vm=$navId loadUrl same-tab url=$url attached=${webView.parent != null}")
                runWhenAttached(webView) { webView.loadUrl(url) }
            }
        }
    }

    fun goBack() { activeWebView?.goBack() }
    fun goForward() { activeWebView?.goForward() }
    fun reload() { activeWebView?.reload() }
    fun stopLoading() { activeWebView?.stopLoading() }

    /**
     * Returns the active tab to the start page (about:home) — the "Back to
     * start page" action of the exit-confirmation dialog. The tab's WebView
     * is destroyed (not reused) so the NEXT navigation starts with a clean
     * history instead of secretly back-stepping into the abandoned page.
     */
    fun goHome() {
        Log.d(NAV_TAG, "vm=$navId goHome")
        destroyActiveWebView()
        pageState = PageState(isPrivate = pageState.isPrivate)
        pageError = null
        val id = activeTabId
        if (id != null) {
            viewModelScope.launch {
                val tab = browserRepo.tab(id) ?: return@launch
                browserRepo.updateTab(tab.copy(url = "about:home", title = "", lastViewedAt = System.currentTimeMillis()))
            }
        }
    }

    /**
     * Leaves fullscreen (custom-view) media mode. Called by the system Back
     * handler so the first Back press exits fullscreen video instead of
     * killing the engine activity.
     */
    fun exitFullscreen() {
        if (customView != null) {
            customView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
        }
    }

    // ---------- Tabs ----------

    suspend fun openNewTab(url: String = "about:home", isPrivate: Boolean = false) {
        val entity = browserRepo.newTab(profileId, url = url, title = "", isPrivate = isPrivate)
        tabs = browserRepo.openTabs(profileId)
        activeTabId = entity.id
        pageState = pageState.copy(
            isPrivate = isPrivate,
            url = url,
            title = "",
            isHomepage = url == "about:home",
            loading = url != "about:home",
            // A brand-new tab starts with a clean history — the previous
            // tab's back/forward state must NOT leak into it.
            canGoBack = false,
            canGoForward = false
        )
        // The previous tab's failure dies with the previous tab. A page error
        // renders ABOVE the page surface (BrowserContent's `when` tests it
        // first), so one left standing here covers the tab that follows: the
        // restore loads a persisted tab whose server is gone, that load fails,
        // and the tab the user actually asked for finishes fine BEHIND an
        // error it never caused. Observed on CI 36949239247 — /media loaded
        // (onPageFinished, title=localhost:57991/media) while the screen still
        // showed /geo's "No Internet". Clearing it here is what makes the
        // error belong to the tab that produced it.
        pageError = null
        if (url != "about:home") {
            // PER-TAB WebView: every tab gets its OWN engine instance so
            // web history stays tab-scoped (no cross-tab back-stepping) and
            // switching tabs never reloads a still-live page. The session is
            // created BEFORE the attach — an untracked engine was why young
            // tabs reloaded (and leaked) instead of switching cleanly.
            val webView = createWebView()
            activeWebView = webView
            tabManager.ensureSession(entity)
            tabManager.attachWebView(entity.id, webView)
            // Nothing may load while a network decision is pending; the
            // load fires the moment the user decides.
            networkGate.first { !it }
            Log.d(NAV_TAG, "vm=$navId openNewTab url=$url attached=${webView.parent != null}")
            runWhenAttached(webView) { webView.loadUrl(url) }
        } else {
            // The previous tab keeps its engine alive in its OWN session;
            // the new homepage tab simply has no engine of its own.
            activeWebView = null
        }
        evictStaleWebViews(entity.id)
    }

    // ------------------------------------------------------ agent tab binding

    /**
     * The live engine of [id]'s tab, or null when that tab holds none — the
     * start page, an LRU-evicted background tab, or a tab since closed.
     *
     * The agent resolves its engine HERE and not through [activeWebView]. A
     * turn is started on one tab, and every tool of that turn belongs to that
     * tab; reading "whatever is on screen" is how the rest of a turn silently
     * retargets the moment the user switches away from it.
     */
    fun tabWebView(id: String): WebView? = tabManager.get(id)?.webView

    /** Whether [id] is the tab the user is currently looking at. */
    fun isActiveTab(id: String): Boolean = id == activeTabId

    /** Whether [id] is still an open tab of this profile. */
    fun tabExists(id: String): Boolean = tabs.any { it.id == id }

    /**
     * Pins [id] against LRU eviction, or clears the pin when null.
     *
     * [MAX_LIVE_WEBVIEWS] is a memory budget and a background tab is its
     * first casualty — which is exactly what an agent's tab is while the
     * agent works in the background and the user browses elsewhere. Evicting
     * it mid-turn destroys the page the turn is reasoning about, so the
     * budget yields instead: one tab is held above it until the turn ends.
     * The budget is a bound, not a promise, and a turn is worth one engine.
     */
    fun pinTabForAgent(id: String?) {
        agentTabId = id
    }

    fun selectTab(id: String) {
        if (id == activeTabId && activeWebView != null) return
        Log.d(NAV_TAG, "vm=$navId selectTab id=$id same=${id == activeTabId}")
        activeTabId = id
        val tab = tabs.firstOrNull { it.id == id } ?: return
        pageState = pageState.copy(
            url = tab.url,
            title = tab.title,
            isPrivate = tab.isPrivate,
            isHomepage = tab.url == "about:home",
            loading = false,
            desktopMode = false
        )
        // Same reason as openNewTab: the error surface belongs to the tab that
        // produced it. A tab whose own engine then fails re-reports through
        // onReceivedError, so nothing is hidden by clearing it here.
        pageError = null
        // Every selected tab has a session — the engine's lifetime owner.
        tabManager.ensureSession(tab)
        if (tab.url == "about:home") {
            // Homepage tabs keep no live engine: pageState above already
            // shows the start page; drop any stale engine the session may
            // still hold so switching back never resurrects a dead page.
            tabManager.get(id)?.webView?.let { destroyWebViewQuiet(it) }
            activeWebView = null
            pageState = pageState.copy(canGoBack = false, canGoForward = false)
        } else {
            // Reuse the tab's OWN WebView when it is still alive (instant,
            // state-preserving switch); lazily create one only for tabs that
            // never had — or were LRU-evicted from — a live engine, restoring
            // the saved back/forward bundle when present (entity-URL reload
            // as the fallback). While a network decision is pending, no
            // engine is created at all: nothing may load.
            val webView = engineFor(tab)
            if (webView != null) {
                activeWebView = webView
                // History state belongs to the selected tab's engine.
                pageState = pageState.copy(canGoBack = webView.canGoBack(), canGoForward = webView.canGoForward())
            } else {
                activeWebView = null
                pageState = pageState.copy(canGoBack = false, canGoForward = false)
            }
        }
        // Persist the ACTIVE tab per profile: initialize() restores the
        // open tab with the max last_viewed_at (fallback: first).
        viewModelScope.launch { browserRepo.touchTab(id, System.currentTimeMillis()) }
        applyCurrentSiteSettings()
        evictStaleWebViews(id)
    }

    /**
     * The tab's own live engine when it has one; otherwise a FRESH engine
     * created now — restored from the session's saved back/forward bundle
     * when present (LRU eviction / close), falling back to a reload of the
     * entity URL. Returns null while a network decision is pending.
     */
    private fun engineFor(tab: TabEntity): WebView? {
        val live = tabManager.get(tab.id)?.webView
        if (live != null) return live
        if (networkGate.value) return null
        val webView = createWebView()
        tabManager.ensureSession(tab)
        tabManager.attachWebView(tab.id, webView)
        val saved = tabManager.engineState(tab.id)
        Log.d(NAV_TAG, "vm=$navId engineFor tab=${tab.id} url=${tab.url} hasSaved=${saved != null}")
        // The restore/fallback navigation must also start on an attached
        // view (see runWhenAttached — same WebView-83 detached-load wedge
        // as the omnibox path).
        runWhenAttached(webView) {
            var restored = false
            if (saved != null) {
                runCatching { webView.restoreState(saved) }
                restored = webView.copyBackForwardList().size > 0
            }
            if (!restored) webView.loadUrl(tab.url)
        }
        return webView
    }

    fun closeTab(id: String) {
        viewModelScope.launch {
            // Destroy THIS tab's engine before dropping the session — its
            // back/forward state is captured first so reopening the tab
            // restores the page (and its history) instead of a bare reload.
            tabManager.get(id)?.webView?.let { webView ->
                saveEngineStateBeforeDestroy(id, webView)
                destroyWebViewQuiet(webView)
            }
            tabManager.remove(id)
            browserRepo.closeTab(id)
            val remaining = browserRepo.openTabs(profileId)
            tabs = remaining
            if (activeTabId == id) {
                activeWebView = null
                // Activate the most-recently-viewed remaining tab of THIS
                // profile (last_viewed_at), not just the last in position.
                val next = remaining.maxByOrNull { it.lastViewedAt }
                activeTabId = next?.id
                if (next != null) selectTab(next.id) else pageState = PageState()
            }
            if (remaining.none { it.isPrivate }) {
                ProfileEngine.clearSessionArtifacts(getApplication())
            }
        }
    }

    fun reopenClosedTab() {
        viewModelScope.launch {
            val closed = browserRepo.recentlyClosed(profileId).firstOrNull() ?: return@launch
            browserRepo.reopenTab(closed.id)
            tabs = browserRepo.openTabs(profileId)
            selectTab(closed.id)
        }
    }

    fun duplicateTab() {
        val tab = tabs.firstOrNull { it.id == activeTabId } ?: return
        viewModelScope.launch { openNewTab(tab.url, tab.isPrivate) }
    }

    /** onlyLeft: null = all others, true = left, false = right */
    fun closeOtherTabs(onlyLeft: Boolean? = null) {
        val id = activeTabId ?: return
        viewModelScope.launch {
            val open = browserRepo.openTabs(profileId)
            val activePos = open.firstOrNull { it.id == id }?.position ?: 0
            open.filter { tab ->
                when (onlyLeft) {
                    null -> tab.id != id
                    true -> tab.position < activePos
                    else -> tab.position > activePos
                }
            }.forEach { tab ->
                // Per-tab engines: release each closed tab's engine + session,
                // not just its database row — with the history bundle saved
                // first (a reopened tab gets its page back).
                tabManager.get(tab.id)?.webView?.let { webView ->
                    saveEngineStateBeforeDestroy(tab.id, webView)
                    destroyWebViewQuiet(webView)
                }
                tabManager.remove(tab.id)
                browserRepo.closeTab(tab.id)
            }
            tabs = browserRepo.openTabs(profileId)
        }
    }

    fun moveTab(id: String, position: Int) {
        viewModelScope.launch {
            browserRepo.moveTab(id, position)
            tabs = browserRepo.openTabs(profileId)
        }
    }

    fun groupTab(id: String, group: String?) {
        viewModelScope.launch {
            browserRepo.groupTab(id, group)
            tabs = browserRepo.openTabs(profileId)
        }
    }

    fun pinTab(id: String) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            browserRepo.pinTab(id, !tab.isPinned)
            tabs = browserRepo.openTabs(profileId)
        }
    }

    fun startPrivateTab() {
        viewModelScope.launch { openNewTab("about:home", isPrivate = true) }
    }

    private fun createWebView(): WebView {
        val webView = ProfileEngine.createWebView(getApplication(), profile)
        webView.webViewClient = webViewClient
        // ONE CHROME CLIENT PER ENGINE, bound to the engine it serves. The two
        // permission callbacks (onPermissionRequest,
        // onGeolocationPermissionsShowPrompt) carry no WebView of their own —
        // the bound engine is the only handle on the tab that asked, and
        // without it the host has to refuse, which is what it did: an unbound
        // client made every camera, microphone and location request a silent
        // denial. Rebuilding the client on a profile change therefore does
        // nothing for engines that already exist; this is where they get one.
        webView.webChromeClient = RoomWebChromeClient(profile, chromeCallbacks, webView)
        // Password-manager page bridge: page JS sees window.RoomVault (the
        // document-start detection script comes from ProfileEngine.configure).
        // Every call is host-validated against THIS WebView's URL inside
        // RoomVaultBridge before it reaches [vaultCallbacks].
        webView.addJavascriptInterface(
            RoomVaultBridge(webView, vaultCallbacks),
            RoomVaultBridge.JS_INTERFACE_NAME
        )
        // Wallet dApp bridge: page JS sees window.ethereum / window.solana /
        // window.aptos / window.suiWallet / window.tronLink (the provider
        // script itself comes from ProfileEngine.configure's document-start
        // install). Every call is host-validated against THIS WebView's URL
        // inside WalletBridge before anything reaches the engine, and the
        // engine settles each request through the confirmation UI.
        val walletBridge = com.roombrowser.browser.wallet.dapp.WalletBridge(
            engineProvider = { graph.walletEngine },
            activeNetworkProvider = { chain ->
                graph.walletEngine.activeNetworks.value[chain]
            },
            webViewRef = java.lang.ref.WeakReference(webView)
        )
        walletBridges[webView] = walletBridge
        webView.addJavascriptInterface(
            walletBridge,
            com.roombrowser.browser.wallet.dapp.WalletBridge.JS_INTERFACE_NAME
        )
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val name = com.roombrowser.browser.engine.DownloadEngine.guessFileName(url, contentDisposition, mimeType)
            // The WebView's own UA, not a fresh one: the download must present
            // the same device identity as the page that linked to it.
            download(url, name, mimeType, userAgent)
        }
        return webView
    }

    // ---------- Per-tab WebView lifecycle ----------

    /** Destroys the ACTIVE tab's engine (fresh history on next navigation). */
    private fun destroyActiveWebView() {
        val webView = activeWebView ?: return
        activeWebView = null
        destroyWebViewQuiet(webView)
    }

    // ---------- Deferred engine actions (attach-ordered navigation) --------

    /**
     * Actions (navigation starts, session restores) queued for engines not
     * yet attached to the window, keyed by the engine itself. Consumed by
     * [consumePendingActionFor] the moment WebViewHost attaches the engine;
     * dropped by [destroyWebViewQuiet] when the engine dies.
     */
    private val pendingEngineActions = mutableMapOf<WebView, () -> Unit>()

    /**
     * Runs [action] on [webView] now when the view is already attached to
     * the window hierarchy, and DEFERS it until the first attach otherwise.
     *
     * WHY THIS EXISTS (CI 36833914913, Tabs + Wallet e2e): a navigation
     * started on a WebView that has no parent — the omnibox path for a
     * start-page tab created the engine and called loadUrl while
     * pageState.isHomepage was still true, so WebViewHost was not composed
     * and the engine stayed parentless — wedges permanently on the WebView
     * 83 stack once the view attaches mid-flight: the renderer spawns,
     * onPageStarted fires, and then the navigation never commits — no
     * finish, no error, an empty surface forever. Every engine-creating
     * path therefore queues its navigation here, and WebViewHost's update
     * block consumes it right after frame.addView: the navigation always
     * begins on an attached, laid-out view. The consume runs through
     * webView.post, so the attach traversal (measure/layout/
     * onAttachedToWindow) completes before the load starts.
     */
    private fun runWhenAttached(webView: WebView, action: () -> Unit) {
        if (webView.parent != null) {
            action()
        } else {
            pendingEngineActions[webView] = action
        }
    }

    /**
     * Called by WebViewHost right after it attached [webView]: fires the
     * navigation/restore queued for this engine, if any.
     */
    fun consumePendingActionFor(webView: WebView) {
        pendingEngineActions.remove(webView)?.let { action ->
            // No size here. This runs from the AndroidView `update` callback,
            // i.e. BEFORE the first layout pass, so it reports 0x0 for every
            // engine — including the ones whose pages render perfectly. That
            // measurement is taken at onPageCommitVisible instead, which is
            // after layout and therefore means something.
            Log.d(NAV_TAG, "vm=$navId deferred engine action fired (attached)")
            webView.post(action)
        }
    }

    /** Detaches [webView] from its session, view tree and the renderer —
     *  never throws, safe for already-released engines. */
    private fun destroyWebViewQuiet(webView: WebView) {
        // A load deferred for this engine can never fire anymore — drop it
        // so a destroyed view is never asked to navigate.
        pendingEngineActions.remove(webView)
        // Only the ACTIVE engine is ever allowed to raise a credential
        // challenge, so a pending one belongs to this engine when this engine
        // is the active one. Cancelling it settles the handler while the
        // WebView is still alive and takes the dialog down with it; leaving it
        // would strand a prompt over a tab that no longer exists.
        if (webView === activeWebView && pendingHttpAuth != null) dismissHttpAuth()
        // The dApp bridge must go FIRST, while the WebView is still intact.
        // A WeakHashMap entry is not enough to release it: an in-flight relay
        // coroutine is a strong reference to the bridge, so without this the
        // bridge of every closed or LRU-evicted tab outlived its engine —
        // still holding a Handler and its pending-call bookkeeping, and still
        // trying to respond into a destroyed WebView.
        runCatching { walletBridges.remove(webView)?.dispose() }
        tabManager.detachWebView(webView)
        runCatching { webView.stopLoading() }
        runCatching { (webView.parent as? android.view.ViewGroup)?.removeView(webView) }
        runCatching { webView.destroy() }
    }

    /**
     * Live-engine budget: at most [MAX_LIVE_WEBVIEWS] engines stay alive at
     * once (each holds renderer memory). Oldest BACKGROUND tabs are evicted
     * first; their back/forward state is captured into the session BEFORE
     * the destroy, so re-selecting the tab restores its page and history
     * (entity-URL reload only when no bundle exists) — graceful
     * degradation, never a leak, never a lost page.
     */
    private fun evictStaleWebViews(keepId: String) {
        val live = tabManager.liveWebViewSessions()
        if (live.size <= MAX_LIVE_WEBVIEWS) return
        val excess = live.size - MAX_LIVE_WEBVIEWS
        tabManager.lruVictims(keepId)
            // The agent's tab is never a candidate: an in-flight turn owns the
            // page in it, and destroying that engine mid-turn is the turn
            // losing the page it was reading. See [pinTabForAgent].
            .filter { it.id != agentTabId }
            .take(excess)
            .forEach { victim ->
                victim.webView?.let { webView ->
                    saveEngineStateBeforeDestroy(victim.id, webView)
                    destroyWebViewQuiet(webView)
                }
            }
    }

    /**
     * Captures the engine's back/forward state (history stack, scroll and
     * form data as far as WebView allows) under the OWNING tab's id — a
     * bundle can never be restored into a different tab.
     */
    private fun saveEngineStateBeforeDestroy(id: String, webView: WebView) {
        val bundle = Bundle()
        runCatching { webView.saveState(bundle) }
        if (!bundle.isEmpty) tabManager.saveEngineState(id, bundle)
    }

    /** Destroys EVERY live engine (profile switch / final teardown). */
    fun destroyAllWebViews() {
        tabManager.liveWebViewSessions().forEach { session ->
            session.webView?.let { destroyWebViewQuiet(it) }
        }
        activeWebView = null
        // The pin names a tab of the profile being torn down; a leftover pin
        // would hold a dead id and silently exempt a LIVE tab of the next
        // profile from eviction.
        agentTabId = null
    }

    /** Re-applies profile settings (JS, UA, zoom, cookies…) to EVERY live
     *  engine — per-tab engines in the background must not keep stale
     *  settings until they happen to be re-selected. */
    private fun reconfigureAllWebViews() {
        tabManager.liveWebViewSessions().forEach { session ->
            session.webView?.let { ProfileEngine.configure(it, profile) }
        }
    }

    // ---------- WebView lifecycle helpers ----------

    fun captureThumbnail() {
        val view = activeWebView ?: return
        val id = activeTabId ?: return
        if (view.width == 0 || view.height == 0) return
        // NEVER draw a WebView synchronously on the main thread. The
        // software-draw path (view.draw(Canvas)) forces a synchronous
        // rasterization round-trip through the renderer, and on
        // WebView-83-class stacks it DEADLOCKS when the compositor has not
        // produced a frame for the view yet — CI 36842626140 proved the
        // whole sequence: onPageFinished -> captureThumbnail ->
        // view.draw() -> the ':browser' process froze forever (no further
        // logcat from app or renderer, queued input events never
        // processed, the a11y tree frozen on the last dispatched frame,
        // the tab-persist coroutine never ran, the DB row never updated).
        // PixelCopy is the asynchronous surface copy: it delivers the
        // frame — or an error — through the callback, and the main thread
        // is NEVER blocked. The View-source overload is API 34 — this
        // project's platform surface offers the API-26 Window overload
        // only, so the copy runs against the ACTIVITY window and is CROPPED
        // to the engine's bounds (getLocationInWindow — the standard
        // compat pattern). A failed thumbnail is purely cosmetic: never
        // worth a crash or a hang.
        val root = view.rootView
        val window = (root.context as? android.app.Activity)?.window ?: return
        if (root.width == 0 || root.height == 0) return
        try {
            val full = createBitmap(root.width, root.height)
            android.view.PixelCopy.request(
                window,
                full,
                { result ->
                    if (result == android.view.PixelCopy.SUCCESS) {
                        runCatching {
                            val loc = IntArray(2)
                            view.getLocationInWindow(loc)
                            val x = loc[0].coerceIn(0, full.width)
                            val y = loc[1].coerceIn(0, full.height)
                            val cropW = view.width.coerceAtMost(full.width - x)
                            val cropH = view.height.coerceAtMost(full.height - y)
                            if (cropW > 0 && cropH > 0) {
                                val cropped = Bitmap.createBitmap(full, x, y, cropW, cropH)
                                tabManager.captureThumbnail(id, cropped)
                            }
                        }
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } catch (_: Exception) {
            // Not attached to a window yet / surface unavailable — skip.
        }
    }

    /**
     * Persists [url] — and [title] when the caller knows it — onto the tab
     * with this id: the tab that OWNS the engine that fired, which is NOT
     * necessarily the active one. A null [title] keeps the stored one (a
     * navigation that just started has no new document title yet).
     * [touch] stamps "last viewed" — a USER action (the active tab), while a
     * background tab merely loading is not one and must not reorder recency.
     */
    private fun persistTab(id: String, url: String, title: String? = null, touch: Boolean = false) {
        // Refresh the reverse index BEFORE the DB write: shouldInterceptRequest
        // reads the owning engine's URL from it to judge sub-resources, and a
        // background tab whose index entry still held the previous page would
        // have its cross-site decisions made against the wrong host. Called
        // here, on the callback's own (main) thread — the launch below may
        // suspend and resume on another.
        tabManager.setPageUrl(id, url)
        viewModelScope.launch {
            val tab = browserRepo.tab(id) ?: return@launch
            browserRepo.updateTab(
                if (touch) {
                    tab.copy(url = url, title = title ?: tab.title, lastViewedAt = System.currentTimeMillis())
                } else {
                    tab.copy(url = url, title = title ?: tab.title)
                }
            )
        }
    }

    /** Synchronous tab persistence used by the profile-switch executor. */
    suspend fun persistActiveTabNow() {
        val id = activeTabId ?: return
        val url = pageState.url
        val title = pageState.title
        val tab = browserRepo.tab(id) ?: return
        browserRepo.updateTab(tab.copy(url = url, title = title, lastViewedAt = System.currentTimeMillis()))
    }

    /** Detach (do not destroy twice) the shared WebView — used on switch. */
    fun detachWebView() {
        activeWebView = null
    }

    /** Release in-memory caches — used by the profile-switch executor. */
    fun clearInMemoryState() {
        tabManager.clear()
        readerContent = null
        pageError = null
        pendingPermission = null
        pendingGeolocation = null
        customView = null
        siteSettingsSnapshot = emptyMap()
    }

    // ---------- Bookmarks / history ----------

    fun toggleBookmark() {
        val url = pageState.url.takeIf { it != "about:home" } ?: return
        viewModelScope.launch {
            if (browserRepo.isBookmarked(profileId, url)) {
                browserRepo.bookmarks(profileId).firstOrNull { it.url == url }?.let {
                    browserRepo.deleteBookmark(it.id)
                }
            } else {
                browserRepo.addBookmark(profileId, url, pageState.title.ifBlank { url })
            }
            refreshBookmarks()
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { browserRepo.deleteBookmark(id); refreshBookmarks() }
    }

    fun deleteHistoryItem(id: Long) {
        viewModelScope.launch { browserRepo.deleteHistoryItem(id) }
    }

    fun clearHistory(since: Long) {
        viewModelScope.launch { browserRepo.clearHistory(profileId, since) }
    }

    // ---------- Downloads ----------

    fun download(url: String, suggestedName: String, mime: String, userAgent: String? = null) {
        if (::downloadEngine.isInitialized) {
            downloadEngine.enqueue(url, suggestedName, mime, userAgent)
        }
    }

    fun pauseDownload(id: Long) { if (::downloadEngine.isInitialized) downloadEngine.pause(id) }
    fun resumeDownload(id: Long) { if (::downloadEngine.isInitialized) downloadEngine.resume(id) }
    fun cancelDownload(id: Long) { if (::downloadEngine.isInitialized) downloadEngine.cancel(id) }
    fun retryDownload(id: Long) { if (::downloadEngine.isInitialized) downloadEngine.retry(id) }
    fun deleteDownload(id: Long) { if (::downloadEngine.isInitialized) downloadEngine.delete(id) }
    fun openDownload(id: Long) { if (::downloadEngine.isInitialized) downloadEngine.open(id) }
    fun shareDownload(id: Long) { if (::downloadEngine.isInitialized) downloadEngine.share(id) }

    // ---------- Shields & site settings ----------

    fun refreshShields() {
        viewModelScope.launch {
            val host = UrlIntelligence.hostOf(pageState.url) ?: return@launch
            val counts = browserRepo.statCountsForHost(profileId, host, System.currentTimeMillis() - DAY_MS)
            val setting = browserRepo.siteSetting(profileId, host)
            val map = counts.associate { it.category to it.count }
            shieldsState = ShieldsState(
                host = host,
                adsBlocked = map[StatCategories.AD] ?: 0,
                trackersBlocked = (map[StatCategories.TRACKER] ?: 0) + (map[StatCategories.CROSS_SITE_TRACKER] ?: 0),
                httpsUpgrades = map[StatCategories.HTTPS_UPGRADE] ?: 0,
                shieldsDisabled = setting?.shieldsDisabled == true
            )
        }
    }

    fun toggleShieldsForSite(disabled: Boolean) {
        val host = shieldsState.host.ifBlank { UrlIntelligence.hostOf(pageState.url) ?: "" }
        if (host.isBlank()) return
        viewModelScope.launch {
            val existing = browserRepo.siteSetting(profileId, host)
            browserRepo.upsertSiteSetting(
                (existing ?: SiteSettingEntity(profileId = profileId.value, host = host)).copy(
                    shieldsDisabled = disabled
                )
            )
            loadSiteSettingsSnapshot()
            refreshShields()
            emitMessage(if (disabled) "Shields off for $host" else "Shields on for $host")
        }
    }

    fun setSiteSetting(transform: (SiteSettingEntity) -> SiteSettingEntity) {
        val host = UrlIntelligence.hostOf(pageState.url) ?: return
        viewModelScope.launch {
            val existing = browserRepo.siteSetting(profileId, host)
                ?: SiteSettingEntity(profileId = profileId.value, host = host)
            browserRepo.upsertSiteSetting(transform(existing))
            loadSiteSettingsSnapshot()
            applyCurrentSiteSettings()
            refreshShields()
        }
    }

    fun clearSiteDataForCurrentSite() {
        val host = UrlIntelligence.hostOf(pageState.url) ?: return
        viewModelScope.launch {
            val webView = activeWebView ?: return@launch
            webView.clearCache(true)
            CookieManager.getInstance().removeSessionCookies(null)
            withContext(Dispatchers.IO) {
                // engine data for this profile dir is wiped; per-site granularity
                // is best-effort on WebView (documented in PROFILE_ISOLATION.md)
                ProfileEngine.clearEngineStorage(getApplication(), profileId)
            }
            emitMessage("Site data cleared for $host")
        }
    }

    fun applyCurrentSiteSettings() {
        val host = UrlIntelligence.hostOf(pageState.url) ?: return
        val setting = siteSettingsSnapshot[host] ?: return
        val webView = activeWebView ?: return
        setting.jsEnabled?.let { webView.settings.javaScriptEnabled = it }
        setting.desktopMode?.let {
            ProfileEngine.applyDesktopMode(webView, profile, it)
            pageState = pageState.copy(desktopMode = it)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(
            webView,
            !(setting.cookiesBlocked ?: profile.settings.blockThirdPartyCookies)
        )
    }

    private fun loadSiteSettingsSnapshot() {
        viewModelScope.launch {
            siteSettingsSnapshot = browserRepo.allSiteSettings(profileId).associateBy { it.host }
        }
    }

    // ---------- Privacy dashboard ----------

    fun refreshStats() {
        viewModelScope.launch {
            val counts = browserRepo.statCounts(profileId, System.currentTimeMillis() - 30 * DAY_MS)
            privacyStats = counts.associate { it.category to it.count }
        }
    }

    private fun recordBlock(host: String, category: String) {
        viewModelScope.launch(Dispatchers.IO) {
            browserRepo.recordBlock(profileId, host, category)
        }
    }

    private fun recordVisit(url: String, title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            browserRepo.recordVisit(profileId, url, title)
        }
    }

    // ---------- Permissions (web engine) ----------

    /**
     * Grants the pending camera/microphone request.
     *
     * `request.resources` is the WebView's OWN array, passed through whole.
     * Granting a subset is not the cautious choice it looks like: a page that
     * asked for camera+microphone and is granted only the microphone gets a
     * getUserMedia({audio,video}) promise that rejects, which it cannot tell
     * apart from a broken device. The sheet says what will be shared and the
     * user answers all of it.
     */
    fun grantPendingPermission() {
        val pending = pendingPermission ?: return
        pendingPermission = null
        pending.request.grant(pending.request.resources)
    }

    /**
     * Refuses the pending camera/microphone request. Dismissal routes here
     * too: a dropped request is not a refusal, it is silence, and silence
     * leaves the page's promise pending forever.
     */
    fun denyPendingPermission() {
        val pending = pendingPermission ?: return
        pendingPermission = null
        pending.request.deny()
    }

    /**
     * The hook a settings screen would write through. NOT called by the
     * permission sheet: that sheet answers one visit and says so, and a
     * durable decision belongs on a screen that can also show and revoke it.
     */
    fun setPermission(kind: PermissionKind, decision: PermissionDecision) {
        val host = UrlIntelligence.hostOf(pageState.url) ?: return
        viewModelScope.launch {
            browserRepo.setPermission(profileId, host, kind, decision)
            emitMessage("${kind.name.lowercase().replaceFirstChar { it.uppercase() }}: $decision for $host")
        }
    }

    /**
     * Settles the pending location request. `retain = false` deliberately:
     * retaining would store the decision in the WebView's own per-origin
     * store, where nothing in Settings could show or revoke it. Every visit
     * asks again, and this answer is the only place a location grant exists.
     */
    fun respondGeolocation(allow: Boolean) {
        val pending = pendingGeolocation ?: return
        pendingGeolocation = null
        pending.callback.invoke(pending.origin, allow, false)
    }

    // ---------- Clear data ----------

    fun clearBrowsingData(
        clearHistory: Boolean,
        clearCookies: Boolean,
        clearCache: Boolean,
        clearSiteData: Boolean,
        clearDownloads: Boolean,
        clearPermissions: Boolean,
        since: Long
    ) {
        viewModelScope.launch {
            if (clearHistory) browserRepo.clearHistory(profileId, since)
            if (clearDownloads) downloads.map { it.id }.forEach { downloadEngine.delete(it) }
            if (clearPermissions) {
                browserRepo.allSiteSettings(profileId).forEach {
                    browserRepo.resetPermissions(profileId, it.host)
                }
            }
            withContext(Dispatchers.Main) {
                if (clearCache) activeWebView?.clearCache(true)
                if (clearCookies || clearSiteData) {
                    ProfileEngine.clearEngineStorage(getApplication(), profileId)
                }
            }
            emitMessage("Browsing data cleared")
            refreshStats()
        }
    }

    // ---------- Find in page ----------

    fun findInPage(query: String) {
        activeWebView?.findAllAsync(query)
    }

    fun findInPageNavigate(forward: Boolean, query: String) {
        val escaped = query.replace("\\", "\\\\").replace("'", "\\'")
        activeWebView?.evaluateJavascript(
            "window.find('$escaped', false, ${!forward}, true)"
        ) { }
    }

    fun clearFindInPage() {
        activeWebView?.clearMatches()
    }

    // ---------- Reader mode ----------

    data class ReaderContent(
        val title: String,
        val byline: String,
        val html: String,
        val url: String
    )

    fun enterReaderMode() {
        val webView = activeWebView ?: return
        val url = pageState.url
        webView.evaluateJavascript(READER_SCRIPT) { result ->
            val json = result?.let { unescapeJson(it) }
            if (json.isNullOrBlank() || json == "null") {
                emitMessage("Reader mode: page could not be simplified")
                return@evaluateJavascript
            }
            runCatching {
                val obj = JSONObject(json)
                readerContent = ReaderContent(
                    title = obj.optString("title"),
                    byline = obj.optString("byline"),
                    html = obj.optString("html"),
                    url = url
                )
            }.onFailure { emitMessage("Reader mode: page could not be simplified") }
        }
    }

    fun exitReaderMode() { readerContent = null }

    private fun unescapeJson(raw: String): String? = runCatching {
        if (raw == "null") return null
        // evaluateJavascript returns a JSON-encoded string
        val arr = JSONArray("[$raw]")
        arr.optString(0)
    }.getOrNull()

    // ---------- Desktop mode ----------

    fun toggleDesktopMode() {
        val webView = activeWebView ?: return
        val newValue = !pageState.desktopMode
        ProfileEngine.applyDesktopMode(webView, profile, newValue)
        pageState = pageState.copy(desktopMode = newValue)
        setSiteSetting { it.copy(desktopMode = newValue) }
        webView.reload()
    }

    // ---------- Settings ----------

    suspend fun updateSettings(newSettings: ProfileSettings) {
        graph.profileManager.updateSettings(profileId, newSettings)
        profile = profile.copy(settings = newSettings)
        reconfigureAllWebViews()
        httpClient = dnsMonitor.apply(globalSettings, profile)
        agent.updateClient(httpClient)
        val profiles = graph.profileRepo.profiles()
        networkIdentity.checkOnOpen(httpClient, profile, globalSettings, profiles)
    }

    suspend fun updateGlobalSettings(newGlobal: BrowserGlobalSettings) {
        appState.saveGlobalSettings(newGlobal)
        globalSettings = newGlobal
        httpClient = dnsMonitor.apply(newGlobal, profile)
        agent.updateClient(httpClient)
    }

    /** Apply a new theme to THIS profile (Theme Studio "Apply"). */
    fun updateTheme(spec: RoomThemeSpec) {
        themeSpec = spec.sanitized()
        viewModelScope.launch {
            graph.profileRepo.updateTheme(profileId, ThemeJson.encode(themeSpec))
        }
    }

    fun profileSettings(): ProfileSettings = profile.settings

    // ---------- Device identity ----------

    /**
     * Point this profile at a device, or null to take its device away.
     *
     * Goes through the profile manager rather than [updateSettings] because
     * assigning a device also clears any UA preset (one identity, one
     * control), and the open pages then have to be reconfigured with the new
     * UA.
     */
    suspend fun setDevice(deviceId: String?) {
        graph.profileManager.setDevice(profileId, deviceId)
        // Re-read rather than patch the local copy: assigning a device also
        // clears the UA fields, and the manager is what decides that.
        profile = graph.profileRepo.getProfile(profileId) ?: profile
        reconfigureAllWebViews()
    }

    /** A device no other profile is presenting as. */
    suspend fun pickFreeDevice(): Device? =
        graph.profileManager.pickFreeDeviceId(profileId)?.let { Devices.find(it) }

    /** Device ids other profiles are already presenting as. */
    suspend fun devicesInUse(): Set<String> = graph.profileManager.devicesInUse(except = profileId)

    // ---------- QR ----------

    fun onQrResult(text: String) {
        val (_, url) = UrlIntelligence.classify(text, profileSettings().searchEngineId)
        if (url.isNotBlank()) loadUrl(url)
    }

    // ---------- Network identity / warning gate ----------

    /**
     * Arms the network-decision gate: the conflict payload is PERSISTED so
     * process death or activity recreation can never bypass the decision —
     * BrowserActivity re-launches NetworkWarningActivity while it stands.
     */
    private suspend fun armNetworkWarning(conflict: NetworkIdentity.NetState.Conflict) {
        if (networkGate.value) return
        val payload = PendingNetDecision(
            profileId = profileId.value,
            ip = conflict.currentIp,
            previousProfileName = conflict.previousProfileName,
            lastSeenAt = conflict.lastSeenAt
        )
        appState.setPendingNetDecision(payload)
        pendingNetWarning = payload
        networkGate.value = true
    }

    /**
     * Applies the user's decision from NetworkWarningActivity: clears the
     * persisted pending state and the gate, then rebuilds the active tab's
     * engine (restoring its saved state). Loads that queued while the gate
     * stood resume on their own — they were suspended on [networkGate].
     */
    fun resolveNetworkWarning(decision: NetworkWarningDecision) {
        viewModelScope.launch {
            when (decision) {
                // Persisted suppression, scoped to THIS profile: this IP
                // never warns again here (IpConflictDetector honors the
                // profile's suppressed IPs) — other profiles keep warning.
                NetworkWarningDecision.SUPPRESS -> networkIdentity.suppressCurrentIp(profileId.value)
                // Session-level acknowledgement (same semantics the old
                // dialog's Continue had).
                else -> networkIdentity.dismissWarning()
            }
            appState.clearPendingNetDecision()
            pendingNetWarning = null
            networkGate.value = false
            // Engines withheld while the gate stood are created now. A
            // queued navigation may already have created one (it resumed
            // the instant the gate flipped) — selectTab then early-returns.
            activeTabId?.let { selectTab(it) }
            if (decision == NetworkWarningDecision.SWITCH) {
                quickSwitcherSignal.value += 1
            }
        }
    }

    /**
     * Fallback for a pending flag whose payload can no longer be decoded:
     * the decision cannot be presented, and holding the gate would brick
     * the profile — clear the stale state instead (the normal path always
     * has a payload; this is the documented corruption valve).
     */
    fun discardUnreadableNetworkWarning() {
        viewModelScope.launch {
            appState.clearPendingNetDecision()
            pendingNetWarning = null
            networkGate.value = false
            emitMessage("Network warning state was unreadable and has been reset")
        }
    }

    /** UI message from outside the ViewModel's own flows (sheets, dialogs). */
    fun postMessage(message: String) {
        viewModelScope.launch { snackbar.emit(message) }
    }

    /** Re-check after a network change / from settings (spec 74.6). A fresh
     *  conflict re-arms the gate and re-launches the warning activity. */
    fun recheckNetwork() {
        viewModelScope.launch {
            val profiles = graph.profileRepo.profiles()
            networkIdentity.recheck(httpClient, profile, globalSettings, profiles)
        }
    }

    // ---------- Quick switcher: create profile ----------

    /** Accent colors cycled through by the quick-create dialog. */
    private val quickCreateColors = longArrayOf(
        0xFF6750A4L, 0xFF2196F3L, 0xFF00897BL, 0xFF43A047L,
        0xFFF4511EL, 0xFFD81B60L, 0xFF5C6BC0L
    )

    /** Default suggestion for the quick-create dialog: first free "Profile N". */
    fun suggestedProfileName(): String {
        val taken = allProfiles.map { it.name.lowercase() }.toSet()
        var n = allProfiles.size + 1
        while ("profile $n" in taken) n++
        return "Profile $n"
    }

    /**
     * Creates a profile from the quick switcher. AppGraph works in the
     * ':browser' process (Room multi-instance invalidation), so the row is
     * visible everywhere immediately. Throws on invalid/duplicate names —
     * the caller owns the failure UX (snackbar + stay).
     */
    suspend fun createProfileFromSwitcher(name: String): Profile {
        val color = quickCreateColors[allProfiles.size % quickCreateColors.size]
        val created = graph.profileManager.create(
            name = name,
            icon = "\uD83D\uDC64",
            colorArgb = color
        )
        allProfiles = graph.profileRepo.profiles()
        return created
    }

    // ---------- Password vault: login autofill + save prompt ----------

    /**
     * Offer-sheet payload: the logins matching the login form the user just
     * focused. Holds decrypted [SavedCredential]s (the sheet itself only ever
     * RENDERS username/title/domain — passwords are passed straight to the
     * page fill and nowhere else).
     *
     * [locked] marks the variant shown when a login field is focused while
     * the vault is locked for this process: [credentials] is then ALWAYS
     * empty — the sheet must not reveal whether anything is stored, let alone
     * what — and the only way forward is the user's own unlock tap
     * ([unlockVaultForOffer]).
     */
    data class VaultOffer(
        val host: String,
        val credentials: List<SavedCredential>,
        val locked: Boolean = false
    )

    /**
     * Save-prompt payload. The reported password exists ONLY in this state
     * (plus the sheet argument) until the user taps Save — never in a log, a
     * cache or any other field — and disappears with the prompt.
     */
    data class VaultSavePrompt(
        val host: String,
        val username: String,
        val password: String
    )

    /** Offer sheet state (rendered by BrowserScreen → VaultOfferSheet). */
    var vaultOffer by mutableStateOf<VaultOffer?>(null)
        private set

    /** Save-prompt sheet state (rendered by BrowserScreen → VaultSaveSheet). */
    var vaultSavePrompt by mutableStateOf<VaultSavePrompt?>(null)
        private set

    /**
     * The process-wide wallet engine (bound to this profile in [initialize],
     * DEFERRED — see there). Exposed for BrowserScreen to render the dApp
     * confirmation sheets. IMPORTANT: composition reads [walletRequests],
     * NOT the engine's own flow — touching `graph.walletEngine` at first
     * composition would lazy-load the whole crypto stack (web3j/BC/jackson)
     * on the UI thread and stall the first frames; this accessor is only
     * safe to call once a pending request exists (post-bind).
     */
    val walletEngine: com.roombrowser.browser.wallet.WalletEngineApi
        get() = graph.walletEngine

    /**
     * Mirror of the engine's pending dApp request queue, populated only
     * after the deferred bind. Starts empty and stays empty for profiles
     * with no wallet activity — reading it in composition never triggers
     * the crypto-stack class load.
     */
    val walletRequests = kotlinx.coroutines.flow.MutableStateFlow<List<com.roombrowser.browser.wallet.DappRequest>>(emptyList())

    /**
     * Push wallet state changes to every live page: chainChanged (EVM hex
     * chainId) when the active network per chain changes, accountsChanged
     * when the EVM account set changes. Collectors are cancel-and-replace
     * (one generation alive — the observeTabCounts lesson) and only emit on
     * an actual CHANGE, so re-binds never spam pages with synthetic events.
     */
    private var walletEventsJob: kotlinx.coroutines.Job? = null

    private fun observeWalletEvents() {
        walletEventsJob?.cancel()
        lastWalletChainIds = emptyMap()
        lastWalletEvmAddresses = emptyList()
        walletEventsJob = viewModelScope.launch {
            // Mirror the pending-request queue for composition (see
            // [walletRequests] — the UI must not touch the engine directly
            // before the bind).
            launch {
                walletEngine.pendingRequests.collect { walletRequests.value = it }
            }
            launch {
                walletEngine.activeNetworks.collect { active ->
                    if (active != lastWalletChainIds) {
                        val previous = lastWalletChainIds
                        lastWalletChainIds = active
                        // The FIRST population stays silent — pages ask for
                        // the current chain themselves via eth_chainId. Any
                        // LATER change emits chainChanged (EVM: hex chainId).
                        if (previous.isNotEmpty()) {
                            active.forEach { (chain, network) ->
                                if (previous[chain]?.chainId != network.chainId &&
                                    chain == com.roombrowser.domain.wallet.model.ChainType.EVM
                                ) {
                                    // The parentheses are the fix, not style:
                                    // `+` binds tighter than `?:`, so
                                    // `"0x" + x?.y ?: z` makes the elvis's left
                                    // operand the whole concatenation — never
                                    // null, so the fallback was unreachable and
                                    // a non-decimal chainId emitted the literal
                                    // string "0xnull" to every connected dApp.
                                    val hex = "0x" + (
                                        network.chainId.toLongOrNull(10)
                                            ?.toString(16)?.lowercase()
                                            ?: network.chainId.removePrefix("0x")
                                        )
                                    emitWalletEvent("chainChanged", "\"$hex\"")
                                }
                            }
                        }
                    }
                }
            }
            launch {
                walletEngine.accounts.collect { accounts ->
                    val evm = accounts
                        .filter { it.chainType == com.roombrowser.domain.wallet.model.ChainType.EVM }
                        .map { it.address }
                    if (evm != lastWalletEvmAddresses) {
                        val previous = lastWalletEvmAddresses
                        lastWalletEvmAddresses = evm
                        // The INITIAL population stays silent — pages ask for
                        // accounts themselves via eth_accounts. Any LATER
                        // change (add/remove account) emits to every page.
                        if (previous.isNotEmpty()) {
                            val jsonArray = evm.joinToString(
                                prefix = "[", separator = ",", postfix = "]"
                            ) { address -> "\"$address\"" }
                            emitWalletEvent("accountsChanged", jsonArray)
                        }
                    }
                }
            }
        }
    }

    /** Relay one EIP-1193 event to every live WebView's wallet bridge. */
    private fun emitWalletEvent(event: String, payloadJson: String) {
        val bridges = walletBridges.values.toList()
        bridges.forEach { it.emitEvent(event, payloadJson) }
    }

    /**
     * Hosts whose offer the user dismissed for the CURRENT page — a
     * re-focused login field must not re-summon a sheet the user just closed.
     * Cleared on every navigation (a fresh page is a fresh question).
     */
    private val dismissedOfferHosts = mutableSetOf<String>()

    /** Wired into every engine in [createWebView] (via RoomVaultBridge). */
    private val vaultCallbacks = object : RoomVaultBridge.Callbacks {
        override fun onCredentialsRequested(webView: WebView, host: String, href: String) {
            handleVaultRequest(webView, host)
        }

        override fun onCredentialReported(
            webView: WebView,
            host: String,
            username: String,
            password: String
        ) {
            handleVaultReport(webView, host, username, password)
        }
    }

    /**
     * The user focused a password field on [webView]. Only the ACTIVE tab's
     * engine may surface UI (a background tab's page cannot).
     *
     * LOCKED VAULT: the vault starts locked in every ':browser' process, so
     * treating "locked" as "say nothing" made the whole flow (open site ->
     * tap the password field -> fill) silently do NOTHING after every process
     * death. The locked answer is now the offer sheet's locked variant: it
     * carries NO credentials and no counts — a locked vault still reveals
     * nothing about what is stored — and its single action is the user's own
     * unlock tap. The biometric gate is never started from here: focusing a
     * login field must never raise a prompt by itself, only the tap may.
     */
    private fun handleVaultRequest(webView: WebView, host: String) {
        if (webView !== activeWebView) return
        if (vaultOffer != null) return
        if (host in dismissedOfferHosts) return
        if (!graph.credentialRepo.isUnlocked.value) {
            vaultOffer = VaultOffer(host, emptyList(), locked = true)
            return
        }
        viewModelScope.launch {
            val matches = runCatching {
                graph.credentialRepo.findForDomain(profileId, host)
            }.getOrNull() ?: return@launch
            // The active tab may have changed while the lookup ran.
            if (webView !== activeWebView || matches.isEmpty()) return@launch
            vaultOffer = VaultOffer(host, matches)
        }
    }

    /**
     * "Unlock" on the LOCKED offer sheet. Runs the UI-owned biometric /
     * device-credential gate (same contract as [savePromptedLogin]: the gate
     * must genuinely have passed before the repo is told), then unlocks this
     * process's vault and re-runs the offer so the now-available logins
     * replace the locked prompt in place.
     *
     * SECURITY: this is strictly user-initiated — nothing else in this class
     * calls it, and a cancelled or failed gate is a no-op ([onFailure] is
     * deliberately empty: the sheet stays locked and the vault stays locked).
     */
    fun unlockVaultForOffer(
        gateProvider: (onSuccess: () -> Unit, onFailure: () -> Unit) -> Unit
    ) {
        val offer = vaultOffer ?: return
        if (!offer.locked) return
        // Positional call: a function-type value cannot take named arguments
        // (K2 prohibits them for function types).
        if (graph.credentialRepo.isUnlocked.value) {
            // Another surface unlocked while this sheet was up — no gate needed.
            rerunVaultOffer(offer.host)
        } else {
            gateProvider(
                {
                    if (!graph.credentialRepo.isUnlocked.value) {
                        graph.credentialRepo.unlock()
                    }
                    rerunVaultOffer(offer.host)
                },
                { /* Stay locked; the sheet keeps its locked state. */ }
            )
        }
    }

    /**
     * Re-runs the offer lookup for [host] after an unlock, settling the
     * EXISTING sheet rather than asking the user to focus the field again.
     * Nothing is shown when the host has no saved logins (an unlocked lookup
     * that finds nothing is a silent, empty answer — same as the original
     * path), and a sheet whose host no longer matches (navigation, dismissal,
     * tab switch) is left alone.
     */
    private fun rerunVaultOffer(host: String) {
        val webView = activeWebView ?: return
        viewModelScope.launch {
            val matches = runCatching {
                graph.credentialRepo.findForDomain(profileId, host)
            }.getOrNull() ?: return@launch
            if (webView !== activeWebView) return@launch
            if (vaultOffer?.host != host) return@launch
            vaultOffer = if (matches.isEmpty()) null else VaultOffer(host, matches)
        }
    }

    /**
     * A login form submitted on the active page. POLICY: the save prompt is
     * allowed to appear while the vault is LOCKED (first-run users have
     * nothing saved yet — the prompt is the discovery path) and the
     * biometric gate runs only when the user actually taps Save. Private
     * tabs persist nothing, so they never prompt. Duplicate suppression
     * (same profile+domain+username AND same password) needs decrypted rows
     * and therefore only runs while unlocked; locked reports skip the
     * comparison and prompt (the check is re-run at Save time).
     */
    private fun handleVaultReport(
        webView: WebView,
        host: String,
        username: String,
        password: String
    ) {
        if (webView !== activeWebView) return
        if (password.isEmpty()) return
        if (pageState.isPrivate) return
        viewModelScope.launch {
            if (graph.credentialRepo.isUnlocked.value) {
                val duplicate = runCatching {
                    graph.credentialRepo.findForDomain(profileId, host)
                }.getOrNull()?.any { it.username == username && it.password == password } == true
                if (duplicate) return@launch
            }
            if (webView !== activeWebView) return@launch
            vaultSavePrompt = VaultSavePrompt(host, username, password)
        }
    }

    /** Offer sheet dismissed (outside tap / Back): same page stays quiet. */
    fun dismissVaultOffer() {
        vaultOffer?.let { dismissedOfferHosts.add(it.host) }
        vaultOffer = null
    }

    /** "Not now" on the save prompt: forget the reported login entirely. */
    fun dismissVaultSavePrompt() {
        vaultSavePrompt = null
    }

    /** Navigation hook (onPageStarted): retires the offer + its suppressions. */
    private fun invalidateVaultOfferOnNavigation() {
        vaultOffer = null
        dismissedOfferHosts.clear()
    }

    /**
     * Fills the picked login into the page that requested it. SECURITY: the
     * active engine's CURRENT url is re-validated against the offer's host
     * right before the values are handed to JS — credentials only ever enter
     * the page that asked for them (the bridge validated the same host family
     * when the request arrived; this closes the focus→pick window against a
     * navigation or tab switch in between). The payload is JSON-quoted —
     * values are never naively interpolated into a JS string.
     */
    fun fillVaultCredential(credential: SavedCredential) {
        val offer = vaultOffer ?: return
        // The locked variant carries no credentials, so there is nothing to
        // fill; refuse it explicitly rather than trusting the sheet to have
        // hidden its rows.
        if (offer.locked) return
        val webView = activeWebView
        val currentHost = webView?.url?.let { UrlIntelligence.hostOf(it) }
        vaultOffer = null
        if (webView == null || currentHost == null) return
        if (!CredentialDomainMatcher.matches(offer.host, currentHost)) return
        val payload = JSONObject()
            .put("u", credential.username)
            .put("p", credential.password)
            .toString()
        webView.evaluateJavascript(
            "window.__roomVaultFill && window.__roomVaultFill(${jsStringLiteral(payload)})",
            null
        )
    }

    /** [value] as a double-quoted JS string literal (JSON quoting rules). */
    private fun jsStringLiteral(value: String): String =
        JSONObject.quote(value)
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")

    /**
     * "Save" on the save-prompt sheet.
     *
     * [gateProvider] runs the biometric / device-credential gate and must
     * invoke exactly one callback — the gate is UI-owned because the
     * ViewModel has no Activity (BrowserScreen lends it its own). On gate
     * success this process's vault is unlocked for the session (repo
     * contract: the gate must have genuinely passed before unlock()) and the
     * login is stored; on failure the user sees "Vault locked — not saved"
     * and nothing is written. The duplicate check is re-run here because the
     * prompt-time check was skipped while locked.
     */
    fun savePromptedLogin(
        gateProvider: (onSuccess: () -> Unit, onFailure: () -> Unit) -> Unit
    ) {
        val prompt = vaultSavePrompt ?: return
        // The sheet is gone the moment Save is tapped — a cancelled biometric
        // prompt must not resurrect it.
        vaultSavePrompt = null
        val commit: () -> Unit = {
            if (!graph.credentialRepo.isUnlocked.value) {
                graph.credentialRepo.unlock()
            }
            viewModelScope.launch {
                val duplicate = runCatching {
                    graph.credentialRepo.findForDomain(profileId, prompt.host)
                }.getOrNull()?.any {
                    it.username == prompt.username && it.password == prompt.password
                } == true
                if (duplicate) {
                    emitMessage("Login already saved")
                    return@launch
                }
                runCatching {
                    graph.credentialRepo.save(
                        profileId = profileId,
                        domain = prompt.host,
                        username = prompt.username,
                        password = prompt.password,
                        title = null
                    )
                }.onSuccess {
                    emitMessage("Login saved for ${prompt.host}")
                }.onFailure {
                    emitMessage("Vault locked — not saved")
                }
            }
        }
        if (graph.credentialRepo.isUnlocked.value) {
            commit()
        } else {
            // Positional call: a function-type value cannot take named
            // arguments (K2 prohibits them for function types).
            gateProvider(
                commit,
                { emitMessage("Vault locked — not saved") }
            )
        }
    }

    // ---------- Search suggestions ----------

    suspend fun fetchSuggestions(query: String): List<String> {
        if (!profileSettings().searchSuggestions || query.isBlank()) return emptyList()
        val engine = com.roombrowser.domain.model.SearchEngines.byId(profileSettings().searchEngineId)
        val template = engine.suggestionUrlTemplate ?: return emptyList()
        val url = template.replace("{query}", java.net.URLEncoder.encode(query, "UTF-8"))
        return withContext(Dispatchers.IO) {
            runCatching {
                httpClient.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList()
                    val body = response.body?.string() ?: return@use emptyList()
                    parseSuggestions(body)
                }
            }.getOrDefault(emptyList())
        }
    }

    private fun parseSuggestions(body: String): List<String> = runCatching {
        when {
            body.trimStart().startsWith("[") -> {
                val arr = JSONArray(body)
                (0 until arr.length()).mapNotNull { i ->
                    (arr.opt(i) as? String) ?: (arr.opt(i) as? JSONArray)?.optString(0)
                }.filter { it.isNotBlank() }
            }
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    private fun refreshBookmarks() {
        viewModelScope.launch { bookmarks = browserRepo.bookmarks(profileId) }
    }

    private fun emitMessage(message: String) {
        viewModelScope.launch { snackbar.emit(message) }
    }

    private fun sslErrorText(error: SslError): String = when (error.primaryError) {
        SslError.SSL_EXPIRED -> "The certificate has expired."
        SslError.SSL_IDMISMATCH -> "The certificate hostname does not match."
        SslError.SSL_NOTYETVALID -> "The certificate is not yet valid."
        SslError.SSL_UNTRUSTED -> "The certificate authority is not trusted."
        SslError.SSL_INVALID -> "A generic certificate error occurred."
        SslError.SSL_DATE_INVALID -> "The certificate date is invalid."
        else -> "The site's certificate could not be verified."
    }

    override fun onCleared() {
        Log.d(NAV_TAG, "vm=$navId onCleared")
        runCatching { agent.shutdown() }
        // Per-tab engines must not outlive the ViewModel's scope.
        runCatching { destroyAllWebViews() }
        if (::downloadEngine.isInitialized) downloadEngine.shutdown()
        super.onCleared()
    }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000

        /** Navigation state-machine log tag — the CI per-test logcat greps
         *  these to reconstruct the exact callback order (see the Tabs/Wallet
         *  e2e forensics). */
        private const val NAV_TAG = "RoomNav"

        /** Live per-tab engine budget — beyond this, oldest background
         *  tabs lose their engine (rebuilt lazily on re-selection). */
        const val MAX_LIVE_WEBVIEWS = 4

        const val READER_SCRIPT = """
            (function(){
              var candidates = document.querySelectorAll('article, main, [role=main], .post, #content, .content');
              var best = null; var bestScore = -1;
              candidates.forEach(function(el){
                var text = el.innerText || '';
                var score = text.length + (el.querySelector('p') ? text.length : 0);
                if (score > bestScore && text.length > 250) { best = el; bestScore = score; }
              });
              if (!best) best = document.body;
              var clone = best.cloneNode(true);
              clone.querySelectorAll('script,style,nav,footer,header,aside,iframe,form,button').forEach(function(n){n.remove();});
              return JSON.stringify({
                title: document.title,
                byline: (document.querySelector('[rel=author],.author,.byline')||{}).innerText || '',
                html: clone.innerHTML
              });
            })()
        """
    }
}
