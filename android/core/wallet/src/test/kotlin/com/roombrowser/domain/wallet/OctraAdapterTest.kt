package com.roombrowser.domain.wallet

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.wallet.chains.octra.OctraAdapter
import com.roombrowser.domain.wallet.chains.octra.OctraCanonicalJson
import com.roombrowser.domain.wallet.crypto.Ed25519
import com.roombrowser.domain.wallet.crypto.Hashes
import com.roombrowser.domain.wallet.crypto.Hex
import com.roombrowser.domain.wallet.crypto.Mnemonics
import com.roombrowser.domain.wallet.model.BroadcastResult
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.domain.wallet.model.NetworkConfig
import com.roombrowser.domain.wallet.model.WalletException
import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Octra has no specification and publishes no official test vectors, so the
 * only defensible correctness claim is byte-equality with an independent
 * implementation. [VECTOR_SEED] and everything derived from it in
 * [the frozen end-to-end vector] are the values `octave.js` (MIT) freezes in
 * its own test suite and asserts against its own signer — so reproducing them
 * here proves this is the same algorithm, not merely a plausible one.
 *
 * The RPC fixtures below are the live node's replies verbatim, but no offline
 * test — and no devnet probe either — establishes that the network accepts a
 * signature: `octra_submit` answers `sender not found` before it verifies one,
 * and devnet has no faucet to fund an account that could get further.
 */
class OctraAdapterTest {

    private val adapter = OctraAdapter()

    // ------------------------------------------------------------------
    // The reference vector
    // ------------------------------------------------------------------

    @Test
    fun `the frozen octave_js transaction vector reproduces byte for byte`() {
        assertThat(Hex.encode(Ed25519.publicKeyFromSeed(VECTOR_SEED))).isEqualTo(VECTOR_PUB)
        assertThat(adapter.addressFromPublicKey(Hex.decode(VECTOR_PUB))).isEqualTo(VECTOR_ADDRESS)

        val canonical = OctraCanonicalJson.transfer(
            from = VECTOR_ADDRESS,
            to = VECTOR_ADDRESS,
            amountRaw = "1000000",
            nonce = 42,
            feeRaw = "200000",
            timestamp = 1_700_000_000,
            message = VECTOR_MESSAGE
        )
        assertThat(canonical).isEqualTo(VECTOR_CANONICAL)

        // The hash is the digest of the canonical bytes, not of the signed
        // object — asserted as a digest so the comparison cannot be weakened
        // by how this file happens to spell its escapes.
        assertThat(Hex.encode(Hashes.sha256(canonical.toByteArray(Charsets.UTF_8))))
            .isEqualTo(VECTOR_TX_HASH)

        val signature = Ed25519.sign(VECTOR_SEED, canonical.toByteArray(Charsets.UTF_8))
        assertThat(Base64.getEncoder().encodeToString(signature)).isEqualTo(VECTOR_SIGNATURE)
        assertThat(Ed25519.verify(Hex.decode(VECTOR_PUB), canonical.toByteArray(Charsets.UTF_8), signature))
            .isTrue()
    }

    @Test
    fun `a transfer without a message omits the field entirely`() {
        assertThat(
            OctraCanonicalJson.transfer(
                from = "octABC",
                to = "octDEF",
                amountRaw = "1000000",
                nonce = 1,
                feeRaw = "200000",
                timestamp = 1_703_000_000
            )
        ).isEqualTo(
            "{\"from\":\"octABC\",\"to_\":\"octDEF\",\"amount\":\"1000000\",\"nonce\":1," +
                "\"ou\":\"200000\",\"timestamp\":1703000000,\"op_type\":\"standard\"}"
        )
    }

    @Test
    fun `message escaping covers the short forms and raw control codes`() {
        val canonical = OctraCanonicalJson.transfer(
            from = "octABC",
            to = "octDEF",
            amountRaw = "1",
            nonce = 0,
            feeRaw = "1",
            timestamp = 0,
            message = "a\u0001b\u001Fc\u0008d\u00e9"
        )
        assertThat(canonical).endsWith(
            "\"message\":\"a\\u0001b\\u001fc\\bd\u00e9\"}"
        )
    }

