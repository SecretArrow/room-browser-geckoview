package com.roombrowser.domain.oct

import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What the node says a circle is.
 *
 * [resourceMode] is the only field that changes what this app does: a public circle
 * answers `circle_asset`, and a sealed one answers nothing but a ciphertext keyed by
 * the resource key. [browserMode] is carried for display, as the reference client
 * carries it.
 */
data class OctCircleInfo(
    val circleId: String,
    val browserMode: String,
    val resourceMode: String
) {
    val sealed: Boolean get() = resourceMode == SEALED_READ

    companion object {
        const val PUBLIC_RESOURCES = "public_resources"
        const val SEALED_READ = "sealed_read"

        fun from(json: JsonObject): OctCircleInfo? {
            val id = json.text("circle_id") ?: return null
            if (!OctUri.isCircleId(id)) return null
            return OctCircleInfo(
                circleId = id,
                browserMode = json.text("browser_mode").orEmpty(),
                resourceMode = json.text("resource_mode").orEmpty()
            )
        }
    }
}

/** A circle asset that arrived as plain bytes. */
class OctAsset(
    val circleId: String,
    val path: String,
    val contentType: String,
    val bytes: ByteArray
)

/** A circle asset that arrived sealed, still to be opened. */
class OctSealedAsset(
    val circleId: String,
    val path: String,
    val contentType: String,
    val envelope: ByteArray,
    val plaintextHash: String,
    val keyId: String
)

/** What a fetch turned out to be. */
sealed interface OctAssetResult {
    data class Public(val asset: OctAsset) : OctAssetResult
    data class Sealed(val asset: OctSealedAsset) : OctAssetResult

    /** The node answered something that is not this circle's asset. Never guessed at. */
    data object Malformed : OctAssetResult
}

/**
 * Turns the wire shapes of `circle_asset` and
 * `circle_asset_ciphertext_by_resource_key` into bytes, or refuses them.
 *
 * The checks are not decoration. Every field here is answered by a third party, and
 * three of them can be used to make this app render something other than what the
 * reader asked for: a `canonical_path` that is not the one we requested, a
 * `circle_id` that is not ours, and a body that is not the canonical base64 of its
 * own size. Each is a rejection, not a repair.
 */
object OctCircleCodec {

    /** The node's own ceiling for a body it will answer inline, per the reference reader. */
    const val MAX_INLINE_DECODED_BYTES = 1_048_576

    fun publicAsset(json: JsonObject, uri: OctUri): OctAssetResult {
        if (!describes(json, uri)) return OctAssetResult.Malformed
        val bytes = body(json, "body_b64") ?: return OctAssetResult.Malformed
        if (!sizeAgrees(json, bytes.size)) return OctAssetResult.Malformed
        return OctAssetResult.Public(
            OctAsset(uri.circleId, uri.path, contentType(json), bytes)
        )
    }

    fun sealedAsset(json: JsonObject, uri: OctUri): OctAssetResult {
        if (!describes(json, uri)) return OctAssetResult.Malformed
        val envelope = body(json, "ciphertext_b64") ?: return OctAssetResult.Malformed
        val hash = json.text("plaintext_hash") ?: return OctAssetResult.Malformed
        val keyId = json.text("key_id") ?: return OctAssetResult.Malformed
        return OctAssetResult.Sealed(
            OctSealedAsset(uri.circleId, uri.path, contentType(json), envelope, hash, keyId)
        )
    }

    /**
     * A `data:` URL for [bytes], which is how a circle reaches the engine: both
     * engines accept one, and a `data:` document has an opaque origin.
     */
    fun dataUrl(contentType: String, bytes: ByteArray): String {
        val type = mediaType(contentType)
        val charset = if (type.startsWith("text/") && !type.contains("charset=")) ";charset=utf-8" else ""
        return "data:$type$charset;base64," + Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * The answer is about this circle, at this path — every field it carries must say so.
     *
     * Absent is allowed and mismatched is not, because the two responses are shaped
     * differently: `circle_asset` names the path it answered, and the by-resource-key
     * response may name neither. A field that IS present and disagrees is the case worth
     * refusing, since it is the one where the bytes belong to something else.
     */
    private fun describes(json: JsonObject, uri: OctUri): Boolean =
        json.text("circle_id").let { it == null || it == uri.circleId } &&
            json.text("canonical_path").let { it == null || it == uri.path }

    private fun body(json: JsonObject, key: String): ByteArray? {
        val wire = json.text(key) ?: return null
        if (wire.length > 4 * ((MAX_INLINE_DECODED_BYTES + 2) / 3)) return null
        val decoded = canonicalBase64(wire) ?: return null
        return decoded.takeIf { it.size <= MAX_INLINE_DECODED_BYTES }
    }

    /**
     * Strict and canonical at once: the alphabet has to be base64's, the padding has to
     * be right, and re-encoding has to reproduce the input. The last one is what a
     * decoder alone will not tell you — it accepts spellings the node never wrote.
     */
    private fun canonicalBase64(wire: String): ByteArray? = runCatching {
        Base64.getDecoder().decode(wire)
    }.getOrNull()?.takeIf { Base64.getEncoder().encodeToString(it) == wire }

    /**
     * `size_bytes` is declared as a string by the node. It is only compared when the
     * body is not encoded, because a compressed body's declared size is the size of
     * something other than its own bytes.
     */
    private fun sizeAgrees(json: JsonObject, decoded: Int): Boolean {
        val encoding = json.text("encoding").orEmpty().lowercase()
        if (encoding.isNotEmpty() && encoding != "identity") return true
        return json.text("size_bytes")?.toIntOrNull()?.let { it == decoded } ?: true
    }

    private fun contentType(json: JsonObject): String = mediaType(json.text("content_type").orEmpty())

    /**
     * A media type out of an answer we do not control, reduced to `type/subtype`. The
     * value ends up inside a URL, so a `;` or a `,` in it would not be a content type
     * any more — it would be URL syntax.
     */
    private fun mediaType(raw: String): String {
        val trimmed = raw.trim()
        val slash = trimmed.indexOf('/')
        if (slash <= 0) return FALLBACK_TYPE
        val type = trimmed.substring(0, slash)
        val subtype = trimmed.substring(slash + 1).substringBefore(';').trim()
        val legal = type.isNotEmpty() && subtype.isNotEmpty() &&
            (type + subtype).all { it.isLetterOrDigit() || it in "-!#$&^_.+" }
        return if (legal) "$type/$subtype" else FALLBACK_TYPE
    }

    private const val FALLBACK_TYPE = "text/plain"
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
