package com.roombrowser.devtools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `pageFactsText` is what the panel puts on the clipboard, so it is the one
 * piece of the copy affordance that can be checked without a device.
 */
class PageFactsTextTest {

    @Test
    fun a_row_the_engine_left_unanswered_is_copied_as_unknown() {
        val text = pageFactsText(
            PageOverview(url = "https://example.com/", title = "Example"),
            tabUrl = "https://example.com/",
            tabTitle = "Example"
        )
        assertThat(text).contains("ready state: unknown")
        assertThat(text).contains("indexeddb: unknown")
    }

    @Test
    fun a_fully_reported_page_carries_no_unknown_line() {
        val text = pageFactsText(FULL, tabUrl = FULL.url!!, tabTitle = FULL.title!!)
        assertThat(text).doesNotContain("unknown")
    }

    @Test
    fun a_silent_engine_says_so_instead_of_listing_empty_facts() {
        val text = pageFactsText(null, tabUrl = "https://example.com/", tabTitle = "")
        assertThat(text).contains("https://example.com/")
        assertThat(text).contains("No page reading")
        assertThat(text).doesNotContain("ready state")
    }

    @Test
    fun a_blank_page_title_falls_back_to_the_tab_title() {
        val text = pageFactsText(
            PageOverview(title = "   "),
            tabUrl = "https://example.com/",
            tabTitle = "Tab title"
        )
        assertThat(text.lines()[0]).isEqualTo("Tab title")
    }

    @Test
    fun storage_flags_are_summarised_as_presence_not_as_true_false() {
        val text = pageFactsText(
            PageOverview(localStorage = true, sessionStorage = false),
            tabUrl = "https://example.com/",
            tabTitle = "Example"
        )
        assertThat(text).contains("local storage: present")
        assertThat(text).contains("session storage: not reported")
    }

    @Test
    fun a_whole_device_pixel_ratio_copies_without_the_fraction() {
        assertThat(compactNumber(2.0)).isEqualTo("2")
        assertThat(compactNumber(2.75)).isEqualTo("2.75")
    }

    private companion object {
        val FULL = PageOverview(
            url = "https://example.com/",
            origin = "https://example.com",
            title = "Example",
            readyState = "complete",
            lang = "en",
            charset = "UTF-8",
            contentType = "text/html",
            nodes = 12,
            scripts = 3,
            images = 1,
            forms = 0,
            links = 4,
            frames = 1,
            viewportWidth = 320,
            viewportHeight = 640,
            devicePixelRatio = 1.0,
            isSecureContext = true,
            serviceWorker = "none",
            localStorage = true,
            sessionStorage = false,
            indexedDb = false,
            caches = false,
            storageEstimate = false,
            notificationPermission = "default",
            manifest = "none"
        )
    }
}
