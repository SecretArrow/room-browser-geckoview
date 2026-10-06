package com.roombrowser.browser.wallet

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.export.WalletBackup
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.wallet.chains.ChainRegistry
import com.roombrowser.domain.wallet.chains.bitcoin.BitcoinAdapter
import com.roombrowser.domain.wallet.chains.cosmos.CosmosAdapter
import com.roombrowser.domain.wallet.chains.evm.EvmAdapter
import com.roombrowser.domain.wallet.chains.octra.OctraAdapter
import com.roombrowser.domain.wallet.crypto.Hashes
import com.roombrowser.domain.wallet.crypto.Hex
import com.roombrowser.domain.wallet.crypto.Mnemonics
import com.roombrowser.domain.wallet.model.BalanceResult
import com.roombrowser.domain.wallet.model.BroadcastResult
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.domain.wallet.model.FeeEstimate
import com.roombrowser.domain.wallet.model.NetworkConfig
import com.roombrowser.domain.wallet.model.WalletException
import io.mockk.coVerify
import io.mockk.spyk
import java.math.BigInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests for [WalletEngine] against the FROZEN contract. Deterministic
 * vectors: the standard "abandon … about" BIP39 mnemonic, with index-0/1
 * addresses pinned to the values produced by the REAL chain adapters (cross
 * checked where possible against ChainAdaptersCrossValidationTest's official
 * SDK vectors) and the raw key "c5338c…" for imports. NO network: the
 * balance / sign / chainlist legs are overridden seams.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WalletEngineTest {

    // ------------------------------------------------------------------
    // Deterministic vectors (REAL ChainRegistry output, pinned)
    // ------------------------------------------------------------------

    private companion object {
        const val TEST_NOW = 1_700_000_000_000L

        /** ChainAdaptersCrossValidationTest.ABANDON_MNEMONIC. */
        const val ABANDON =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

        /** ChainAdaptersCrossValidationTest.SEED (hex secp256k1/ed25519 material). */
        const val SEED = "c5338cd251c22daa8c9c9cc94f498cc8a5c7e1d2e75287a5dda91096fe64efa5"

        /** Base58 of SEED's 32 bytes — the Solana/Phantom import form. */
        const val SEED_B58 = "EGnueDXyzm1qrJe1eLxXx3bvhz2bJqa8fKAdu7fiP6HJ"

        // Index-0/1 addresses derived from ABANDON by the real adapters.
        const val EVM0 = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
        const val EVM1 = "0x6Fac4D18c912343BF86fa7049364Dd4E424Ab9C0"
        const val SOL0 = "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk"
        const val APTOS0 = "0x20a09cf089b49c6aaf8269fcf50b5fd3334e2299b75f164d0a5da11513fcf177"
        // Canonical Sui path m/44'/784'/0'/0'/0' (the pre-release build put the
        // index on the account level, so this value moved — see SuiAdapter).
        const val SUI0 = "0x5e93a736d04fbb25737aa40bee40171ef79f65fae833749e3c089fe7cc2161f1"
        const val COSMOS0 = "cosmos19rl4cm2hmr8afy4kldpxz3fka4jguq0auqdal4"
        const val BTC0 = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
        const val TRON0 = "TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH"
        // Octra has a single derivation identity, not a BIP-32 path.
        const val OCTRA0 = "octCRus1yKzZbQoABuUhWQzcps8KhdqqQWxPzGciLgY698h"

        // personal_sign of "hello world" with the EVM0 key (RFC6979: deterministic).
        const val EVM0_PERSONAL_SIGN =
            "0xae35d9375b015664a7b115a63a4515142b68059b164dd187e0b5232d47ca69685104" +
                "d05d1c6c58b1fe5842f28459e2ea5bd571c0196f10da25fd2140eeef47e51c"

        /** Minimal valid EIP-712 payload (web3j StructuredDataEncoder-compatible). */
        const val TYPED_DATA = "{\"types\":{\"EIP712Domain\":[" +
            "{\"name\":\"name\",\"type\":\"string\"},{\"name\":\"version\",\"type\":\"string\"}," +
            "{\"name\":\"chainId\",\"type\":\"uint256\"}]," +
            "\"Mail\":[{\"name\":\"from\",\"type\":\"address\"},{\"name\":\"contents\",\"type\":\"string\"}]}," +
            "\"primaryType\":\"Mail\",\"domain\":{\"name\":\"Room Browser\",\"version\":\"1\",\"chainId\":\"1\"}," +
            "\"message\":{\"from\":\"$EVM0\",\"contents\":\"hello typed data\"}}"

        // eth_signTypedData_v4 of TYPED_DATA with the EVM0 key (RFC6979: deterministic).
        const val EVM0_TYPED_SIGNATURE =
            "0xaf115e8dc7ccaebe90ad67cdcab39b1f09b494dc1dc8cb5fddcbbb6e2c923aad" +
                "05bfc2dad1df747e68cb975cc1d41acca5be46b291ea40a6cca0275eeb915e451c"

        // Import addresses for SEED (cross-validated against the official SDKs).
        const val EVM_IMPORT = "0x417AA4b5a8bf239d05C03C7C0C0231ECF7620c26"
        const val SOL_IMPORT = "FwzQxHPj38ZiS6RPyFeGhFL9RahBPAA2ZZNWS6sab475"
        const val APTOS_IMPORT = "0x978c213990c4833df71548df7ce49d54c759d6b6d932de22b24d56060b7af2aa"
        const val SUI_IMPORT = "0x21ba6e3bcecaa6c683027e4b4fbd8d4de71f139e7ad7e89e43cb7b792602d63d"
        const val TRON_IMPORT = "TFwRqwWNov6cPN531NnhAKnLHPa6V1JtVu"
    }

    // ------------------------------------------------------------------
    // Fixture
    // ------------------------------------------------------------------

    private val testDispatcher = StandardTestDispatcher()
    private val profile = ProfileId("profile-1")
    private val otherProfile = ProfileId("profile-2")

    private lateinit var fake: FakeWalletRepository
    private lateinit var engine: TestWalletEngine

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fake = FakeWalletRepository()
        engine = TestWalletEngine()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * The engine with every network-facing leg replaced: crypto on the
     * virtual dispatcher; native/dApp sends, balances, Chainlist and EVM fees
     * are injectable results.
     */
    private inner class TestWalletEngine(
        repo: WalletRepositoryApi = this@WalletEngineTest.fake
    ) : WalletEngine(repo, ChainRegistry(), { TEST_NOW }) {

        override val cryptoDispatcher: CoroutineDispatcher
            get() = this@WalletEngineTest.testDispatcher

        var nativeSendResult: BroadcastResult = BroadcastResult.Ok("0xnativehash")
        var nativeSendError: WalletException? = null

        var dappSendResult: DappTransactionResult =
            DappTransactionResult("0xdapphash", null, "0.001 ETH gas")
        var dappSendError: WalletException? = null

        /** accountId -> Ok/Error value, "silent" for null, absent = default Ok. */
        val balanceBehavior = mutableMapOf<String, Any?>()

        var chainlistResult: List<NetworkConfig> = emptyList()
        var chainlistError: Boolean = false

        var evmFeeResult: BigInteger? = null

        var octraFeeResult: String? = null

        override suspend fun signAndBroadcastNative(
            account: WalletAccountRecord,
            network: NetworkConfig,
            to: String,
            baseAmount: BigInteger
        ): BroadcastResult {
            nativeSendError?.let { throw it }
            return nativeSendResult
        }

        override suspend fun signAndBroadcastDappTransaction(
            request: DappRequest.SendTransaction,
            account: WalletAccountRecord,
            network: NetworkConfig,
            evmParams: EvmAdapter.TransactionParams?
        ): DappTransactionResult {
            dappSendError?.let { throw it }
            return dappSendResult
        }

        override suspend fun fetchAccountBalance(
            account: WalletAccountRecord,
            profileId: ProfileId
        ): BalanceResult? = when (val behavior = balanceBehavior[account.id]) {
            is WalletException -> throw behavior
            is BalanceResult -> behavior
            "silent" -> null
            else -> BalanceResult.Ok("1.0", account.chainType.displayName)
        }

        override suspend fun fetchChainlistCatalog(): List<NetworkConfig> {
            if (chainlistError) throw WalletException.NetworkUnavailable("offline")
            return chainlistResult
        }

        override suspend fun evmFeeEstimate(
            network: NetworkConfig,
            from: String,
            to: String?,
            value: BigInteger
        ): BigInteger? = evmFeeResult

        override suspend fun octraFeeEstimate(network: NetworkConfig): String? = octraFeeResult
    }

    /** assertThrows for suspend blocks, run inside the current test coroutine. */
    private suspend inline fun <reified T : Throwable> assertThrowsSuspend(
        crossinline block: suspend () -> Unit
    ): T {
        val thrown = try {
            block()
            null
        } catch (e: Throwable) {
            e
        }
        checkNotNull(thrown) { "Expected ${T::class.simpleName} but nothing was thrown" }
        assertThat(thrown).isInstanceOf(T::class.java)
        @Suppress("UNCHECKED_CAST")
        return thrown as T
    }

    /** The non-null bridge error of a settled outcome (tests assert the code). */
    private fun DappOutcome.requireError(): WalletBridgeError = checkNotNull(error)

    // ------------------------------------------------------------------
    // Session + lock policy
    // ------------------------------------------------------------------

    @Test
    fun `bind loads persisted wallet state and starts locked`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.EVM, ChainType.SOLANA))
        engine.bind(profile)
        advanceUntilIdle()

        val loaded = checkNotNull(engine.wallet.value)
        assertThat(loaded.profileId).isEqualTo(profile)
        assertThat(engine.accounts.value).hasSize(2)
        assertThat(engine.networks.value).isNotEmpty()
        assertThat(engine.activeNetworks.value[ChainType.EVM]).isNotNull()
        assertThat(engine.lockState.value).isEqualTo(WalletLockState.LOCKED)
    }

    @Test
    fun `no wallet means NO_WALLET regardless of unlock flag`() = runTest(testDispatcher) {
        engine.bind(profile)
        advanceUntilIdle()
        engine.unlock()
        assertThat(engine.lockState.value).isEqualTo(WalletLockState.NO_WALLET)
    }

    @Test
    fun `locked key operations throw until unlock`() = runTest(testDispatcher) {
        engine.bind(profile)
        advanceUntilIdle()
        val created = engine.createWallet("Main", listOf(ChainType.EVM))
        advanceUntilIdle()

        val evmAccount = engine.accounts.value.first { it.chainType == ChainType.EVM }
        val networkId = engine.networks.value.first { it.config.chainType == ChainType.EVM }.config.id

        assertThrowsSuspend<WalletLockedException> { engine.revealMnemonic() }
        assertThrowsSuspend<WalletLockedException> { engine.addDerivedAccount(ChainType.EVM) }
        assertThrowsSuspend<WalletLockedException> {
            engine.importAccount(ChainType.EVM, SEED, "import")
        }
        assertThrowsSuspend<WalletLockedException> {
            engine.sendNative(evmAccount.id, networkId, EVM1, "0.1")
        }

        engine.unlock()
        assertThat(engine.lockState.value).isEqualTo(WalletLockState.UNLOCKED)
        assertThat(engine.revealMnemonic()).isEqualTo(created)

        engine.lock()
        assertThat(engine.lockState.value).isEqualTo(WalletLockState.LOCKED)
        assertThrowsSuspend<WalletLockedException> { engine.revealMnemonic() }
    }

    @Test
    fun `key-only wallet derives nothing and reveals no mnemonic`() = runTest(testDispatcher) {
        fake.createKeylessWallet(profile, "Key only")
        engine.bind(profile)
        advanceUntilIdle()
        engine.unlock()

        assertThat(engine.revealMnemonic()).isNull()
        assertThat(engine.addDerivedAccount(ChainType.EVM)).isNull()
    }

    @Test
    fun `backupContents refuses a locked session unless the caller holds the phrase`() =
        runTest(testDispatcher) {
            seedWallet(profile, ABANDON, listOf(ChainType.EVM))
            engine.bind(profile)
            advanceUntilIdle()

            // Locked, and the phrase is in the vault: reading it here would
            // be the gate leaking.
            assertThrowsSuspend<WalletLockedException> { engine.backupContents() }

            // The onboarding reveal passes the phrase it is already holding.
            // That is the entire reason an export can exist at the one moment
            // the user has the phrase in front of them, while the session is
            // still locked by design.
            val contents = engine.backupContents(ABANDON)
            assertThat(contents.mnemonic).isEqualTo(ABANDON)
            assertThat(contents.accounts.map { it.chain }).contains("EVM")
        }

    @Test
    fun `backupContents carries the phrase and the paths but no derived keys`() =
        runTest(testDispatcher) {
            seedWallet(profile, ABANDON, listOf(ChainType.EVM, ChainType.SOLANA))
            engine.bind(profile)
            advanceUntilIdle()
            engine.unlock()

            val contents = engine.backupContents()

            assertThat(contents.mnemonic).isEqualTo(ABANDON)
            assertThat(contents.walletLabel).isEqualTo("Seeded")
            assertThat(contents.isEmpty).isFalse()
            assertThat(contents.accounts).hasSize(2)
            // A derived key is reproduced by the phrase; writing it into the
            // file would be a second copy of the secret for no benefit.
            assertThat(contents.accounts.map { it.privateKey }).containsExactly(null, null)
            assertThat(contents.accounts.none { it.path.isBlank() }).isTrue()
        }

    @Test
    fun `backupContents includes an imported key, which the phrase cannot restore`() =
        runTest(testDispatcher) {
            seedWallet(profile, ABANDON, listOf(ChainType.EVM))
            engine.bind(profile)
            advanceUntilIdle()
            engine.unlock()
            engine.importAccount(ChainType.EVM, SEED, "imported")
            advanceUntilIdle()

            val imported = engine.backupContents().accounts.single { it.label == "imported" }

            // Nothing re-derives this one: a backup that omitted it would
            // restore a wallet that looks complete and cannot spend from it.
            assertThat(imported.privateKey).isNotNull()
            assertThat(imported.privateKey).isNotEmpty()
            assertThat(imported.path).isEmpty()
        }

    @Test
    fun `backupContents of a key-only wallet has no phrase but keeps its keys`() =
        runTest(testDispatcher) {
            fake.createKeylessWallet(profile, "Key only")
            engine.bind(profile)
            advanceUntilIdle()
            engine.unlock()
            engine.importAccount(ChainType.EVM, SEED, "imported")
            advanceUntilIdle()

            val contents = engine.backupContents()

            assertThat(contents.mnemonic).isNull()
            // Not empty: the imported key IS the backup for this wallet.
            assertThat(contents.isEmpty).isFalse()
        }

    @Test
    fun `createWallet and importWallet fail closed while LOCKED but run on a fresh profile`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        advanceUntilIdle()

        // LOCKED (a wallet exists behind the gate): creating/importing is
        // refused before anything touches the repository.
        assertThrowsSuspend<WalletLockedException> { engine.createWallet("Second", listOf(ChainType.EVM)) }
        assertThrowsSuspend<WalletLockedException> {
            engine.importWallet(ABANDON, "Second", listOf(ChainType.EVM))
        }
        assertThat(fake.accountsOf(profile)).hasSize(1)

        // NO_WALLET is NOT locked — onboarding must be able to create.
        engine.bind(otherProfile)
        advanceUntilIdle()
        val created = engine.createWallet("Fresh", listOf(ChainType.EVM))
        advanceUntilIdle()
        assertThat(Mnemonics.isValid(created)).isTrue()
        assertThat(engine.accounts.value).hasSize(1)
    }

    @Test
    fun `deleting the wallet clears its rows and returns the surface to NO_WALLET`() =
        runTest(testDispatcher) {
            seedWallet(profile, ABANDON, listOf(ChainType.EVM, ChainType.SOLANA))
            seedWallet(otherProfile, ABANDON, listOf(ChainType.EVM))
            engine.bind(profile)
            advanceUntilIdle()
            engine.unlock()
            assertThat(engine.wallet.value).isNotNull()

            engine.deleteWallet()
            advanceUntilIdle()

            assertThat(engine.wallet.value).isNull()
            assertThat(engine.accounts.value).isEmpty()
            assertThat(engine.balances.value).isEmpty()
            assertThat(engine.lockState.value).isEqualTo(WalletLockState.NO_WALLET)
            assertThat(fake.wallet(profile)).isNull()
            assertThat(fake.accountsOf(profile)).isEmpty()
            // Profile-scoped: the sibling profile's wallet is not collateral.
            assertThat(fake.wallet(otherProfile)).isNotNull()
            assertThat(fake.accountsOf(otherProfile)).hasSize(1)
        }

    @Test
    fun `delete is refused while the session is locked`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        advanceUntilIdle()

        // Irreversible and key-destroying, so the UI's confirmation must not
        // be the only thing between a locked session and a wiped wallet.
        assertThrowsSuspend<WalletLockedException> { engine.deleteWallet() }
        assertThat(fake.wallet(profile)).isNotNull()
        assertThat(fake.accountsOf(profile)).hasSize(1)
    }

    // ------------------------------------------------------------------
    // Wallet lifecycle + derivation vectors
    // ------------------------------------------------------------------

    @Test
    fun `createWallet derives index-0 accounts for every enabled chain`() = runTest(testDispatcher) {
        engine.bind(profile)
        advanceUntilIdle()
        val mnemonic = engine.createWallet("All chains", ChainType.entries.toList())
        advanceUntilIdle()

        assertThat(Mnemonics.isValid(mnemonic)).isTrue()
        assertThat(mnemonic.split(" ")).hasSize(24)
        val byChain = engine.accounts.value.associateBy { it.chainType }
        assertThat(byChain).hasSize(8)

        // The index-0 address must equal what the REAL adapter derives from
        // the returned mnemonic (pins the whole path/key handling per chain).
        val seed = Mnemonics.toSeed(mnemonic)
        val real = ChainRegistry()
        assertThat(byChain.getValue(ChainType.EVM).address)
            .isEqualTo(real.evm.deriveAccount(seed, 0).address)
        assertThat(byChain.getValue(ChainType.EVM).path).isEqualTo("m/44'/60'/0'/0/0")
        assertThat(byChain.getValue(ChainType.EVM).label).isEqualTo("EVM 1")
        assertThat(byChain.getValue(ChainType.SOLANA).address)
            .isEqualTo(real.solana.deriveAccount(seed, 0).address)
        assertThat(byChain.getValue(ChainType.SOLANA).path).isEqualTo("m/44'/501'/0'/0'")
        assertThat(byChain.getValue(ChainType.APTOS).address)
            .isEqualTo(real.aptos.deriveAccount(seed, 0).address)
        assertThat(byChain.getValue(ChainType.APTOS).path).isEqualTo("m/54'/6'/0'/0'/0'")
        assertThat(byChain.getValue(ChainType.SUI).address)
            .isEqualTo(real.sui.deriveAccount(seed, 0).address)
        assertThat(byChain.getValue(ChainType.SUI).path).isEqualTo("m/44'/784'/0'/0'/0'")
        assertThat(byChain.getValue(ChainType.COSMOS).address)
            .isEqualTo(real.cosmos.deriveAccount(seed, real.defaultNetworks(ChainType.COSMOS).first(), 0).address)
        assertThat(byChain.getValue(ChainType.COSMOS).path).isEqualTo("m/44'/118'/0'/0/0")
        assertThat(byChain.getValue(ChainType.BITCOIN).address)
            .isEqualTo(real.bitcoin.deriveAccount(seed, real.defaultNetworks(ChainType.BITCOIN).first(), 0).address)
        assertThat(byChain.getValue(ChainType.BITCOIN).path).isEqualTo("m/84'/0'/0'/0/0")
        assertThat(byChain.getValue(ChainType.TRON).address)
            .isEqualTo(real.tron.deriveAccount(seed, 0).address)
        assertThat(byChain.getValue(ChainType.TRON).path).isEqualTo("m/44'/195'/0'/0/0")
        assertThat(byChain.getValue(ChainType.OCTRA).address)
            .isEqualTo(real.octra.deriveAccount(seed, 0).address)
        assertThat(byChain.getValue(ChainType.OCTRA).path).isEqualTo("octra/0")
        assertThat(byChain.getValue(ChainType.EVM).source).isEqualTo(WalletAccountRecord.Source.DERIVED)
        assertThat(checkNotNull(engine.wallet.value).hasMnemonic).isTrue()
        assertThat(engine.lockState.value).isEqualTo(WalletLockState.LOCKED)
    }

    @Test
    fun `importWallet rejects an invalid mnemonic and persists nothing`() = runTest(testDispatcher) {
        engine.bind(profile)
        advanceUntilIdle()
        assertThrowsSuspend<WalletException.InvalidParams> {
            engine.importWallet("definitely not a mnemonic", "X", listOf(ChainType.EVM))
        }
        advanceUntilIdle()
        assertThat(fake.wallets(profile)).isNull()
        assertThat(engine.accounts.value).isEmpty()
    }

    @Test
    fun `importWallet derives from the given mnemonic`() = runTest(testDispatcher) {
        engine.bind(profile)
        advanceUntilIdle()
        engine.importWallet(ABANDON, "Imported", listOf(ChainType.EVM, ChainType.BITCOIN))
        advanceUntilIdle()

        val byChain = engine.accounts.value.associateBy { it.chainType }
        assertThat(byChain.getValue(ChainType.EVM).address).isEqualTo(EVM0)
        assertThat(byChain.getValue(ChainType.BITCOIN).address).isEqualTo(BTC0)
        assertThat(checkNotNull(engine.wallet.value).hasMnemonic).isTrue()
    }

    @Test
    fun `importWallet derives the golden index-0 addresses from the fixed mnemonic`() = runTest(testDispatcher) {
        engine.bind(profile)
        advanceUntilIdle()
        engine.importWallet(ABANDON, "Imported", ChainType.entries.toList())
        advanceUntilIdle()

        val byChain = engine.accounts.value.associateBy { it.chainType }
        assertThat(byChain).hasSize(8)
        assertThat(byChain.getValue(ChainType.EVM).address).isEqualTo(EVM0)
        assertThat(byChain.getValue(ChainType.SOLANA).address).isEqualTo(SOL0)
        assertThat(byChain.getValue(ChainType.APTOS).address).isEqualTo(APTOS0)
        assertThat(byChain.getValue(ChainType.SUI).address).isEqualTo(SUI0)
        assertThat(byChain.getValue(ChainType.COSMOS).address).isEqualTo(COSMOS0)
        assertThat(byChain.getValue(ChainType.BITCOIN).address).isEqualTo(BTC0)
        assertThat(byChain.getValue(ChainType.TRON).address).isEqualTo(TRON0)
        assertThat(byChain.getValue(ChainType.OCTRA).address).isEqualTo(OCTRA0)
        assertThat(checkNotNull(engine.wallet.value).hasMnemonic).isTrue()
    }

    @Test
    fun `addDerivedAccount uses the next free BIP44 index`() = runTest(testDispatcher) {
        engine.bind(profile)
        advanceUntilIdle()
        engine.importWallet(ABANDON, "Main", listOf(ChainType.EVM))
        advanceUntilIdle()
        engine.unlock()

        val second = checkNotNull(engine.addDerivedAccount(ChainType.EVM))
        assertThat(second.address).isEqualTo(EVM1)
        assertThat(second.path).isEqualTo("m/44'/60'/0'/0/1")
        assertThat(second.label).isEqualTo("EVM 2")
    }

    @Test
    fun `importAccount parses per-chain keys and stores the canonical form`() = runTest(testDispatcher) {
        engine.bind(profile)
        advanceUntilIdle()
        engine.createWallet("Main", listOf(ChainType.EVM))
        advanceUntilIdle()
        engine.unlock()

        val evm = checkNotNull(engine.importAccount(ChainType.EVM, "0x$SEED", "evm import"))
        assertThat(evm.address).isEqualTo(EVM_IMPORT)
        assertThat(fake.revealPrivateKey(evm.id)).isEqualTo(SEED)

        val sol = checkNotNull(engine.importAccount(ChainType.SOLANA, SEED_B58, "sol import"))
        assertThat(sol.address).isEqualTo(SOL_IMPORT)
        assertThat(fake.revealPrivateKey(sol.id)).isEqualTo(SEED_B58)

        val aptos = checkNotNull(engine.importAccount(ChainType.APTOS, "0x$SEED", "apt import"))
        assertThat(aptos.address).isEqualTo(APTOS_IMPORT)

        val sui = checkNotNull(engine.importAccount(ChainType.SUI, SEED, "sui import"))
        assertThat(sui.address).isEqualTo(SUI_IMPORT)

        val tron = checkNotNull(engine.importAccount(ChainType.TRON, SEED, "tron import"))
        assertThat(tron.address).isEqualTo(TRON_IMPORT)

        assertThat(evm.source).isEqualTo(WalletAccountRecord.Source.IMPORTED)
        assertThat(evm.path).isEmpty()

        assertThrowsSuspend<WalletException.InvalidParams> {
            engine.importAccount(ChainType.EVM, "zzz-not-hex", "bad")
        }
        assertThrowsSuspend<WalletException.InvalidParams> {
            engine.importAccount(ChainType.SOLANA, "too-short-for-a-key", "bad")
        }
    }

    // ------------------------------------------------------------------
    // Collector hygiene (bind / unbind)
    // ------------------------------------------------------------------

    @Test
    fun `re-bind cancels the previous collector generation`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        seedWallet(otherProfile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        advanceUntilIdle()
        assertThat(fake.activeAccountObservers).isEqualTo(1)
        engine.unlock()

        engine.bind(otherProfile)
        advanceUntilIdle()
        // Exactly one live generation; the old profile's collector is gone.
        assertThat(fake.activeAccountObservers).isEqualTo(1)
        // Re-bind resets the session lock.
        assertThat(engine.lockState.value).isEqualTo(WalletLockState.LOCKED)

        // Pushing to the OLD profile's flow must not leak into engine state.
        val stale = fake.accountsOf(profile).first().copy(label = "stale")
        fake.pushAccounts(profile, listOf(stale))
        advanceUntilIdle()
        assertThat(engine.accounts.value).doesNotContain(stale)
        assertThat(engine.accounts.value).containsExactlyElementsIn(fake.accountsOf(otherProfile))
    }

    @Test
    fun `unbind empties all state and settles pending with DISCONNECTED`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        advanceUntilIdle()

        val outcomes = mutableListOf<DappOutcome>()
        engine.submitDappRequest(
            DappRequest.Connect("c1", "app.example.org", ChainType.EVM, "https://app.example.org")
        ) { outcomes += it }
        engine.submitDappRequest(
            DappRequest.SignMessage("m1", "app.example.org", ChainType.EVM, EVM0, "hello world", "hello world")
        ) { outcomes += it }
        advanceUntilIdle()
        assertThat(engine.pendingRequests.value).hasSize(2)

        engine.unbind()
        advanceUntilIdle()

        assertThat(engine.lockState.value).isEqualTo(WalletLockState.NO_WALLET)
        assertThat(engine.accounts.value).isEmpty()
        assertThat(engine.networks.value).isEmpty()
        assertThat(engine.pendingRequests.value).isEmpty()
        assertThat(fake.activeAccountObservers).isEqualTo(0)
        assertThat(outcomes).hasSize(2)
        assertThat(outcomes.all { it.error?.code == WalletBridgeError.DISCONNECTED }).isTrue()
    }

    // ------------------------------------------------------------------
    // dApp queue
    // ------------------------------------------------------------------

    @Test
    fun `Connect approve grants permission and settles with the address array`() = runTest(testDispatcher) {
        // mockk spy around the fake pins the exact grant call.
        val spy = spyk(fake)
        val spied = TestWalletEngine(spy)
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        spied.bind(profile)
        advanceUntilIdle()

        val outcomes = mutableListOf<DappOutcome>()
        spied.submitDappRequest(
            DappRequest.Connect("c1", "app.uniswap.org", ChainType.EVM, "https://app.uniswap.org")
        ) { outcomes += it }
        advanceUntilIdle()
        assertThat(spied.pendingRequests.value).hasSize(1)

        spied.decideDappRequest(DappDecision("c1", approved = true))
        advanceUntilIdle()

        assertThat(outcomes).hasSize(1)
        assertThat(outcomes[0].error).isNull()
        assertThat(outcomes[0].resultJson).isEqualTo("""["$EVM0"]""")
        assertThat(spied.pendingRequests.value).isEmpty()

        coVerify {
            spy.grantDappPermission(
                profile, "app.uniswap.org", ChainType.EVM, EVM0,
                withArg<List<String>> { methods ->
                    assertThat(methods).containsAtLeast("personal_sign", "eth_sendTransaction")
                }
            )
        }
        assertThat(spied.isDappPermitted("app.uniswap.org", ChainType.EVM, EVM0, "personal_sign")).isTrue()
        assertThat(spied.isDappPermitted("other.host", ChainType.EVM, EVM0, "personal_sign")).isFalse()
    }

    @Test
    fun `Connect reject settles USER_REJECTED and grants nothing`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        advanceUntilIdle()

        val outcomes = mutableListOf<DappOutcome>()
        engine.submitDappRequest(
            DappRequest.Connect("c1", "app.uniswap.org", ChainType.EVM, "https://app.uniswap.org")
        ) { outcomes += it }
        engine.decideDappRequest(DappDecision("c1", approved = false))
        advanceUntilIdle()

        assertThat(outcomes).hasSize(1)
        assertThat(outcomes[0].requireError().code).isEqualTo(WalletBridgeError.USER_REJECTED)
        assertThat(outcomes[0].resultJson).isNull()
        assertThat(fake.allDappPermissions(profile)).isEmpty()
    }

    @Test
    fun `Connect without a matching account settles UNAUTHORIZED`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        advanceUntilIdle()

        val outcomes = mutableListOf<DappOutcome>()
        engine.submitDappRequest(
            DappRequest.Connect("c1", "app.uniswap.org", ChainType.SOLANA, "https://app.uniswap.org")
        ) { outcomes += it }
        engine.decideDappRequest(DappDecision("c1", approved = true))
        advanceUntilIdle()

        assertThat(outcomes).hasSize(1)
        assertThat(outcomes[0].requireError().code).isEqualTo(WalletBridgeError.UNAUTHORIZED)
    }

    @Test
    fun `duplicate semantics coalesce into one prompt and settle together`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        advanceUntilIdle()

        val outcomes = mutableListOf<DappOutcome>()
        engine.submitDappRequest(
            DappRequest.Connect("r1", "app.uniswap.org", ChainType.EVM, "https://app.uniswap.org")
        ) { outcomes += it }
        engine.submitDappRequest(
            DappRequest.Connect("r2", "app.uniswap.org", ChainType.EVM, "https://app.uniswap.org")
        ) { outcomes += it }
        advanceUntilIdle()
        // Only ONE prompt for two identical Connect requests.
        assertThat(engine.pendingRequests.value).hasSize(1)
        assertThat(engine.pendingRequests.value[0].id).isEqualTo("r1")

        engine.decideDappRequest(DappDecision("r1", approved = true))
        advanceUntilIdle()

        assertThat(outcomes).hasSize(2)
        assertThat(outcomes.map { it.requestId }).containsExactly("r1", "r2").inOrder()
        assertThat(outcomes[0].resultJson).isEqualTo(outcomes[1].resultJson)
        assertThat(outcomes[0].error).isNull()
        assertThat(outcomes[1].error).isNull()
    }

    @Test
    fun `re-submitting a pending id replaces the callback and never duplicates the prompt`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        advanceUntilIdle()

        val first = mutableListOf<DappOutcome>()
        val second = mutableListOf<DappOutcome>()
        val connect = DappRequest.Connect("c1", "app.uniswap.org", ChainType.EVM, "https://app.uniswap.org")
        engine.submitDappRequest(connect) { first += it }
        engine.submitDappRequest(connect) { second += it }
        advanceUntilIdle()

        // One queue entry — the replaced callback owns the settle.
        assertThat(engine.pendingRequests.value).hasSize(1)
        assertThat(engine.pendingRequests.value[0].id).isEqualTo("c1")

        engine.decideDappRequest(DappDecision("c1", approved = false))
        advanceUntilIdle()

        assertThat(first).isEmpty()
        assertThat(second).hasSize(1)
        assertThat(second[0].requireError().code).isEqualTo(WalletBridgeError.USER_REJECTED)
        assertThat(engine.pendingRequests.value).isEmpty()
    }

    @Test
    fun `re-bind settles pending requests with DISCONNECTED and the queue works again`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        seedWallet(otherProfile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        advanceUntilIdle()

        val outcomes = mutableListOf<DappOutcome>()
        engine.submitDappRequest(
            DappRequest.Connect("c1", "app.uniswap.org", ChainType.EVM, "https://app.uniswap.org")
        ) { outcomes += it }
        advanceUntilIdle()
        assertThat(engine.pendingRequests.value).hasSize(1)

        engine.bind(otherProfile)
        advanceUntilIdle()

        assertThat(outcomes).hasSize(1)
        assertThat(outcomes[0].requireError().code).isEqualTo(WalletBridgeError.DISCONNECTED)
        assertThat(engine.pendingRequests.value).isEmpty()

        // The queue is fresh for the new profile: a new prompt settles normally.
        engine.submitDappRequest(
            DappRequest.Connect("c2", "app.uniswap.org", ChainType.EVM, "https://app.uniswap.org")
        ) { outcomes += it }
        engine.decideDappRequest(DappDecision("c2", approved = true))
        advanceUntilIdle()

        assertThat(outcomes.filter { it.error?.code == WalletBridgeError.DISCONNECTED }).hasSize(1)
        assertThat(outcomes.last().error).isNull()
    }

    @Test
    fun `Connect settles the per-chain result shapes the bridge auto-approve uses`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.SOLANA, ChainType.TRON))
        engine.bind(profile)
        advanceUntilIdle()

        val outcomes = mutableListOf<DappOutcome>()
        engine.submitDappRequest(
            DappRequest.Connect("s1", "app.example.org", ChainType.SOLANA, "https://app.example.org")
        ) { outcomes += it }
        engine.submitDappRequest(
            DappRequest.Connect("t1", "app.example.org", ChainType.TRON, "https://app.example.org")
        ) { outcomes += it }
        advanceUntilIdle()
        engine.decideDappRequest(DappDecision("s1", approved = true))
        engine.decideDappRequest(DappDecision("t1", approved = true))
        advanceUntilIdle()

        // SOLANA resolves {"publicKey": …} and TRON {"address": …} — exactly
        // WalletBridgeProtocol.connectSuccessResult's shapes, so prompted and
        // silently auto-approved connects agree on every chain.
        assertThat(outcomes).hasSize(2)
        assertThat(outcomes[0].error).isNull()
        assertThat(outcomes[0].resultJson).isEqualTo("""{"publicKey":"$SOL0"}""")
        assertThat(outcomes[1].error).isNull()
        assertThat(outcomes[1].resultJson).isEqualTo("""{"address":"$TRON0"}""")
    }

    /**
     * The property a dApp depends on is that the key it is handed is the key
     * BEHIND the address it was given, so each case is checked by round trip
     * through the same adapter that produced the address — not against a
     * second copy of the expected hex, which would only prove the test and
     * the engine share an assumption.
     */
    @Test
    fun `publicKeyOf derives the key for the chains that publish one and null elsewhere`() =
        runTest(testDispatcher) {
            seedWallet(
                profile,
                ABANDON,
                listOf(
                    ChainType.COSMOS, ChainType.APTOS, ChainType.BITCOIN, ChainType.OCTRA,
                    ChainType.EVM, ChainType.SOLANA, ChainType.TRON
                )
            )
            engine.bind(profile)
            advanceUntilIdle()

            fun accountOf(chain: ChainType) =
                engine.accounts.value.first { it.chainType == chain }

            val cosmosKey = engine.publicKeyOf(accountOf(ChainType.COSMOS).id)
            assertThat(cosmosKey).isNotNull()
            // Compressed secp256k1: 33 bytes, and the same key the bech32
            // address was built from (which is what makes it usable in a
            // CosmJS sign doc).
            assertThat(Hex.decode(cosmosKey!!).size).isEqualTo(33)
            assertThat(CosmosAdapter().bech32Address(Hex.decode(cosmosKey), "cosmos"))
                .isEqualTo(COSMOS0)

            val aptosKey = engine.publicKeyOf(accountOf(ChainType.APTOS).id)
            assertThat(aptosKey).isNotNull()
            assertThat(Hex.decode(aptosKey!!).size).isEqualTo(32)
            // Aptos derives the address as sha3_256(pubkey || 0x00).
            assertThat("0x" + Hex.encode(Hashes.sha3_256(Hex.decode(aptosKey) + byteArrayOf(0))))
                .isEqualTo(APTOS0)

            val bitcoinKey = engine.publicKeyOf(accountOf(ChainType.BITCOIN).id)
            assertThat(bitcoinKey).isNotNull()
            assertThat(Hex.decode(bitcoinKey!!).size).isEqualTo(33)
            assertThat(BitcoinAdapter().p2wpkhAddress(Hex.decode(bitcoinKey), testnet = false))
                .isEqualTo(BTC0)

            val octraKey = engine.publicKeyOf(accountOf(ChainType.OCTRA).id)
            assertThat(octraKey).isNotNull()
            assertThat(Hex.decode(octraKey!!).size).isEqualTo(32)
            // The address is base58(sha256(pubkey)), so round-tripping it also
            // pins the HMAC master key behind octraSeed() — the vault read,
            // not just the adapter.
            assertThat(OctraAdapter().addressFromPublicKey(Hex.decode(octraKey)))
                .isEqualTo(OCTRA0)

            // Nothing is published on these. EVM matches MetaMask (EIP-1193
            // carries addresses only), Solana's address IS its public key and
            // is already in the connect result, and TRON's convention has no
            // such call.
            assertThat(engine.publicKeyOf(accountOf(ChainType.EVM).id)).isNull()
            assertThat(engine.publicKeyOf(accountOf(ChainType.SOLANA).id)).isNull()
            assertThat(engine.publicKeyOf(accountOf(ChainType.TRON).id)).isNull()
            assertThat(engine.publicKeyOf("no-such-account")).isNull()
        }

    @Test
    fun `SignMessage while locked settles UNAUTHORIZED`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        advanceUntilIdle()

        val outcomes = mutableListOf<DappOutcome>()
        engine.submitDappRequest(
            DappRequest.SignMessage("m1", "app.uniswap.org", ChainType.EVM, EVM0, "hello world", "hello world")
        ) { outcomes += it }
        engine.decideDappRequest(DappDecision("m1", approved = true))
        advanceUntilIdle()

        assertThat(outcomes).hasSize(1)
        assertThat(outcomes[0].requireError().code).isEqualTo(WalletBridgeError.UNAUTHORIZED)
        assertThat(outcomes[0].requireError().message).contains("Unlock")
    }

    @Test
    fun `SignMessage unlocked settles the deterministic signature and records activity`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        advanceUntilIdle()
        engine.unlock()

        val outcomes = mutableListOf<DappOutcome>()
        engine.submitDappRequest(
            DappRequest.SignMessage("m1", "app.uniswap.org", ChainType.EVM, EVM0, "hello world", "hello world")
        ) { outcomes += it }
        engine.decideDappRequest(DappDecision("m1", approved = true))
        advanceUntilIdle()

        assertThat(outcomes).hasSize(1)
        assertThat(outcomes[0].error).isNull()
        assertThat(outcomes[0].resultJson).isEqualTo("\"$EVM0_PERSONAL_SIGN\"")
        assertThat(engine.activities.value).hasSize(1)
        val activity = engine.activities.value[0]
        assertThat(activity.kind).isEqualTo(WalletActivityRecord.Kind.SIGN_MESSAGE)
        assertThat(activity.accountAddress).isEqualTo(EVM0)
        assertThat(activity.createdAt).isEqualTo(TEST_NOW)
    }

    @Test
    fun `SignTypedData is EVM-only`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.SOLANA))
        engine.bind(profile)
        advanceUntilIdle()
        engine.unlock()

        val outcomes = mutableListOf<DappOutcome>()
        engine.submitDappRequest(
            DappRequest.SignTypedData("t1", "app.uniswap.org", ChainType.SOLANA, SOL0, "{}")
        ) { outcomes += it }
        engine.decideDappRequest(DappDecision("t1", approved = true))
        advanceUntilIdle()

        assertThat(outcomes).hasSize(1)
        assertThat(outcomes[0].requireError().code).isEqualTo(WalletBridgeError.UNSUPPORTED_METHOD)
    }

    @Test
    fun `SignTypedData signs EIP-712 data and records the activity`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        advanceUntilIdle()
        engine.unlock()

        val outcomes = mutableListOf<DappOutcome>()
        engine.submitDappRequest(
            DappRequest.SignTypedData("t1", "app.uniswap.org", ChainType.EVM, EVM0, TYPED_DATA)
        ) { outcomes += it }
        engine.decideDappRequest(DappDecision("t1", approved = true))
        advanceUntilIdle()

        assertThat(outcomes).hasSize(1)
        assertThat(outcomes[0].error).isNull()
        assertThat(outcomes[0].resultJson).isEqualTo("\"$EVM0_TYPED_SIGNATURE\"")
        assertThat(engine.activities.value).hasSize(1)
        val activity = engine.activities.value[0]
        assertThat(activity.kind).isEqualTo(WalletActivityRecord.Kind.SIGN_MESSAGE)
        assertThat(activity.accountAddress).isEqualTo(EVM0)
        assertThat(activity.displayAmount).isEqualTo("typed data")
        assertThat(activity.createdAt).isEqualTo(TEST_NOW)
    }

    @Test
    fun `SendTransaction happy path settles the hash and records DAPP_SEND`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        advanceUntilIdle()
        engine.unlock()

        val params = """{"to":"$EVM1","value":"0xde0b6b3a7640000","gas":"21000","gasPrice":"0x3b9aca00"}"""
        val outcomes = mutableListOf<DappOutcome>()
        engine.submitDappRequest(
            DappRequest.SendTransaction(
                "s1", "app.uniswap.org", ChainType.EVM, "EVM:1", EVM0, params, null
            )
        ) { outcomes += it }
        engine.decideDappRequest(DappDecision("s1", approved = true))
        advanceUntilIdle()

        assertThat(outcomes).hasSize(1)
        assertThat(outcomes[0].error).isNull()
        assertThat(outcomes[0].resultJson).isEqualTo("\"0xdapphash\"")
        assertThat(engine.activities.value).hasSize(1)
        val activity = engine.activities.value[0]
        assertThat(activity.kind).isEqualTo(WalletActivityRecord.Kind.DAPP_SEND)
        assertThat(activity.hash).isEqualTo("0xdapphash")
        assertThat(activity.toAddress).isEqualTo(EVM1)
        assertThat(activity.displayAmount).contains("1 ETH")
        assertThat(activity.displayAmount).contains("0.001 ETH gas")
        assertThat(activity.explorerUrl).isEqualTo("https://etherscan.io/tx/0xdapphash")
    }

    @Test
    fun `SendTransaction sign failure settles INTERNAL and never throws`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        advanceUntilIdle()
        engine.unlock()
        engine.dappSendError = WalletException.NetworkUnavailable("offline")

        val outcomes = mutableListOf<DappOutcome>()
        engine.submitDappRequest(
            DappRequest.SendTransaction(
                "s1", "app.uniswap.org", ChainType.EVM, "EVM:1", EVM0, """{"to":"$EVM1"}""", null
            )
        ) { outcomes += it }
        engine.decideDappRequest(DappDecision("s1", approved = true))
        advanceUntilIdle()

        assertThat(outcomes).hasSize(1)
        assertThat(outcomes[0].requireError().code).isEqualTo(WalletBridgeError.INTERNAL)
        assertThat(outcomes[0].requireError().message).contains("offline")
        assertThat(engine.pendingRequests.value).isEmpty()
    }

    @Test
    fun `SendTransaction while locked settles UNAUTHORIZED`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        advanceUntilIdle()

        val outcomes = mutableListOf<DappOutcome>()
        engine.submitDappRequest(
            DappRequest.SendTransaction(
                "s1", "app.uniswap.org", ChainType.EVM, "EVM:1", EVM0, """{"to":"$EVM1"}""", null
            )
        ) { outcomes += it }
        engine.decideDappRequest(DappDecision("s1", approved = true))
        advanceUntilIdle()

        assertThat(outcomes).hasSize(1)
        assertThat(outcomes[0].requireError().code).isEqualTo(WalletBridgeError.UNAUTHORIZED)
    }

    @Test
    fun `SwitchChain unknown or disabled settles UNRECOGNIZED_CHAIN`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        advanceUntilIdle()

        val outcomes = mutableListOf<DappOutcome>()
        engine.submitDappRequest(
            DappRequest.SwitchChain("w1", "app.uniswap.org", ChainType.EVM, "EVM:999999")
        ) { outcomes += it }
        engine.submitDappRequest(
            DappRequest.SwitchChain("w2", "app.uniswap.org", ChainType.EVM, "EVM:137")
        ) { outcomes += it }
        engine.decideDappRequest(DappDecision("w1", approved = true))
        engine.decideDappRequest(DappDecision("w2", approved = true))
        advanceUntilIdle()

        assertThat(outcomes).hasSize(2)
        assertThat(outcomes[0].requireError().code).isEqualTo(WalletBridgeError.UNRECOGNIZED_CHAIN)
        // EVM:137 (Polygon) is seeded but DISABLED by default.
        assertThat(outcomes[1].requireError().code).isEqualTo(WalletBridgeError.UNRECOGNIZED_CHAIN)
        // The active EVM network is untouched by the failed switches.
        assertThat(engine.activeNetworks.value.getValue(ChainType.EVM).id).isEqualTo("EVM:1")
    }

    @Test
    fun `SwitchChain to an enabled network switches and settles null`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        advanceUntilIdle()
        fake.setNetworkEnabledForTests(profile, "EVM:137", true)
        advanceUntilIdle()

        val outcomes = mutableListOf<DappOutcome>()
        engine.submitDappRequest(
            DappRequest.SwitchChain("w1", "app.uniswap.org", ChainType.EVM, "EVM:137")
        ) { outcomes += it }
        // A switch that CAN happen still asks: the prompt is the point, and
        // only the two settled-without-a-sheet cases above skip it.
        assertThat(engine.pendingRequests.value.map { it.id }).containsExactly("w1")
        engine.decideDappRequest(DappDecision("w1", approved = true))
        advanceUntilIdle()

        assertThat(outcomes).hasSize(1)
        assertThat(outcomes[0].error).isNull()
        assertThat(outcomes[0].resultJson).isNull()
        assertThat(engine.activeNetworks.value[ChainType.EVM]).isNotNull()
        assertThat(engine.activeNetworks.value.getValue(ChainType.EVM).id).isEqualTo("EVM:137")
    }

    @Test
    fun `SwitchChain to an unserved network settles 4902 without a sheet`() = runTest(testDispatcher) {
        // A prompt for a chain the wallet cannot switch to has one possible
        // result, and its Reject would settle 4001 instead.
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        advanceUntilIdle()

        val outcomes = mutableListOf<DappOutcome>()
        engine.submitDappRequest(
            DappRequest.SwitchChain("w1", "app.uniswap.org", ChainType.EVM, "EVM:999999")
        ) { outcomes += it }
        advanceUntilIdle()

        assertThat(engine.pendingRequests.value).isEmpty()
        assertThat(outcomes).hasSize(1)
        assertThat(outcomes[0].requireError().code).isEqualTo(WalletBridgeError.UNRECOGNIZED_CHAIN)
    }

    @Test
    fun `SwitchChain to a disabled network settles 4902 without a sheet`() = runTest(testDispatcher) {
        // Bundled testnets are seeded DISABLED — exactly the state a dApp on
        // Sepolia switches into, where 4902 sends it to addEthereumChain.
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        advanceUntilIdle()

        val outcomes = mutableListOf<DappOutcome>()
        engine.submitDappRequest(
            DappRequest.SwitchChain("w1", "app.uniswap.org", ChainType.EVM, "EVM:137")
        ) { outcomes += it }
        advanceUntilIdle()

        assertThat(engine.pendingRequests.value).isEmpty()
        assertThat(outcomes).hasSize(1)
        assertThat(outcomes[0].requireError().code).isEqualTo(WalletBridgeError.UNRECOGNIZED_CHAIN)
    }

    @Test
    fun `SwitchChain to the active network settles null without a sheet`() = runTest(testDispatcher) {
        // Switching to the chain you are already on changes nothing, and the
        // Reject that sheet offered failed a connect that had succeeded.
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        advanceUntilIdle()

        val outcomes = mutableListOf<DappOutcome>()
        engine.submitDappRequest(
            DappRequest.SwitchChain("w1", "app.uniswap.org", ChainType.EVM, "EVM:1")
        ) { outcomes += it }
        advanceUntilIdle()

        assertThat(engine.pendingRequests.value).isEmpty()
        assertThat(outcomes).hasSize(1)
        assertThat(outcomes[0].error).isNull()
        assertThat(engine.activeNetworks.value.getValue(ChainType.EVM).id).isEqualTo("EVM:1")
    }

    @Test
    fun `AddChain validates before storing`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        advanceUntilIdle()

        val valid = NetworkConfig.evm(9999, "Custom Net", listOf("https://rpc.custom"), "CST", null)
        listOf(
            valid.copy(name = " "),
            valid.copy(rpcUrls = listOf("ftp://nope")),
            valid.copy(nativeDecimals = 0),
            valid.copy(chainId = "not-a-number")
        ).forEachIndexed { index, proposed ->
            val outcomes = mutableListOf<DappOutcome>()
            engine.submitDappRequest(
                DappRequest.AddChain("a$index", "app.uniswap.org", ChainType.EVM, proposed)
            ) { outcomes += it }
            engine.decideDappRequest(DappDecision("a$index", approved = true))
            advanceUntilIdle()
            assertThat(outcomes[0].requireError().code).isEqualTo(WalletBridgeError.INVALID_PARAMS)
        }

        val outcomes = mutableListOf<DappOutcome>()
        engine.submitDappRequest(
            DappRequest.AddChain("a9", "app.uniswap.org", ChainType.EVM, valid)
        ) { outcomes += it }
        engine.decideDappRequest(DappDecision("a9", approved = true))
        advanceUntilIdle()

        assertThat(outcomes[0].error).isNull()
        assertThat(outcomes[0].resultJson).isNull()
        assertThat(fake.networks(profile).map { it.config.id }).contains("EVM:9999")
    }

    @Test
    fun `AddChain selects the network it just added`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        advanceUntilIdle()
        assertThat(engine.activeNetworks.value.getValue(ChainType.EVM).id).isEqualTo("EVM:1")

        // Sepolia is bundled but seeded DISABLED (only a family's first network
        // is enabled), so this is exactly the state a dApp reaches after
        // wallet_switchEthereumChain answers 4902 — and the only path by which
        // a bundled testnet can become active at all.
        val sepolia = NetworkConfig.evm(
            11155111, "Ethereum Sepolia",
            listOf("https://ethereum-sepolia-rpc.publicnode.com"), "ETH",
            "https://sepolia.etherscan.io", testnet = true
        )
        assertThat(fake.networks(profile).first { it.config.id == "EVM:11155111" }.enabled).isFalse()

        val outcomes = mutableListOf<DappOutcome>()
        engine.submitDappRequest(
            DappRequest.AddChain("s1", "app.uniswap.org", ChainType.EVM, sepolia)
        ) { outcomes += it }
        engine.decideDappRequest(DappDecision("s1", approved = true))
        advanceUntilIdle()

        assertThat(outcomes[0].error).isNull()
        assertThat(engine.activeNetworks.value.getValue(ChainType.EVM).id)
            .isEqualTo("EVM:11155111")
    }

    @Test
    fun `a rejected AddChain changes nothing`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        advanceUntilIdle()

        val sepolia = NetworkConfig.evm(
            11155111, "Ethereum Sepolia",
            listOf("https://ethereum-sepolia-rpc.publicnode.com"), "ETH",
            "https://sepolia.etherscan.io", testnet = true
        )
        val outcomes = mutableListOf<DappOutcome>()
        engine.submitDappRequest(
            DappRequest.AddChain("s2", "app.uniswap.org", ChainType.EVM, sepolia)
        ) { outcomes += it }
        engine.decideDappRequest(DappDecision("s2", approved = false))
        advanceUntilIdle()

        assertThat(outcomes[0].requireError().code).isEqualTo(WalletBridgeError.USER_REJECTED)
        assertThat(engine.activeNetworks.value.getValue(ChainType.EVM).id).isEqualTo("EVM:1")
        assertThat(fake.networks(profile).first { it.config.id == "EVM:11155111" }.enabled).isFalse()
    }

    @Test
    fun `a proposed chain may not smuggle a plaintext endpoint in beside a TLS one`() =
        runTest(testDispatcher) {
            seedWallet(profile, ABANDON, listOf(ChainType.EVM))
            engine.bind(profile)
            advanceUntilIdle()

            // The old check was `any { it.startsWith("https://") }`, so this
            // list passed on the strength of its SECOND url — while
            // RpcEndpointChain tries the list in order and would therefore have
            // sent every balance read, fee estimate and signed transaction to
            // the plaintext host first. One good url made the whole list legal.
            val mixed = NetworkConfig.evm(
                9998, "Mixed",
                listOf("http://plain.example", "https://rpc.good"), "MIX", null
            )
            val outcomes = mutableListOf<DappOutcome>()
            engine.submitDappRequest(
                DappRequest.AddChain("m1", "app.uniswap.org", ChainType.EVM, mixed)
            ) { outcomes += it }
            engine.decideDappRequest(DappDecision("m1", approved = true))
            advanceUntilIdle()

            assertThat(outcomes[0].requireError().code).isEqualTo(WalletBridgeError.INVALID_PARAMS)
            assertThat(fake.networks(profile).map { it.config.id }).doesNotContain("EVM:9998")
        }

    @Test
    fun `decide on an unknown id is a silent no-op`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        advanceUntilIdle()

        engine.submitDappRequest(
            DappRequest.Connect("c1", "app.uniswap.org", ChainType.EVM, "https://app.uniswap.org")
        ) { }
        engine.decideDappRequest(DappDecision("does-not-exist", approved = true))
        engine.decideDappRequest(DappDecision("c1", approved = false))
        // Deciding the same id twice settles exactly once.
        engine.decideDappRequest(DappDecision("c1", approved = true))
        advanceUntilIdle()

        assertThat(engine.pendingRequests.value).isEmpty()
    }

    @Test
    fun `a request raised before the first bind waits for it and is then answered`() =
        runTest(testDispatcher) {
            // The engine binds 2.5 s after startup on purpose — the bind
            // class-loads the crypto stack and doing it inline stalled first
            // paint — so a page that connects on load lands here. The old
            // answer was an immediate 4900, which a toolkit reads as "provider
            // disconnected" and does not retry: the user was told the wallet
            // could not be reached by a wallet half a second from answering.
            seedWallet(profile, ABANDON, listOf(ChainType.EVM))
            val outcomes = mutableListOf<DappOutcome>()
            engine.submitDappRequest(
                DappRequest.Connect("c1", "app.uniswap.org", ChainType.EVM, "https://app.uniswap.org")
            ) { outcomes += it }
            assertThat(outcomes).isEmpty()
            assertThat(engine.pendingRequests.value).isEmpty()

            engine.bind(profile)
            advanceUntilIdle()

            assertThat(outcomes).isEmpty()
            assertThat(engine.pendingRequests.value.map { it.id }).contains("c1")

            engine.decideDappRequest(DappDecision("c1", approved = true))
            advanceUntilIdle()
            assertThat(outcomes.single().error).isNull()
        }

    @Test
    fun `a request still held when the bind never comes answers DISCONNECTED`() =
        runTest(testDispatcher) {
            // The hold must expire, not hang: a page waiting on a prompt that
            // can never appear is worse than an error it can act on.
            val outcomes = mutableListOf<DappOutcome>()
            engine.submitDappRequest(
                DappRequest.Connect("c9", "app.uniswap.org", ChainType.EVM, "https://app.uniswap.org")
            ) { outcomes += it }

            advanceTimeBy(HELD_REQUEST_TIMEOUT_MS - 1)
            assertThat(outcomes).isEmpty()

            advanceUntilIdle()
            assertThat(outcomes.single().requireError().code)
                .isEqualTo(WalletBridgeError.DISCONNECTED)
            assertThat(engine.pendingRequests.value).isEmpty()
        }

    @Test
    fun `unbind drops a request that was waiting for the bind`() = runTest(testDispatcher) {
        // A later bind must not pick up a request raised against a session
        // that has already ended.
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        val outcomes = mutableListOf<DappOutcome>()
        engine.submitDappRequest(
            DappRequest.Connect("c2", "app.uniswap.org", ChainType.EVM, "https://app.uniswap.org")
        ) { outcomes += it }
        assertThat(outcomes).isEmpty()

        engine.unbind()
        assertThat(outcomes.single().requireError().code)
            .isEqualTo(WalletBridgeError.DISCONNECTED)

        engine.bind(profile)
        advanceUntilIdle()
        assertThat(outcomes).hasSize(1)
        assertThat(engine.pendingRequests.value).isEmpty()
    }

    @Test
    fun `activeNetworks is empty until the bind lands, then carries the chain`() =
        runTest(testDispatcher) {
            // The dApp relay waits on exactly this flow to get through the
            // deferred 2.5 s bind: while it is empty there is no chain to
            // answer eth_chainId from, and answering CHAIN_DISCONNECTED there
            // is what wagmi reports as the connect itself failing. The wait is
            // only sound while the flow stays empty pre-bind AND fills once
            // the bind lands, which is what this pins.
            seedWallet(profile, ABANDON, listOf(ChainType.EVM))
            assertThat(engine.activeNetworks.value).isEmpty()

            engine.bind(profile)
            // Still empty here on purpose: bind() publishes the profile and
            // clears this map synchronously, and the refresh that fills it is
            // launched. A "bound" signal would go true at this point and let a
            // relay read an engine with no networks in it.
            assertThat(engine.activeNetworks.value).isEmpty()

            advanceUntilIdle()
            assertThat(engine.activeNetworks.value.getValue(ChainType.EVM).chainId)
                .isEqualTo("1")
        }

    @Test
    fun `unbind empties activeNetworks again`() = runTest(testDispatcher) {
        // A read arriving after unbind must not be answered from the networks
        // of a session that has ended.
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        advanceUntilIdle()
        assertThat(engine.activeNetworks.value).isNotEmpty()

        engine.unbind()

        assertThat(engine.activeNetworks.value).isEmpty()
    }

    @Test
    fun `isDappPermitted never matches a blank host`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        // Grant BEFORE bind so the engine's permission cache loads it.
        fake.grantDappPermission(profile, "app.uniswap.org", ChainType.EVM, EVM0, listOf("personal_sign"))
        engine.bind(profile)
        advanceUntilIdle()

        assertThat(engine.isDappPermitted("app.uniswap.org", ChainType.EVM, EVM0, "personal_sign")).isTrue()
        assertThat(engine.isDappPermitted("", ChainType.EVM, EVM0, "personal_sign")).isFalse()
        assertThat(engine.isDappPermitted("app.uniswap.org", ChainType.EVM, EVM0, "eth_sendTransaction")).isFalse()
    }

    // ------------------------------------------------------------------
    // Balances
    // ------------------------------------------------------------------

    @Test
    fun `refreshBalances isolates per-account failures and keeps offline silence`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.EVM, ChainType.SOLANA, ChainType.BITCOIN))
        val ids = fake.accountsOf(profile).associateBy { it.chainType }.mapValues { it.value.id }
        engine.balanceBehavior[ids.getValue(ChainType.EVM)] =
            WalletException.NetworkUnavailable("rpc down")
        engine.balanceBehavior[ids.getValue(ChainType.SOLANA)] = BalanceResult.Ok("1.5", "SOL")
        engine.balanceBehavior[ids.getValue(ChainType.BITCOIN)] = "silent"

        engine.bind(profile)
        advanceUntilIdle()
        engine.refreshBalances()

        val balances = engine.balances.value
        assertThat(balances[ids.getValue(ChainType.EVM)]).isEqualTo(BalanceResult.Error("rpc down"))
        assertThat(balances[ids.getValue(ChainType.SOLANA)]).isEqualTo(BalanceResult.Ok("1.5", "SOL"))
        assertThat(balances).doesNotContainKey(ids.getValue(ChainType.BITCOIN))
    }

    // ------------------------------------------------------------------
    // Native sends + fees
    // ------------------------------------------------------------------

    @Test
    fun `sendNative happy path records the SEND activity and explorer url`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        advanceUntilIdle()
        engine.unlock()

        val account = engine.accounts.value.first { it.chainType == ChainType.EVM }
        val result = engine.sendNative(account.id, "EVM:1", EVM1, "0.1")
        advanceUntilIdle()

        assertThat(result).isEqualTo(BroadcastResult.Ok("0xnativehash"))
        assertThat(engine.activities.value).hasSize(1)
        val activity = engine.activities.value[0]
        assertThat(activity.kind).isEqualTo(WalletActivityRecord.Kind.SEND)
        assertThat(activity.hash).isEqualTo("0xnativehash")
        assertThat(activity.displayAmount).isEqualTo("0.1 ETH")
        assertThat(activity.explorerUrl).isEqualTo("https://etherscan.io/tx/0xnativehash")
    }

    @Test
    fun `sendNative validates recipient, amount, network and lock`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        advanceUntilIdle()

        val account = engine.accounts.value.first { it.chainType == ChainType.EVM }
        // Locked first.
        assertThrowsSuspend<WalletLockedException> {
            engine.sendNative(account.id, "EVM:1", EVM1, "0.1")
        }
        engine.unlock()

        assertThrowsSuspend<WalletException.InvalidParams> {
            engine.sendNative(account.id, "EVM:1", "not-an-address", "0.1")
        }
        assertThrowsSuspend<WalletException.InvalidParams> {
            engine.sendNative(account.id, "EVM:1", EVM1, "12abc")
        }
        assertThrowsSuspend<WalletException.InvalidParams> {
            engine.sendNative(account.id, "EVM:unknown", EVM1, "0.1")
        }
        // A Solana network id cannot serve an EVM account.
        assertThrowsSuspend<WalletException.InvalidParams> {
            engine.sendNative(account.id, "SOLANA:mainnet-beta", EVM1, "0.1")
        }
    }

    @Test
    fun `sendNative maps broadcast errors to typed WalletExceptions`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        advanceUntilIdle()
        engine.unlock()
        engine.nativeSendResult = BroadcastResult.Error("nonce too low")

        val account = engine.accounts.value.first { it.chainType == ChainType.EVM }
        val thrown = assertThrowsSuspend<WalletException.RpcError> {
            engine.sendNative(account.id, "EVM:1", EVM1, "0.1")
        }
        assertThat(thrown.message).contains("nonce too low")
    }

    @Test
    fun `estimateSendFee returns null on unknown inputs and a fee on the EVM path`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        advanceUntilIdle()
        engine.unlock()

        val account = engine.accounts.value.first { it.chainType == ChainType.EVM }
        assertThat(engine.estimateSendFee(ChainType.EVM, "EVM:1", "nope", EVM1, "0.1")).isNull()
        assertThat(engine.estimateSendFee(ChainType.EVM, "EVM:unknown", account.id, EVM1, "0.1")).isNull()
        assertThat(
            engine.estimateSendFee(ChainType.SOLANA, "SOLANA:mainnet-beta", account.id, SOL0, "0.1")
        ).isNull()

        engine.evmFeeResult = BigInteger("1000000000000")
        val fee = engine.estimateSendFee(ChainType.EVM, "EVM:1", account.id, EVM1, "0.1")
        assertThat(fee).isEqualTo(FeeEstimate("Estimated gas fee", "0.000001 ETH"))
    }

    @Test
    fun `estimateSendFee quotes the Octra fee the send will actually attach`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.OCTRA))
        engine.bind(profile)
        advanceUntilIdle()
        engine.unlock()

        val account = engine.accounts.value.first { it.chainType == ChainType.OCTRA }
        // No quote from the node means no fee row — not a free send.
        assertThat(engine.estimateSendFee(ChainType.OCTRA, "OCTRA:mainnet", account.id, OCTRA0, "1"))
            .isNull()

        engine.octraFeeResult = "200000"
        val fee = engine.estimateSendFee(ChainType.OCTRA, "OCTRA:mainnet", account.id, OCTRA0, "1")
        assertThat(fee).isEqualTo(FeeEstimate("Network fee", "0.2 OCT"))
    }

    @Test
    fun `parseEvmTransactionParams handles hex and decimal values`() = runTest(testDispatcher) {
        val params = engine.parseEvmTransactionParams(
            """{"to":"$EVM1","value":"0xde0b6b3a7640000","gas":21000,""" +
                """"gasPrice":"0x3b9aca00","data":"0xdeadbeef","nonce":"0x7"}"""
        )
        assertThat(params.value).isEqualTo(BigInteger("de0b6b3a7640000", 16))
        assertThat(params.to).isEqualTo(EVM1)
        assertThat(params.gasLimit).isEqualTo(BigInteger.valueOf(21000))
        assertThat(params.gasPrice).isEqualTo(BigInteger.valueOf(1_000_000_000))
        assertThat(params.data).isEqualTo("0xdeadbeef")
        assertThat(params.nonce).isEqualTo(BigInteger.valueOf(7))

        assertThrowsSuspend<WalletException.InvalidParams> {
            engine.parseEvmTransactionParams("not json at all")
        }
    }

    // ------------------------------------------------------------------
    // Networks
    // ------------------------------------------------------------------

    @Test
    fun `setActiveNetwork validates existence and chain membership`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        advanceUntilIdle()

        assertThrowsSuspend<WalletException.InvalidParams> {
            engine.setActiveNetwork(ChainType.EVM, "EVM:unknown")
        }
        assertThrowsSuspend<WalletException.InvalidParams> {
            engine.setActiveNetwork(ChainType.EVM, "SOLANA:mainnet-beta")
        }
        engine.setActiveNetwork(ChainType.EVM, "EVM:137")
        assertThat(engine.activeNetworks.value[ChainType.EVM]).isNotNull()
        assertThat(engine.activeNetworks.value.getValue(ChainType.EVM).id).isEqualTo("EVM:137")
    }

    @Test
    fun `addCustomNetwork validates before storing`() = runTest(testDispatcher) {
        engine.bind(profile)
        advanceUntilIdle()

        val valid = NetworkConfig.evm(9999, "Custom Net", listOf("https://rpc.custom"), "CST", null)
        assertThat(engine.addCustomNetwork(valid.copy(name = " "))).isFalse()
        assertThat(engine.addCustomNetwork(valid.copy(rpcUrls = listOf("ftp://nope")))).isFalse()
        assertThat(engine.addCustomNetwork(valid.copy(nativeDecimals = 0))).isFalse()
        assertThat(engine.addCustomNetwork(valid.copy(chainId = "not-a-number"))).isFalse()
        assertThat(fake.networks(profile).map { it.config.id }).doesNotContain("EVM:9999")

        assertThat(engine.addCustomNetwork(valid)).isTrue()
        assertThat(fake.networks(profile).map { it.config.id }).contains("EVM:9999")
    }

    @Test
    fun `a plaintext endpoint the user typed is still accepted`() = runTest(testDispatcher) {
        engine.bind(profile)
        advanceUntilIdle()

        // The other half of the rule: what a web page may not propose, the user
        // may still type. `http://127.0.0.1:8545` is how a local dev node is
        // reached, and refusing it would take a real capability away from
        // someone who already knows what they are doing.
        val local = NetworkConfig.evm(
            9997, "Local Node", listOf("http://127.0.0.1:8545"), "ETH", null
        )
        assertThat(engine.addCustomNetwork(local)).isTrue()
        assertThat(fake.networks(profile).map { it.config.id }).contains("EVM:9997")

        // A local node WITH an https fallback is the same call. The stricter
        // all-TLS rule belongs to the dApp path; it must not leak over here.
        val mixed = NetworkConfig.evm(
            9996, "Local + remote",
            listOf("http://127.0.0.1:8545", "https://rpc.good"), "ETH", null
        )
        assertThat(engine.addCustomNetwork(mixed)).isTrue()
    }

    @Test
    fun `refreshChainlist counts only new networks and disables them`() = runTest(testDispatcher) {
        engine.bind(profile)
        advanceUntilIdle()

        val known = NetworkConfig.evm(1, "Ethereum Mainnet", listOf("https://eth.llamarpc.com"), "ETH", null)
        val fresh = NetworkConfig.evm(999999, "Brand New", listOf("https://rpc.brandnew"), "BRN", null)
        engine.chainlistResult = listOf(known, fresh)

        assertThat(engine.refreshChainlist()).isEqualTo(1)
        val stored = fake.networks(profile).first { it.config.id == "EVM:999999" }
        assertThat(stored.enabled).isFalse()
        // Second run: nothing new.
        assertThat(engine.refreshChainlist()).isEqualTo(0)

        engine.chainlistError = true
        assertThat(engine.refreshChainlist()).isEqualTo(0)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Creates a wallet via the FAKE (bypassing the engine) + derived accounts. */
    private suspend fun seedWallet(profileId: ProfileId, mnemonic: String, chains: List<ChainType>) {
        fake.createWalletForTests(profileId, "Seeded", mnemonic)
        val seed = Mnemonics.toSeed(mnemonic)
        val registry = ChainRegistry()
        chains.forEach { chain ->
            val (address, path) = when (chain) {
                ChainType.EVM -> registry.evm.deriveAccount(seed, 0).let { it.address to it.path }
                ChainType.SOLANA -> registry.solana.deriveAccount(seed, 0).let { it.address to it.path }
                ChainType.APTOS -> registry.aptos.deriveAccount(seed, 0).let { it.address to it.path }
                ChainType.SUI -> registry.sui.deriveAccount(seed, 0).let { it.address to it.path }
                ChainType.COSMOS ->
                    registry.cosmos.deriveAccount(seed, registry.defaultNetworks(ChainType.COSMOS).first(), 0)
                        .let { it.address to it.path }
                ChainType.BITCOIN ->
                    registry.bitcoin.deriveAccount(seed, registry.defaultNetworks(ChainType.BITCOIN).first(), 0)
                        .let { it.address to it.path }
                ChainType.TRON -> registry.tron.deriveAccount(seed, 0).let { it.address to it.path }
                ChainType.OCTRA -> registry.octra.deriveAccount(seed, 0).let { it.address to it.path }
            }
            fake.addDerivedAccount(profileId, chain, address, path, "${chain.displayName} 1")
        }
    }

    // ------------------------------------------------------------------
    // Restore from a backup file
    // ------------------------------------------------------------------

    private fun backupPayload(
        mnemonic: String? = ABANDON,
        accounts: List<WalletBackup.KeyEntry> = emptyList()
    ) = WalletBackup.Payload(
        walletLabel = "Restored",
        createdAt = 0L,
        mnemonic = mnemonic,
        accounts = accounts
    )

    @Test
    fun `restore rebuilds the phrase accounts and the imported keys alike`() = runTest(testDispatcher) {
        engine.bind(profile)
        advanceUntilIdle()

        val report = engine.restoreFromBackup(
            backupPayload(
                accounts = listOf(
                    // A derived row carries no private key: the phrase above
                    // recreates it, so it is listed for the reader and nothing
                    // more. Restoring it again would be a duplicate account.
                    WalletBackup.KeyEntry("EVM", "EVM 1", EVM0, "m/44'/60'/0'/0/0"),
                    // An imported key, which nothing but this row can restore.
                    WalletBackup.KeyEntry("EVM", "Cold", "0xwhatever", "", privateKey = SEED)
                )
            ),
            enabledChains = listOf(ChainType.EVM)
        )
        advanceUntilIdle()

        assertThat(report.phraseRestored).isTrue()
        assertThat(report.derivedAccountCount).isEqualTo(1)
        assertThat(report.importedAccountCount).isEqualTo(1)
        assertThat(report.skipped).isEmpty()

        val stored = fake.accountsOf(profile)
        // Two accounts, not three: the file listed a derived row too, and it
        // is not restored a second time beside the one the phrase made.
        assertThat(stored).hasSize(2)
        assertThat(stored.map { it.source }).containsExactly(
            WalletAccountRecord.Source.DERIVED,
            WalletAccountRecord.Source.IMPORTED
        )
        // The imported key lands at the address THIS app computes for it, not
        // at the one the file claimed: a backup is an untrusted file, and an
        // account labelled with an address its key does not control is a trap
        // the user would only discover when a send failed.
        val imported = stored.first { it.source == WalletAccountRecord.Source.IMPORTED }
        assertThat(imported.address).isEqualTo(EVM_IMPORT)
        assertThat(imported.address).isNotEqualTo("0xwhatever")
        // Stored canonically, so re-exporting writes the same 64 hex digits
        // and a restore round-trips through a backup unchanged.
        assertThat(fake.revealPrivateKey(imported.id)).isEqualTo(SEED)
    }

    @Test
    fun `restore refuses to run over an existing wallet`() = runTest(testDispatcher) {
        seedWallet(profile, ABANDON, listOf(ChainType.EVM))
        engine.bind(profile)
        engine.unlock()
        advanceUntilIdle()

        // Replacing a live wallet is not a restore, it is a deletion, and
        // deletions are confirmed separately. Nothing may be written here.
        assertThrowsSuspend<WalletException.InvalidParams> {
            engine.restoreFromBackup(backupPayload(), enabledChains = listOf(ChainType.EVM))
        }
        assertThat(fake.accountsOf(profile)).hasSize(1)
    }

    @Test
    fun `a key this build cannot parse is reported, not fatal`() = runTest(testDispatcher) {
        engine.bind(profile)
        advanceUntilIdle()

        val report = engine.restoreFromBackup(
            backupPayload(
                accounts = listOf(
                    WalletBackup.KeyEntry("EVM", "Bad", "0xbad", "", privateKey = "not-a-key"),
                    WalletBackup.KeyEntry("Nostromo", "Alien", "0xalien", "", privateKey = SEED),
                    WalletBackup.KeyEntry("EVM", "Good", "0xgood", "", privateKey = SEED)
                )
            ),
            enabledChains = listOf(ChainType.EVM)
        )
        advanceUntilIdle()

        // One bad key and one unknown chain must not cost the user the key
        // that was fine: that is the whole reason restore reports instead of
        // throwing.
        assertThat(report.importedAccountCount).isEqualTo(1)
        assertThat(report.skipped.map { it.label }).containsExactly("Bad", "Alien")
        assertThat(report.skipped.map { it.reason })
            .containsExactly("Invalid EVM private key", "unknown chain for this build")
    }

    @Test
    fun `a backup with no phrase is still a wallet worth restoring`() = runTest(testDispatcher) {
        engine.bind(profile)
        advanceUntilIdle()

        // The keys-only wallet: every account is an individually imported
        // key, so the phrase column stays empty and the keys are everything.
        val report = engine.restoreFromBackup(
            backupPayload(mnemonic = null, accounts = listOf(
                WalletBackup.KeyEntry("EVM", "Solo", "0xwho", "", privateKey = SEED)
            )),
            enabledChains = listOf(ChainType.EVM)
        )
        advanceUntilIdle()

        assertThat(report.phraseRestored).isFalse()
        assertThat(report.derivedAccountCount).isEqualTo(0)
        assertThat(report.importedAccountCount).isEqualTo(1)
        assertThat(engine.wallet.value).isNotNull()
        assertThat(engine.wallet.value!!.hasMnemonic).isFalse()
    }

    @Test
    fun `an invalid phrase is refused before anything is written`() = runTest(testDispatcher) {
        engine.bind(profile)
        advanceUntilIdle()

        assertThrowsSuspend<WalletException.InvalidParams> {
            engine.restoreFromBackup(
                backupPayload(mnemonic = "not actually a bip39 phrase at all"),
                enabledChains = listOf(ChainType.EVM)
            )
        }
        // No half-built wallet left behind for a later call to trip over.
        assertThat(fake.wallet(profile)).isNull()
        assertThat(fake.accountsOf(profile)).isEmpty()
    }

    @Test
    fun `restore fails closed while the session is locked`() = runTest(testDispatcher) {
        seedWallet(otherProfile, ABANDON, listOf(ChainType.EVM))
        engine.bind(otherProfile)
        advanceUntilIdle()

        // A restored wallet is key material being written; it must not happen
        // behind the gate any more than an import does.
        assertThrowsSuspend<WalletLockedException> {
            engine.restoreFromBackup(backupPayload(), enabledChains = listOf(ChainType.EVM))
        }
        assertThat(fake.accountsOf(otherProfile)).hasSize(1)
    }
}

