package com.roombrowser.engine

import android.content.Intent
import android.net.Uri

/**
 * What to do about a navigation the engine is about to start.
 *
 * A decision rather than a boolean because the https-upgrade policy needs a
 * third answer: "not this URL, that one". Expressing it as a boolean plus a
 * side-effecting `loadUri` call would put the substitute load outside the
 * engine's own navigation accounting, which is where it goes wrong.
 */
sealed interface NavigationDecision {

    /** Let the engine load it. */
    object Allow : NavigationDecision

    /** Abandon the navigation. Nothing is shown and nothing is loaded. */
    object Block : NavigationDecision

    /**
     * Load [url] instead. The caller has already classified it.
     *
     * What happens to the original navigation is the engine's business; the
     * contract is only that the session ends up loading [url] and that the
     * original is not loaded as well.
     */
    data class LoadDifferent(val url: String) : NavigationDecision
}

/**
 * The answer to a permission request: camera, microphone, geolocation.
 *
 * SINGLE-SHOT, and implementations must ignore a second call. Both engines
 * hand out one callback per request and hang the requesting document until it
 * is answered, so answering twice is not a harmless mistake: it is a double
 * release of something the engine considers a single-use handle.
 *
 * [grant] grants everything that was requested. There is deliberately no
 * "grant the camera but not the microphone": an engine-neutral vocabulary for
 * picking individual devices would be a vocabulary the WebView edition cannot
 * honour, and the app's own sheet already asks about the whole request.
 */
interface PermissionResponder {
    fun grant()
    fun deny()
}

/**
 * The answer to an HTTP authentication challenge.
 *
 * SINGLE-SHOT for the same reason as [PermissionResponder]: the engine holds
 * the navigation open until this is called, and calls it exactly once.
 *
 * [proceed] is the only path that may reach the network with credentials. An
 * implementation that receives neither call leaves the navigation hanging,
 * which reads to the user as a page that never loads.
 */
interface HttpAuthResponder {
    fun proceed(username: String, password: String)
    fun cancel()
}

/**
 * How a failed load failed, in the only categories the app's policy needs.
 *
 * This exists because the two engines disagree about where a certificate
 * failure is reported -- the WebView edition has a dedicated
 * `onReceivedSslError`, GeckoView reports it as a load error carrying a
 * security code -- and the app must render a different screen for it ("this
 * connection is not secure") than for a site that is merely unreachable.
 * Encoding that distinction as a raw engine error code would mean the shared
 * policy branches on two different numbering systems.
 */
enum class PageErrorKind {
    /** The connection was refused because its certificate is not trusted. */
    CERTIFICATE,

    /**
     * The host's name could not be resolved.
     *
     * Separate from [TRANSPORT] because the two lead to different advice: an
     * unresolvable name is usually a typo or a network that is not there,
     * while a refused or dropped connection means the host exists and did not
     * answer. Both engines report the difference -- WebView as
     * `ERROR_HOST_LOOKUP`, GeckoView as `NS_ERROR_UNKNOWN_HOST` -- so folding
     * them together here throws away a distinction the user can act on.
     */
    DNS,

    /** The host could not be reached, or the connection dropped. */
    TRANSPORT,

    /** Anything else the engine reported. */
    OTHER
}

/**
 * The numbering [EngineSessionListener.onPageError] reports `errorCode` in.
 *
 * THE APP'S POLICY BRANCHES ON THESE NUMBERS, which is why they are a stated
 * contract and not "whatever the engine said". Two places above the facade
 * read the code and make a decision with it:
 *
 *  - `HttpsUpgradeFallbackPolicy.isRecoverable`, which decides whether a
 *    failed https upgrade gets one automatic retry over the original http
 *    URL. It is keyed to [TIMEOUT] and [CONNECT] (and [UNKNOWN]); it
 *    deliberately excludes [HOST_LOOKUP], because a name that does not
 *    resolve will not resolve over http either.
 *  - `BrowserViewModel.sslErrorText`, which turns a [PageErrorKind.CERTIFICATE]
 *    code into the sentence the user reads, so the numbers there are the
 *    `SSL_*` family below.
 *
 * Both were written against WebView and are unchanged, which is the point:
 * the facade absorbs the numbering difference so the shared policy never has
 * to know which engine is underneath. The values are the platform's own
 * (`WebViewClient.ERROR_*`, `SslError.SSL_*`) rather than new invented ones,
 * so the WebView edition passes its codes through untouched and the two
 * editions agree by construction.
 *
 * NOT A COMPLETE MIRROR OF EITHER ENGINE. These are the codes the shared
 * policy distinguishes, and an engine that cannot tell two of them apart
 * reports the one that produces the right BEHAVIOUR and the truthful
 * sentence, rather than inventing a distinction it does not have. The
 * GeckoView certificate codes are the clear case: it reports that a
 * certificate is bad but not whether it expired or names the wrong host, so
 * it answers [CERT_INVALID] for every certificate error instead of guessing
 * at "expired" and being wrong half the time.
 */
