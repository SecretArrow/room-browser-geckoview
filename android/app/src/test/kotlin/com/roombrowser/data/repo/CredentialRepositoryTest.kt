package com.roombrowser.data.repo

import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.db.CredentialDao
import com.roombrowser.data.db.CredentialEntity
import com.roombrowser.domain.credentials.SavedCredential
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.security.VaultCryptor
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * JVM tests of the credential repository against a mockk-backed in-memory
 * DAO and a fake cryptor — no Room, no Android Keystore. What is under test
 * is the repository's OWN behaviour: lock policy, per-profile isolation,
 * canonicalization, re-encryption on import, and ciphertext-only persistence.
 */
class CredentialRepositoryTest {

    private val profileA = ProfileId("11111111-1111-1111-1111-111111111111")
    private val profileB = ProfileId("22222222-2222-2222-2222-222222222222")

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

    /** In-memory CredentialDao on a relaxed mockk — answers backed by a map. */
    private class FakeDao {
        val rows = linkedMapOf<String, CredentialEntity>()
        val dao = mockk<CredentialDao>(relaxed = true).apply {
            coEvery { upsert(any()) } answers {
                val e = firstArg<CredentialEntity>()
                rows[e.id] = e
                Unit
            }
            coEvery { byId(any()) } answers { rows[firstArg<String>()] }
            coEvery { allForProfile(any()) } answers {
                rows.values.filter { it.profileId == firstArg<String>() }
            }
            coEvery { search(any(), any()) } answers {
                val pid = firstArg<String>()
                val q = secondArg<String>().lowercase()
                rows.values.filter {
                    it.profileId == pid &&
                        (it.domain.lowercase().contains(q) ||
                            it.username.lowercase().contains(q) ||
                            it.title?.lowercase()?.contains(q) == true)
                }
            }
            coEvery { delete(any()) } answers {
                rows.remove(firstArg<String>())
                Unit
            }
            every { observe(any()) } answers {
                val pid = firstArg<String>()
                flowOf(
                    rows.values
                        .filter { it.profileId == pid }
                        .sortedWith(compareBy({ it.domain.lowercase() }, { it.username }))
                )
            }
        }
    }

    private lateinit var fakeDao: FakeDao
    private lateinit var cryptor: FakeCryptor
    private lateinit var repo: CredentialRepository

