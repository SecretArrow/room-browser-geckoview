package com.roombrowser.data.repo

import com.roombrowser.browser.wallet.DappPermissionRecord
import com.roombrowser.browser.wallet.NetworkRecord
import com.roombrowser.browser.wallet.RestoreReport
import com.roombrowser.browser.wallet.WalletAccountRecord
import com.roombrowser.browser.wallet.WalletActivityRecord
import com.roombrowser.browser.wallet.WalletRepositoryApi
import com.roombrowser.browser.wallet.WalletKeyCodec
import com.roombrowser.browser.wallet.WalletSummary
import com.roombrowser.data.db.DappPermissionDao
import com.roombrowser.data.db.DappPermissionEntity
import com.roombrowser.data.db.WalletAccountDao
import com.roombrowser.data.db.WalletAccountEntity
import com.roombrowser.data.db.WalletActivityDao
import com.roombrowser.data.db.WalletActivityEntity
import com.roombrowser.data.db.WalletActiveNetworkEntity
import com.roombrowser.data.db.WalletDao
import com.roombrowser.data.db.WalletEntity
import com.roombrowser.data.db.WalletNetworkDao
import com.roombrowser.data.db.WalletNetworkEntity
import com.roombrowser.domain.export.WalletBackup
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.wallet.chains.ChainRegistry
import com.roombrowser.domain.wallet.crypto.Mnemonics
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.domain.wallet.model.NetworkConfig
import com.roombrowser.domain.wallet.model.WalletException
import com.roombrowser.security.VaultCryptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Per-profile multi-chain wallet persistence: Room rows (wallets, accounts,
 * networks, dApp permissions, activity) + per-profile key encryption
 * (security.WalletKeyCrypto / [VaultCryptor]). Implements the frozen
 * com.roombrowser.browser.wallet.WalletRepositoryApi contract.
 *
 * Security invariants:
 *  - the DAOs see ONLY ciphertext: the wallet's mnemonic
 *    (wallets.mnemonic_enc) and imported accounts' private keys
 *    (wallet_accounts.private_key_enc) are AndroidKeyStore AES-256-GCM
 *    blobs under the profile's wallet key (alias
 *    roomwallet-&lt;safeSuffix&gt;); derived accounts store NO key material at
 *    all (their keys are re-derived from the encrypted mnemonic on use);
 *  - plaintext key material crosses this boundary at exactly three points —
 *    [createWallet] and [addImportedAccount] (input, encrypted on the spot)
 *    and [revealMnemonic]/[revealPrivateKey] (output; the UI layer gates
 *    BOTH behind its biometric prompt before calling) — and nothing is ever
 *    logged;
 *  - every read/write is scoped to the profile it was called with (wallet
 *    data is profile data, like tabs and credentials);
 *  - all suspend work runs on [Dispatchers.IO] (Keystore + Room are
 *    blocking); observe* flows are mapped off-IO the same way.
 *
 * One wallet per profile (v1) — enforced by a UNIQUE index, and
 * [createWallet] refuses to run when one exists. Account uniqueness per
 * (wallet, chain, address) is enforced by the schema.
 */
