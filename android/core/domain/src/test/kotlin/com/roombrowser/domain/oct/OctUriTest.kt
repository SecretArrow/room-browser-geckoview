package com.roombrowser.domain.oct

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The `oct://` grammar. Strictness is the point: a path this app normalizes differently
 * from the node is a path it would fetch the wrong object for.
 */
class OctUriTest {

    private val circle = "oct" + "A".repeat(44)

    @Test
    fun `a bare circle id defaults to index html`() {
        val parsed = OctUri.parse("oct://$circle")
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.circleId).isEqualTo(circle)
        assertThat(parsed.path).isEqualTo("/index.html")
    }

    @Test
    fun `the scheme is matched case-insensitively, the id is not`() {
        assertThat(OctUri.parse("OCT://$circle")).isNotNull()
        assertThat(OctUri.parse("oct://" + "oct" + "a".repeat(44))).isNotNull()
        assertThat(OctUri.parse("oct://" + "oct" + "A".repeat(44).lowercase() + "0")).isNull()
    }

    @Test
    fun `a truncated id or one outside the base58 alphabet is refused`() {
        assertThat(OctUri.parse("oct://oct" + "A".repeat(43))).isNull()
        assertThat(OctUri.parse("oct://oct" + "A".repeat(45))).isNull()
        assertThat(OctUri.parse("oct://oct" + "0".repeat(44))).isNull()
        assertThat(OctUri.parse("oct://oct" + "l".repeat(44))).isNull()
    }

    @Test
    fun `anything that is not oct is refused`() {
        assertThat(OctUri.parse(null)).isNull()
        assertThat(OctUri.parse("")).isNull()
        assertThat(OctUri.parse("https://oct$circle/index.html")).isNull()
        assertThat(OctUri.parse("oct:/$circle")).isNull()
    }

    @Test
    fun `query and fragment are dropped before the path is read`() {
        val a = OctUri.parse("oct://$circle/page.html?x=1")
        val b = OctUri.parse("oct://$circle/page.html#frag")
        assertThat(a!!.path).isEqualTo("/page.html")
        assertThat(b!!.path).isEqualTo("/page.html")
        assertThat(OctUri.parse("oct://$circle?x=1")!!.path).isEqualTo("/index.html")
    }

    @Test
    fun `empty and dot segments are dropped`() {
        assertThat(OctUri.parse("oct://$circle/a//b/./c")!!.path).isEqualTo("/a/b/c")
        assertThat(OctUri.parse("oct://$circle/")!!.path).isEqualTo("/")
    }

    @Test
    fun `a traversing path is refused rather than collapsed`() {
        assertThat(OctUri.parse("oct://$circle/a/../b")).isNull()
        assertThat(OctUri.parse("oct://$circle/..")).isNull()
        assertThat(OctUri.parse("oct://$circle/%2e%2e/b")).isNull()
    }

    @Test
    fun `a percent-escaped separator is a separator by the time the path is split`() {
        assertThat(OctUri.parse("oct://$circle/a%2Fb")!!.path).isEqualTo("/a/b")
    }

    @Test
    fun `an over-long path is refused`() {
        val long = "/" + "a".repeat(OctUri.MAX_PATH_LENGTH)
        assertThat(OctUri.parse("oct://$circle$long")).isNull()
        assertThat(OctUri.canonicalPath(long)).isNull()
    }

    @Test
    fun `a malformed escape is a failure, not a replacement character`() {
        assertThat(OctUri.parse("oct://$circle/a%zz")).isNull()
        assertThat(OctUri.parse("oct://$circle/a%")).isNull()
        assertThat(OctUri.canonicalPath("/%")).isNull()
    }

    @Test
    fun `raw is the canonical spelling to store in a tab`() {
        assertThat(OctUri.parse("oct://$circle/a//b/")!!.raw).isEqualTo("oct://$circle/a/b")
    }
}
