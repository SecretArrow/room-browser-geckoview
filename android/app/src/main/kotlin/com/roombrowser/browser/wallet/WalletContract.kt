package com.roombrowser.browser.wallet

import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.export.WalletBackup
import com.roombrowser.domain.wallet.model.BalanceResult
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.domain.wallet.model.FeeEstimate
import com.roombrowser.domain.wallet.model.NetworkConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * WALLET APP-SIDE CONTRACT — the frozen API surface between the wallet
 * data layer (WalletRepository), the engine (WalletEngine), the dApp
 * bridge (RoomWalletScript/WalletBridge) and the UI (WalletActivity and
 * sheets). Implemented per the Profile -> Room -> Wallet isolation model:
 * every record is keyed by [ProfileId], one wallet per profile (v1), and
 * key material crosses NONE of these boundaries in plaintext except the
 * one-time create/import/reveal calls.
 *
 * OWNERSHIP (see worklog): data layer implements [WalletRepositoryApi];
 * engine implements [WalletEngineApi]; the bridge builds [DappRequest]s
 * and consumes [DappOutcome]s; the UI renders [DappRequest]s and calls
 * [WalletEngineApi.decideDappRequest].
 */

// ---------------------------------------------------------------------------
// Records
// ---------------------------------------------------------------------------

/** The profile's wallet. One per profile in v1; `hasMnemonic` is false only for key-only imports. */
data class WalletSummary(
    val id: String,
    val profileId: ProfileId,
    val label: String,
    val createdAt: Long,
    val hasMnemonic: Boolean
)

/** A derived or imported account on one chain. No key material. */
data class WalletAccountRecord(
    val id: String,
    val walletId: String,
    val chainType: ChainType,
    val address: String,
    val label: String,
    /** BIP44 path for derived accounts; "" for imports. */
    val path: String,
    val source: Source
) {
    enum class Source { DERIVED, IMPORTED }
}

/**
 * What a restore actually did, so the screen that ran it can report facts
 * rather than "restored" or "failed".
 *
 * A backup is a file from outside the app and it can be partially wrong: a
 * row naming a chain this build does not have, a private key that is not a
 * key, a derived account whose path this build would derive differently. Each
 * of those loses one account, not the wallet, and the difference between
 * "your wallet is back, minus this one key (here is why)" and "restore
 * failed" is the difference between a user who checks their addresses and a
 * user who is left with nothing and no explanation.
 */
data class RestoreReport(
    /** The label the restored wallet carries; falls back to "Wallet". */
    val walletLabel: String,
    /** True when a recovery phrase was present and its accounts were derived. */
    val phraseRestored: Boolean,
    /** How many index-0 accounts the phrase re-derived. */
    val derivedAccountCount: Int,
    /** How many imported private keys were accepted. */
    val importedAccountCount: Int,
    /** Keys that could not be restored, each with the reason. */
    val skipped: List<SkippedKey>
) {
    data class SkippedKey(val chain: String, val label: String, val reason: String)

    val restoredAnything: Boolean get() = phraseRestored || importedAccountCount > 0
}

/** A network row: the [NetworkConfig] plus per-profile UI state. */
data class NetworkRecord(
    val config: NetworkConfig,
    val enabled: Boolean,
    val isCustom: Boolean
)

/** A granted dApp permission (host is the WebView-verified host, never a claimed one). */
data class DappPermissionRecord(
    val id: String,
    val profileId: ProfileId,
    val host: String,
    val chainType: ChainType,
    val accountAddress: String,
    val methods: List<String>,
    val grantedAt: Long
)

/** Locally-recorded wallet activity (what THIS wallet sent — not chain indexing). */
data class WalletActivityRecord(
    val id: String,
    val profileId: ProfileId,
    val chainType: ChainType,
    val networkName: String,
    val kind: Kind,
    val accountAddress: String,
    val toAddress: String?,
    /** Human-readable amount, e.g. "0.1 ETH" or "message". */
    val displayAmount: String,
    val hash: String?,
    val explorerUrl: String?,
    val createdAt: Long
) {
    enum class Kind { SEND, SIGN_MESSAGE, SIGN_TRANSACTION, DAPP_SEND }
}

// ---------------------------------------------------------------------------
// Session lock
// ---------------------------------------------------------------------------

/** Wallet key-material availability for the current session. */
enum class WalletLockState {
    /** No wallet exists for the profile yet. */
    NO_WALLET,
    /** Wallet exists; signing/import/reveal requires [WalletEngineApi.unlock] first. */
    LOCKED,
    /** Unlocked for this session (after the UI biometric gate). */
    UNLOCKED
}

