package com.roombrowser.engine

/**
 * Everything an engine session reports upward, expressed without a single
 * engine type.
 *
 * WHY THIS EXISTS. The two editions of this browser differ in exactly one
 * place: the engine. Everything above it -- which host is blocked, when an
 * https upgrade falls back to http, how a dApp's wallet request is answered,
 * what the omnibox does with a bare word -- is product policy and must not be
 * written twice. That policy is written against concrete callbacks in the
 * WebView edition (a `WebViewClient` subclass); the equivalent GeckoView code
 * is a `NavigationDelegate`. Neither type can appear in shared code, so the
 * shared code is written against THIS interface instead, and each engine
 * supplies a thin adapter.
 *
 * The consequence worth stating plainly: adding a feature means implementing
 * its policy once, here, and mapping one more callback in each engine adapter.
 * It never means writing the feature twice.
 *
 * EVERY METHOD HAS A DEFAULT that does nothing. That is deliberate: it lets an
 * edition implement only the callbacks it can actually serve, so an engine
 * that has no equivalent of a signal (GeckoView has no certificate-error
 * callback that can be continued, by design) simply does not implement it,
 * rather than being forced to fake one.
 *
 * THREADING. Implementations may deliver these from the engine's own threads.
 * Every method must therefore be safe to call off the main thread, and no
 * implementation may assume otherwise -- the same requirement the existing
 * `WebViewClient` callbacks already carry.
 */
interface EngineSessionListener {

    /**
     * The session's visible URL changed.
     *
     * This is the ONLY source of a session's URL, and that is a security
     * property rather than a convenience: in the WebView edition
     * `WebView.getUrl()` is readable at any moment and can already name the
     * next document while the current one is still on screen. GeckoView
     * removed `getUrl()` outright, so both editions now derive the URL from
     * this callback and nothing else.
     *
     * [isTopLevel] is false for sub-frame navigation. Callers that make a
     * trust decision -- the wallet bridge above all -- MUST require true,
     * because a page can navigate an iframe to any origin it likes.
     *
     * [hasUserGesture] distinguishes a navigation the user asked for from one
     * the page started on its own. An engine that cannot tell the difference
     * for a given navigation reports false, which is the conservative answer.
     */
    fun onUrlChanged(session: EngineSession, url: String?, isTopLevel: Boolean, hasUserGesture: Boolean) {}

    /** The document's title changed, or became unknown (null). */
    fun onTitleChanged(session: EngineSession, title: String?) {}

    /** A load started. */
    fun onPageStarted(session: EngineSession, url: String?) {}

    /** A load finished. [success] is false when the engine gave up on it. */
    fun onPageFinished(session: EngineSession, url: String?, success: Boolean) {}

    /** Load progress, 0..100. */
    fun onProgress(session: EngineSession, progress: Int) {}

    /**
     * Whether the session can move in either direction right now.
     *
     * Reports state rather than reacting to a command, because the two engines
     * disagree about when this becomes true: WebView only knows after a load
     * settles, GeckoView pushes it whenever its history changes. Treating it
     * as state makes both correct.
     */
    fun onNavigationStateChanged(session: EngineSession, canGoBack: Boolean, canGoForward: Boolean) {}

    /**
     * A main-frame load failed.
     *
     * Return true to have handled it; returning false lets the engine show its
     * own error surface. Recovery -- retrying the original http URL after a
     * failed https upgrade -- is the CALLER's policy, implemented once above
     * the facade. The engine only reports what happened.
     */
    fun onPageError(session: EngineSession, url: String?, errorCode: Int, description: String?): Boolean = false

    /**
     * The page asked to open another window (target=_blank, window.open).
     *
     * Return true to have handled it, which in this app means "opened it as a
     * new tab". Returning false lets the engine decide, and GeckoView's own
     * answer is to refuse, which would break everyday links.
     */
    fun onNewWindowRequest(session: EngineSession, url: String?): Boolean = false

    /**
     * A message from one of this session's page-world scripts.
     *
     * THE TRANSPORT REPLACEMENT. GeckoView has no `@JavascriptInterface` and no
     * `evaluateJavascript`; the only way a page reaches native code is a
     * WebExtension port. Both of the app's existing native entry points --
     * `RoomWalletNative` for wallet requests and `RoomVaultNative` for
     * credential detection and fill -- arrive here instead, distinguished by
     * [channel].
     *
     * [payload] is page-controlled and MUST be treated as hostile: it is the
     * same trust level the `@JavascriptInterface` string argument had. The
     * session's [EngineSession.url] is the anchor to validate it against, and
     * that is engine-supplied.
     */
    fun onPageMessage(session: EngineSession, channel: String, payload: String) {}

    /**
     * The engine behind this session is gone -- a crash in its process, or a
     * close the app did not ask for. The handle is no longer usable and must
     * be replaced rather than reloaded.
     */
    fun onClosed(session: EngineSession) {}
}
