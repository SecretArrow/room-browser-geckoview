package com.roombrowser.domain.wallet.chains.bitcoin

import com.roombrowser.domain.wallet.chains.DerivationPathIndex
import com.roombrowser.domain.wallet.chains.DerivationPathParsing
import com.roombrowser.domain.wallet.crypto.Base58
import com.roombrowser.domain.wallet.crypto.Bech32
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger

/**
 * Bitcoin adapter — native segwit (BIP84) HD accounts, plus legacy BIP44
 * (P2PKH) accounts when explicitly requested.
 *
 * Derivation m/84'/0'/0'/0/i (mainnet) with bech32 P2WPKH addresses
 * (BIP173); legacy accounts derive at m/44' and return base58check P2PKH
 * addresses instead. Transaction building signs with the BIP143 segwit
 * sighash — covered by the official BIP143 test vector in BitcoinTest.
 * UTXO/fee/broadcast go through mempool.space (mainnet) and blockstream.info
 * (testnet).
 *
 * Bitcoin is NOT treated as an EVM chain: its addresses, signing and
 * serialization are all Bitcoin-specific.
 */
class BitcoinAdapter(private val rpc: JsonRpcClient = JsonRpcClient()) : DerivationPathIndex {

    fun chainType(): ChainType = ChainType.BITCOIN

    /**
     * This network's mempool API bases as a failover chain.
     *
     * Every call in this adapter used to go through `apiBase()`, which took
     * `network.rpcUrls.firstOrNull()` and nothing else, so the second url in
     * a network's list was dead weight: a network whose primary indexer had
     * died could not be read or broadcast at all, even with a working host
     * recorded beside it. A network carrying no url of its own keeps the
     * public mempool.space/blockstream host as the chain's only endpoint —
     * the fallback `apiBase()` used to provide. See [RpcEndpointChain].
     */
    fun endpointsOf(network: NetworkConfig): RpcEndpointChain {
        val chain = RpcEndpointChain.of(rpc, network)
        if (chain.urls.isNotEmpty()) return chain
        return RpcEndpointChain(
            rpc = rpc,
            key = network.id,
            urls = listOf(if (network.isTestnet) "https://blockstream.info/testnet/api" else "https://mempool.space/api")
        )
    }

    fun deriveAccount(seed: ByteArray, network: NetworkConfig, index: Int, legacy: Boolean = false): DerivedBitcoinKey {
        val coinType = if (network.isTestnet) 1L else 0L
        val purpose = if (legacy) 44L else 84L
        val path = "m/$purpose'/$coinType'/0'/0/$index"
        val key = Bip32PrivateKey.derive(seed, path)
        // A legacy (m/44') account must yield a base58check P2PKH address, not
        // a bech32 one: pairing a m/44' derivation with a segwit address would
        // hand the user an address whose key they cannot actually spend from.
        val address = if (legacy) {
            p2pkhAddress(key.compressedPublicKey, network.isTestnet)
        } else {
            p2wpkhAddress(key.compressedPublicKey, network.isTestnet)
        }
        return DerivedBitcoinKey(
            privateKey = key.key,
            compressedPublicKey = key.compressedPublicKey,
            address = address,
            path = path
        )
    }

    /**
     * Inverts [deriveAccount]: m/{purpose}'/{coinType}'/0'/0/{index}, where
     * purpose is 84 (native segwit) or 44 (legacy P2PKH). The index is the
     * unhardened FINAL level in both cases; coinType is left unchecked
     * because it is only ever 0 (mainnet) or 1 (testnet), and the stored
     * account already belongs to the chain the caller asked about.
     */
    override fun derivationIndexOf(path: String): Int? {
        val levels = DerivationPathParsing.levels(path) ?: return null
        if (levels.size != 5) return null
        val purpose = DerivationPathParsing.levelValue(levels[0]) ?: return null
        if (purpose != 44 && purpose != 84) return null
        if (!DerivationPathParsing.isLevel(levels[2], 0)) return null
        if (!DerivationPathParsing.isLevel(levels[3], 0)) return null
        return DerivationPathParsing.levelValue(levels[4])
    }

    fun isValidAddress(address: String, testnet: Boolean): Boolean {
        val expectedHrp = if (testnet) "tb" else "bc"
        val decoded = Bech32.decode(address) ?: return false
        if (decoded.first != expectedHrp) return false
        val data = decoded.second
        if (data.isEmpty()) return false
        val version = data[0]
        if (version != 0) return false
        val program = Bech32.from5bit(data.copyOfRange(1, data.size)) ?: return false
        return program.size == 20 // P2WPKH
    }

