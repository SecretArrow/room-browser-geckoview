package com.roombrowser.data.repo

import com.google.common.truth.Truth.assertThat
import com.roombrowser.browser.wallet.WalletAccountRecord
import com.roombrowser.browser.wallet.WalletActivityRecord
import com.roombrowser.data.db.DappPermissionDao
import com.roombrowser.data.db.DappPermissionEntity
import com.roombrowser.data.db.WalletAccountDao
import com.roombrowser.data.db.WalletAccountEntity
import com.roombrowser.data.db.WalletActiveNetworkEntity
import com.roombrowser.data.db.WalletActivityDao
import com.roombrowser.data.db.WalletActivityEntity
import com.roombrowser.data.db.WalletDao
import com.roombrowser.data.db.WalletEntity
import com.roombrowser.data.db.WalletNetworkDao
import com.roombrowser.data.db.WalletNetworkEntity
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.wallet.chains.ChainRegistry
import com.roombrowser.domain.wallet.chains.sui.SuiAdapter
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.domain.wallet.model.NetworkConfig
import com.roombrowser.security.VaultCryptor
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test

/**
 * JVM tests of the wallet repository against mockk-backed in-memory DAOs
 * and a fake cryptor — no Room, no Android Keystore. What is under test is
 * the repository's OWN behaviour: ciphertext-only persistence, duplicate
 * and no-wallet refusals, derivation-index math, network seeding and
 * active-network selection, dApp-permission upserts, activity round-trips
 * and the delete cascade.
 */
class WalletRepositoryTest {

    private val profileA = ProfileId("11111111-1111-1111-1111-111111111111")
    private val profileB = ProfileId("22222222-2222-2222-2222-222222222222")
    private val mnemonic = "abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon about"

    /**
     * Deterministic cryptor double: "enc:<key>:<plaintext>". Decrypt only
     * opens blobs sealed under the SAME key — a wrong profile key fails the
     * way the Keystore one would (tag mismatch), which is what the
     * isolation assertions rely on.
     */
    private class FakeCryptor : VaultCryptor {
        val encryptions = mutableListOf<Pair<String, String>>()

        override fun encrypt(profileKey: String, plaintext: String): String {
            encryptions.add(profileKey to plaintext)
            return "enc:$profileKey:$plaintext"
        }

        override fun decrypt(profileKey: String, encoded: String): String {
            val prefix = "enc:$profileKey:"
            require(encoded.startsWith(prefix)) { "blob sealed under a different profile key" }
            return encoded.removePrefix(prefix)
        }
    }

    /**
     * In-memory wallet DAOs on relaxed mockk — every answer is backed by a
     * map and mirrors its DAO's SQL contract (joins, filters, orders).
     */
    private class FakeWalletDb {
        val wallets = linkedMapOf<String, WalletEntity>()
        val accounts = linkedMapOf<String, WalletAccountEntity>()
        val networks = linkedMapOf<String, WalletNetworkEntity>() // key: "<profileId>|<id>"
        val activeNetworks = linkedMapOf<String, WalletActiveNetworkEntity>() // key: "<pid>|<chain>"
        val permissions = linkedMapOf<String, DappPermissionEntity>()
        val activities = linkedMapOf<String, WalletActivityEntity>()

        private fun nkey(pid: String, id: String) = "$pid|$id"

        /** The observeForProfile/forProfile JOIN: accounts via the profile's wallet. */
        private fun accountsForProfile(pid: String): List<WalletAccountEntity> {
            val walletIds = wallets.values.filter { it.profileId == pid }.map { it.id }.toSet()
            // ORDER BY created_at, rowid. sortedBy is STABLE and the map is
            // insertion-ordered, so ties keep insertion order exactly as the
            // rowid tiebreak does on the device. (A compareBy(..., id)
            // tiebreak would mirror the random UUID instead, and two accounts
            // created in the same millisecond — which this test does, and
            // which createWallet's per-chain derivation does too — would come
            // back in an arbitrary order.)
            return accounts.values.filter { it.walletId in walletIds }
                .sortedBy { it.createdAt }
        }

        private fun networksForProfile(pid: String): List<WalletNetworkEntity> =
            networks.values.filter { it.profileId == pid }
                .sortedWith(compareBy({ it.isCustom }, { it.id }))

        val walletDao = mockk<WalletDao>(relaxed = true).apply {
            coEvery { upsert(any()) } answers {
                val e = firstArg<WalletEntity>()
                wallets[e.id] = e
                Unit
            }
            coEvery { byProfile(any()) } answers {
                wallets.values.firstOrNull { it.profileId == firstArg<String>() }
            }
            every { observeByProfile(any()) } answers {
                flowOf(wallets.values.firstOrNull { it.profileId == firstArg<String>() })
            }
            coEvery { byId(any()) } answers { wallets[firstArg<String>()] }
            coEvery { deleteAllForProfile(any()) } answers {
                wallets.values.removeAll { it.profileId == firstArg<String>() }
                Unit
            }
        }

