package com.roombrowser.domain.wallet.chains.aptos

import com.roombrowser.domain.wallet.chains.DerivationPathIndex
import com.roombrowser.domain.wallet.chains.DerivationPathParsing
import com.roombrowser.domain.wallet.crypto.Ed25519
import com.roombrowser.domain.wallet.crypto.Hashes
import com.roombrowser.domain.wallet.crypto.Hex
import com.roombrowser.domain.wallet.crypto.Slip10Ed25519Key
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.domain.wallet.model.NetworkConfig
import com.roombrowser.domain.wallet.model.WalletException
import com.roombrowser.domain.wallet.rpc.JsonRpcClient
import com.roombrowser.domain.wallet.rpc.RpcEndpointChain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Aptos adapter.
 *
 * Keys: SLIP-0010 ed25519 at m/54'/6'/0'/0'/i' (Petra-compatible derivation).
 * Address = sha3_256(pubkey || 0x00) — verified against the official TS SDK.
 *
 * Transactions use the fullnode's own /v1/transactions/signing_message
 * endpoint: the node serializes the payload and returns the exact signing
 * message (sha3_256("APTOS::RawTransaction") || bcs(RawTransaction)), so the
 * signature always matches the chain's serialization rules. Pre-serialized
 * wallet-standard transactions (aptos:signTransaction) are hashed locally.
 */
class AptosAdapter(private val rpc: JsonRpcClient = JsonRpcClient()) : DerivationPathIndex {

    fun chainType(): ChainType = ChainType.APTOS

    /**
     * This network's endpoints as a failover chain.
     *
     * Aptos talks to a fullnode over REST rather than JSON-RPC, so calls go
     * through [RpcEndpointChain.firstWorking] with the same paths as before.
     * Picking the base url through the chain is what makes a network's second
     * endpoint matter: the fullnode may be rate-limiting, down, or answering
     * with a broken certificate, and any of those should fall through to a
     * working host instead of failing the call. See [RpcEndpointChain].
     */
    fun endpointsOf(network: NetworkConfig): RpcEndpointChain = RpcEndpointChain.of(rpc, network)

    fun deriveAccount(seed: ByteArray, index: Int): DerivedAptosKey {
        val key = Slip10Ed25519Key.derive(seed, "m/54'/6'/0'/0'/$index'")
        val pubkey = Ed25519.publicKeyFromSeed(key.seed)
        return DerivedAptosKey(
            seed = key.seed,
            publicKey = pubkey,
            address = "0x" + Hex.encode(Hashes.sha3_256(pubkey + byteArrayOf(0x00))),
            path = "m/54'/6'/0'/0'/$index'"
        )
    }

    /**
     * Inverts [deriveAccount]: m/54'/6'/0'/0'/{index}'. Like Sui, the index
     * is the hardened FINAL level (the previous repository fix only taught it
     * to strip the trailing "'" — this replaces that with the real shape).
     */
    override fun derivationIndexOf(path: String): Int? {
        val levels = DerivationPathParsing.levels(path) ?: return null
        if (levels.size != 5) return null
        if (!DerivationPathParsing.isLevel(levels[0], 54)) return null
        if (!DerivationPathParsing.isLevel(levels[1], 6)) return null
        if (!DerivationPathParsing.isLevel(levels[2], 0)) return null
        if (!DerivationPathParsing.isLevel(levels[3], 0)) return null
        return DerivationPathParsing.levelValue(levels[4])
    }

    fun isValidAddress(address: String): Boolean {
        if (!address.startsWith("0x")) return false
        val bytes = Hex.decodeOrNull(address) ?: return false
        return bytes.size == 32
    }

    // ------------------------------------------------------------------
    // Wallet signMessage ("APTOS\nmessage: ...\nnonce: ..." — wallet standard)
    // ------------------------------------------------------------------

    data class SignMessageResult(
        val signatureHex: String,
        val fullMessage: String,
        val prefix: String = "APTOS"
    )

