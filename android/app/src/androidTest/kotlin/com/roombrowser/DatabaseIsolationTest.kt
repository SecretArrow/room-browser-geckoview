package com.roombrowser

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.roombrowser.browser.wallet.WalletActivityRecord
import com.roombrowser.data.db.AppDatabase
import com.roombrowser.data.db.ProfileEntity
import com.roombrowser.data.db.TabEntity
import com.roombrowser.data.db.BookmarkEntity
import com.roombrowser.data.db.HistoryEntity
import com.roombrowser.data.repo.BrowserRepository
import com.roombrowser.data.repo.CredentialRepository
import com.roombrowser.data.repo.ProfileRepositoryImpl
import com.roombrowser.data.repo.WalletRepository
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.security.VaultCrypto
import com.roombrowser.security.VaultCryptoException
import com.roombrowser.security.WalletKeyCrypto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Database-level profile isolation tests (instrumented; Room needs Android).
 * Verifies that tabs/bookmarks/history/credentials are physically scoped by
 * profile_id, that deleting a profile cascades ONLY to that profile's rows,
 * that the per-profile vault crypto round-trips on-device (and fails loudly
 * on wrong keys / corrupt data), that tab positions are allocated uniquely
 * across open AND closed rows, and that the wallet tables (Room v8) are
 * scoped + encrypted per profile with the profile-delete cascade wiping
 * exactly one profile's wallet data.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseIsolationTest {

    private lateinit var db: AppDatabase
    private val profileA = "11111111-1111-1111-1111-111111111111"
    private val profileB = "22222222-2222-2222-2222-222222222222"

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            db.profileDao().upsert(profile(profileA, "Personal"))
            db.profileDao().upsert(profile(profileB, "Work"))
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun profile(id: String, name: String) = ProfileEntity(
        id = id, name = name, icon = "x", colorArgb = 0,
        isLocked = false, isDefault = false,
        createdAt = 0, lastActiveAt = 0, settingsJson = "{}"
    )

    @Test
    fun tabs_are_scoped_per_profile() = runBlocking {
        db.tabDao().upsert(TabEntity("tabA", profileA, 0, "A tab", "https://a.example.com", false, createdAt = 0, lastViewedAt = 0))
        db.tabDao().upsert(TabEntity("tabB", profileB, 0, "B tab", "https://b.example.com", false, createdAt = 0, lastViewedAt = 0))

        assertThat(db.tabDao().openTabs(profileA).map { it.id }).containsExactly("tabA")
        assertThat(db.tabDao().openTabs(profileB).map { it.id }).containsExactly("tabB")
        assertThat(db.tabDao().openCount(profileA)).isEqualTo(1)
    }

    @Test
    fun bookmarks_and_history_are_scoped_per_profile() = runBlocking {
        db.bookmarkDao().upsert(BookmarkEntity(profileId = profileA, url = "https://a.example.com", title = "A", createdAt = 0))
        db.historyDao().insert(HistoryEntity(profileId = profileB, url = "https://b.example.com", title = "B", visitedAt = 1))

        assertThat(db.bookmarkDao().all(profileA)).hasSize(1)
        assertThat(db.bookmarkDao().all(profileB)).isEmpty()
        assertThat(db.historyDao().since(profileA, 0)).isEmpty()
        assertThat(db.historyDao().since(profileB, 0)).hasSize(1)
    }

    @Test
    fun deleting_profile_cascades_only_that_profile() = runBlocking {
        db.tabDao().upsert(TabEntity("tabA", profileA, 0, "A", "https://a.example.com", false, createdAt = 0, lastViewedAt = 0))
        db.tabDao().upsert(TabEntity("tabB", profileB, 0, "B", "https://b.example.com", false, createdAt = 0, lastViewedAt = 0))
        db.bookmarkDao().upsert(BookmarkEntity(profileId = profileA, url = "https://a.example.com", title = "A", createdAt = 0))
        db.bookmarkDao().upsert(BookmarkEntity(profileId = profileB, url = "https://b.example.com", title = "B", createdAt = 0))

        db.profileDao().delete(profileA)
        // ProfileRepositoryImpl.remove(cascade=true) also purges dependent rows:
        db.tabDao().deleteAllFor(profileA)
        db.bookmarkDao().deleteAllFor(profileA)

        assertThat(db.tabDao().openTabs(profileA)).isEmpty()
        assertThat(db.tabDao().openTabs(profileB)).hasSize(1)
        assertThat(db.bookmarkDao().all(profileB)).hasSize(1)
    }

    @Test
    fun notes_are_scoped_and_cascade_with_their_profile() = runBlocking<Unit> {
        val a = ProfileId(profileA)
        val b = ProfileId(profileB)
        val repo = BrowserRepository(db)
        repo.saveNote(a, "A note", "a body")
        repo.saveNote(b, "B note", "b body")
        assertThat(repo.notes(a).map { it.title }).containsExactly("A note")
        assertThat(repo.notes(b).map { it.title }).containsExactly("B note")

        // The REAL profile-deletion cascade, same call the profile UI makes.
        ProfileRepositoryImpl(db).remove(a, cascadeData = true)

        assertThat(repo.notes(a)).isEmpty()
        assertThat(repo.notes(b).map { it.title }).containsExactly("B note")
    }

    @Test
    fun uuid_persistence_and_rename_keeps_identity() = runBlocking {
        db.profileDao().upsert(profile(profileA, "Personal"))
        val loaded = db.profileDao().get(profileA)!!
        assertThat(loaded.name).isEqualTo("Personal")
        db.profileDao().upsert(loaded.copy(name = "Family"))
        val renamed = db.profileDao().get(profileA)!!
        assertThat(renamed.id).isEqualTo(profileA)
        assertThat(renamed.name).isEqualTo("Family")
    }

    /**
     * Credentials are profile data like tabs and history: the repository
     * (real AndroidKeyStore cryptor over the real Room v7 schema) must only
     * ever hand a profile its own rows — and the cascade that Profile-
     * RepositoryImpl.remove runs must clear the profile's rows while the
     * other profile's vault stays intact.
     */
    @Test
    fun credentials_are_scoped_and_encrypted_per_profile() = runBlocking<Unit> {
        val a = ProfileId(profileA)
        val b = ProfileId(profileB)
        val repo = CredentialRepository(db.credentialDao(), VaultCrypto)
        repo.unlock()

        val savedA = repo.save(a, "https://A.Example.com/signin", "user-a", "secret-a", "A login")
        repo.save(b, "b.example.com", "user-b", "secret-b")

        // observe / export see only the caller's own profile.
        assertThat(repo.observe(a).first().map { it.username }).containsExactly("user-a")
        assertThat(repo.observe(b).first().map { it.username }).containsExactly("user-b")
        assertThat(repo.exportAll(a).map { it.password }).containsExactly("secret-a")
        assertThat(repo.exportAll(b).map { it.password }).containsExactly("secret-b")

        // Another profile's row id never surfaces through A's calls.
        assertThat(repo.get(b, savedA.id)).isNull()

        // The stored row is ciphertext: the plaintext is nowhere in the DB,
        // the blob decrypts under A's key — and under nobody else's.
        val rowA = db.credentialDao().allForProfile(profileA).single()
        assertThat(rowA.passwordEnc).isNotEqualTo("secret-a")
        assertThat(rowA.domain).isEqualTo("a.example.com") // canonicalized
        assertThat(VaultCrypto.decrypt(a, rowA.passwordEnc)).isEqualTo("secret-a")
        assertThrows(VaultCryptoException::class.java) {
            VaultCrypto.decrypt(b, rowA.passwordEnc)
        }

        // The profile-deletion cascade (credentialDao.deleteAllForProfile +
        // VaultCrypto.deleteKey in ProfileRepositoryImpl) clears A only.
        db.credentialDao().deleteAllForProfile(profileA)
        assertThat(db.credentialDao().countForProfile(profileA)).isEqualTo(0)
        assertThat(repo.exportAll(b).map { it.username }).containsExactly("user-b")
    }

    /**
     * The on-device vault crypto contract: round-trip under the profile's
     * own key, per-profile key isolation, and LOUD failure on wrong data
     * (never a silent null — that would drop a saved password).
     */
    @Test
    fun vault_crypto_roundtrip_and_loud_corruption_failures() {
        val fake = ProfileId("99999999-9999-9999-9999-999999999999")
        val other = ProfileId("88888888-8888-8888-8888-888888888888")

        val blob = VaultCrypto.encrypt(fake, "round-trip-secret")
        assertThat(VaultCrypto.decrypt(fake, blob)).isEqualTo("round-trip-secret")
        assertThat(blob).doesNotContain("round-trip-secret")

        // A different profile's key cannot open the blob (GCM tag mismatch).
        assertThrows(VaultCryptoException::class.java) {
            VaultCrypto.decrypt(other, blob)
        }
        // Corrupt / truncated / empty payloads fail loudly, never null.
        assertThrows(VaultCryptoException::class.java) {
            VaultCrypto.decrypt(fake, "definitely-not-base64-!!!")
        }
        assertThrows(VaultCryptoException::class.java) {
            VaultCrypto.decrypt(fake, "")
        }
        assertThrows(VaultCryptoException::class.java) {
            VaultCrypto.decrypt(fake, "AAAA") // shorter than the 12-byte IV
        }

        // The keystore alias is the profile's safe suffix.
        assertThat(VaultCrypto.aliasFor(fake)).isEqualTo("roomvault-" + fake.safeSuffix)
    }

    /**
     * TabDao.insertNextPosition is the atomic position allocator: positions
     * must be unique and monotonic across ALL of the profile's rows — open
     * AND closed — so a reopened closed tab can never collide with a newer
     * tab (the pre-fix bug), and a second profile starts its own sequence.
     */
    @Test
    fun tab_positions_are_unique_and_monotonic_across_closed_rows() = runBlocking {
        db.tabDao().insertNextPosition("t1", profileA, "Home", "about:home", isPrivate = false, createdAt = 0, lastViewedAt = 0)
        db.tabDao().insertNextPosition("t2", profileA, "A", "https://a.example.com", isPrivate = false, createdAt = 1, lastViewedAt = 1)
        // Close the first row — its position must never be handed out again.
        db.tabDao().close("t1", 100)
        db.tabDao().insertNextPosition("t3", profileA, "B", "https://b.example.com", isPrivate = false, createdAt = 2, lastViewedAt = 2)
        db.tabDao().insertNextPosition("t4", profileA, "C", "https://c.example.com", isPrivate = false, createdAt = 3, lastViewedAt = 3)

        assertThat(db.tabDao().get("t1")!!.position).isEqualTo(0)
        assertThat(db.tabDao().get("t2")!!.position).isEqualTo(1)
        assertThat(db.tabDao().get("t3")!!.position).isEqualTo(2)
        assertThat(db.tabDao().get("t4")!!.position).isEqualTo(3)

        // Reopening the closed row keeps the profile's positions unique.
        db.tabDao().reopen("t1")
        val positions = db.tabDao().openTabs(profileA).map { it.position }
        assertThat(positions).containsExactly(0, 1, 2, 3)

        // A different profile has its own sequence.
        db.tabDao().insertNextPosition("u1", profileB, "P", "https://p.example.com", isPrivate = false, createdAt = 0, lastViewedAt = 0)
        assertThat(db.tabDao().get("u1")!!.position).isEqualTo(0)
    }

    /**
     * The wallet tables are profile data like everything else: the
     * repository (real WalletKeyCrypto over the real Room v8 schema) hands a
     * profile only its own wallet/accounts/networks/permissions/activity,
     * the stored mnemonic is ciphertext under the profile's OWN wallet key
     * (and nobody else's), and derived accounts persist no key material.
     *
     * Fixed public vectors (WalletEngineTest / ChainAdaptersCrossValidation
     * .Test): the standard "abandon … about" phrase for profile A, the
     * "shoot island …" phrase for profile B.
     */
    @Test
    fun wallet_tables_are_scoped_and_encrypted_per_profile() = runBlocking<Unit> {
        val a = ProfileId(profileA)
        val b = ProfileId(profileB)
        val repo = WalletRepository(
            db.walletDao(),
            db.walletAccountDao(),
            db.walletNetworkDao(),
            db.dappPermissionDao(),
            db.walletActivityDao(),
            WalletKeyCrypto
        )
        val mnemonicA =
            "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon about"
        val mnemonicB =
            "shoot island position soft burden budget tooth cruel issue economy destroy above"

        val walletA = repo.createWallet(a, "A Wallet", mnemonicA)
        repo.createWallet(b, "B Wallet", mnemonicB)

        // One wallet per profile — different rows, never each other's.
        assertThat(repo.wallet(a)?.id).isEqualTo(walletA.id)
        assertThat(repo.wallet(b)?.id).isNotEqualTo(walletA.id)

        // Accounts are scoped through the profile's wallet (JOIN), and
        // DERIVED rows persist no key material. The addresses are the fixed
        // cross-validated vectors (abandon-mnemonic index-0 for A; the
        // c5338c-key import address for B — opaque strings to the repo).
        val evm0 = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
        val evmImport = "0x417AA4b5a8bf239d05C03C7C0C0231ECF7620c26"
        repo.addDerivedAccount(a, ChainType.EVM, evm0, "m/44'/60'/0'/0/0", "EVM 1")
        repo.addDerivedAccount(b, ChainType.EVM, evmImport, "m/44'/60'/0'/0/0", "EVM 1")
        assertThat(repo.accounts(a).map { it.address }).containsExactly(evm0)
        assertThat(repo.accounts(b).map { it.address }).containsExactly(evmImport)
        assertThat(db.walletAccountDao().forProfile(profileA).single().privateKeyEnc).isNull()

        // The mnemonic is ciphertext: no plaintext in the column, and the
        // blob decrypts under the profile's OWN wallet key — under nobody
        // else's.
        val rowA = db.walletDao().byProfile(profileA) ?: error("A must have a wallet row")
        val encA = rowA.mnemonicEnc ?: error("A's wallet row must store the mnemonic blob")
        assertThat(encA).doesNotContain("abandon")
        assertThat(WalletKeyCrypto.decrypt(a, encA)).isEqualTo(mnemonicA)
        assertThrows(VaultCryptoException::class.java) {
            WalletKeyCrypto.decrypt(b, encA)
        }

        // Networks: identical default catalogues per profile, but the rows
        // are INDEPENDENT — disabling A's Ethereum Mainnet never touches B's.
        repo.ensureDefaultNetworks(a)
        repo.ensureDefaultNetworks(b)
        val netA = repo.networks(a)
        val netB = repo.networks(b)
        assertThat(netA.map { it.config.id }).isEqualTo(netB.map { it.config.id })
        repo.setNetworkEnabled(a, "EVM:1", false)
        assertThat(repo.networks(a).first { it.config.id == "EVM:1" }.enabled).isFalse()
        assertThat(repo.networks(b).first { it.config.id == "EVM:1" }.enabled).isTrue()
        // No enabled EVM network left for A; B keeps its mainnet default.
        assertThat(repo.activeNetwork(a, ChainType.EVM)).isNull()
        assertThat(repo.activeNetwork(b, ChainType.EVM)?.id).isEqualTo("EVM:1")

        // dApp permissions and the activity log are scoped per profile too.
        repo.grantDappPermission(a, "a.example.com", ChainType.EVM, evm0, listOf("eth_requestAccounts"))
        repo.grantDappPermission(b, "b.example.com", ChainType.EVM, evmImport, listOf("eth_requestAccounts"))
        assertThat(repo.dappPermissions(a, "b.example.com")).isEmpty()
        assertThat(repo.allDappPermissions(a).map { it.host }).containsExactly("a.example.com")
        assertThat(repo.allDappPermissions(b).map { it.host }).containsExactly("b.example.com")
        repo.recordActivity(
            WalletActivityRecord(
                id = "act-a", profileId = a, chainType = ChainType.EVM, networkName = "Ethereum",
                kind = WalletActivityRecord.Kind.SIGN_MESSAGE, accountAddress = evm0,
                toAddress = null, displayAmount = "message", hash = null,
                explorerUrl = null, createdAt = 1
            )
        )
        repo.recordActivity(
            WalletActivityRecord(
                id = "act-b", profileId = b, chainType = ChainType.EVM, networkName = "Ethereum",
                kind = WalletActivityRecord.Kind.SIGN_MESSAGE, accountAddress = evmImport,
                toAddress = null, displayAmount = "message", hash = null,
                explorerUrl = null, createdAt = 2
            )
        )
        assertThat(db.walletActivityDao().observeForProfile(profileA).first().map { it.id })
            .containsExactly("act-a")
        assertThat(db.walletActivityDao().observeForProfile(profileB).first().map { it.id })
            .containsExactly("act-b")
    }

    /**
     * The profile-deletion cascade (ProfileRepositoryImpl.remove with
     * cascadeData = true — the REAL path the profile UI runs) wipes EVERY
     * wallet table row of THAT profile — accounts before the wallet row they
     * reference — and destroys only its wallet key: the other profile's
     * wallet rows survive AND still decrypt under its untouched key.
     */
    @Test
    fun deleting_profile_cascades_wallet_rows_for_that_profile_only() = runBlocking {
        val a = ProfileId(profileA)
        val b = ProfileId(profileB)
        val repo = WalletRepository(
            db.walletDao(),
            db.walletAccountDao(),
            db.walletNetworkDao(),
            db.dappPermissionDao(),
            db.walletActivityDao(),
            WalletKeyCrypto
        )
        val mnemonicA =
            "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon about"
        val mnemonicB =
            "shoot island position soft burden budget tooth cruel issue economy destroy above"
        repo.createWallet(a, "A Wallet", mnemonicA)
        repo.createWallet(b, "B Wallet", mnemonicB)
        repo.addDerivedAccount(a, ChainType.EVM, "0x9858EfFD232B4033E47d90003D41EC34EcaEda94", "m/44'/60'/0'/0/0", "EVM 1")
        repo.addDerivedAccount(b, ChainType.EVM, "0x417AA4b5a8bf239d05C03C7C0C0231ECF7620c26", "m/44'/60'/0'/0/0", "EVM 1")
        repo.ensureDefaultNetworks(a)
        repo.ensureDefaultNetworks(b)
        repo.grantDappPermission(a, "a.example.com", ChainType.EVM, "0x9858EfFD232B4033E47d90003D41EC34EcaEda94", listOf("eth_requestAccounts"))
        repo.grantDappPermission(b, "b.example.com", ChainType.EVM, "0x417AA4b5a8bf239d05C03C7C0C0231ECF7620c26", listOf("eth_requestAccounts"))
        repo.recordActivity(
            WalletActivityRecord(
                id = "act-a", profileId = a, chainType = ChainType.EVM, networkName = "Ethereum",
                kind = WalletActivityRecord.Kind.DAPP_SEND, accountAddress = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94",
                toAddress = "0x000000000000000000000000000000000000dEaD",
                displayAmount = "0.001 ETH", hash = "0xhash", explorerUrl = null, createdAt = 1
            )
        )
        repo.recordActivity(
            WalletActivityRecord(
                id = "act-b", profileId = b, chainType = ChainType.EVM, networkName = "Ethereum",
                kind = WalletActivityRecord.Kind.DAPP_SEND, accountAddress = "0x417AA4b5a8bf239d05C03C7C0C0231ECF7620c26",
                toAddress = "0x000000000000000000000000000000000000dEaD",
                displayAmount = "0.001 ETH", hash = "0xhash", explorerUrl = null, createdAt = 2
            )
        )

        // The REAL cascade — same call ProfileRepositoryImpl's profile-UI
        // path makes (rows first, then the profile's wallet key).
        ProfileRepositoryImpl(db).remove(a, cascadeData = true)

        // Every wallet table is wiped for A — and ONLY for A.
        assertThat(db.walletDao().byProfile(profileA)).isNull()
        assertThat(db.walletAccountDao().forProfile(profileA)).isEmpty()
        assertThat(db.walletNetworkDao().forProfile(profileA)).isEmpty()
        assertThat(db.walletNetworkDao().activeNetwork(profileA, "EVM")).isNull()
        assertThat(db.dappPermissionDao().allForProfile(profileA)).isEmpty()
        assertThat(db.walletActivityDao().observeForProfile(profileA).first()).isEmpty()
        assertThat(repo.wallet(a)).isNull()

        // B's wallet data is fully intact.
        assertThat(db.walletDao().byProfile(profileB)).isNotNull()
        assertThat(db.walletAccountDao().forProfile(profileB)).hasSize(1)
        assertThat(db.walletNetworkDao().forProfile(profileB)).isNotEmpty()
        assertThat(db.dappPermissionDao().allForProfile(profileB)).hasSize(1)
        assertThat(db.walletActivityDao().observeForProfile(profileB).first()).hasSize(1)
        assertThat(repo.accounts(b)).hasSize(1)

        // Only A's key was destroyed — B's blob still decrypts under B's.
        val encB = db.walletDao().byProfile(profileB)?.mnemonicEnc
            ?: error("B's wallet row must survive the cascade")
        assertThat(WalletKeyCrypto.decrypt(b, encB)).isEqualTo(mnemonicB)
    }
}
