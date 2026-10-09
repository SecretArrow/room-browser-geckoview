package com.roombrowser.domain.wallet.rpc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.roombrowser.domain.wallet.model.WalletException
import com.roombrowser.domain.proxy.OutboundProxy
import com.roombrowser.domain.proxy.ProxyScope
import java.util.concurrent.TimeUnit

/**
 * JSON-RPC 2.0 client shared by EVM, Solana, Aptos, Sui and TRON adapters.
 * POSTs one request per call with a short timeout and clear error mapping —
 * RPC problems surface as [WalletException] instead of being swallowed.
 */
class JsonRpcClient(
    private val http: OkHttpClient = defaultClient()
) {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /**
     * Calls `method(params)` on [endpoint] and returns the `result` element.
     * Throws [WalletException.RpcError] on a JSON-RPC error object,
     * [WalletException.NetworkUnavailable] on transport failures.
     */
    suspend fun call(endpoint: String, method: String, params: List<JsonElement> = emptyList()): JsonElement =
        withContext(Dispatchers.IO) {
            val url = endpoint.toHttpUrlOrNull()
                ?: throw WalletException.InvalidParams("Invalid RPC endpoint: $endpoint")
            val body = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", method)
                put("params", kotlinx.serialization.json.JsonArray(params))
            }.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()
            val responseText = try {
                http.newCall(request).execute().use { resp ->
                    when {
                        !resp.isSuccessful -> throw WalletException.RpcError(
                            resp.code,
                            "RPC HTTP ${resp.code} from $endpoint"
                        )
                        else -> resp.body?.string() ?: throw WalletException.RpcError(resp.code, "Empty RPC response")
                    }
                }
            } catch (e: WalletException) {
                throw e
            } catch (e: java.io.IOException) {
                throw transportFailure("RPC", e)
            }
            val parsed = parseObject(responseText, endpoint)
            parsed["error"]?.let { err ->
                val obj = (err as? JsonObject)
                val code = obj?.get("code")?.jsonPrimitive?.content?.toIntOrNull() ?: -1
                val message = obj?.get("message")?.jsonPrimitive?.content ?: "RPC error"
                throw WalletException.RpcError(code, message)
            }
            parsed["result"] ?: throw WalletException.RpcError(-1, "RPC response missing result")
        }

    /** Convenience for single-object params (most chains use this shape). */
    suspend fun callObject(endpoint: String, method: String, params: List<JsonElement> = emptyList()): JsonObject =
        call(endpoint, method, params).jsonObject

    /** Plain REST helpers for Aptos (indexer), Cosmos LCD and Bitcoin APIs. */
    suspend fun getJson(endpoint: String): JsonElement = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(endpoint).get().build()
        executeForJson(request)
    }

    suspend fun postJson(endpoint: String, payload: JsonElement): JsonElement = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        executeForJson(request)
    }

    suspend fun postJsonText(endpoint: String, bodyText: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint)
            .post(bodyText.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request)
    }

    suspend fun getBytes(endpoint: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(endpoint).get().build()
        try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw WalletException.RpcError(resp.code, "HTTP ${resp.code} from $endpoint")
                resp.body?.bytes() ?: throw WalletException.RpcError(resp.code, "Empty body")
            }
        } catch (e: WalletException) {
            throw e
        } catch (e: java.io.IOException) {
            throw transportFailure("Request", e)
        }
    }

    private suspend fun executeForJson(request: Request): JsonElement {
        val text = execute(request)
        return parse(text, request.url.toString())
    }

    /**
     * Parses a response body, turning "this is not JSON at all" into an
     * endpoint error rather than a crash.
     *
     * WHY THIS IS NOT JUST DEFENSIVE CODING: a dead host does not always fail
     * the connection. It answers 200 with an HTML parking page, a proxy error
     * page, or a truncated body — and `parseToJsonElement` then throws a
     * [kotlinx.serialization.SerializationException], which is not a
     * [WalletException] and therefore used to escape the adapter's failover
     * and abort the whole call. The endpoint that could never have answered
     * JSON-RPC took the working endpoint down with it, which is precisely the
     * failure this client's error mapping exists to prevent.
     *
     * 502 is the honest code to report: a gateway returned something unusable.
     * It sits in the HTTP range on purpose, so [RpcEndpointChain] reads it as
     * "this endpoint could not answer" and moves to the next one.
     *
     * The result is [JsonElement] and not [JsonObject] because several REST
     * endpoints this client serves answer with a top-level ARRAY — Bitcoin's
     * `/address/{a}/utxo` and the EVM chainlist catalog among them. Callers
     * that need an object ask for one; forcing it here would have rejected
     * healthy responses.
     */
    private fun parse(text: String, endpoint: String): JsonElement = try {
        json.parseToJsonElement(text)
    } catch (e: Exception) {
        throw WalletException.RpcError(
            502,
            "RPC endpoint did not return JSON (${e.message?.lineSequence()?.firstOrNull() ?: "unreadable"})"
        )
    }

    /** [parse] for the JSON-RPC shape, which is always a single object. */
    private fun parseObject(text: String, endpoint: String): JsonObject = try {
        parse(text, endpoint).jsonObject
    } catch (e: WalletException) {
        throw e
    } catch (e: Exception) {
        throw WalletException.RpcError(502, "RPC endpoint returned a non-object response")
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw WalletException.RpcError(resp.code, "HTTP ${resp.code} from $request")
                resp.body?.string() ?: throw WalletException.RpcError(resp.code, "Empty body")
            }
        } catch (e: WalletException) {
            throw e
        } catch (e: java.io.IOException) {
            throw transportFailure("Request", e)
        }
    }

    /**
     * Maps a transport failure onto a [WalletException].
     *
     * TLS GETS ITS OWN CASE because it is the one transport failure whose
     * cause is at the far end. OkHttp reports an expired chain, an untrusted
     * issuer and a hostname mismatch as an [javax.net.ssl.SSLException]
     * (`SSLHandshakeException`, `SSLPeerUnverifiedException`) — all of which
     * are IOExceptions, so this branch has to come first or it never runs.
     * Without it the user is told "network unavailable" and goes to check
     * their own Wi-Fi for a fault that belongs to the endpoint.
     *
     * Nothing here relaxes certificate validation. The failure is named and
     * reported, never bypassed; [RpcEndpointChain] then tries the network's
     * next endpoint, which is exactly what the second url in a network's list
     * is for. A host with a mis-configured certificate is the common case of
     * endpoint rot, not an attack to be worked around.
     */
    private fun transportFailure(noun: String, e: java.io.IOException): WalletException =
        if (e is javax.net.ssl.SSLException) {
            WalletException.TlsFailure(
                "$noun endpoint presented a certificate this device rejected (${e.message})"
            )
        } else {
            WalletException.NetworkUnavailable("$noun unreachable (${e.message})")
        }

    companion object {
        /**
         * Every chain's transport is built here, so this one line is the whole of the
         * wallet's proxy support. It is a SELECTOR rather than a fixed proxy: the app
         * installs the bound profile's wallet entry at every profile bind, and a client
         * built before that must still pick it up on its next request.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .proxySelector(OutboundProxy.selector(ProxyScope.WALLET))
            .build()
    }
}
