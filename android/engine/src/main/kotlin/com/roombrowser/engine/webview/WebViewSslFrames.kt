package com.roombrowser.engine.webview

import java.net.URI

/**
 * Which frame a failing certificate belongs to.
 *
 * WHY THIS IS HERE AND NOT ABOVE THE FACADE: `onReceivedSslError` is the one
 * WebView callback that does not say which frame it fired for -- an `SslError`
 * carries a url and nothing else -- while the facade's
 * [com.roombrowser.engine.EngineSessionListener.onPageError] demands an
 * `isTopLevel` answer. That flag therefore has to be produced inside the
 * engine, from state the engine alone holds: the most recent main-frame url
 * any callback has seen, and the engine's committed url.
 *
 * WHY IT MATTERS AT ALL: without the discrimination, a single third-party
 * sub-resource with a broken certificate (an ad iframe, a tracker pixel, a CDN
 * with an expired cert) replaced an otherwise perfectly good page with the
 * full-screen "Connection Not Secure" error -- the reported symptom, and not
 * something any other browser does: Chrome blocks the one resource and keeps
 * the page. Ported verbatim from `android/app/src/main/kotlin/com/roombrowser/
 * browser/SslFrameMatch.kt`, which existed for exactly this reason.
 *
 * IT USES [java.net.URI] RATHER THAN `android.net.Uri` for the reason the
 * original recorded: `Uri.parse` never fails -- it returns a `Uri` with a null
 * scheme for input that is not a url at all -- so the "unparseable" branch this
 * decision depends on was unreachable with it. The app's version lived apart
 * from the client so a JVM test could pin it; the engine module's unit-test
 * classpath has no android.jar at all, so the same property holds here by
 * construction.
 */
internal object WebViewSslFrames {

    /**
     * Whether a certificate failure for [failingUrl] belongs to the main
     * frame, given the last main-frame url seen ([mainFrameUrl]) and the
     * engine's committed url ([committedUrl], i.e. `WebView.getUrl`).
     *
     * Compares SCHEME + HOST + PORT. Port is included because a service on
     * another port of the same host can present a different certificate;
     * scheme is included because an https sub-resource under an http page is
     * a different origin with a different certificate.
     *
     * An unparseable failing url answers TRUE -- treat it as a main frame. So
     * does one that matches nothing we have recorded about any main frame,
     * which is the case on a first navigation that fails before the callbacks
     * that record one have run.
     *
     * The safe direction to be wrong in: a false "main frame" shows the user
     * an error page they can act on, while a false "sub-resource" would
     * silently swallow a failed navigation and leave the old page on screen
     * with no explanation at all. The asymmetry is the whole argument -- one
     * costs a retry, the other costs the truth.
     */
    fun isMainFrameFailure(
        failingUrl: String?,
        mainFrameUrl: String?,
        committedUrl: String?
    ): Boolean {
        val failing = authorityOf(failingUrl) ?: return true
        val known = listOfNotNull(authorityOf(mainFrameUrl), authorityOf(committedUrl))
        // Nothing was ever recorded about a main frame, so there is nothing to
        // compare against -- and "no evidence it is a sub-resource" is not
        // evidence that it is one.
        if (known.isEmpty()) return true
        return failing in known
    }

    /** `scheme://host:port`, with the scheme's default port filled in. */
    fun authorityOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val port = when {
            uri.port > 0 -> uri.port
            scheme == "https" -> 443
            scheme == "http" -> 80
            else -> -1
        }
        return "$scheme://$host:$port"
    }
}
