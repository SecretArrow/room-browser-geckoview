package com.roombrowser.engine

import android.graphics.Bitmap
import android.view.View

/**
 * Opaque, engine-owned tab restore token.
 *
 * The app stores one per tab and hands it back on restore. It never inspects
 * it, because it cannot: WebView serialises a `Bundle` and GeckoView
 * serialises a `GeckoSession.SessionState`, and exposing either shape would
 * put an engine type back on the app's classpath.
 *
 * NOT PERSISTABLE ACROSS PROCESS DEATH, in either edition. A session that
 * cannot produce a token returns null, and the caller restores the tab
 * without its history rather than failing to restore the tab at all.
 */
interface EngineState

/**
 * The page-world scripts this session installs at document start.
 *
 * These are the app's own scripts -- the device shim, the password-manager
 * bridge, the wallet dApp provider -- and they must run in the page's own
 * world before any page script, because they define globals the page calls
 * (`window.RoomWallet`, `window.RoomVault`) and, for the device shim, because
 * a claim installed after the page's first script is a claim that script
 * already measured. How that is achieved is engine business; this type only
 * carries the text.
 */
data class EnginePageScripts(
    val deviceShim: String?,
    val vaultBridge: String,
    val walletProvider: String
)

/**
 * One browsing session: a tab.
 *
 * DELIBERATELY NOT A DATA CLASS, and implementations must not override
 * `equals`/`hashCode`. The tab manager indexes sessions in an identity map and
 * reads it from background threads. That is only sound while two distinct
 * sessions can never compare equal, which is the property a `WebView` had by
 * inheriting identity equality. An implementation that added value equality
 * would make two live tabs collide in that map, and the failure would appear
 * as one tab's state being applied to another -- far from the cause. The
 * reasoning is carried over verbatim from the WebView edition's tab manager,
 * where it was written down the first time.
 *
 * THREADING. `url`, `title`, `progress` and the two history flags are written
 * by the engine's own callbacks and may be read from any thread, so
 * implementations publish them safely rather than as plain fields. Every
 * command is callable from any thread and is dispatched to the engine's
 * required thread internally.
 */
interface EngineSession {

    /**
     * App-assigned identity of this session (the tab id). Never an engine
     * value -- the app owns it and the engine merely carries it.
     */
    val id: String

    /**
     * The view that renders this session, for the Compose tree.
     *
     * Hosts must not downcast it. Attaching and detaching it is NOT just
     * `addView`/`removeView` for every engine: GeckoView requires its session
     * to be released before another is attached, so hosts go through
     * [attachTo] / [detach] instead of touching the hierarchy themselves.
     */
    val view: View

    /**
     * The current top-level URL, or null before the first navigation.
     *
     * This is the trust anchor that used to be `WebView.getUrl()`, and it is
     * populated ONLY from the engine's own navigation callback for a top-level
     * frame. It is never supplied by the page, and it is readable from any
     * thread -- both properties are load-bearing for the wallet bridge, which
     * decides whether to answer a dApp request from it.
     *
     * KNOWN SEMANTIC DIFFERENCE between editions: WebView reports the
     * committed document, GeckoView reports at navigation start and on
     * redirects, so this value can be one hop ahead of what is on screen.
     */
    val url: String?

    /** The document title, or null when unknown. */
    val title: String?

    /** Load progress 0..100. */
    val progress: Int

    /** Whether this session currently has a live engine behind it. */
    val isOpen: Boolean

    /** History state, as the engine last reported it. */
    val canGoBack: Boolean
    val canGoForward: Boolean

    /** Install the listener. Replaces any previous one. */
    fun setListener(listener: EngineSessionListener?)

    /** Navigate to [uri]. The URI must already be classified by the caller. */
    fun loadUri(uri: String)

    fun reload()

    /** Abandon the in-flight load, keeping the current document. */
    fun stop()

    fun goBack()

    fun goForward()

    /**
     * Whether this session is visible. A session that is not active may have
     * its compositor parked, so a background tab costs far less.
     */
    fun setActive(active: Boolean)

    /**
     * Run [script] in the page and hand its result to [callback].
     *
     * The replacement for `WebView.evaluateJavascript(script, callback)`.
     *
     * ASYNCHRONOUS IN BOTH EDITIONS, and callers must treat it that way even
     * where the WebView implementation happens to answer quickly. [callback]
     * receives the JSON-encoded result, or null when the script could not be
     * delivered at all -- a document that has not started yet has nowhere to
     * run, and that is a real state rather than an error.
     */
    fun evaluateJs(script: String, callback: ((String?) -> Unit)? = null)

