package com.roombrowser.engine.gecko

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.UserAgents
import com.roombrowser.engine.EnginePageScripts
import com.roombrowser.engine.EngineSession
import com.roombrowser.engine.EngineSessionListener
import com.roombrowser.engine.EngineState
import com.roombrowser.engine.HttpAuthResponder
import com.roombrowser.engine.NavigationDecision
import com.roombrowser.engine.PageErrorKind
import com.roombrowser.engine.PermissionResponder
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A GeckoSession behind the engine facade.
 *
 * Three things here are structurally different from the WebView edition and
 * are worth knowing before reading the callbacks:
 *
 * 1. THERE IS NO `getUrl()`. GeckoView removed it. The session's URL is a
 *    `@Volatile` mirror fed only by the navigation callback for a top-level
 *    frame, which is what makes it a trust anchor rather than a page-writable
 *    value.
 * 2. THERE IS NO `evaluateJavascript`. Scripting runs over a WebExtension
 *    port, so [evaluateJs] is asynchronous, and a document that has not
 *    started yet has nowhere to run -- hence the bounded queue in [sendEval].
 * 3. SESSION SETTINGS ARE MUTABLE, but the user agent mode and the override
 *    that goes with it must always be set together: setting only the mode
 *    leaves the previous identity installed, which is a session that claims
 *    to be desktop while sending a mobile UA. [applyDesktopMode] and
 *    [configure] therefore set both, and neither is reachable from a bare
 *    Boolean -- see the note on [EngineSession] for why that operation lives
 *    on the host, which has the profile.
 */
