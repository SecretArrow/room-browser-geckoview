package com.roombrowser.engine.webview

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.UaMode
import com.roombrowser.domain.model.UserAgents
import com.roombrowser.engine.EnginePageScripts
import com.roombrowser.engine.EngineSession
import com.roombrowser.engine.EngineSessionListener
import com.roombrowser.engine.EngineState
import com.roombrowser.engine.HttpAuthResponder
import com.roombrowser.engine.NavigationDecision
import com.roombrowser.engine.PageErrorKind
import com.roombrowser.engine.PermissionResponder
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One `WebView` behind the engine facade.
 *
 * IDENTITY, NOT EQUALITY -- DO NOT ADD `equals`/`hashCode`. The tab manager
 * indexes sessions in an identity map and reads it from background threads.
 * That is only sound while two distinct sessions can never compare equal,
 * which is the property a `WebView` had by inheriting identity equality, and
 * which this class keeps by overriding nothing. An implementation that added
 * value equality would make two live tabs collide in that map, and the failure
 * would appear as one tab's state being applied to another -- far from the
 * cause. The reasoning is carried over verbatim from the WebView edition's tab
 * manager (TabManager.kt:48-67, over `ConcurrentHashMap<WebView, EngineEntry>`),
 * where it was written down the first time.
 *
 * THE URL IS A SECURITY BOUNDARY, NOT A CONVENIENCE. [url] is a `@Volatile`
 * mirror published ONLY from the two callbacks that mean "the main frame
 * COMMITTED a document" -- `doUpdateVisitedHistory` and `onPageFinished` -- and
 * never read back from `WebView.getUrl()` at an arbitrary moment. Those two are
 * what `getUrl()` itself would have returned, which is the value this replaces:
 * `RoomVaultBridge.validatedHost` anchors on `getUrl()` today
 * (WebClients.kt:806-807), and the wallet and vault bridges above decide
 * whether to answer a page from [url], so it has to name the document that is
 * actually live and nothing else. NOTHING is published at navigation start:
 * see `onPageStarted` below for why the start url is deliberately a different
 * signal. `getUrl()` IS still read in exactly one place -- the certificate-frame
 * comparison below -- because that is a comparison of urls the engine itself
 * recorded, not a trust decision.
 *
 * THREADING. `url`, `title`, `progress` and the two history flags are written
 * by engine callbacks (main thread, via the clients below) and read from any
 * thread, hence `@Volatile` on every one of them. Every command is callable
 * from any thread and is marshalled to the main thread by [runOnMain], because
 * EVERY `WebView` method is main-thread only -- unlike GeckoView, which takes
 * its commands from the thread you call them on.
 *
 * WHAT THIS FILE PORTS. `ProfileEngine.configure`/`applyDesktopMode`
 * (ProfileEngine.kt:206-312, 400-427), the `WebViewClient`/`WebChromeClient`
 * surface of `WebClients.kt`, the WebView factory and destroy path of
 * `BrowserViewModel.createWebView`/`destroyWebViewQuiet`
 * (BrowserViewModel.kt:1288-1333, 1398-1419), and
 * `saveEngineStateBeforeDestroy` (BrowserViewModel.kt:1452-1456).
 */
