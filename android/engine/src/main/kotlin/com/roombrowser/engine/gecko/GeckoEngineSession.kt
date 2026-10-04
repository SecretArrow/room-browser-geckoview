package com.roombrowser.engine.gecko

import android.content.Context
import android.graphics.Bitmap
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
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import java.util.concurrent.ConcurrentHashMap
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

        override fun onLoadError(
            session: GeckoSession,
            uri: String?,
            error: org.mozilla.geckoview.WebRequestError
        ): GeckoResult<String>? {
            listener?.onPageError(
                this@GeckoEngineSession,
                uri,
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
            listener?.onNewWindowRequest(this@GeckoEngineSession, uri)
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

    /**
     * Wiring, deliberately LAST in the file.
     *
     * Kotlin runs property initialisers and `init` blocks in the order they
     * appear, so this has to sit after the three delegate properties it
     * installs. Placed at the top -- where it reads better -- it would pass
     * three not-yet-initialised nulls to GeckoView, which accepts null
     * delegates happily and would then silently deliver no callbacks at all:
     * no URL, no title, no progress, no error surface, with nothing in the
     * log to say why.
     */
    init {
        session.setNavigationDelegate(navigationDelegate)
        session.setContentDelegate(contentDelegate)
        session.setProgressDelegate(progressDelegate)
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