// ---------------------------------------------------------------------------
// dApp request pipeline (bridge -> engine queue -> UI -> decision -> outcome)
// ---------------------------------------------------------------------------

/** A dApp-originated request awaiting the user's decision. */
sealed interface DappRequest {
    val id: String
    /** WebView-verified host (never the page's claimed origin). */
    val host: String
    val chainType: ChainType

    /** eth_requestAccounts / solana connect / aptos connect / sui connect. */
    data class Connect(
        override val id: String,
        override val host: String,
        override val chainType: ChainType,
        val originUrl: String
    ) : DappRequest

    /** personal_sign / solana signMessage / aptos signMessage. */
    data class SignMessage(
        override val id: String,
        override val host: String,
        override val chainType: ChainType,
        val accountAddress: String,
        /** Raw message as sent by the dApp (hex or utf-8, unparsed). */
        val message: String,
        /** Human-readable rendering for the confirmation sheet. */
        val displayMessage: String
    ) : DappRequest

    /** eth_signTypedData_v4 (EIP-712). */
    data class SignTypedData(
        override val id: String,
        override val host: String,
        override val chainType: ChainType,
        val accountAddress: String,
        val typedDataJson: String
    ) : DappRequest

    /** eth_sendTransaction / solana signAndSendTransaction / aptos signAndSubmit. */
    data class SendTransaction(
        override val id: String,
        override val host: String,
        override val chainType: ChainType,
        val networkId: String,
        val accountAddress: String,
        /** Chain-specific params as JSON, parsed by the engine per chain family. */
        val txParamsJson: String,
        /** Present when the engine could pre-compute it; null = fees unknown/offline. */
        val feeEstimate: FeeEstimate?
    ) : DappRequest

    /** wallet_switchEthereumChain. */
    data class SwitchChain(
        override val id: String,
        override val host: String,
        override val chainType: ChainType,
        val targetNetworkId: String
    ) : DappRequest

    /** wallet_addEthereumChain (validated against Chainlist rules by the engine). */
    data class AddChain(
        override val id: String,
        override val host: String,
        override val chainType: ChainType,
        val proposed: NetworkConfig
    ) : DappRequest
}

/** The user's answer for one [DappRequest]. */
data class DappDecision(
    val requestId: String,
    val approved: Boolean,
    /** For Connect: which account to expose (null = primary account of that chain). */
    val chosenAccountId: String? = null
)

/**
 * The settled result delivered back to the bridge (which relays it to the
 * page as the resolved/rejected promise). EIP-1193-aligned error codes.
 */
data class DappOutcome(
    val requestId: String,
    /** JSON value to resolve the page promise with (null on error). */
    val resultJson: String?,
    val error: WalletBridgeError?
)

/** EIP-1193-style error surfaced to the page. */
data class WalletBridgeError(val code: Int, val message: String) {
    companion object {
        const val USER_REJECTED = 4001
        const val UNAUTHORIZED = 4100
        const val UNSUPPORTED_METHOD = 4200
        const val DISCONNECTED = 4900
        const val CHAIN_DISCONNECTED = 4901
        const val UNRECOGNIZED_CHAIN = 4902
        const val INTERNAL = -32603
        const val INVALID_PARAMS = -32602
    }
}

// ---------------------------------------------------------------------------
// Repository (data layer implements; engine consumes)
// ---------------------------------------------------------------------------

/**
 * Per-profile wallet persistence. Plaintext keys touch exactly three
 * entry points — [createWallet], [importAccount] (input) and
 * [revealMnemonic]/[revealPrivateKey] (output) — everything stored is
 * Keystore-encrypted by the implementation.
 */
interface WalletRepositoryApi {

    // -- wallet ------------------------------------------------------------

    fun observeWallet(profileId: ProfileId): Flow<WalletSummary?>

    suspend fun wallet(profileId: ProfileId): WalletSummary?

    /**
     * Creates the profile's wallet. Fails if one already exists.
     *
     * [mnemonic] is null for a KEYS-ONLY wallet — one whose accounts were all
     * imported as individual private keys and which therefore has no phrase
     * to derive from. That is a real shape, not a degenerate one: the backup
     * format can write such a wallet ([WalletBackup.Contents.isEmpty] is
     * false for it), so refusing to create one would leave the app able to
     * write a file it cannot read back. A blank string is rejected rather
     * than treated as "no phrase", because that is a caller bug, not a
     * wallet without a phrase.
     *
     * @throws IllegalArgumentException when [mnemonic] is blank but not null.
     */
    suspend fun createWallet(
        profileId: ProfileId,
        label: String,
        mnemonic: String?
    ): WalletSummary