object EngineErrorCode {
    /**
     * `val`, not `const val`, on purpose: these are the platform's constants
     * rather than literals repeated here, so the two editions cannot drift
     * apart from android.jar, and nothing needs them in a compile-time
     * constant context.
     */

    /** The host's name could not be resolved. */
    val HOST_LOOKUP = android.webkit.WebViewClient.ERROR_HOST_LOOKUP

    /** The connection could not be established, or it dropped. */
    val CONNECT = android.webkit.WebViewClient.ERROR_CONNECT

    /** The connection was established but the peer did not answer in time. */
    val TIMEOUT = android.webkit.WebViewClient.ERROR_TIMEOUT

    /** A TLS handshake failed on an endpoint the app itself upgraded. */
    val FAILED_SSL_HANDSHAKE = android.webkit.WebViewClient.ERROR_FAILED_SSL_HANDSHAKE

    /**
     * A failure the engine could not classify. Deliberately the same slot the
     * app's retry policy already treats as "some stacks report a connect
     * failure this way" -- an unrecognised error is reported here rather than
     * under a specific meaning the engine cannot support.
     */
    val UNKNOWN = android.webkit.WebViewClient.ERROR_UNKNOWN

    /** The site's certificate is not valid. */
    val CERT_INVALID = android.net.http.SslError.SSL_INVALID

