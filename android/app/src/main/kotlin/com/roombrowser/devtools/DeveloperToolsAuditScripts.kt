package com.roombrowser.devtools

import kotlinx.serialization.Serializable

/**
 * What a page can observe about itself for the Audit panel.
 *
 * THIS IS NOT LIGHTHOUSE AND DOES NOT PRETEND TO BE. Lighthouse loads a page in
 * a controlled environment, throttles it, and scores it against a scoring model
 * -- none of which is available here, and a number invented to look like its
 * number would be the worst thing this panel could print. So the probe answers
 * only questions a page can answer about itself, each one a fact rather than a
 * rating, and the panel prints facts.
 *
 * THE SIGNALS THAT ARE ABSENT ARE ABSENT FOR A REASON. Layout shift cannot be
 * read after the fact -- `getEntriesByType('layout-shift')` is empty and only a
 * PerformanceObserver installed at document start ever sees it -- and installing
 * one would change the page being measured. Response headers are not readable
 * from the page at all, so the CSP line below is the `<meta>` declaration and is
 * named as one. Both are stated in the panel rather than approximated.
 *
 * SYNCHRONOUS, like the overview and the security probe: nothing here needs a
 * promise, so there is nothing to poll and the panel answers in one round trip.
 */
internal object DeveloperToolsAuditScripts {

    private const val SAFE = """
        function __rbSafe(f) { try { var v = f(); return v === undefined ? null : v; } catch (e) { return null; } }
    """

    /** How many alt-less images the probe names. The count is reported separately, so a capped list cannot read as the whole. */
    internal const val MISSING_ALT_CAP = 10

    fun auditProbeJs(): String = """
        (function () {
          $SAFE
          function metaContent(names) {
            var all;
            try { all = document.querySelectorAll('meta'); } catch (e) { return null; }
            for (var i = 0; i < all.length; i++) {
              var key = (all[i].getAttribute('http-equiv') || all[i].getAttribute('name') || '').toLowerCase();
              if (key && names.indexOf(key) >= 0) return all[i].getAttribute('content') || '';
            }
            return '';
          }
          function linkRel(rel) {
            var found;
            try { found = document.querySelector('link[rel="' + rel + '"]'); } catch (e) { return null; }
            return found ? (found.getAttribute('href') || '') : '';
          }
          function ms(value) {
            // ZERO IS "NOT MEASURED", NOT "INSTANT". Navigation Timing reports
            // 0 in every field until the corresponding event has fired, so a
            // panel that printed it would call a still-loading page fast.
            return (typeof value === 'number' && isFinite(value) && value > 0) ? Math.round(value) : null;
          }
          var nav = __rbSafe(function () { return performance.getEntriesByType('navigation')[0]; });
          var images = __rbSafe(function () { return document.images; }) || [];
          var missing = [];
          var missingCount = 0;
          for (var i = 0; i < images.length; i++) {
            var image = images[i];
            // An EMPTY alt is a deliberate "decorative" and is not a defect;
            // only a missing attribute is. Counting both would report every
            // correct page as broken.
            if (image.getAttribute('alt') === null) {
              missingCount++;
              if (missing.length < $MISSING_ALT_CAP) {
                missing.push(String(image.currentSrc || image.src || 'image ' + i));
              }
            }
          }
          return JSON.stringify({
            title: __rbSafe(function () { return document.title || ''; }),
            lang: __rbSafe(function () {
              return (document.documentElement && document.documentElement.getAttribute('lang')) || '';
            }),
            charset: __rbSafe(function () { return document.characterSet || ''; }),
            viewport: metaContent(['viewport']),
            description: metaContent(['description']),
            robots: metaContent(['robots']),
            cspMeta: metaContent(['content-security-policy', 'content-security-policy-report-only']),
            protocol: __rbSafe(function () { return location.protocol; }),
            isSecureContext: __rbSafe(function () { return window.isSecureContext; }),
            compatMode: __rbSafe(function () { return document.compatMode || ''; }),
            manifestUrl: linkRel('manifest'),
            serviceWorker: __rbSafe(function () {
              if (!('serviceWorker' in navigator)) return 'unsupported';
              return navigator.serviceWorker.controller ? 'controlling' : 'supported-uncontrolled';
            }),
            imageCount: images.length,
            missingAltCount: missingCount,
            missingAltSamples: missing,
            timeToFirstByteMs: __rbSafe(function () { return nav ? ms(nav.responseStart) : null; }),
            domContentLoadedMs: __rbSafe(function () { return nav ? ms(nav.domContentLoadedEventEnd) : null; }),
            loadMs: __rbSafe(function () { return nav ? ms(nav.loadEventEnd) : null; })
          });
        })()
    """.trimIndent()
}

/**
 * Decoded [DeveloperToolsAuditScripts.auditProbeJs] output.
 *
 * Nullable throughout, and the null is load-bearing: it is the difference
 * between "the page looked and there was none" and "the page never answered the
 * question". The formatter prints those as two different things.
 *
 * PUBLIC ON PURPOSE, unlike everything else in this package: `InspectorSession`
 * is a public class and this is the return type of one of its public reads, and
 * a public function may not expose an internal type. The same reason
 * [PageOverview] and [ApplicationProbe] carry no modifier.
 */
@Serializable
data class AuditProbe(
    val title: String? = null,
    val lang: String? = null,
    val charset: String? = null,
    val viewport: String? = null,
    val description: String? = null,
    val robots: String? = null,
    val cspMeta: String? = null,
    val protocol: String? = null,
    val isSecureContext: Boolean? = null,
    val compatMode: String? = null,
    val manifestUrl: String? = null,
    val serviceWorker: String? = null,
    val imageCount: Int? = null,
    val missingAltCount: Int? = null,
    val missingAltSamples: List<String>? = null,
    val timeToFirstByteMs: Int? = null,
    val domContentLoadedMs: Int? = null,
    val loadMs: Int? = null
)