    /** Deletes the wallet, its accounts, networks, permissions and activity rows. */
    suspend fun deleteWallet(profileId: ProfileId)

    /** Decrypts and returns the mnemonic, or null when absent. UI gates this behind biometrics. */
    suspend fun revealMnemonic(profileId: ProfileId): String?

    /**
     * Everything a wallet-keys export needs: the phrase, plus every account
     * with its path and — for imported ones only — its private key.
     *
     * [mnemonic] lets a caller that already holds the phrase (the onboarding
     * reveal) skip the vault read; every other caller passes null. Unlike the
     * session-guarded [WalletEngineApi.backupContents] this asks for no
     * binding and no unlock — the caller that needs it most, a profile
     * export, is not the session that holds the wallet.
     */
    suspend fun backupContents(profileId: ProfileId, mnemonic: String?): WalletBackup.Contents

    // -- accounts ----------------------------------------------------------

    fun observeAccounts(profileId: ProfileId): Flow<List<WalletAccountRecord>>

    suspend fun accounts(profileId: ProfileId): List<WalletAccountRecord>

    suspend fun addDerivedAccount(
        profileId: ProfileId,
        chainType: ChainType,
        address: String,
        path: String,
        label: String
    ): WalletAccountRecord

    suspend fun addImportedAccount(
        profileId: ProfileId,
        chainType: ChainType,
        address: String,
        privateKey: String,
        label: String
    ): WalletAccountRecord

    /**
     * Derives and writes the index-0 account for each of [enabledChains].
     *
     * The seed is derived here from [mnemonic] rather than read back from the
     * wallet row: a caller that has just created the wallet holds the phrase
     * already, and decrypting it again would be a second trip through the
     * vault for a value that is right there.
     */
    suspend fun seedInitialAccounts(
        profileId: ProfileId,
        mnemonic: String,
        enabledChains: List<ChainType>
    )

    /**
     * Writes a whole wallet from an opened backup into [profileId].
     *
     * TAKES [profileId] EXPLICITLY AND NEEDS NO SESSION. That is the point of
     * it living here rather than on the engine: the caller that needs it most
     * is a profile import, which restores the wallet into a profile that has
     * never been opened and therefore cannot be bound. The engine's own
     * [WalletEngineApi.restoreFromBackup] adds the session guards and
     * delegates here.
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
     *
     * @throws WalletException.InvalidParams when the file's phrase is not a
     * valid BIP39 mnemonic, or when a wallet already exists for [profileId].
     */
    suspend fun restore(
        profileId: ProfileId,
        payload: WalletBackup.Payload,
        enabledChains: List<ChainType>
    ): RestoreReport

    suspend fun renameAccount(accountId: String, label: String)

    suspend fun removeAccount(accountId: String)

    /** Decrypts and returns an imported account's private key, or null for derived accounts. */
    suspend fun revealPrivateKey(accountId: String): String?

    /** Next free BIP44 index for a chain (0 when none exist). */
    suspend fun nextDerivationIndex(profileId: ProfileId, chainType: ChainType): Int

    // -- networks ----------------------------------------------------------

    fun observeNetworks(profileId: ProfileId): Flow<List<NetworkRecord>>

    suspend fun networks(profileId: ProfileId): List<NetworkRecord>

    /** Seeds the default networks on first access (idempotent, never overrides). */
    suspend fun ensureDefaultNetworks(profileId: ProfileId)

    suspend fun upsertCustomNetwork(profileId: ProfileId, config: NetworkConfig)

    suspend fun removeCustomNetwork(profileId: ProfileId, networkId: String)

    suspend fun setNetworkEnabled(profileId: ProfileId, networkId: String, enabled: Boolean)

    /** The profile's active network for a chain (default = first enabled). */
    suspend fun activeNetwork(profileId: ProfileId, chainType: ChainType): NetworkConfig?

    suspend fun setActiveNetwork(profileId: ProfileId, chainType: ChainType, networkId: String)

    // -- dApp permissions --------------------------------------------------

    suspend fun grantDappPermission(
        profileId: ProfileId,
        host: String,
        chainType: ChainType,
        accountAddress: String,
        methods: List<String>
    )

    suspend fun revokeDappPermission(profileId: ProfileId, host: String, chainType: ChainType)

    suspend fun dappPermissions(profileId: ProfileId, host: String): List<DappPermissionRecord>

    suspend fun allDappPermissions(profileId: ProfileId): List<DappPermissionRecord>

    // -- activity ----------------------------------------------------------

    fun observeActivities(profileId: ProfileId): Flow<List<WalletActivityRecord>>

    suspend fun recordActivity(record: WalletActivityRecord)
}

