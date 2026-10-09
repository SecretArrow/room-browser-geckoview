package com.roombrowser.domain.wallet.chains.octra

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.oct.OctAsset
import com.roombrowser.domain.oct.OctAssetResult
import com.roombrowser.domain.oct.OctCircleDocument
import com.roombrowser.domain.oct.OctCircleFixtures
import com.roombrowser.domain.oct.OctCircleInfo
import com.roombrowser.domain.oct.OctSealedAsset
import com.roombrowser.domain.oct.OctUri
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The resolver, driven entirely through [OctCircleSource] so no node is involved.
 *
 * The sealed cases build their envelopes with [OctCircleFixtures] rather than with
 * hand-written ciphertext: the protocol's framing is the thing under test, so a fixture
 * that re-derived it would agree with the reader even when both were wrong.
 */
class OctCircleResolverTest {

    private val circle = "oct" + "A".repeat(44)
    private val uri = OctUri(circle, "/index.html")

    private val publicInfo = OctCircleInfo(circle, "html", OctCircleInfo.PUBLIC_RESOURCES)
    private val sealedInfo = OctCircleInfo(circle, "html", OctCircleInfo.SEALED_READ)

    @Test
    fun `a public circle inlines the image its document references`() = runTest {
        val source = FakeSource(
            publicInfo,
            mapOf(
                "/index.html" to public("/index.html", "text/html", "<img src=\"/logo.png\">"),
                "/logo.png" to public("/logo.png", "image/png", "PNGDATA")
            )
        )

        val document = resolver(source, null).resolve(uri)

        assertThat(document).isInstanceOf(OctDocument.Rendered::class.java)
        val html = (document as OctDocument.Rendered).html
        assertThat(html).contains("src=\"data:image/png;base64,")
        assertThat(html).doesNotContain("logo.png")
    }

    @Test
    fun `an unknown circle is unavailable`() = runTest {
        val source = FakeSource(null, emptyMap())

        assertThat(resolver(source, null).resolve(uri)).isEqualTo(OctDocument.Unavailable)
    }

    @Test
    fun `a sealed circle with no passphrase asks for one and returns the key id`() = runTest {
        // The envelope is never opened here: a null passphrase short-circuits before
        // OctSealed.open, so it does not have to be real ciphertext.
        val source = FakeSource(
            sealedInfo,
            mapOf(
                "/index.html" to OctAssetResult.Sealed(
                    OctSealedAsset(circle, "/index.html", "text/html", byteArrayOf(1, 2, 3), "deadbeef", KEY_ID)
                )
            )
        )

        assertThat(resolver(source, null).resolve(uri))
            .isEqualTo(OctDocument.NeedsPassphrase(circle, KEY_ID))
        assertThat(source.fetched).contains("/index.html")
    }

    @Test
    fun `a subresource the node refuses is skipped and the document still renders`() = runTest {
        val source = FakeSource(
            publicInfo,
            mapOf("/index.html" to public("/index.html", "text/html", "<img src=\"/gone.png\"><p>hi</p>"))
        )

        val document = resolver(source, null).resolve(uri)

        assertThat(document).isInstanceOf(OctDocument.Rendered::class.java)
        val html = (document as OctDocument.Rendered).html
        assertThat(html).doesNotContain("gone.png")
        assertThat(html).contains("<p>hi</p>")
    }

    @Test
    fun `a document wider than the cap fetches no more than the cap`() = runTest {
        val paths = (0 until OctCircleDocument.MAX_SUBRESOURCES + 8).map { "/a$it.png" }
        val answers = HashMap<String, OctAssetResult>()
        answers["/index.html"] =
            public("/index.html", "text/html", paths.joinToString("") { "<img src=\"$it\">" })
        paths.forEach { answers[it] = public(it, "image/png", "x") }
        val source = FakeSource(publicInfo, answers)

        assertThat(resolver(source, null).resolve(uri)).isInstanceOf(OctDocument.Rendered::class.java)

        assertThat(source.fetched.count { it != "/index.html" })
            .isEqualTo(OctCircleDocument.MAX_SUBRESOURCES)
    }

