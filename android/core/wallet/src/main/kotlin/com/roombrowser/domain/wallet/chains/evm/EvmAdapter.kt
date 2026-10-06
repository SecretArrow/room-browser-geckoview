package com.roombrowser.domain.wallet.chains.evm

import com.roombrowser.domain.wallet.chains.DerivationPathIndex
import com.roombrowser.domain.wallet.chains.DerivationPathParsing
import com.roombrowser.domain.wallet.crypto.Bip32PrivateKey
import com.roombrowser.domain.wallet.crypto.Hashes
import com.roombrowser.domain.wallet.crypto.Hex
import com.roombrowser.domain.wallet.model.BroadcastResult
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.domain.wallet.model.NetworkConfig
import com.roombrowser.domain.wallet.model.WalletException
import com.roombrowser.domain.wallet.rpc.JsonRpcClient
import com.roombrowser.domain.wallet.rpc.RpcEndpointChain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.web3j.crypto.Credentials
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.Sign
import org.web3j.crypto.StructuredDataEncoder
import org.web3j.crypto.TransactionEncoder
import org.web3j.crypto.Sign.SignatureData
import org.web3j.utils.Numeric
import java.math.BigInteger

/**
 * EVM chain adapter — Ethereum, BNB Chain, Polygon, Arbitrum, Optimism, Base,
 * Avalanche and any custom EVM network.
 *
 * Everything the dApp bridge needs for MetaMask-style interactions:
 * address derivation (BIP44 m/44'/60'/0'/0/i), personal_sign (EIP-191),
 * eth_signTypedData_v3/v4 (EIP-712), eth_sendTransaction
 * (legacy + EIP-1559 with EIP-155 replay protection), balances and
 * ERC-20/ERC-721 reads via eth_call.
 *
 * `eth_signTransaction` is not part of that surface: the bridge answers it
 * unsupported on purpose, so a dApp never receives a signed transaction it
 * could broadcast itself.
 */
class EvmAdapter(private val rpc: JsonRpcClient = JsonRpcClient()) : DerivationPathIndex {

    fun chainType(): ChainType = ChainType.EVM

    /**
     * This network's endpoints as a failover chain.
     *
     * Every RPC call in this adapter goes through one of these instead of
     * `network.rpcUrls.firstOrNull()`, which is what used to make the second
     * url in a network's list dead weight: a network whose primary endpoint
     * had died or was serving a broken certificate could not be used at all,
     * even with a working endpoint recorded right beside it. See
     * [RpcEndpointChain].
     */
    fun endpointsOf(network: NetworkConfig): RpcEndpointChain = RpcEndpointChain.of(rpc, network)

    // ------------------------------------------------------------------
    // Keys & addresses
    // ------------------------------------------------------------------

    /** EIP-55 checksummed address (MetaMask semantics). */
    fun addressFromPrivateKey(privateKey: BigInteger): String {
        val lower = "0x" + org.web3j.crypto.Keys.getAddress(
            org.web3j.crypto.ECKeyPair(privateKey, Sign.publicKeyFromPrivate(privateKey))
        ).lowercase()
        return toChecksumAddress(lower)
    }

    fun deriveAccount(seed: ByteArray, index: Int): DerivedEvmKey {
        val key = Bip32PrivateKey.derive(seed, "m/44'/60'/0'/0/$index")
        return DerivedEvmKey(
            privateKey = key.key,
            address = addressFromPrivateKey(key.key),
            path = "m/44'/60'/0'/0/$index"
        )
    }

    /**
     * Inverts [deriveAccount]: m/44'/60'/0'/0/{index} — the standard BIP44
     * shape, index on the unhardened ADDRESS level. This is the case the old
     * last-level rule already read correctly; the check here is what keeps
     * that behaviour, not what changes it.
     */
    override fun derivationIndexOf(path: String): Int? {
        val levels = DerivationPathParsing.levels(path) ?: return null
        if (levels.size != 5) return null
        if (!DerivationPathParsing.isLevel(levels[0], 44)) return null
        if (!DerivationPathParsing.isLevel(levels[1], 60)) return null
        if (!DerivationPathParsing.isLevel(levels[2], 0)) return null
        if (!DerivationPathParsing.isLevel(levels[3], 0)) return null
        return DerivationPathParsing.levelValue(levels[4])
    }

