package com.roombrowser.domain.oct

import com.google.common.truth.Truth.assertThat
import java.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/**
 * The wire decoding for a circle asset. Each rejection here is a way a third party's
 * answer could have made this app render something the reader did not ask for.
 */
class OctCircleCodecTest {

    private val circle = "oct" + "D".repeat(44)
    private val uri = OctUri.parse("oct://$circle/index.html")!!
    private val body = Base64.getEncoder().encodeToString("<h1>circle</h1>".toByteArray())

    private fun asset(vararg overrides: Pair<String, String>) = buildJsonObject {
        put("circle_id", circle)
        put("canonical_path", "/index.html")
        put("content_type", "text/html")
        put("encoding", "identity")
        put("size_bytes", "15")
        put("body_b64", body)
        overrides.forEach { (key, value) -> put(key, value) }
    }

    @Test
    fun `a public asset decodes to its own bytes`() {
        val result = OctCircleCodec.publicAsset(asset(), uri)
        assertThat(result).isInstanceOf(OctAssetResult.Public::class.java)
        val decoded = (result as OctAssetResult.Public).asset
        assertThat(String(decoded.bytes)).isEqualTo("<h1>circle</h1>")
        assertThat(decoded.contentType).isEqualTo("text/html")
        assertThat(decoded.path).isEqualTo("/index.html")
    }

    @Test
    fun `a body that is not canonical base64 is refused rather than decoded`() {
        val unpadded = OctCircleCodec.publicAsset(asset("body_b64" to "aGk"), uri)
        assertThat(unpadded).isEqualTo(OctAssetResult.Malformed)
        val notBase64 = OctCircleCodec.publicAsset(asset("body_b64" to "not base64!"), uri)
        assertThat(notBase64).isEqualTo(OctAssetResult.Malformed)
    }

    @Test
    fun `an answer about another circle or another path is refused`() {
        val otherCircle = "oct" + "E".repeat(44)
        assertThat(OctCircleCodec.publicAsset(asset("circle_id" to otherCircle), uri))
            .isEqualTo(OctAssetResult.Malformed)
        assertThat(OctCircleCodec.publicAsset(asset("canonical_path" to "/other.html"), uri))
            .isEqualTo(OctAssetResult.Malformed)
    }

    @Test
    fun `an answer that names neither is taken at its word, as the sealed shape does`() {
        val bare = buildJsonObject {
            put("content_type", "text/html")
            put("body_b64", body)
        }
        assertThat(OctCircleCodec.publicAsset(bare, uri)).isInstanceOf(OctAssetResult.Public::class.java)
    }

    @Test
    fun `a body larger than the node answers inline is refused`() {
        val huge = "A".repeat(1 + 4 * ((OctCircleCodec.MAX_INLINE_DECODED_BYTES + 2) / 3))
        assertThat(OctCircleCodec.publicAsset(asset("body_b64" to huge), uri))
            .isEqualTo(OctAssetResult.Malformed)
    }

    @Test
    fun `a declared size that disagrees with the bytes is refused`() {
        assertThat(OctCircleCodec.publicAsset(asset("size_bytes" to "999"), uri))
            .isEqualTo(OctAssetResult.Malformed)
    }

    @Test
    fun `a compressed body's declared size describes something else, so it is not compared`() {
        val gzipped = asset("encoding" to "gzip", "size_bytes" to "999")
        assertThat(OctCircleCodec.publicAsset(gzipped, uri)).isInstanceOf(OctAssetResult.Public::class.java)
    }

    @Test
    fun `a sealed asset yields the envelope and the facts needed to open it`() {
        val sealed = buildJsonObject {
            put("content_type", "text/html")
            put("ciphertext_b64", body)
            put("plaintext_hash", "ab".repeat(32))
            put("key_id", "key-1")
        }
        val result = OctCircleCodec.sealedAsset(sealed, uri)
        assertThat(result).isInstanceOf(OctAssetResult.Sealed::class.java)
        val decoded = (result as OctAssetResult.Sealed).asset
        assertThat(decoded.keyId).isEqualTo("key-1")
        assertThat(decoded.plaintextHash).isEqualTo("ab".repeat(32))
        assertThat(Base64.getEncoder().encodeToString(decoded.envelope)).isEqualTo(body)
    }

    @Test
    fun `a sealed asset without a key id or a hash cannot be opened and is refused`() {
        val noKey = buildJsonObject {
            put("ciphertext_b64", body)
            put("plaintext_hash", "ab".repeat(32))
        }
        val noHash = buildJsonObject {
            put("ciphertext_b64", body)
            put("key_id", "key-1")
        }
        assertThat(OctCircleCodec.sealedAsset(noKey, uri)).isEqualTo(OctAssetResult.Malformed)
        assertThat(OctCircleCodec.sealedAsset(noHash, uri)).isEqualTo(OctAssetResult.Malformed)
    }

    @Test
    fun `circle info is read only when it names a circle`() {
        val info = OctCircleInfo.from(
            buildJsonObject {
                put("circle_id", circle)
                put("browser_mode", "gateway_allowed")
                put("resource_mode", "sealed_read")
            }
        )
        assertThat(info!!.sealed).isTrue()
        assertThat(OctCircleInfo.from(buildJsonObject { put("circle_id", "oct-nope") })).isNull()
    }

    @Test
    fun `a text document is declared utf-8 and a binary one is not`() {
        val html = OctCircleCodec.dataUrl("text/html", ByteArray(0))
        assertThat(html).isEqualTo("data:text/html;charset=utf-8;base64,")
        val png = OctCircleCodec.dataUrl("image/png", ByteArray(0))
        assertThat(png).isEqualTo("data:image/png;base64,")
    }

    @Test
    fun `a content type that is not one cannot spell url syntax into the data url`() {
        assertThat(OctCircleCodec.dataUrl("text/html,evil", ByteArray(0))).startsWith("data:text/plain")
        assertThat(OctCircleCodec.dataUrl("", ByteArray(0))).startsWith("data:text/plain")
        assertThat(OctCircleCodec.dataUrl("nonsense", ByteArray(0))).startsWith("data:text/plain")
    }

    @Test
    fun `the data url carries the bytes it was given`() {
        val payload = "<h1>hi</h1>".toByteArray()
        val url = OctCircleCodec.dataUrl("text/html", payload)
        val encoded = url.substringAfter("base64,")
        assertThat(Base64.getDecoder().decode(encoded)).isEqualTo(payload)
    }
}
