package com.roombrowser.domain.wallet.chains.octra

import com.roombrowser.domain.wallet.chains.DerivationPathIndex
import com.roombrowser.domain.wallet.crypto.Base58
import com.roombrowser.domain.wallet.crypto.Ed25519
import com.roombrowser.domain.wallet.crypto.Hashes
import com.roombrowser.domain.wallet.model.BroadcastResult
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.domain.wallet.model.NetworkConfig
import com.roombrowser.domain.wallet.model.WalletException
import com.roombrowser.domain.wallet.rpc.JsonRpcClient
import com.roombrowser.domain.wallet.rpc.RpcEndpointChain
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * Octra adapter.
 *
 * Keys: ed25519 over the BIP-39 seed, but with **no BIP-32 path**. Octra
 * derives its account key with a single HMAC —
 * `priv = HMAC-SHA512("Octra seed", bip39Seed)[0..32]` — so the account is not
 * at a BIP-44 position and there is nothing to walk. [deriveAccount]
 * deliberately refuses every index but 0: the reference defines further
 * indices under two mutually incompatible schemes (`hdVersion` 1 and 2) and
 * nothing establishes which the live network uses, so a second account would
 * be a key nobody can check. Refusing is the only honest answer.
 *
 * Addresses: `"oct"` + Base58(SHA-256(pubkey)), left-padded with `1` to 44
 * characters (47 total). There is **no checksum**, so a mistyped character
 * inside the alphabet is undetectable — [isValidAddress] checks shape only,
 * exactly as the reference validator does.
 *
 * Transactions: a plain transfer is signed over its canonical JSON (see
 * [OctraCanonicalJson]) as raw UTF-8 — there is no hash step — and the
 * detached 64-byte ed25519 signature rides along base64-encoded beside the
 * public key. The nonce is the node's `pending_nonce` (else `nonce`) plus one.
 */
class OctraAdapter(private val rpc: JsonRpcClient = JsonRpcClient()) : DerivationPathIndex {

    fun chainType(): ChainType = ChainType.OCTRA

    fun endpointsOf(network: NetworkConfig): RpcEndpointChain = RpcEndpointChain.of(rpc, network)

    /**
     * Derives the account key for [index].
     *
     * Only index 0 exists; see the class KDoc for why. Any other index throws
     * rather than silently returning a key derived under a scheme the chain
     * may not implement.
     */
    fun deriveAccount(seed: ByteArray, index: Int): DerivedOctraKey {
        if (index != 0) {
            throw WalletException.UnsupportedMethod(
                "Octra supports a single account per wallet"
            )
        }
        return accountFromSeed(privateSeedFromBip39(seed))
    }

    fun accountFromSeed(privateSeed32: ByteArray): DerivedOctraKey {
        val publicKey = Ed25519.publicKeyFromSeed(privateSeed32)
        return DerivedOctraKey(
            seed = privateSeed32,
            publicKey = publicKey,
            address = addressFromPublicKey(publicKey),
            path = PATH
        )
    }

    /** Inverts [deriveAccount]: the one path this adapter emits. */
    override fun derivationIndexOf(path: String): Int? = if (path == PATH) 0 else null

    /** Shape check only — Octra addresses carry no checksum to verify. */
    fun isValidAddress(address: String): Boolean {
        if (address.length != ADDRESS_LENGTH) return false
        if (!address.startsWith(ADDRESS_PREFIX)) return false
        return address.substring(ADDRESS_PREFIX.length).all { it in BASE58_ALPHABET }
    }