// ---------------------------------------------------------------------------
// In-memory fake of WalletRepositoryApi (deterministic, observer-counting)
// ---------------------------------------------------------------------------

/** Counting cold-flow wrapper so tests can assert collector hygiene. */
private fun <T> StateFlow<T>.counting(started: () -> Unit, finished: () -> Unit): Flow<T> =
    onSubscription { started() }.onCompletion { finished() }

/**
 * Full in-memory [WalletRepositoryApi]: per-profile StateFlows, key-only
 * wallets, an explicit active-network map defaulting to the first enabled
 * network, and observer counters for collector-hygiene assertions. Open so
 * mockk can spy on it.
 */
private open class FakeWalletRepository : WalletRepositoryApi {

    private val registry = ChainRegistry()

    // profileId.value -> state
    private val wallets = mutableMapOf<String, WalletSummary?>()
    private val mnemonics = mutableMapOf<String, String>()
    private val accountLists = mutableMapOf<String, MutableList<WalletAccountRecord>>()
    private val networkLists = mutableMapOf<String, MutableList<NetworkRecord>>()
    private val importedKeys = mutableMapOf<String, String>() // accountId -> key
    private val activeIds = mutableMapOf<String, String>() // "profile|chain" -> networkId
    private val permissionLists = mutableMapOf<String, MutableList<DappPermissionRecord>>()
    private val activityLists = mutableMapOf<String, MutableList<WalletActivityRecord>>()