    fun signWalletMessage(
        seed32: ByteArray,
        message: String,
        nonce: String,
        includeAddress: Boolean = false,
        ourAddress: String? = null,
        includeApplication: Boolean = false,
        application: String? = null,
        includeChainId: Boolean = false,
        chainId: Long? = null
    ): SignMessageResult {
        val parts = mutableListOf("APTOS", "message: $message", "nonce: $nonce")
        if (includeAddress && ourAddress != null) parts += "address: $ourAddress"
        if (includeApplication && application != null) parts += "application: $application"
        if (includeChainId && chainId != null) parts += "chainId: $chainId"
        val fullMessage = parts.joinToString("\n")
        val signature = Ed25519.sign(seed32, fullMessage.toByteArray(Charsets.UTF_8))
        return SignMessageResult(
            signatureHex = "0x" + Hex.encode(signature),
            fullMessage = fullMessage
        )
    }

    // ------------------------------------------------------------------
    // Pre-serialized transaction signing (aptos:signTransaction)
    // ------------------------------------------------------------------

    /**
     * txBytes: base64 BCS RawTransaction. The sender is the first 32 bytes —
     * verified against our address before anything is signed. Signature
     * commits to sha3_256("APTOS::RawTransaction") || txBytes.
     */
    fun signSerializedTransaction(seed32: ByteArray, ourAddress: String, txBytesBase64: String): String {
        val raw = try {
            java.util.Base64.getDecoder().decode(txBytesBase64)
        } catch (_: IllegalArgumentException) {
            throw WalletException.InvalidParams("Invalid base64 transaction")
        }
        if (raw.size < 32) throw WalletException.InvalidParams("Transaction too short")
        val sender = "0x" + Hex.encode(raw.copyOfRange(0, 32))
        if (!sender.equals(ourAddress, ignoreCase = true)) {
            throw WalletException.InvalidParams("Transaction sender $sender is not this account")
        }
        val signingMessage = Hashes.sha3_256(RAW_TRANSACTION_SALT.toByteArray(Charsets.US_ASCII)) + raw
        val signature = Ed25519.sign(seed32, signingMessage)
        return "0x" + Hex.encode(signature)
    }

    // ------------------------------------------------------------------
    // signAndSubmitTransaction (legacy window.aptos API)
    // ------------------------------------------------------------------

    data class SubmittedTransaction(
        val hash: String,
        val sender: String,
        val sequenceNumber: String,
        val payloadSummary: String,
        val maxGasAmount: String,
        val gasUnitPrice: String
    )

