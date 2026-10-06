package com.roombrowser.browser.wallet

import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.export.WalletBackup
import com.roombrowser.browser.wallet.dapp.WalletBridgeProtocol
import com.roombrowser.domain.wallet.chains.ChainRegistry
import com.roombrowser.domain.wallet.chains.cosmos.CosmosAdapter
import com.roombrowser.domain.wallet.chains.evm.EvmAdapter
import com.roombrowser.domain.wallet.chains.octra.OctraAdapter
import com.roombrowser.domain.wallet.crypto.Base58
import com.roombrowser.domain.wallet.crypto.Bip32PrivateKey
import com.roombrowser.domain.wallet.crypto.Ed25519
import com.roombrowser.domain.wallet.crypto.Hashes
import com.roombrowser.domain.wallet.crypto.Hex
import com.roombrowser.domain.wallet.crypto.Mnemonics
import com.roombrowser.domain.wallet.crypto.Slip10Ed25519Key
import com.roombrowser.domain.wallet.model.AmountFormat
import com.roombrowser.domain.wallet.model.BalanceResult
import com.roombrowser.domain.wallet.model.BroadcastResult
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.domain.wallet.model.FeeEstimate
import com.roombrowser.domain.wallet.model.NetworkConfig
import com.roombrowser.domain.wallet.model.WalletException
import com.roombrowser.domain.wallet.wire.ProtoWriter
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Base64
import java.util.UUID

/**
 * Thrown by every [WalletEngine] operation that needs key material while the
 * session is LOCKED (or no wallet exists). The UI layer runs its biometric
 * gate, calls [WalletEngine.unlock], and only then retries — the engine
 * itself never prompts and never stores key material outside the repository.
 */
class WalletLockedException : IllegalStateException(
    "The wallet is locked; unlock the session before using key material"
)

/**
 * How long a dApp request may wait for the first bind before answering
 * DISCONNECTED. Comfortably longer than the 2.5 s cold-start deferral, so
 * only an engine that genuinely never binds reaches it.
 */
internal const val HELD_REQUEST_TIMEOUT_MS = 15_000L

/** Cap on requests parked across that window; beyond it they fail fast. */
private const val MAX_HELD_REQUESTS = 16

/**
 * Wallet orchestration for the bound profile: session lock, wallet lifecycle,
 * account derivation/import, balances, networks, native sends and the dApp
 * request queue — the implementation of the frozen [WalletEngineApi].
 *
 * CONSTRUCTOR (manual DI — wired by the app integrator, no Android Context):
 * ```kotlin
 * WalletEngine(
 *     repo: WalletRepositoryApi,
 *     registry: ChainRegistry = ChainRegistry(),
 *     clock: () -> Long = System::currentTimeMillis
 * )
 * ```
 *
 * Threading model:
 *  - every mutating/lifecycle entry point ([bind], [unbind], [unlock], [lock],
 *    [submitDappRequest], [decideDappRequest], [isDappPermitted]) is called on
 *    the main thread (the UI and the WebView bridge live there), matching the
 *    session-lock convention used by the app's credential repository;
 *  - all public suspend APIs are main-safe: repository calls run through the
 *    repository's own IO dispatching, and CPU-heavy crypto (BIP39 seed →
 *    PBKDF2, BIP32/SLIP-0010 derivations, signing) runs on
 *    [cryptoDispatcher] (Dispatchers.Default in production);
 *  - all observable state is [StateFlow]; dApp settle callbacks run exactly
 *    once, on the main thread.
 *
 * KEY-MATERIAL POLICY: plaintext keys exist only between a reveal call and
 * the signing call inside one operation. Nothing is logged; the engine holds
 * no cache of mnemonics or private keys — every operation re-reveals through
 * [WalletRepositoryApi].
 *
 * DERIVATION POLICY (per chain, through the chain's own adapter — never
 * hand-rolled here): EVM m/44'/60'/0'/0/i, Solana m/44'/501'/i'/0',
 * Aptos m/54'/6'/0'/0'/i (the adapter's Petra-compatible canonical path),
 * Sui m/44'/784'/0'/0'/i', Cosmos m/44'/118'/0'/0/i (coin 118, hrp "cosmos"),
 * Bitcoin m/84'/0'/0'/0/i (mainnet BIP84) and TRON m/44'/195'/0'/0/i.
 */