    private val walletFlows = mutableMapOf<String, MutableStateFlow<WalletSummary?>>()
    private val accountFlows = mutableMapOf<String, MutableStateFlow<List<WalletAccountRecord>>>()
    private val networkFlows = mutableMapOf<String, MutableStateFlow<List<NetworkRecord>>>()
    private val activityFlows = mutableMapOf<String, MutableStateFlow<List<WalletActivityRecord>>>()

    /** Live observeAccounts collectors — asserted by the re-bind hygiene test. */
    var activeAccountObservers = 0
        private set

    // -- lookups used by tests --------------------------------------------

    fun wallets(profileId: ProfileId): WalletSummary? = wallets[profileId.value]

    fun accountsOf(profileId: ProfileId): List<WalletAccountRecord> =
        accountLists[profileId.value]?.toList() ?: emptyList()

    fun activeNetworkIdOf(profileId: ProfileId, chainType: ChainType): String? =
        activeIds["${profileId.value}|$chainType"]

    // -- wallet ------------------------------------------------------------

    override fun observeWallet(profileId: ProfileId): Flow<WalletSummary?> =
        walletFlow(profileId).counting({ }) { }

    override suspend fun wallet(profileId: ProfileId): WalletSummary? = wallets[profileId.value]

    override suspend fun createWallet(
        profileId: ProfileId,
        label: String,
        mnemonic: String?
    ): WalletSummary {
        check(wallets[profileId.value] == null) { "Wallet already exists" }
        return if (mnemonic == null) createKeylessWallet(profileId, label)
        else createWalletForTests(profileId, label, mnemonic)
    }