    @Before
    fun setUp() {
        fakeDao = FakeDao()
        cryptor = FakeCryptor()
        repo = CredentialRepository(fakeDao.dao, cryptor)
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
    fun `vault starts locked and every api rejects the call`() = runTest {
        assertThat(repo.isUnlocked.value).isFalse()

        assertThrowsSuspend<VaultLockedException> {
            repo.save(profileA, "example.com", "u", "p")
        }
        assertThrowsSuspend<VaultLockedException> { repo.observe(profileA) }
        assertThrowsSuspend<VaultLockedException> { repo.get(profileA, "id") }
        assertThrowsSuspend<VaultLockedException> { repo.search(profileA, "q") }
        assertThrowsSuspend<VaultLockedException> { repo.delete(profileA, "id") }
        assertThrowsSuspend<VaultLockedException> { repo.findForDomain(profileA, "example.com") }
        assertThrowsSuspend<VaultLockedException> { repo.exportAll(profileA) }
        assertThrowsSuspend<VaultLockedException> {
            repo.importAll(profileA, listOf(savedCredential()))
        }

        // Nothing leaked through: the lock rejected before any DAO/crypto use.
        assertThat(fakeDao.rows).isEmpty()
        assertThat(cryptor.encryptions).isEmpty()
    }

    @Test
    fun `save then observe roundtrips and the row stores only ciphertext`() = runTest {
        repo.unlock()
        assertThat(repo.isUnlocked.value).isTrue()

        val saved = repo.save(
            profileA,
            "https://Accounts.Google.com/signin", // scheme + case + path on purpose
            "user@gmail.com",
            "hunter2",
            "Google"
        )

        // Domain is canonicalized on the way in.
        assertThat(saved.domain).isEqualTo("accounts.google.com")
        assertThat(saved.password).isEqualTo("hunter2")
        assertThat(saved.profileId).isEqualTo(profileA.value)
        assertThat(saved.id).isNotEmpty()

        // The database row holds the cryptor's blob, never the plaintext.
        val row = fakeDao.rows.values.single()
        assertThat(row.passwordEnc).isEqualTo("enc:${profileA.safeSuffix}:hunter2")
        assertThat(row.profileId).isEqualTo(profileA.value)

        // observe() hands back decrypted credentials.
        val observed = repo.observe(profileA).first()
        assertThat(observed).containsExactly(saved)
    }

    @Test
    fun `save with id edits the row and keeps createdAt`() = runTest {
        repo.unlock()
        val first = repo.save(profileA, "a.com", "u", "old-secret")

        val edited = repo.save(profileA, "a.com", "u2", "new-secret", "label", id = first.id)

        assertThat(edited.id).isEqualTo(first.id)
        assertThat(edited.createdAt).isEqualTo(first.createdAt) // preserved, not reset
        assertThat(edited.updatedAt).isAtLeast(first.updatedAt) // bumped
        assertThat(fakeDao.rows).hasSize(1) // upsert, not a second row
        assertThat(fakeDao.rows.values.single().passwordEnc)
            .isEqualTo("enc:${profileA.safeSuffix}:new-secret")
        assertThat(fakeDao.rows.values.single().username).isEqualTo("u2")
    }

    @Test
    fun `save refuses to edit another profile's row id`() = runTest {
        repo.unlock()
        val theirs = repo.save(profileB, "b.com", "u", "p")

        assertThrowsSuspend<IllegalArgumentException> {
            repo.save(profileA, "a.com", "u", "p", id = theirs.id)
        }

        // The other profile's row is untouched.
        assertThat(fakeDao.rows.values.single().profileId).isEqualTo(profileB.value)
    }

    @Test
    fun `save rejects a blank domain`() = runTest {
        repo.unlock()
        assertThrowsSuspend<IllegalArgumentException> {
            repo.save(profileA, "  https://  ", "u", "p")
        }
        assertThat(fakeDao.rows).isEmpty()
    }

    @Test
    fun `search matches domain username and title`() = runTest {
        repo.unlock()
        repo.save(profileA, "github.com", "octocat@example.com", "p1", "Work account")
        repo.save(profileA, "gitlab.com", "someone", "p2")
        repo.save(profileB, "github.com", "other-profile-user", "p3") // different vault

        assertThat(repo.search(profileA, "OCTO").map { it.username })
            .containsExactly("octocat@example.com")
        assertThat(repo.search(profileA, "gitlab").map { it.password }).containsExactly("p2")
        assertThat(repo.search(profileA, "work").map { it.title }).containsExactly("Work account")
        assertThat(repo.search(profileA, "").map { it.username })
            .containsExactly("octocat@example.com", "someone") // blank query = all of A
    }

    @Test
    fun `findForDomain returns parent exact and subdomain matches for this profile only`() = runTest {
        repo.unlock()
        repo.save(profileA, "google.com", "parent-user", "p1")
        repo.save(profileA, "accounts.google.com", "exact-user", "p2")
        repo.save(profileA, "mail.google.com", "sibling-user", "p3")
        repo.save(profileA, "notevil.com", "suffix-user", "p4")
        repo.save(profileB, "google.com", "other-profile", "p5")

        val found = repo.findForDomain(profileA, "accounts.google.com")

        // Parent-domain sharing both ways; sibling subdomains and
        // suffix-lookalikes do NOT match; profile B's login never surfaces.
        assertThat(found.map { it.username }).containsExactly("parent-user", "exact-user")
        assertThat(found.map { it.password }).containsExactly("p1", "p2").inOrder()
    }

    @Test
    fun `importAll re-encrypts under the target profile key with fresh ids`() = runTest {
        val source = listOf(
            savedCredential(
                id = "same-source-id", domain = "Example.COM.",
                username = "alice", password = "secret-1", createdAt = 10L
            ),
            savedCredential(
                id = "same-source-id", domain = "example.org",
                username = "bob", password = "secret-2", createdAt = 11L
            )
        )

        repo.unlock()
        val written = repo.importAll(profileA, source)

        val rows = fakeDao.rows.values.toList()
        assertThat(rows).hasSize(2)
        assertThat(written).isEqualTo(2)
        // Fresh UUIDs: neither the shared source id nor each other's.
        assertThat(rows.map { it.id }).containsNoneIn(listOf("same-source-id"))
        assertThat(rows.map { it.id }.toSet()).hasSize(2)
        // Re-encrypted under the TARGET profile's key, ciphertext only.
        assertThat(rows.map { it.profileId }).containsExactly(profileA.value, profileA.value)
        assertThat(rows.map { it.passwordEnc }).containsExactly(
            "enc:${profileA.safeSuffix}:secret-1",
            "enc:${profileA.safeSuffix}:secret-2"
        )
        // Source timestamps preserved, domains canonicalized.
        assertThat(rows.map { it.domain }).containsExactly("example.com", "example.org")
        assertThat(rows.map { it.createdAt }).containsExactly(10L, 11L)
        assertThat(cryptor.encryptions.map { it.first }.toSet())
            .containsExactly(profileA.safeSuffix)
    }

    @Test
    fun `importAll skips rows with unusable domains instead of aborting the batch`() = runTest {
        repo.unlock()
        val written = repo.importAll(
            profileA,
            listOf(
                savedCredential(id = "bad", domain = "   ", password = "x"),
                savedCredential(id = "good", domain = "good.com", password = "y")
            )
        )
        assertThat(fakeDao.rows.values.map { it.domain }).containsExactly("good.com")
        // The skipped row is not counted, so the import report can never
        // promise a login the device does not hold.
        assertThat(written).isEqualTo(1)
    }

    @Test
    fun `delete removes only this profile's row`() = runTest {
        repo.unlock()
        val mine = repo.save(profileA, "a.com", "u", "p")
        val theirs = repo.save(profileB, "b.com", "u", "p")

        // Wrong profile: silent no-op, never a cross-profile delete.
        repo.delete(profileB, mine.id)
        assertThat(fakeDao.rows).hasSize(2)

        repo.delete(profileA, mine.id)
        assertThat(fakeDao.rows.values.map { it.id }).containsExactly(theirs.id)
    }

    @Test
    fun `get never returns another profile's credential`() = runTest {
        repo.unlock()
        val theirs = repo.save(profileB, "b.com", "u", "their-secret")

        assertThat(repo.get(profileB, theirs.id)?.password).isEqualTo("their-secret")
        assertThat(repo.get(profileA, theirs.id)).isNull()
        assertThat(repo.get(profileA, "missing")).isNull()
    }

    @Test
    fun `exportAll returns every decrypted credential of the profile`() = runTest {
        repo.unlock()
        repo.save(profileA, "a.com", "u1", "p1")
        repo.save(profileA, "b.com", "u2", "p2")
        repo.save(profileB, "c.com", "u3", "p3")

        val exported = repo.exportAll(profileA)
        assertThat(exported.map { it.password }).containsExactly("p1", "p2")
    }

    @Test
    fun `locking mid-session fails an observed flow closed`() = runTest {
        repo.unlock()
        repo.save(profileA, "a.com", "u", "p")

        val flow = repo.observe(profileA)
        repo.lock() // session ends while a collector still holds the flow

        assertThrowsSuspend<VaultLockedException> { flow.first() }
    }

    // ---------- Auto-lock timeout ----------
    // The watchdog is an ordinary coroutine delay, so these run on the test
    // scheduler's VIRTUAL clock (testScheduler.advanceTimeBy) — no real
    // sleeping, no flake. Production uses the 5-minute default scope.

    private fun TestScope.timedRepo(timeoutMs: Long) = CredentialRepository(
        fakeDao.dao,
        cryptor,
        autoLockTimeoutMs = timeoutMs,
        autoLockScope = backgroundScope
    )

    @Test
    fun `vault re-locks itself once the unlock window passes`() = runTest {
        val timed = timedRepo(60_000L)
        timed.unlock()
        testScheduler.runCurrent() // let the watchdog reach its delay
        assertThat(timed.isUnlocked.value).isTrue()

        // One millisecond short of the window: still open.
        testScheduler.advanceTimeBy(59_999L)
        testScheduler.runCurrent()
        assertThat(timed.isUnlocked.value).isTrue()

        // Past the window: re-locked, and the lock is REAL — the API rejects.
        testScheduler.advanceTimeBy(1L)
        testScheduler.runCurrent()
        assertThat(timed.isUnlocked.value).isFalse()
        assertThrowsSuspend<VaultLockedException> { timed.get(profileA, "id") }
    }

    @Test
    fun `a second unlock restarts the window and an explicit lock ends it`() = runTest {
        val timed = timedRepo(60_000L)
        timed.unlock()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(30_000L)
        testScheduler.runCurrent()

        // Re-unlocking must cancel the FIRST watchdog: 90s after the first
        // unlock is 60s after the second, and the vault must still be open.
        timed.unlock()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(59_000L)
        testScheduler.runCurrent()
        assertThat(timed.isUnlocked.value).isTrue()

        // An explicit lock cancels the pending watchdog outright.
        timed.lock()
        assertThat(timed.isUnlocked.value).isFalse()
        testScheduler.advanceTimeBy(120_000L)
        testScheduler.runCurrent()
        assertThat(timed.isUnlocked.value).isFalse()
    }

    @Test
    fun `a non-positive timeout keeps the vault open until it is locked`() = runTest {
        val untimed = timedRepo(0L)
        untimed.unlock()
        testScheduler.runCurrent()

        // A day of virtual time must not lock a vault with the timeout off.
        testScheduler.advanceTimeBy(24L * 60L * 60L * 1000L)
        testScheduler.runCurrent()
        assertThat(untimed.isUnlocked.value).isTrue()

        untimed.lock()
        assertThat(untimed.isUnlocked.value).isFalse()
    }

    @Test
    fun `the default unlock window is five minutes`() {
        // Pins the documented policy: the constant is part of the contract.
        assertThat(DEFAULT_AUTO_LOCK_TIMEOUT_MS).isEqualTo(300_000L)
    }

    @Test
    fun `lock and unlock gate the same session`() = runTest {
        repo.unlock()
        val saved = repo.save(profileA, "a.com", "u", "p")
        repo.lock()
        assertThrowsSuspend<VaultLockedException> { repo.get(profileA, saved.id) }
        repo.unlock()
        assertThat(repo.get(profileA, saved.id)?.password).isEqualTo("p")
    }

    private fun savedCredential(
        id: String = "src-id",
        profileId: String = profileB.value,
        domain: String = "example.com",
        username: String = "user",
        password: String = "pw",
        createdAt: Long = 10L
    ) = SavedCredential(
        id = id,
        profileId = profileId,
        domain = domain,
        username = username,
        password = password,
        title = null,
        createdAt = createdAt,
        updatedAt = 20
    )
}