open class WalletEngine(
    private val repo: WalletRepositoryApi,
    private val registry: ChainRegistry = ChainRegistry(),
    private val clock: () -> Long = System::currentTimeMillis
) : WalletEngineApi {

    // ------------------------------------------------------------------
    // Scope + dispatchers
    // ------------------------------------------------------------------

    /**
     * Where CPU-heavy crypto runs (BIP39 seed derivation, HD walks, signing).
     * Internal open so JVM tests can swap in a virtual-time dispatcher.
     */
    internal open val cryptoDispatcher: CoroutineDispatcher = Dispatchers.Default

    /**
     * Main-thread scope for state mutations and collector lifetimes. A plain
     * SupervisorJob: one cancelled collector must never take down the others.
     */
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private val profileState = MutableStateFlow<ProfileId?>(null)
    private val unlockedState = MutableStateFlow(false)

    private val lockStateBacking = MutableStateFlow(WalletLockState.NO_WALLET)
    private val walletState = MutableStateFlow<WalletSummary?>(null)
    private val accountsState = MutableStateFlow<List<WalletAccountRecord>>(emptyList())
    private val balancesState = MutableStateFlow<Map<String, BalanceResult>>(emptyMap())
    private val networksState = MutableStateFlow<List<NetworkRecord>>(emptyList())
    private val activeNetworksState = MutableStateFlow<Map<ChainType, NetworkConfig>>(emptyMap())
    private val pendingRequestsState = MutableStateFlow<List<DappRequest>>(emptyList())
    private val activitiesState = MutableStateFlow<List<WalletActivityRecord>>(emptyList())
    private val permissionsState = MutableStateFlow<List<DappPermissionRecord>>(emptyList())

    override val lockState: StateFlow<WalletLockState> get() = lockStateBacking.asStateFlow()
    override val wallet: StateFlow<WalletSummary?> get() = walletState.asStateFlow()
    override val accounts: StateFlow<List<WalletAccountRecord>> get() = accountsState.asStateFlow()
    override val balances: StateFlow<Map<String, BalanceResult>> get() = balancesState.asStateFlow()
    override val networks: StateFlow<List<NetworkRecord>> get() = networksState.asStateFlow()
    override val activeNetworks: StateFlow<Map<ChainType, NetworkConfig>> =
        activeNetworksState.asStateFlow()
    override val pendingRequests: StateFlow<List<DappRequest>> =
        pendingRequestsState.asStateFlow()
    override val activities: StateFlow<List<WalletActivityRecord>> =
        activitiesState.asStateFlow()
    override val dappPermissions: StateFlow<List<DappPermissionRecord>> =
        permissionsState.asStateFlow()

    /** Collector-generation handles — cancel-and-replace on (re)bind (Task 1-a hygiene). */
    private var bindJob: Job? = null
    private var activeNetworksJob: Job? = null
    private var balancesJob: Job? = null

    /**
     * dApp queue bookkeeping (main thread only): [dappCallbacks] holds the
     * bridge callback per request id (original AND coalesced ids);
     * [coalescedInto] maps a coalesced duplicate onto the id that actually
     * shows in the prompt queue.
     */
    private val dappCallbacks = mutableMapOf<String, (DappOutcome) -> Unit>()
    private val coalescedInto = mutableMapOf<String, String>()

    // ------------------------------------------------------------------
    // Session
    // ------------------------------------------------------------------

    override fun bind(profileId: ProfileId) {
        // RE-BIND TO THE SAME PROFILE IS A NO-OP. The engine is a process
        // singleton with two independent binders — BrowserViewModel on engine
        // start, and WalletActivity when the wallet screen opens — so opening
        // the wallet UI used to re-run everything below for a profile that had
        // not changed: a full collector restart, a wiped unlock session, and
        // settleAllPending(DISCONNECTED) aborting every dApp request raised by
        // the pages behind the sheet. Guarding on the live generation makes the
        // second binder free, which is what it always meant to be.
        if (profileState.value == profileId && bindJob?.isActive == true) return
        // Cancel-and-replace: exactly ONE collector generation is alive (the
        // observeTabCounts duplicate-collector fix, applied to wallet state).
        bindJob?.cancel()
        activeNetworksJob?.cancel()
        balancesJob?.cancel()
        settleAllPending(WalletBridgeError.DISCONNECTED, "Profile changed")
        unlockedState.value = false
        profileState.value = profileId
        walletState.value = null
        accountsState.value = emptyList()
        balancesState.value = emptyMap()
        networksState.value = emptyList()
        activeNetworksState.value = emptyMap()
        activitiesState.value = emptyList()
        permissionsState.value = emptyList()
        refreshLockState()
        bindJob = engineScope.launch {
            launch {
                repo.observeWallet(profileId).collect {
                    walletState.value = it
                    refreshLockState()
                }
            }
            launch {
                repo.observeAccounts(profileId).collect { accountsState.value = it }
            }
            launch {
                repo.observeNetworks(profileId).collect { records ->
                    networksState.value = records
                    refreshActiveNetworks(profileId)
                }
            }
            launch {
                repo.observeActivities(profileId).collect { activitiesState.value = it }
            }
            // Idempotent on the repository side; first bind seeds the defaults.
            quiet { repo.ensureDefaultNetworks(profileId) }
            permissionsState.value = quiet { repo.allDappPermissions(profileId) } ?: emptyList()
            refreshActiveNetworksNow(profileId)
            // Fire-and-forget balance refresh; offline is silent (see refreshBalances).
            balancesJob = launch { quiet { refreshBalances() } }
        }
        // Anything that arrived before this bind can now be asked properly.
        // Deliberately AFTER the state resets above: the sheets read the
        // networks and accounts those flows carry.
        releaseHeldRequests(disconnected = false)
    }

    override fun unbind() {
        bindJob?.cancel()
        bindJob = null
        activeNetworksJob?.cancel()
        activeNetworksJob = null
        balancesJob?.cancel()
        balancesJob = null
        settleAllPending(WalletBridgeError.DISCONNECTED, "Wallet disconnected")
        // Nothing bound means nothing to hold them for; a later bind must not
        // pick up a request raised against a session that has ended.
        releaseHeldRequests(disconnected = true)
        profileState.value = null
        unlockedState.value = false
        walletState.value = null
        accountsState.value = emptyList()
        balancesState.value = emptyMap()
        networksState.value = emptyList()
        activeNetworksState.value = emptyMap()
        activitiesState.value = emptyList()
        permissionsState.value = emptyList()
        refreshLockState()
    }

    /**
     * Marks the session unlocked. The caller MUST have completed its own
     * biometric/device-credential gate first — this records the gate result,
     * it never runs a gate.
     */
    override fun unlock() {
        unlockedState.value = true
        refreshLockState()
    }

    /** Re-locks the session (explicit lock, backgrounding, profile switch). */
    override fun lock() {
        unlockedState.value = false
        refreshLockState()
    }

    private fun refreshLockState() {
        lockStateBacking.value = when {
            walletState.value == null -> WalletLockState.NO_WALLET
            unlockedState.value -> WalletLockState.UNLOCKED
            else -> WalletLockState.LOCKED
        }
    }

    private fun requireBound(): ProfileId =
        profileState.value ?: throw WalletException.Unauthorized(
            "Wallet engine is not bound to a profile"
        )

    private fun requireUnlocked() {
        if (lockStateBacking.value != WalletLockState.UNLOCKED) throw WalletLockedException()
    }

    /**
     * Fails closed for key-material PRODUCING operations while a wallet
     * exists behind a locked session: creating or importing a wallet must
     * not run behind the gate. NO_WALLET is deliberately allowed — onboarding
     * (the only NO_WALLET caller) must be able to create the first wallet.
     */
    private fun requireNotLocked() {
        if (lockStateBacking.value == WalletLockState.LOCKED) throw WalletLockedException()
    }

    // ------------------------------------------------------------------
    // Wallet lifecycle
    // ------------------------------------------------------------------

    override suspend fun createWallet(label: String, enabledChains: List<ChainType>): String {
        val profileId = requireBound()
        requireNotLocked()
        val mnemonic = Mnemonics.generate()
        repo.createWallet(profileId, label, mnemonic)
        seedInitialAccounts(profileId, mnemonic, enabledChains)
        // The ONE time the plaintext leaves the vault: the caller shows it to
        // the user; nothing else ever stores it outside the repository.
        return mnemonic
    }

    override suspend fun importWallet(mnemonic: String, label: String, enabledChains: List<ChainType>) {
        val profileId = requireBound()
        requireNotLocked()
        if (!Mnemonics.isValid(mnemonic)) {
            throw WalletException.InvalidParams("Not a valid BIP39 mnemonic")
        }
        val normalized = Mnemonics.normalize(mnemonic)
        repo.createWallet(profileId, label, normalized)
        seedInitialAccounts(profileId, normalized, enabledChains)
    }

    override suspend fun revealMnemonic(): String? {
        val profileId = requireBound()
        requireUnlocked()
        return repo.revealMnemonic(profileId)
    }

    /**
     * Deletes the bound profile's wallet and every row that belongs to it. The
     * unlock requirement is the session gate, not the user's confirmation: the
     * confirmation is the UI's delete flow, and this runs after it.
     */
    override suspend fun deleteWallet() {
        val profileId = requireBound()
        requireUnlocked()
        repo.deleteWallet(profileId)
        // Eager, so the surface leaves the dashboard on this frame rather than
        // on the next Room invalidation; the observer agrees a moment later.
        walletState.value = null
        accountsState.value = emptyList()
        balancesState.value = emptyMap()
        refreshLockState()
    }

    /**
     * See [WalletEngineApi.publicKeyOf] for which chains this answers for and
     * why the others are left out.
     *
     * The key is derived from the account's own key material rather than read
     * from a stored copy, so there is nothing new at rest and nothing to
     * migrate — and NOTHING here is gated on an unlocked session beyond what
     * reading that material already requires. A locked wallet, or an account
     * whose key cannot be read, answers null: the dApp gets the address-only
     * result it got before, which is a working connect for every flow that
     * does not need the key.
     *
     * Only the chains listed in the API doc are handled; the rest return null
     * without touching key material at all.
     */
    override suspend fun publicKeyOf(accountId: String): String? {
        val account = accounts.value.firstOrNull { it.id == accountId } ?: return null
        return publicKeyOf(account)
    }

    /**
     * The derivation behind [publicKeyOf], taking the record directly.
     *
     * [executeConnect] already holds the account it is about to connect —
     * freshly read from the repository — so routing that call back through
     * the state flow would make the prompted path depend on the flow having
     * caught up, and hand a prompted connect a key-less result on exactly the
     * same account the auto-approve path answers with one.
     */
    private suspend fun publicKeyOf(account: WalletAccountRecord): String? =
        runCatching {
            when (account.chainType) {
                ChainType.COSMOS, ChainType.BITCOIN -> Hex.encode(
                    Bip32PrivateKey.compressedPublicKeyOf(secpPrivateKey(account))
                )
                ChainType.APTOS ->
                    Hex.encode(Ed25519.publicKeyFromSeed(ed25519Seed(account)))
                ChainType.OCTRA ->
                    Hex.encode(Ed25519.publicKeyFromSeed(octraSeed(account)))
                else -> null
            }
        }.getOrNull()

    override suspend fun backupContents(mnemonic: String?): WalletBackup.Contents {
        val profileId = requireBound()
        // A caller holding the phrase already (the onboarding reveal, where
        // creating a wallet deliberately leaves the session locked) needs no
        // unlock. Everyone else reads it from the vault and therefore does.
        val phrase = mnemonic ?: run {
            requireUnlocked()
            repo.revealMnemonic(profileId)
        }
        // Read from the repository rather than from the `accounts` flow: the
        // flow lags a write by one Room invalidation, and an export taken
        // right after wallet creation must not describe a wallet with no
        // accounts in it.
        val accounts = repo.accounts(profileId).map { record ->
            WalletBackup.KeyEntry(
                chain = record.chainType.displayName,
                label = record.label,
                address = record.address,
                path = record.path,
                // Derived accounts are re-derived from the phrase, so storing
                // their keys would duplicate the secret for nothing. An
                // imported key is reproduced by nothing else.
                privateKey = if (record.source == WalletAccountRecord.Source.IMPORTED) {
                    withContext(cryptoDispatcher) { repo.revealPrivateKey(record.id) }
                } else {
                    null
                }
            )
        }
        val wallet = repo.wallet(profileId)
        return WalletBackup.Contents(
            walletLabel = wallet?.label?.takeIf { it.isNotBlank() } ?: "Wallet",
            createdAt = wallet?.createdAt ?: 0L,
            mnemonic = phrase,
            accounts = accounts
        )
    }

    /** Derives index-0 accounts for every enabled chain from [mnemonic]. */
    private suspend fun seedInitialAccounts(
        profileId: ProfileId,
        mnemonic: String,
        enabledChains: List<ChainType>
    ) {
        if (enabledChains.isEmpty()) return
        repo.ensureDefaultNetworks(profileId)
        withContext(cryptoDispatcher) {
            val seed = Mnemonics.toSeed(Mnemonics.normalize(mnemonic))
            enabledChains.forEach { chain ->
                val derived = deriveDerivedAccount(chain, seed, 0)
                repo.addDerivedAccount(
                    profileId, chain, derived.first, derived.second, "${chain.displayName} 1"
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Accounts
    // ------------------------------------------------------------------

    override suspend fun addDerivedAccount(chainType: ChainType): WalletAccountRecord? {
        val profileId = requireBound()
        requireUnlocked()
        val mnemonic = repo.revealMnemonic(profileId) ?: return null
        val index = repo.nextDerivationIndex(profileId, chainType)
        return withContext(cryptoDispatcher) {
            val seed = Mnemonics.toSeed(Mnemonics.normalize(mnemonic))
            val derived = deriveDerivedAccount(chainType, seed, index)
            repo.addDerivedAccount(
                profileId, chainType, derived.first, derived.second, "${chainType.displayName} ${index + 1}"
            )
        }
    }

    override suspend fun importAccount(
        chainType: ChainType,
        privateKey: String,
        label: String
    ): WalletAccountRecord? {
        val profileId = requireBound()
        requireUnlocked()
        val (address, storedKey) = parseImportedKey(chainType, privateKey)
        return repo.addImportedAccount(profileId, chainType, address, storedKey, label)
    }

    /**
     * Turns a pasted private key into (address, canonical stored form) using
     * the chain's own rules.
     *
     * Shared by [importAccount] and [restoreFromBackup] so the two can never
     * disagree about what a valid key is: a backup written by this app and
     * typed back in by hand must be accepted or rejected by the same code.
     * [parseSecp256k1Key]/[parseEd25519Seed] throw
     * [WalletException.InvalidParams] with the chain's own wording, which is
     * what a caller reports.
     */
    private fun parseImportedKey(chainType: ChainType, privateKey: String): Pair<String, String> {
        val trimmed = privateKey.trim()
        return when (chainType) {
            ChainType.EVM -> {
                val key = parseSecp256k1Key(trimmed, chainType)
                registry.evm.addressFromPrivateKey(key) to canonicalSecpKey(key)
            }
            ChainType.SOLANA -> {
                val seed = parseEd25519Seed(trimmed, chainType)
                val address = Base58.encode(Ed25519.publicKeyFromSeed(seed))
                address to Base58.encode(seed)
            }
            ChainType.APTOS -> {
                val seed = parseEd25519Seed(trimmed, chainType)
                val pubkey = Ed25519.publicKeyFromSeed(seed)
                aptosAddress(pubkey) to Hex.encode(seed)
            }
            ChainType.SUI -> {
                val seed = parseEd25519Seed(trimmed, chainType)
                val pubkey = Ed25519.publicKeyFromSeed(seed)
                suiAddress(pubkey) to Hex.encode(seed)
            }
            ChainType.COSMOS -> {
                val key = parseSecp256k1Key(trimmed, chainType)
                val compressed = Bip32PrivateKey.compressedPublicKeyOf(key)
                val hrp = cosmosHomeNetwork().bech32Hrp ?: "cosmos"
                registry.cosmos.bech32Address(compressed, hrp) to canonicalSecpKey(key)
            }
            ChainType.BITCOIN -> {
                val key = parseSecp256k1Key(trimmed, chainType)
                val compressed = Bip32PrivateKey.compressedPublicKeyOf(key)
                registry.bitcoin.p2wpkhAddress(compressed, testnet = false) to canonicalSecpKey(key)
            }
            ChainType.TRON -> {
                val key = parseSecp256k1Key(trimmed, chainType)
                registry.tron.addressFromPrivateKey(key) to canonicalSecpKey(key)
            }
            ChainType.OCTRA -> {
                val seed = parseEd25519Seed(trimmed, chainType)
                val derived = registry.octra.accountFromSeed(seed)
                derived.address to Hex.encode(seed)
            }
        }
    }

    /**
     * Restores a wallet from an opened backup file. See [WalletEngineApi].
     *
     * ORDER MATTERS AND IS LOAD-BEARING. The wallet row is written first and
     * the accounts hang off it, so there is no window where accounts exist
     * without a wallet to own them. The phrase goes in before any imported
     * key, so a file that names a chain this build does not know still
     * restores its derived accounts.
     *
     * FAILURE OF ONE KEY IS NOT FAILURE OF THE RESTORE: each imported key is
     * attempted on its own and a bad one is recorded in
     * [RestoreReport.skipped]. A restore that aborts on the first unparseable
     * row would leave a half-built wallet behind AND lose the readable
     * accounts, which is the worst of both.
     *
     * Refuses to run over an existing wallet: this replaces nothing. The
     * caller deletes first, as a separate confirmed step.
     */
    override suspend fun restoreFromBackup(
        payload: WalletBackup.Payload,
        enabledChains: List<ChainType>
    ): RestoreReport {
        val profileId = requireBound()
        requireNotLocked()
        if (repo.wallet(profileId) != null) {
            throw WalletException.InvalidParams(
                "This profile already has a wallet. Delete it first if you mean to replace it."
            )
        }
        val phrase = payload.mnemonic?.trim()?.takeIf { it.isNotEmpty() }
        if (phrase != null && !Mnemonics.isValid(phrase)) {
            throw WalletException.InvalidParams(
                "The recovery phrase in this file is not a valid BIP39 phrase"
            )
        }
        val label = payload.walletLabel.trim().takeIf { it.isNotEmpty() } ?: "Wallet"

        // A wallet with a phrase gets its index-0 accounts derived exactly as
        // a freshly imported wallet would; one without is created empty and
        // filled entirely by the imported keys below.
        val normalized = phrase?.let { Mnemonics.normalize(it) }
        repo.createWallet(profileId, label, normalized)
        if (normalized != null) {
            seedInitialAccounts(profileId, normalized, enabledChains)
        }

        // Compare against what is on disk, not against the file: the phrase
        // just derived a row per enabled chain, and a file that also lists
        // one of those addresses as an imported key would otherwise create a
        // second account pointing at the same address.
        val taken = repo.accounts(profileId)
            .map { it.chainType to it.address.lowercase() }
            .toMutableSet()
        val skipped = mutableListOf<RestoreReport.SkippedKey>()
        var imported = 0
        payload.accounts.forEach { entry ->
            val key = entry.privateKey?.trim()
            if (key.isNullOrEmpty()) return@forEach  // derived rows carry no key by design
            val chain = ChainType.fromName(entry.chain)
                ?: ChainType.entries.firstOrNull {
                    it.displayName.equals(entry.chain, ignoreCase = true)
                }
            if (chain == null) {
                skipped.add(
                    RestoreReport.SkippedKey(entry.chain, entry.label, "unknown chain for this build")
                )
                return@forEach
            }
            val parsed = runCatching { parseImportedKey(chain, key) }
            val address = parsed.getOrNull()?.first
            if (address == null) {
                val reason = parsed.exceptionOrNull()?.message ?: "not a valid private key"
                skipped.add(RestoreReport.SkippedKey(entry.chain, entry.label, reason))
                return@forEach
            }
            if (!taken.add(chain to address.lowercase())) {
                // Already restored by the phrase above (or repeated in the
                // file). Not a failure: the account exists, which is what the
                // file asked for.
                return@forEach
            }
            runCatching {
                repo.addImportedAccount(
                    profileId,
                    chain,
                    address,
                    parsed.getOrThrow().second,
                    entry.label.takeIf { it.isNotBlank() } ?: "${chain.displayName} (imported)"
                )
            }.onSuccess {
                imported++
            }.onFailure { failure ->
                taken.remove(chain to address.lowercase())
                skipped.add(
                    RestoreReport.SkippedKey(
                        entry.chain,
                        entry.label,
                        failure.message ?: failure.javaClass.simpleName
                    )
                )
            }
        }
        return RestoreReport(
            walletLabel = label,
            phraseRestored = normalized != null,
            derivedAccountCount = if (normalized == null) 0 else enabledChains.size,
            importedAccountCount = imported,
            skipped = skipped
        )
    }

    override suspend fun renameAccount(accountId: String, label: String) {
        requireBound()
        repo.renameAccount(accountId, label)
    }

    override suspend fun removeAccount(accountId: String) {
        requireBound()
        repo.removeAccount(accountId)
    }

    /**
     * Canonical per-chain derivation through the chain's own adapter.
     * Returns (address, path). Pure CPU — call inside [cryptoDispatcher].
     */
    private fun deriveDerivedAccount(
        chainType: ChainType,
        seed: ByteArray,
        index: Int
    ): Pair<String, String> = when (chainType) {
        ChainType.EVM -> registry.evm.deriveAccount(seed, index).let { it.address to it.path }
        ChainType.SOLANA -> registry.solana.deriveAccount(seed, index).let { it.address to it.path }
        ChainType.APTOS -> registry.aptos.deriveAccount(seed, index).let { it.address to it.path }
        ChainType.SUI -> registry.sui.deriveAccount(seed, index).let { it.address to it.path }
        ChainType.COSMOS -> registry.cosmos.deriveAccount(seed, cosmosHomeNetwork(), index)
            .let { it.address to it.path }
        ChainType.BITCOIN -> registry.bitcoin.deriveAccount(seed, bitcoinHomeNetwork(), index)
            .let { it.address to it.path }
        ChainType.TRON -> registry.tron.deriveAccount(seed, index).let { it.address to it.path }
        ChainType.OCTRA -> registry.octra.deriveAccount(seed, index).let { it.address to it.path }
    }

    /** The chain's canonical home network for derivation (Cosmos: coin 118 + hrp). */
    private fun cosmosHomeNetwork(): NetworkConfig =
        registry.defaultNetworks(ChainType.COSMOS).first()

    /** Bitcoin mainnet home (coin 0, hrp "bc") for derivation. */
    private fun bitcoinHomeNetwork(): NetworkConfig =
        registry.defaultNetworks(ChainType.BITCOIN).first()

    // ------------------------------------------------------------------
    // Balances
    // ------------------------------------------------------------------

    override suspend fun refreshBalances() {
        val profileId = requireBound()
        val current = repo.accounts(profileId)
        if (current.isEmpty()) {
            balancesState.value = emptyMap()
            return
        }
        // One async per account: a failure stays inside its own child (no
        // sibling cancellation) and the shared map is assembled by THIS
        // coroutine only — the children inherit the caller's dispatcher,
        // which is not guaranteed to be single-threaded.
        val fetched: List<Pair<String, BalanceResult?>> = coroutineScope {
            current.map { account -> async { account.id to fetchBalanceQuietly(account, profileId) } }
                .awaitAll()
        }
        val results = mutableMapOf<String, BalanceResult>()
        fetched.forEach { (id, result) -> if (result != null) results[id] = result }
        // Merge: keep stale entries for accounts that went silent this round
        // (offline = quiet, never an error row), drop entries of removed accounts.
        val alive = current.map { it.id }.toSet()
        balancesState.value = balancesState.value.filterKeys { it in alive } + results
    }

    /** One account's balance, with failures downgraded to [BalanceResult.Error]. */
    private suspend fun fetchBalanceQuietly(
        account: WalletAccountRecord,
        profileId: ProfileId
    ): BalanceResult? = try {
        fetchAccountBalance(account, profileId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: WalletException) {
        // One account's failure marks only that account; the siblings keep going.
        BalanceResult.Error(e.message ?: "Balance query failed")
    } catch (_: Exception) {
        BalanceResult.Error("Balance query failed")
    }

    /**
     * One account's balance against the chain's ACTIVE network. Returns null
     * for offline silence (no active network / adapter returned null); throws
     * [WalletException] for explicit RPC failures. Internal open: JVM tests
     * fake the network leg here.
     */
    internal open suspend fun fetchAccountBalance(
        account: WalletAccountRecord,
        profileId: ProfileId
    ): BalanceResult? {
        val network = repo.activeNetwork(profileId, account.chainType) ?: return null
        val address = account.address
        val amount: String? = when (account.chainType) {
            ChainType.EVM -> registry.evm.getBalance(network, address)
                ?.let { formatBaseUnits(it, network.nativeDecimals) }
            ChainType.SOLANA -> registry.solana.getBalance(network, address)
                ?.let { formatBaseUnits(BigInteger.valueOf(it), network.nativeDecimals) }
            ChainType.APTOS -> registry.aptos.getBalance(network, address)
                ?.let { formatBaseUnits(BigInteger.valueOf(it), network.nativeDecimals) }
            ChainType.SUI -> registry.sui.getBalance(network, address)
                ?.let { formatBaseUnits(BigInteger.valueOf(it), network.nativeDecimals) }
            ChainType.COSMOS -> registry.cosmos.getBalance(network, address)
                ?.let { raw -> cosmosRawBalance(raw, network) }
            ChainType.BITCOIN -> formatBaseUnits(
                BigInteger.valueOf(registry.bitcoin.getBalanceSats(network, address)),
                network.nativeDecimals
            )
            ChainType.TRON -> registry.tron.getTrxBalance(network, address)
                ?.let { formatBaseUnits(BigInteger.valueOf(it), network.nativeDecimals) }
            ChainType.OCTRA -> registry.octra.getBalance(network, address)
                ?.let { formatBaseUnits(BigInteger.valueOf(it), network.nativeDecimals) }
        }
        return amount?.let { BalanceResult.Ok(it, network.nativeSymbol) }
    }

    /** "1234 uatom" (adapter shape) → display decimals; symbol comes from the network. */
    private fun cosmosRawBalance(raw: String, network: NetworkConfig): String? {
        val amount = raw.trim().split(' ', limit = 2).firstOrNull()?.toBigIntegerOrNull()
            ?: return null
        return formatBaseUnits(amount, network.nativeDecimals)
    }

    // ------------------------------------------------------------------
    // Networks
    // ------------------------------------------------------------------

    override suspend fun setActiveNetwork(chainType: ChainType, networkId: String) {
        val profileId = requireBound()
        val record = repo.networks(profileId)
            .firstOrNull { it.config.id == networkId }
            ?: throw WalletException.InvalidParams("Unknown network '$networkId'")
        if (record.config.chainType != chainType) {
            throw WalletException.InvalidParams(
                "Network '$networkId' is not a ${chainType.displayName} network"
            )
        }
        repo.setActiveNetwork(profileId, chainType, networkId)
        refreshActiveNetworksNow(profileId)
    }

    override suspend fun addCustomNetwork(config: NetworkConfig): Boolean {
        val profileId = requireBound()
        if (!isValidNetworkConfig(config)) return false
        repo.upsertCustomNetwork(profileId, config)
        refreshActiveNetworksNow(profileId)
        return true
    }

    override suspend fun removeCustomNetwork(networkId: String) {
        val profileId = requireBound()
        repo.removeCustomNetwork(profileId, networkId)
        refreshActiveNetworksNow(profileId)
    }

    override suspend fun setNetworkEnabled(networkId: String, enabled: Boolean) {
        val profileId = requireBound()
        repo.setNetworkEnabled(profileId, networkId, enabled)
        refreshActiveNetworksNow(profileId)
    }

    /**
     * Refreshes the Chainlist catalog and upserts the networks that are not
     * already known, disabled by default (they are discovery results, not
     * user choices). Returns how many are newly known; 0 offline — never throws.
     */
    override suspend fun refreshChainlist(): Int {
        val profileId = requireBound()
        return try {
            val catalog = fetchChainlistCatalog()
            val known = repo.networks(profileId).map { it.config.id }.toHashSet()
            val fresh = catalog.filter { it.id !in known }
            fresh.forEach { config ->
                repo.upsertCustomNetwork(profileId, config)
                repo.setNetworkEnabled(profileId, config.id, false)
            }
            refreshActiveNetworksNow(profileId)
            fresh.size
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            0
        }
    }

    /**
     * The live Chainlist catalog. Internal open: JVM tests inject a fixed
     * list (the real call goes to chainid.network).
     */
    internal open suspend fun fetchChainlistCatalog(): List<NetworkConfig> =
        registry.chainlist.catalog(forceRefresh = true)

    /**
     * Shared validation for UI-added networks and wallet_addEthereumChain.
     *
     * [requireTls] separates the two callers. A network the USER types into
     * Settings may be plaintext: `http://127.0.0.1:8545` is how a local dev
     * node is reached, and refusing it would remove a legitimate capability
     * from someone who already knows what they are doing. A network a WEB PAGE
     * proposes may not: `wallet_addEthereumChain` lets an arbitrary dApp pick
     * the endpoint every later balance read, fee estimate and
     * `eth_sendRawTransaction` of that chain travels through, and over
     * plaintext any network observer can both read those addresses and amounts
     * and rewrite the responses the signing UI shows. That is a prompt the
     * user cannot meaningfully audit, so the transport is required to be TLS.
     *
     * "Required to be TLS" means EVERY endpoint in the list, not merely one of
     * them. The check used to be `any { it.startsWith("https://") }`, which a
     * proposal of `["http://plain.example", "https://good.example"]` satisfies
     * — and [RpcEndpointChain] tries the list in order, so the plaintext host
     * is the one every balance read, fee estimate and signed transaction would
     * actually travel through. The https entry was a fig leaf that made the
     * list pass while the traffic went out in the clear. `all` is what the
     * sentence above has always claimed.
     *
     * The user's own path keeps `any`: a list of a local `http://` node plus a
     * remote https fallback is a legitimate thing to type in, and the user is
     * the one who typed it.
     */
    internal fun isValidNetworkConfig(
        config: NetworkConfig,
        requireTls: Boolean = false
    ): Boolean {
        val decimalOrHexChainId = config.chainId.toLongOrNull() != null ||
            config.chainId.removePrefix("0x").toLongOrNull(16) != null
        val hasUsableRpc = if (requireTls) {
            // isNotEmpty() is load-bearing: `all` is vacuously true on an empty
            // list, which would make a config with no endpoints at all "usable".
            config.rpcUrls.isNotEmpty() && config.rpcUrls.all { it.startsWith("https://") }
        } else {
            config.rpcUrls.any {
                it.startsWith("https://") || it.startsWith("http://")
            }
        }
        return config.id.isNotBlank() &&
            config.name.isNotBlank() &&
            hasUsableRpc &&
            config.nativeDecimals > 0 &&
            (config.chainType != ChainType.EVM || decimalOrHexChainId)
    }

    /**
     * Rebuilds activeNetworks from the repository (there is no observe API
     * for the active row, so this polls on network-list changes and writes).
     * Cancel-and-replace per call (Task 1-a hygiene).
     */
    private fun refreshActiveNetworks(profileId: ProfileId) {
        activeNetworksJob?.cancel()
        activeNetworksJob = engineScope.launch { refreshActiveNetworksNow(profileId) }
    }

    private suspend fun refreshActiveNetworksNow(profileId: ProfileId) {
        val chains = networksState.value.map { it.config.chainType }.distinct()
        val map = mutableMapOf<ChainType, NetworkConfig>()
        chains.forEach { chain ->
            quiet { repo.activeNetwork(profileId, chain) }?.let { map[chain] = it }
        }
        activeNetworksState.value = map
    }

    // ------------------------------------------------------------------
    // Sending (dashboard)
    // ------------------------------------------------------------------

    override suspend fun estimateSendFee(
        chainType: ChainType,
        networkId: String,
        fromAccountId: String,
        to: String,
        amount: String
    ): FeeEstimate? {
        return try {
            val profileId = profileState.value ?: return null
            val account = repo.accounts(profileId).firstOrNull { it.id == fromAccountId } ?: return null
            val network = repo.networks(profileId)
                .firstOrNull { it.config.id == networkId }?.config ?: return null
            if (network.chainType != chainType || account.chainType != chainType) return null
            when (chainType) {
                ChainType.EVM -> {
                    val value = parseAmountToBaseUnits(amount, network.nativeDecimals) ?: return null
                    evmFeeEstimate(network, account.address, to.takeIf { it.isNotBlank() }, value)
                        ?.let { wei ->
                            FeeEstimate(
                                label = "Estimated gas fee",
                                estimatedCost = formatBaseUnits(wei, network.nativeDecimals) +
                                    " " + network.nativeSymbol
                            )
                        }
                }
                ChainType.OCTRA -> {
                    val feeRaw = octraFeeEstimate(network) ?: return null
                    FeeEstimate(
                        label = "Network fee",
                        estimatedCost = formatBaseUnits(BigInteger(feeRaw), network.nativeDecimals) +
                            " " + network.nativeSymbol
                    )
                }
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * EVM gas*price estimate in base units. Internal open: JVM tests inject
     * the result (the real path is two RPC calls).
     *
     * Takes the NETWORK, not one endpoint url: the estimate must fail over
     * across the network's endpoints like every other RPC call, and the chain
     * that knows how to do that is built by the adapter.
     */
    internal open suspend fun evmFeeEstimate(
        network: NetworkConfig,
        from: String,
        to: String?,
        value: BigInteger
    ): BigInteger? {
        val chain = registry.evm.endpointsOf(network)
        val fees = registry.evm.suggestFees(chain) ?: return null
        val gas = try {
            registry.evm.estimateGas(chain, from, to, value, "0x")
        } catch (_: WalletException) {
            return null
        }
        return gas.multiply(fees.first)
    }

    /**
     * Octra's flat fee in raw units — the same value [OctraAdapter.sendNative]
     * will attach, so the sheet quotes the number it will actually pay.
     * Internal open: JVM tests inject the result (the real path is one RPC call).
     */
    internal open suspend fun octraFeeEstimate(network: NetworkConfig): String? =
        registry.octra.recommendedFeeOf(network)

    override suspend fun sendNative(
        accountId: String,
        networkId: String,
        to: String,
        amount: String
    ): BroadcastResult {
        val profileId = requireBound()
        requireUnlocked()
        val account = repo.accounts(profileId).firstOrNull { it.id == accountId }
            ?: throw WalletException.InvalidParams("Unknown account '$accountId'")
        val network = repo.networks(profileId)
            .firstOrNull { it.config.id == networkId }?.config
            ?: throw WalletException.InvalidParams("Unknown network '$networkId'")
        if (network.chainType != account.chainType) {
            throw WalletException.InvalidParams(
                "Network '${network.name}' does not match the account's chain ${account.chainType.displayName}"
            )
        }
        validateRecipient(account.chainType, network, to)
        val baseAmount = parseAmountToBaseUnits(amount, network.nativeDecimals)
            ?: throw WalletException.InvalidParams("Invalid amount '$amount'")
        val result = signAndBroadcastNative(account, network, to, baseAmount)
        return when (result) {
            is BroadcastResult.Ok -> {
                recordActivity(
                    kind = WalletActivityRecord.Kind.SEND,
                    account = account,
                    network = network,
                    toAddress = to,
                    displayAmount = "${AmountFormat.display(amount)} ${network.nativeSymbol}",
                    hash = result.hash,
                    explorer = explorerUrl(account.chainType, network, result.hash)
                )
                result
            }
            is BroadcastResult.Error ->
                throw WalletException.RpcError(-1, result.message)
        }
    }

    /**
     * The per-chain native send (key reveal + adapter send path). Internal
     * open: JVM tests fake this seam so the happy path needs no network.
     */
    internal open suspend fun signAndBroadcastNative(
        account: WalletAccountRecord,
        network: NetworkConfig,
        to: String,
        baseAmount: BigInteger
    ): BroadcastResult {
        val longAmount = toLongAmount(baseAmount, account.chainType)
        return when (account.chainType) {
            ChainType.EVM -> {
                val key = secpPrivateKey(account)
                val (_, signedRaw) = registry.evm.prepareAndSign(
                    network, key, account.address, to, baseAmount, "0x",
                    null, null, null, null, null
                )
                registry.evm.broadcastRaw(network, signedRaw)
            }
            ChainType.SOLANA ->
                registry.solana.sendNative(
                    network, ed25519Seed(account), account.address, to, longAmount
                )
            ChainType.APTOS -> {
                val seed = ed25519Seed(account)
                try {
                    val submitted = registry.aptos.signAndSubmit(
                        network, seed, Ed25519.publicKeyFromSeed(seed), account.address,
                        aptosTransferPayload(to, baseAmount)
                    )
                    BroadcastResult.Ok(submitted.hash)
                } catch (e: WalletException) {
                    BroadcastResult.Error(e.message ?: "Aptos submit failed")
                }
            }
            ChainType.SUI -> {
                val seed = ed25519Seed(account)
                registry.sui.sendSui(
                    network, seed, Ed25519.publicKeyFromSeed(seed),
                    account.address, to, longAmount
                )
            }
            ChainType.COSMOS -> {
                val key = secpPrivateKey(account)
                registry.cosmos.sendNative(
                    network, key, Bip32PrivateKey.compressedPublicKeyOf(key),
                    account.address, to, baseAmount.toString(), cosmosBaseDenom(network)
                )
            }
            ChainType.BITCOIN -> {
                val key = secpPrivateKey(account)
                val prepared = registry.bitcoin.buildAndSignSend(
                    network, key, account.address, to, longAmount
                )
                registry.bitcoin.broadcast(network, prepared.rawSignedHex)
            }
            ChainType.TRON ->
                registry.tron.sendTrx(
                    network, secpPrivateKey(account), account.address, to, longAmount
                )
            ChainType.OCTRA ->
                registry.octra.sendNative(
                    network, octraSeed(account), account.address, to, longAmount
                )
        }
    }

    /** entry_function_payload for 0x1::aptos_account::transfer. */
    private fun aptosTransferPayload(to: String, amount: BigInteger): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("entry_function_payload"))
            put("function", JsonPrimitive("0x1::aptos_account::transfer"))
            put("type_arguments", JsonArray(emptyList()))
            put("arguments", buildJsonArray {
                add(JsonPrimitive(to))
                add(JsonPrimitive(amount.toString()))
            })
        }

    /** Best-effort LCD base denom from the symbol (uatom/osmo/utia; INJ is "inj"). */
    private fun cosmosBaseDenom(network: NetworkConfig): String {
        val symbol = network.nativeSymbol.lowercase()
        return if (network.nativeDecimals == 18) symbol else "u$symbol"
    }

    private fun validateRecipient(chainType: ChainType, network: NetworkConfig, to: String) {
        val ok = when (chainType) {
            ChainType.EVM -> registry.evm.isValidAddress(to)
            ChainType.SOLANA -> registry.solana.isValidAddress(to)
            ChainType.APTOS -> registry.aptos.isValidAddress(to)
            ChainType.SUI -> registry.sui.isValidAddress(to)
            ChainType.COSMOS -> registry.cosmos.isValidAddress(to, network.bech32Hrp)
            ChainType.BITCOIN -> registry.bitcoin.isValidAddress(to, network.isTestnet)
            ChainType.TRON -> registry.tron.isValidAddress(to)
            ChainType.OCTRA -> registry.octra.isValidAddress(to)
        }
        if (!ok) {
            throw WalletException.InvalidParams(
                "Invalid ${chainType.displayName} recipient address '$to'"
            )
        }
    }

    // ------------------------------------------------------------------
    // dApp request pipeline
    // ------------------------------------------------------------------

    /**
     * Requests that arrived before the first [bind]. The bind is deferred
     * 2.5 s after startup on purpose — it class-loads the crypto stack, and
     * doing that inline stalled first paint — so a page that connects on load
     * lands in that window. Answering 4900 there is a claim the page acts on:
     * EIP-1193 defines it as "the provider is disconnected", and a toolkit
     * surfaces it once without retrying, so the user is told the wallet could
     * not be reached by a wallet that was half a second from answering. These
     * wait for the bind instead; only one that never comes (see
     * [HELD_REQUEST_TIMEOUT_MS]) still answers DISCONNECTED.
     */
    private val heldRequests =
        LinkedHashMap<String, Pair<DappRequest, (DappOutcome) -> Unit>>()
    private var heldTimeout: Job? = null

    /**
     * Bridge entry point. Semantically-identical requests from the same host
     * (e.g. a second Connect while one is already showing) are COALESCED: no
     * second prompt appears; when the original settles, every coalesced
     * request settles with the SAME outcome under its own id. Request ids
     * are unique per caller; re-submitting an id that is already pending
     * replaces its callback and its queue entry — never a second prompt and
     * never a stale coalesced mapping.
     *
     * A [DappRequest.SwitchChain] is the one request that can be settled here
     * without ever reaching the queue — see [settleSwitchWithoutPrompt].
     */
    override fun submitDappRequest(request: DappRequest, onSettled: (DappOutcome) -> Unit) {
        if (profileState.value == null) {
            holdUntilBound(request, onSettled)
            return
        }
        if (request is DappRequest.SwitchChain && settleSwitchWithoutPrompt(request, onSettled)) {
            return
        }
        enqueueDappRequest(request, onSettled)
    }

    /**
     * EIP-3326's two prompt-free outcomes: a switch to the chain already
     * active changes nothing, and one to a chain this wallet does not serve
     * can only settle 4902. A prompt for either is a sheet whose every button
     * is wrong — dismissing it settles 4001, which ends the dApp's connect.
     * Returns true when the request is settled and must not be queued.
     */
    private fun settleSwitchWithoutPrompt(
        request: DappRequest.SwitchChain,
        onSettled: (DappOutcome) -> Unit
    ): Boolean {
        val profileId = profileState.value ?: return false
        // Already on it: the state this engine publishes, no round trip.
        if (activeNetworksState.value[request.chainType]?.id == request.targetNetworkId) {
            onSettled(DappOutcome(request.id, null, null))
            return true
        }
        val known = networksState.value.firstOrNull { it.config.id == request.targetNetworkId }
        if (known != null) {
            // A row this list carries is the current answer — it is fed by the
            // row's own flow. Switchable ones prompt as before.
            if (known.enabled && known.config.chainType == request.chainType) return false
            onSettled(unrecognizedChainOutcome(request))
            return true
        }
        // Not in the list at all, which includes "the list has not filled yet"
        // right after a bind. Confirm against the repository rather than
        // answering 4902 from a list that may only be early: pushing a working
        // chain down the add-chain path is the worse mistake.
        engineScope.launch {
            val record = quiet {
                repo.networks(profileId).firstOrNull { it.config.id == request.targetNetworkId }
            }
            val active = quiet { repo.activeNetwork(profileId, request.chainType) }
            when {
                record == null || !record.enabled || record.config.chainType != request.chainType ->
                    onSettled(unrecognizedChainOutcome(request))
                active?.id == request.targetNetworkId ->
                    onSettled(DappOutcome(request.id, null, null))
                else -> enqueueDappRequest(request, onSettled)
            }
        }
        return true
    }

    private fun unrecognizedChainOutcome(request: DappRequest.SwitchChain) = DappOutcome(
        request.id, null,
        WalletBridgeError(
            WalletBridgeError.UNRECOGNIZED_CHAIN,
            "Unrecognized chain '" + request.targetNetworkId + "'"
        )
    )

    /** The queueing half of [submitDappRequest]. */
    private fun enqueueDappRequest(request: DappRequest, onSettled: (DappOutcome) -> Unit) {
        // A stale coalesced mapping for this id must never steal the new settle.
        coalescedInto.remove(request.id)
        val twin = pendingRequestsState.value.firstOrNull {
            it.id != request.id && it.sameSemanticsAs(request)
        }
        if (twin != null) {
            coalescedInto[request.id] = twin.id
            dappCallbacks[request.id] = onSettled
            return
        }
        dappCallbacks[request.id] = onSettled
        val current = pendingRequestsState.value
        pendingRequestsState.value = if (current.any { it.id == request.id }) {
            current.map { if (it.id == request.id) request else it }
        } else {
            current + request
        }
    }

    private fun holdUntilBound(request: DappRequest, onSettled: (DappOutcome) -> Unit) {
        if (heldRequests.size >= MAX_HELD_REQUESTS) {
            onSettled(disconnectedFailure(request.id))
            return
        }
        heldRequests[request.id] = request to onSettled
        if (heldTimeout?.isActive != true) {
            heldTimeout = engineScope.launch {
                delay(HELD_REQUEST_TIMEOUT_MS)
                // Cleared BEFORE the release: that call cancels the timeout
                // job, and cancelling the job it is running inside would be
                // cancelling itself.
                heldTimeout = null
                releaseHeldRequests(disconnected = true)
            }
        }
    }

    private fun releaseHeldRequests(disconnected: Boolean) {
        heldTimeout?.cancel()
        heldTimeout = null
        if (heldRequests.isEmpty()) return
        val held = heldRequests.values.toList()
        heldRequests.clear()
        held.forEach { (request, onSettled) ->
            if (disconnected) onSettled(disconnectedFailure(request.id))
            else submitDappRequest(request, onSettled)
        }
    }

    private fun disconnectedFailure(id: String) = DappOutcome(
        id, null,
        WalletBridgeError(WalletBridgeError.DISCONNECTED, "Wallet disconnected")
    )

    /**
     * UI entry point. Unknown/already-settled ids are silent no-ops. The
     * decision executes on the engine scope: state on Main.immediate, crypto
     * on [cryptoDispatcher]; settle callbacks run exactly once, on main.
     */
    override fun decideDappRequest(decision: DappDecision) {
        val original = pendingRequestsState.value.firstOrNull { it.id == decision.requestId } ?: return
        val profileId = profileState.value ?: return
        pendingRequestsState.value = pendingRequestsState.value.filterNot { it.id == original.id }
        engineScope.launch {
            val outcome = try {
                executeDecision(original, decision, profileId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: WalletLockedException) {
                DappOutcome(
                    original.id, null,
                    WalletBridgeError(WalletBridgeError.UNAUTHORIZED, "Unlock the wallet first")
                )
            } catch (e: WalletException.InvalidParams) {
                DappOutcome(
                    original.id, null,
                    WalletBridgeError(WalletBridgeError.INVALID_PARAMS, e.message ?: "Invalid parameters")
                )
            } catch (e: WalletException) {
                DappOutcome(
                    original.id, null,
                    WalletBridgeError(WalletBridgeError.INTERNAL, e.message ?: "Request failed")
                )
            } catch (e: Exception) {
                DappOutcome(
                    original.id, null,
                    WalletBridgeError(WalletBridgeError.INTERNAL, e.message ?: "Request failed")
                )
            }
            settleDappOutcome(original.id, outcome)
        }
    }

    override fun isDappPermitted(
        host: String,
        chainType: ChainType,
        accountAddress: String,
        method: String
    ): Boolean {
        if (host.isBlank()) return false
        return permissionsState.value.any {
            it.host == host && it.chainType == chainType &&
                it.accountAddress == accountAddress && method in it.methods
        }
    }

    /**
     * dApp-initiated disconnect (`window.solana.disconnect`): drops the
     * host's permission for one chain family, so the next connect prompts
     * again. Main-thread entry point (the bridge lives there); the repository
     * write happens on the engine scope, then the permission cache is
     * refreshed — until it lands, the host is still "permitted", which is the
     * safe direction (a permission is never widened by a race).
     */
    override fun revokeDappPermission(host: String, chainType: ChainType) {
        if (host.isBlank()) return
        val profileId = profileState.value ?: return
        engineScope.launch {
            quiet { repo.revokeDappPermission(profileId, host, chainType) }
            permissionsState.value = quiet { repo.allDappPermissions(profileId) } ?: emptyList()
        }
    }

    /** Executes one approved/rejected decision into a settle outcome. */
    private suspend fun executeDecision(
        request: DappRequest,
        decision: DappDecision,
        profileId: ProfileId
    ): DappOutcome {
        if (!decision.approved) {
            return DappOutcome(
                request.id, null,
                WalletBridgeError(WalletBridgeError.USER_REJECTED, "User rejected the request")
            )
        }
        return when (request) {
            is DappRequest.Connect -> executeConnect(request, decision, profileId)
            is DappRequest.SignMessage -> executeSignMessage(request, profileId)
            is DappRequest.SignTypedData -> executeSignTypedData(request, profileId)
            is DappRequest.SendTransaction -> executeSendTransaction(request, profileId)
            is DappRequest.SwitchChain -> executeSwitchChain(request, profileId)
            is DappRequest.AddChain -> executeAddChain(request, profileId)
        }
    }

    private suspend fun executeConnect(
        request: DappRequest.Connect,
        decision: DappDecision,
        profileId: ProfileId
    ): DappOutcome {
        val accounts = repo.accounts(profileId)
        val chosen = if (decision.chosenAccountId != null) {
            accounts.firstOrNull {
                it.id == decision.chosenAccountId && it.chainType == request.chainType
            }
                ?: return DappOutcome(
                    request.id, null,
                    WalletBridgeError(WalletBridgeError.UNAUTHORIZED, "Unknown account")
                )
        } else {
            accounts.firstOrNull { it.chainType == request.chainType }
                ?: return DappOutcome(
                    request.id, null,
                    WalletBridgeError(
                        WalletBridgeError.UNAUTHORIZED,
                        "No ${request.chainType.displayName} account to connect"
                    )
                )
        }
        repo.grantDappPermission(
            profileId, request.host, request.chainType, chosen.address,
            dappMethodsFor(request.chainType)
        )
        permissionsState.value = quiet { repo.allDappPermissions(profileId) } ?: emptyList()
        // Prompted Connects resolve with the bridge's per-chain success
        // shapes (EVM/SUI address ARRAY, SOLANA {"publicKey"},
        // APTOS/TRON {"address"}) — byte-identical to the bridge's own
        // silent auto-approve, so a dApp cannot tell the paths apart.
        // The public key rides along where the chain's conventions carry
        // one; null (locked wallet, unreadable key) leaves the shape as it
        // was rather than turning into an error.
        return DappOutcome(
            request.id,
            WalletBridgeProtocol.connectSuccessResult(
                request.chainType,
                chosen.address,
                publicKeyOf(chosen)
            ),
            null
        )
    }

    private suspend fun executeSignMessage(
        request: DappRequest.SignMessage,
        profileId: ProfileId
    ): DappOutcome {
        if (lockStateBacking.value != WalletLockState.UNLOCKED) {
            return DappOutcome(
                request.id, null,
                WalletBridgeError(WalletBridgeError.UNAUTHORIZED, "Unlock the wallet to sign")
            )
        }
        val account = findAccountForAddress(repo.accounts(profileId), request)
            ?: return DappOutcome(
                request.id, null,
                WalletBridgeError(WalletBridgeError.UNAUTHORIZED, "No matching account")
            )
        val signature = signDappMessage(account, request.message)
        recordActivity(
            kind = WalletActivityRecord.Kind.SIGN_MESSAGE,
            account = account,
            network = quiet { repo.activeNetwork(profileId, account.chainType) },
            toAddress = null,
            displayAmount = "message",
            hash = null,
            explorer = null
        )
        return DappOutcome(request.id, JsonPrimitive(signature).toString(), null)
    }

    private suspend fun executeSignTypedData(
        request: DappRequest.SignTypedData,
        profileId: ProfileId
    ): DappOutcome {
        if (request.chainType != ChainType.EVM) {
            return DappOutcome(
                request.id, null,
                WalletBridgeError(
                    WalletBridgeError.UNSUPPORTED_METHOD,
                    "eth_signTypedData_v4 is EVM-only"
                )
            )
        }
        if (lockStateBacking.value != WalletLockState.UNLOCKED) {
            return DappOutcome(
                request.id, null,
                WalletBridgeError(WalletBridgeError.UNAUTHORIZED, "Unlock the wallet to sign")
            )
        }
        val account = findAccountForAddress(repo.accounts(profileId), request)
            ?: return DappOutcome(
                request.id, null,
                WalletBridgeError(WalletBridgeError.UNAUTHORIZED, "No matching account")
            )
        val signature = withContext(cryptoDispatcher) {
            registry.evm.signTypedData(secpPrivateKey(account), request.typedDataJson)
        }
        recordActivity(
            kind = WalletActivityRecord.Kind.SIGN_MESSAGE,
            account = account,
            network = quiet { repo.activeNetwork(profileId, ChainType.EVM) },
            toAddress = null,
            displayAmount = "typed data",
            hash = null,
            explorer = null
        )
        return DappOutcome(request.id, JsonPrimitive(signature).toString(), null)
    }

    private suspend fun executeSendTransaction(
        request: DappRequest.SendTransaction,
        profileId: ProfileId
    ): DappOutcome {
        if (lockStateBacking.value != WalletLockState.UNLOCKED) {
            return DappOutcome(
                request.id, null,
                WalletBridgeError(WalletBridgeError.UNAUTHORIZED, "Unlock the wallet to send")
            )
        }
        if (request.chainType == ChainType.BITCOIN) {
            return DappOutcome(
                request.id, null,
                WalletBridgeError(
                    WalletBridgeError.UNSUPPORTED_METHOD,
                    "Bitcoin dApp transactions are not supported"
                )
            )
        }
        val account = findAccountForAddress(repo.accounts(profileId), request)
            ?: return DappOutcome(
                request.id, null,
                WalletBridgeError(WalletBridgeError.UNAUTHORIZED, "No matching account")
            )
        val network = resolveDappNetwork(request, profileId)
            ?: return DappOutcome(
                request.id, null,
                WalletBridgeError(
                    WalletBridgeError.CHAIN_DISCONNECTED,
                    "No active network for ${request.chainType.displayName}"
                )
            )
        val evmParams = if (request.chainType == ChainType.EVM) {
            parseEvmTransactionParams(request.txParamsJson)
        } else {
            null
        }
        val result = signAndBroadcastDappTransaction(request, account, network, evmParams)
        val resultJson = when {
            result.hash != null -> JsonPrimitive(result.hash).toString()
            result.signature != null -> JsonPrimitive(result.signature).toString()
            else -> return DappOutcome(
                request.id, null,
                WalletBridgeError(WalletBridgeError.INTERNAL, "No hash or signature produced")
            )
        }
        recordActivity(
            kind = WalletActivityRecord.Kind.DAPP_SEND,
            account = account,
            network = network,
            toAddress = evmParams?.to,
            displayAmount = dappDisplayAmount(evmParams, network, result.feeLabel),
            hash = result.hash ?: result.signature,
            explorer = result.hash?.let { explorerUrl(request.chainType, network, it) }
        )
        return DappOutcome(request.id, resultJson, null)
    }

    private suspend fun executeSwitchChain(
        request: DappRequest.SwitchChain,
        profileId: ProfileId
    ): DappOutcome {
        val record = repo.networks(profileId)
            .firstOrNull { it.config.id == request.targetNetworkId }
        // Reachable when the row was disabled or dropped between the check
        // [submitDappRequest] makes and the decision — the sheet is already up.
        if (record == null || !record.enabled || record.config.chainType != request.chainType) {
            return unrecognizedChainOutcome(request)
        }
        repo.setActiveNetwork(profileId, record.config.chainType, record.config.id)
        refreshActiveNetworksNow(profileId)
        // EIP-1193: a successful switch resolves with null.
        return DappOutcome(request.id, null, null)
    }

    private suspend fun executeAddChain(
        request: DappRequest.AddChain,
        profileId: ProfileId
    ): DappOutcome {
        if (!isValidNetworkConfig(request.proposed, requireTls = true)) {
            return DappOutcome(
                request.id, null,
                WalletBridgeError(
                    WalletBridgeError.INVALID_PARAMS,
                    "Proposed chain is not a usable network (an https:// RPC endpoint is required)"
                )
            )
        }
        repo.upsertCustomNetwork(profileId, request.proposed)
        // EIP-3085 leaves the switch to the wallet; every dApp toolkit assumes
        // MetaMask's. It reads 4902 from wallet_switchEthereumChain, calls
        // this, and never asks again — so resolving null while eth_chainId
        // still answers the old chain pins the page on "Wrong network" for
        // good, and leaves every bundled-but-disabled testnet unreachable.
        repo.networks(profileId)
            .firstOrNull { it.config.id == request.proposed.id }
            ?.takeIf { it.enabled }
            ?.let {
                repo.setActiveNetwork(profileId, it.config.chainType, it.config.id)
                refreshActiveNetworksNow(profileId)
            }
        return DappOutcome(request.id, null, null)
    }

    /**
     * The per-chain dApp transaction execution (parse + sign + broadcast).
     * Internal open: JVM tests fake this seam so the happy path needs no
     * network.
     */
    internal open suspend fun signAndBroadcastDappTransaction(
        request: DappRequest.SendTransaction,
        account: WalletAccountRecord,
        network: NetworkConfig,
        evmParams: EvmAdapter.TransactionParams?
    ): DappTransactionResult {
        when (account.chainType) {
            ChainType.EVM -> {
                val params = evmParams ?: parseEvmTransactionParams(request.txParamsJson)
                val to = params.to
                if (to != null && !registry.evm.isValidAddress(to)) {
                    throw WalletException.InvalidParams("Invalid 'to' address")
                }
                val key = secpPrivateKey(account)
                val (prepared, signedRaw) = registry.evm.prepareAndSign(
                    network, key, account.address, to, params.value, params.data,
                    params.gasLimit, params.gasPrice, params.maxFeePerGas,
                    params.maxPriorityFeePerGas, params.nonce
                )
                val feeLabel = formatBaseUnits(prepared.estimatedFeeWei, network.nativeDecimals) +
                    " " + network.nativeSymbol + " gas"
                return when (val sent = registry.evm.broadcastRaw(network, signedRaw)) {
                    is BroadcastResult.Ok ->
                        DappTransactionResult(hash = sent.hash, signature = null, feeLabel = feeLabel)
                    is BroadcastResult.Error ->
                        throw WalletException.RpcError(-1, sent.message)
                }
            }
            ChainType.SOLANA -> {
                val base64Tx = request.txParamsJson.trim().let {
                    (json.parseToJsonElement(it) as? JsonObject)
                        ?.get("transaction")?.jsonPrimitive?.content ?: it
                }
                registry.solana.parseTransaction(base64Tx) // validates signer membership
                val signed = registry.solana.signTransaction(
                    ed25519Seed(account), account.address, base64Tx
                )
                val chain = registry.solana.endpointsOf(network)
                if (chain.urls.isEmpty()) {
                    throw WalletException.InvalidParams("Network has no RPC endpoint")
                }
                return when (val sent = registry.solana.broadcast(chain, signed)) {
                    is BroadcastResult.Ok ->
                        DappTransactionResult(hash = sent.hash, signature = null, feeLabel = "5000 lamports")
                    is BroadcastResult.Error ->
                        throw WalletException.RpcError(-1, sent.message)
                }
            }
            ChainType.APTOS -> {
                val fields = json.parseToJsonElement(request.txParamsJson) as? JsonObject
                    ?: throw WalletException.InvalidParams("tx params must be a JSON object")
                val payload = fields["payload"] as? JsonObject
                if (payload != null) {
                    val seed = ed25519Seed(account)
                    val submitted = registry.aptos.signAndSubmit(
                        network, seed, Ed25519.publicKeyFromSeed(seed), account.address, payload
                    )
                    return DappTransactionResult(
                        hash = submitted.hash, signature = null,
                        feeLabel = "${submitted.maxGasAmount} gas @ ${submitted.gasUnitPrice}"
                    )
                }
                val txBytes = fields["txBytes"]?.jsonPrimitive?.content
                    ?: throw WalletException.InvalidParams("Missing payload or txBytes")
                // sign-only flow: the dApp broadcasts the signed transaction itself.
                val signature = registry.aptos.signSerializedTransaction(
                    ed25519Seed(account), account.address, txBytes
                )
                return DappTransactionResult(hash = null, signature = signature, feeLabel = null)
            }
            ChainType.SUI -> {
                val txBytes = (json.parseToJsonElement(request.txParamsJson) as? JsonObject)
                    ?.get("txBytes")?.jsonPrimitive?.content
                    ?: throw WalletException.InvalidParams("Missing txBytes")
                val seed = ed25519Seed(account)
                val signature = registry.sui.signTransaction(
                    seed, Ed25519.publicKeyFromSeed(seed), txBytes
                )
                return when (val sent = registry.sui.executeTransactionBlock(network, txBytes, signature)) {
                    is BroadcastResult.Ok ->
                        DappTransactionResult(hash = sent.hash, signature = null, feeLabel = "gas object")
                    is BroadcastResult.Error ->
                        throw WalletException.RpcError(-1, sent.message)
                }
            }
            ChainType.COSMOS -> {
                val fields = json.parseToJsonElement(request.txParamsJson) as? JsonObject
                    ?: throw WalletException.InvalidParams("tx params must be a JSON object")
                // Keplr signAmino / signDirect are SIGN-ONLY: the dApp gets the
                // signature back and broadcasts the transaction itself, so the
                // digest must cover exactly the doc the dApp supplied.
                (fields["aminoSignDoc"] as? JsonPrimitive)?.contentOrNull?.let { aminoDoc ->
                    val doc = try {
                        json.parseToJsonElement(aminoDoc)
                    } catch (_: SerializationException) {
                        throw WalletException.InvalidParams("aminoSignDoc is not valid JSON")
                    }
                    val signature = registry.cosmos.signAmino(secpPrivateKey(account), doc)
                    return DappTransactionResult(
                        hash = null, signature = signature.signatureBase64, feeLabel = null
                    )
                }
                val signOnly = (fields["signOnly"] as? JsonPrimitive)?.contentOrNull == "true"
                val body = fields["bodyBytes"]?.jsonPrimitive?.content?.let { decodeBase64(it) }
                    ?: throw WalletException.InvalidParams("Missing bodyBytes")
                val authInfo = fields["authInfoBytes"]?.jsonPrimitive?.content?.let { decodeBase64(it) }
                    ?: throw WalletException.InvalidParams("Missing authInfoBytes")
                val signature = registry.cosmos.signDirect(
                    secpPrivateKey(account),
                    CosmosAdapter.DirectSignDoc(
                        bodyBytes = body,
                        authInfoBytes = authInfo,
                        chainId = fields["chainId"]?.jsonPrimitive?.content ?: network.chainId,
                        accountNumber = fields["accountNumber"]?.jsonPrimitive?.content ?: "0"
                    )
                )
                if (signOnly) {
                    return DappTransactionResult(
                        hash = null, signature = signature.signatureBase64, feeLabel = null
                    )
                }
                val lcdChain = registry.cosmos.endpointsOf(network)
                if (lcdChain.urls.isEmpty()) {
                    throw WalletException.InvalidParams("Network has no LCD endpoint")
                }
                val txRaw = ProtoWriter()
                    .writeBytes(1, body)
                    .writeBytes(2, authInfo)
                    .writeBytes(3, decodeBase64(signature.signatureBase64))
                    .bytes()
                return when (val sent = registry.cosmos.broadcastTx(lcdChain, txRaw)) {
                    is BroadcastResult.Ok ->
                        DappTransactionResult(hash = sent.hash, signature = null, feeLabel = "2500 fee")
                    is BroadcastResult.Error ->
                        throw WalletException.RpcError(-1, sent.message)
                }
            }
            ChainType.TRON -> {
                val tx = json.parseToJsonElement(request.txParamsJson) as? JsonObject
                    ?: throw WalletException.InvalidParams("tx params must be a JSON object")
                val tronChain = registry.tron.endpointsOf(network)
                if (tronChain.urls.isEmpty()) {
                    throw WalletException.InvalidParams("Network has no RPC endpoint")
                }
                val (signed, _) = registry.tron.signTransaction(
                    secpPrivateKey(account), account.address, tx
                )
                return when (val sent = registry.tron.broadcast(tronChain, signed)) {
                    is BroadcastResult.Ok ->
                        DappTransactionResult(hash = sent.hash, signature = null, feeLabel = "bandwidth + energy")
                    is BroadcastResult.Error ->
                        throw WalletException.RpcError(-1, sent.message)
                }
            }
            ChainType.BITCOIN ->
                throw WalletException.UnsupportedMethod(
                    "Bitcoin dApp transactions are not supported"
                )
            // No published Octra provider API exists to route against, so the
            // bridge advertises `connect` only and never reaches here.
            ChainType.OCTRA ->
                throw WalletException.UnsupportedMethod(
                    "Octra dApp transactions are not supported"
                )
        }
    }

    /** Parses EVM dApp tx params (hex or decimal values) — pure, no network. */
    internal fun parseEvmTransactionParams(txParamsJson: String): EvmAdapter.TransactionParams {
        val obj = try {
            json.parseToJsonElement(txParamsJson)
        } catch (_: SerializationException) {
            throw WalletException.InvalidParams("tx params must be a JSON object")
        } as? JsonObject
            ?: throw WalletException.InvalidParams("tx params must be a JSON object")
        fun str(name: String): String? =
            (obj[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        fun num(name: String): BigInteger? = str(name)?.let {
            try {
                registry.evm.parseValue(it)
            } catch (e: WalletException.InvalidParams) {
                throw WalletException.InvalidParams("Invalid $name: ${e.message}")
            }
        }
        return EvmAdapter.TransactionParams(
            from = "",
            to = str("to"),
            value = num("value") ?: BigInteger.ZERO,
            data = str("data") ?: str("input") ?: "0x",
            gasLimit = num("gas") ?: num("gasLimit"),
            gasPrice = num("gasPrice"),
            maxFeePerGas = num("maxFeePerGas"),
            maxPriorityFeePerGas = num("maxPriorityFeePerGas"),
            nonce = num("nonce")
        )
    }

    /** The per-chain dApp message signing — local crypto only. */
    private suspend fun signDappMessage(account: WalletAccountRecord, message: String): String {
        val bytes = messageBytes(message)
        val cosmosChainId = if (account.chainType == ChainType.COSMOS) {
            quiet { repo.activeNetwork(requireBound(), ChainType.COSMOS) }?.chainId ?: ""
        } else {
            ""
        }
        return withContext(cryptoDispatcher) {
            when (account.chainType) {
                ChainType.EVM -> registry.evm.personalSign(secpPrivateKey(account), bytes)
                ChainType.SOLANA -> registry.solana.signMessage(ed25519Seed(account), bytes)
                ChainType.APTOS -> registry.aptos.signWalletMessage(
                    ed25519Seed(account), message, nonce = ""
                ).signatureHex
                ChainType.SUI -> registry.sui.signPersonalMessage(
                    ed25519Seed(account), Ed25519.publicKeyFromSeed(ed25519Seed(account)),
                    Base64.getEncoder().encodeToString(bytes)
                )
                ChainType.COSMOS -> registry.cosmos.signArbitrary(
                    secpPrivateKey(account), cosmosChainId, account.address, message
                ).signatureBase64
                ChainType.BITCOIN -> registry.bitcoin.signMessage(secpPrivateKey(account), bytes)
                ChainType.TRON -> registry.tron.signMessageV2(secpPrivateKey(account), bytes)
                ChainType.OCTRA -> throw WalletException.UnsupportedMethod(
                    "Octra message signing is not supported"
                )
            }
        }
    }

    /** personal_sign message bytes: hex when it looks like hex, else utf-8. */
    private fun messageBytes(message: String): ByteArray =
        if (message.length % 2 == 0 && message.startsWith("0x") &&
            message.drop(2).all { it in "0123456789abcdefABCDEF" }
        ) Hex.decode(message) else message.toByteArray(Charsets.UTF_8)

    private fun findAccountForAddress(
        accounts: List<WalletAccountRecord>,
        request: DappRequest
    ): WalletAccountRecord? {
        val address = when (request) {
            is DappRequest.SignMessage -> request.accountAddress
            is DappRequest.SignTypedData -> request.accountAddress
            is DappRequest.SendTransaction -> request.accountAddress
            else -> return null
        }
        return accounts.firstOrNull {
            it.chainType == request.chainType && addressesMatch(it.address, address, request.chainType)
        }
    }

    /** EVM addresses match case-insensitively (checksum vs lowercase); base58 exactly. */
    private fun addressesMatch(stored: String, requested: String, chainType: ChainType): Boolean =
        if (chainType == ChainType.EVM) stored.equals(requested, ignoreCase = true)
        else stored == requested

    private suspend fun resolveDappNetwork(
        request: DappRequest.SendTransaction,
        profileId: ProfileId
    ): NetworkConfig? {
        val all = repo.networks(profileId)
        request.networkId.takeIf { it.isNotBlank() }?.let { id ->
            all.firstOrNull { it.config.id == id && it.config.chainType == request.chainType }
                ?.let { return it.config }
        }
        return repo.activeNetwork(profileId, request.chainType)
    }

    private fun dappDisplayAmount(
        evmParams: EvmAdapter.TransactionParams?,
        network: NetworkConfig,
        feeLabel: String?
    ): String {
        val amount = evmParams?.value
            ?.takeIf { it.signum() > 0 }
            ?.let { formatBaseUnits(it, network.nativeDecimals) + " " + network.nativeSymbol }
            ?: "contract call"
        return if (feeLabel != null) "$amount (fee ~$feeLabel)" else amount
    }

    /**
     * Drops the pending requests of a page that has gone away (see
     * [WalletEngineApi.cancelDappRequests]). Main-thread only, like every
     * other dApp-queue mutation — [WalletBridge.dispose] runs there.
     */
    override fun cancelDappRequests(requestIds: Collection<String>) {
        if (requestIds.isEmpty()) return
        val ids = requestIds.toSet()
        pendingRequestsState.value = pendingRequestsState.value.filterNot { it.id in ids }
        ids.forEach { id ->
            settleDappOutcome(
                id,
                DappOutcome(
                    id, null,
                    WalletBridgeError(WalletBridgeError.DISCONNECTED, "Page closed")
                )
            )
            // A coalesced duplicate whose ORIGINAL belongs to a surviving page
            // is settled above by id; this clears the mapping either way so a
            // later settle of that original cannot look for a dead callback.
            coalescedInto.remove(id)
        }
    }

    /** Settles one original request AND every coalesced duplicate of it. */
    private fun settleDappOutcome(originalId: String, outcome: DappOutcome) {
        val ids = mutableListOf(originalId)
        val iterator = coalescedInto.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value == originalId) {
                ids += entry.key
                iterator.remove()
            }
        }
        ids.forEach { id ->
            val callback = dappCallbacks.remove(id) ?: return@forEach
            callback(outcome.copy(requestId = id))
        }
    }

    private fun settleAllPending(code: Int, message: String) {
        val requests = pendingRequestsState.value.toList()
        pendingRequestsState.value = emptyList()
        requests.forEach { request ->
            settleDappOutcome(
                request.id,
                DappOutcome(request.id, null, WalletBridgeError(code, message))
            )
        }
        dappCallbacks.clear()
        coalescedInto.clear()
    }

    /**
     * Canonical method set granted on Connect. The list lives in
     * [WalletBridgeProtocol.grantedMethodsFor] — the bridge, the injected
     * script and the tests all read the same table, so a method can never be
     * advertised here without a route to answer it (see that function's doc).
     */
    private fun dappMethodsFor(chainType: ChainType): List<String> =
        WalletBridgeProtocol.grantedMethodsFor(chainType)

    // ------------------------------------------------------------------
    // Key material (revealed per operation, never cached)
    // ------------------------------------------------------------------

    private suspend fun seedFor(profileId: ProfileId): ByteArray? =
        repo.revealMnemonic(profileId)?.let { mnemonic ->
            withContext(cryptoDispatcher) { Mnemonics.toSeed(Mnemonics.normalize(mnemonic)) }
        }

    /** secp256k1 scalar (EVM/Cosmos/Bitcoin/TRON) for derived OR imported accounts. */
    private suspend fun secpPrivateKey(account: WalletAccountRecord): BigInteger {
        if (account.source == WalletAccountRecord.Source.DERIVED) {
            val seed = seedFor(requireBound())
                ?: throw WalletException.Unauthorized("Wallet has no mnemonic")
            if (account.path.isBlank()) {
                throw WalletException.Unauthorized("Derived account has no path")
            }
            return withContext(cryptoDispatcher) {
                Bip32PrivateKey.derive(seed, account.path).key
            }
        }
        val key = repo.revealPrivateKey(account.id)
            ?: throw WalletException.Unauthorized("Account has no stored private key")
        return parseSecp256k1Key(key, account.chainType)
    }

    /** 32-byte ed25519 seed (Solana/Aptos/Sui) for derived OR imported accounts. */
    private suspend fun ed25519Seed(account: WalletAccountRecord): ByteArray {
        if (account.source == WalletAccountRecord.Source.DERIVED) {
            val seed = seedFor(requireBound())
                ?: throw WalletException.Unauthorized("Wallet has no mnemonic")
            if (account.path.isBlank()) {
                throw WalletException.Unauthorized("Derived account has no path")
            }
            return withContext(cryptoDispatcher) {
                Slip10Ed25519Key.derive(seed, account.path).seed
            }
        }
        val key = repo.revealPrivateKey(account.id)
            ?: throw WalletException.Unauthorized("Account has no stored private key")
        return parseEd25519Seed(key, account.chainType)
    }

    /**
     * Octra's account key for a derived OR imported account.
     *
     * Separate from [ed25519Seed] on purpose: that one walks `account.path`
     * with SLIP-10, and Octra has no derivation path — its key is a single
     * HMAC over the BIP-39 seed. Routing an Octra account through
     * [ed25519Seed] would run its "octra/0" string through a BIP-32 parser and
     * silently produce a key the chain does not recognise, which is the one
     * failure mode a wallet must never have.
     */
    private suspend fun octraSeed(account: WalletAccountRecord): ByteArray {
        if (account.source == WalletAccountRecord.Source.DERIVED) {
            val seed = seedFor(requireBound())
                ?: throw WalletException.Unauthorized("Wallet has no mnemonic")
            return withContext(cryptoDispatcher) {
                OctraAdapter.privateSeedFromBip39(seed)
            }
        }
        val key = repo.revealPrivateKey(account.id)
            ?: throw WalletException.Unauthorized("Account has no stored private key")
        return parseEd25519Seed(key, account.chainType)
    }

    private fun parseSecp256k1Key(key: String, chainType: ChainType): BigInteger {
        val clean = key.removePrefix("0x").removePrefix("0X")
        val parsed = runCatching { BigInteger(clean, 16) }.getOrNull()
            ?: throw WalletException.InvalidParams("Invalid ${chainType.displayName} private key")
        if (parsed.signum() <= 0 || parsed >= Bip32PrivateKey.CURVE_N) {
            throw WalletException.InvalidParams("${chainType.displayName} private key out of range")
        }
        return parsed
    }

    /** Solana imports are base58; Aptos/Sui SDKs use hex — accept either. */
    private fun parseEd25519Seed(key: String, chainType: ChainType): ByteArray =
        Base58.decodeOrNull(key)?.takeIf { it.size == 32 }
            ?: Hex.decodeOrNull(key)?.takeIf { it.size == 32 }
            ?: throw WalletException.InvalidParams(
                "Invalid ${chainType.displayName} private key (expected 32-byte base58 or hex)"
            )

    /** Stored-key form for secp chains: 64 hex chars, no 0x. */
    private fun canonicalSecpKey(key: BigInteger): String = key.toString(16).padStart(64, '0')

    private fun aptosAddress(pubkey: ByteArray): String =
        "0x" + Hex.encode(Hashes.sha3_256(pubkey + byteArrayOf(0x00)))

    private fun suiAddress(pubkey: ByteArray): String =
        "0x" + Hex.encode(Hashes.blake2b256(byteArrayOf(0x00) + pubkey))

    // ------------------------------------------------------------------
    // Activity log
    // ------------------------------------------------------------------

    private suspend fun recordActivity(
        kind: WalletActivityRecord.Kind,
        account: WalletAccountRecord,
        network: NetworkConfig?,
        toAddress: String?,
        displayAmount: String,
        hash: String?,
        explorer: String?
    ) {
        val profileId = profileState.value ?: return
        repo.recordActivity(
            WalletActivityRecord(
                id = UUID.randomUUID().toString(),
                profileId = profileId,
                chainType = account.chainType,
                networkName = network?.name ?: "",
                kind = kind,
                accountAddress = account.address,
                toAddress = toAddress,
                displayAmount = displayAmount,
                hash = hash,
                explorerUrl = explorer,
                createdAt = clock()
            )
        )
    }

    /** Best-effort explorer link per chain family. */
    private fun explorerUrl(chainType: ChainType, network: NetworkConfig, hash: String): String? {
        val base = network.explorerUrl?.trimEnd('/') ?: return null
        return when (chainType) {
            ChainType.EVM, ChainType.SOLANA, ChainType.SUI, ChainType.BITCOIN -> "$base/tx/$hash"
            ChainType.APTOS -> "$base/txn/$hash"
            ChainType.COSMOS -> "$base/txs/$hash"
            ChainType.TRON -> "$base/#/transaction/$hash"
            ChainType.OCTRA -> "$base/tx/$hash"
        }
    }

    // ------------------------------------------------------------------
    // Amount helpers
    // ------------------------------------------------------------------

    /** "0.1" (decimals=18) → 10^17; null on blank/negative/unparsable. */
    internal fun parseAmountToBaseUnits(amount: String, decimals: Int): BigInteger? = try {
        val parsed = BigDecimal(amount.trim())
        if (parsed.signum() < 0) null
        else parsed.movePointRight(decimals).toBigIntegerExact()
    } catch (_: NumberFormatException) {
        null
    } catch (_: ArithmeticException) {
        null
    }

    /** 10^17 → "0.1" (plain string, no scientific notation), display-truncated. */
    internal fun formatBaseUnits(value: BigInteger, decimals: Int): String =
        AmountFormat.fromBaseUnits(value, decimals)

    private fun toLongAmount(value: BigInteger, chainType: ChainType): Long {
        if (value.bitLength() > 62) {
            throw WalletException.InvalidParams("${chainType.displayName} amount too large")
        }
        return value.toLong()
    }

    private fun decodeBase64(text: String): ByteArray = Base64.getDecoder().decode(text)

    /** Runs [block], converting any failure (but never cancellation) to null. */
    private inline fun <T> quiet(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

/** Result of the dApp transaction seam: a broadcast hash and/or a raw signature. */
internal data class DappTransactionResult(
    val hash: String?,
    val signature: String?,
    val feeLabel: String?
)

/** Semantic-duplicate check for request coalescing (same host + same payload). */
internal fun DappRequest.sameSemanticsAs(other: DappRequest): Boolean {
    if (this::class != other::class) return false
    if (host != other.host || chainType != other.chainType) return false
    return when (this) {
        is DappRequest.Connect -> {
            val that = other as DappRequest.Connect
            originUrl == that.originUrl
        }
        is DappRequest.SignMessage -> {
            val that = other as DappRequest.SignMessage
            accountAddress == that.accountAddress && message == that.message
        }
        is DappRequest.SignTypedData -> {
            val that = other as DappRequest.SignTypedData
            accountAddress == that.accountAddress && typedDataJson == that.typedDataJson
        }
        is DappRequest.SendTransaction -> {
            val that = other as DappRequest.SendTransaction
            networkId == that.networkId && accountAddress == that.accountAddress &&
                txParamsJson == that.txParamsJson
        }
        is DappRequest.SwitchChain -> {
            val that = other as DappRequest.SwitchChain
            targetNetworkId == that.targetNetworkId
        }
        is DappRequest.AddChain -> {
            val that = other as DappRequest.AddChain
            proposed == that.proposed
        }
    }
}