// ---------------------------------------------------------------------------
// Engine (engine layer implements; UI + bridge consume)
// ---------------------------------------------------------------------------

/**
 * Wallet orchestration for the bound profile: lifecycle, accounts, balances,
 * networks, signing/sending, and the dApp request queue. All state is
 * [StateFlow]s so Compose can collect it directly; all network calls are
 * offline-tolerant (balances simply stay absent, errors surface as
 * [BalanceResult.Error]).
 */
interface WalletEngineApi {

    // -- session -----------------------------------------------------------

    val lockState: StateFlow<WalletLockState>
    val wallet: StateFlow<WalletSummary?>
    val accounts: StateFlow<List<WalletAccountRecord>>
    val balances: StateFlow<Map<String, BalanceResult>>
    val networks: StateFlow<List<NetworkRecord>>
    val activeNetworks: StateFlow<Map<ChainType, NetworkConfig>>
    val pendingRequests: StateFlow<List<DappRequest>>
    val activities: StateFlow<List<WalletActivityRecord>>

    /**
     * The profile's granted dApp permissions, one row per (host, chain,
     * account). Read-only view of what the connect prompt has already
     * answered — revoking goes through [revokeDappPermission], which is what
     * makes a host start asking again.
     */
    val dappPermissions: StateFlow<List<DappPermissionRecord>>

    /** Bind to a profile (idempotent; re-binds on profile switch). */
    fun bind(profileId: ProfileId)

    fun unbind()

    /** Marks the session unlocked (UI calls this after a successful biometric gate). */
    fun unlock()

    fun lock()

    // -- wallet lifecycle ----------------------------------------------------

    /** Generates a mnemonic, persists the wallet, derives index-0 accounts for [enabledChains]. */
    suspend fun createWallet(label: String, enabledChains: List<ChainType>): String

    /** Imports an existing mnemonic (BIP39-validated). Fails if a wallet exists. */
    suspend fun importWallet(mnemonic: String, label: String, enabledChains: List<ChainType>)

    /**
     * Deletes the bound profile's wallet and every row it owns — its accounts,
     * its network list and active choices, its dApp permissions and its
     * recorded activity. Only this profile is touched; other profiles' wallets
     * are separate rows and are left alone.
     *
     * ONE WALLET PER PROFILE, so this is the whole wallet: afterwards the bound
     * profile is back to NO_WALLET and the surface's correct state is the
     * empty-wallet onboarding, not a wallet list with a hole in it.
     *
     * Requires an unlocked session. It is an irreversible, key-destroying
     * action and the UI's own delete flow is where the user's confirmation
     * lives — this method is the half that runs after that confirmation.
     *
     * The profile's wallet KEY is deliberately not destroyed here: the
     * profile-deletion cascade owns that, and a key with no rows behind it
     * seals nothing.
     */
    suspend fun deleteWallet()

    /** Requires an unlocked session. */
    suspend fun revealMnemonic(): String?

    /**
     * The account's PUBLIC key as lowercase hex, for the chains whose dApp
     * conventions publish one: Cosmos (compressed secp256k1, the `pubKey`
     * Keplr's `getKey` returns and CosmJS's `getAccounts` needs to build a
     * sign doc), Aptos (ed25519, `account().publicKey`) and Bitcoin
     * (compressed secp256k1, `connect().publicKey`). Octra (ed25519) is
     * answered here as a plain fact about the account, but nothing publishes
     * it: the chain has no specified provider API, so the bridge withholds it
     * from the connect payload.
     *
     * Returns null on the chains that never publish one, and null for any
     * account whose key material cannot be read — a locked wallet, or an
     * account id that no longer exists. Null is not an error: every caller
     * degrades to the address-only answer it gave before this existed.
     *
     * EVM is deliberately excluded, and not because it would be hard: EIP-1193
     * has no public-key call at all. A dApp recovers the signer from the
     * signature with `ecrecover`, so MetaMask never sends one and neither does
     * this wallet — `eth_accounts` stays the whole of what an EVM page learns
     * about the account. Solana is excluded for the opposite reason: its
     * address IS the base58 public key, and the connect result already carries
     * it in the `publicKey` field Phantom uses.
     *
     * A public key is not secret. It is published on chain alongside every
     * signature this wallet makes, and handing it to a dApp grants no ability
     * to sign, to spend, or to derive the private key. The engine's rule about
     * key material is about SECRET material, and this method is the boundary
     * being drawn precisely rather than widened.
     */
    suspend fun publicKeyOf(accountId: String): String?