    // ------------------------------------------------------------------
    // Derivation and address shape
    // ------------------------------------------------------------------

    @Test
    fun `the abandon mnemonic master key matches the reference derivation`() {
        val seed = Mnemonics.toSeed(ABANDON)
        val privateSeed = OctraAdapter.privateSeedFromBip39(seed)
        assertThat(Hex.encode(privateSeed)).isEqualTo(ABANDON_MASTER_KEY)
        // The address the ENGINE's create/import flow must land on.
        assertThat(adapter.accountFromSeed(privateSeed).address).isEqualTo(OCTRA0)
        assertThat(adapter.deriveAccount(seed, 0).address).isEqualTo(OCTRA0)
    }

    @Test
    fun `a body shorter than 44 characters is padded with leading ones`() {
        assertThat(adapter.addressFromPublicKey(Hex.decode(SHORT_BODY_PUB)))
            .isEqualTo(SHORT_BODY_ADDRESS)
    }

    @Test
    fun `address validation checks the shape and nothing else`() {
        assertThat(adapter.isValidAddress(OCTRA0)).isTrue()
        assertThat(adapter.isValidAddress(SHORT_BODY_ADDRESS)).isTrue()
        // 47 characters, right prefix, but 0/O/I/l are not in the alphabet.
        assertThat(adapter.isValidAddress("oct" + "1".repeat(43) + "0")).isFalse()
        assertThat(adapter.isValidAddress("oct" + "1".repeat(43) + "O")).isFalse()
        assertThat(adapter.isValidAddress("oct" + "1".repeat(43) + "I")).isFalse()
        assertThat(adapter.isValidAddress("oct" + "1".repeat(43) + "l")).isFalse()
        // Wrong prefix, and one character short.
        assertThat(adapter.isValidAddress("0ct" + "1".repeat(44))).isFalse()
        assertThat(adapter.isValidAddress("oct" + "1".repeat(43))).isFalse()
    }

    @Test
    fun `only account zero exists`() {
        val seed = Mnemonics.toSeed(ABANDON)
        val thrown = assertThrows(WalletException.UnsupportedMethod::class.java) {
            adapter.deriveAccount(seed, 1)
        }
        assertThat(thrown).hasMessageThat().contains("single account")
        assertThat(adapter.derivationIndexOf(OctraAdapter.PATH)).isEqualTo(0)
        assertThat(adapter.derivationIndexOf("m/44'/501'/0'/0'")).isNull()
    }

    // ------------------------------------------------------------------
    // The signed wire object
    // ------------------------------------------------------------------

    @Test
    fun `the signed object carries the reference field order and verifies`() {
        val signed = adapter.signTransfer(
            seed32 = VECTOR_SEED,
            from = VECTOR_ADDRESS,
            to = VECTOR_ADDRESS,
            amountRaw = 1_000_000,
            nonce = 42,
            feeRaw = "200000",
            timestamp = 1_700_000_000
        )
        assertThat(signed.keys.toList()).containsExactly(
            "from", "to_", "amount", "nonce", "ou", "timestamp", "op_type", "signature", "public_key"
        ).inOrder()
        assertThat(signed["amount"]!!.toString()).isEqualTo("\"1000000\"")
        assertThat(signed["nonce"]!!.toString()).isEqualTo("42")
        assertThat(signed["op_type"]!!.toString()).isEqualTo("\"standard\"")

        // Signature and key ride OUTSIDE the signed payload.
        val canonical = OctraCanonicalJson.transfer(
            VECTOR_ADDRESS, VECTOR_ADDRESS, "1000000", 42, "200000", 1_700_000_000
        )
        val signature = Base64.getDecoder().decode(signed["signature"]!!.toString().trim('"'))
        assertThat(Ed25519.verify(Hex.decode(VECTOR_PUB), canonical.toByteArray(Charsets.UTF_8), signature))
            .isTrue()
    }

    // ------------------------------------------------------------------
    // RPC
    // ------------------------------------------------------------------

