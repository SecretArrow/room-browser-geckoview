package com.roombrowser.domain.proxy

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.paging.Paging
import org.junit.Test

/** The search and page arithmetic behind the network-warning proxy picker. */
class ProxyPickerTest {

    private fun candidate(host: String, port: Int = 8080, country: String? = null) =
        ProxyCandidate(host = host, port = port, scheme = ProxyScheme.HTTP, country = country)

    private val list = listOf(
        candidate("proxy.alpha.net", 8080, "DE"),
        candidate("10.20.30.40", 3128, "US"),
        candidate("beta.example.org", 1080, "NL")
    )

    @Test
    fun `an empty query keeps everything`() {
        assertThat(ProxyPicker.filter(list, "")).hasSize(3)
        assertThat(ProxyPicker.filter(list, "   ")).hasSize(3)
    }

    @Test
    fun `the search matches host port and country case-insensitively`() {
        assertThat(ProxyPicker.filter(list, "ALPHA").map { it.host })
            .containsExactly("proxy.alpha.net")
        assertThat(ProxyPicker.filter(list, "Beta.Example").map { it.host })
            .containsExactly("beta.example.org")
        assertThat(ProxyPicker.filter(list, "3128").map { it.host })
            .containsExactly("10.20.30.40")
        assertThat(ProxyPicker.filter(list, "nl").map { it.host })
            .containsExactly("beta.example.org")
    }

    @Test
    fun `a query that matches nothing yields nothing`() {
        assertThat(ProxyPicker.filter(list, "zzz")).isEmpty()
    }

    @Test
    fun `paging windows the list and reports the counts`() {
        val many = (1..25).map { candidate("h$it.example", 8000 + it, "US") }
        val first = ProxyPicker.page(many, "", 0)
        assertThat(first.items).hasSize(Paging.PAGE_SIZE)
        assertThat(first.total).isEqualTo(25)
        assertThat(first.pageCount).isEqualTo(3)
        assertThat(first.pageIndex).isEqualTo(0)

        val last = ProxyPicker.page(many, "", 2)
        assertThat(last.items).hasSize(5)
        assertThat(last.items.first().host).isEqualTo("h21.example")
    }

    @Test
    fun `a page past the end is clamped instead of blank`() {
        val many = (1..12).map { candidate("h$it.example") }
        val clamped = ProxyPicker.page(many, "", 9)
        assertThat(clamped.pageIndex).isEqualTo(1)
        assertThat(clamped.items).hasSize(2)
    }

    @Test
    fun `a search that shrinks the list pulls the page back in`() {
        val many = (1..25).map {
            candidate("h$it.example", 8000 + it, if (it == 7) "DE" else "US")
        }
        val narrowed = ProxyPicker.page(many, "de", 2)
        assertThat(narrowed.total).isEqualTo(1)
        assertThat(narrowed.pageIndex).isEqualTo(0)
        assertThat(narrowed.items.map { it.host }).containsExactly("h7.example")
    }

    @Test
    fun `an empty result has no page`() {
        val empty = ProxyPicker.page(emptyList(), "", 0)
        assertThat(empty.total).isEqualTo(0)
        assertThat(empty.pageCount).isEqualTo(0)
        assertThat(empty.items).isEmpty()
    }
}