    /**
     * Everything a wallet-keys export needs: the phrase, plus every account
     * with its path and — for imported ones only — its private key.
     *
     * [mnemonic] is for the ONBOARDING REVEAL, the one caller that holds the
     * phrase already: creating a wallet deliberately does not unlock the
     * session, so requiring an unlock there would put the export out of reach
     * at the exact moment a user is most likely to want it. Every other
     * caller passes null and must be unlocked.
     *
     * Returns contents that may be empty for a wallet built purely from
     * imported keys with nothing imported yet — callers check
     * [WalletBackup.Contents.isEmpty] rather than writing a file that
     * restores nothing.
     */
    suspend fun backupContents(mnemonic: String? = null): WalletBackup.Contents

    /**
     * Restores a wallet from a backup file the user opened.
     *
     * [payload] comes from [WalletBackup.Restored.payload]; the caller has
     * already decrypted the file and shown the user what it read, so this
     * method is the second half of a decision the user has already made.
     *
     * ONE WALLET PER PROFILE, and this does not replace one: restoring over a
     * live wallet would silently orphan whatever it holds, so an existing
     * wallet is refused. The caller deletes first if that is what the user
     * asked for, which is a separate, separately-confirmed step.
     *
     * What it restores: the phrase (re-deriving index-0 accounts for
     * [enabledChains]) and every imported private key in the file. A key the
     * chain cannot parse is reported in [RestoreReport.skipped] rather than
     * failing the whole restore — a file with one bad row should not cost the
     * user the other nineteen.
     */
    suspend fun restoreFromBackup(
        payload: WalletBackup.Payload,
        enabledChains: List<ChainType>
    ): RestoreReport

    // -- accounts ------------------------------------------------------------

    suspend fun addDerivedAccount(chainType: ChainType): WalletAccountRecord?

    /** Chain-family-specific key parsing (hex EVM/TRON, base58 Solana). */
    suspend fun importAccount(chainType: ChainType, privateKey: String, label: String): WalletAccountRecord?

    suspend fun renameAccount(accountId: String, label: String)

    suspend fun removeAccount(accountId: String)

    /** Refreshes balances for all accounts of enabled chains (offline-tolerant). */
    suspend fun refreshBalances()

    // -- networks ------------------------------------------------------------

    suspend fun setActiveNetwork(chainType: ChainType, networkId: String)

    suspend fun addCustomNetwork(config: NetworkConfig): Boolean

    suspend fun removeCustomNetwork(networkId: String)

    suspend fun setNetworkEnabled(networkId: String, enabled: Boolean)

    /** Refreshes the Chainlist EVM catalog; returns how many networks are newly known. */
    suspend fun refreshChainlist(): Int

    // -- sending -------------------------------------------------------------

    suspend fun estimateSendFee(
        chainType: ChainType,
        networkId: String,
        fromAccountId: String,
        to: String,
        amount: String
    ): FeeEstimate?

    /** Sends a native-token transfer from OUR account. Requires an unlocked session. */
    suspend fun sendNative(accountId: String, networkId: String, to: String, amount: String):
        com.roombrowser.domain.wallet.model.BroadcastResult

    // -- dApp pipeline -------------------------------------------------------

    /**
     * Bridge entry point. The engine enqueues the request; the UI shows a
     * confirmation; when [decideDappRequest] settles it, `onSettled` runs on
     * the main thread exactly once with the page-bound outcome.
     */
    fun submitDappRequest(request: DappRequest, onSettled: (DappOutcome) -> Unit)

    /**
     * Abandons the still-pending requests belonging to a page that is gone.
     *
     * The engine is a process singleton and keeps a callback per outstanding
     * request until the user resolves its prompt. A tab closed or evicted
     * mid-prompt therefore used to pin its bridge — and everything the bridge
     * holds — for the life of the process, while a confirmation sheet stayed
     * queued for a page that no longer exists. Each id settles with
     * DISCONNECTED and leaves the pending queue. Unknown ids are no-ops.
     */
    fun cancelDappRequests(requestIds: Collection<String>)

    /** UI entry point. Rejected requests settle with USER_REJECTED. */
    fun decideDappRequest(decision: DappDecision)

    /** Whether a host is already permitted for an account+method (no prompt needed). */
    fun isDappPermitted(host: String, chainType: ChainType, accountAddress: String, method: String): Boolean

    /**
     * Revokes [host]'s permission for one chain family — the dApp-initiated
     * disconnect (`window.solana.disconnect`). Fire-and-forget on the main
     * thread: the next silent connect auto-approve will prompt again, and
     * `eth_accounts`/`getKey` stop answering for that host.
     */
    fun revokeDappPermission(host: String, chainType: ChainType)
}