    /** P2WPKH address for a 33-byte compressed public key. */
    fun p2wpkhAddress(compressedPublicKey: ByteArray, testnet: Boolean): String {
        val pubkeyHash = Hashes.ripemd160(Hashes.sha256(compressedPublicKey))
        val hrp = if (testnet) "tb" else "bc"
        // to5bit encodes the witness version as a single 5-bit symbol followed
        // by the program's convertbits symbols (BIP173).
        return Bech32.encode(hrp, Bech32.to5bit(byteArrayOf(0x00) + pubkeyHash))
    }

    /**
     * Base58check P2PKH address for a 33-byte compressed public key — the
     * legacy (m/44') counterpart of [p2wpkhAddress]. Version byte 0x00 on
     * mainnet, 0x6f on testnet, with the first 4 bytes of sha256d as the
     * checksum (the same construction TronAdapter.base58Check uses).
     */
    fun p2pkhAddress(compressedPublicKey: ByteArray, testnet: Boolean): String {
        val pubkeyHash = Hashes.ripemd160(Hashes.sha256(compressedPublicKey))
        val version = if (testnet) 0x6f else 0x00 // 0x6f testnet, 0x00 mainnet
        val payload = byteArrayOf(version.toByte()) + pubkeyHash
        return Base58.encode(payload + Hashes.sha256d(payload).copyOfRange(0, 4))
    }

    // ------------------------------------------------------------------
    // Message signing (window.BitcoinProvider.signMessage)
    // ------------------------------------------------------------------

