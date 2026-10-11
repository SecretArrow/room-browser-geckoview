package com.roombrowser.domain.wallet.chains.tron

import com.roombrowser.domain.wallet.chains.DerivationPathIndex
import com.roombrowser.domain.wallet.chains.DerivationPathParsing
import com.roombrowser.domain.wallet.crypto.Base58
import com.roombrowser.domain.wallet.crypto.Bip32PrivateKey
import com.roombrowser.domain.wallet.crypto.Hashes
import com.roombrowser.domain.wallet.crypto.Hex
import com.roombrowser.domain.wallet.crypto.Signing
import com.roombrowser.domain.wallet.model.BroadcastResult
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.domain.wallet.model.NetworkConfig
import com.roombrowser.domain.wallet.model.WalletException
import com.roombrowser.domain.wallet.rpc.JsonRpcClient
import com.roombrowser.domain.wallet.rpc.RpcEndpointChain
import com.roombrowser.domain.wallet.wire.ProtoReader
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigInteger

/**
 * TRON adapter — TRX + TRC-20.
 *
 * Keys: BIP44 at m/44'/195'/0'/0/i (secp256k1). Address = base58check(0x41
 * || keccak256(uncompressed pubkey)[12..32]) — the same scheme TronWeb uses.
 *
 * Transaction signing: txID = sha256(raw_data serialized protobuf bytes);
 * the signature is ECDSA over txID (65 bytes, r||s||v). The dApp supplies
 * `raw_data_hex`; when a transaction must be built locally (dashboard sends)
 * the fullnode's /wallet/createtransaction and /wallet/triggersmartcontract
 * endpoints produce it, so the bytes always match the node's own
 * serialization.
 *
 * Message signing follows TronWeb: signMessageV2 signs
 * keccak256("\x19TRON Signed Message:\n" + len + data) (verified against the
 * tronweb package source).
 */
class TronAdapter(private val rpc: JsonRpcClient = JsonRpcClient()) : DerivationPathIndex {

    fun chainType(): ChainType = ChainType.TRON

    /**
     * This network's endpoints as a failover chain.
     *
     * Every fullnode call in this adapter goes through one of these instead
     * of `network.rpcUrls.firstOrNull()`, which is what used to make the
     * second url in a network's list dead weight: a network whose primary
     * fullnode had died could not be used at all, even with a working node
     * recorded right beside it. See [RpcEndpointChain].
     */
    fun endpointsOf(network: NetworkConfig): RpcEndpointChain = RpcEndpointChain.of(rpc, network)

    fun deriveAccount(seed: ByteArray, index: Int): DerivedTronKey {
        val path = "m/44'/195'/0'/0/$index"
        val key = Bip32PrivateKey.derive(seed, path)
        return DerivedTronKey(
            privateKey = key.key,
            address = addressFromPrivateKey(key.key),
            path = path
        )
    }

    /**
     * Inverts [deriveAccount]: m/44'/195'/0'/0/{index}. TRON uses the plain
     * BIP44 shape, so the index is the unhardened FINAL level.
     */
    override fun derivationIndexOf(path: String): Int? {
        val levels = DerivationPathParsing.levels(path) ?: return null
        if (levels.size != 5) return null
        if (!DerivationPathParsing.isLevel(levels[0], 44)) return null
        if (!DerivationPathParsing.isLevel(levels[1], 195)) return null
        if (!DerivationPathParsing.isLevel(levels[2], 0)) return null
        if (!DerivationPathParsing.isLevel(levels[3], 0)) return null
        return DerivationPathParsing.levelValue(levels[4])
    }

    fun addressFromPrivateKey(privateKey: BigInteger): String {
        val pub64 = Bip32PrivateKey.publicKeyPoint(privateKey).let { p ->
            p.affineXCoord.encoded + p.affineYCoord.encoded
        }
        val hash = Hashes.keccak256(pub64)
        return base58Check(byteArrayOf(0x41) + hash.copyOfRange(12, 32))
    }

    fun base58Check(payload: ByteArray): String {
        val checksum = Hashes.sha256d(payload).copyOfRange(0, 4)
        return Base58.encode(payload + checksum)
    }

    /** Validates a base58 TRON address (T..., 21-byte payload + 4-byte checksum). */
    fun isValidAddress(address: String): Boolean {
        val bytes = Base58.decodeOrNull(address) ?: return false
        if (bytes.size != 25 || bytes[0] != 0x41.toByte()) return false
        val checksum = Hashes.sha256d(bytes.copyOfRange(0, 21)).copyOfRange(0, 4)
        return checksum.contentEquals(bytes.copyOfRange(21, 25))
    }