    /** The site's certificate is not trusted for another reason. */
    val CERT_UNTRUSTED = android.net.http.SslError.SSL_UNTRUSTED
}

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
 * callback in the pinned version -- a certificate failure arrives as a load
 * error with [PageErrorKind.CERTIFICATE]) simply does not implement it, rather
 * than being forced to fake one. A member with no implementation is a real
 * gap and has to be recorded as one; it must never be a default that looks
 * like a working feature.
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
     * A navigation is about to start. Answer what should happen to it.
     *
     * THIS IS WHERE THE APP'S NAVIGATION POLICY LIVES: refusing a host on the
     * malicious-site list, and rewriting an `http://` load to `https://` when
     * the profile asks for upgrades. Both are decisions about a URL that has
     * not been loaded yet, so they cannot be expressed as a report after the
     * fact.
     *
     * Returning [NavigationDecision.LoadDifferent] is how an upgrade is
     * performed, and the failed-upgrade fallback is the caller's: it sees the
     * https load fail through [onPageError] and may answer this callback with
     * the original http URL on the retry. That fallback lives above the
     * facade, once, for both editions.
     *
     * DELIBERATELY NO DEFAULT DECISION BEYOND [NavigationDecision.Allow]: an
     * engine that never calls this is an engine where nothing is blocked, and
     * that has to be visible in the adapter rather than hidden in a default
     * that reads as "checked, and fine".
     */
    fun onNavigationRequest(
        session: EngineSession,
        url: String,
        isTopLevel: Boolean,
        hasUserGesture: Boolean
    ): NavigationDecision = NavigationDecision.Allow

    /**
     * A sub-resource load -- script, image, stylesheet, `fetch`, XHR -- is
     * about to start. Return true to block it.
     *
     * The app's content blocking decides here, against the owning session's
     * page host: ads, trackers, cross-site requests and known-malicious hosts.
     * [isForMainFrame] is false for everything this exists to block; it is
     * passed because a caller that makes a decision from the page's own URL
     * needs to know when the request IS the page.
     *
     * ENGINE NOTE, and a real difference to keep in sight: the WebView edition
     * serves this from `shouldInterceptRequest`, which sees every request. The
     * GeckoView edition has no per-request delegate for arbitrary
     * sub-resources and answers this from a bundled WebExtension instead, so
     * until that lands the GeckoView edition blocks nothing here and the
     * profile's own tracking-protection settings are the only thing standing
     * between the page and a tracker. That is a gap, not a design.
     */
    fun onResourceRequest(
        session: EngineSession,
        url: String,
        isForMainFrame: Boolean
    ): Boolean = false

    /**
     * A main-frame load failed.
     *
     * Return true to have handled it; returning false lets the engine show its
     * own error surface. Recovery -- retrying the original http URL after a
     * failed https upgrade -- is the CALLER's policy, implemented once above
     * the facade. The engine only reports what happened.
     *
     * [kind] is what the shared policy branches on for what to SHOW. [errorCode]
     * is what it branches on for what to DO, and it is therefore reported in
     * [EngineErrorCode]'s numbering, not the engine's own -- an engine that
     * passed its native code through would make the app's retry policy and its
     * certificate wording silently wrong on that edition, with no compile
     * error and no failing test to catch it. [description] is the engine's
     * own free-form text and is never parsed; it is passed for the diagnostics
     * screen only.
     */
    fun onPageError(
        session: EngineSession,
        url: String?,
        kind: PageErrorKind,
        isTopLevel: Boolean,
        errorCode: Int,
        description: String?
    ): Boolean = false

    /**
     * May this session open another window at all?
     *
     * THE GATE, and it is a separate question from [onNewWindowResolved]
     * because that is how both engines actually work: the answer is decided
     * from the session and the gesture, BEFORE any URL exists. WebView asks it
     * from `onCreateWindow` before a transport WebView is built; GeckoView asks
     * it from `onNewSession` before a session is created. Returning false
     * refuses the popup outright and costs no renderer.
     *
     * WHY IT IS NOT ONE METHOD TAKING A NULLABLE URL. An earlier form of this
     * interface asked once, with `url = null` meaning "gate", and the app-side
     * implementation read a null url as "there is nothing to open" and refused.
     * Both halves were individually reasonable and together refused EVERY
     * popup -- every `target="_blank"` link and every `window.open()` -- with
     * no error surfaced anywhere, because a bare `window.open()` never
     * produces a URL at all and so the resolving call never came. Two
     * questions that must be answered in order are two methods.
     *
     * [hasUserGesture] is what separates a link the user pressed from a script
     * that decided to open a window, and the profile's popup policy is decided
     * from it: a popup with no gesture is blocked even where popups are
     * allowed. An engine that cannot supply the flag for a given request
     * reports false, which errs toward blocking rather than toward letting an
     * unsolicited window through.
     */
    fun onNewWindowRequest(session: EngineSession, hasUserGesture: Boolean): Boolean = false

    /**
     * The popup's target URL is now known. Open it as a new tab.
     *
     * Called only after [onNewWindowRequest] returned true for the SAME popup,
     * so the decision has already been made and there is deliberately no
     * return value: a refusal here would leave the engine holding a half-built
     * popup with no way to finish or discard it.
     *
     * [url] is non-null by construction -- it is the whole reason this second
     * call exists.
     */
    fun onNewWindowResolved(session: EngineSession, url: String, hasUserGesture: Boolean) {}

    /**
     * The page started a download, or navigated to something the engine cannot
     * render itself.
     *
     * Return true to have handled it. The four descriptive parameters are the
     * ones the WebView edition's `DownloadListener` receives -- the URL, the
     * user agent that made the request, the server's `Content-Disposition`,
     * and the MIME type -- and GeckoView's `WebResponse` supplies the same
     * set. [contentLength] is -1 when the engine does not know it, which is
     * the value the WebView edition already sees.
     *
     * NOT CONSUMING THE RESPONSE is the caller's business: an engine that
     * offers a response and is told the app handled it will not fetch the
     * body itself, so returning true and then doing nothing is a silent failed
     * download rather than a harmless no-op.
     */
    fun onDownloadRequest(
        session: EngineSession,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ): Boolean = false

    /**
     * The page asked for the camera or the microphone.
     *
     * BOTH ENGINES HANG THE DOCUMENT UNTIL THIS IS ANSWERED. A request that is
     * never answered is not "the sheet did not appear" -- it is a page whose
     * `getUserMedia` promise never settles, so an unanswered request is a bug
     * with a visible symptom far from its cause.
     */
    fun onMediaPermissionRequest(
        session: EngineSession,
        origin: String,
        wantsVideo: Boolean,
        wantsAudio: Boolean,
        responder: PermissionResponder
    ) {}

    /**
     * The page asked for the user's location. [origin] may be null when the
     * engine cannot attribute the request, and a null origin is not a reason
     * to grant anything.
     */
    fun onGeolocationRequest(session: EngineSession, origin: String?, responder: PermissionResponder) {}

    /**
     * The site asked for a username and password (HTTP authentication).
     *
     * [host] and [realm] are what the engine says the challenge is for; the
     * app shows them and, on the user's answer, calls [responder] exactly
     * once. Cancelling abandons the navigation, which is what the engine does
     * with it.
     */
    fun onHttpAuthRequest(session: EngineSession, host: String, realm: String, responder: HttpAuthResponder) {}

    /**
     * The page entered or left fullscreen, or the engine failed to.
     *
     * A STATE NOTIFICATION, NOT A VIEW. The WebView edition hands the app a
     * separate view to render and a callback to return it; GeckoView renders
     * fullscreen content inside its own view and reports the transition only.
     * Rather than put a view in the facade that one edition has nothing to put
     * in it, the engine adapter owns the surface and the app only has to react
     * to the state -- which is all the shared code does with it anyway.
     *
     * A session that is left believing it is fullscreen after the user backed
     * out is a browser with no visible chrome, so an adapter must be able to
     * report the exit as well as the entry.
     */
    fun onFullScreen(session: EngineSession, fullScreen: Boolean) {}

    /**
     * The page asked for a file. The engine builds the picker Intent (so the
     * app never has to know which engine asked), and [accept] answers the
     * engine with the chosen documents, or null for a cancelled picker.
     *
     * The default dismisses: a session with no UI, like a headless agent turn,
     * must answer rather than leave the page's input waiting forever.
     */
    fun onFileChooserRequest(intent: Intent, accept: (Array<Uri>?) -> Unit) {
        accept(null)
    }

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