    /**
     * Builds a UserTransaction around the dApp's payload, obtains the
     * fullnode's signing message, signs and submits. [payload] is the JSON
     * object the dApp sent (entry_function_payload / script_function_payload
     * / transaction_payload etc.).
     */
    suspend fun signAndSubmit(
        network: NetworkConfig,
        seed32: ByteArray,
        publicKey: ByteArray,
        ourAddress: String,
        payload: kotlinx.serialization.json.JsonObject,
        gasUnitPrice: Long? = null,
        maxGasAmount: Long = 200_000,
        expirationSecondsFromNow: Long = 600
    ): SubmittedTransaction {
        val chain = endpointsOf(network)
        if (chain.urls.isEmpty()) throw WalletException.InvalidParams("Network has no REST endpoint")
        val account = getAccount(chain, ourAddress)
            ?: throw WalletException.NetworkUnavailable("Account not found on chain")
        val sequence = account["sequence_number"]?.jsonPrimitive?.content ?: "0"
        val price = gasUnitPrice ?: estimateGasPrice(chain)
        val expiration = System.currentTimeMillis() / 1000 + expirationSecondsFromNow
        val request = buildJsonObject {
            put("sender", JsonPrimitive(ourAddress))
            put("sequence_number", JsonPrimitive(sequence))
            put("max_gas_amount", JsonPrimitive(maxGasAmount.toString()))
            put("gas_unit_price", JsonPrimitive(price.toString()))
            put("expiration_timestamp_secs", JsonPrimitive(expiration.toString()))
            put("payload", payload)
        }
        val signingMessageHex = postSigningMessage(chain, request)
        val signingMessage = Hex.decode(signingMessageHex)
        val signature = Ed25519.sign(seed32, signingMessage)
        val submission = buildJsonObject {
            put("signature", buildJsonObject {
                put("type", JsonPrimitive("ed25519_signature"))
                put("public_key", JsonPrimitive("0x" + Hex.encode(publicKey)))
                put("signature", JsonPrimitive("0x" + Hex.encode(signature)))
            })
            put("transaction", request)
        }
        val response = chain.firstWorking { url ->
            rpc.postJson("${url.trimEnd('/')}/transactions", submission)
        }.jsonObject
        val hash = response["hash"]?.jsonPrimitive?.content
            ?: throw WalletException.RpcError(-1, "Submission did not return a hash")
        return SubmittedTransaction(
            hash = hash,
            sender = ourAddress,
            sequenceNumber = sequence,
            payloadSummary = payload["function"]?.jsonPrimitive?.content ?: payload["type"]?.jsonPrimitive?.content ?: "transaction",
            maxGasAmount = maxGasAmount.toString(),
            gasUnitPrice = price.toString()
        )
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    suspend fun getAccount(chain: RpcEndpointChain, address: String): kotlinx.serialization.json.JsonObject? =
        try {
            chain.firstWorking { url -> rpc.getJson("${url.trimEnd('/')}/accounts/$address") }.jsonObject
        } catch (_: WalletException) {
            null
        }

    suspend fun getBalance(network: NetworkConfig, address: String): Long? {
        val chain = endpointsOf(network)
        if (chain.urls.isEmpty()) throw WalletException.InvalidParams("Network has no REST endpoint")
        return try {
            val resource = chain.firstWorking { url ->
                rpc.getJson("${url.trimEnd('/')}/accounts/$address/resource/0x1::coin::CoinStore<0x1::aptos_coin::AptosCoin>")
            }.jsonObject
            val amount = resource["data"]?.jsonObject?.get("coin")?.jsonObject?.get("value")?.jsonPrimitive?.content
            amount?.toLongOrNull()
        } catch (_: WalletException) {
            null
        }
    }

    suspend fun estimateGasPrice(chain: RpcEndpointChain): Long = try {
        val result = chain.firstWorking { url ->
            rpc.getJson("${url.trimEnd('/')}/estimate_gas_price")
        }.jsonObject
        result["gas_estimate"]?.jsonPrimitive?.content?.toLongOrNull() ?: 100
    } catch (_: WalletException) {
        100
    }

    private suspend fun postSigningMessage(
        chain: RpcEndpointChain,
        request: kotlinx.serialization.json.JsonObject
    ): String {
        val response = chain.firstWorking { url ->
            rpc.postJson("${url.trimEnd('/')}/transactions/signing_message", request)
        }.jsonObject
        return response["message"]?.jsonPrimitive?.content
            ?: throw WalletException.RpcError(-1, "Fullnode did not return a signing message")
    }

    data class DerivedAptosKey(
        val seed: ByteArray,
        val publicKey: ByteArray,
        val address: String,
        val path: String
    )

    companion object {
        const val RAW_TRANSACTION_SALT = "APTOS::RawTransaction"

        val MAINNET = NetworkConfig(
            id = "APTOS:mainnet",
            chainType = ChainType.APTOS,
            chainId = "mainnet",
            name = "Aptos Mainnet",
            rpcUrls = listOf("https://fullnode.mainnet.aptoslabs.com/v1"),
            nativeSymbol = "APT",
            nativeDecimals = 8,
            explorerUrl = "https://explorer.aptoslabs.com",
            isTestnet = false
        )
        val TESTNET = NetworkConfig(
            id = "APTOS:testnet",
            chainType = ChainType.APTOS,
            chainId = "testnet",
            name = "Aptos Testnet",
            rpcUrls = listOf("https://fullnode.testnet.aptoslabs.com/v1"),
            nativeSymbol = "APT",
            nativeDecimals = 8,
            explorerUrl = "https://explorer.aptoslabs.com",
            isTestnet = true
        )
        val DEVNET = NetworkConfig(
            id = "APTOS:devnet",
            chainType = ChainType.APTOS,
            chainId = "devnet",
            name = "Aptos Devnet",
            rpcUrls = listOf("https://fullnode.devnet.aptoslabs.com/v1"),
            nativeSymbol = "APT",
            nativeDecimals = 8,
            explorerUrl = "https://explorer.aptoslabs.com",
            isTestnet = true
        )

        fun defaultNetworks(): List<NetworkConfig> = listOf(MAINNET, TESTNET, DEVNET)
    }
}
