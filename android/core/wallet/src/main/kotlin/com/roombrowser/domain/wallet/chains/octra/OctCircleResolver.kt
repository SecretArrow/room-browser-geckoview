package com.roombrowser.domain.wallet.chains.octra

import com.roombrowser.domain.oct.OctAssetResult
import com.roombrowser.domain.oct.OctCircleDocument
import com.roombrowser.domain.oct.OctCircleInfo
import com.roombrowser.domain.oct.OctInlined
import com.roombrowser.domain.oct.OctSealed
import com.roombrowser.domain.oct.OctSealedAsset
import com.roombrowser.domain.oct.OctSealedResult
import com.roombrowser.domain.oct.OctUri
import com.roombrowser.domain.wallet.model.NetworkConfig

/** Exactly the two reads a circle needs, so a test can answer them without a node. */
interface OctCircleSource {
    suspend fun info(circleId: String): OctCircleInfo?
    suspend fun asset(uri: OctUri, info: OctCircleInfo): OctAssetResult
}

/** Binds a client to one profile's node. */
class RpcCircleSource(
    private val client: OctCircleClient,
    private val network: NetworkConfig
) : OctCircleSource {
    override suspend fun info(circleId: String): OctCircleInfo? = client.info(network, circleId)
    override suspend fun asset(uri: OctUri, info: OctCircleInfo): OctAssetResult =
        client.asset(network, uri, info)
}

sealed interface OctDocument {
    /** The materialized document, ready to hand to the engine. */
    data class Rendered(val html: String) : OctDocument

    /** The circle is sealed and the passphrase we hold is missing or wrong. */
    data class NeedsPassphrase(val circleId: String, val keyId: String) : OctDocument

    /** The node does not know this circle, or answered something that is not its asset. */
    data object Unavailable : OctDocument
}

/**
 * Turns an `oct://` address into one document the engine can render.
 *
 * A circle is fetched, not served: the entry document is read first, then every reference
 * it makes is fetched too, and the whole thing is handed to the engine as one self-contained
 * document. A sealed circle seals every asset in it, so the passphrase is read once per
 * resolve and reused for each of them rather than asked for once per asset.
 */
class OctCircleResolver(
    private val source: OctCircleSource,
    private val passphrase: suspend (circleId: String, keyId: String) -> CharArray?
) {

    suspend fun resolve(uri: OctUri): OctDocument {
        val info = source.info(uri.circleId) ?: return OctDocument.Unavailable

        var held: CharArray? = null
        var asked = false

        // The passphrase is per circle, not per asset, so the first sealed asset read
        // supplies it and every later one in this call reuses the same array.
        suspend fun unlock(keyId: String): CharArray? {
            if (!asked) {
                asked = true
                held = passphrase(uri.circleId, keyId)
            }
            return held
        }

        suspend fun opened(asset: OctSealedAsset): ByteArray? {
            val secret = unlock(asset.keyId) ?: return null
            if (secret.isEmpty()) return null
            // A malformed envelope reads the same as a wrong passphrase: the only thing
            // the reader can do about either is try again.
            val result = OctSealed.open(asset.envelope, uri.circleId, asset.keyId, asset.plaintextHash, secret)
            return (result as? OctSealedResult.Opened)?.bytes
        }

        val entry = when (val fetched = source.asset(uri, info)) {
            OctAssetResult.Malformed -> return OctDocument.Unavailable
            is OctAssetResult.Public ->
                OctInlined(uri.path, fetched.asset.contentType, fetched.asset.bytes)
            is OctAssetResult.Sealed -> {
                val asset = fetched.asset
                val bytes = opened(asset) ?: return OctDocument.NeedsPassphrase(uri.circleId, asset.keyId)
                OctInlined(uri.path, asset.contentType, bytes)
            }
        }

        val html = entry.text ?: return OctDocument.Unavailable

        val assets = LinkedHashMap<String, OctInlined>()
        var budget = OctCircleDocument.MAX_TOTAL_BYTES
        val queue = ArrayDeque<Pair<OctCircleDocument.Reference, Int>>()
        OctCircleDocument.references(html, uri.path).forEach { queue.addLast(it to 0) }

        while (queue.isNotEmpty()) {
            if (assets.size >= OctCircleDocument.MAX_SUBRESOURCES) break
            val (reference, depth) = queue.removeFirst()
            if (assets.containsKey(reference.path)) continue

            val fetched = source.asset(OctUri(uri.circleId, reference.path), info)
            val inlined: OctInlined? = when (fetched) {
                OctAssetResult.Malformed -> null
                is OctAssetResult.Public ->
                    OctInlined(reference.path, fetched.asset.contentType, fetched.asset.bytes)
                is OctAssetResult.Sealed -> {
                    val asset = fetched.asset
                    opened(asset)?.let { OctInlined(reference.path, asset.contentType, it) }
                }
            }
            if (inlined == null || inlined.bytes.size > budget) continue

            budget -= inlined.bytes.size
            assets[reference.path] = inlined
            if (reference.styleSheet && depth < OctCircleDocument.MAX_CSS_DEPTH) {
                val css = inlined.text ?: continue
                OctCircleDocument.styleSheetReferences(css, reference.path)
                    .forEach { queue.addLast(it to depth + 1) }
            }
        }

        return OctDocument.Rendered(OctCircleDocument.materialize(html, uri, assets))
    }
}
