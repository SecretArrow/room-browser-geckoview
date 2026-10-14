package com.roombrowser.domain.wallet.chains.solana

import com.roombrowser.domain.wallet.bcs.BcsReader
import com.roombrowser.domain.wallet.bcs.BcsWriter
import com.roombrowser.domain.wallet.chains.DerivationPathIndex
import com.roombrowser.domain.wallet.chains.DerivationPathParsing
import com.roombrowser.domain.wallet.crypto.Base58
import com.roombrowser.domain.wallet.crypto.Ed25519
import com.roombrowser.domain.wallet.crypto.Slip10Ed25519Key
import com.roombrowser.domain.wallet.model.BroadcastResult
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
 * Solana adapter.
 *
 * Keys: SLIP-0010 ed25519 at m/44'/501'/i'/0' (the Phantom/Backpack standard).
 * Addresses are base58-encoded ed25519 public keys; signatures sign the raw
 * message bytes (Solana has no domain separator for personal messages —
 * Phantom signs exactly the bytes it is given).
 *
 * Transactions arrive from dApps as base64-serialized wire transactions; the
 * signature commits to the message bytes following the signature section.
 */
class SolanaAdapter(private val rpc: JsonRpcClient = JsonRpcClient()) : DerivationPathIndex {

    fun chainType(): ChainType = ChainType.SOLANA

    /**
     * This network's endpoints as a failover chain.
     *
     * Every RPC call in this adapter goes through one of these instead of
     * `network.rpcUrls.firstOrNull()`. Solana's bundled networks list a single
     * public endpoint today, but a user's custom network may list several, and
     * a public host that is rate-limiting or down should not make the network
     * unusable while a working endpoint sits beside it in the same list. See
     * [RpcEndpointChain].
     */
    fun endpointsOf(network: NetworkConfig): RpcEndpointChain = RpcEndpointChain.of(rpc, network)

    fun deriveAccount(seed: ByteArray, index: Int): DerivedSolanaKey {
        val key = Slip10Ed25519Key.derive(seed, "m/44'/501'/$index'/0'")
        val pubkey = Ed25519.publicKeyFromSeed(key.seed)
        return DerivedSolanaKey(
            seed = key.seed,
            publicKey = pubkey,
            address = Base58.encode(pubkey),
            path = "m/44'/501'/$index'/0'"
        )
    }

    /**
     * Inverts [deriveAccount]: m/44'/501'/{index}'/0'.
     *
     * The index is the ACCOUNT level — the third one — while the final level
     * is the fixed hardened change `0'`. This is the shape that broke the
     * repository's old "read the last level" rule: a second account counted,
     * but the counter could never pass 1, so a third account was derived at
     * an address that already existed.
     */
    override fun derivationIndexOf(path: String): Int? {
        val levels = DerivationPathParsing.levels(path) ?: return null
        if (levels.size != 4) return null
        if (!DerivationPathParsing.isLevel(levels[0], 44)) return null
        if (!DerivationPathParsing.isLevel(levels[1], 501)) return null
        if (!DerivationPathParsing.isLevel(levels[3], 0)) return null
        return DerivationPathParsing.levelValue(levels[2])
    }

    fun isValidAddress(address: String): Boolean {
        val bytes = Base58.decodeOrNull(address) ?: return false
        return bytes.size == 32
    }

    // ------------------------------------------------------------------
    // Message signing (window.solana.signMessage)
    // ------------------------------------------------------------------

    fun signMessage(seed32: ByteArray, message: ByteArray): String =
        Base58.encode(Ed25519.sign(seed32, message))

    // ------------------------------------------------------------------
    // Transaction signing (signTransaction / signAndSendTransaction)
    // ------------------------------------------------------------------

    data class ParsedTransaction(
        val messageBytes: ByteArray,
        val staticSigners: List<String>,
        val requiredSignatureCount: Int
    )

    /** Parses a base64 wire transaction; validates signer membership. */
    fun parseTransaction(base64Tx: String): ParsedTransaction {
        val raw = try {
            java.util.Base64.getDecoder().decode(base64Tx)
        } catch (_: IllegalArgumentException) {
            throw WalletException.InvalidParams("Invalid base64 transaction")
        }
        val reader = BcsReader(raw)
        val signatureCount = reader.readUleb128().toInt()
        if (signatureCount < 1 || signatureCount > 64) {
            throw WalletException.InvalidParams("Unreasonable signature count $signatureCount")
        }
        var offset = 0
        // Skip signature section (each signature is exactly 64 bytes).
        var i = 0
        while (i < signatureCount) {
            reader.skip(64)
            i++
        }
        offset = raw.size - reader.remaining()
        val messageBytes = raw.copyOfRange(offset, raw.size)
        val message = BcsReader(messageBytes)
        // Versioned messages: first byte >= 0x80 → versioned (v0 = 0x80).
        if ((messageBytes[0].toInt() and 0xff) >= 0x80) message.skip(1)
        val numRequiredSignatures = message.readU8()
        val numReadonlySigned = message.readU8()
        val numReadonlyUnsigned = message.readU8()
        val keyCount = message.readUleb128().toInt()
        val signers = mutableListOf<String>()
        for (k in 0 until keyCount) {
            val keyBytes = message.readFixed(32)
            if (k < numRequiredSignatures) signers += Base58.encode(keyBytes)
        }
        return ParsedTransaction(messageBytes, signers, numRequiredSignatures)
    }

