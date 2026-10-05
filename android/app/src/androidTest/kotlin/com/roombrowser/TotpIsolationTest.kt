package com.roombrowser

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.db.AppDatabase
import com.roombrowser.data.repo.TotpRepository
import com.roombrowser.data.repo.VaultLockedException
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.security.TotpKeyCrypto
import com.roombrowser.security.VaultCryptoException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The authenticator table is profile data like credentials and wallet keys:
 * scoped by profile_id at the DAO and locked, ciphertext-only at the
 * repository. This runs the REAL TotpKeyCrypto over the REAL Room schema, the
 * same harness DatabaseIsolationTest uses for the password and wallet vaults —
 * so no fake cryptor is needed, and the Keystore path is exercised too.
 */
@RunWith(AndroidJUnit4::class)
class TotpIsolationTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TotpRepository

    private val profileA = ProfileId("11111111-1111-1111-1111-111111111111")
    private val profileB = ProfileId("22222222-2222-2222-2222-222222222222")

    /** A well-formed Base32 seed; its plaintext must never reach the column. */
    private val secret = "JBSWY3DPEHPK3PXP"

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // Auto-lock off: each test opens the lock itself and keeps it open.
        repo = TotpRepository(db.totpDao(), TotpKeyCrypto, autoLockTimeoutMs = 0L)
    }

    @After
    fun tearDown() {
        db.close()
        TotpKeyCrypto.deleteKey(profileA)
        TotpKeyCrypto.deleteKey(profileB)
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

    @Test
    fun totp_rows_are_scoped_per_profile() = runBlocking<Unit> {
        repo.unlock()
        val a = repo.save(profileA, "Acme", "owner@acme.test", secret)
        val b = repo.save(profileB, "Beta", "owner@beta.test", secret)

        // DAO scoping: each profile's scans see only its own row.
        assertThat(db.totpDao().allForProfile(profileA.value).map { it.id })
            .containsExactly(a.id)
        assertThat(db.totpDao().allForProfile(profileB.value).map { it.id })
            .containsExactly(b.id)
        assertThat(db.totpDao().observe(profileA.value).first().map { it.id })
            .containsExactly(a.id)
        assertThat(db.totpDao().observe(profileB.value).first().map { it.id })
            .containsExactly(b.id)
        assertThat(db.totpDao().countForProfile(profileA.value)).isEqualTo(1)
        assertThat(db.totpDao().countForProfile(profileB.value)).isEqualTo(1)
        // byId is NOT profile-scoped — the repository is where that boundary
        // lives, so the raw row resolves while B's repository view does not.
        assertThat(db.totpDao().byId(a.id)!!.profileId).isEqualTo(profileA.value)

        // Repository scoping across every screen-facing read.
        assertThat(repo.get(profileB, a.id)).isNull()
        assertThat(repo.observe(profileB).first().map { it.id }).containsExactly(b.id)
        assertThat(repo.exportAll(profileB).map { it.id }).containsExactly(b.id)
        assertThat(repo.countForProfile(profileA)).isEqualTo(1)
        assertThat(repo.countForProfile(profileB)).isEqualTo(1)
    }

    @Test
    fun another_profile_cannot_read_or_delete_this_profiles_row() = runBlocking<Unit> {
        repo.unlock()
        val mine = repo.save(profileA, "Acme", "owner@acme.test", secret)

        assertThat(repo.get(profileB, mine.id)).isNull()
        assertThat(repo.observe(profileB).first()).isEmpty()
        assertThat(repo.exportAll(profileB)).isEmpty()
        assertThat(repo.countForProfile(profileB)).isEqualTo(0)
        assertThat(db.totpDao().allForProfile(profileB.value)).isEmpty()
        assertThat(db.totpDao().observe(profileB.value).first()).isEmpty()
        assertThat(db.totpDao().countForProfile(profileB.value)).isEqualTo(0)

        // A cross-profile delete is a silent no-op, never a cross-profile delete.
        repo.delete(profileB, mine.id)
        assertThat(db.totpDao().byId(mine.id)).isNotNull()
        assertThat(repo.countForProfile(profileA)).isEqualTo(1)
    }

    @Test
    fun delete_removes_only_this_profiles_row() = runBlocking<Unit> {
        repo.unlock()
        val mine = repo.save(profileA, "Acme", "owner@acme.test", secret)
        val theirs = repo.save(profileB, "Beta", "owner@beta.test", secret)

        repo.delete(profileA, mine.id)

        assertThat(db.totpDao().byId(mine.id)).isNull()
        assertThat(db.totpDao().byId(theirs.id)).isNotNull()
        assertThat(db.totpDao().countForProfile(profileA.value)).isEqualTo(0)
        assertThat(db.totpDao().countForProfile(profileB.value)).isEqualTo(1)
    }

    @Test
    fun stored_secret_is_ciphertext_and_round_trips_through_the_repository() = runBlocking<Unit> {
        repo.unlock()
        val saved = repo.save(profileA, "Acme", "owner@acme.test", secret)

        // The seed is never on disk in any form...
        val row = db.totpDao().byId(saved.id) ?: error("row must exist")
        assertThat(row.secretEnc).isNotEmpty()
        assertThat(row.secretEnc).isNotEqualTo(secret)
        assertThat(row.secretEnc).doesNotContain(secret)
        // ...it is the profile's own 2FA blob, and only that profile's key opens it.
        assertThat(TotpKeyCrypto.decrypt(profileA, row.secretEnc)).isEqualTo(secret)
        assertThrows(VaultCryptoException::class.java) {
            TotpKeyCrypto.decrypt(profileB, row.secretEnc)
        }

        // The repository round-trips to the original seed.
        assertThat(repo.get(profileA, saved.id)?.secret).isEqualTo(secret)
        assertThat(repo.exportAll(profileA).map { it.secret }).containsExactly(secret)
        assertThat(repo.observe(profileA).first().map { it.secret }).containsExactly(secret)
    }

    @Test
    fun every_screen_facing_api_stays_locked_until_an_unlock() = runBlocking<Unit> {
        assertThat(repo.isUnlocked.value).isFalse()

        assertThrowsSuspend<VaultLockedException> {
            repo.save(profileA, "Acme", "owner@acme.test", secret)
        }
        assertThrowsSuspend<VaultLockedException> { repo.observe(profileA) }
        assertThrowsSuspend<VaultLockedException> { repo.get(profileA, "missing") }
        assertThrowsSuspend<VaultLockedException> { repo.delete(profileA, "missing") }
        assertThrowsSuspend<VaultLockedException> { repo.exportAll(profileA) }

        // Nothing was written while locked.
        assertThat(db.totpDao().countForProfile(profileA.value)).isEqualTo(0)
    }
}