internal class WebViewEngineSession(
    override val id: String,
    context: Context,
    profile: Profile,
    /**
     * Carried because the facade requires it, and used for one thing: see
     * [clearSessionData]. This port cannot do what the GeckoView
     * implementation does with it -- WebView has no per-session storage
     * context to put a session in, so there is no flag here that could give a
     * private tab a private cookie jar. The flag is therefore recorded rather
     * than acted on, and the erase below is gated on it instead of pretending
     * the scope is narrower than it is.
     */
    private val isPrivate: Boolean
) : EngineSession {

    private val webView: WebView = WebView(context)

    @Volatile
    private var listener: EngineSessionListener? = null

    @Volatile
    private var currentUrl: String? = null

    @Volatile
    private var currentTitle: String? = null

    @Volatile
    private var currentProgress: Int = 0

    @Volatile
    private var backAvailable: Boolean = false

    @Volatile
    private var forwardAvailable: Boolean = false

    @Volatile
    private var closed: Boolean = false

    /** The most recent state [saveState] captured. See that member for why. */
    @Volatile
    private var lastState: EngineState? = null

    /**
     * The most recent MAIN-FRAME url this client has seen, which exists for
     * ONE purpose: deciding whether a failing certificate belongs to the page
     * or to a sub-resource. Port of the identically named field in
     * WebClients.kt:302-324, including its `@Volatile` (shouldInterceptRequest
     * runs on a background thread while the SSL callback runs on the UI
     * thread).
     *
     * It is NOT the facade's [url] and must not become it: this one is fed by
     * sub-frame-capable callbacks too, which is exactly what makes it a
     * usable discriminator.
     */
    @Volatile
    private var mainFrameUrl: String? = null

    /** The scripts the app last asked for. Replaced wholesale, never stacked. */
    @Volatile
    private var pageScripts: EnginePageScripts? = null

    // The document-start handlers currently installed, one per script, so a
    // reconfigure REPLACES the previous one instead of stacking a second
    // document-start listener. Port of ProfileEngine's three WeakHashMaps
    // (ProfileEngine.kt:79-95), collapsed to three fields because a session
    // owns exactly one engine and the map's only job was the WebView key.
    private var deviceShimHandler: ScriptHandler? = null
    private var vaultHandler: ScriptHandler? = null
    private var walletHandler: ScriptHandler? = null

    /**
     * The view group this session was last attached to, remembered so the
     * fullscreen surface has somewhere to go. See [onShowCustomView].
     */
    private var host: ViewGroup? = null

    /** The page's fullscreen content, owned by this adapter. See [onShowCustomView]. */
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    override val view: View get() = webView

    override val url: String? get() = currentUrl

    override val title: String? get() = currentTitle

    override val progress: Int get() = currentProgress

    /**
     * A `WebView` has no session handle that can be closed independently of
     * the view, so "open" means exactly "not destroyed by us". The GeckoView
     * edition has two things to ask (`!closed && session.isOpen`); this one
     * has one, and inventing a second would be a distinction with no
     * difference.
     */
    override val isOpen: Boolean get() = !closed

    override val canGoBack: Boolean get() = backAvailable

    override val canGoForward: Boolean get() = forwardAvailable

    override fun setListener(listener: EngineSessionListener?) {
        this.listener = listener
    }

    override fun loadUri(uri: String) = runOnMain {
        if (!closed) runCatching { webView.loadUrl(uri) }
    }

    override fun reload() = runOnMain { if (!closed) runCatching { webView.reload() } }

    override fun stop() = runOnMain { if (!closed) runCatching { webView.stopLoading() } }

    override fun goBack() = runOnMain { if (!closed) runCatching { webView.goBack() } }

    override fun goForward() = runOnMain { if (!closed) runCatching { webView.goForward() } }

    /**
     * DELIBERATELY EMPTY. `android.webkit` exposes no compositor-parking API:
     * `WebView.onPause`/`onResume` are for the long-removed plugin model, are
     * documented as not pausing JavaScript, and calling them on the view the
     * user is looking at is a known way to produce a blank surface. The
     * WebView edition's answer to "a background tab costs too much" is the
     * app's live-engine LRU budget -- destroy the least recently used engine
     * and restore it from its saved state -- which lives above the facade and
     * is untouched by this member. So there is nothing here for a Boolean to
     * do, and doing nothing is the honest implementation rather than a stubbed
     * one.
     */
    override fun setActive(active: Boolean) {
        // Intentionally empty -- see the KDoc above. Not a TODO.
    }

    override fun attachTo(host: ViewGroup) {
        this.host = host
        if (webView.parent !== host) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            host.addView(webView)
        }
        // A page that entered fullscreen before this session had a host has
        // been waiting for one; give it its surface now.
        mountCustomView()
    }

    override fun detach() {
        (webView.parent as? ViewGroup)?.removeView(webView)
    }

    // ---- settings ---------------------------------------------------------

    /**
     * Apply [profile]'s settings to this live session. Port of
     * `ProfileEngine.configure` (ProfileEngine.kt:206-312).
     *
     * EVERY SETTING IS ASSIGNED EXPLICITLY, INCLUDING THE ONES THAT MATCH THE
     * PLATFORM DEFAULT, because this runs again on every settings change. The
     * load-bearing example is the user agent: a null result from
     * `UserAgents.effectiveUserAgent` means "use the engine's own UA" and it
     * must be APPLIED, not skipped -- a `?.let` alone would leave the previous
     * identity installed on every open WebView until the process restarted,
     * which is the bug the app's own comment records. Same rule for
     * `UaMode.DEFAULT` in [applyDesktopMode].
     *
     * THE PAGE SCRIPTS ARE NOT INSTALLED HERE. They arrive through
     * [setPageScripts], because the script TEXT comes from above the facade
     * (the device shim is built from the app's device catalogue, the other two
     * are app constants). What this does do is re-apply whichever set is
     * stored, matching the GeckoView edition's `configure`, which re-sends the
     * scripts it holds.
     */
    internal fun configure(profile: Profile) {
        runOnMain {
            if (closed) return@runOnMain
            val s: WebSettings = webView.settings
            val settings = profile.settings

            // POLICY (user mandate): JavaScript is NEVER disabled by default.
            // The platform default is false -- this is always applied
            // explicitly from ProfileSettings, whose own default is true. Only
            // an explicit per-profile toggle or per-site override may turn it
            // off, never a default path.
            s.javaScriptEnabled = settings.javascriptEnabled
            s.domStorageEnabled = true
            s.databaseEnabled = true
            s.allowFileAccess = false
            s.allowContentAccess = false
            s.allowFileAccessFromFileURLs = false
            s.allowUniversalAccessFromFileURLs = false
            s.setSupportZoom(true)
            s.builtInZoomControls = true
            s.displayZoomControls = false
            s.loadWithOverviewMode = true
            s.useWideViewPort = true
            s.setSupportMultipleWindows(true) // required for popup control
            // Autoplay is an explicit constant, NOT derived from
            // `settings.blockMalicious`: that was a miswire -- blocking
            // malicious sites has nothing to do with whether a page may start
            // playing media, so toggling that one shield silently changed an
            // unrelated behaviour. It is FALSE (autoplay permitted) because
            // that is what the app has actually shipped for every fresh
            // install and every profile that never touched the shield, and
            // because compatibility-first is what this mode means everywhere
            // else in the app.
            s.mediaPlaybackRequiresUserGesture = MEDIA_PLAYBACK_REQUIRES_USER_GESTURE
            s.javaScriptCanOpenWindowsAutomatically = false
            // Mixed content: compatibility mode by default. NEVER_ALLOW blanked
            // real-world sites that still load http sub-resources (legacy CDNs,
            // older image hosts); a user who wants strictness enables "Block
            // mixed content" in settings.
            s.mixedContentMode = if (settings.blockMixedContent) {
                WebSettings.MIXED_CONTENT_NEVER_ALLOW
            } else {
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            // See the note above: the null branch is a RESET and has to be
            // applied. DEFAULT no longer means "the engine's UA verbatim" --
            // the stock string announces a WebView, and video sites in
            // particular serve a degraded player to that identity, so the two
            // WebView markers come off first (UserAgents.webViewNeutralUserAgent).
            val chosen = UserAgents.effectiveUserAgent(settings)
            s.userAgentString = chosen
                ?: UserAgents.webViewNeutralUserAgent(WebViewStockUserAgent.of(s))
            s.textZoom = (settings.fontScale * 100f).toInt().coerceIn(50, 200)

            // Cookies are process-wide on WebView (one CookieManager for the
            // whole process), scoped on disk by the data-directory suffix
            // [WebViewEngineHost.bind] installed.
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, !settings.blockThirdPartyCookies)

            // Android Autofill integration: AUTO participates in the system
            // autofill framework; NO opts the WebView out when the profile
            // disables autofill.
            webView.importantForAutofill =
                if (settings.autofillEnabled) View.IMPORTANT_FOR_AUTOFILL_AUTO
                else View.IMPORTANT_FOR_AUTOFILL_NO
        }
        applyPageScripts()
    }

    /**
     * Desktop-site toggle for this session. Port of
     * `ProfileEngine.applyDesktopMode` (ProfileEngine.kt:400-427).
     *
     * The device shim half of the original is not repeated here: the shim is
     * an [EnginePageScripts] value the app owns, so the app supplies the
     * desktop-mode variant through [setPageScripts]. What IS reproduced is the
     * coupling -- a desktop-mode change makes the installed scripts stale, so
     * they are re-applied exactly as the GeckoView edition does
     * (`GeckoEngineSession.applyDesktopMode`), and as the app did by calling
     * `applyDeviceShim` from both paths.
     */
    internal fun applyDesktopMode(profile: Profile, desktop: Boolean) {
        runOnMain {
            if (closed) return@runOnMain
            val s = webView.settings
            if (desktop) {
                s.userAgentString = UserAgents.desktopModeUserAgent
                s.useWideViewPort = true
                s.loadWithOverviewMode = false
            } else {
                s.useWideViewPort = true
                s.loadWithOverviewMode = true
                when (profile.settings.uaMode) {
                    UaMode.DEFAULT -> s.userAgentString = null
                    else -> UserAgents.effectiveUserAgent(profile.settings)?.let { s.userAgentString = it }
                }
            }
        }
        applyPageScripts()
    }

    // ---- page scripts -----------------------------------------------------

    /**
     * Install the page-world scripts. Port of `ProfileEngine.applyDeviceShim`
     * + `applyVaultScript` + `applyWalletScript`
     * (ProfileEngine.kt:338-398), including the ORDER and the replace-don't-
     * stack rule.
     *
     * WHY DOCUMENT_START AND NOT SOMETHING ELSE: these scripts define globals
     * the page calls and a device shim whose entire purpose is to be installed
     * before the page measures anything. A shim injected after the page's first
     * script is a claim that script already read. `WebViewCompat
     * .addDocumentStartJavaScript` is the only API that gives that, which is
     * why this module depends on `androidx.webkit` at all.
     *
     * HONEST LIMIT, carried over from the app: on a WebView too old to support
     * the feature, the scripts are silently absent -- the native entry points
     * are still exposed but nothing calls them. The guard is the app's, and it
     * sits AFTER the removal of the previous handler so that a device which
     * loses the feature does not keep running a stale script.
     */
    override fun setPageScripts(scripts: EnginePageScripts) {
        pageScripts = scripts
        applyPageScripts()
    }

    private fun applyPageScripts() {
        val scripts = pageScripts ?: return
        runOnMain {
            if (closed) return@runOnMain

            // The DEVICE SHIM goes first, so that anything the later scripts
            // read from the environment is already the claimed one -- the same
            // ordering requirement the GeckoView edition's page-world bridge
            // records.
            deviceShimHandler?.let { previous -> runCatching { previous.remove() } }
            deviceShimHandler = null
            // A null shim is NOT "an empty script": it is the app's own answer
            // for a profile with no device, no screen claim and the default
            // WebRTC policy. The removal above has already happened, so a
            // profile that switches back to no-device LOSES its shim rather
            // than keeping the stale one.
            scripts.deviceShim?.let { shim ->
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    runCatching {
                        deviceShimHandler =
                            WebViewCompat.addDocumentStartJavaScript(webView, shim, setOf("*"))
                    }
                }
            }

            vaultHandler?.let { previous -> runCatching { previous.remove() } }
            vaultHandler = null
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                runCatching {
                    vaultHandler = WebViewCompat.addDocumentStartJavaScript(
                        webView,
                        scripts.vaultBridge,
                        setOf("*")
                    )
                }
            }

            walletHandler?.let { previous -> runCatching { previous.remove() } }
            walletHandler = null
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                runCatching {
                    walletHandler = WebViewCompat.addDocumentStartJavaScript(
                        webView,
                        scripts.walletProvider,
                        setOf("*")
                    )
                }
            }
        }
    }

    // ---- scripting --------------------------------------------------------

    /**
     * The direct replacement for `WebView.evaluateJavascript`.
     *
     * THE CALLBACK RECEIVES THE JSON-ENCODED RESULT, EXACTLY AS THE APP'S
     * CALLERS ALREADY EXPECT: `evaluateJavascript` hands back a string
     * containing JSON, so a JS `null` arrives as the four-character string
     * `"null"` and a JS string arrives quoted. That is NOT the same as this
     * edition's Kotlin null, which means one thing only -- the script could not
     * be delivered at all (the session is closed, or the call threw). The
     * GeckoView edition cannot reproduce the JSON encoding and documents the
     * difference on its side; this edition must not "fix" it, because the
     * app-side callers that unquote a result (`unescapeJson`, the reader-mode
     * and vault paths) depend on it.
     */
    override fun evaluateJs(script: String, callback: ((String?) -> Unit)?) {
        runOnMain {
            if (closed) {
                callback?.invoke(null)
                return@runOnMain
            }
            runCatching {
                webView.evaluateJavascript(script, ValueCallback<String> { value ->
                    callback?.invoke(value)
                })
            }.onFailure {
                callback?.invoke(null)
            }
        }
    }

    // ---- capture and state ------------------------------------------------

    /**
     * Capture the session's rendering, cropped to its own bounds. Port of
     * `BrowserViewModel.captureThumbnail` (BrowserViewModel.kt:1481-1532),
     * including the reason it is not `view.draw(Canvas)`.
     *
     * NEVER DRAW A WEBVIEW SYNCHRONOUSLY ON THE MAIN THREAD. The software-draw
     * path forces a synchronous rasterization round-trip through the renderer
     * and DEADLOCKS on WebView-83-class stacks when the compositor has not
     * produced a frame for the view yet: CI proved the whole sequence --
     * onPageFinished -> captureThumbnail -> view.draw() -> the browser process
     * froze forever. `PixelCopy` is the asynchronous surface copy: it delivers
     * the frame, or an error, through the callback, and the main thread is
     * never blocked. The View-source overload of `PixelCopy.request` is API 34
     * and this project's compile surface offers the API-26 Window overload, so
     * the copy runs against the Activity window and is CROPPED to the engine's
     * bounds.
     *
     * The app simply returned when there was nothing to capture; the facade
     * requires an answer, so every one of those early exits reports null. A
     * failed thumbnail is purely cosmetic and never worth a crash or a hang.
     */
    override fun capturePixels(onResult: (Bitmap?) -> Unit) {
        runOnMain {
            val captured = webView
            if (closed) {
                onResult(null)
                return@runOnMain
            }
            if (captured.width == 0 || captured.height == 0) {
                onResult(null)
                return@runOnMain
            }
            val root = captured.rootView
            val window = (root.context as? Activity)?.window
            if (window == null || root.width == 0 || root.height == 0) {
                onResult(null)
                return@runOnMain
            }
            try {
                val full = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                PixelCopy.request(window, full, { result ->
                    if (result == PixelCopy.SUCCESS) {
                        onResult(
                            runCatching {
                                val loc = IntArray(2)
                                captured.getLocationInWindow(loc)
                                val x = loc[0].coerceIn(0, full.width)
                                val y = loc[1].coerceIn(0, full.height)
                                val cropW = captured.width.coerceAtMost(full.width - x)
                                val cropH = captured.height.coerceAtMost(full.height - y)
                                if (cropW > 0 && cropH > 0) {
                                    Bitmap.createBitmap(full, x, y, cropW, cropH)
                                } else {
                                    null
                                }
                            }.getOrNull()
                        )
                    } else {
                        onResult(null)
                    }
                }, Handler(Looper.getMainLooper()))
            } catch (_: Exception) {
                // Not attached to a window yet / surface unavailable.
                onResult(null)
            }
        }
    }

    /**
     * Capture the engine's back/forward state under an opaque token. Port of
     * `saveEngineStateBeforeDestroy` (BrowserViewModel.kt:1452-1456).
     *
     * WHY IT RETURNS THE LAST CAPTURE RATHER THAN BLOCKING. `WebView.saveState`
     * is main-thread only and synchronous, but this member is callable from any
     * thread. Called ON the main thread -- which is where both of its callers
     * live, the tab manager's pre-destroy capture among them -- it runs inline
     * and returns the state it just took. Called from anywhere else it posts
     * the capture and returns the previous one, which is the same shape the
     * GeckoView edition has (it can only ever return what Gecko flushed last).
     * A caller that keeps the most recent token rather than demanding one on
     * the spot -- which the tab manager already does -- cannot tell the
     * difference.
     *
     * An empty bundle means the engine had nothing to save, and the app
     * deliberately stored nothing in that case rather than a token that would
     * restore to a blank page.
     */
    override fun saveState(): EngineState? {
        runOnMain {
            if (closed) return@runOnMain
            val bundle = Bundle()
            runCatching { webView.saveState(bundle) }
            if (!bundle.isEmpty) lastState = WebViewState(bundle)
        }
        return lastState
    }

    override fun restoreState(state: EngineState): Boolean {
        val bundle = (state as? WebViewState)?.bundle ?: return false
        var restored = false
        runOnMain {
            if (closed) return@runOnMain
            runCatching { webView.restoreState(bundle) }
            // Asked HERE, on the same thread and the same turn as the restore,
            // because that is the only point where the answer is meaningful.
            // `restoreState` populates the back/forward list as part of that
            // call, so the list is already correct on the next line -- this is
            // exactly what the app asked before the facade existed
            // (`copyBackForwardList().size > 0`). The session's own
            // `canGoBack` is NOT a substitute: its backing field is written by
            // the back/forward callbacks, none of which have run yet, so it
            // answers false even for a restore that worked perfectly -- and
            // the caller reads false as "nothing was restored, reload the URL"
            // and throws the restored history away.
            restored = runCatching {
                webView.copyBackForwardList().size > 0
            }.getOrDefault(false)
        }
        return restored
    }

    /**
     * Session-scoped erase: session cookies and form data.
     *
     * NOT THE PROFILE WIPE. [EngineHost.clearBrowsingData] erases everything
     * for the whole profile -- every tab, every site -- and it is the wrong
     * instrument for "this private session is over": using it here would sign
     * the user out of every site they are logged into, to close one tab. This
     * is the narrower operation the private-tab promise needs, and it is the
     * exact body the app ran before the facade existed
     * (`ProfileEngine.clearSessionArtifacts`, ProfileEngine.kt:492-497) --
     * removeSessionCookies + flush + clearFormData.
     *
     * A REAL ERASE, not a formality. The private-tab surface tells the user
     * session cookies are gone when the last private tab closes, and the
     * per-site "clear data" action promises the same for one site. An empty
     * implementation makes both of those texts false.
     *
     * Deliberately NOT gated on `closed`. The cookie jar is process-wide and
     * this is precisely what a session being torn down needs; refusing it
     * because the view is already gone would break the promise in the one case
     * it exists for.
     */
    override fun clearSessionData() {
        // Gated on privacy for the same reason the GeckoView implementation
        // is: only a private session's artifacts are about THAT session rather
        // than about the profile. WebView cannot narrow the scope any further
        // -- `removeSessionCookies` is process-wide -- so calling this for a
        // normal session would drop every session cookie the user holds, which
        // is the facade's named failure mode rather than a smaller version of
        // the same operation.
        if (!isPrivate) return
        runOnMain {
            runCatching {
                val cookies = CookieManager.getInstance()
                cookies.removeSessionCookies(null)
                cookies.flush()
            }
            runCatching { WebViewDatabase.getInstance(webView.context).clearFormData() }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runOnMain {
            // The order is the app's (BrowserViewModel.destroyWebViewQuiet,
            // BrowserViewModel.kt:1398-1419): stop the load, take the view out
            // of the hierarchy, and only then destroy it. Destroying an
            // attached WebView is what the platform means by misuse.
            runCatching { webView.stopLoading() }
            runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
            runCatching { webView.destroy() }
        }
        // Everything below releases the session's own references. They are
        // dropped rather than `remove()`d because the WebView is already gone
        // and a document-start handle for a destroyed view has nothing to
        // unregister from.
        deviceShimHandler = null
        vaultHandler = null
        walletHandler = null
        customView = null
        customViewCallback = null
        listener = null
    }

    // ---- the WebViewClient ------------------------------------------------

    private val webViewClient = object : WebViewClient() {

        /**
         * Port of `RoomWebViewClient.shouldInterceptRequest`
         * (WebClients.kt:113-149) and of the main-frame record that precedes
         * its early return (WebClients.kt:120).
         *
         * Runs on a WebView BACKGROUND thread for every sub-resource of every
         * engine, which is why [mainFrameUrl] is volatile and why the facade
         * requires every listener method to be safe off the main thread.
         *
         * The app returned early for the main frame, so this member never
         * blocked the page itself -- the malicious-site rule lives in the
         * navigation callback instead, where it can also stop a navigation
         * rather than a document. The facade passes `isForMainFrame` up rather
         * than making that decision here, so the caller can keep that split.
         */
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val url = request.url.toString()
            if (request.isForMainFrame) mainFrameUrl = url
            val blocked = listener?.onResourceRequest(
                this@WebViewEngineSession,
                url,
                request.isForMainFrame
            ) ?: false
            return if (blocked) blockedResponse() else null
        }

        /**
         * Where the app's navigation policy ran. Port of
         * `RoomWebViewClient.shouldOverrideUrlLoading` (WebClients.kt:151-191),
         * expressed as the facade's [NavigationDecision].
         *
         * The malicious-site refusal is the BLOCK answer; the HTTPS upgrade is
         * the LoadDifferent answer, and the app's `view.post { loadUrl(...) }`
         * is kept exactly -- posting keeps the substitute load out of the
         * callback's own stack, which is how the app avoided re-entering the
         * engine's navigation accounting. The failed-upgrade RETRY is not
         * here: the facade puts that above, where the error report reaches it.
         *
         * The frame url is recorded WITHOUT asking which frame this is, exactly
         * as the app records it (WebClients.kt:183 and :189). The app's own
         * comment here says "Reaching here means the engine loads this url: it
         * is the main frame" -- this callback is not expected to fire for a
         * sub-frame at all -- so the unguarded assignment is intentional and is
         * carried over rather than tightened. The request's frame flag is still
         * passed to the listener, which needs it for the navigation policy.
         */
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val url = request.url.toString()
            val decision = listener?.onNavigationRequest(
                this@WebViewEngineSession,
                url,
                request.isForMainFrame,
                request.hasGesture()
            ) ?: NavigationDecision.Allow
            return when (decision) {
                NavigationDecision.Allow -> {
                    mainFrameUrl = url
                    false
                }
                NavigationDecision.Block -> true
                is NavigationDecision.LoadDifferent -> {
                    mainFrameUrl = decision.url
                    view.post { runCatching { view.loadUrl(decision.url) } }
                    true
                }
            }
        }

        /**
         * A main-frame load began. Port of `RoomWebViewClient.onPageStarted`
         * (WebClients.kt:193-204).
         *
         * WHY IT RECORDS `mainFrameUrl` BUT NOT [url]. This is the app's
         * authoritative main-frame anchor for the certificate-frame comparison
         * (WebClients.kt:198), so it feeds `mainFrameUrl` -- but it is NOT a
         * commit, and the facade's [url] has to be the document that is
         * actually live, because the wallet and vault bridges above decide
         * whether to answer a page from it. `WebView.getUrl()` -- the value
         * this replaces, and the value `RoomVaultBridge.validatedHost` still
         * anchors on (WebClients.kt:806-807) -- names the COMMITTED document,
         * and the facade records that as this edition's known difference
         * against GeckoView's navigation-start report. Publishing here would
         * put [url] one hop ahead of the live document, which is the failure
         * the facade's security note is about.
         *
         * The start URL is still reported, through [EngineSessionListener
         * .onPageStarted] -- which is also what the app's own page-state update
         * uses to move the omnibox at navigation start
         * (BrowserViewModel.kt:381). The two signals are distinct above the
         * facade and stay distinct here.
         *
         * `hasUserGesture` is reported false because this callback carries no
         * such flag. The facade says an engine that cannot supply it for a
         * given navigation reports false, which is the conservative answer --
         * and the navigation POLICY callback, which DOES receive the gesture
         * from the request, is where a caller must make that decision anyway.
         */
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            CookieManager.getInstance().flush()
            mainFrameUrl = url
            currentProgress = 0
            // Early history feedback: the back/forward buttons light up as soon
            // as a navigation begins; doUpdateVisitedHistory re-reports the
            // authoritative state when the entry lands.
            publishHistory(view)
            listener?.onPageStarted(this@WebViewEngineSession, url)
        }

        /**
         * Port of `RoomWebViewClient.onPageFinished` (WebClients.kt:206-211).
         *
         * THE SUCCESS FLAG IS ALWAYS TRUE, and that is not optimism: this
         * callback carries no outcome, and a main-frame failure arrives
         * through [onReceivedError]/[onReceivedSslError] instead, which is
         * where the app has always read failures from. "The load finished and
         * no error was reported" is the only thing this signal can mean.
         *
         * The url is published here as well as on the commit, which the app
         * also did for its own frame record (WebClients.kt:208). It is the
         * backstop for the loads that finish without a history update -- the
         * one case where publishing only on the commit would leave the anchor
         * naming the previous document after a finished load. Publishing the
         * same url twice is idempotent.
         */
        override fun onPageFinished(view: WebView, url: String) {
            CookieManager.getInstance().flush()
            mainFrameUrl = url
            currentUrl = url
            currentProgress = 100
            listener?.onUrlChanged(this@WebViewEngineSession, url, true, false)
            publishHistory(view)
            listener?.onPageFinished(this@WebViewEngineSession, url, true)
        }

        /**
         * THE reliable history signal. Port of
         * `RoomWebViewClient.doUpdateVisitedHistory` (WebClients.kt:245-247).
         *
         * It fires for every history commit -- including the same-document
         * navigations (`history.pushState`) that skip onPageStarted and
         * onPageFinished entirely. Without it the navigation buttons stay grey
         * forever on SPA sites, and the facade's [url] would keep naming the
         * document the SPA was on when it first loaded.
         *
         * This is the PRIMARY publication point for [url]: a history commit is
         * precisely the moment a document becomes the live one, which is the
         * value `getUrl()` would return and the value the bridges above anchor
         * a trust decision on. `onPageFinished` repeats it as a backstop.
         */
        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (url != null) {
                currentUrl = url
                listener?.onUrlChanged(this@WebViewEngineSession, url, true, false)
            }
            publishHistory(view)
        }

        /**
         * A main-frame load failed, in the modern signature. Port of
         * `RoomWebViewClient.onReceivedError(WebView, WebResourceRequest,
         * WebResourceError)` (WebClients.kt:249-270), minus the upgrade
         * fallback, which the facade places above and answers through
         * [onPageError]'s url.
         *
         * WebView's own return type here is void, so the listener's "I handled
         * it" answer cannot be honoured and is deliberately not consulted: the
         * platform's error surface stands, exactly as it does today, and the
         * app's own error UI is what covers it.
         */
        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (!request.isForMainFrame) return
            val failedUrl = request.url.toString()
            listener?.onPageError(
                this@WebViewEngineSession,
                failedUrl,
                kindOf(error.errorCode),
                true,
                // Passed through untouched, and that is not laziness: this
                // edition's codes ARE the space the app's policy is written
                // against (EngineErrorCode), so a mapping here would be the
                // identity function with a chance of being wrong.
                error.errorCode,
                error.description?.toString()
            )
        }

        /**
         * THE LEGACY 4-ARG ERROR CALLBACK, and why it is not redundant.
         *
         * Some WebView stacks report main-frame transport failures -- a
         * TLS handshake reset mid-flight, a plain-http port answering an https
         * attempt -- ONLY through this deprecated signature. The default
         * implementation is a no-op, so dropping it loses the failure
         * entirely: CI proved exactly that for the upgrade path
         * (BrowserNavigationE2eTest: the https attempt started, the back/forward
         * controls lit up, and then nothing -- no error surface, no recovery).
         *
         * The app's own override existed ONLY to consume its upgrade-fallback
         * registry and deliberately did not report, because that registry lived
         * in this class. The registry now lives above the facade, where it is
         * fed by [onPageError], so this callback has to report or the fallback
         * it used to trigger is unreachable on those stacks. A stack that calls
         * BOTH signatures reports the failure twice; the caller's registry is
         * consume-based (it removes the entry it matches), so the second report
         * matches nothing and changes nothing, and the app's own error state is
         * a plain assignment. The alternative -- reporting from one signature
         * only -- silently loses the failure on half the devices.
         *
         * IS IT TOP LEVEL? This signature carries no frame flag, and an
         * earlier version of this method answered `true` unconditionally --
         * which is a claim it cannot support. The app paints a full-screen
         * error surface for a top-level failure, so that answer let a
         * SUB-RESOURCE failure (a blocked image, a font that never loaded)
         * paint a full-screen error over a perfectly healthy page. The
         * comparison below is the honest answer available here: the failure is
         * top-level exactly when it names the main frame being tracked, and
         * anything else -- a different URL, or no URL at all -- is not, which
         * errs toward leaving a good page alone.
         */
        @Deprecated("Deprecated in Java")
        override fun onReceivedError(
            view: WebView,
            errorCode: Int,
            description: String?,
            failingUrl: String?
        ) {
            listener?.onPageError(
                this@WebViewEngineSession,
                failingUrl,
                kindOf(errorCode),
                failingUrl != null && failingUrl == mainFrameUrl,
                errorCode,
                description
            )
        }

        /**
         * Port of `RoomWebViewClient.onReceivedSslError`
         * (WebClients.kt:337-367), minus the upgrade fallback (above the
         * facade now).
         *
         * NEVER PROCEED. There is no `handler.proceed()` anywhere in this file
         * and there must never be one: the user decides, and the facade's
         * [PageErrorKind.CERTIFICATE] is what tells the shared policy to render
         * "this connection is not secure" rather than "the site is
         * unreachable".
         *
         * A certificate that is bad for a SUB-RESOURCE is that resource's
         * problem, not the page's: the handler is cancelled, the resource is
         * not loaded, and nothing is reported -- exactly what the app did, and
         * what stops one expired ad-server certificate from replacing a good
         * page with a full-screen error.
         *
         * The url reported is the one that FAILED (`error.url`), never the
         * committed url: for a main-frame navigation `getUrl()` still names the
         * previous page, and telling the user that address is insecure when it
         * is not is the same class of lie the app's comment records.
         */
        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: SslError
        ) {
            handler.cancel()
            if (!WebViewSslFrames.isMainFrameFailure(error.url, mainFrameUrl, view.url)) return
            listener?.onPageError(
                this@WebViewEngineSession,
                error.url ?: view.url ?: "",
                PageErrorKind.CERTIFICATE,
                true,
                error.primaryError,
                null
            )
        }

        /**
         * Port of `RoomWebViewClient.onReceivedHttpAuthRequest`
         * (WebClients.kt:385-409).
         *
         * An HTTP status error (`onReceivedHttpError`) and the first-frame
         * signal (`onPageCommitVisible`) are deliberately absent: the app used
         * both for its trace only, and the facade has no member for either.
         * They are gaps in the facade, not in this port.
         *
         * The handler is single-shot and the app's `answered` guard is
         * reproduced inside the responder. `useHttpAuthUsernamePassword` is
         * not consulted, exactly as in the app: this browser keeps no WebView
         * credential database, so every challenge is answered by the user.
         */
        override fun onReceivedHttpAuthRequest(
            view: WebView,
            handler: HttpAuthHandler,
            host: String?,
            realm: String?
        ) {
            listener?.onHttpAuthRequest(
                this@WebViewEngineSession,
                host.orEmpty(),
                realm.orEmpty(),
                WebViewHttpAuthResponder(handler)
            )
        }
    }

    // ---- the WebChromeClient ----------------------------------------------

    private val webChromeClient = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            currentProgress = newProgress
            listener?.onProgress(this@WebViewEngineSession, newProgress)
        }

        /**
         * Port of `RoomWebChromeClient.onReceivedTitle` (WebClients.kt:535-537):
         * `title?.let { ... }` -- a NULL TITLE IS DROPPED, neither stored nor
         * reported, so the session keeps the last title it had. That is the
         * app's behaviour and is reproduced rather than "fixed": the facade
         * documents [title] as nullable ("or null when unknown"), and the
         * GeckoView edition does report null, so this is a real difference
         * between the editions rather than a detail. It is left as it is
         * because changing it changes what the shared code above is handed,
         * which is exactly the kind of change that must not slip through
         * unreviewed.
         *
         * NOTE, and it is the app's known limitation rather than a new one:
         * this callback is not guaranteed to be main-frame only, so an iframe
         * title can arrive here. There is no frame flag to check -- the
         * platform does not supply one.
         */
        override fun onReceivedTitle(view: WebView, title: String?) {
            if (title == null) return
            currentTitle = title
            listener?.onTitleChanged(this@WebViewEngineSession, title)
        }

        /**
         * Window creation. Port of `RoomWebChromeClient.onCreateWindow`
         * (WebClients.kt:540-599).
         *
         * THE LISTENER IS ASKED TWICE FOR ONE POPUP, and it has to be, because
         * of how WebView reports a popup: `onCreateWindow` must answer
         * synchronously and is given NO URL, while the URL only exists once
         * the transport WebView it hands back navigates. So the first call
         * carries a null url and is the GATE (the profile's popup policy and
         * the "not the active tab" refusal both live above the facade and
         * cannot be decided here); the second carries the resolved target and
         * is the ACTION. Answering false to the gate refuses the popup and the
         * page simply sees `window.open()` fail, which is what the app's
         * refusal paths did.
         *
         * The gesture flag is carried from the gate into the action so the
         * caller does not have to remember it, and the transport is reaped
         * after [TRANSPORT_REAP_MS] whether or not it ever navigated: the
         * transport only dies when it NAVIGATES, and a `window.open()` with no
         * URL, or one the page keeps as a handle, would otherwise hold a
         * renderer alive for the life of the process.
         *
         * THE SECOND CALL'S RETURN VALUE IS NOT CONSULTED. By the time the
         * target is known the transport has already been handed to the engine
         * and is navigating; there is no longer a way to un-answer the first
         * call, and `shouldOverrideUrlLoading` returning true suppresses the
         * load, so a refusal lands as a popup that never fills in and is reaped
         * a moment later. The app's own refusal for this case was identical --
         * it stopped the load and destroyed the transport.
         *
         * The gate is asked BEFORE the transport exists, which preserves the
         * app's ordering guarantee: "a popup from a background engine is
         * refused outright, BEFORE the transport is built" (WebClients.kt:551-553),
         * so a background page cannot even cost a renderer for its popup.
         */
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            val accepted = listener?.onNewWindowRequest(
                this@WebViewEngineSession,
                isUserGesture
            ) ?: false
            if (!accepted) return false

            val temp = WebView(view.context)
            // Single-shot destroy, shared by the navigation callback and the
            // timeout below. Main-thread only (onCreateWindow, the client
            // callback and postDelayed all run there), so a plain Boolean is
            // the right guard -- destroy() on an already-destroyed WebView
            // throws.
            var reaped = false
            val reap = {
                if (!reaped) {
                    reaped = true
                    runCatching { temp.stopLoading() }
                    runCatching { temp.destroy() }
                }
            }
            temp.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    tempView: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val target = request.url.toString()
                    tempView.stopLoading()
                    tempView.post { reap() }
                    // The gate above already said yes; this is the second half
                    // of the protocol, and it is the ONLY call that carries a
                    // URL. Reporting it as a fresh request -- which is what an
                    // earlier form of this file did, by passing the target back
                    // to onNewWindowRequest -- would ask the gate a question it
                    // has already answered, and a gate that refuses a null URL
                    // reads the answer as "nothing to open".
                    listener?.onNewWindowResolved(
                        this@WebViewEngineSession,
                        target,
                        isUserGesture
                    )
                    return true
                }
            }
            (resultMsg?.obj as? WebView.WebViewTransport)?.webView = temp
            resultMsg?.sendToTarget()
            temp.postDelayed({ reap() }, TRANSPORT_REAP_MS)
            return true
        }

        /**
         * FULLSCREEN: THE ADAPTER OWNS THE SURFACE.
         *
         * The facade says so in as many words -- the WebView edition hands out
         * a separate view to render, GeckoView renders fullscreen inside its
         * own view, and rather than put a view in the facade that one edition
         * has nothing to put in it, the engine adapter keeps the view and the
         * app only reacts to the state. So this class holds the custom view,
         * mounts it over the session's own host (the `ViewGroup` [attachTo]
         * was given) and unmounts it again, and the listener hears only
         * "entered" / "left".
         *
         * `onCustomViewHidden()` MUST be called or WebView keeps rendering
         * fullscreen content into a view nobody is showing, which is a browser
         * with no visible chrome -- the failure the facade's own comment
         * warns about. It is called on the way OUT here, because the app's
         * chrome callback (`RoomWebChromeClient.onHideCustomView`,
         * WebClients.kt:571-575) was the one that used to do it and that
         * callback is now this code.
         */
        override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
            if (customView != null) {
                // One at a time: WebView treats the pair as single-shot, and a
                // second entry would strand the first callback with no way to
                // acknowledge it.
                runCatching { callback.onCustomViewHidden() }
                return
            }
            customView = view
            customViewCallback = callback
            mountCustomView()
            listener?.onFullScreen(this@WebViewEngineSession, true)
        }

        override fun onHideCustomView() {
            hideCustomView()
        }

        /**
         * Port of `RoomWebChromeClient.onPermissionRequest`
         * (WebClients.kt:609-650).
         *
         * PROTECTED MEDIA IS ANSWERED ON THE SPOT, exactly as the app answers
         * it: the resource is not a window onto anything the user owns, it is
         * a request to decode content the page is already delivering with keys
         * it obtained from its own licence server, so there is nothing for a
         * prompt to protect -- and denying it is why video stalls on
         * "initializing" on the sites that use it. MIDI sysex, the remaining
         * kind-less resource, is refused: the app has no MIDI device story.
         *
         * A MIXED REQUEST (camera or microphone AND protected media) stays
         * with the sheet, because `PermissionRequest` is single-shot and a
         * partial grant would leave the other half hanging -- which is also
         * why the responder grants the WHOLE resource array, as the app's
         * stored-decision path does (BrowserViewModel.kt:703).
         *
         * An unanswered request hangs the page for the life of its document,
         * so a session with no listener denies rather than passing it on.
         */
        override fun onPermissionRequest(request: PermissionRequest) {
            val resources = request.resources
            val wantsVideo = PermissionRequest.RESOURCE_VIDEO_CAPTURE in resources
            val wantsAudio = PermissionRequest.RESOURCE_AUDIO_CAPTURE in resources
            val target = listener
            if (!wantsVideo && !wantsAudio) {
                if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in resources) {
                    runCatching {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
                    }
                } else {
                    runCatching { request.deny() }
                }
                return
            }
            if (target == null) {
                runCatching { request.deny() }
                return
            }
            target.onMediaPermissionRequest(
                this@WebViewEngineSession,
                request.origin?.toString() ?: "",
                wantsVideo,
                wantsAudio,
                WebViewPermissionResponder(request)
            )
        }

        /**
         * Port of `RoomWebChromeClient.onGeolocationPermissionsShowPrompt`
         * (WebClients.kt:652-657) and the app's answer
         * (BrowserViewModel.kt:598-628).
         *
         * `retain` is always FALSE, as the app always passes it: the WebView's
         * own per-origin store is not where a decision the user can neither
         * see nor revoke belongs.
         */
        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback
        ) {
            val target = listener
            if (target == null) {
                runCatching { callback.invoke(origin, false, false) }
                return
            }
            target.onGeolocationRequest(
                this@WebViewEngineSession,
                origin,
                WebViewGeolocationResponder(origin, callback)
            )
        }

        /**
         * REFUSED, AND THAT IS A REAL GAP REPORTED RATHER THAN HIDDEN.
         *
         * The app's `onShowFileChooser` (WebClients.kt:659-679) raises a file
         * picker through the host, with a deliberate refusal path for a
         * request that cannot be attributed to the active engine. The facade
         * carries NO file-chooser transport -- there is no member for it on
         * [EngineSessionListener] -- so there is no way to ask the app for a
         * picker, and none to hand the chosen uris back.
         *
         * Given that, the choice is between letting the platform's own picker
         * appear (which the app deliberately suppressed, and which would let a
         * background tab raise UI over the page the user is reading) and
         * completing the callback with a null result. The null result is what
         * the app already does for a request it cannot own
         * (`callback.onResult(null)`), so a file input currently cancels
         * instead of opening a picker. The alternative -- returning without
         * invoking the callback -- leaves the page waiting for a file for the
         * life of its document, which the app's own comment calls worse than
         * being told no.
         *
         * `true` is returned even though the request is refused: `false` hands
         * the request to the platform's own picker, which is the very UI this
         * refusal exists to suppress.
         */
        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: WebChromeClient.FileChooserParams?
        ): Boolean {
            runCatching { filePathCallback?.onReceiveValue(null) }
            return true
        }
    }

    /**
     * The two page bridges. Each is registered under its own name so that the
     * page sees exactly the methods it saw before -- see
     * [WebViewPageChannels] for why one object installed twice would not do.
     *
     * They are declared HERE, immediately above the `init` block that installs
     * them, and that placement is load-bearing rather than tidy: Kotlin
     * initialises properties and `init` blocks in the order they appear, so a
     * property declared below the `init` that reads it is still null at that
     * moment -- `addJavascriptInterface` would be handed a null object and the
     * two globals would simply never exist, with nothing in the log to say so.
     *
     * The lambda captures this session, which the WebView already holds, so no
     * new reference is created and no extra weak indirection is needed (the
     * app's bridges used a `WeakReference` to the WebView because THEY were
     * held by a WebView they did not otherwise belong to).
     *
     * DELIVERY THREAD: `@JavascriptInterface` methods arrive on WebView's
     * JavaBridge thread and are forwarded from there, unchanged. The facade
     * requires every listener method to be safe off the main thread, and the
     * bridges above hop for themselves -- which is what the app's own bridges
     * already did (`RoomVaultBridge.main`, WebClients.kt:742).
     */
    private val vaultPageBridge = VaultPageBridge { channel, payload ->
        listener?.onPageMessage(this@WebViewEngineSession, channel, payload)
    }

    private val walletPageBridge = WalletPageBridge { channel, payload ->
        listener?.onPageMessage(this@WebViewEngineSession, channel, payload)
    }

    /**
     * Wiring, deliberately LAST in the file.
     *
     * Kotlin runs property initialisers and `init` blocks in the order they
     * appear, so this has to sit after the two client properties and the two
     * page-bridge properties it installs. Placed at the top -- where it reads
     * better -- it would pass not-yet-initialised values to the WebView, which
     * accepts a null client happily and would then silently deliver no
     * callbacks at all: no URL, no title, no progress, no error surface, and
     * nothing in the log to say why.
     *
     * The ORDER of the statements is `ProfileEngine.createWebView` followed by
     * `BrowserViewModel.createWebView` (ProfileEngine.kt:195-204,
     * BrowserViewModel.kt:1288-1333), which is the order the app has always
     * built an engine in: configured before anything is attached to it, then
     * the two clients, then the two native page bridges, then the download
     * listener.
     */
    init {
        webView.isFocusable = true
        configure(profile)
        webView.webViewClient = webViewClient
        webView.webChromeClient = webChromeClient
        webView.addJavascriptInterface(vaultPageBridge, WebViewPageChannels.VAULT_INTERFACE)
        webView.addJavascriptInterface(walletPageBridge, WebViewPageChannels.WALLET_INTERFACE)
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            listener?.onDownloadRequest(
                this@WebViewEngineSession,
                url,
                userAgent,
                contentDisposition,
                mimeType,
                contentLength
            )
        }
    }

    /** Reported when a sub-resource load is refused. Port of
     *  `RoomWebViewClient.blockedResponse` (WebClients.kt:411-412). */
    private fun blockedResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /** Reports the engine's history state, which is reported as STATE rather
     *  than as a reaction to a command -- the two engines disagree about when
     *  it becomes true, so the facade treats it as state and both are correct.
     *  Called from every callback the app called `onHistoryChanged` from
     *  (WebClients.kt:202, 209, 246). */
    private fun publishHistory(view: WebView) {
        val canBack = view.canGoBack()
        val canForward = view.canGoForward()
        backAvailable = canBack
        forwardAvailable = canForward
        listener?.onNavigationStateChanged(this@WebViewEngineSession, canBack, canForward)
    }

    /**
     * Leave fullscreen. The app's Back handler owns this decision, and this is
     * the only lever that can carry it out.
     *
     * WHY THIS HAS TO BE A FACADE MEMBER AT ALL.
     * [EngineSessionListener.onFullScreen] only REPORTS what the engine is
     * doing -- it is not a way to ask for anything. Without this member the
     * app could set its own fullscreen flag to false and nothing else would
     * happen: the page's view stays mounted over the web area, the page still
     * believes it is fullscreen, and the chrome that just came back is hidden
     * behind it. The user is left in a state no control can undo.
     *
     * Idempotent, and a no-op for a session that is not in fullscreen.
     */
    override fun exitFullScreen() {
        runOnMain { hideCustomView() }
    }

    /**
     * Takes the page's fullscreen content back down and tells WebView it is
     * over. Shared by WebView's own exit (the chrome-client callback) and by
     * [exitFullScreen], which is why it is not inline in that callback: doing
     * only half of it is the trap. Unmounting without `onCustomViewHidden()`
     * leaves WebView rendering fullscreen content into a view nobody is
     * showing; calling it without unmounting leaves the page's view on top of
     * the web area with nothing left that can remove it.
     *
     * Silent when there is nothing to hide, so a redundant call cannot report
     * a fullscreen exit that never happened.
     */
    private fun hideCustomView() {
        val view = customView
        val callback = customViewCallback
        if (view == null && callback == null) return
        customView = null
        customViewCallback = null
        if (view != null) runCatching { (view.parent as? ViewGroup)?.removeView(view) }
        runCatching { callback?.onCustomViewHidden() }
        listener?.onFullScreen(this@WebViewEngineSession, false)
    }

    /** Puts the page's fullscreen content over this session's own view, once
     *  there is a host to put it in. See [onShowCustomView]. */
    private fun mountCustomView() {
        val view = customView ?: return
        if (view.parent != null) return
        val container = host ?: return
        runCatching {
            container.addView(
                view,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    /**
     * Marshals [block] to the thread every `WebView` method requires.
     *
     * Running inline when already there is not an optimisation: several
     * members have to observe their effect before returning (`saveState`), and
     * a `post` would make that impossible.
     */
    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            // A lambda literal, not the function value: a Kotlin `() -> Unit`
            // does not convert to a Java SAM interface on its own.
            Handler(Looper.getMainLooper()).post { block() }
        }
    }

    /**
     * Single-shot wrapper for a media permission request. The facade requires
     * implementations to ignore a second answer, and the engine agrees with
     * it: `PermissionRequest` is a single-use handle, so a second `grant` is a
     * double release rather than a harmless mistake.
     *
     * The hop to the main thread is not defensive politeness -- a
     * `PermissionRequest` settled off the main thread is ignored by WebView
     * and the page waits for the life of its document, and the facade requires
     * every command to be dispatchable to the engine's own thread internally.
     */
    private inner class WebViewPermissionResponder(
        private val request: PermissionRequest
    ) : PermissionResponder {

        private val answered = AtomicBoolean(false)

        override fun grant() {
            if (!answered.compareAndSet(false, true)) return
            runOnMain { runCatching { request.grant(request.resources) } }
        }

        override fun deny() {
            if (!answered.compareAndSet(false, true)) return
            runOnMain { runCatching { request.deny() } }
        }
    }

    /** The geolocation equivalent of [WebViewPermissionResponder]. `retain` is
     *  false on both answers, always. */
    private inner class WebViewGeolocationResponder(
        private val origin: String?,
        private val callback: GeolocationPermissions.Callback
    ) : PermissionResponder {

        private val answered = AtomicBoolean(false)

        override fun grant() {
            if (!answered.compareAndSet(false, true)) return
            runOnMain { runCatching { callback.invoke(origin, true, false) } }
        }

        override fun deny() {
            if (!answered.compareAndSet(false, true)) return
            runOnMain { runCatching { callback.invoke(origin, false, false) } }
        }
    }

    /** The HTTP authentication equivalent, reproducing the app's `answered`
     *  guard (WebClients.kt:391-408) -- one answer for the pair, not one
     *  each. */
    private inner class WebViewHttpAuthResponder(
        private val handler: HttpAuthHandler
    ) : HttpAuthResponder {

        private val answered = AtomicBoolean(false)

        override fun proceed(username: String, password: String) {
            if (!answered.compareAndSet(false, true)) return
            runOnMain { runCatching { handler.proceed(username, password) } }
        }

        override fun cancel() {
            if (!answered.compareAndSet(false, true)) return
            runOnMain { runCatching { handler.cancel() } }
        }
    }

    private companion object {

        /**
         * Whether a page must wait for a user gesture before it may start
         * playing media. Port of `ProfileEngine.MEDIA_PLAYBACK_REQUIRES_USER_GESTURE`
         * (ProfileEngine.kt:41-46, 249): it is FALSE -- autoplay permitted --
         * because that is what the app has actually shipped, and because the
         * value it used to be derived from (`blockMalicious`) was a miswire
         * where toggling the malicious-site shield silently changed an
         * unrelated behaviour.
         */
        const val MEDIA_PLAYBACK_REQUIRES_USER_GESTURE = false

        /**
         * How long a popup transport WebView may live without navigating.
         * Port of `RoomWebChromeClient.TRANSPORT_REAP_MS`
         * (WebClients.kt:681-691): long enough that a real popup is never cut
         * off on a cold emulator, short enough that an abandoned one is not
         * holding a renderer while the user browses on.
         */
        const val TRANSPORT_REAP_MS = 10_000L
    }
}