    /** Test helper: a wallet row with NO mnemonic (key-only import case). */
    fun createKeylessWallet(profileId: ProfileId, label: String): WalletSummary =
        upsertWallet(
            profileId,
            WalletSummary(
                id = "wallet-${profileId.value}", profileId = profileId, label = label,
                createdAt = 0L, hasMnemonic = false
            )
        )

    /** Test helper mirroring createWallet without the engine. */
    fun createWalletForTests(profileId: ProfileId, label: String, mnemonic: String): WalletSummary {
        mnemonics[profileId.value] = mnemonic
        return upsertWallet(
            profileId,
            WalletSummary(
                id = "wallet-${profileId.value}", profileId = profileId, label = label,
                createdAt = 0L, hasMnemonic = true
            )
        )
    }

    private fun upsertWallet(profileId: ProfileId, summary: WalletSummary): WalletSummary {
        wallets[profileId.value] = summary
        walletFlow(profileId).value = summary
        return summary
    }

    override suspend fun deleteWallet(profileId: ProfileId) {
        wallets.remove(profileId.value)
        mnemonics.remove(profileId.value)
        accountLists.remove(profileId.value)
        walletFlow(profileId).value = null
        accountFlows[profileId.value]?.value = emptyList()
    }

    override suspend fun revealMnemonic(profileId: ProfileId): String? = mnemonics[profileId.value]