    /** Base58 T-address → hex form (TronWeb's toHex): "41" || 20 bytes, no checksum. */
    fun addressToHex(address: String): String {
        val bytes = Base58.decodeOrNull(address) ?: throw WalletException.InvalidParams("Invalid TRON address")
        if (bytes.size != 25) throw WalletException.InvalidParams("Invalid TRON address")
        val checksum = Hashes.sha256d(bytes.copyOfRange(0, 21)).copyOfRange(0, 4)
        if (!checksum.contentEquals(bytes.copyOfRange(21, 25))) {
            throw WalletException.InvalidParams("Invalid TRON address")
        }
        return Hex.encode(bytes.copyOfRange(0, 21))
    }

    /** Hex T-address → base58 (TronWeb's fromHex). Accepts the 21-byte
     *  payload ("41…"), an EVM-style 20-byte form (prefixed with 0x41), and
     *  the 25-byte checksummed form — never re-checksumming an existing checksum. */
    fun hexToAddress(hex: String): String {
        val clean = hex.removePrefix("0x").removePrefix("0X")
        val bytes = Hex.decodeOrNull(clean) ?: throw WalletException.InvalidParams("Invalid hex address")
        val payload = when (bytes.size) {
            20 -> byteArrayOf(0x41) + bytes
            21 -> bytes
            25 -> {
                val checksum = Hashes.sha256d(bytes.copyOfRange(0, 21)).copyOfRange(0, 4)
                if (!checksum.contentEquals(bytes.copyOfRange(21, 25))) {
                    throw WalletException.InvalidParams("Invalid TRON address checksum")
                }
                bytes.copyOfRange(0, 21)
            }
            else -> throw WalletException.InvalidParams("Invalid hex address")
        }
        return base58Check(payload)
    }

    // ------------------------------------------------------------------
    // Message signing (TronLink signMessageV2)
    // ------------------------------------------------------------------

    /** "\x19TRON Signed Message:\n" + len + data, keccak256, ECDSA → 0x hex. */
    fun signMessageV2(privateKey: BigInteger, message: ByteArray): String {
        val prefix = "\u0019TRON Signed Message:\n".toByteArray(Charsets.UTF_8)
        val digest = Hashes.keccak256(prefix + message.size.toString().toByteArray(Charsets.US_ASCII) + message)
        val sig = Signing.secp256k1SignDigest(privateKey, digest)
        return "0x" + Hex.encode(sig.r + sig.s + byteArrayOf(sig.v[0]))
    }

    // ------------------------------------------------------------------
    // Transaction signing (signTransaction)
    // ------------------------------------------------------------------

    data class ParsedTronTransaction(
        val txId: String,
        val ownerAddress: String?,
        val toAddress: String?,
        val amountSun: Long?,
        val contractType: Long?,
        val contractDataHex: String?,
        val understood: Boolean
    )

    /**
     * Signs a dApp transaction object (TronLink format: {raw_data,
     * raw_data_hex, visible}). Returns the same object with the signature
     * appended, plus the parsed summary for the confirmation screen.
     */
    fun signTransaction(
        privateKey: BigInteger,
        ourAddress: String,
        tx: JsonObject
    ): Pair<JsonObject, ParsedTronTransaction> {
        val rawHex = tx["raw_data_hex"]?.jsonPrimitive?.content
            ?: throw WalletException.InvalidParams("Transaction has no raw_data_hex")
        val rawBytes = Hex.decodeOrNull(rawHex)
            ?: throw WalletException.InvalidParams("Invalid raw_data_hex")
        val txId = Hex.encode(Hashes.sha256(rawBytes))
        val parsed = parseRaw(rawBytes)
        if (parsed.ownerAddress != null && !parsed.ownerAddress.equals(ourAddress, ignoreCase = true)) {
            throw WalletException.InvalidParams("Transaction owner ${parsed.ownerAddress} is not this account")
        }
        val digest = Hashes.sha256(rawBytes)
        val sig = Signing.secp256k1SignDigest(privateKey, digest)
        val signatureHex = Hex.encode(sig.r + sig.s + byteArrayOf(sig.v[0]))
        val signatures = (tx["signature"] as? JsonArray ?: JsonArray(emptyList())).let {
            JsonArray(it + JsonPrimitive(signatureHex))
        }
        val signedTx = buildJsonObject {
            tx.forEach { (key, value) -> if (key != "signature") put(key, value) }
            put("signature", signatures)
        }
        return signedTx to parsed.copy(txId = txId)
    }

