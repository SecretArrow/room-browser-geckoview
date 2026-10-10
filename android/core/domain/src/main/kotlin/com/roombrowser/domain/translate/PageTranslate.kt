package com.roombrowser.domain.translate

import com.roombrowser.domain.model.LanguagePresets
import java.net.URLEncoder

/**
 * Builds the Google Translate wrapper URL for a page.
 *
 * This lives in the domain rather than inside the dialog because the URL *is*
 * the feature -- a URL assembled in a Composable cannot be unit-tested, and the
 * dialog is the only place it was ever visible.
 */
object PageTranslate {

    /** The wrapper endpoint. Held as a constant so a test can pin it offline. */
    const val WRAPPER = "https://translate.google.com/translate"

    /**
     * True when [pageUrl] is a document a translator can actually fetch: a real
     * http(s) page.
     *
     * Not a cosmetic distinction. The start page (`about:home`), a `data:`
     * document and the synthetic `oct://` address have nothing behind them for
     * Google to retrieve, so the wrapper answers with its own error page -- the
     * dialog refuses those instead of opening a tab that can never work.
     */
    fun isTranslatable(pageUrl: String): Boolean {
        val url = pageUrl.trim()
        return url.startsWith("https://", ignoreCase = true) ||
            url.startsWith("http://", ignoreCase = true)
    }

    /**
     * The wrapper URL rendering [pageUrl] into [targetLanguage], or null when
     * either input is unusable.
     *
     * Both inputs are percent-encoded. For the page URL that is what keeps the
     * page's own query string from becoming a parameter of the wrapper's -- a
     * page URL containing `&tl=xx` must not be able to rewrite the target
     * language. [LanguagePresets.isSupported] already restricts a language code
     * to alphanumerics and hyphens, and it is encoded as well so that a future
     * loosening of that validator cannot turn into parameter injection.
     */
    fun urlFor(pageUrl: String, targetLanguage: String): String? {
        if (!isTranslatable(pageUrl)) return null
        val code = targetLanguage.trim()
        if (!LanguagePresets.isSupported(code)) return null
        val source = URLEncoder.encode(pageUrl.trim(), "UTF-8")
        val language = URLEncoder.encode(code, "UTF-8")
        return "$WRAPPER?sl=auto&tl=$language&u=$source"
    }
}
