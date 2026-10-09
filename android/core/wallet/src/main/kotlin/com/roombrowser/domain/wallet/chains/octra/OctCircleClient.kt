package com.roombrowser.domain.wallet.chains.octra

import com.roombrowser.domain.oct.OctAssetResult
import com.roombrowser.domain.oct.OctCircleCodec
import com.roombrowser.domain.oct.OctCircleInfo
import com.roombrowser.domain.oct.OctResourceKey
import com.roombrowser.domain.oct.OctUri
import com.roombrowser.domain.wallet.model.NetworkConfig
import com.roombrowser.domain.wallet.rpc.JsonRpcClient
import com.roombrowser.domain.wallet.rpc.RpcEndpointChain
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads an Octra Circle: what it is, and then one asset out of it.
 *
 * The node answers circles over JSON-RPC and nothing else — there is no HTTP gateway to
 * fall back on — so this rides the wallet's existing [RpcEndpointChain], which is what
 * gives it the endpoint failover, the last-known-good memo and the profile's proxy
 * selector for free. No second HTTP client is built here, on purpose: a second one would
 * be a second place where the proxy scope can be forgotten.
 *
 * Two calls, in order, because the second one depends on the first: `circle_info` says
 * whether the circle is public or sealed, and the two are fetched by different methods
 * under different names.
 *
 * The node also offers a signed variant of each read (`octra_circleInfoAuth` and
 * friends), which this app does not use. Signing a circle read would mean spending the
 * wallet's key to browse a page, and the two are deliberately kept apart.
 */
class OctCircleClient(private val rpc: JsonRpcClient = JsonRpcClient()) {

    /** The circle's own description, or null when the node does not know this id. */
    suspend fun info(network: NetworkConfig, circleId: String): OctCircleInfo? =
        chain(network)
            .callObject(METHOD_INFO, listOf(JsonPrimitive(circleId)))
            .let(OctCircleInfo::from)

    /** The asset [uri] names, decoded or refused. */
    suspend fun asset(network: NetworkConfig, uri: OctUri, info: OctCircleInfo): OctAssetResult {
        val chain = chain(network)
        return if (info.sealed) {
            val key = OctResourceKey.of(uri.circleId, uri.path)
            OctCircleCodec.sealedAsset(
                chain.callObject(METHOD_SEALED_ASSET, listOf(JsonPrimitive(uri.circleId), JsonPrimitive(key))),
                uri
            )
        } else {
            OctCircleCodec.publicAsset(
                chain.callObject(METHOD_ASSET, listOf(JsonPrimitive(uri.circleId), JsonPrimitive(uri.path))),
                uri
            )
        }
    }

    private fun chain(network: NetworkConfig): RpcEndpointChain = RpcEndpointChain.of(rpc, network)

    companion object {
        const val METHOD_INFO = "circle_info"
        const val METHOD_ASSET = "circle_asset"
        const val METHOD_SEALED_ASSET = "circle_asset_ciphertext_by_resource_key"
    }
}