/**
 * The serialised form of a WebView session state, opaque above the facade.
 *
 * A `Bundle` is the only thing `WebView.saveState` produces, and it is the
 * counterpart of the GeckoView edition's serialised `SessionState`: the app
 * stores one per tab and hands it back on restore without ever inspecting it,
 * which is the property that keeps an engine type off the app's classpath.
 *
 * NOT PERSISTABLE ACROSS PROCESS DEATH in either edition, which is why the
 * tab manager keeps these in memory only.
 */
internal class WebViewState(val bundle: Bundle) : EngineState

/**
 * The engine's own stock user agent, captured the first time a session is
 * configured.
 *
 * CAPTURED, NOT RE-READ, and this is the whole reason the object exists:
 * `WebSettings` has no "what would you have sent" getter, so once a UA has
 * been assigned, `userAgentString` returns the assignment. The DEFAULT mode
 * has to be able to clear an identity a previous configure installed (a
 * profile switching from CUSTOM or a preset back to DEFAULT), and reading the
 * property back at that moment would return the custom UA and leave it in
 * place -- exactly the bug the surrounding comment warns about.
 *
 * Process-wide, like the app's field in `ProfileEngine` (ProfileEngine.kt:48-73),
 * because the value belongs to the PROCESS's engine and not to any session.
 * The first configure of a process always runs against a freshly constructed
 * WebView, so that first read is the engine's default.
 */
