package com.roombrowser.domain.oct

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Where a navigation address is routed. The engine cannot render `oct://`, so only this
 * decision keeps the scheme out of it — the negative controls are as load-bearing as the
 * positive one.
 */
class OctNavigationTest {

    private val circle = "oct" + "A".repeat(44)

    @Test
    fun `a circle link is routed to the app's loader`() {
        val routed = OctNavigation.classify("oct://$circle/a/b.html")
        assertThat(routed).isInstanceOf(OctNavigation.Circle::class.java)
        val uri = (routed as OctNavigation.Circle).uri
        assertThat(uri.circleId).isEqualTo(circle)
        assertThat(uri.path).isEqualTo("/a/b.html")
    }

    @Test
    fun `a circle with no path still routes, defaulting to index`() {
        assertThat((OctNavigation.classify("oct://$circle") as OctNavigation.Circle).uri.path)
            .isEqualTo("/index.html")
    }

    @Test
    fun `a malformed oct address is refused, never a circle`() {
        assertThat(OctNavigation.classify("oct://not-a-circle/x")).isEqualTo(OctNavigation.Malformed)
        assertThat(OctNavigation.classify("oct://")).isEqualTo(OctNavigation.Malformed)
        assertThat(OctNavigation.classify("oct://oct" + "0".repeat(44))).isEqualTo(OctNavigation.Malformed)
        assertThat(OctNavigation.classify("oct://$circle/../escape")).isEqualTo(OctNavigation.Malformed)
    }

    @Test
    fun `web and internal addresses stay with the engine`() {
        assertThat(OctNavigation.classify("https://example.com/")).isEqualTo(OctNavigation.PassThrough)
        assertThat(OctNavigation.classify("http://example.com/a?b=c")).isEqualTo(OctNavigation.PassThrough)
        assertThat(OctNavigation.classify("about:home")).isEqualTo(OctNavigation.PassThrough)
        assertThat(OctNavigation.classify(null)).isEqualTo(OctNavigation.PassThrough)
        assertThat(OctNavigation.classify("")).isEqualTo(OctNavigation.PassThrough)
        // A host that merely starts with the same letters is not the circle scheme.
        assertThat(OctNavigation.classify("https://octopus.example/")).isEqualTo(OctNavigation.PassThrough)
    }
}