    // -- accounts ----------------------------------------------------------

    override fun observeAccounts(profileId: ProfileId): Flow<List<WalletAccountRecord>> =
        accountsFlow(profileId)
            .counting({ activeAccountObservers++ }) { activeAccountObservers-- }

    override suspend fun accounts(profileId: ProfileId): List<WalletAccountRecord> = accountsOf(profileId)

    override suspend fun addDerivedAccount(
        profileId: ProfileId,
        chainType: ChainType,
        address: String,
        path: String,
        label: String
    ): WalletAccountRecord {
        val record = WalletAccountRecord(
            id = "acct-${profileId.value}-${accountsOf(profileId).size}",
            walletId = "wallet-${profileId.value}", chainType = chainType, address = address,
            label = label, path = path, source = WalletAccountRecord.Source.DERIVED
        )
        accountLists.getOrPut(profileId.value) { mutableListOf() }.add(record)
        republishAccounts(profileId)
        return record
    }

    override suspend fun addImportedAccount(
        profileId: ProfileId,
        chainType: ChainType,
        address: String,
        privateKey: String,
        label: String
    ): WalletAccountRecord {
        val record = WalletAccountRecord(
            id = "acct-${profileId.value}-${accountsOf(profileId).size}",
            walletId = "wallet-${profileId.value}", chainType = chainType, address = address,
            label = label, path = "", source = WalletAccountRecord.Source.IMPORTED
        )
        accountLists.getOrPut(profileId.value) { mutableListOf() }.add(record)
        importedKeys[record.id] = privateKey
        republishAccounts(profileId)
        return record
    }