    /**
     * Signs a dApp-provided transaction. Returns the base64 transaction with
     * our signature placed at the signer's slot (Phantom semantics: the
     * signature array position must match the signer's account-key index
     * among required signers).
     */
    fun signTransaction(seed32: ByteArray, ourAddress: String, base64Tx: String): String {
        val raw = java.util.Base64.getDecoder().decode(base64Tx)
        val parsed = parseTransaction(base64Tx)
        val signerIndex = parsed.staticSigners.indexOf(ourAddress)
        if (signerIndex < 0) {
            throw WalletException.InvalidParams("Transaction does not list this account as a signer")
        }
        val signature = Ed25519.sign(seed32, parsed.messageBytes)
        // Signature count <= 64 encodes as a single uleb byte.
        val firstByte = raw[0].toInt() and 0xff
        require(firstByte and 0x80 == 0) { "Signature count varint too long" }
        val out = raw.copyOf()
        val slotStart = 1 + 64 * signerIndex
        if (slotStart + 64 > out.size) {
            throw WalletException.InvalidParams("Signature slot out of bounds")
        }
        System.arraycopy(signature, 0, out, slotStart, 64)
        return java.util.Base64.getEncoder().encodeToString(out)
    }

    // ------------------------------------------------------------------
    // Send (dashboard)
    // ------------------------------------------------------------------

    suspend fun sendNative(
        network: NetworkConfig,
        seed32: ByteArray,
        fromAddress: String,
        toAddress: String,
        lamports: Long
    ): BroadcastResult {
        val chain = endpointsOf(network)
        if (chain.urls.isEmpty()) return BroadcastResult.Error("Network has no RPC endpoint")
        val blockhash = getLatestBlockhash(chain)
            ?: return BroadcastResult.Error("Could not fetch a recent blockhash")
        val fromKey = Base58.decode(fromAddress)
        val toKey = Base58.decode(toAddress)
        val instruction = BcsWriter()
            .writeU8(2) // SystemInstruction::Transfer
            .writeU64(lamports)
            .bytes()
        // Message: header (1 required sig, 0 readonly signed, 1 readonly
        // unsigned), keys [from, to], blockhash, 1 instruction.
        val message = BcsWriter()
            .writeU8(1).writeU8(0).writeU8(1)
            .writeUleb128(2).writeRaw(fromKey).writeRaw(toKey)
            .writeRaw(blockhash)
            .writeUleb128(1)
            .writeU8(2) // program id index = 2 (system program)
            .writeUleb128(2).writeU8(0).writeU8(1) // account indices: from, to
            .writeBytes(instruction)
            .bytes()
        val signature = Ed25519.sign(seed32, message)
        val tx = BcsWriter()
            .writeUleb128(1).writeRaw(signature)
            .writeRaw(message)
            .bytes()
        return broadcast(chain, java.util.Base64.getEncoder().encodeToString(tx))
    }

    suspend fun broadcast(chain: RpcEndpointChain, base64Tx: String): BroadcastResult = try {
        val config = buildJsonObject { put("encoding", JsonPrimitive("base64")) }
        val result = chain.call("sendTransaction", listOf(JsonPrimitive(base64Tx), config))
        BroadcastResult.Ok(result.jsonPrimitive.content)
    } catch (e: WalletException) {
        BroadcastResult.Error(e.message ?: "Broadcast failed")
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    suspend fun getBalance(network: NetworkConfig, address: String): Long? {
        val chain = endpointsOf(network)
        if (chain.urls.isEmpty()) return null
        val config = buildJsonObject { put("commitment", JsonPrimitive("confirmed")) }
        val result = chain.callObject("getBalance", listOf(JsonPrimitive(address), config))
        return result["value"]?.jsonPrimitive?.content?.toLongOrNull()
    }

    suspend fun getLatestBlockhash(chain: RpcEndpointChain): ByteArray? = try {
        val config = buildJsonObject { put("commitment", JsonPrimitive("confirmed")) }
        val result = chain.callObject("getLatestBlockhash", listOf(config))
        val blockhash = result["value"]?.jsonObject?.get("blockhash")?.jsonPrimitive?.content ?: return null
        Base58.decode(blockhash)
    } catch (_: WalletException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    data class DerivedSolanaKey(
        val seed: ByteArray,
        val publicKey: ByteArray,
        val address: String,
        val path: String
    )

    companion object {
        const val SYSTEM_PROGRAM = "11111111111111111111111111111111"

        val MAINNET = NetworkConfig(
            id = "SOLANA:mainnet-beta",
            chainType = ChainType.SOLANA,
            chainId = "mainnet-beta",
            name = "Solana Mainnet",
            rpcUrls = listOf("https://api.mainnet-beta.solana.com"),
            nativeSymbol = "SOL",
            nativeDecimals = 9,
            explorerUrl = "https://solscan.io",
            isTestnet = false
        )
        val DEVNET = NetworkConfig(
            id = "SOLANA:devnet",
            chainType = ChainType.SOLANA,
            chainId = "devnet",
            name = "Solana Devnet",
            rpcUrls = listOf("https://api.devnet.solana.com"),
            nativeSymbol = "SOL",
            nativeDecimals = 9,
            explorerUrl = "https://solscan.io",
            isTestnet = true
        )
        val TESTNET = NetworkConfig(
            id = "SOLANA:testnet",
            chainType = ChainType.SOLANA,
            chainId = "testnet",
            name = "Solana Testnet",
            rpcUrls = listOf("https://api.testnet.solana.com"),
            nativeSymbol = "SOL",
            nativeDecimals = 9,
            explorerUrl = "https://solscan.io",
            isTestnet = true
        )

        fun defaultNetworks(): List<NetworkConfig> = listOf(MAINNET, DEVNET, TESTNET)
    }
}