    fun isValidAddress(address: String): Boolean {
        if (!address.matches(Regex("^0x[0-9a-fA-F]{40}$"))) return false
        // Accept all-lowercase / all-uppercase without checksum enforcement;
        // verify EIP-55 when mixed case.
        val hasLower = address.any { it in 'a'..'f' }
        val hasUpper = address.any { it in 'A'..'F' }
        if (hasLower && hasUpper) return address == toChecksumAddress(address.lowercase())
        return true
    }

    /** EIP-55 mixed-case checksum encoding. */
    fun toChecksumAddress(addressLower: String): String {
        val clean = addressLower.removePrefix("0x").lowercase()
        val hash = Hex.encode(Hashes.keccak256(clean.toByteArray(Charsets.US_ASCII)))
        val sb = StringBuilder("0x")
        clean.forEachIndexed { i, c ->
            val nibble = hash[i]
            sb.append(if (c in 'a'..'f' && nibble >= '8') c.uppercaseChar() else c)
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // Signing (dApp requests)
    // ------------------------------------------------------------------

    /** personal_sign — EIP-191 "\x19Ethereum Signed Message:\n<len>" prefix. */
    fun personalSign(privateKey: BigInteger, message: ByteArray): String {
        val keyPair = org.web3j.crypto.ECKeyPair(privateKey, Sign.publicKeyFromPrivate(privateKey))
        val sig = Sign.signPrefixedMessage(message, keyPair)
        return "0x" + Hex.encode(sig.r + sig.s + byteArrayOf(sig.v[0]))
    }

    /** eth_signTypedData_v3 / _v4 — EIP-712 typed structured data. */
    fun signTypedData(privateKey: BigInteger, typedDataJson: String): String {
        val encoder = StructuredDataEncoder(typedDataJson)
        val digest = encoder.hashStructuredData()
        val keyPair = org.web3j.crypto.ECKeyPair(privateKey, Sign.publicKeyFromPrivate(privateKey))
        val sig = Sign.signMessage(digest, keyPair, false)
        return "0x" + Hex.encode(sig.r + sig.s + byteArrayOf(sig.v[0]))
    }

    // ------------------------------------------------------------------
    // Transactions
    // ------------------------------------------------------------------

    /** Parameters the dApp sends with eth_sendTransaction. */
    data class TransactionParams(
        val from: String,
        val to: String?,
        val value: BigInteger = BigInteger.ZERO,
        val data: String = "0x",
        val gasLimit: BigInteger? = null,
        val gasPrice: BigInteger? = null,
        val maxFeePerGas: BigInteger? = null,
        val maxPriorityFeePerGas: BigInteger? = null,
        val nonce: BigInteger? = null
    )

    /** Filled-in transaction shown on the confirmation screen. */
    data class PreparedTransaction(
        val params: TransactionParams,
        val chainId: Long,
        val networkName: String,
        val isEip1559: Boolean,
        val estimatedGasWei: BigInteger,
        val estimatedFeeWei: BigInteger
    )

    fun parseValue(hexOrDecimal: String?): BigInteger {
        if (hexOrDecimal == null) return BigInteger.ZERO
        return try {
            if (hexOrDecimal.startsWith("0x")) Numeric.decodeQuantity(hexOrDecimal) else BigInteger(hexOrDecimal)
        } catch (_: NumberFormatException) {
            throw WalletException.InvalidParams("Invalid numeric value '$hexOrDecimal'")
        }
    }

    /**
     * Fills in nonce/gas/fees from the network so the confirmation screen can
     * show a complete transaction, then signs it. Only touches the network
     * when the dApp did not provide the values itself.
     */
    suspend fun prepareAndSign(
        network: NetworkConfig,
        privateKey: BigInteger,
        from: String,
        to: String?,
        value: BigInteger,
        data: String,
        gasLimit: BigInteger?,
        gasPrice: BigInteger?,
        maxFeePerGas: BigInteger?,
        maxPriorityFeePerGas: BigInteger?,
        nonce: BigInteger?
    ): Pair<PreparedTransaction, String> {
        val chain = endpointsOf(network)
        if (chain.urls.isEmpty()) throw WalletException.InvalidParams("Network has no RPC endpoint")
        val chainId = network.chainId.toLongOrNull()
            ?: throw WalletException.InvalidParams("Invalid EVM chainId ${network.chainId}")

        val resolvedNonce = nonce ?: getTransactionCount(chain, from)
        val resolvedGas = gasLimit ?: estimateGas(chain, from, to, value, data)

        // Fee strategy: explicit 1559 fields → 1559. Explicit gasPrice →
        // legacy. Otherwise prefer 1559 (with base-fee lookup) and fall back
        // to legacy gasPrice on chains that don't support it.
        var use1559 = maxFeePerGas != null || maxPriorityFeePerGas != null
        var resolvedMaxFee = maxFeePerGas
        var resolvedPriority = maxPriorityFeePerGas
        var resolvedGasPrice = gasPrice
        if (resolvedMaxFee == null && resolvedPriority == null && resolvedGasPrice == null) {
            val fees = suggestFees(chain)
            if (fees != null) {
                use1559 = true
                resolvedMaxFee = fees.first
                resolvedPriority = fees.second
            } else {
                resolvedGasPrice = getGasPrice(chain)
            }
        }
        if (use1559 && resolvedMaxFee != null && resolvedPriority == null) {
            resolvedPriority = resolvedMaxFee.shiftRight(4) // ~6% priority share
        }
        if (use1559 && resolvedPriority != null && resolvedMaxFee == null) {
            resolvedMaxFee = resolvedPriority.multiply(BigInteger.TWO)
        }
        if (use1559 && resolvedMaxFee != null && resolvedPriority != null) {
            // Cap the priority fee at the max fee (RPC requirement).
            if (resolvedPriority > resolvedMaxFee) resolvedPriority = resolvedMaxFee
        }

        val credentials = Credentials.create(
            org.web3j.crypto.ECKeyPair(privateKey, Sign.publicKeyFromPrivate(privateKey))
        )
        val toNorm = to?.takeIf { it.isNotBlank() }?.lowercase()

        val rawTx = if (use1559 && resolvedMaxFee != null && resolvedPriority != null) {
            RawTransaction.createTransaction(
                chainId,
                resolvedNonce,
                resolvedGas,
                toNorm ?: "",
                value,
                data,
                resolvedPriority,
                resolvedMaxFee
            )
        } else {
            RawTransaction.createTransaction(
                resolvedNonce,
                resolvedGasPrice ?: getGasPrice(chain),
                resolvedGas,
                toNorm ?: "",
                value,
                data
            )
        }

        val signed: ByteArray = TransactionEncoder.signMessage(rawTx, chainId, credentials)

        val feePerGas = if (use1559 && resolvedMaxFee != null) resolvedMaxFee else resolvedGasPrice ?: BigInteger.ZERO
        val prepared = PreparedTransaction(
            params = TransactionParams(
                from = from, to = toNorm, value = value, data = data,
                gasLimit = resolvedGas, gasPrice = resolvedGasPrice,
                maxFeePerGas = resolvedMaxFee, maxPriorityFeePerGas = resolvedPriority,
                nonce = resolvedNonce
            ),
            chainId = chainId,
            networkName = network.name,
            isEip1559 = use1559,
            estimatedGasWei = resolvedGas,
            estimatedFeeWei = resolvedGas.multiply(feePerGas)
        )
        return prepared to ("0x" + Hex.encode(signed))
    }

    suspend fun broadcastRaw(network: NetworkConfig, signedRawHex: String): BroadcastResult {
        return try {
            val result = endpointsOf(network)
                .call("eth_sendRawTransaction", listOf(JsonPrimitive(signedRawHex)))
            BroadcastResult.Ok(result.jsonPrimitive.content)
        } catch (e: WalletException) {
            BroadcastResult.Error(e.message ?: "Broadcast failed")
        }
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    suspend fun getBalance(network: NetworkConfig, address: String): BigInteger? {
        val result = endpointsOf(network).call(
            "eth_getBalance",
            listOf(JsonPrimitive(address), JsonPrimitive("latest"))
        )
        return Numeric.decodeQuantity(result.jsonPrimitive.content)
    }

    suspend fun getChainId(chain: RpcEndpointChain): Long {
        val result = chain.call("eth_chainId")
        return Numeric.decodeQuantity(result.jsonPrimitive.content).toLong()
    }

    suspend fun getTransactionCount(chain: RpcEndpointChain, address: String): BigInteger {
        val result = chain.call(
            "eth_getTransactionCount",
            listOf(JsonPrimitive(address), JsonPrimitive("pending"))
        )
        return Numeric.decodeQuantity(result.jsonPrimitive.content)
    }

    suspend fun getGasPrice(chain: RpcEndpointChain): BigInteger {
        val result = chain.call("eth_gasPrice")
        return Numeric.decodeQuantity(result.jsonPrimitive.content)
    }

    /** Returns (maxFeePerGas, maxPriorityFeePerGas) or null when unsupported. */
    suspend fun suggestFees(chain: RpcEndpointChain): Pair<BigInteger, BigInteger>? = try {
        val priorityHex = chain.call("eth_maxPriorityFeePerGas").jsonPrimitive.content
        val priority = Numeric.decodeQuantity(priorityHex)
        val base = baseFee(chain)
        if (base != null) {
            // maxFee = 2 * base + priority (2x headroom, standard practice)
            base.multiply(BigInteger.TWO).add(priority) to priority
        } else {
            null
        }
    } catch (_: WalletException) {
        null
    }

    private suspend fun baseFee(chain: RpcEndpointChain): BigInteger? = try {
        val block = chain.callObject(
            "eth_getBlockByNumber",
            listOf(JsonPrimitive("latest"), JsonPrimitive(false))
        )
        block["baseFeePerGas"]?.jsonPrimitive?.content?.let { Numeric.decodeQuantity(it) }
    } catch (_: WalletException) {
        null
    }

    suspend fun estimateGas(
        chain: RpcEndpointChain,
        from: String,
        to: String?,
        value: BigInteger,
        data: String
    ): BigInteger {
        val txObject = buildJsonObject {
            put("from", JsonPrimitive(from))
            to?.let { put("to", JsonPrimitive(it)) }
            put("value", JsonPrimitive(Numeric.encodeQuantity(value)))
            if (data != "0x" && data.isNotBlank()) put("data", JsonPrimitive(data))
        }
        val result = chain.call("eth_estimateGas", listOf(txObject))
        return Numeric.decodeQuantity(result.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------
    // ERC-20 / ERC-721 reads (dashboard + confirmation enrichment)
    // ------------------------------------------------------------------

    suspend fun erc20Balance(
        network: NetworkConfig, contract: String, holder: String
    ): BigInteger? = ethCall(network, contract, "0x70a08231" + holder.removePrefix("0x").lowercase().padStart(64, '0'))
        ?.let { Numeric.toBigIntNoPrefix(it.removePrefix("0x")) }

    suspend fun erc20Decimals(network: NetworkConfig, contract: String): Int? =
        ethCall(network, contract, "0x313ce567")?.let {
            runCatching { Numeric.toBigIntNoPrefix(it.removePrefix("0x")).toInt() }.getOrNull()
        }

    suspend fun erc20Symbol(network: NetworkConfig, contract: String): String? =
        ethCall(network, contract, "0x95d89b41")?.let { decodeAbiString(it.removePrefix("0x")) }

    suspend fun erc721Balance(
        network: NetworkConfig, contract: String, holder: String
    ): BigInteger? = ethCall(network, contract, "0x70a08231" + holder.removePrefix("0x").lowercase().padStart(64, '0'))
        ?.let { Numeric.toBigIntNoPrefix(it.removePrefix("0x")) }

    suspend fun ethCall(network: NetworkConfig, to: String, dataHex: String): String? {
        val callObject = buildJsonObject {
            put("to", JsonPrimitive(to))
            put("data", JsonPrimitive(dataHex))
        }
        return try {
            val result = endpointsOf(network)
                .call("eth_call", listOf(callObject, JsonPrimitive("latest")))
            result.jsonPrimitive.content
        } catch (_: WalletException) {
            null
        }
    }

    /** ABI-encoded `string` return decode (symbol()). */
    private fun decodeAbiString(hex: String): String? {
        return try {
            val bytes = Hex.decode(hex)
            if (bytes.size < 64) return null
            val offset = BigInteger(bytes.copyOfRange(0, 32)).toInt()
            if (offset + 32 > bytes.size) return null
            val len = BigInteger(bytes.copyOfRange(offset, offset + 32)).toInt()
            if (offset + 32 + len > bytes.size) return null
            String(bytes.copyOfRange(offset + 32, offset + 32 + len), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    data class DerivedEvmKey(val privateKey: BigInteger, val address: String, val path: String)
}