    @Test
    fun `a stylesheet's own import is followed`() = runTest {
        val source = FakeSource(
            publicInfo,
            mapOf(
                "/index.html" to public("/index.html", "text/html", "<link rel=\"stylesheet\" href=\"/style.css\">"),
                "/style.css" to public("/style.css", "text/css", "@import \"/imported.css\";"),
                "/imported.css" to public("/imported.css", "text/css", "body{color:red}")
            )
        )

        assertThat(resolver(source, null).resolve(uri)).isInstanceOf(OctDocument.Rendered::class.java)
        assertThat(source.fetched).contains("/imported.css")
    }

    @Test
    fun `an http reference is never fetched from the node`() = runTest {
        val source = FakeSource(
            publicInfo,
            mapOf("/index.html" to public("/index.html", "text/html", "<img src=\"http://example.com/x.png\">"))
        )

        resolver(source, null).resolve(uri)

        assertThat(source.fetched).containsExactly("/index.html")
    }

    @Test
    fun `a sealed circle opens with the passphrase, subresources included`() = runTest {
        val secret = "correct horse".toCharArray()
        val source = FakeSource(
            sealedInfo,
            mapOf(
                "/index.html" to sealed("/index.html", "text/html", "<img src=\"/logo.png\">", secret),
                "/logo.png" to sealed("/logo.png", "image/png", "PNGDATA", secret)
            )
        )

        val document = resolver(source, secret).resolve(uri)

        assertThat(document).isInstanceOf(OctDocument.Rendered::class.java)
        assertThat((document as OctDocument.Rendered).html)
            .contains("src=\"data:image/png;base64,")
    }

    @Test
    fun `a wrong passphrase re-asks instead of reporting a broken circle`() = runTest {
        val source = FakeSource(
            sealedInfo,
            mapOf("/index.html" to sealed("/index.html", "text/html", "<p>hi</p>", "right".toCharArray()))
        )

        assertThat(resolver(source, "wrong".toCharArray()).resolve(uri))
            .isEqualTo(OctDocument.NeedsPassphrase(circle, KEY_ID))
    }

    @Test
    fun `the passphrase is asked for once however many assets are sealed`() = runTest {
        val secret = "one answer".toCharArray()
        val source = FakeSource(
            sealedInfo,
            mapOf(
                "/index.html" to
                    sealed("/index.html", "text/html", "<img src=\"/a.png\"><img src=\"/b.png\">", secret),
                "/a.png" to sealed("/a.png", "image/png", "A", secret),
                "/b.png" to sealed("/b.png", "image/png", "B", secret)
            )
        )
        var asked = 0
        val counting = OctCircleResolver(source) { _, _ -> asked++; secret }

        assertThat(counting.resolve(uri)).isInstanceOf(OctDocument.Rendered::class.java)
        assertThat(asked).isEqualTo(1)
    }

    private fun public(path: String, type: String, body: String): OctAssetResult =
        OctAssetResult.Public(OctAsset(circle, path, type, body.toByteArray(Charsets.UTF_8)))

    private fun sealed(path: String, type: String, body: String, passphrase: CharArray): OctAssetResult {
        val fixture = OctCircleFixtures.seal(body, circle, KEY_ID, passphrase)
        return OctAssetResult.Sealed(
            OctSealedAsset(circle, path, type, fixture.envelope, fixture.plaintextHash, KEY_ID)
        )
    }

    private fun resolver(source: OctCircleSource, secret: CharArray?): OctCircleResolver =
        OctCircleResolver(source) { _, _ -> secret }

    private class FakeSource(
        private val circleInfo: OctCircleInfo?,
        private val answers: Map<String, OctAssetResult>
    ) : OctCircleSource {

        val fetched = ArrayList<String>()

        override suspend fun info(circleId: String): OctCircleInfo? = circleInfo

        override suspend fun asset(uri: OctUri, info: OctCircleInfo): OctAssetResult {
            fetched += uri.path
            return answers[uri.path] ?: OctAssetResult.Malformed
        }
    }

    private companion object {
        const val KEY_ID = "key-1"
    }
}