    fun signMessage(privateKey: BigInteger, message: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(Signing.bitcoinSignMessage(privateKey, message))

    // ------------------------------------------------------------------
    // Transaction building & signing (dashboard send)
    // ------------------------------------------------------------------

    data class Utxo(
        val txid: String,
        val vout: Long,
        val valueSats: Long,
        val confirmed: Boolean
    )

    data class PreparedSend(
        val utxos: List<Utxo>,
        val outputs: List<TxOutput>,
        val feeSats: Long,
        val changeAddress: String,
        val rawSignedHex: String,
        val amountSats: Long,
        val toAddress: String
    )

    data class TxOutput(val address: String, val valueSats: Long)

    suspend fun buildAndSignSend(
        network: NetworkConfig,
        privateKey: BigInteger,
        fromAddress: String,
        toAddress: String,
        amountSats: Long,
        feeRatePerVbyte: Long? = null
    ): PreparedSend {
        val chain = endpointsOf(network)
        val utxos = fetchUtxos(chain, fromAddress).filter { it.confirmed }
        if (utxos.isEmpty()) throw WalletException.NetworkUnavailable("No confirmed UTXOs for $fromAddress")
        val feeRate = feeRatePerVbyte ?: fetchFeeRate(chain) ?: 12L
        val pubkey = Bip32PrivateKey.publicKeyPoint(privateKey).getEncoded(true)
        val pubkeyHash = Hashes.ripemd160(Hashes.sha256(pubkey))

        // Greedy UTXO selection: smallest set covering amount + estimated fee.
        val sorted = utxos.sortedByDescending { it.valueSats }
        var selected = emptyList<Utxo>()
        var inputSum = 0L
        val target = amountSats + 300 // seed fee estimate for selection
        for (u in sorted) {
            selected += u
            inputSum += u.valueSats
            if (inputSum >= target) break
        }
        if (inputSum < amountSats + 200) {
            throw WalletException.InvalidParams("Insufficient balance ($inputSum sats for ${amountSats}s send)")
        }

        val dustLimit = 546L
        val vsizeEstimate = 10 + selected.size * 68 + 2 * 31
        var fee = vsizeEstimate * feeRate
        var change = inputSum - amountSats - fee
        if (change < 0) throw WalletException.InvalidParams("Insufficient balance for fee")
        val outputs = mutableListOf(TxOutput(toAddress, amountSats))
        if (change >= dustLimit) {
            outputs += TxOutput(fromAddress, change)
        } else {
            fee += change // change below dust goes to the miner
            change = 0
        }

        val signed = signP2wpkhTransaction(privateKey, pubkeyHash, selected, outputs)
        return PreparedSend(
            utxos = selected,
            outputs = outputs,
            feeSats = fee,
            changeAddress = fromAddress,
            rawSignedHex = Hex.encode(signed),
            amountSats = amountSats,
            toAddress = toAddress
        )
    }

    /**
     * Builds and signs a native-segwit transaction. Sighash follows BIP143
     * (verified against the official test vector).
     */
    fun signP2wpkhTransaction(
        privateKey: BigInteger,
        pubkeyHash: ByteArray,
        utxos: List<Utxo>,
        outputs: List<TxOutput>,
        locktime: Long = 0
    ): ByteArray {
        val sequence = 0xFFFFFFFFL
        // hashPrevouts = sha256d(concat(outpoints))
        val prevouts = utxos.flatMap { Hex.decode(it.txid).toList() + le32(it.vout).toList() }.toByteArray()
        val hashPrevouts = Hashes.sha256d(prevouts)
        val hashSequence = Hashes.sha256d(utxos.flatMap { le32(sequence).toList() }.toByteArray())
        val hashOutputs = Hashes.sha256d(
            outputs.flatMap { encodeOutput(it).toList() }.toByteArray()
        )

        val witnesses = mutableListOf<ByteArray>()
        utxos.forEachIndexed { index, utxo ->
            val sighash = bip143Sighash(pubkeyHash, utxo, hashPrevouts, hashSequence, hashOutputs, locktime)
            val sig = Signing.secp256k1SignDigest(privateKey, sighash)
            val der = derEncode(sig.r, sig.s) + byteArrayOf(0x01) // SIGHASH_ALL
            val pubkey = Bip32PrivateKey.publicKeyPoint(privateKey).getEncoded(true)
            witnesses += (der + pubkey)
        }

        // Serialize: nVersion | 0x00 0x01 | vin | vout | witness | nLocktime
        val out = java.io.ByteArrayOutputStream()
        out.write(le32(2))
        out.write(0x00); out.write(0x01) // segwit marker + flag
        // vin
        out.write(compactSize(utxos.size.toLong()))
        utxos.forEach {
            out.write(Hex.decode(it.txid))
            out.write(le32(it.vout))
            out.write(compactSize(0)) // empty scriptSig
            out.write(le32(sequence))
        }
        // vout
        out.write(compactSize(outputs.size.toLong()))
        outputs.forEach { out.write(encodeOutput(it)) }
        // witness
        utxos.forEachIndexed { i, _ ->
            out.write(compactSize(2))
            val sig = witnesses[i]
            out.write(compactSize((sig.size - 33).toLong()))
            out.write(sig.copyOfRange(0, sig.size - 33))
            out.write(compactSize(33L))
            out.write(sig.copyOfRange(sig.size - 33, sig.size))
        }
        out.write(le32(locktime))
        return out.toByteArray()
    }

    /**
     * BIP143 sighash for one P2WPKH input (verified against the official
     * BIP143 native-P2WPKH test vector).
     */
    fun bip143Sighash(
        pubkeyHash: ByteArray,
        utxo: Utxo,
        hashPrevouts: ByteArray,
        hashSequence: ByteArray,
        hashOutputs: ByteArray,
        locktime: Long,
        version: Long = 2,
        sequence: Long = 0xFFFFFFFFL
    ): ByteArray {
        val scriptCode = byteArrayOf(0x19) + Hex.decode("76a914") + pubkeyHash + Hex.decode("88ac")
        val preimage = le32(version) +
            hashPrevouts + hashSequence +
            Hex.decode(utxo.txid) + le32(utxo.vout) +
            scriptCode + le64(utxo.valueSats) + le32(sequence) +
            hashOutputs + le32(locktime) + le32(1) // SIGHASH_ALL
        return Hashes.sha256d(preimage)
    }

    private fun encodeOutput(output: TxOutput): ByteArray {
        val script = bech32OutputScript(output.address)
            ?: throw WalletException.InvalidParams("Unsupported output address ${output.address}")
        return le64(output.valueSats) + compactSize(script.size.toLong()) + script
    }

    private fun bech32OutputScript(address: String): ByteArray? {
        val decoded = Bech32.decode(address) ?: return null
        if (decoded.second.isEmpty() || decoded.second[0] != 0) return null
        val program = Bech32.from5bit(decoded.second.copyOfRange(1, decoded.second.size)) ?: return null
        if (program.size != 20) return null // only P2WPKH outputs supported
        return byteArrayOf(0x00, 0x14) + program
    }

    fun le32(value: Long): ByteArray = byteArrayOf(
        (value and 0xff).toByte(), ((value shr 8) and 0xff).toByte(),
        ((value shr 16) and 0xff).toByte(), ((value shr 24) and 0xff).toByte()
    )

    fun le64(value: Long): ByteArray = le32(value) + le32(value ushr 32)

    fun compactSize(value: Long): ByteArray = when {
        value < 0xfd -> byteArrayOf(value.toByte())
        value <= 0xffff -> byteArrayOf(0xfd.toByte()) + le32(value).copyOfRange(0, 2)
        value <= 0xffffffff -> byteArrayOf(0xfe.toByte()) + le32(value)
        else -> byteArrayOf(0xff.toByte()) + le64(value)
    }

    /** Minimal DER encoding of (r, s). */
    fun derEncode(r: ByteArray, s: ByteArray): ByteArray {
        fun derInt(bytes: ByteArray): ByteArray {
            var b = bytes
            var start = 0
            while (start < b.size - 1 && b[start] == 0.toByte() && (b[start + 1].toInt() and 0x80) == 0) start++
            b = b.copyOfRange(start, b.size)
            if ((b[0].toInt() and 0x80) != 0) b = byteArrayOf(0) + b
            return byteArrayOf(0x02, b.size.toByte()) + b
        }
        val rInt = derInt(r)
        val sInt = derInt(s)
        val body = rInt + sInt
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    // ------------------------------------------------------------------
    // Network API (mempool.space / blockstream)
    // ------------------------------------------------------------------

    suspend fun fetchUtxos(chain: RpcEndpointChain, address: String): List<Utxo> = try {
        val result = chain.firstWorking { url -> rpc.getJson("${url.trimEnd('/')}/address/$address/utxo") }
        result.jsonArray.mapNotNull { el ->
            val obj = el.jsonObject
            Utxo(
                txid = obj["txid"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                vout = obj["vout"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null,
                valueSats = obj["value"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null,
                confirmed = obj["status"]?.jsonObject?.get("confirmed")?.jsonPrimitive?.content == "true"
            )
        }
    } catch (_: WalletException) {
        emptyList()
    }

    suspend fun fetchFeeRate(chain: RpcEndpointChain): Long? = try {
        val result = chain.firstWorking { url -> rpc.getJson("${url.trimEnd('/')}/v1/fees/recommended") }.jsonObject
        result["hourFee"]?.jsonPrimitive?.content?.toLongOrNull()?.coerceAtLeast(1)
    } catch (_: WalletException) {
        null
    }

    suspend fun getBalanceSats(network: NetworkConfig, address: String): Long {
        val chain = endpointsOf(network)
        return try {
            val result = chain.firstWorking { url -> rpc.getJson("${url.trimEnd('/')}/address/$address") }.jsonObject
            val stats = result["chain_stats"]?.jsonObject
            val funded = stats?.get("funded_txo_sum")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val spent = stats?.get("spent_txo_sum")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            funded - spent
        } catch (_: WalletException) {
            0L
        }
    }

    suspend fun broadcast(network: NetworkConfig, rawTxHex: String): BroadcastResult = try {
        val tx = endpointsOf(network)
            .firstWorking { url -> rpc.postJsonText("${url.trimEnd('/')}/tx", "\"$rawTxHex\"") }
        BroadcastResult.Ok(tx.trim().trim('"'))
    } catch (e: WalletException) {
        BroadcastResult.Error(e.message ?: "Broadcast failed")
    }

    data class DerivedBitcoinKey(
        val privateKey: BigInteger,
        val compressedPublicKey: ByteArray,
        val address: String,
        val path: String
    )

    companion object {
        fun mainnet(): NetworkConfig = NetworkConfig(
            id = "BITCOIN:mainnet",
            chainType = ChainType.BITCOIN,
            chainId = "mainnet",
            name = "Bitcoin Mainnet",
            rpcUrls = listOf("https://mempool.space/api"),
            nativeSymbol = "BTC",
            nativeDecimals = 8,
            explorerUrl = "https://mempool.space",
            isTestnet = false
        )

        fun testnet(): NetworkConfig = NetworkConfig(
            id = "BITCOIN:testnet",
            chainType = ChainType.BITCOIN,
            chainId = "testnet",
            name = "Bitcoin Testnet",
            rpcUrls = listOf("https://blockstream.info/testnet/api"),
            nativeSymbol = "tBTC",
            nativeDecimals = 8,
            explorerUrl = "https://blockstream.info/testnet",
            isTestnet = true
        )

        fun defaultNetworks(): List<NetworkConfig> = listOf(mainnet(), testnet())
    }
}