    /** Best-effort parse of raw_data for display. */
    fun parseRaw(rawBytes: ByteArray): ParsedTronTransaction = try {
            val rawFields = ProtoReader(rawBytes).all()
            var owner: String? = null
            var to: String? = null
            var amount: Long? = null
            var type: Long? = null
            var dataHex: String? = null
            var understood = false
            rawFields.filter { it.number == 11 && it.wireType == 2 }.forEach { contractField ->
                val contract = contractField.asReader().all()
                type = contract.firstOrNull { it.number == 1 }?.varint
                val parameter = contract.firstOrNull { it.number == 2 }?.bytes
                if (parameter != null) {
                    val valueFields = ProtoReader(parameter).all()
                    val inner = valueFields.firstOrNull { it.number == 2 }?.bytes
                    if (inner != null) {
                        val fields = ProtoReader(inner).all()
                        when (type) {
                            1L -> { // TransferContract
                                owner = fields.firstOrNull { it.number == 1 }?.let { hexToAddressSafe(Hex.encode(it.bytes)) }
                                to = fields.firstOrNull { it.number == 2 }?.let { hexToAddressSafe(Hex.encode(it.bytes)) }
                                amount = fields.firstOrNull { it.number == 3 }?.varint
                                understood = true
                            }
                            31L -> { // TriggerSmartContract
                                owner = fields.firstOrNull { it.number == 1 }?.let { hexToAddressSafe(Hex.encode(it.bytes)) }
                                to = fields.firstOrNull { it.number == 2 }?.let { hexToAddressSafe(Hex.encode(it.bytes)) }
                                dataHex = fields.firstOrNull { it.number == 4 }?.let { "0x" + Hex.encode(it.bytes) }
                                understood = true
                            }
                            else -> understood = false
                        }
                    }
                }
            }
            ParsedTronTransaction("", owner, to, amount, type, dataHex, understood)
        } catch (_: IllegalArgumentException) {
            ParsedTronTransaction("", null, null, null, null, null, understood = false)
        }

    private fun hexToAddressSafe(hex: String): String? = try {
        hexToAddress(hex)
    } catch (_: WalletException) {
        null
    }

    // ------------------------------------------------------------------
    // Dashboard sends
    // ------------------------------------------------------------------

    /** TRX transfer: fullnode builds the unsigned tx, we sign + broadcast. */
    suspend fun sendTrx(
        network: NetworkConfig,
        privateKey: BigInteger,
        ourAddress: String,
        toAddress: String,
        amountSun: Long
    ): BroadcastResult {
        val chain = endpointsOf(network)
        if (chain.urls.isEmpty()) return BroadcastResult.Error("Network has no RPC endpoint")
        val request = buildJsonObject {
            put("owner_address", JsonPrimitive(addressToHex(ourAddress)))
            put("to_address", JsonPrimitive(addressToHex(toAddress)))
            put("amount", JsonPrimitive(amountSun))
            put("visible", JsonPrimitive(false))
        }
        val unsigned = try {
            chain.firstWorking { url -> rpc.postJson("$url/wallet/createtransaction", request) }.jsonObject
        } catch (e: WalletException) {
            return BroadcastResult.Error("Build transfer failed: ${e.message}")
        }
        return signAndBroadcast(network, privateKey, ourAddress, unsigned)
    }

    /** TRC-20 transfer via triggerSmartContract. */
    suspend fun sendTrc20(
        network: NetworkConfig,
        privateKey: BigInteger,
        ourAddress: String,
        contractAddress: String,
        toAddress: String,
        amount: BigInteger,
        decimals: Int
    ): BroadcastResult {
        val chain = endpointsOf(network)
        if (chain.urls.isEmpty()) return BroadcastResult.Error("Network has no RPC endpoint")
        val selector = Hex.encode(Hashes.keccak256("transfer(address,uint256)".toByteArray(Charsets.US_ASCII))).substring(0, 8)
        val data = Hex.decode(
            selector +
                addressToHex(toAddress).removePrefix("0x").padStart(64, '0') +
                amount.toString(16).padStart(64, '0')
        )
        val request = buildJsonObject {
            put("owner_address", JsonPrimitive(addressToHex(ourAddress)))
            put("contract_address", JsonPrimitive(addressToHex(contractAddress)))
            put("function_selector", JsonPrimitive("transfer(address,uint256)"))
            put("parameter", JsonPrimitive(""))
            put("data", JsonPrimitive(Hex.encode(data)))
            put("fee_limit", JsonPrimitive(100_000_000L)) // 100 TRX
            put("call_value", JsonPrimitive(0))
            put("visible", JsonPrimitive(false))
        }
        val result = try {
            chain.firstWorking { url -> rpc.postJson("$url/wallet/triggersmartcontract", request) }.jsonObject
        } catch (e: WalletException) {
            return BroadcastResult.Error("Build TRC-20 call failed: ${e.message}")
        }
        val unsigned = result["transaction"]?.jsonObject
            ?: return BroadcastResult.Error("Contract call did not return a transaction")
        return signAndBroadcast(network, privateKey, ourAddress, unsigned)
    }