internal object WebViewStockUserAgent {

    @Volatile
    private var captured: String? = null

    fun of(settings: WebSettings): String {
        captured?.let { return it }
        val value = settings.userAgentString.orEmpty()
        captured = value
        return value
    }
}

/**
 * The error-code classification the app's `onReceivedError` applied
 * (BrowserViewModel.kt:503-508), moved to where the engine reports errors.
 *
 * The app branched on three codes explicitly -- `ERROR_HOST_LOOKUP` to a DNS
 * failure, `ERROR_CONNECT`/`ERROR_TIMEOUT` to "no internet", everything else
 * to a generic error -- and the facade's [PageErrorKind] is the engine-neutral
 * spelling of exactly that split. Codes the app did not classify stay
 * [PageErrorKind.OTHER] rather than being guessed into TRANSPORT: the
 * distinction drives which screen the user sees, and a guess there is a wrong
 * screen.
 */
private fun kindOf(errorCode: Int): PageErrorKind = when (errorCode) {
    // The NAME did not resolve. Kept apart from a host that resolved and then
    // did not answer, because the app's advice differs -- see
    // PageErrorKind.DNS. Folding it into TRANSPORT is what made the app's
    // "DNS Resolution Failed" surface unreachable dead code.
    WebViewClient.ERROR_HOST_LOOKUP -> PageErrorKind.DNS

    WebViewClient.ERROR_CONNECT,
    WebViewClient.ERROR_TIMEOUT -> PageErrorKind.TRANSPORT

    else -> PageErrorKind.OTHER
}
