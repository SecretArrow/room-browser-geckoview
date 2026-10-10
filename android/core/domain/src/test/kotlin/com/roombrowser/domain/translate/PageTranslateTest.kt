package com.roombrowser.domain.translate

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the translate wrapper URL, which until now was assembled inline inside
 * the dialog's confirm lambda and therefore had no coverage at all: nothing
 * asserted its shape, and nothing held the page's own query string inside the
 * `u` value where it belongs.
 */
class PageTranslateTest {

    @Test
    fun the_wrapper_url_carries_the_page_and_the_target_language() {
        assertThat(PageTranslate.urlFor("https://example.com/a", "id")).isEqualTo(
            "https://translate.google.com/translate?sl=auto&tl=id" +
                "&u=https%3A%2F%2Fexample.com%2Fa"
        )
    }

    @Test
    fun the_page_url_is_encoded_so_its_own_query_cannot_rewrite_the_target_language() {
        // The page's parameters must stay INSIDE the `u` value: left raw, a page
        // URL carrying `&tl=zz` would arrive at Google as a second `tl` and
        // decide the target language itself.
        val url = requireNotNull(
            PageTranslate.urlFor("https://shop.example/p?size=xl&tl=zz&q=a b", "ja")
        )
        assertThat(url).contains("tl=ja")
        assertThat(url).doesNotContain("tl=zz")
        assertThat(url.count { it == '&' }).isEqualTo(2)
        assertThat(url).endsWith(
            "&u=https%3A%2F%2Fshop.example%2Fp%3Fsize%3Dxl%26tl%3Dzz%26q%3Da+b"
        )
    }

    @Test
    fun a_page_with_no_fetchable_address_has_no_translate_url() {
        listOf(
            "about:home",
            "about:blank",
            "oct://oct7DrVLpM3ZAnB7uJvXoVJt1uUw2iA6vqs9pKwTzUqM4Lx8Yb2",
            "data:text/html,<h1>hi</h1>",
            "file:///sdcard/page.html",
            "chrome://version",
            "",
            "   "
        ).forEach { page ->
            assertThat(PageTranslate.isTranslatable(page)).isFalse()
            assertThat(PageTranslate.urlFor(page, "id")).isNull()
        }
    }

    @Test
    fun an_unusable_language_code_is_refused() {
        listOf("", "   ", "id id", "id/../x", "id&tl=en", "<script>", "1d").forEach { code ->
            assertThat(PageTranslate.urlFor("https://example.com/", code)).isNull()
        }
    }

    @Test
    fun the_language_code_is_trimmed_and_a_curated_preset_survives_the_round_trip() {
        assertThat(PageTranslate.urlFor("https://example.com/", "  ja  "))
            .isEqualTo(
                "https://translate.google.com/translate?sl=auto&tl=ja" +
                    "&u=https%3A%2F%2Fexample.com%2F"
            )
        // A region subtag is a legal code and must reach Google unchanged.
        assertThat(PageTranslate.urlFor("https://example.com/", "pt-BR")).contains("tl=pt-BR")
    }

    @Test
    fun the_dialog_button_predicate_and_the_url_builder_agree() {
        // The dialog enables its button on isTranslatable and builds the URL
        // with urlFor. If the two ever disagree the button is pressable and
        // does nothing, which is the silent no-op this pair exists to prevent.
        listOf(
            "https://example.com/",
            "http://example.com/",
            "HTTPS://Example.COM/Path",
            "about:home",
            "oct://oct7DrVLpM3ZAnB7uJvXoVJt1uUw2iA6vqs9pKwTzUqM4Lx8Yb2",
            ""
        ).forEach { page ->
            assertThat(PageTranslate.isTranslatable(page))
                .isEqualTo(PageTranslate.urlFor(page, "id") != null)
        }
    }
}