        val accountDao = mockk<WalletAccountDao>(relaxed = true).apply {
            coEvery { upsert(any()) } answers {
                val e = firstArg<WalletAccountEntity>()
                accounts[e.id] = e
                Unit
            }
            coEvery { byId(any()) } answers { accounts[firstArg<String>()] }
            every { observeForProfile(any()) } answers { flowOf(accountsForProfile(firstArg())) }
            coEvery { forProfile(any()) } answers { accountsForProfile(firstArg()) }
            coEvery { derivedForProfileChain(any(), any()) } answers {
                accountsForProfile(firstArg<String>())
                    .filter { it.chainType == secondArg<String>() && it.source == "DERIVED" }
            }
            coEvery { rename(any(), any()) } answers {
                accounts[firstArg<String>()]?.let { accounts[it.id] = it.copy(label = secondArg()) }
                Unit
            }
            coEvery { delete(any()) } answers {
                accounts.remove(firstArg<String>())
                Unit
            }
            coEvery { deleteForWallet(any()) } answers {
                accounts.values.removeAll { it.walletId == firstArg<String>() }
                Unit
            }
            coEvery { deleteAllForProfile(any()) } answers {
                val walletIds = wallets.values
                    .filter { it.profileId == firstArg<String>() }
                    .map { it.id }
                    .toSet()
                accounts.values.removeAll { it.walletId in walletIds }
                Unit
            }
        }

        val networkDao = mockk<WalletNetworkDao>(relaxed = true).apply {
            coEvery { upsert(any()) } answers {
                val e = firstArg<WalletNetworkEntity>()
                networks[nkey(e.profileId, e.id)] = e
                Unit
            }
            every { observeForProfile(any()) } answers { flowOf(networksForProfile(firstArg())) }
            coEvery { forProfile(any()) } answers { networksForProfile(firstArg()) }
            coEvery { byProfileAndId(any(), any()) } answers {
                networks[nkey(firstArg(), secondArg())]
            }
            coEvery { setEnabled(any(), any(), any()) } answers {
                networks[nkey(firstArg(), secondArg())]?.let {
                    networks[nkey(it.profileId, it.id)] = it.copy(enabled = thirdArg())
                }
                Unit
            }
            coEvery { delete(any(), any()) } answers {
                networks.remove(nkey(firstArg(), secondArg()))
                Unit
            }
            coEvery { deleteAllForProfile(any()) } answers {
                val pid = firstArg<String>()
                networks.keys.removeAll { it.startsWith("$pid|") }
                Unit
            }
            coEvery { upsertActive(any()) } answers {
                val e = firstArg<WalletActiveNetworkEntity>()
                activeNetworks["${e.profileId}|${e.chainType}"] = e
                Unit
            }
            coEvery { activeNetwork(any(), any()) } answers {
                activeNetworks["${firstArg<String>()}|${secondArg<String>()}"]
            }
            coEvery { deleteActiveNetworksForProfile(any()) } answers {
                val pid = firstArg<String>()
                activeNetworks.keys.removeAll { it.startsWith("$pid|") }
                Unit
            }
        }

        val permissionDao = mockk<DappPermissionDao>(relaxed = true).apply {
            coEvery { upsert(any()) } answers {
                val e = firstArg<DappPermissionEntity>()
                permissions[e.id] = e
                Unit
            }
            coEvery { byKey(any(), any(), any(), any()) } answers {
                val pid = firstArg<String>()
                val host = secondArg<String>()
                val chain = thirdArg<String>()
                val address = arg<String>(3)
                permissions.values.firstOrNull {
                    it.profileId == pid && it.host == host &&
                        it.chainType == chain && it.accountAddress == address
                }
            }
            coEvery { forHost(any(), any()) } answers {
                permissions.values
                    .filter { it.profileId == firstArg<String>() && it.host == secondArg<String>() }
                    .sortedWith(compareBy({ it.chainType }, { it.accountAddress }))
            }
            coEvery { allForProfile(any()) } answers {
                permissions.values
                    .filter { it.profileId == firstArg<String>() }
                    .sortedWith(compareBy({ it.host }, { it.chainType }, { it.accountAddress }))
            }
            coEvery { revokeForHostAndChain(any(), any(), any()) } answers {
                permissions.values.removeAll {
                    it.profileId == firstArg<String>() &&
                        it.host == secondArg<String>() &&
                        it.chainType == thirdArg<String>()
                }
                Unit
            }
            coEvery { deleteAllForProfile(any()) } answers {
                permissions.values.removeAll { it.profileId == firstArg<String>() }
                Unit
            }
        }

