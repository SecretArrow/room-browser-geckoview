package com.roombrowser.devtools

import com.roombrowser.engine.devtools.EngineCookie
import java.time.Instant

/**
 * The text the Application panel's Cookies section writes.
 *
 * Pure functions, for the same reason the storage, security and audit texts
 * are: what a copy puts on the clipboard is what a bug report carries, so the
 * rules here have to be testable without an emulator.
 *
 * TWO RULES, and both are about not lying.
 *
 *  - A VALUE IS A SECRET. Every value renders masked -- a fixed run of dots and
 *    its length -- and the mask is what a copy carries. [revealedCookieText] is
 *    built only for one row a reader opened, and nothing that leaves the panel
 *    ever uses it.
 *  - AN ATTRIBUTE THE ENGINE DID NOT REPORT IS NOT `false`. WebView's
 *    `CookieManager` hands over a whole `name=value; name2=value2` header and no
 *    flags at all, so on that edition every attribute cell reads
 *    [UNKNOWN_ATTRIBUTE]; printing `secure=false` there would be a claim the
 *    engine never made.
 */
internal object DeveloperToolsCookiesText {

    /**
     * What an attribute cell shows when the engine could not report it.
     *
     * The wording is the point: "unknown on this engine" names WHY the cell is
     * empty, so it cannot be read as the engine having said "no".
     */
    const val UNKNOWN_ATTRIBUTE = "unknown on this engine"

    /**
     * The whole Cookies section, masked: a summary line and one row per cookie.
     *
     * `null` and `empty` are different answers and print differently. A blank
     * list would let "this build cannot read cookies" read as "this page set
     * none", which is the one thing this section exists to keep apart.
     */
    fun cookiesSection(cookies: List<EngineCookie>?): String {
        if (cookies == null) return "Cookies -- this build could not read them"
        if (cookies.isEmpty()) return "Cookies -- none for this page"
        val head = "Cookies -- ${cookies.size} ${if (cookies.size == 1) "cookie" else "cookies"}"
        return (listOf(head) + cookies.map { cookieText(it) }).joinToString("\n")
    }

    /** One cookie with its value MASKED. This is the form every copy carries. */
    fun cookieText(cookie: EngineCookie): String = cookieLines(cookie, revealedValue = null)

    /** One cookie with its value in the clear. Built only for a row a reader opened, and never copied. */
    fun revealedCookieText(cookie: EngineCookie): String = cookieLines(cookie, revealedValue = cookie.value)

    /**
     * A value hidden behind a fixed run of dots and its length.
     *
     * The length is kept because it is not the secret and it is useful: a reader
     * can tell a two-character flag from a session token without reading either.
     */
    fun maskedValue(value: String): String = "$MASK (${value.length} chars)"

    private fun cookieLines(cookie: EngineCookie, revealedValue: String?): String {
        val known = cookie.attributesKnown
        return listOf(
            "  ${cookie.name}",
            "    value: ${revealedValue ?: maskedValue(cookie.value)}",
            "    domain: ${stringAttribute(cookie.domain, known)}",
            "    path: ${stringAttribute(cookie.path, known)}",
            "    secure: ${flagAttribute(cookie.secure, known)}",
            "    httpOnly: ${flagAttribute(cookie.httpOnly, known)}",
            "    expiry: ${expiryAttribute(cookie, known)}",
            "    sameSite: ${stringAttribute(cookie.sameSite, known)}"
        ).joinToString("\n")
    }

    private fun stringAttribute(value: String?, known: Boolean): String = when {
        !known -> UNKNOWN_ATTRIBUTE
        value.isNullOrBlank() -> "(not reported)"
        else -> value
    }

    private fun flagAttribute(value: Boolean?, known: Boolean): String = when {
        !known -> UNKNOWN_ATTRIBUTE
        value == true -> "yes"
        value == false -> "no"
        else -> "(not reported)"
    }

    private fun expiryAttribute(cookie: EngineCookie, known: Boolean): String {
        // Read through a local: expiresAtMs comes from :engine, and Kotlin will
        // not smart-cast a public property declared in another module.
        val expiresAtMs = cookie.expiresAtMs
        return when {
            !known -> UNKNOWN_ATTRIBUTE
            cookie.session == true -> "session (no expiry)"
            expiresAtMs != null -> datetime(expiresAtMs)
            else -> "(not reported)"
        }
    }

    private fun datetime(epochMs: Long): String =
        runCatching { Instant.ofEpochMilli(epochMs).toString() }.getOrDefault("(not reported)")

    /** The mask is a fixed run, so its own length never leaks the value's. */
    private const val MASK = "••••••••"
}
