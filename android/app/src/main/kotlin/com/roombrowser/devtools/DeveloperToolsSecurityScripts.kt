package com.roombrowser.devtools

import kotlinx.serialization.Serializable

/**
 * What the page itself can observe about its own transport security.
 *
 * THERE IS NO CERTIFICATE HERE, AND THERE CANNOT BE. A page cannot see the
 * certificate its own connection used, and on WebView neither can the app. So
 * this script answers the questions a page CAN answer -- how it was served,
 * whether a form would post in the clear, which of its subresources came over
 * plain HTTP -- and the certificate comes from the engine, or from the sentence
 * saying this edition cannot read one.
 *
 * RESPONSE HEADERS ARE ABSENT ON PURPOSE. A page cannot read the CSP header it
 * was served with, only a `<meta http-equiv>` declaration, so [cspMeta] is named
 * for what it is rather than presented as the policy actually in force. The same
 * goes for the referrer policy.
 *
 * The insecure-subresource list is capped: it is a debugging aid, not an
 * inventory, and the count is reported separately so a capped list cannot be
 * mistaken for a complete one.
 */
internal object DeveloperToolsSecurityScripts {

    private const val SAFE = """
        function __rbSafe(f) { try { var v = f(); return v === undefined ? null : v; } catch (e) { return null; } }
    """

    /** How many mixed-content URLs a row list carries. The count is reported separately. */
    internal const val SUBRESOURCE_CAP = 20

    fun securityProbeJs(): String = """
        (function () {
          $SAFE
          function metaContent(keys) {
            var all;
            try { all = document.querySelectorAll('meta'); } catch (e) { return null; }
            for (var i = 0; i < all.length; i++) {
              var key = (all[i].getAttribute('http-equiv') || all[i].getAttribute('name') || '').toLowerCase();
              if (key && keys.indexOf(key) >= 0) return all[i].getAttribute('content') || '';
            }
            return '';
          }
          var insecure = __rbSafe(function () {
            if (location.protocol !== 'https:') return [];
            var seen = [];
            performance.getEntriesByType('resource').forEach(function (r) {
              var url = String(r.name);
              if (url.indexOf('http://') === 0 && seen.indexOf(url) < 0) seen.push(url);
            });
            return seen;
          }) || [];
          return JSON.stringify({
            protocol: __rbSafe(function () { return location.protocol; }),
            isSecureContext: __rbSafe(function () { return window.isSecureContext; }),
            cspMeta: metaContent(['content-security-policy', 'content-security-policy-report-only']),
            referrerPolicyMeta: metaContent(['referrer']),
            formActionInsecure: __rbSafe(function () {
              if (location.protocol !== 'https:') return false;
              for (var i = 0; i < document.forms.length; i++) {
                var action = document.forms[i].getAttribute('action') || '';
                if (action.indexOf('http://') === 0) return true;
              }
              return false;
            }),
            subresourceCount: __rbSafe(function () {
              return performance.getEntriesByType('resource').length;
            }),
            insecureSubresourceCount: insecure.length,
            insecureSubresources: insecure.slice(0, $SUBRESOURCE_CAP)
          });
        })()
    """.trimIndent()
}

/**
 * Decoded [DeveloperToolsSecurityScripts.securityProbeJs] output.
 *
 * Nullable throughout, so "the page would not say" stays distinguishable from
 * "the page said no" -- which for `formActionInsecure` is the difference between
 * a broken probe and a safe page.
 */
@Serializable
data class SecurityProbe(
    val protocol: String? = null,
    val isSecureContext: Boolean? = null,
    val cspMeta: String? = null,
    val referrerPolicyMeta: String? = null,
    val formActionInsecure: Boolean? = null,
    val subresourceCount: Int? = null,
    val insecureSubresourceCount: Int? = null,
    val insecureSubresources: List<String>? = null
)
