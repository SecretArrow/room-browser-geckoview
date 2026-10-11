package com.roombrowser.engine.devtools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The `Cookie:` header split, pinned in one place for both editions.
 *
 * This is the whole of the parsing either edition does: WebView answers with
 * `CookieManager.getCookie` and the GeckoView edition's fallback reads the page's
 * own `document.cookie`, and both hand back the same flat string. The two ways to
 * get the split wrong are worth naming, because each one silently produces a
 * cookie that does not exist:
 *
 *  - a value full of `=`, where a split on every one invents a name;
 *  - a quoted value containing `;`, where a split on every one invents a second
 *    cookie.
 *
 * The last test is the negative control for the panel's "unknown on this engine"
 * cells: nothing here may fill an attribute in.
 */
class EngineCookieHeaderTest {

    @Test
    fun a_value_may_carry_an_equals_sign_and_is_split_on_the_first_one_only() {
        // A JWT or a base64 blob is full of `=`, so a split on every one would
        // report a cookie whose name is the front of someone's token.
        val parsed = parseCookieHeader("token=a.b=c; sid=1")
        assertThat(parsed.map { it.name }).containsExactly("token", "sid").inOrder()
        assertThat(parsed[0].value).isEqualTo("a.b=c")
    }

    @Test
    fun a_quoted_value_may_carry_a_semicolon_and_is_kept_whole() {
        // `;` separates pairs, so a value that contains one has to be quoted --
        // and inside the quotes it is not a boundary.
        val parsed = parseCookieHeader("q=\"a;b\"; sid=1")
        assertThat(parsed.map { it.name }).containsExactly("q", "sid").inOrder()
        assertThat(parsed[0].value).isEqualTo("\"a;b\"")
    }

    @Test
    fun every_cookie_off_the_header_says_its_attributes_are_unknown() {
        // The header carries no flags at all, so a parser that filled any in
        // would be inventing them. This is the negative control for the section's
        // "unknown on this engine" cells.
        val parsed = parseCookieHeader("sid=abc123; theme=dark")
        assertThat(parsed).hasSize(2)
        parsed.forEach { cookie ->
            assertThat(cookie.attributesKnown).isFalse()
            assertThat(cookie.secure).isNull()
            assertThat(cookie.httpOnly).isNull()
            assertThat(cookie.session).isNull()
            assertThat(cookie.expiresAtMs).isNull()
            assertThat(cookie.domain).isNull()
            assertThat(cookie.path).isNull()
            assertThat(cookie.sameSite).isNull()
        }
    }

    @Test
    fun a_header_with_nothing_in_it_yields_no_cookies() {
        // The header is empty both when a page set nothing and when every cookie
        // it has is invisible to it, so this answers with a list -- an empty one
        // -- rather than with a failure.
        assertThat(parseCookieHeader(null)).isEmpty()
        assertThat(parseCookieHeader("")).isEmpty()
        assertThat(parseCookieHeader("   ")).isEmpty()
        // Separators and a fragment with no name are not cookies.
        assertThat(parseCookieHeader("; ;")).isEmpty()
        assertThat(parseCookieHeader("=noname")).isEmpty()
    }

    @Test
    fun the_name_and_value_are_the_only_things_the_header_can_fill_in() {
        // A positive control for the mask test at the app level: this is the one
        // shape both editions produce, so the values really are carried and it is
        // only the flags that are absent.
        val parsed = parseCookieHeader("sid=abc123")
        assertThat(parsed).hasSize(1)
        assertThat(parsed[0].name).isEqualTo("sid")
        assertThat(parsed[0].value).isEqualTo("abc123")
    }
}