    override suspend fun renameAccount(accountId: String, label: String) {
        accountLists.keys.forEach { key ->
            val list = accountLists.getValue(key)
            val index = list.indexOfFirst { it.id == accountId }
            if (index >= 0) {
                list[index] = list[index].copy(label = label)
                accountFlows[key]?.value = list.toList()
            }
        }
    }

    override suspend fun removeAccount(accountId: String) {
        accountLists.values.forEach { list -> list.removeAll { it.id == accountId } }
        importedKeys.remove(accountId)
        accountLists.keys.forEach { key -> accountFlows[key]?.value = accountLists.getValue(key).toList() }
    }

    override suspend fun revealPrivateKey(accountId: String): String? = importedKeys[accountId]

    override suspend fun nextDerivationIndex(profileId: ProfileId, chainType: ChainType): Int {
        val indices = accountsOf(profileId)
            .filter { it.chainType == chainType && it.source == WalletAccountRecord.Source.DERIVED }
            .mapNotNull { it.path.substringAfterLast('/').toIntOrNull() }
        return (indices.maxOrNull() ?: -1) + 1
    }

    private fun republishAccounts(profileId: ProfileId) {
        accountsFlow(profileId).value = accountsOf(profileId)
    }

    // -- networks ----------------------------------------------------------