    fun addressFromPublicKey(publicKey: ByteArray): String {
        val body = Base58.encode(Hashes.sha256(publicKey)).padStart(BODY_LENGTH, '1')
        return ADDRESS_PREFIX + body
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /** The account's spendable balance in raw units (1 OCT = 1_000_000). */
    suspend fun getBalance(network: NetworkConfig, address: String): Long? {
        val chain = endpointsOf(network)
        if (chain.urls.isEmpty()) return null
        val result = chain.callObject("octra_balance", listOf(JsonPrimitive(address)))
        return rawAmount(result["raw"])
    }

    // ------------------------------------------------------------------
    // Send (dashboard)
    // ------------------------------------------------------------------

    /**
     * Signs and submits a plain transfer.
     *
     * [amountRaw] is in raw units. The fee is whatever the node recommends,
     * falling back to [DEFAULT_FEE_RAW] when it will not say — the value the
     * reference implementation's own transaction vector uses.
     */
    suspend fun sendNative(
        network: NetworkConfig,
        seed32: ByteArray,
        fromAddress: String,
        toAddress: String,
        amountRaw: Long,
        timestamp: Long = System.currentTimeMillis() / 1000
    ): BroadcastResult {
        val chain = endpointsOf(network)
        if (chain.urls.isEmpty()) return BroadcastResult.Error("Network has no RPC endpoint")
        if (!isValidAddress(toAddress)) {
            return BroadcastResult.Error("Invalid Octra recipient address")
        }
        val account = try {
            chain.callObject("octra_balance", listOf(JsonPrimitive(fromAddress)))
        } catch (e: WalletException) {
            return BroadcastResult.Error(e.message ?: "Could not read the account state")
        }
        val nonce = accountNonce(account)
            ?: return BroadcastResult.Error("Could not read the account nonce")
        val signed = try {
            signTransfer(
                seed32 = seed32,
                from = fromAddress,
                to = toAddress,
                amountRaw = amountRaw,
                nonce = nonce + 1,
                feeRaw = recommendedFee(chain),
                timestamp = timestamp
            )
        } catch (e: WalletException) {
            return BroadcastResult.Error(e.message ?: "Signing failed")
        }
        return broadcast(chain, signed)
    }

    /** Builds the signed transaction object for a plain transfer. */
    fun signTransfer(
        seed32: ByteArray,
        from: String,
        to: String,
        amountRaw: Long,
        nonce: Long,
        feeRaw: String,
        timestamp: Long
    ): JsonObject {
        val amount = amountRaw.toString()
        val canonical = OctraCanonicalJson.transfer(
            from = from,
            to = to,
            amountRaw = amount,
            nonce = nonce,
            feeRaw = feeRaw,
            timestamp = timestamp
        )
        val signature = Ed25519.sign(seed32, canonical.toByteArray(Charsets.UTF_8))
        val publicKey = Ed25519.publicKeyFromSeed(seed32)
        val encoder = Base64.getEncoder()
        return buildJsonObject {
            put("from", JsonPrimitive(from))
            put("to_", JsonPrimitive(to))
            put("amount", JsonPrimitive(amount))
            put("nonce", JsonPrimitive(nonce))
            put("ou", JsonPrimitive(feeRaw))
            put("timestamp", JsonPrimitive(timestamp))
            put("op_type", JsonPrimitive(OctraCanonicalJson.OP_STANDARD))
            put("signature", JsonPrimitive(encoder.encodeToString(signature)))
            put("public_key", JsonPrimitive(encoder.encodeToString(publicKey)))
        }
    }

    suspend fun broadcast(chain: RpcEndpointChain, signedTx: JsonObject): BroadcastResult = try {
        val result = chain.call("octra_submit", listOf(signedTx))
        val hash = when (result) {
            is JsonPrimitive -> result.contentOrNull
            is JsonObject -> result["tx_hash"]?.jsonPrimitive?.contentOrNull
            else -> null
        }
        if (hash.isNullOrBlank()) {
            BroadcastResult.Error("The node did not return a transaction hash")
        } else {
            BroadcastResult.Ok(hash)
        }
    } catch (e: WalletException) {
        BroadcastResult.Error(e.message ?: "Broadcast failed")
    }

    /**
     * The fee [sendNative] will attach, for the send sheet to show. Every
     * transfer pays it, so a caller that cannot state it must not pretend the
     * send is free.
     */
    suspend fun recommendedFeeOf(network: NetworkConfig): String {
        val chain = endpointsOf(network)
        if (chain.urls.isEmpty()) return DEFAULT_FEE_RAW
        return recommendedFee(chain)
    }

    /**
     * The node's suggested fee, or [DEFAULT_FEE_RAW] when it does not answer
     * with a plain integer. The reply shape is not documented, so this accepts
     * a bare value or an object carrying it under any of the usual names and
     * ignores anything that is not a non-negative integer.
     */
    private suspend fun recommendedFee(chain: RpcEndpointChain): String {
        val suggested = try {
            val result = chain.call("octra_recommendedFee", emptyList())
            when (result) {
                is JsonPrimitive -> result.contentOrNull
                is JsonObject ->
                    (result["fee"] ?: result["ou"] ?: result["recommended_fee"])
                        ?.jsonPrimitive?.contentOrNull
                else -> null
            }
        } catch (_: WalletException) {
            null
        } ?: return DEFAULT_FEE_RAW
        return suggested.trim().takeIf { it.matches(RAW_AMOUNT) } ?: DEFAULT_FEE_RAW
    }

    /** The node's next nonce, or null when neither field is a usable number. */
    private fun accountNonce(account: JsonObject): Long? =
        rawLong(account["pending_nonce"]) ?: rawLong(account["nonce"])

    private fun rawLong(element: JsonElement?): Long? =
        element?.jsonPrimitive?.contentOrNull?.toLongOrNull()

    private fun rawAmount(element: JsonElement?): Long? =
        element?.jsonPrimitive?.contentOrNull?.toLongOrNull()

    data class DerivedOctraKey(
        val seed: ByteArray,
        val publicKey: ByteArray,
        val address: String,
        val path: String
    )

    companion object {
        /** The single derivation identity Octra has; not a BIP-32 path. */
        const val PATH = "octra/0"

        const val ADDRESS_PREFIX = "oct"
        const val BODY_LENGTH = 44
        const val ADDRESS_LENGTH = ADDRESS_PREFIX.length + BODY_LENGTH

        /** Used when the node will not suggest one; the reference's own vector value. */
        const val DEFAULT_FEE_RAW = "200000"

        private val RAW_AMOUNT = Regex("^\\d+$")

        private const val BASE58_ALPHABET =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

        private val HD_KEY = "Octra seed".toByteArray(Charsets.US_ASCII)

        /**
         * The Octra master key: the first 32 bytes of
         * `HMAC-SHA512("Octra seed", bip39Seed)`. The trailing 32 bytes are the
         * chain code and are unused by the account key itself.
         */
        fun privateSeedFromBip39(bip39Seed: ByteArray): ByteArray =
            Hashes.hmacSha512(HD_KEY, bip39Seed).copyOfRange(0, 32)

        /**
         * Presets. The community node the reference SDK hardcodes this against
         * is served over plain HTTP, which a preset cannot be (every preset
         * must be HTTPS) — so these point at the named hosts and a user who
         * wants the bare IP adds it as a custom network.
         */
        val MAINNET = NetworkConfig(
            id = "OCTRA:mainnet",
            chainType = ChainType.OCTRA,
            chainId = "mainnet",
            name = "Octra Mainnet",
            rpcUrls = listOf("https://octra.network/rpc"),
            nativeSymbol = "OCT",
            nativeDecimals = 6,
            explorerUrl = "https://octrascan.io",
            isTestnet = false
        )
        val DEVNET = NetworkConfig(
            id = "OCTRA:devnet",
            chainType = ChainType.OCTRA,
            chainId = "devnet",
            name = "Octra Devnet",
            rpcUrls = listOf("https://devnet.octrascan.io/rpc"),
            nativeSymbol = "OCT",
            nativeDecimals = 6,
            explorerUrl = "https://devnet.octrascan.io",
            isTestnet = true
        )

        fun defaultNetworks(): List<NetworkConfig> = listOf(MAINNET, DEVNET)
    }
}
