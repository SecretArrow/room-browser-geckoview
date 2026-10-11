package com.roombrowser.engine.devtools

/**
 * The `Cookie:` header format, parsed once for both editions.
 *
 * WebView's `CookieManager.getCookie` and a page's own `document.cookie` answer
 * in the same flat shape -- `name=value; name2=value2` -- so the split is shared
 * rather than written twice, and it is pure, so it is pinned by a JVM test in one
 * place instead of by a device in two.
 *
 * Every result carries [EngineCookie.attributesKnown] false: the header says
 * nothing about a cookie beyond its name and value, and the two editions reach
 * it from different stores whose flags do not agree.
 *
 *  - `=` is NOT a separator. It delimits the name from the value once, and a
 *    base64 or JWT value is full of them, so the split is on the FIRST `=` of a
 *    pair. Splitting on every one would turn `token=a.b=c` into a garbage name.
 *  - `;` separates pairs, but a value that carries one is quoted, so a `;`
 *    inside quotes is part of the value and not a boundary: `q="a;b"` is one
 *    cookie.
 *  - A pair with no name, or with no `=`, is dropped rather than reported as a
 *    cookie called `""`: a header fragment is not a cookie.
 */
internal fun parseCookieHeader(header: String?): List<EngineCookie> {
    if (header.isNullOrBlank()) return emptyList()
    return splitCookiePairs(header).mapNotNull { part ->
        val pair = part.trim()
        val eq = pair.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        EngineCookie(
            name = pair.substring(0, eq).trim(),
            value = pair.substring(eq + 1),
            attributesKnown = false
        )
    }
}

/** The header split on unquoted `;`, verbatim: the value keeps its own quotes. */
private fun splitCookiePairs(header: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    header.forEach { ch ->
        when {
            ch == '"' -> {
                quoted = !quoted
                current.append(ch)
            }
            ch == ';' && !quoted -> {
                parts.add(current.toString())
                current.clear()
            }
            else -> current.append(ch)
        }
    }
    parts.add(current.toString())
    return parts
}
