package com.roombrowser.devtools

import kotlinx.serialization.Serializable

/**
 * The static probe scripts the Developer Tools panels run in the page.
 *
 * These are authored by the app and shipped with it — the same class of thing
 * as [com.roombrowser.agent.PageInjector]'s snapshot script — and are unrelated
 * to the agent's `run_js` tool, which runs a script the *model* wrote.
 *
 * Every field is read through a try/catch, so a page that has broken
 * `document.title` (or removed a global) yields a null field instead of
 * failing the whole probe.
 */
internal object DeveloperToolsScripts {

    private const val SAFE = """
        function __rbSafe(f) { try { var v = f(); return v === undefined ? null : v; } catch (e) { return null; } }
    """

    /**
     * Page-level facts that exist on both engines with no extension and no
     * protocol: identity, document shape and which storage surfaces the page
     * is allowed to use.
     *
     * Everything here is a *count* or a *presence flag*, deliberately — it is
     * cheap on a 320dp emulator and it cannot leak page content into a panel
     * the user did not ask to inspect.
     */
    fun pageOverviewJs(): String = """
        (function () {
          $SAFE
          return JSON.stringify({
            url: __rbSafe(function () { return location.href; }),
            origin: __rbSafe(function () { return location.origin; }),
            title: __rbSafe(function () { return document.title; }),
            readyState: __rbSafe(function () { return document.readyState; }),
            lang: __rbSafe(function () { return document.documentElement.getAttribute('lang'); }),
            charset: __rbSafe(function () { return document.characterSet; }),
            contentType: __rbSafe(function () { return document.contentType; }),
            nodes: __rbSafe(function () { return document.getElementsByTagName('*').length; }),
            scripts: __rbSafe(function () { return document.scripts.length; }),
            images: __rbSafe(function () { return document.images.length; }),
            forms: __rbSafe(function () { return document.forms.length; }),
            links: __rbSafe(function () { return document.links.length; }),
            frames: __rbSafe(function () { return window.frames.length; }),
            viewportWidth: __rbSafe(function () { return window.innerWidth; }),
            viewportHeight: __rbSafe(function () { return window.innerHeight; }),
            devicePixelRatio: __rbSafe(function () { return window.devicePixelRatio; }),
            isSecureContext: __rbSafe(function () { return window.isSecureContext; }),
            serviceWorker: __rbSafe(function () {
              if (!('serviceWorker' in navigator)) return 'absent';
              return navigator.serviceWorker.controller ? 'controlled' : 'registered-none';
            }),
            localStorage: __rbSafe(function () { return 'localStorage' in window; }),
            sessionStorage: __rbSafe(function () { return 'sessionStorage' in window; }),
            indexedDb: __rbSafe(function () { return 'indexedDB' in window; }),
            caches: __rbSafe(function () { return 'caches' in window; }),
            storageEstimate: __rbSafe(function () {
              return !!(navigator.storage && navigator.storage.estimate);
            }),
            notificationPermission: __rbSafe(function () {
              return typeof Notification === 'undefined' ? null : Notification.permission;
            }),
            manifest: __rbSafe(function () {
              var link = document.querySelector('link[rel="manifest"]');
              return link ? link.href : null;
            })
          });
        })()
    """.trimIndent()

    /**
     * What the page itself knows about the resources it loaded: timing, size,
     * type and whether the entry is cross-origin.
     *
     * This is a PULL, run only when the user asks for it. `getEntriesByType`
     * already returns the buffered history, so there is no observer to keep
     * alive and nothing polls the page — which is also what keeps this clear of
     * the bounded eval queue on GeckoView.
     */
    fun networkProbeJs(): String = """
        (function () {
          $SAFE
          var page = __rbSafe(function () { return location.origin; });
          function sameOrigin(u) {
            try { return new URL(u).origin === page; } catch (e) { return false; }
          }
          var rows = __rbSafe(function () {
            return performance.getEntriesByType('resource').map(function (r) {
              return {
                name: r.name,
                initiatorType: r.initiatorType || null,
                startTime: r.startTime,
                duration: r.duration,
                transferSize: r.transferSize,
                encodedBodySize: r.encodedBodySize,
                decodedBodySize: r.decodedBodySize,
                responseStatus: (typeof r.responseStatus === 'number' ? r.responseStatus : null),
                nextHopProtocol: r.nextHopProtocol || null,
                crossOrigin: !sameOrigin(r.name)
              };
            });
          });
          return JSON.stringify(rows === null ? [] : rows.slice(-500));
        })()
    """.trimIndent()
}

/** Decoded [DeveloperToolsScripts.pageOverviewJs] output. Nullable throughout: a field the page refused to answer is absent, never zero. */
@Serializable
data class PageOverview(
    val url: String? = null,
    val origin: String? = null,
    val title: String? = null,
    val readyState: String? = null,
    val lang: String? = null,
    val charset: String? = null,
    val contentType: String? = null,
    val nodes: Int? = null,
    val scripts: Int? = null,
    val images: Int? = null,
    val forms: Int? = null,
    val links: Int? = null,
    val frames: Int? = null,
    val viewportWidth: Int? = null,
    val viewportHeight: Int? = null,
    val devicePixelRatio: Double? = null,
    val isSecureContext: Boolean? = null,
    val serviceWorker: String? = null,
    val localStorage: Boolean? = null,
    val sessionStorage: Boolean? = null,
    val indexedDb: Boolean? = null,
    val caches: Boolean? = null,
    val storageEstimate: Boolean? = null,
    val notificationPermission: String? = null,
    val manifest: String? = null
)