    /**
     * An address the node has never seen is a zero balance, not a failure —
     * every freshly created account starts here. The code is the one the live
     * devnet answers with.
     */
    @Test
    fun `an unknown address reads as a zero balance`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"jsonrpc":"2.0","id":1,"error":{"code":100,"message":"sender not found"}}"""
            )
        )
        server.start()
        try {
            assertThat(runBlocking { adapter.getBalance(network(server), OCTRA0) }).isEqualTo(0L)
        } finally {
            server.shutdown()
        }
    }

    /**
     * The live node's own balance payload, verbatim: `balance_raw` is the key
     * that holds the amount, and `raw` does not exist. Reading the wrong one
     * returned null for every funded account.
     */
    @Test
    fun `getBalance reads balance_raw out of octra_balance`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"jsonrpc":"2.0","id":1,"result":{"address":"$OCTRA0",""" +
                    """"balance":"5.000000","balance_raw":"5000000","nonce":7,""" +
                    """"pending_nonce":7,"has_public_key":true}}"""
            )
        )
        server.start()
        try {
            val balance = runBlocking { adapter.getBalance(network(server), OCTRA0) }
            assertThat(balance).isEqualTo(5_000_000L)
            val body = server.takeRequest().body.readUtf8()
            assertThat(body).contains("\"method\":\"octra_balance\"")
            assertThat(body).contains("\"$OCTRA0\"")
        } finally {
            server.shutdown()
        }
    }

    /** A node that answers with the old `raw` spelling still reads. */
    @Test
    fun `getBalance still accepts a bare raw field`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"jsonrpc":"2.0","id":1,"result":{"raw":"5000000","nonce":7}}"""
            )
        )
        server.start()
        try {
            assertThat(runBlocking { adapter.getBalance(network(server), OCTRA0) })
                .isEqualTo(5_000_000L)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `sendNative prefers pending_nonce adds one and submits the signed object`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"jsonrpc":"2.0","id":1,"result":{"address":"$VECTOR_ADDRESS",""" +
                    """"balance":"5.000000","balance_raw":"5000000","nonce":7,""" +
                    """"pending_nonce":9,"has_public_key":true}}"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{"jsonrpc":"2.0","id":2,"result":{"minimum":"1000","base_fee":"1000",""" +
                    """"recommended":"250000","fast":"2000"}}"""
            )
        )
        server.enqueue(
            MockResponse().setBody("""{"jsonrpc":"2.0","id":3,"result":{"tx_hash":"0xabc"}}""")
        )
        server.start()
        try {
            val result = runBlocking {
                adapter.sendNative(
                    network = network(server),
                    seed32 = VECTOR_SEED,
                    fromAddress = VECTOR_ADDRESS,
                    toAddress = VECTOR_ADDRESS,
                    amountRaw = 1_000_000,
                    timestamp = 1_700_000_000
                )
            }
            assertThat(result).isEqualTo(BroadcastResult.Ok("0xabc"))

            server.takeRequest() // octra_balance
            assertThat(server.takeRequest().body.readUtf8()).contains("\"method\":\"octra_recommendedFee\"")
            val submitted = server.takeRequest().body.readUtf8()
            assertThat(submitted).contains("\"method\":\"octra_submit\"")
            // pending_nonce 9 + 1, and the fee the node recommended.
            assertThat(submitted).contains("\"nonce\":10")
            assertThat(submitted).contains("\"ou\":\"250000\"")
            assertThat(submitted).contains("\"amount\":\"1000000\"")
            assertThat(submitted).contains("\"op_type\":\"standard\"")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `sendNative refuses a recipient that is not an Octra address`() {
        val server = MockWebServer()
        server.start()
        try {
            val result = runBlocking {
                adapter.sendNative(
                    network = network(server),
                    seed32 = VECTOR_SEED,
                    fromAddress = VECTOR_ADDRESS,
                    toAddress = "0xdeadbeef",
                    amountRaw = 1_000_000
                )
            }
            assertThat(result).isInstanceOf(BroadcastResult.Error::class.java)
            // Refused before touching the network, so no request was made.
            assertThat(server.requestCount).isEqualTo(0)
        } finally {
            server.shutdown()
        }
    }

    /**
     * The live node answers `octra_recommendedFee` with this schedule, so this
     * is the shape the reader has to find the fee in.
     */
    @Test
    fun `the recommended fee is read out of the node's schedule`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"jsonrpc":"2.0","id":1,"result":{"minimum":"1000","base_fee":"1000",""" +
                    """"recommended":"1750","fast":"2000","usage_pct":0}}"""
            )
        )
        server.start()
        try {
            assertThat(runBlocking { adapter.recommendedFeeOf(network(server)) }).isEqualTo("1750")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a fee the node will not quote falls back to the published floor`() {
        val server = MockWebServer()
        // The live node's own answer when it is saturated.
        server.enqueue(
            MockResponse().setBody(
                """{"jsonrpc":"2.0","id":1,"error":{"code":-32000,""" +
                    """"message":"RPC capacity reached; retry shortly"}}"""
            )
        )
        server.start()
        try {
            assertThat(runBlocking { adapter.recommendedFeeOf(network(server)) })
                .isEqualTo(OctraAdapter.DEFAULT_FEE_RAW)
        } finally {
            server.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // Presets
    // ------------------------------------------------------------------

    @Test
    fun `presets put a non-testnet mainnet first`() {
        val presets = OctraAdapter.defaultNetworks()
        assertThat(presets).isNotEmpty()
        assertThat(presets.first().isTestnet).isFalse()
        assertThat(presets.map { it.id }).containsExactly("OCTRA:mainnet", "OCTRA:devnet")
        assertThat(presets.map { it.chainType }.toSet()).containsExactly(ChainType.OCTRA)
        assertThat(presets.all { it.rpcUrls.isNotEmpty() && it.rpcUrls.all { url -> url.startsWith("https://") } })
            .isTrue()
    }

    private fun network(server: MockWebServer) = NetworkConfig(
        id = "OCTRA:test",
        chainType = ChainType.OCTRA,
        chainId = "test",
        name = "Octra Test",
        rpcUrls = listOf(server.url("/").toString()),
        nativeSymbol = "OCT",
        nativeDecimals = 6,
        explorerUrl = "https://example.invalid",
        isTestnet = true
    )

    private companion object {
        const val ABANDON =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

        /** The engine's own golden address for [ABANDON]. */
        const val OCTRA0 = "octCRus1yKzZbQoABuUhWQzcps8KhdqqQWxPzGciLgY698h"

        /** HMAC-SHA512("Octra seed", bip39Seed)[0..32] for [ABANDON]. */
        const val ABANDON_MASTER_KEY =
            "6d6951ff80c1bfe7eea39065bdcd42387bd25d4277d21bfa7b6f9e23c8e09c10"

        /** A public key whose base58 body is 43 characters, so the pad branch runs. */
        const val SHORT_BODY_PUB =
            "000000000000000000000000000000000000000000000000000000000000001a"
        const val SHORT_BODY_ADDRESS = "oct1zHDhuhZ9kBpPku5KstyRbZ7t54ZTk6xNz15dwQyHZAK"

        val VECTOR_SEED = ByteArray(32) { 0x01 }
        const val VECTOR_PUB =
            "8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c"
        const val VECTOR_ADDRESS = "oct4XmjKEd9A96KhoMX94zWJmd28dcPisbWGYWtad1dQ9v5"
        const val VECTOR_MESSAGE = "hello \"oct\" \\ \n"
        const val VECTOR_CANONICAL =
            "{\"from\":\"" + VECTOR_ADDRESS + "\",\"to_\":\"" + VECTOR_ADDRESS +
                "\",\"amount\":\"1000000\",\"nonce\":42,\"ou\":\"200000\"," +
                "\"timestamp\":1700000000,\"op_type\":\"standard\"," +
                "\"message\":\"hello \\\"oct\\\" \\\\ \\n\"}"
        const val VECTOR_SIGNATURE =
            "lJiansZKYnQ/GkoIAB+aHSC0UkUeoMH5VepNCkfKtIq0iGwPWJY+V0xmWLIs/P8zoS/J7d+iv3v61RJ9aNyOBg=="
        const val VECTOR_TX_HASH =
            "995d697f6239951db171d4c26a5295d3d8f5f099efbcb11eba8b879846c1ccac"
    }
}