    suspend fun signAndBroadcast(
        network: NetworkConfig,
        privateKey: BigInteger,
        ourAddress: String,
        unsignedTx: JsonObject
    ): BroadcastResult {
        val chain = endpointsOf(network)
        if (chain.urls.isEmpty()) return BroadcastResult.Error("Network has no RPC endpoint")
        return try {
            val (signed, _) = signTransaction(privateKey, ourAddress, unsignedTx)
            broadcast(chain, signed)
        } catch (e: WalletException) {
            BroadcastResult.Error(e.message ?: "Sign failed")
        }
    }

    suspend fun broadcast(chain: RpcEndpointChain, signedTx: JsonObject): BroadcastResult = try {
        val result = chain.firstWorking { url -> rpc.postJson("$url/wallet/broadcasttransaction", signedTx) }.jsonObject
        val code = result["result"]?.let { (it as? JsonPrimitive)?.content }
            ?: result["code"]?.jsonPrimitive?.content
        when {
            result.containsKey("Error") -> BroadcastResult.Error(result["Error"]?.jsonPrimitive?.content ?: "Unknown error")
            result.containsKey("code") && result["code"]?.jsonPrimitive?.content != "SUCCESS" ->
                BroadcastResult.Error("Broadcast rejected: ${result["code"]?.jsonPrimitive?.content} ${result["message"]?.jsonPrimitive?.content ?: ""}")
            else -> BroadcastResult.Ok(result["txid"]?.jsonPrimitive?.content ?: result["transaction"]?.jsonObject?.get("txID")?.jsonPrimitive?.content ?: "")
        }
    } catch (e: WalletException) {
        BroadcastResult.Error(e.message ?: "Broadcast failed")
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    suspend fun getTrxBalance(network: NetworkConfig, address: String): Long? {
        val chain = endpointsOf(network)
        if (chain.urls.isEmpty()) return null
        return try {
            val request = buildJsonObject {
                put("address", JsonPrimitive(addressToHex(address)))
                put("visible", JsonPrimitive(false))
            }
            val result = chain.firstWorking { url -> rpc.postJson("$url/wallet/getaccount", request) }.jsonObject
            result["balance"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        } catch (_: WalletException) {
            null
        }
    }

    suspend fun getTrc20Balance(
        network: NetworkConfig, contractAddress: String, holder: String
    ): BigInteger? {
        val chain = endpointsOf(network)
        if (chain.urls.isEmpty()) return null
        return try {
            val selector = Hex.encode(Hashes.keccak256("balanceOf(address)".toByteArray(Charsets.US_ASCII))).substring(0, 8)
            val data = Hex.decode(selector + addressToHex(holder).removePrefix("0x").padStart(64, '0'))
            val request = buildJsonObject {
                put("owner_address", JsonPrimitive(addressToHex(holder)))
                put("contract_address", JsonPrimitive(addressToHex(contractAddress)))
                put("function_selector", JsonPrimitive("balanceOf(address)"))
                put("data", JsonPrimitive(Hex.encode(data)))
                put("visible", JsonPrimitive(false))
            }
            val result = chain.firstWorking { url -> rpc.postJson("$url/wallet/triggerconstantcontract", request) }.jsonObject
            val constant = result["constant_result"]?.let { it as? JsonArray }?.firstOrNull()?.jsonPrimitive?.content
                ?: return null
            BigInteger(constant.removePrefix("0x").padStart(1, '0'), 16)
        } catch (_: WalletException) {
            null
        }
    }

    data class DerivedTronKey(
        val privateKey: BigInteger,
        val address: String,
        val path: String
    )

    companion object {
        val MAINNET = NetworkConfig(
            id = "TRON:mainnet",
            chainType = ChainType.TRON,
            chainId = "mainnet",
            name = "TRON Mainnet",
            rpcUrls = listOf("https://api.trongrid.io"),
            nativeSymbol = "TRX",
            nativeDecimals = 6,
            explorerUrl = "https://tronscan.org",
            isTestnet = false
        )
        val SHASTA = NetworkConfig(
            id = "TRON:shasta",
            chainType = ChainType.TRON,
            chainId = "shasta",
            name = "TRON Shasta Testnet",
            rpcUrls = listOf("https://api.shasta.trongrid.io"),
            nativeSymbol = "TRX",
            nativeDecimals = 6,
            explorerUrl = "https://shasta.tronscan.org",
            isTestnet = true
        )
        val NILE = NetworkConfig(
            id = "TRON:nile",
            chainType = ChainType.TRON,
            chainId = "nile",
            name = "TRON Nile Testnet",
            rpcUrls = listOf("https://nile.trongrid.io"),
            nativeSymbol = "TRX",
            nativeDecimals = 6,
            explorerUrl = "https://nile.tronscan.org",
            isTestnet = true
        )

        fun defaultNetworks(): List<NetworkConfig> = listOf(MAINNET, SHASTA, NILE)
    }
}