    override fun observeNetworks(profileId: ProfileId): Flow<List<NetworkRecord>> =
        networkFlow(profileId).counting({ }) { }

    override suspend fun networks(profileId: ProfileId): List<NetworkRecord> =
        networkLists[profileId.value]?.toList() ?: emptyList()

    override suspend fun ensureDefaultNetworks(profileId: ProfileId) {
        if (networkLists[profileId.value]?.isNotEmpty() == true) return
        val records = ChainType.entries.flatMap { chain ->
            registry.defaultNetworks(chain).mapIndexed { index, config ->
                NetworkRecord(config = config, enabled = index == 0, isCustom = false)
            }
        }.toMutableList()
        networkLists[profileId.value] = records
        networkFlow(profileId).value = records.toList()
    }

    override suspend fun upsertCustomNetwork(profileId: ProfileId, config: NetworkConfig) {
        val list = networkLists.getOrPut(profileId.value) { mutableListOf() }
        val existing = list.indexOfFirst { it.config.id == config.id }
        if (existing >= 0) {
            list[existing] = list[existing].copy(config = config, enabled = true, isCustom = true)
        } else {
            list.add(NetworkRecord(config = config, enabled = true, isCustom = true))
        }
        networkFlow(profileId).value = list.toList()
    }

    override suspend fun removeCustomNetwork(profileId: ProfileId, networkId: String) {
        networkLists[profileId.value]?.removeAll { it.config.id == networkId }
        networkFlow(profileId).value = networks(profileId)
    }

