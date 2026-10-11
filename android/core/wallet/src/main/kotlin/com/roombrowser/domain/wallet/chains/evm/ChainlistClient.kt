package com.roombrowser.domain.wallet.chains.evm

import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.domain.wallet.model.NetworkConfig
import com.roombrowser.domain.wallet.model.WalletException
import com.roombrowser.domain.wallet.rpc.JsonRpcClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Chainlist discovery client.
 *
 * Pulls the live catalog from chainid.network (the dataset behind
 * chainlist.org), keeping only chains with usable public RPC endpoints and
 * mapping them to [NetworkConfig]. Results are cached in memory; the
 * bundled [EvmNetworkSnapshot] covers offline use, and anything the user
 * actually adds is persisted in the app database — Chainlist is a discovery
 * aid, never a hard dependency.
 */
class ChainlistClient(
    private val rpc: JsonRpcClient = JsonRpcClient(),
    private val catalogUrl: String = "https://chainid.network/chains.json"
) {

    private var cached: List<NetworkConfig>? = null
    private var cachedAt: Long = 0

    suspend fun search(query: String, forceRefresh: Boolean = false): List<NetworkConfig> {
        val all = catalog(forceRefresh)
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all.take(100)
        return all.filter {
            it.name.lowercase().contains(q) ||
                it.chainId == q ||
                it.nativeSymbol.lowercase() == q
        }.take(100)
    }

    suspend fun catalog(forceRefresh: Boolean = false): List<NetworkConfig> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cached != null && now - cachedAt < CACHE_MS) return cached!!
        val fetched = fetch() ?: return cached ?: snapshot()
        cached = fetched
        cachedAt = now
        return fetched
    }

    /** Snapshot fallback for offline first-run. */
    fun snapshot(): List<NetworkConfig> = EvmNetworkSnapshot.entries.map {
        NetworkConfig.evm(it.chainId, it.name, it.rpcUrls, it.symbol, it.explorer, it.testnet)
    }

    private suspend fun fetch(): List<NetworkConfig>? = try {
        val element = rpc.getJson(catalogUrl)
        val array = element as? JsonArray ?: return null
        val seen = HashSet<Long>()
        array.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val chainId = obj["chainId"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
            if (!seen.add(chainId)) return@mapNotNull null
            val rpcs = (obj["rpc"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.filter { it.startsWith("https://") && !it.contains("\${") }
                ?.distinct()
                ?.take(3)
                ?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.content ?: "Chain $chainId"
            val currency = obj["nativeCurrency"] as? JsonObject
            val symbol = currency?.get("symbol")?.jsonPrimitive?.content ?: "ETH"
            val explorer = (obj["explorers"] as? JsonArray)
                ?.firstOrNull()
                ?.let { (it as? JsonObject)?.get("url")?.jsonPrimitive?.content }
            NetworkConfig.evm(chainId, name, rpcs, symbol, explorer)
        }.sortedBy { it.chainId.toLong() }
    } catch (_: WalletException) {
        null
    }

    companion object {
        private const val CACHE_MS = 10 * 60 * 1000L

        /** Chainlist "add to MetaMask" style entry (wallet_addEthereumChain). */
        fun addChainRequestToConfig(chainType: ChainType, params: JsonObject): NetworkConfig? {
            if (chainType != ChainType.EVM) return null
            val chainIdHex = params["chainId"]?.jsonPrimitive?.content ?: return null
            val chainId = chainIdHex.removePrefix("0x").toLongOrNull(16) ?: return null
            val name = params["chainName"]?.jsonPrimitive?.content ?: "Chain $chainId"
            val rpcUrls = (params["rpcUrls"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.takeIf { it.isNotEmpty() }
                ?: return null
            val currency = params["nativeCurrency"] as? JsonObject
            val symbol = currency?.get("symbol")?.jsonPrimitive?.content ?: "ETH"
            val decimals = currency?.get("decimals")?.jsonPrimitive?.content?.toIntOrNull() ?: 18
            val explorer = (params["blockExplorerUrls"] as? JsonArray)
                ?.firstOrNull()?.jsonPrimitive?.contentOrNull
            return NetworkConfig(
                id = "EVM:$chainId",
                chainType = ChainType.EVM,
                chainId = chainId.toString(),
                name = name,
                rpcUrls = rpcUrls,
                nativeSymbol = symbol,
                nativeDecimals = decimals,
                explorerUrl = explorer
            )
        }
    }
}