internal class GeckoEngineSession(
    override val id: String,
    private val runtime: GeckoRuntime,
    context: Context,
    profile: Profile
) : EngineSession {

    private val session: GeckoSession = GeckoSession(settingsFor(profile))
    private val geckoView: GeckoView = GeckoView(context)

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

    /** The most recent state Gecko flushed, as the serialised form. */
    @Volatile
    private var lastStateJson: String? = null

    // ---- scripting bridge -------------------------------------------------

    /**
     * The port this session's isolated content script opened. Null until a
     * document has started, which is a real state rather than an error.
     */
    @Volatile
    private var port: WebExtension.Port? = null

    private val pendingEval = ConcurrentHashMap<Long, (String?) -> Unit>()
    private val nextEvalId = AtomicLong(0)

    /**
     * Scripts that arrived before the port did.
     *
     * Bounded on purpose. The caller for these is the AI agent reading the
     * page, and a session that never opens a document would otherwise grow
     * this without limit for the life of the tab. Beyond the bound the oldest
     * waiting script is answered with a null result, which is the same answer
     * it would have received for a document that never started.
     */
    private val queuedEvals = ArrayDeque<Pair<Long, String>>()

    private val pendingPageScripts: MutableList<EnginePageScripts> = mutableListOf()

    override val view: View get() = geckoView

    override val url: String? get() = currentUrl

    override val title: String? get() = currentTitle

    override val progress: Int get() = currentProgress

    override val canGoBack: Boolean get() = backAvailable

    override val canGoForward: Boolean get() = forwardAvailable

    override val isOpen: Boolean get() = !closed && session.isOpen

    override fun setListener(listener: EngineSessionListener?) {
        this.listener = listener
    }

    override fun loadUri(uri: String) = runOnMain { session.loadUri(uri) }

    override fun reload() = runOnMain { session.reload() }

    override fun stop() = runOnMain { session.stop() }

    override fun goBack() = runOnMain { session.goBack() }

    override fun goForward() = runOnMain { session.goForward() }

    override fun setActive(active: Boolean) = runOnMain { session.setActive(active) }

    override fun attachTo(host: ViewGroup) {
        if (geckoView.parent === host) return
        (geckoView.parent as? ViewGroup)?.removeView(geckoView)
        host.addView(geckoView)
    }

    override fun detach() {
        (geckoView.parent as? ViewGroup)?.removeView(geckoView)
    }

    // ---- settings ---------------------------------------------------------

    private fun settingsFor(profile: Profile): GeckoSessionSettings {
        val s = profile.settings
        val builder = GeckoSessionSettings.Builder()
            // THE isolation key inside the process. Derived from the immutable
            // profile UUID, never the display name, and identical to the value
            // the WebView edition used as its data-directory suffix -- so the
            // two editions partition storage by the same identity.
            .contextId(profile.id.safeSuffix)
            .usePrivateMode(false)
            .allowJavascript(s.javascriptEnabled)
        applyUserAgent(builder, profile, desktop = s.desktopModeDefault)
        return builder.build()
    }

    /**
     * Apply the profile's identity to a settings builder.
     *
     * GeckoView's own UA is a Firefox UA, which is a different claim from the
     * one this browser makes. When the profile names an explicit identity the
     * override is set; otherwise the mode alone decides, and GeckoView uses
     * its own string. That difference is deliberate rather than overlooked:
     * the WebView edition could read its engine's stock UA back and strip the
     * WebView markers from it, and GeckoView exposes no way to ask what it
     * would have sent -- only whether an override is set.
     */
    private fun applyUserAgent(
        builder: GeckoSessionSettings.Builder,
        profile: Profile,
        desktop: Boolean
    ) {
        if (desktop) {
            builder.userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
            builder.userAgentOverride(UserAgents.desktopModeUserAgent)
            return
        }
        builder.userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
        UserAgents.effectiveUserAgent(profile.settings)?.let { builder.userAgentOverride(it) }
    }

    internal fun configure(profile: Profile) {
        runOnMain {
            val s = session.settings
            s.setAllowJavascript(profile.settings.javascriptEnabled)
            val desktop = profile.settings.desktopModeDefault
            s.setUserAgentMode(
                if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            )
            if (desktop) {
                s.setUserAgentOverride(UserAgents.desktopModeUserAgent)
            } else {
                // Null here means "no override", which is how GeckoView resets
                // to its own UA -- the same rule the WebView edition applies
                // when a profile switches back to DEFAULT. Passing the previous
                // string through would leave the old identity installed on
                // every live session until the process restarted.
                s.setUserAgentOverride(UserAgents.effectiveUserAgent(profile.settings))
            }
        }
        reapplyPageScripts()
    }

    internal fun applyDesktopMode(profile: Profile, desktop: Boolean) {
        runOnMain {
            val s = session.settings
            if (desktop) {
                s.setUserAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
                s.setUserAgentOverride(UserAgents.desktopModeUserAgent)
            } else {
                s.setUserAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                s.setUserAgentOverride(UserAgents.effectiveUserAgent(profile.settings))
            }
        }
        // Desktop mode changes the claimed device, so the shim has to be
        // re-sent -- the same coupling the WebView edition applies when it
        // swaps the shim out under a desktop UA.
        reapplyPageScripts()
    }

    // ---- the scripting bridge --------------------------------------------

    internal fun installBridge(extension: WebExtension) {
        session.webExtensionController.setMessageDelegate(
            extension,
            bridgeDelegate,
            BRIDGE_NATIVE_APP
        )
    }

    private val bridgeDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            this@GeckoEngineSession.port = port
            port.setDelegate(portDelegate)
            flushQueuedEvals(port)
            flushPageScripts(port)
        }
    }

    private val portDelegate = object : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, port: WebExtension.Port) {
            val json = message as? JSONObject ?: return
            when (json.optString("type")) {
                "evalResult" -> {
                    val id = json.optLong("id")
                    pendingEval.remove(id)?.invoke(json.optString("value"))
                }
                "app" -> {
                    listener?.onPageMessage(
                        this@GeckoEngineSession,
                        json.optString("channel"),
                        json.optString("payload")
                    )
                }
            }
        }

        override fun onDisconnect(port: WebExtension.Port) {
            if (this@GeckoEngineSession.port !== port) return
            this@GeckoEngineSession.port = null
            // Every evaluation still outstanding was sent to a document that
            // no longer exists, so none of them can ever be answered. Leaving
            // them in the map is not a leak of memory but a leak of PROMISES:
            // the dApp awaits a result that never arrives, and the wallet UI
            // waits forever on a request the page has already forgotten. The
            // interface says a null result means "could not be delivered",
            // and that is exactly what happened.
            //
            // NOT re-queued for the next document on purpose. The next
            // document is a different origin, and replaying a script written
            // for one origin against another is the failure mode the trust
            // anchor exists to prevent.
            val abandoned = pendingEval.keys.toList()
            abandoned.forEach { id -> pendingEval.remove(id)?.invoke(null) }
        }
    }

    override fun setPageScripts(scripts: EnginePageScripts) {
        synchronized(pendingPageScripts) {
            pendingPageScripts.clear()
            pendingPageScripts.add(scripts)
        }
        port?.let { flushPageScripts(it) }
    }

    private fun reapplyPageScripts() {
        port?.let { flushPageScripts(it) }
    }

    /**
     * Send the page-world scripts to the isolated content script, which
     * forwards them to the main world.
     *
     * Re-sent on every configuration change rather than accumulated: the
     * scripts are idempotent (each guards itself with a `__room*Installed`
     * flag) but a session that stacked one copy per settings edit would leak
     * them for the life of the tab.
     */
    private fun flushPageScripts(port: WebExtension.Port) {
        val scripts = synchronized(pendingPageScripts) { pendingPageScripts.lastOrNull() } ?: return
        val message = JSONObject()
            .put("type", "scripts")
            .put("deviceShim", scripts.deviceShim ?: JSONObject.NULL)
            .put("vault", scripts.vaultBridge)
            .put("wallet", scripts.walletProvider)
        postQuietly(port, message)
    }

    override fun evaluateJs(script: String, callback: ((String?) -> Unit)?) {
        val id = nextEvalId.getAndIncrement()
        if (callback != null) pendingEval[id] = callback
        sendEval(id, script)
    }

    private fun sendEval(id: Long, script: String) {
        val active = port
        if (active == null) {
            enqueueEval(id, script)
            return
        }
        val message = JSONObject().put("type", "eval").put("id", id).put("code", script)
        postQuietly(active, message)
    }

    private fun enqueueEval(id: Long, script: String) {
        val dropped: List<Long>
        synchronized(queuedEvals) {
            queuedEvals.addLast(id to script)
            dropped = if (queuedEvals.size > MAX_QUEUED_EVALS) {
                (0 until queuedEvals.size - MAX_QUEUED_EVALS).map { queuedEvals.removeFirst().first }
            } else {
                emptyList()
            }
        }
        dropped.forEach { pendingEval.remove(it)?.invoke(null) }
    }

    private fun flushQueuedEvals(port: WebExtension.Port) {
        val queued: List<Pair<Long, String>>
        synchronized(queuedEvals) {
            queued = queuedEvals.toList()
            queuedEvals.clear()
        }
        queued.forEach { (id, script) ->
            postQuietly(port, JSONObject().put("type", "eval").put("id", id).put("code", script))
        }
    }

    private fun postQuietly(port: WebExtension.Port, message: JSONObject) {
        // A port can close between the read and the post; a lost message costs
        // one result, where an exception would take down the caller's thread.
        runCatching { port.postMessage(message) }
    }

    // ---- capture and state ------------------------------------------------

    override fun capturePixels(onResult: (Bitmap?) -> Unit) {
        runOnMain {
            val onValue = GeckoResult.Consumer<Bitmap> { bitmap -> onResult(bitmap) }
            val onError = GeckoResult.Consumer<Throwable> { onResult(null) }
            runCatching { geckoView.capturePixels().accept(onValue, onError) }
                .onFailure { onResult(null) }
        }
    }

    /**
     * Ask Gecko to flush and hand back the state it last reported.
     *
     * `GeckoSession.saveState()` does not exist in this API -- it was removed
     * long before this version -- so the only way to obtain a SessionState is
     * the asynchronous `onSessionStateChange` callback, triggered here by
     * `flushSessionState()`. The consequence is that the FIRST call on a
     * freshly loaded tab returns null and the token appears once the flush
     * lands. Callers keep the most recent token rather than requiring one on
     * demand, which is how the tab manager already works.
     */
    override fun saveState(): EngineState? {
        runOnMain { session.flushSessionState() }
        return lastStateJson?.let(::GeckoState)
    }

    override fun restoreState(state: EngineState) {
        val json = (state as? GeckoState)?.json ?: return
        val parsed = GeckoSession.SessionState.fromString(json) ?: return
        runOnMain { session.restoreState(parsed) }
    }

    override fun close() {
        if (closed) return
        closed = true
        runOnMain {
            runCatching { geckoView.releaseSession() }
            runCatching { if (session.isOpen) session.close() }
        }
        port?.let { runCatching { it.disconnect() } }
        port = null
        listener = null
        pendingEval.values.forEach { it(null) }
        pendingEval.clear()
        synchronized(queuedEvals) { queuedEvals.clear() }
    }

    // ---- delegates --------------------------------------------------------
    //
    // ONE LISTENER MEMBER HAS NO OVERRIDE HERE, and its absence is a fact
    // rather than an oversight. `EngineSessionListener.onResourceRequest`
    // cannot be served by GeckoView 153: there is no `shouldInterceptRequest`
    // equivalent, and the only per-request hooks the engine exposes are the
    // two load-request callbacks below, which fire for navigations -- an
    // iframe's document, a link -- and never for the scripts, images and
    // `fetch` calls that member exists to block. The GeckoView edition gets
    // this signal from a bundled WebExtension when that lands. Overriding
    // nothing is the honest encoding of "this engine cannot answer"; an
    // override that always returned false would read as "checked, and not
    // blocked", which is the one thing it must not read as.

    private val navigationDelegate = object : GeckoSession.NavigationDelegate {

        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean
        ) {
            // GeckoView cannot tell us whether this is the top-level document
            // through this callback alone; onCanGoBack/onCanGoForward and the
            // page-start signal are what distinguish a real navigation from a
            // sub-frame one. Until the load settles we publish the URL, which
            // matches the WebView edition's behaviour of reporting at
            // navigation start, and the listener decides what to trust.
            currentUrl = url
            listener?.onUrlChanged(this@GeckoEngineSession, url, true, hasUserGesture)
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            backAvailable = canGoBack
            listener?.onNavigationStateChanged(this@GeckoEngineSession, backAvailable, forwardAvailable)
        }

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            forwardAvailable = canGoForward
            listener?.onNavigationStateChanged(this@GeckoEngineSession, backAvailable, forwardAvailable)
        }

        /**
         * The app's navigation policy, for a TOP-LEVEL navigation.
         *
         * GeckoView splits the policy question across two callbacks with
         * identical shapes, and which one ran IS the frame signal: there is no
         * field on [GeckoSession.NavigationDelegate.LoadRequest] that says
         * main-frame. So this one reports isTopLevel = true and its twin
         * reports false, and neither invents the flag.
         */
        override fun onLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest
        ): GeckoResult<AllowOrDeny>? = decideNavigation(request, isTopLevel = true)

        /** The same policy for a non-top-level navigation -- an iframe's document. */
        override fun onSubframeLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest
        ): GeckoResult<AllowOrDeny>? = decideNavigation(request, isTopLevel = false)

        override fun onLoadError(
            session: GeckoSession,
            uri: String?,
            error: WebRequestError
        ): GeckoResult<String>? {
            // isTopLevel is TRUE. GeckoView's onLoadError carries no frame flag
            // of its own, and it is the callback that feeds the engine's error
            // page -- a top-level concept, and the meaning the facade documents
            // for this member ("a main-frame load failed"). One case is a
            // slight overclaim: GeckoView also routes a frame's rejection of an
            // unsafe scheme through this same callback, and nothing here can
            // tell that apart from a document failure. Reporting false instead
            // would be the larger error -- it would mark every real top-level
            // failure as a frame failure -- so true is reported, and the one
            // imprecise case is recorded here rather than hidden.
            //
            // The engine's own code is passed alongside the mapped kind rather
            // than instead of it: the kind is what the shared policy branches
            // on, the code is what the diagnostics screen prints, and
            // collapsing them would put a GeckoView numbering system into code
            // that must not know one.
            listener?.onPageError(
                this@GeckoEngineSession,
                uri,
                pageErrorKind(error),
                true,
                error.code,
                "category=" + error.category
            )
            // Always null: that hands the failure to GeckoView's own error page.
            //
            // The listener's return value is deliberately NOT consulted here.
            // In the WebView edition that flag drives an error page we render
            // ourselves, and the only thing this app ever did with it was
            // retry an https upgrade over http -- which is a recovery policy,
            // not an error surface, so it belongs above the facade and must
            // not be smuggled in through the engine's error page.
            //
            // There is deliberately no "proceed anyway" path anywhere in this
            // file. GeckoView exposes none, and this code must not grow one:
            // that is what makes "never bypass certificate validation" a
            // structural property of this edition rather than a rule someone
            // has to remember.
            return null
        }

        override fun onNewSession(
            session: GeckoSession,
            uri: String
        ): GeckoResult<GeckoSession>? {
            // hasUserGesture is FALSE, and that is a report rather than a
            // guess: GeckoView 153's `onNewSession` takes the session and the
            // URI and nothing else -- there is no gesture flag anywhere in the
            // callback. The shared popup policy blocks a window request that
            // arrives without one, so in this edition EVERY popup is blocked
            // until a gesture source is found. That is a known, recorded gap,
            // not something to paper over: reporting `true` here would invent
            // a fact about a navigation the engine never described, and would
            // give a script's unsolicited `window.open()` the same standing as
            // a link the user pressed.
            listener?.onNewWindowRequest(this@GeckoEngineSession, uri, false)
            // Null anyway, even when the app just opened the URL as its own
            // new tab: answering with a session would hand GeckoView a second
            // session for a window the app has already opened, and the user
            // would get two tabs for one click.
            return null
        }
    }

    private val contentDelegate = object : GeckoSession.ContentDelegate {

        override fun onTitleChange(session: GeckoSession, title: String?) {
            currentTitle = title
            listener?.onTitleChanged(this@GeckoEngineSession, title)
        }

        /**
         * The download hook. GeckoView routes anything it will not render
         * itself -- a download, a navigation to a type it has no viewer for --
         * through here.
         *
         * THE BODY STREAM IS CLOSED UNCONDITIONALLY, in a `finally` that runs
         * even when the listener is absent or answers "handled". The stream is
         * the read side of a live connection: leaving it open holds the socket
         * until the stream is finalised by the GC, which on a page that fires
         * several downloads is a leaked file descriptor, not a tidy-up. Closing
         * it here cannot break a handler, because the listener signature
         * carries no stream for a handler to have taken -- an app that wants
         * the bytes re-fetches from the URL, which is the only thing it can do
         * with what it was given.
         */
        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            try {
                // `response.headers` is the RESPONSE header map, and there is no
                // accessor for content type, disposition or length -- they are
                // read out of it and nowhere else. A missing or unparseable
                // Content-Length becomes -1, which is the same value the WebView
                // edition's DownloadListener already reports for "unknown", so
                // the shape above the facade is identical in both editions.
                listener?.onDownloadRequest(
                    this@GeckoEngineSession,
                    response.uri,
                    // null, and it cannot be anything else: WebResponse carries
                    // response headers only, so the request's User-Agent is never
                    // echoed back to us, and GeckoView exposes no query for the
                    // effective UA of a session either.
                    null,
                    response.headers["Content-Disposition"],
                    response.headers["Content-Type"],
                    response.headers["Content-Length"]?.toLongOrNull() ?: -1L
                )
            } finally {
                runCatching { response.body?.close() }
            }
        }

        /**
         * Fullscreen is a state notification here, because GeckoView renders
         * fullscreen content in its own view -- there is no second surface to
         * hand the app and none to hand back, which is why the facade carries a
         * boolean and not a view. Leaving fullscreen is `GeckoSession.exitFullScreen()`
         * on the engine side; the facade has no member for the app to ask for
         * that yet, so this direction is report-only.
         */
        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
            listener?.onFullScreen(this@GeckoEngineSession, fullScreen)
        }

        override fun onCrash(session: GeckoSession) {
            listener?.onClosed(this@GeckoEngineSession)
        }

        override fun onKill(session: GeckoSession) {
            listener?.onClosed(this@GeckoEngineSession)
        }
    }

    private val progressDelegate = object : GeckoSession.ProgressDelegate {

        override fun onPageStart(session: GeckoSession, url: String) {
            currentProgress = 0
            listener?.onPageStarted(this@GeckoEngineSession, url)
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            currentProgress = if (success) 100 else currentProgress
            listener?.onPageFinished(this@GeckoEngineSession, currentUrl, success)
        }

        override fun onProgressChange(session: GeckoSession, progress: Int) {
            currentProgress = progress
            listener?.onProgress(this@GeckoEngineSession, progress)
        }

        override fun onSessionStateChange(
            session: GeckoSession,
            sessionState: GeckoSession.SessionState
        ) {
            lastStateJson = sessionState.toString()
        }
    }

    private val permissionDelegate = object : GeckoSession.PermissionDelegate {

        /**
         * Geolocation, the only content permission the facade has a member
         * for.
         *
         * A NULL return is treated by GeckoView as VALUE_PROMPT, and that is
         * the right answer for every other permission type: it neither grants
         * nor persists anything, and leaves the engine's own behaviour in
         * place. The facade has no vocabulary for notifications, autoplay or
         * storage access, and inventing one here would be the adapter deciding
         * product policy.
         *
         * VALUE_ALLOW and VALUE_DENY are more than an answer to this request:
         * GeckoView writes them into its own permission store for the site, so
         * the app is choosing what happens on every later visit as well. That
         * is the engine's semantics and the facade's contract; the adapter
         * must not soften it by resolving PROMPT on a real answer.
         */
        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: GeckoSession.PermissionDelegate.ContentPermission
        ): GeckoResult<Int>? {
            if (perm.permission != GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION) {
                return null
            }
            val result = GeckoResult<Int>()
            val answered = AtomicBoolean(false)
            val responder = object : PermissionResponder {
                override fun grant() {
                    // A second resolution throws in GeckoView and would throw
                    // on whatever thread the app answered from. The guard is
                    // what makes the facade's "answer exactly once" rule
                    // enforceable here instead of merely documented.
                    if (answered.compareAndSet(false, true)) {
                        result.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                    }
                }

                override fun deny() {
                    if (answered.compareAndSet(false, true)) {
                        result.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                    }
                }
            }
            val listener = this@GeckoEngineSession.listener
            if (listener == null) {
                // No listener must not mean a hung request: a geolocation call
                // that never settles is a page that never recovers. With
                // nothing to ask, PROMPT is the one answer that grants nothing
                // and persists nothing.
                result.complete(
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT
                )
            } else {
                listener.onGeolocationRequest(this@GeckoEngineSession, perm.uri, responder)
            }
            return result
        }

        /**
         * Camera and microphone.
         *
         * Each list is the engine's own device list for that kind, and either
         * may be null when nothing of that kind was requested -- so "wants" is
         * asked of the list rather than inferred from the document. Granting
         * passes the first source of each requested kind back to the callback,
         * which wants sources it gave us or null for a kind that was not
         * requested. The facade's grant() is deliberately all-or-nothing
         * because the sheet that answers it is.
         */
        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
            audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
            callback: GeckoSession.PermissionDelegate.MediaCallback
        ) {
            val answered = AtomicBoolean(false)
            val responder = object : PermissionResponder {
                override fun grant() {
                    if (!answered.compareAndSet(false, true)) return
                    val videoSource = video?.firstOrNull()
                    val audioSource = audio?.firstOrNull()
                    // The callback is @UiThread and the responder is not:
                    // nothing in the facade promises the sheet answers on the
                    // UI thread, so the call is posted there rather than run
                    // inline. When the sheet already is on the main thread
                    // this executes immediately and nothing is delayed.
                    runOnMain { callback.grant(videoSource, audioSource) }
                }

                override fun deny() {
                    if (!answered.compareAndSet(false, true)) return
                    runOnMain { callback.reject() }
                }
            }
            val listener = this@GeckoEngineSession.listener
            if (listener == null) {
                // There is no PROMPT to leave this at -- MediaCallback has
                // only grant and reject -- and this override is what replaces
                // GeckoView's own default, which rejects. An unanswered
                // request is a `getUserMedia` promise that never settles, so
                // "no listener" has to resolve rather than return quietly.
                callback.reject()
            } else {
                listener.onMediaPermissionRequest(
                    this@GeckoEngineSession,
                    uri,
                    !video.isNullOrEmpty(),
                    !audio.isNullOrEmpty(),
                    responder
                )
            }
        }
    }

    private val promptDelegate = object : GeckoSession.PromptDelegate {

        /**
         * HTTP authentication, the only prompt the facade models.
         *
         * Every other prompt type -- JavaScript dialogs, choices, file pickers
         * -- is left to GeckoView by returning null, which is how this override
         * says "not mine" without pretending to have handled it. GeckoView's
         * default for those is to dismiss, so a null here is a cancel and not
         * a hang.
         *
         * The navigation stays open until the returned result resolves, which
         * is why the responder resolves exactly once and why the
         * absent-listener case resolves immediately: an unresolved prompt is a
         * page that never finishes loading.
         */
        override fun onAuthPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.AuthPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
            val answered = AtomicBoolean(false)
            val responder = object : HttpAuthResponder {
                override fun proceed(username: String, password: String) {
                    if (!answered.compareAndSet(false, true)) return
                    // confirm() and dismiss() both THROW once the prompt has
                    // been completed, so the guard above is not only about the
                    // GeckoResult: a second call would throw out of whatever
                    // thread the app answered on.
                    runOnMain { result.complete(prompt.confirm(username, password)) }
                }

                override fun cancel() {
                    if (!answered.compareAndSet(false, true)) return
                    runOnMain { result.complete(prompt.dismiss()) }
                }
            }
            val listener = this@GeckoEngineSession.listener
            if (listener == null) {
                result.complete(prompt.dismiss())
            } else {
                // The realm is reported EMPTY, and that is the honest answer
                // rather than a lost value: GeckoView 153's AuthPrompt has no
                // `httpRealm` field anywhere -- the challenged site is carried
                // by `authOptions.uri`, which is what authHost() reads.
                // Inventing a realm from the URL or the message would put text
                // in front of the user that the engine never said.
                listener.onHttpAuthRequest(
                    this@GeckoEngineSession,
                    authHost(prompt.authOptions.uri),
                    "",
                    responder
                )
            }
            return result
        }
    }

    // ---- policy mapping ---------------------------------------------------

    /**
     * Run one load request through the app's navigation policy.
     *
     * The whole decision vocabulary is honoured here: ALLOW hands the load
     * back to GeckoView, BLOCK abandons it, and LoadDifferent abandons the
     * original and starts the substitute. That last one is the https upgrade,
     * and it is the reason the facade carries a decision instead of a boolean
     * -- the substitute load is issued from here, so it stays inside the
     * session's own navigation accounting rather than escaping as a
     * side-effecting call the caller made.
     *
     * A SUBSTITUTE CANNOT BE ISSUED FOR A SUBFRAME, and the code refuses to
     * pretend otherwise: `loadUri` navigates the top-level document, so using
     * it to "upgrade" an iframe's request would turn a frame load into a page
     * load -- a worse outcome than the one the caller was avoiding. The caller
     * asked for the original NOT to load, so the faithful answer is BLOCK: the
     * frame does not load, and nothing is loaded in its place.
     *
     * NO "PROCEED ANYWAY" IS EXPRESSIBLE HERE. A certificate failure never
     * reaches this method -- GeckoView reports it as a load error instead --
     * and a DENY here is a policy judgement about a URL, never an override of
     * a failed validation.
     */
    private fun decideNavigation(
        request: GeckoSession.NavigationDelegate.LoadRequest,
        isTopLevel: Boolean
    ): GeckoResult<AllowOrDeny> {
        val listener = this.listener ?: return GeckoResult.allow()
        val decision = listener.onNavigationRequest(
            this,
            request.uri,
            isTopLevel,
            request.hasUserGesture
        )
        return when (decision) {
            NavigationDecision.Allow -> GeckoResult.allow()
            NavigationDecision.Block -> GeckoResult.deny()
            is NavigationDecision.LoadDifferent -> {
                if (isTopLevel) {
                    // Loaded with the delegate explicitly bypassed, and not
                    // through the session's own `loadUri`. GeckoView runs
                    // `onLoadRequest` for the app's direct loads as well --
                    // LOAD_FLAGS_NONE is the Loader default, and only
                    // LOAD_FLAGS_BYPASS_LOAD_URI_DELEGATE skips the callback
                    // -- so a plain `loadUri(substitute)` would come straight
                    // back through this method. The caller has already
                    // classified the substitute; asking it about a URL it
                    // chose is not a second opinion, it is a loop.
                    runOnMain {
                        session.load(
                            GeckoSession.Loader()
                                .uri(decision.url)
                                .flags(GeckoSession.LOAD_FLAGS_BYPASS_LOAD_URI_DELEGATE)
                        )
                    }
                }
                GeckoResult.deny()
            }
        }
    }

    /**
     * Map a GeckoView load error onto the facade's three categories.
     *
     * The category is checked first because GeckoView gives its certificate
     * failures a category of their own; the individual codes are checked as
     * well because this mapping exists to be exact, not to be clever. The one
     * error that must NOT be read as a certificate failure is ERROR_HTTPS_ONLY:
     * it means https-only mode blocked a plaintext load, so the site is
     * unreachable rather than untrusted, and showing the "not secure" screen
     * for it would tell the user something untrue.
     */
    private fun pageErrorKind(error: WebRequestError): PageErrorKind = when {
        error.category == WebRequestError.ERROR_CATEGORY_SECURITY -> PageErrorKind.CERTIFICATE
        error.code == WebRequestError.ERROR_SECURITY_SSL ||
            error.code == WebRequestError.ERROR_SECURITY_BAD_CERT ||
            error.code == WebRequestError.ERROR_BAD_HSTS_CERT -> PageErrorKind.CERTIFICATE
        error.category == WebRequestError.ERROR_CATEGORY_NETWORK -> PageErrorKind.TRANSPORT
        error.code == WebRequestError.ERROR_NET_RESET ||
            error.code == WebRequestError.ERROR_NET_INTERRUPT ||
            error.code == WebRequestError.ERROR_NET_TIMEOUT ||
            error.code == WebRequestError.ERROR_OFFLINE ||
            error.code == WebRequestError.ERROR_PORT_BLOCKED ||
            error.code == WebRequestError.ERROR_UNKNOWN_HOST ||
            error.code == WebRequestError.ERROR_PROXY_CONNECTION_REFUSED -> PageErrorKind.TRANSPORT
        else -> PageErrorKind.OTHER
    }

    /**
     * The host of an auth challenge, for the sheet that has to show it.
     *
     * GeckoView 153 carries the challenged site in `AuthOptions.uri` and has no
     * `httpRealm` field at all. When the URI has no parseable host -- an opaque
     * or malformed authority -- the whole URI is reported: it is less precise,
     * but it is still the thing the user is being asked to authenticate
     * against, and an empty string would hide exactly that.
     */
    private fun authHost(uri: String?): String {
        if (uri == null) return ""
        return Uri.parse(uri).host ?: uri
    }

    /**
     * Wiring, deliberately LAST in the file.
     *
     * Kotlin runs property initialisers and `init` blocks in the order they
     * appear, so this has to sit after the five delegate properties it
     * installs. Placed at the top -- where it reads better -- it would pass
     * five not-yet-initialised nulls to GeckoView, which accepts null delegates
     * happily and would then silently deliver no callbacks at all: no URL, no
     * title, no progress, no error surface, no permission sheet and no auth
     * sheet, with nothing in the log to say why.
     */
    init {
        session.setNavigationDelegate(navigationDelegate)
        session.setContentDelegate(contentDelegate)
        session.setProgressDelegate(progressDelegate)
        session.setPermissionDelegate(permissionDelegate)
        session.setPromptDelegate(promptDelegate)
        session.open(runtime)
        geckoView.setSession(session)
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else Handler(Looper.getMainLooper()).post(block)
    }

    private companion object {
        /** Must match the native app name in the bridge extension's port. */
        const val BRIDGE_NATIVE_APP = "roombridge"

        /**
         * How many scripts may wait for a document to exist. See [queuedEvals]
         * for why this is bounded rather than unbounded.
         */
        const val MAX_QUEUED_EVALS = 32
    }
}

/** The serialised form of a Gecko session state, opaque above the facade. */
internal class GeckoState(val json: String) : EngineState
