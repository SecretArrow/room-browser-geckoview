package com.roombrowser.domain.oct

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The materializer. Every case here is one the reference reader also has to handle, and the
 * ones that fail quietly are the interesting ones: a subresource that is never fetched, a
 * policy a circle replaces, a `url()` that gets blanked instead of inlined.
 */
class OctCircleDocumentTest {

    private val circle = "oct" + "A".repeat(44)
    private val uri = OctUri(circle, "/index.html")

    private fun asset(path: String, type: String = "text/plain", body: String = "x") =
        OctInlined(path, type, body.toByteArray(Charsets.UTF_8))

    private fun materialize(html: String, assets: Map<String, OctInlined> = emptyMap()) =
        OctCircleDocument.materialize(html, uri, assets)

    // ------------------------------------------------------------ resolve

    @Test
    fun `a relative reference resolves against the document's own directory`() {
        assertThat(OctUri.resolve("/pages/a.html", "b.css")).isEqualTo("/pages/b.css")
        assertThat(OctUri.resolve("/index.html", "img/logo.png")).isEqualTo("/img/logo.png")
        assertThat(OctUri.resolve("/pages/a.html", "/top.png")).isEqualTo("/top.png")
        assertThat(OctUri.resolve("/index.html", "./a.png")).isEqualTo("/a.png")
    }

    @Test
    fun `a parent reference is collapsed rather than refused`() {
        assertThat(OctUri.resolve("/pages/a.html", "../img/x.png")).isEqualTo("/img/x.png")
        assertThat(OctUri.resolve("/a/b/c.html", "../../d.png")).isEqualTo("/d.png")
        assertThat(OctUri.resolve("/a/b/c.html", "../../../d.png")).isEqualTo("/d.png")
    }

    @Test
    fun `an escaped traversal is still refused, because it decodes only later`() {
        assertThat(OctUri.resolve("/a/b.html", "%2e%2e/%2e%2e/x")).isNull()
    }

    @Test
    fun `a fragment, an empty reference and a foreign scheme are not circle paths`() {
        assertThat(OctUri.resolve("/a.html", "#top")).isNull()
        assertThat(OctUri.resolve("/a.html", "   ")).isNull()
        assertThat(OctUri.resolve("/a.html", "?x=1")).isNull()
        assertThat(OctUri.resolve("/a.html", "mailto:x@y.z")).isNull()
        assertThat(OctUri.resolve("/a.html", "https://example.com/x")).isNull()
        assertThat(OctUri.resolve("/a.html", "//example.com/x")).isNull()
    }

    @Test
    fun `a query or fragment on a real reference is stripped, not resolved into the path`() {
        assertThat(OctUri.resolve("/index.html", "a.css?v=2")).isEqualTo("/a.css")
        assertThat(OctUri.resolve("/index.html", "a.css#f")).isEqualTo("/a.css")
    }

    // ------------------------------------------------------------ collecting

    @Test
    fun `every fetched attribute is collected, and a link target is not`() {
        val html = """
            <link rel="stylesheet" href="a.css">
            <link rel="icon" href="fav.png">
            <img src="img/logo.png" srcset="img/small.png 1x, img/big.png 2x">
            <script src="app.js"></script>
            <a href="other.html">x</a>
            <div style="background:url(bg.png)"></div>
            <video poster="p.png"></video>
        """.trimIndent()
        val references = OctCircleDocument.references(html, "/index.html")
        assertThat(references.map { it.path }).containsExactly(
            "/a.css", "/fav.png", "/img/logo.png", "/img/small.png", "/img/big.png",
            "/app.js", "/bg.png", "/p.png"
        )
        assertThat(references.filter { it.styleSheet }.map { it.path }).containsExactly("/a.css")
    }

    @Test
    fun `a stylesheet and its imports are the only references that recurse`() {
        val html = """<link rel="stylesheet" href="a.css"><style>@import "b.css";</style>"""
        val references = OctCircleDocument.references(html, "/index.html")
        assertThat(references.filter { it.styleSheet }.map { it.path })
            .containsExactly("/a.css", "/b.css")
    }

    @Test
    fun `markup inside a script is not mistaken for markup`() {
        val html = """<script>var s = '<img src="trap.png">';</script>"""
        assertThat(OctCircleDocument.references(html, "/index.html")).isEmpty()
    }

    @Test
    fun `a repeated reference is fetched once`() {
        val html = """<img src="a.png"><img src="a.png"><img src="./a.png">"""
        assertThat(OctCircleDocument.references(html, "/index.html").map { it.path })
            .containsExactly("/a.png")
    }

    // ------------------------------------------------------------ materializing

