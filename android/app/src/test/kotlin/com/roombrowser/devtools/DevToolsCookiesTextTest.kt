package com.roombrowser.devtools

import com.google.common.truth.Truth.assertThat
import com.roombrowser.engine.devtools.EngineCookie
import org.junit.Test

/**
 * The Cookies section's copy texts, asserted without an emulator.
 *
 * WHAT THESE PIN. Every test here is a place the section could state something
 * FALSE and still look right, and the two that matter most come with a negative
 * control beside them:
 *
 *  - a value must be MASKED by default and must never reach a copy, so the test
 *    that a revealed row shows it is paired with one that the masked row does
 *    not;
 *  - an attribute the engine did not report must read as unknown, so the test
 *    for an attribute-less cookie is paired with one where a cookie the engine
 *    DID describe prints yes/no -- otherwise "unknown" would pass simply because
 *    nothing ever printed a flag at all.
 */
class DevToolsCookiesTextTest {

    /** A cookie from an engine that reports only name and value -- the WebView shape. */
    private val anonymous = EngineCookie(
        name = "session",
        value = "abc123",
        attributesKnown = false
    )

    /** A cookie from an engine that reports every attribute -- the fixture for the positive control. */
    private val described = EngineCookie(
        name = "sid",
        value = "deadbeef",
        domain = "example.com",
        path = "/",
        secure = true,
        httpOnly = true,
        session = false,
        expiresAtMs = 1_798_761_600_000L,
        sameSite = "Lax",
        attributesKnown = true
    )

    // ---------- Masking ------------------------------------------------------

    @Test
    fun a_value_is_masked_by_default_and_the_section_never_carries_it() {
        val row = DeveloperToolsCookiesText.cookieText(described)
        assertThat(row).contains(DeveloperToolsCookiesText.maskedValue("deadbeef"))
        assertThat(row).doesNotContain("deadbeef")

        val section = DeveloperToolsCookiesText.cookiesSection(listOf(described))
        assertThat(section).contains(DeveloperToolsCookiesText.maskedValue("deadbeef"))
        assertThat(section).doesNotContain("deadbeef")
    }

    @Test
    fun a_revealed_row_shows_the_value_and_the_masked_row_still_hides_it() {
        // The two halves of the reveal: the revealed form carries the value, the
        // copyable form does not. Dropping either half makes the other a lie.
        assertThat(DeveloperToolsCookiesText.revealedCookieText(described)).contains("deadbeef")
        assertThat(DeveloperToolsCookiesText.cookieText(described)).doesNotContain("deadbeef")
    }

    @Test
    fun the_mask_keeps_the_length_without_the_value() {
        // A reader can still tell a short flag from a long token, which is the
        // one useful thing the length gives away.
        assertThat(DeveloperToolsCookiesText.maskedValue("abc123")).contains("(6 chars)")
        assertThat(DeveloperToolsCookiesText.maskedValue("abc123")).doesNotContain("abc123")
    }

    // ---------- Unknown attributes -------------------------------------------

    @Test
    fun an_engine_that_cannot_report_attributes_says_unknown_rather_than_false() {
        val row = DeveloperToolsCookiesText.cookieText(anonymous)
        assertThat(row).contains("secure: ${DeveloperToolsCookiesText.UNKNOWN_ATTRIBUTE}")
        assertThat(row).contains("httpOnly: ${DeveloperToolsCookiesText.UNKNOWN_ATTRIBUTE}")
        assertThat(row).contains("sameSite: ${DeveloperToolsCookiesText.UNKNOWN_ATTRIBUTE}")
        // Never a flag the engine never read: not "false", not "no", not blank.
        assertThat(row).doesNotContain("secure: no")
        assertThat(row).doesNotContain("secure: yes")
        assertThat(row).doesNotContain("false")
    }

    @Test
    fun a_cookie_the_engine_did_describe_still_prints_its_flags() {
        // The positive control for the test above: without it, "unknown" would
        // pass just as well from a section that printed no flags at all.
        val row = DeveloperToolsCookiesText.cookieText(described)
        assertThat(row).contains("secure: yes")
        assertThat(row).contains("httpOnly: yes")
        assertThat(row).contains("domain: example.com")
        assertThat(row).contains("sameSite: Lax")
        assertThat(row).doesNotContain(DeveloperToolsCookiesText.UNKNOWN_ATTRIBUTE)
    }

    // ---------- Empty and unreadable -----------------------------------------

    @Test
    fun an_empty_set_and_an_unreadable_set_say_different_things() {
        assertThat(DeveloperToolsCookiesText.cookiesSection(emptyList()))
            .isEqualTo("Cookies -- none for this page")
        assertThat(DeveloperToolsCookiesText.cookiesSection(null))
            .isEqualTo("Cookies -- this build could not read them")
    }

    @Test
    fun the_summary_counts_the_cookies_and_agrees_with_itself_on_the_plural() {
        assertThat(DeveloperToolsCookiesText.cookiesSection(listOf(anonymous)).lines()[0])
            .isEqualTo("Cookies -- 1 cookie")
        assertThat(DeveloperToolsCookiesText.cookiesSection(listOf(anonymous, described)).lines()[0])
            .isEqualTo("Cookies -- 2 cookies")
    }

    // ---------- Awkward values -----------------------------------------------

    @Test
    fun a_value_with_an_equals_sign_or_semicolon_is_not_mangled() {
        // A `name=value; name2=value2` header is split on `=` at most once and on
        // `;` only between pairs, so a value that itself contains either must come
        // through whole. The formatter must not re-split what it was handed.
        val awkward = EngineCookie(name = "q", value = "a=b;c", attributesKnown = false)
        assertThat(DeveloperToolsCookiesText.revealedCookieText(awkward)).contains("a=b;c")
        assertThat(DeveloperToolsCookiesText.maskedValue("a=b;c")).contains("(5 chars)")
        assertThat(DeveloperToolsCookiesText.cookieText(awkward)).doesNotContain("a=b;c")
    }
}