    /**
     * Set the page-world scripts for this session's documents.
     *
     * Every call replaces the previous set rather than adding to it, because
     * the app re-applies configuration whenever settings change and stacking a
     * second copy of the device shim per edit would be a leak.
     */
    fun setPageScripts(scripts: EnginePageScripts)

    /**
     * Capture the current rendering as a thumbnail, or null when unavailable.
     *
     * A session that is not attached to a display has nothing to capture and
     * reports null immediately rather than blocking; callers already only ask
     * for the visible tab.
     */
    fun capturePixels(onResult: (Bitmap?) -> Unit)

    /**
     * Render the current document to PDF and hand the raw bytes to [onResult].
     *
     * [onResult] receives null when the engine cannot produce one -- a session
     * that is not attached, or a renderer that failed. The callback is invoked
     * on the main thread, and the stream, when non-null, is the caller's to
     * read and close off the main thread.
     */
    fun saveAsPdf(onResult: (java.io.InputStream?) -> Unit)

    // NOTE: there is deliberately no setDesktopMode() here. Switching desktop
    // mode is not something a session can do to itself -- it needs the
    // profile, because leaving desktop mode means restoring the profile's own
    // identity, and a session does not hold one. The operation lives on
    // [EngineHost.applyDesktopMode], which has both. An earlier draft declared
    // it here and the GeckoView implementation could not have honoured it:
    // the only thing it could have done with a bare Boolean is set the mode
    // and leave the previous override installed, which is a session claiming
    // to be desktop while sending a mobile user agent.

    /** Attach this session's view to a host, performing any engine handover. */
    fun attachTo(host: android.view.ViewGroup)

    /** Detach the view and release any engine binding held by the host. */
    fun detach()

    /** Save this session's history so it can be restored later, if possible. */
    fun saveState(): EngineState?

    /**
     * Restore a token previously produced by [saveState], and report whether
     * it actually produced any history.
     *
     * THE RETURN VALUE IS NOT DECORATION. The caller's next line, when the
     * answer is false, is to load the tab's URL afresh -- because a tab whose
     * state could not be restored must still show something, and reloading the
     * entity URL is exactly what it showed before. Without the answer, the
     * caller has to guess, and the obvious guess is wrong: the session's own
     * "can go back" flag is populated by the engine's back/forward callbacks,
     * which have not run yet on the line after a restore. Reading it there
     * always says false, so every restored tab was reloaded from its URL and
     * the state that had just been restored was thrown away -- the tab lost
     * its history and scroll position on every reopen and every eviction.
     *
     * Implementations answer from the restore itself, synchronously: WebView
     * from the back/forward list the restore populated, GeckoView from whether
     * the session state it was handed carried any entries. A token that yields
     * no history returns false, which is the same "nothing to restore"
     * answer as the WebView edition's empty-bundle guard.
     */
    fun restoreState(state: EngineState): Boolean

    /**
     * Leave fullscreen, if this session is in it.
     *
     * The page cannot be asked and cannot be relied on to ask: the app's Back
     * handler owns leaving fullscreen, and [EngineSessionListener.onFullScreen]
     * only REPORTS the engine's state -- it is not a lever. Without this
     * member the app can set its own fullscreen flag to false while the
     * engine's fullscreen view stays mounted over the web area, with the page
     * still believing it is fullscreen: the chrome comes back but the user
     * cannot see it, and nothing is left that can undo it.
     *
     * Idempotent, and a no-op for a session that is not in fullscreen.
     */
    fun exitFullScreen()

    /**
     * Highlight every match of [query] in the current document.
     *
     * The replacement for `WebView.findAllAsync(query)`. A later call replaces
     * the previous highlights, and an empty [query] clears them rather than
     * searching for nothing. Stepping between matches is the app's own job --
     * it runs `window.find` through [evaluateJs] for that.
     */
    fun findInPage(query: String)

    /**
     * Drop the highlights installed by [findInPage].
     *
     * The replacement for `WebView.clearMatches()`, and a no-op when there is
     * nothing highlighted.
     */
    fun clearFindMatches()

    /**
     * Erase this session's transient browsing artifacts: session cookies and
     * form data.
     *
     * SCOPE IS THE POINT. [EngineHost.clearBrowsingData] erases the whole
     * profile -- every tab, every site -- and is the wrong instrument for
     * "this session is over": it would sign the user out of everything to
     * close one private tab. This is the narrower operation the private-tab
     * promise and the per-site "clear data" action both need.
     *
     * It is a real erase and not a formality: the private-tab surface tells
     * the user session cookies are gone when the last private tab closes, and
     * an empty implementation makes that text false.
     */
    fun clearSessionData()

    /** Tear the session down. Idempotent: calling it twice must be harmless. */
    fun close()
}