    @Test
    fun `a subresource is carried inside the document as a data url`() {
        val out = materialize("""<img src="logo.png">""", mapOf("/logo.png" to asset("/logo.png", "image/png", "PNG")))
        assertThat(out).contains("src=\"data:image/png;base64,")
        assertThat(out).doesNotContain("logo.png")
    }

    @Test
    fun `an unquoted attribute is quoted, because a data url contains an equals sign`() {
        val out = materialize("""<img src=logo.png>""", mapOf("/logo.png" to asset("/logo.png", "image/png", "P")))
        assertThat(out).contains("src=\"data:image/png;base64,")
    }

    @Test
    fun `a reference the fetch did not return is dropped rather than left to fail`() {
        val out = materialize("""<img src="gone.png" alt="a">""")
        assertThat(out).doesNotContain("src=")
        assertThat(out).contains("alt=\"a\"")
    }

    @Test
    fun `a srcset keeps its descriptors`() {
        val out = materialize(
            """<img srcset="a.png 1x, b.png 2x">""",
            mapOf("/a.png" to asset("/a.png"), "/b.png" to asset("/b.png"))
        )
        assertThat(out).contains(" 1x, ")
        assertThat(out).contains(" 2x\"")
    }

    @Test
    fun `a circle link becomes an oct address and anything else is neutralised`() {
        val out = materialize(
            """<a href="page2.html">1</a><a href="https://example.com">2</a><a href="javascript:alert(1)">3</a>"""
        )
        assertThat(out).contains("href=\"${OctUri.PREFIX}$circle/page2.html\"")
        assertThat(out).contains("href=\"https://example.com\"")
        assertThat(out).doesNotContain("javascript:")
        assertThat(out).contains("href=\"#\"")
    }

    @Test
    fun `a circle cannot rebase its references`() {
        val out = materialize("""<base href="https://evil.example/"><img src="a.png">""")
        assertThat(out).doesNotContain("<base")
    }

    @Test
    fun `a circle cannot replace the policy this app set`() {
        val out = materialize(
            """<meta http-equiv="Content-Security-Policy" content="default-src *">""" +
                """<meta name="referrer" content="unsafe-url">"""
        )
        assertThat(out).doesNotContain("default-src *")
        assertThat(out).doesNotContain("unsafe-url")
    }

    @Test
    fun `the policy is inserted after the doctype, so nothing in the document precedes it`() {
        val out = materialize("<!doctype html><html><body>hi</body></html>")
        assertThat(out).startsWith("<!doctype html><meta charset=\"utf-8\">")
        assertThat(out).contains("connect-src 'none'")
        assertThat(out).contains("<body>hi</body>")
    }

    @Test
    fun `a document with no doctype still gets the policy first`() {
        val out = materialize("<html><body>hi</body></html>")
        assertThat(out).startsWith("<meta charset=\"utf-8\">")
    }

    // ------------------------------------------------------------ css

    @Test
    fun `an import is inlined, and what it inlined is not scanned a second time`() {
        val out = OctCircleDocument.styleSheet(
            """@import "a.css"; body { color: red }""",
            "/index.html",
            mapOf(
                "/a.css" to asset("/a.css", "text/css", """p { background: url("p.png") }"""),
                "/p.png" to asset("/p.png", "image/png", "P")
            )
        )
        assertThat(out).doesNotContain("@import")
        assertThat(out).contains("data:image/png;base64,")
        assertThat(out).doesNotContain("url(\"data:,\")")
    }

    @Test
    fun `a url that cannot be inlined is emptied, not left as a request`() {
        val out = OctCircleDocument.styleSheet("""p { background: url("gone.png") }""", "/index.html", emptyMap())
        assertThat(out).contains("url(\"data:,\")")
        assertThat(out).doesNotContain("gone.png")
    }

    @Test
    fun `an import chain is cut off rather than followed forever`() {
        val assets = (0..8).associate { depth ->
            val next = depth + 1
            "/$depth.css" to asset("/$depth.css", "text/css", """@import "$next.css";""")
        }
        val out = OctCircleDocument.styleSheet("""@import "0.css";""", "/index.html", assets)
        assertThat(out).doesNotContain("@import")
    }

    @Test
    fun `a style attribute's urls are inlined in place`() {
        val out = materialize(
            """<div style="background:url(bg.png)">x</div>""",
            mapOf("/bg.png" to asset("/bg.png", "image/png", "B"))
        )
        assertThat(out).contains("data:image/png;base64,")
        assertThat(out).contains("style=\"background:url(")
    }

    @Test
    fun `an svg inside a stylesheet is decoded as text and an image is not`() {
        assertThat(asset("/a.svg", "image/svg+xml", "<svg/>").text).isEqualTo("<svg/>")
        assertThat(asset("/a.png", "image/png", "PNG").text).isNull()
        assertThat(asset("/a.css", "text/css; charset=utf-8", "p{}").text).isEqualTo("p{}")
    }
}