        val activityDao = mockk<WalletActivityDao>(relaxed = true).apply {
            coEvery { upsert(any()) } answers {
                val e = firstArg<WalletActivityEntity>()
                activities[e.id] = e
                Unit
            }
            every { observeForProfile(any()) } answers {
                flowOf(
                    activities.values
                        .filter { it.profileId == firstArg<String>() }
                        .sortedWith(
                            compareByDescending<WalletActivityEntity> { it.createdAt }
                                .thenByDescending { it.id }
                        )
                )
            }
            coEvery { deleteAllForProfile(any()) } answers {
                activities.values.removeAll { it.profileId == firstArg<String>() }
                Unit
            }
        }
    }

    private lateinit var db: FakeWalletDb
    private lateinit var cryptor: FakeCryptor
    private lateinit var repo: WalletRepository

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setUp() {
        db = FakeWalletDb()
        cryptor = FakeCryptor()
        repo = WalletRepository(
            db.walletDao,
            db.accountDao,
            db.networkDao,
            db.permissionDao,
            db.activityDao,
            cryptor
        )
    }

    /** assertThrows for suspend blocks — JUnit's takes a non-suspend lambda. */
    private suspend inline fun <reified T : Throwable> assertThrowsSuspend(
        block: suspend () -> Unit
    ): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("Expected ${T::class.java.simpleName} but got $e", e)
        }
        throw AssertionError("Expected ${T::class.java.simpleName} but nothing was thrown")
    }

    private fun WalletNetworkEntity.config(): NetworkConfig =
        json.decodeFromString(NetworkConfig.serializer(), payload)

    @Test
    fun `createWallet stores ciphertext only and rejects duplicates`() = runTest {
        val created = repo.createWallet(profileA, "Main wallet", mnemonic)

        assertThat(created.hasMnemonic).isTrue()
        assertThat(created.label).isEqualTo("Main wallet")
        assertThat(created.profileId).isEqualTo(profileA)
        assertThat(created.id).isNotEmpty()

        // The database row holds the cryptor's blob, never the plaintext.
        val row = db.wallets.values.single()
        assertThat(row.mnemonicEnc).isEqualTo("enc:${profileA.safeSuffix}:$mnemonic")
        assertThat(row.profileId).isEqualTo(profileA.value)

        // observe + one-shot read agree with the returned summary.
        assertThat(repo.observeWallet(profileA).first()).isEqualTo(created)
        assertThat(repo.wallet(profileA)).isEqualTo(created)

        // A second wallet for the same profile is refused.
        assertThrowsSuspend<IllegalStateException> {
            repo.createWallet(profileA, "Another", mnemonic)
        }
        assertThat(db.wallets).hasSize(1)

        // A blank mnemonic never touches the database.
        assertThrowsSuspend<IllegalArgumentException> {
            repo.createWallet(profileB, "Bad", "   ")
        }
        assertThat(db.wallets.values.map { it.profileId }).containsExactly(profileA.value)
        assertThat(cryptor.encryptions.map { it.first }.toSet())
            .containsExactly(profileA.safeSuffix)
    }

    @Test
    fun `revealMnemonic roundtrips and is null without a mnemonic or wallet`() = runTest {
        repo.createWallet(profileA, "Main", mnemonic)
        assertThat(repo.revealMnemonic(profileA)).isEqualTo(mnemonic)

        // No wallet at all.
        assertThat(repo.revealMnemonic(profileB)).isNull()

        // Imported-accounts-only wallet (the schema allows a NULL mnemonic).
        db.wallets["keyless"] = WalletEntity(
            id = "keyless",
            profileId = profileB.value,
            label = "Keyless",
            mnemonicEnc = null,
            createdAt = 1L
        )
        assertThat(repo.revealMnemonic(profileB)).isNull()
    }

    @Test
    fun `derived accounts store no key material and imported accounts store ciphertext`() =
        runTest {
            // No wallet yet: account creation fails loudly.
            assertThrowsSuspend<IllegalStateException> {
                repo.addDerivedAccount(profileB, ChainType.EVM, "0xabc", "m/44'/60'/0'/0/0", "x")
            }
            assertThrowsSuspend<IllegalStateException> {
                repo.addImportedAccount(profileB, ChainType.EVM, "0xabc", "k", "x")
            }

            repo.createWallet(profileA, "Main", mnemonic)

            val derived = repo.addDerivedAccount(
                profileA, ChainType.EVM, "0xabc", "m/44'/60'/0'/0/0", "EVM 1"
            )
            assertThat(derived.source).isEqualTo(WalletAccountRecord.Source.DERIVED)
            assertThat(derived.path).isEqualTo("m/44'/60'/0'/0/0")
            val derivedRow = db.accounts.values.single()
            assertThat(derivedRow.privateKeyEnc).isNull() // derived = no stored key
            assertThat(repo.revealPrivateKey(derived.id)).isNull()

            val imported = repo.addImportedAccount(
                profileA, ChainType.SOLANA, "SolAddr", "sol-secret-key", "Imported"
            )
            assertThat(imported.source).isEqualTo(WalletAccountRecord.Source.IMPORTED)
            assertThat(imported.path).isEmpty() // imports carry no path
            val importedRow = db.accounts.values.first { it.id == imported.id }
            assertThat(importedRow.privateKeyEnc)
                .isEqualTo("enc:${profileA.safeSuffix}:sol-secret-key")
            assertThat(repo.revealPrivateKey(imported.id)).isEqualTo("sol-secret-key")

            // observe + one-shot read agree, profile-scoped.
            assertThat(repo.observeAccounts(profileA).first())
                .containsExactly(derived, imported)
                .inOrder()
            assertThat(repo.accounts(profileA)).hasSize(2)
            assertThat(repo.accounts(profileB)).isEmpty()
        }

    @Test
    fun `nextDerivationIndex is max plus one with holes, per chain, imports excluded`() = runTest {
        repo.createWallet(profileA, "Main", mnemonic)

        // Nothing derived yet: 0.
        assertThat(repo.nextDerivationIndex(profileA, ChainType.EVM)).isEqualTo(0)

        repo.addDerivedAccount(profileA, ChainType.EVM, "0xa0", "m/44'/60'/0'/0/0", "0")
        repo.addDerivedAccount(profileA, ChainType.EVM, "0xa2", "m/44'/60'/0'/0/2", "2")
        repo.addDerivedAccount(profileA, ChainType.EVM, "0xa5", "m/44'/60'/0'/0/5", "5")

        // Non-contiguous indices: max(0, 2, 5) + 1.
        assertThat(repo.nextDerivationIndex(profileA, ChainType.EVM)).isEqualTo(6)

        // Other chains are independent, and a hardened final level reads as
        // its index: Sui stores m/44'/784'/0'/0'/i', so 0' is index 0.
        assertThat(repo.nextDerivationIndex(profileA, ChainType.SUI)).isEqualTo(0)
        repo.addDerivedAccount(profileA, ChainType.SUI, "0xsui0", "m/44'/784'/0'/0'/0'", "sui")
        assertThat(repo.nextDerivationIndex(profileA, ChainType.SUI)).isEqualTo(1)

        // Imported accounts never count towards derivation.
        repo.addImportedAccount(profileA, ChainType.EVM, "0ximported", "evm-secret", "imp")
        assertThat(repo.nextDerivationIndex(profileA, ChainType.EVM)).isEqualTo(6)

        // A profile without a wallet reports 0, not an error.
        assertThat(repo.nextDerivationIndex(profileB, ChainType.EVM)).isEqualTo(0)
    }

    /**
     * Each chain reads its own path shape. Sui and Aptos harden the ADDRESS
     * level, so their stored paths end in "'" — reading that element with
     * toIntOrNull alone returned null, the index fell back to 0 on every
     * call and "Add account" re-derived the first account forever (a UNIQUE
     * -index failure against the (wallet, chain, address) key). These
     * assertions pin the end-to-end index the next account is derived at.
     */
    @Test
    fun `nextDerivationIndex reads each chain's own path shape and never throws on a bad one`() =
        runTest {
            repo.createWallet(profileA, "Main", mnemonic)

            // EVM's final level is unhardened (m/44'/60'/0'/0/i) — the
            // regression guard for the chains that already worked.
            assertThat(repo.nextDerivationIndex(profileA, ChainType.EVM)).isEqualTo(0)
            repo.addDerivedAccount(profileA, ChainType.EVM, "0xa0", "m/44'/60'/0'/0/0", "0")
            assertThat(repo.nextDerivationIndex(profileA, ChainType.EVM)).isEqualTo(1)

            // Sui's canonical path carries the index on the hardened address
            // level (m/44'/784'/0'/0'/i'): account 2 must be derived at 1,
            // and account 3 at 2. This is the shape the earlier parse fix
            // repaired; it stays here so the chain-aware version cannot
            // regress it.
            assertThat(repo.nextDerivationIndex(profileA, ChainType.SUI)).isEqualTo(0)
            repo.addDerivedAccount(profileA, ChainType.SUI, "0xsui0", "m/44'/784'/0'/0'/0'", "s1")
            assertThat(repo.nextDerivationIndex(profileA, ChainType.SUI)).isEqualTo(1)
            repo.addDerivedAccount(profileA, ChainType.SUI, "0xsui1", "m/44'/784'/0'/0'/1'", "s2")
            assertThat(repo.nextDerivationIndex(profileA, ChainType.SUI)).isEqualTo(2)

            // Aptos has the same shape at m/54'/6'/0'/0'/i'.
            repo.addDerivedAccount(profileA, ChainType.APTOS, "0xapt4", "m/54'/6'/0'/0'/4'", "a")
            assertThat(repo.nextDerivationIndex(profileA, ChainType.APTOS)).isEqualTo(5)

            // Solana derives at m/44'/501'/i'/0': the index is on the ACCOUNT
            // level and the final element is the fixed hardened change 0'.
            // This used to be the live defect — reading only the last element
            // counted 0' for every account, so a third account asked for
            // index 1 again and collided with the second under the UNIQUE
            // (wallet, chain, address) index. The Solana adapter reads its own
            // shape now, so 0 and 1 in storage give 2. (The two stored paths
            // below are exactly what its adapter emits for 0 and 1.)
            assertThat(repo.nextDerivationIndex(profileA, ChainType.SOLANA)).isEqualTo(0)
            repo.addDerivedAccount(profileA, ChainType.SOLANA, "sol0", "m/44'/501'/0'/0'", "sol1")
            repo.addDerivedAccount(profileA, ChainType.SOLANA, "sol1", "m/44'/501'/1'/0'", "sol2")
            assertThat(repo.nextDerivationIndex(profileA, ChainType.SOLANA)).isEqualTo(2)

            // A level the TRON adapter cannot read as an index — a corrupt or
            // hand-edited row — is ignored, keeps the fallback and does NOT
            // throw.
            repo.addDerivedAccount(profileA, ChainType.TRON, "t-junk", "m/44'/195'/0'/0/x", "junk")
            assertThat(repo.nextDerivationIndex(profileA, ChainType.TRON)).isEqualTo(0)
            repo.addDerivedAccount(profileA, ChainType.TRON, "t-5", "m/44'/195'/0'/0/5", "five")
            assertThat(repo.nextDerivationIndex(profileA, ChainType.TRON)).isEqualTo(6)
        }

    /**
     * The Solana defect, end to end: Solana keeps its index on the ACCOUNT
     * level (m/44'/501'/i'/0') and its final level is always the fixed 0', so
     * a last-level rule counted 0 for every Solana account and a third
     * account was offered an index that already existed — the UNIQUE
     * (wallet, chain, address) index rejected it and the user could not
     * create one. Each account below must advance the counter by exactly one.
     */
    @Test
    fun `solana derivation index advances one per account so a third is creatable`() = runTest {
        repo.createWallet(profileA, "Main", mnemonic)

        assertThat(repo.nextDerivationIndex(profileA, ChainType.SOLANA)).isEqualTo(0)
        repo.addDerivedAccount(profileA, ChainType.SOLANA, "sol0", "m/44'/501'/0'/0'", "sol1")
        assertThat(repo.nextDerivationIndex(profileA, ChainType.SOLANA)).isEqualTo(1)
        repo.addDerivedAccount(profileA, ChainType.SOLANA, "sol1", "m/44'/501'/1'/0'", "sol2")
        assertThat(repo.nextDerivationIndex(profileA, ChainType.SOLANA)).isEqualTo(2)
        // The third account — the one the bug could never hand out.
        repo.addDerivedAccount(profileA, ChainType.SOLANA, "sol2", "m/44'/501'/2'/0'", "sol3")
        assertThat(repo.nextDerivationIndex(profileA, ChainType.SOLANA)).isEqualTo(3)

        // Holes are respected here too: 0, 1, 2 and 7 stored give 8.
        repo.addDerivedAccount(profileA, ChainType.SOLANA, "sol7", "m/44'/501'/7'/0'", "sol8")
        assertThat(repo.nextDerivationIndex(profileA, ChainType.SOLANA)).isEqualTo(8)

        // Only the ACCOUNT level counts: a path whose third level is not a
        // number is ignored rather than mis-read, and does not throw.
        repo.addDerivedAccount(profileA, ChainType.SOLANA, "sol-junk", "m/44'/501'/x'/0'", "junk")
        assertThat(repo.nextDerivationIndex(profileA, ChainType.SOLANA)).isEqualTo(8)
    }

    @Test
    fun `renameAccount and removeAccount work by id`() = runTest {
        repo.createWallet(profileA, "Main", mnemonic)
        val account = repo.addDerivedAccount(
            profileA, ChainType.EVM, "0xabc", "m/44'/60'/0'/0/0", "Old"
        )

        repo.renameAccount(account.id, "New")
        assertThat(repo.accounts(profileA).single().label).isEqualTo("New")

        repo.removeAccount(account.id)
        assertThat(repo.accounts(profileA)).isEmpty()
    }

    @Test
    fun `ensureDefaultNetworks seeds one enabled network per family idempotently`() = runTest {
        val registry = ChainRegistry()
        repo.ensureDefaultNetworks(profileA)

        val rows = db.networks.values.filter { it.profileId == profileA.value }
        assertThat(rows.map { it.id })
            .containsExactlyElementsIn(registry.allDefaultNetworks().map { it.id })

        // Exactly one ENABLED network per family: its first (the mainnet).
        ChainType.entries.forEach { chain ->
            val family = rows.filter { it.config().chainType == chain }
            val enabled = family.filter { it.enabled }
            assertThat(enabled).hasSize(1)
            assertThat(enabled.single().id).isEqualTo(registry.defaultNetworks(chain).first().id)
            assertThat(family.none { it.isCustom }).isTrue()
        }

        // Payloads round-trip as full NetworkConfigs.
        assertThat(repo.networks(profileA).map { it.config })
            .contains(registry.defaultNetworks(ChainType.EVM).first())

        // Idempotence: a user-disabled default is NEVER re-enabled, and no
        // row is duplicated.
        repo.setNetworkEnabled(profileA, "EVM:1", false)
        repo.ensureDefaultNetworks(profileA)
        val evmEnabled = db.networks.values
            .filter { it.profileId == profileA.value && it.enabled }
            .filter { it.config().chainType == ChainType.EVM }
        assertThat(evmEnabled).isEmpty()
        assertThat(db.networks.values.filter { it.profileId == profileA.value })
            .hasSize(registry.allDefaultNetworks().size)
    }

    @Test
    fun `a bundled network's addresses are refreshed without touching the user's switch`() = runTest {
        repo.ensureDefaultNetworks(profileA)
        val key = "${profileA.value}|SUI:mainnet"

        // The row as an older build seeded it, pointed at a host that has since
        // deprecated its API — with the user having switched that network OFF.
        val stale = SuiAdapter.MAINNET.copy(rpcUrls = listOf("https://fullnode.mainnet.sui.io"))
        db.networks[key] = db.networks.getValue(key).copy(
            enabled = false,
            payload = Json.encodeToString(NetworkConfig.serializer(), stale)
        )

        repo.ensureDefaultNetworks(profileA)

        val refreshed = db.networks.getValue(key)
        // The addresses are ours to correct: a wallet left dialling the dead
        // host errors on every read and every send, for every profile.
        assertThat(refreshed.config()).isEqualTo(SuiAdapter.MAINNET)
        assertThat(refreshed.config().rpcUrls).doesNotContain("https://fullnode.mainnet.sui.io")
        // The switch is the user's: refreshing must not turn it back on, and
        // must not quietly promote the row to a custom network either.
        assertThat(refreshed.enabled).isFalse()
        assertThat(refreshed.isCustom).isFalse()
        assertThat(db.networks.getValue("${profileA.value}|EVM:1").enabled).isTrue()
    }

    @Test
    fun `a custom row that took over a default's id is never refreshed`() = runTest {
        repo.ensureDefaultNetworks(profileA)
        val custom = NetworkConfig.evm(
            1L, "My Ethereum", listOf("https://my.example/rpc"), "ETH", null
        )
        repo.upsertCustomNetwork(profileA, custom)

        repo.ensureDefaultNetworks(profileA)

        val row = db.networks.getValue("${profileA.value}|EVM:1")
        assertThat(row.isCustom).isTrue()
        assertThat(row.config().rpcUrls).containsExactly("https://my.example/rpc")
    }

    @Test
    fun `a network dropped from the catalogue is removed only while it is off`() = runTest {
        repo.ensureDefaultNetworks(profileA)

        // A profile seeded by a build that still shipped a Sui devnet preset,
        // left switched off — exactly how this one is seeded.
        fun seedDevnet(enabled: Boolean) {
            val devnet = SuiAdapter.MAINNET.copy(
                id = "SUI:devnet",
                chainId = "devnet",
                name = "Sui Devnet",
                rpcUrls = listOf("https://fullnode.devnet.sui.io"),
                isTestnet = true
            )
            db.networks["${profileA.value}|SUI:devnet"] = WalletNetworkEntity(
                id = devnet.id,
                profileId = profileA.value,
                enabled = enabled,
                isCustom = false,
                payload = Json.encodeToString(NetworkConfig.serializer(), devnet)
            )
        }

        seedDevnet(enabled = false)
        repo.ensureDefaultNetworks(profileA)
        assertThat(db.networks.keys).doesNotContain("${profileA.value}|SUI:devnet")

        // Switched ON it is the network the user is on: not deleted from
        // under them, even though the catalogue no longer lists it.
        seedDevnet(enabled = true)
        repo.ensureDefaultNetworks(profileA)
        assertThat(db.networks.keys).contains("${profileA.value}|SUI:devnet")
    }

    @Test
    fun `upsert remove and toggle custom networks without touching defaults`() = runTest {
        repo.ensureDefaultNetworks(profileA)
        val custom = NetworkConfig.evm(
            1337L, "My Chain", listOf("https://rpc.example.com"), "MCH", null
        )

        repo.upsertCustomNetwork(profileA, custom)
        val stored = repo.networks(profileA).single { it.config.id == "EVM:1337" }
        assertThat(stored.isCustom).isTrue()
        assertThat(stored.enabled).isTrue()

        // Upserting again replaces the payload (the edit flow).
        repo.upsertCustomNetwork(profileA, custom.copy(name = "My Chain 2"))
        assertThat(repo.networks(profileA).single { it.config.id == "EVM:1337" }.config.name)
            .isEqualTo("My Chain 2")

        // Toggling works for defaults and customs alike.
        repo.setNetworkEnabled(profileA, "EVM:1", false)
        repo.setNetworkEnabled(profileA, "EVM:1337", false)
        val toggled = repo.networks(profileA)
        assertThat(toggled.single { it.config.id == "EVM:1" }.enabled).isFalse()
        assertThat(toggled.single { it.config.id == "EVM:1337" }.enabled).isFalse()

        // Removing a custom works; removing a seeded default is a no-op.
        repo.removeCustomNetwork(profileA, "EVM:1337")
        repo.removeCustomNetwork(profileA, "EVM:1")
        val ids = repo.networks(profileA).map { it.config.id }
        assertThat(ids).doesNotContain("EVM:1337")
        assertThat(ids).contains("EVM:1")
    }

    @Test
    fun `activeNetwork defaults to the first enabled network and honors overrides`() = runTest {
        repo.ensureDefaultNetworks(profileA)

        // Default: the only enabled EVM network is Ethereum Mainnet.
        assertThat(repo.activeNetwork(profileA, ChainType.EVM)?.id).isEqualTo("EVM:1")

        // An explicit choice wins even while Ethereum stays enabled.
        repo.setActiveNetwork(profileA, ChainType.EVM, "EVM:137")
        assertThat(repo.activeNetwork(profileA, ChainType.EVM)?.id).isEqualTo("EVM:137")

        // A disabled choice falls back to the first enabled network.
        repo.setNetworkEnabled(profileA, "EVM:137", false)
        assertThat(repo.activeNetwork(profileA, ChainType.EVM)?.id).isEqualTo("EVM:1")

        // Selecting a disabled network enables it — an active network must be usable.
        repo.setActiveNetwork(profileA, ChainType.EVM, "EVM:10")
        assertThat(repo.activeNetwork(profileA, ChainType.EVM)?.id).isEqualTo("EVM:10")
        assertThat(repo.networks(profileA).single { it.config.id == "EVM:10" }.enabled).isTrue()

        // Unknown network ids and foreign-chain ids fail loudly.
        assertThrowsSuspend<IllegalArgumentException> {
            repo.setActiveNetwork(profileA, ChainType.EVM, "EVM:999999")
        }
        assertThrowsSuspend<IllegalArgumentException> {
            repo.setActiveNetwork(profileA, ChainType.EVM, "SOLANA:mainnet-beta")
        }

        // A chain with no enabled network at all resolves to null.
        repo.setNetworkEnabled(profileA, "SOLANA:mainnet-beta", false)
        assertThat(repo.activeNetwork(profileA, ChainType.SOLANA)).isNull()
    }

    @Test
    fun `dApp permissions grant upserts by host chain and account and revokes by host chain`() =
        runTest {
            repo.grantDappPermission(
                profileA, "app.uniswap.org", ChainType.EVM, "0xabc",
                listOf("eth_requestAccounts", "personal_sign")
            )

            val first = repo.dappPermissions(profileA, "app.uniswap.org").single()
            assertThat(first.host).isEqualTo("app.uniswap.org")
            assertThat(first.chainType).isEqualTo(ChainType.EVM)
            assertThat(first.accountAddress).isEqualTo("0xabc")
            assertThat(first.methods).containsExactly("eth_requestAccounts", "personal_sign")

            // A re-grant refreshes methods + grantedAt on the SAME row.
            repo.grantDappPermission(
                profileA, "app.uniswap.org", ChainType.EVM, "0xabc", listOf("eth_accounts")
            )
            val refreshed = repo.dappPermissions(profileA, "app.uniswap.org").single()
            assertThat(refreshed.id).isEqualTo(first.id)
            assertThat(refreshed.methods).containsExactly("eth_accounts")
            assertThat(refreshed.grantedAt).isAtLeast(first.grantedAt)
            assertThat(db.permissions).hasSize(1)

            // A second account under the same host+chain is its own row.
            repo.grantDappPermission(
                profileA, "app.uniswap.org", ChainType.EVM, "0xdef",
                listOf("eth_requestAccounts")
            )
            assertThat(repo.dappPermissions(profileA, "app.uniswap.org")).hasSize(2)
            assertThat(repo.allDappPermissions(profileA)).hasSize(2)

            // Cross-profile isolation.
            assertThat(repo.allDappPermissions(profileB)).isEmpty()

            // Revoke drops EVERY account permission of the host+chain pair.
            repo.revokeDappPermission(profileA, "app.uniswap.org", ChainType.EVM)
            assertThat(repo.dappPermissions(profileA, "app.uniswap.org")).isEmpty()
            assertThat(db.permissions).isEmpty()
        }

    @Test
    fun `recordActivity roundtrips through observe`() = runTest {
        val record = WalletActivityRecord(
            id = "act-1",
            profileId = profileA,
            chainType = ChainType.EVM,
            networkName = "Ethereum Mainnet",
            kind = WalletActivityRecord.Kind.SEND,
            accountAddress = "0xabc",
            toAddress = "0xdef",
            displayAmount = "0.1 ETH",
            hash = "0xhash",
            explorerUrl = "https://etherscan.io/tx/0xhash",
            createdAt = 42L
        )
        repo.recordActivity(record)

        assertThat(repo.observeActivities(profileA).first()).containsExactly(record)
        assertThat(repo.observeActivities(profileB).first()).isEmpty()
    }

    @Test
    fun `deleteWallet cascades every wallet row but leaves other profiles alone`() = runTest {
        // Seed profile A fully, plus a wallet for profile B.
        repo.createWallet(profileA, "A", mnemonic)
        repo.createWallet(profileB, "B", mnemonic)
        repo.ensureDefaultNetworks(profileA)
        repo.ensureDefaultNetworks(profileB)
        val aAccount = repo.addDerivedAccount(
            profileA, ChainType.EVM, "0xabc", "m/44'/60'/0'/0/0", "A1"
        )
        repo.addDerivedAccount(profileB, ChainType.EVM, "0xfff", "m/44'/60'/0'/0/0", "B1")
        repo.setActiveNetwork(profileA, ChainType.EVM, "EVM:137")
        repo.grantDappPermission(
            profileA, "app.uniswap.org", ChainType.EVM, aAccount.address,
            listOf("eth_requestAccounts")
        )
        repo.recordActivity(
            WalletActivityRecord(
                id = "act-1",
                profileId = profileA,
                chainType = ChainType.EVM,
                networkName = "Ethereum Mainnet",
                kind = WalletActivityRecord.Kind.DAPP_SEND,
                accountAddress = "0xabc",
                toAddress = null,
                displayAmount = "0.1 ETH",
                hash = null,
                explorerUrl = null,
                createdAt = 1L
            )
        )

        repo.deleteWallet(profileA)

        assertThat(db.wallets.values.map { it.profileId }).containsExactly(profileB.value)
        assertThat(db.accounts.values.map { it.address }).containsExactly("0xfff")
        assertThat(db.networks.values.map { it.profileId }.toSet())
            .containsExactly(profileB.value)
        // A's explicit choice row is gone; B never made one (default
        // resolution below needs no row).
        assertThat(db.activeNetworks).isEmpty()
        assertThat(db.permissions).isEmpty()
        assertThat(db.activities).isEmpty()
        assertThat(repo.wallet(profileA)).isNull()
        assertThat(repo.accounts(profileA)).isEmpty()
        assertThat(repo.activeNetwork(profileB, ChainType.EVM)?.id).isEqualTo("EVM:1")

        // Idempotent: deleting an absent wallet is a no-op.
        repo.deleteWallet(profileA)
        assertThat(db.wallets).hasSize(1)
    }
}