    override suspend fun setNetworkEnabled(profileId: ProfileId, networkId: String, enabled: Boolean) {
        val list = networkLists[profileId.value] ?: return
        val index = list.indexOfFirst { it.config.id == networkId }
        if (index >= 0) {
            list[index] = list[index].copy(enabled = enabled)
            networkFlow(profileId).value = list.toList()
        }
    }

    /** Test helper so tests can flip a default network's enabled flag. */
    fun setNetworkEnabledForTests(profileId: ProfileId, networkId: String, enabled: Boolean) {
        val list = networkLists.getValue(profileId.value)
        val index = list.indexOfFirst { it.config.id == networkId }
        list[index] = list[index].copy(enabled = enabled)
        networkFlow(profileId).value = list.toList()
    }

    override suspend fun activeNetwork(profileId: ProfileId, chainType: ChainType): NetworkConfig? {
        val records = networks(profileId).filter { it.config.chainType == chainType }
        // Mirrors the real repository: the stored choice only counts while
        // its row exists AND is enabled; otherwise the FIRST enabled network
        // of the chain; otherwise null (chain fully disabled).
        val explicit = activeIds["${profileId.value}|$chainType"]
            ?.let { id -> records.firstOrNull { it.config.id == id } }
            ?.takeIf { it.enabled }
        if (explicit != null) return explicit.config
        return records.firstOrNull { it.enabled }?.config
    }

    override suspend fun setActiveNetwork(profileId: ProfileId, chainType: ChainType, networkId: String) {
        val list = networkLists[profileId.value]
            ?: throw IllegalArgumentException("Unknown network $networkId for this profile")
        val index = list.indexOfFirst { it.config.id == networkId }
        if (index < 0 || list[index].config.chainType != chainType) {
            throw IllegalArgumentException("Network $networkId does not belong to chain ${chainType.name}")
        }
        // An active network must be usable: selecting it enables it.
        if (!list[index].enabled) {
            list[index] = list[index].copy(enabled = true)
            networkFlow(profileId).value = list.toList()
        }
        activeIds["${profileId.value}|$chainType"] = networkId
    }

    // -- dApp permissions --------------------------------------------------

    override suspend fun grantDappPermission(
        profileId: ProfileId,
        host: String,
        chainType: ChainType,
        accountAddress: String,
        methods: List<String>
    ) {
        val list = permissionLists.getOrPut(profileId.value) { mutableListOf() }
        list.removeAll { it.host == host && it.chainType == chainType }
        list.add(
            DappPermissionRecord(
                id = "perm-${list.size}", profileId = profileId, host = host,
                chainType = chainType, accountAddress = accountAddress,
                methods = methods, grantedAt = 0L
            )
        )
    }

    override suspend fun revokeDappPermission(profileId: ProfileId, host: String, chainType: ChainType) {
        permissionLists[profileId.value]?.removeAll { it.host == host && it.chainType == chainType }
    }

    override suspend fun dappPermissions(profileId: ProfileId, host: String): List<DappPermissionRecord> =
        allDappPermissions(profileId).filter { it.host == host }

    override suspend fun allDappPermissions(profileId: ProfileId): List<DappPermissionRecord> =
        permissionLists[profileId.value]?.toList() ?: emptyList()

    // -- activity ----------------------------------------------------------

    override fun observeActivities(profileId: ProfileId): Flow<List<WalletActivityRecord>> =
        activityFlow(profileId).counting({ }) { }

    override suspend fun recordActivity(record: WalletActivityRecord) {
        activityLists.getOrPut(record.profileId.value) { mutableListOf() }.add(record)
        activityFlows[record.profileId.value]?.value =
            activityLists.getValue(record.profileId.value).toList()
    }

    // -- flows -------------------------------------------------------------

    private fun walletFlow(profileId: ProfileId): MutableStateFlow<WalletSummary?> =
        walletFlows.getOrPut(profileId.value) { MutableStateFlow(null) }

    private fun accountsFlow(profileId: ProfileId): MutableStateFlow<List<WalletAccountRecord>> =
        accountFlows.getOrPut(profileId.value) { MutableStateFlow(emptyList()) }

    private fun networkFlow(profileId: ProfileId): MutableStateFlow<List<NetworkRecord>> =
        networkFlows.getOrPut(profileId.value) { MutableStateFlow(emptyList()) }

    private fun activityFlow(profileId: ProfileId): MutableStateFlow<List<WalletActivityRecord>> =
        activityFlows.getOrPut(profileId.value) { MutableStateFlow(emptyList()) }

    /** Test helper: force an accounts emission for a profile. */
    fun pushAccounts(profileId: ProfileId, accounts: List<WalletAccountRecord>) {
        accountLists[profileId.value] = accounts.toMutableList()
        accountsFlow(profileId).value = accounts
    }
}