class WalletRepository(
    private val walletDao: WalletDao,
    private val accountDao: WalletAccountDao,
    private val networkDao: WalletNetworkDao,
    private val permissionDao: DappPermissionDao,
    private val activityDao: WalletActivityDao,
    private val crypto: VaultCryptor
) : WalletRepositoryApi {

    /**
     * The default-network catalogue. Built lazily (and only once): pure-JVM
     * chain adapters with no side effects at construction, so wallet CRUD
     * paths that never seed networks pay nothing for them.
     */
    private val registry: ChainRegistry by lazy { ChainRegistry() }

    /** NetworkConfig / method-list codec; encodeDefaults keeps payloads complete. */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // -- wallet ------------------------------------------------------------

    /** Live wallet row of the profile (null until one is created). */
    override fun observeWallet(profileId: ProfileId): Flow<WalletSummary?> =
        walletDao.observeByProfile(profileId.value)
            .map { it?.toSummary() }
            .flowOn(Dispatchers.IO)

    /** The profile's wallet, or null when none exists yet. */
    override suspend fun wallet(profileId: ProfileId): WalletSummary? =
        withContext(Dispatchers.IO) { walletDao.byProfile(profileId.value)?.toSummary() }

    /**
     * Creates the profile's wallet: the mnemonic (when there is one) is
     * encrypted under the profile's wallet key and ONLY the ciphertext is
     * stored. Deriving the first accounts is the engine's job — this layer
     * just persists the wallet.
     *
     * A null [mnemonic] stores no phrase at all: that is a keys-only wallet,
     * one whose every account is an individually imported private key. The
     * column is already nullable and the app already renders that shape
     * ("Recovery phrase: none" in an export), so this is the persistence half
     * of a case that exists rather than a new one. A BLANK string is rejected
     * instead: it is indistinguishable from "no phrase" to a reader but means
     * a caller forgot to pass one.
     *
     * @throws IllegalStateException when a wallet already exists for the
     *   profile (one wallet per profile in v1).
     * @throws IllegalArgumentException when [mnemonic] is blank but not null.
     */
    override suspend fun createWallet(
        profileId: ProfileId,
        label: String,
        mnemonic: String?
    ): WalletSummary {
        require(mnemonic == null || mnemonic.isNotBlank()) {
            "Wallet mnemonic must not be blank (pass null for a keys-only wallet)"
        }
        return withContext(Dispatchers.IO) {
            walletDao.byProfile(profileId.value)?.let {
                throw IllegalStateException("A wallet already exists for this profile")
            }
            val entity = WalletEntity(
                id = UUID.randomUUID().toString(),
                profileId = profileId.value,
                label = label,
                mnemonicEnc = mnemonic?.let { crypto.encrypt(profileId.safeSuffix, it) },
                createdAt = System.currentTimeMillis()
            )
            walletDao.upsert(entity)
            entity.toSummary()
        }
    }

    /**
     * Deletes the wallet and every wallet-owned row of the profile: its
     * accounts, network list + active choices, dApp permissions and
     * recorded activity. Idempotent — a profile without a wallet is a
     * no-op. The profile's wallet KEY is not touched here; it is destroyed
     * by the profile-deletion cascade (ProfileRepositoryImpl), which also
     * wipes these tables.
     */
    override suspend fun deleteWallet(profileId: ProfileId) {
        withContext(Dispatchers.IO) {
            walletDao.byProfile(profileId.value)?.let { wallet ->
                accountDao.deleteForWallet(wallet.id)
            }
            walletDao.deleteAllForProfile(profileId.value)
            networkDao.deleteAllForProfile(profileId.value)
            networkDao.deleteActiveNetworksForProfile(profileId.value)
            permissionDao.deleteAllForProfile(profileId.value)
            activityDao.deleteAllForProfile(profileId.value)
        }
    }

    /**
     * Decrypts and returns the wallet's mnemonic, or null when the wallet
     * has none (imported-accounts-only wallet) or no wallet exists. The UI
     * layer MUST run its biometric gate before calling this — the
     * repository returns plaintext on purpose so the reveal screen can show
     * it; it never logs or persists it.
     */
    override suspend fun revealMnemonic(profileId: ProfileId): String? =
        withContext(Dispatchers.IO) {
            walletDao.byProfile(profileId.value)?.mnemonicEnc
                ?.let { crypto.decrypt(profileId.safeSuffix, it) }
        }

    // -- accounts ----------------------------------------------------------

    /** Live account list of the profile's wallet (empty while no wallet). */
    override fun observeAccounts(profileId: ProfileId): Flow<List<WalletAccountRecord>> =
        accountDao.observeForProfile(profileId.value)
            .map { rows -> rows.map { it.toRecord() } }
            .flowOn(Dispatchers.IO)

    /** The profile's accounts (empty while no wallet). */
    override suspend fun accounts(profileId: ProfileId): List<WalletAccountRecord> =
        withContext(Dispatchers.IO) { accountDao.forProfile(profileId.value).map { it.toRecord() } }

    /**
     * Persists a mnemonic-derived account. Stores NO key material — derived
     * keys are re-computed from the encrypted mnemonic when signing — only
     * the address, [path] and label.
     *
     * @throws IllegalStateException when the profile has no wallet.
     */
    override suspend fun addDerivedAccount(
        profileId: ProfileId,
        chainType: ChainType,
        address: String,
        path: String,
        label: String
    ): WalletAccountRecord = withContext(Dispatchers.IO) {
        val wallet = requireWallet(profileId)
        val entity = WalletAccountEntity(
            id = UUID.randomUUID().toString(),
            walletId = wallet.id,
            chainType = chainType.name,
            address = address,
            label = label,
            path = path,
            source = WalletAccountRecord.Source.DERIVED.name,
            privateKeyEnc = null,
            createdAt = System.currentTimeMillis()
        )
        accountDao.upsert(entity)
        entity.toRecord()
    }

    /**
     * Persists an imported account: the private key is encrypted under the
     * profile's wallet key and ONLY the ciphertext is stored; the record's
     * path is "" (imports have no derivation path).
     *
     * @throws IllegalStateException when the profile has no wallet.
     */
    override suspend fun addImportedAccount(
        profileId: ProfileId,
        chainType: ChainType,
        address: String,
        privateKey: String,
        label: String
    ): WalletAccountRecord = withContext(Dispatchers.IO) {
        val wallet = requireWallet(profileId)
        val entity = WalletAccountEntity(
            id = UUID.randomUUID().toString(),
            walletId = wallet.id,
            chainType = chainType.name,
            address = address,
            label = label,
            path = "",
            source = WalletAccountRecord.Source.IMPORTED.name,
            privateKeyEnc = crypto.encrypt(profileId.safeSuffix, privateKey),
            createdAt = System.currentTimeMillis()
        )
        accountDao.upsert(entity)
        entity.toRecord()
    }

    /**
     * Derives and writes the index-0 account for each of [enabledChains].
     *
     * Derivation runs on [Dispatchers.Default] and the writes that follow run
     * one at a time: the CPU work is the expensive half, and keeping the two
     * apart means a slow disk cannot look like slow crypto.
     */
    override suspend fun seedInitialAccounts(
        profileId: ProfileId,
        mnemonic: String,
        enabledChains: List<ChainType>
    ) {
        if (enabledChains.isEmpty()) return
        ensureDefaultNetworks(profileId)
        val derived = withContext(Dispatchers.Default) {
            val seed = Mnemonics.toSeed(Mnemonics.normalize(mnemonic))
            enabledChains.map { chain ->
                chain to WalletKeyCodec.deriveAccount(chain, seed, 0, registry)
            }
        }
        derived.forEach { (chain, account) ->
            addDerivedAccount(
                profileId, chain, account.first, account.second, "${chain.displayName} 1"
            )
        }
    }

    override suspend fun restore(
        profileId: ProfileId,
        payload: WalletBackup.Payload,
        enabledChains: List<ChainType>
    ): RestoreReport {
        if (wallet(profileId) != null) {
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
        createWallet(profileId, label, normalized)
        if (normalized != null) seedInitialAccounts(profileId, normalized, enabledChains)

        // Compare against what is on disk, not against the file: the phrase
        // just derived a row per enabled chain, and a file that also lists one
        // of those addresses as an imported key would otherwise create a
        // second account pointing at the same address.
        val taken = accounts(profileId)
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
            val parsed = runCatching { WalletKeyCodec.parse(chain, key, registry) }
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
                addImportedAccount(
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

    /**
     * The profile's wallet as an export sees it.
     *
     * Read from the DAOs rather than from the `accounts` flow: the flow lags a
     * write by one Room invalidation, and an export taken right after wallet
     * creation must not describe a wallet with no accounts in it.
     *
     * Derived accounts are re-derived from the phrase, so their keys are left
     * null rather than duplicated; an imported key is reproduced by nothing
     * else and is the one piece of key material this returns in the clear.
     */
    override suspend fun backupContents(
        profileId: ProfileId,
        mnemonic: String?
    ): WalletBackup.Contents {
        val phrase = mnemonic ?: revealMnemonic(profileId)
        val entries = accounts(profileId).map { record ->
            WalletBackup.KeyEntry(
                chain = record.chainType.displayName,
                label = record.label,
                address = record.address,
                path = record.path,
                privateKey = if (record.source == WalletAccountRecord.Source.IMPORTED) {
                    revealPrivateKey(record.id)
                } else {
                    null
                }
            )
        }
        val wallet = wallet(profileId)
        return WalletBackup.Contents(
            walletLabel = wallet?.label?.takeIf { it.isNotBlank() } ?: "Wallet",
            createdAt = wallet?.createdAt ?: 0L,
            mnemonic = phrase,
            accounts = entries
        )
    }

    /** Renames one account by id. */
    override suspend fun renameAccount(accountId: String, label: String) {
        withContext(Dispatchers.IO) { accountDao.rename(accountId, label) }
    }

    /** Deletes one account by id (idempotent). */
    override suspend fun removeAccount(accountId: String) {
        withContext(Dispatchers.IO) { accountDao.delete(accountId) }
    }

    /**
     * Decrypts and returns an imported account's private key; null for
     * derived accounts (no stored key) and unknown ids. The UI layer MUST
     * run its biometric gate before calling this — like the mnemonic, the
     * plaintext exists only for the caller, never in logs or storage.
     */
    override suspend fun revealPrivateKey(accountId: String): String? =
        withContext(Dispatchers.IO) {
            val account = accountDao.byId(accountId) ?: return@withContext null
            val profileKey = walletDao.byId(account.walletId)?.profileId
                ?: return@withContext null
            account.privateKeyEnc?.let { crypto.decrypt(ProfileId(profileKey).safeSuffix, it) }
        }

    /**
     * The next free account index for the chain: the highest index found
     * among the profile's DERIVED accounts' paths plus one — 0 when the
     * chain has no derived accounts yet. Non-contiguous holes are respected
     * (0, 2 and 5 in storage give 6), and imported accounts never count
     * (they carry no derivation path).
     *
     * Where that index sits in the path differs per chain and the chain's own
     * adapter answers it ([ChainRegistry.derivationIndexOf]): EVM, Bitcoin,
     * Cosmos, TRON, Sui and Aptos carry it on the final level, Solana on the
     * ACCOUNT level (m/44'/501'/i'/0'). A path no adapter claims is counted
     * as -1 — ignored, never a thrown error — so a hand-edited row cannot
     * advance the counter.
     */
    override suspend fun nextDerivationIndex(profileId: ProfileId, chainType: ChainType): Int =
        withContext(Dispatchers.IO) {
            accountDao.derivedForProfileChain(profileId.value, chainType.name)
                .maxOfOrNull { registry.derivationIndexOf(chainType, it.path) ?: -1 }
                ?.plus(1)
                ?: 0
        }

    // -- networks ----------------------------------------------------------

    /** Live network list of the profile (see [ensureDefaultNetworks]). */
    override fun observeNetworks(profileId: ProfileId): Flow<List<NetworkRecord>> =
        networkDao.observeForProfile(profileId.value)
            .map { rows -> rows.map { it.toRecord() } }
            .flowOn(Dispatchers.IO)

    /** The profile's networks (call [ensureDefaultNetworks] first to seed). */
    override suspend fun networks(profileId: ProfileId): List<NetworkRecord> =
        withContext(Dispatchers.IO) { networkDao.forProfile(profileId.value).map { it.toRecord() } }

    /**
     * Idempotently seeds the default-network catalogue for the profile:
     * every [ChainRegistry] default is inserted with the first network of
     * each chain family ENABLED (Ethereum Mainnet for EVM, the family
     * mainnet for the others) and the rest present but disabled.
     *
     * A bundled network's own payload is REFRESHED on every call, while its
     * `enabled` flag is left exactly as the user set it. The two are
     * separable, and separating them is the point: a default's addresses are
     * ours to correct — an RPC host that deprecates its API takes the preset
     * with it, and a wallet still dialling the dead host errors on every read
     * and every send, for every existing profile, forever — while whether the
     * user wants that network switched on is theirs alone. A row the user
     * turned CUSTOM is never touched: that row is theirs now.
     */
    override suspend fun ensureDefaultNetworks(profileId: ProfileId) {
        withContext(Dispatchers.IO) {
            val rows = networkDao.forProfile(profileId.value)
            val byId = rows.associateBy { it.id }
            val defaults = registry.allDefaultNetworks()
            val enabledByDefault = defaults
                .groupBy { it.chainType }
                .mapValues { (_, family) -> family.first().id }
            defaults.forEach { config ->
                val payload = json.encodeToString(NetworkConfig.serializer(), config)
                val row = byId[config.id]
                when {
                    row == null -> networkDao.upsert(
                        WalletNetworkEntity(
                            id = config.id,
                            profileId = profileId.value,
                            enabled = enabledByDefault[config.chainType] == config.id,
                            isCustom = false,
                            payload = payload
                        )
                    )
                    // copy() keeps the row's key and BOTH flags: only the
                    // network's own addresses move.
                    !row.isCustom && row.payload != payload ->
                        networkDao.upsert(row.copy(payload = payload))
                    else -> Unit
                }
            }
            // A network dropped from the catalogue (Sui devnet: every public
            // JSON-RPC it had is gone) leaves a row behind for anyone who
            // seeded it before. Drop the DISABLED, non-custom leftovers —
            // deliberately only those. An ENABLED row is the network the user
            // is on, and a network is not deleted out from under them; a
            // custom row was never ours to keep in step.
            val catalogued = defaults.mapTo(mutableSetOf()) { it.id }
            rows.filter { !it.isCustom && !it.enabled && it.id !in catalogued }
                .forEach { networkDao.delete(profileId.value, it.id) }
        }
    }

    /**
     * Inserts — or replaces — the profile's row for [config].id as a CUSTOM
     * network (payload refreshed, enabled). Upserting with a default's id
     * takes that row over as custom (the engine validates ids before
     * calling; only the row's payload/state change, its key stays).
     */
    override suspend fun upsertCustomNetwork(profileId: ProfileId, config: NetworkConfig) {
        withContext(Dispatchers.IO) {
            networkDao.upsert(
                WalletNetworkEntity(
                    id = config.id,
                    profileId = profileId.value,
                    enabled = true,
                    isCustom = true,
                    payload = json.encodeToString(NetworkConfig.serializer(), config)
                )
            )
        }
    }

    /**
     * Removes the custom network — a no-op for seeded defaults (disable
     * those with [setNetworkEnabled] instead) and for unknown ids.
     */
    override suspend fun removeCustomNetwork(profileId: ProfileId, networkId: String) {
        withContext(Dispatchers.IO) {
            if (networkDao.byProfileAndId(profileId.value, networkId)?.isCustom == true) {
                networkDao.delete(profileId.value, networkId)
            }
        }
    }

    /** Enables/disables one network; a no-op for unknown ids. */
    override suspend fun setNetworkEnabled(
        profileId: ProfileId,
        networkId: String,
        enabled: Boolean
    ) {
        withContext(Dispatchers.IO) {
            networkDao.setEnabled(profileId.value, networkId, enabled)
        }
    }

    /**
     * The profile's active network for the chain: the stored explicit
     * choice (see [setActiveNetwork]) when its row still exists and is
     * enabled, else the FIRST enabled network of the chain in list order
     * (defaults before customs, then id ascending — after
     * [ensureDefaultNetworks] that is the chain's mainnet), else null
     * (chain fully disabled).
     */
    override suspend fun activeNetwork(profileId: ProfileId, chainType: ChainType): NetworkConfig? =
        withContext(Dispatchers.IO) {
            val storedChoice = networkDao.activeNetwork(profileId.value, chainType.name)
            val chosen = storedChoice?.let {
                networkDao.byProfileAndId(profileId.value, it.networkId)
                    ?.takeIf { row -> row.enabled }
                    ?.toConfig()
            }
            chosen ?: networkDao.forProfile(profileId.value)
                .filter { it.enabled }
                .map { it.toRecord() }
                .firstOrNull { it.config.chainType == chainType }
                ?.config
        }

    /**
     * Stores the profile's active network for a chain. Selecting a disabled
     * network enables it (an active network must be usable), and the row
     * must belong to [chainType] — the id and the chain are a matched pair.
     *
     * @throws IllegalArgumentException when [networkId] is unknown for the
     * profile or belongs to a different chain.
     */
    override suspend fun setActiveNetwork(
        profileId: ProfileId,
        chainType: ChainType,
        networkId: String
    ) {
        withContext(Dispatchers.IO) {
            val row = networkDao.byProfileAndId(profileId.value, networkId)
                ?: throw IllegalArgumentException("Unknown network $networkId for this profile")
            require(row.toConfig().chainType == chainType) {
                "Network $networkId does not belong to chain ${chainType.name}"
            }
            if (!row.enabled) {
                networkDao.setEnabled(profileId.value, networkId, true)
            }
            networkDao.upsertActive(
                WalletActiveNetworkEntity(
                    profileId = profileId.value,
                    chainType = chainType.name,
                    networkId = networkId
                )
            )
        }
    }

    // -- dApp permissions --------------------------------------------------

    /**
     * Grants (or re-grants) a dApp permission: the (profile, host, chain,
     * account) row keeps its id and is refreshed with the new method list
     * and a new grantedAt. The host must be the WebView-VERIFIED host.
     */
    override suspend fun grantDappPermission(
        profileId: ProfileId,
        host: String,
        chainType: ChainType,
        accountAddress: String,
        methods: List<String>
    ) {
        withContext(Dispatchers.IO) {
            val existing = permissionDao.byKey(profileId.value, host, chainType.name, accountAddress)
            permissionDao.upsert(
                DappPermissionEntity(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    profileId = profileId.value,
                    host = host,
                    chainType = chainType.name,
                    accountAddress = accountAddress,
                    methodsJson = json.encodeToString(ListSerializer(String.serializer()), methods),
                    grantedAt = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Revokes every account permission of the (profile, host, chain) pair —
     * the contract's revoke granularity is host+chain, so all accounts of
     * that pair go at once.
     */
    override suspend fun revokeDappPermission(
        profileId: ProfileId,
        host: String,
        chainType: ChainType
    ) {
        withContext(Dispatchers.IO) {
            permissionDao.revokeForHostAndChain(profileId.value, host, chainType.name)
        }
    }

    /** The profile's permissions for one host (all chains/accounts). */
    override suspend fun dappPermissions(
        profileId: ProfileId,
        host: String
    ): List<DappPermissionRecord> =
        withContext(Dispatchers.IO) {
            permissionDao.forHost(profileId.value, host).map { it.toRecord() }
        }

    /** Every dApp permission of the profile (the permissions manager list). */
    override suspend fun allDappPermissions(profileId: ProfileId): List<DappPermissionRecord> =
        withContext(Dispatchers.IO) {
            permissionDao.allForProfile(profileId.value).map { it.toRecord() }
        }

    // -- activity ----------------------------------------------------------

    /** Live activity feed of the profile (newest first). */
    override fun observeActivities(profileId: ProfileId): Flow<List<WalletActivityRecord>> =
        activityDao.observeForProfile(profileId.value)
            .map { rows -> rows.map { it.toRecord() } }
            .flowOn(Dispatchers.IO)

    /** Records one wallet activity row (the caller mints the record id). */
    override suspend fun recordActivity(record: WalletActivityRecord) {
        withContext(Dispatchers.IO) {
            activityDao.upsert(
                WalletActivityEntity(
                    id = record.id,
                    profileId = record.profileId.value,
                    chainType = record.chainType.name,
                    networkName = record.networkName,
                    kind = record.kind.name,
                    accountAddress = record.accountAddress,
                    toAddress = record.toAddress,
                    displayAmount = record.displayAmount,
                    hash = record.hash,
                    explorerUrl = record.explorerUrl,
                    createdAt = record.createdAt
                )
            )
        }
    }

    // -- mapping helpers ----------------------------------------------------

    private suspend fun requireWallet(profileId: ProfileId): WalletEntity =
        walletDao.byProfile(profileId.value)
            ?: throw IllegalStateException("No wallet exists for this profile")

    private fun WalletEntity.toSummary(): WalletSummary = WalletSummary(
        id = id,
        profileId = ProfileId(profileId),
        label = label,
        createdAt = createdAt,
        hasMnemonic = mnemonicEnc != null
    )

    private fun WalletAccountEntity.toRecord(): WalletAccountRecord = WalletAccountRecord(
        id = id,
        walletId = walletId,
        chainType = toChainType(chainType),
        address = address,
        label = label,
        path = path,
        source = WalletAccountRecord.Source.valueOf(source)
    )

    private fun WalletNetworkEntity.toRecord(): NetworkRecord =
        NetworkRecord(config = toConfig(), enabled = enabled, isCustom = isCustom)

    private fun WalletNetworkEntity.toConfig(): NetworkConfig =
        json.decodeFromString(NetworkConfig.serializer(), payload)

    private fun DappPermissionEntity.toRecord(): DappPermissionRecord = DappPermissionRecord(
        id = id,
        profileId = ProfileId(profileId),
        host = host,
        chainType = toChainType(chainType),
        accountAddress = accountAddress,
        methods = json.decodeFromString(ListSerializer(String.serializer()), methodsJson),
        grantedAt = grantedAt
    )

    private fun WalletActivityEntity.toRecord(): WalletActivityRecord = WalletActivityRecord(
        id = id,
        profileId = ProfileId(profileId),
        chainType = toChainType(chainType),
        networkName = networkName,
        kind = WalletActivityRecord.Kind.valueOf(kind),
        accountAddress = accountAddress,
        toAddress = toAddress,
        displayAmount = displayAmount,
        hash = hash,
        explorerUrl = explorerUrl,
        createdAt = createdAt
    )

    /**
     * Chain names are written by this repository only; an unknown value
     * means the row predates a schema change and fails loudly instead of
     * silently mapping to a wrong chain.
     */
    private fun toChainType(name: String): ChainType =
        ChainType.fromName(name) ?: throw IllegalArgumentException("Unknown chain type stored: $name")
}
