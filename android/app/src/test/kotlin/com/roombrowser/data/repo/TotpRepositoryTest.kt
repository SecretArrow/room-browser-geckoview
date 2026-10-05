package com.roombrowser.data.repo

import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.db.TotpDao
import com.roombrowser.data.db.TotpEntity
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.totp.Base32
import com.roombrowser.domain.totp.TotpAlgorithm
import com.roombrowser.domain.totp.TotpBackup
import com.roombrowser.domain.totp.TotpEntry
import com.roombrowser.security.VaultCryptor
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * JVM tests of the 2FA repository against a mockk-backed in-memory DAO and a
 * fake cryptor — no Room, no Android Keystore. What is under test is the
 * repository's OWN behaviour: lock policy, per-profile isolation, ciphertext-
 * only persistence, re-keying on import, and the deliberate asymmetry that
 * [TotpRepository.codeForAgent] bypasses the lock while every screen-facing
 * method still fails closed.
 */
class TotpRepositoryTest {

    private val profileA = ProfileId("11111111-1111-1111-1111-111111111111")
    private val profileB = ProfileId("22222222-2222-2222-2222-222222222222")

    /** RFC 6238 Appendix B key: the ASCII bytes of "12345678901234567890". */
    private val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    /**
     * The Appendix B key for SHA-256, 32 bytes rather than SHA-1's 20 — the RFC
     * gives each algorithm its own key length, so the 20-byte one above cannot
     * produce the SHA-256 vector.
     */
    private val sha256Secret = Base32.encode("12345678901234567890123456789012".toByteArray())

    /**
     * Deterministic cryptor double: "enc:<key>:<plaintext>". Decrypt only opens
     * blobs sealed under the SAME key — a wrong profile key fails the way the
     * Keystore one would (tag mismatch), which is what the isolation assertions
     * rely on.
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

    /** In-memory TotpDao on a relaxed mockk — answers backed by a map. */
    private class FakeDao {
        val rows = linkedMapOf<String, TotpEntity>()
        val dao = mockk<TotpDao>(relaxed = true).apply {
            coEvery { upsert(any()) } answers {
                val e = firstArg<TotpEntity>()
                rows[e.id] = e
                Unit
            }
            coEvery { byId(any()) } answers { rows[firstArg<String>()] }
            coEvery { allForProfile(any()) } answers {
                rows.values.filter { it.profileId == firstArg<String>() }
            }
            coEvery { delete(any()) } answers {
                rows.remove(firstArg<String>())
                Unit
            }
            coEvery { markUsed(any(), any()) } answers {
                val id = firstArg<String>()
                val at = secondArg<Long>()
                rows[id]?.let { rows[id] = it.copy(lastUsedAt = at) }
                Unit
            }
            coEvery { deleteAllForProfile(any()) } answers {
                val pid = firstArg<String>()
                rows.entries.removeAll { it.value.profileId == pid }
                Unit
            }
            every { observe(any()) } answers {
                val pid = firstArg<String>()
                flowOf(
                    rows.values
                        .filter { it.profileId == pid }
                        .sortedWith(
                            compareByDescending<TotpEntity> { it.lastUsedAt ?: Long.MIN_VALUE }
                                .thenBy { it.issuer }
                        )
                )
            }
        }
    }

    private lateinit var fakeDao: FakeDao
    private lateinit var cryptor: FakeCryptor
    private lateinit var repo: TotpRepository

    @Before
    fun setUp() {
        fakeDao = FakeDao()
        cryptor = FakeCryptor()
        // Auto-lock off by default so a test only sees it when it asks for it.
        repo = TotpRepository(fakeDao.dao, cryptor, autoLockTimeoutMs = 0L)
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

    private suspend fun add(
        profileId: ProfileId = profileA,
        issuer: String = "Acme",
        account: String = "owner@acme.test",
        id: String? = null
    ): TotpEntry = repo.save(
        profileId = profileId,
        issuer = issuer,
        account = account,
        secret = secret,
        id = id
    )

    @Test
    fun `2FA starts locked and every screen-facing api rejects the call`() = runTest {
        assertThat(repo.isUnlocked.value).isFalse()

        assertThrowsSuspend<VaultLockedException> { add() }
        assertThrowsSuspend<VaultLockedException> { repo.observe(profileA) }
        assertThrowsSuspend<VaultLockedException> { repo.get(profileA, "missing") }
        assertThrowsSuspend<VaultLockedException> { repo.delete(profileA, "missing") }
        assertThrowsSuspend<VaultLockedException> { repo.exportAll(profileA) }
        assertThrowsSuspend<VaultLockedException> { repo.importAll(profileA, emptyList()) }

        assertThat(fakeDao.rows).isEmpty()
        assertThat(cryptor.encryptions).isEmpty()
    }

    @Test
    fun `saving stores ciphertext only and hands back the plaintext entry`() = runTest {
        repo.unlock()

        val saved = add()

        assertThat(saved.secret).isEqualTo(secret)
        assertThat(saved.profileId).isEqualTo(profileA.value)
        assertThat(saved.algorithm).isEqualTo(TotpAlgorithm.SHA1)
        assertThat(saved.digits).isEqualTo(6)
        assertThat(saved.period).isEqualTo(30)

        val row = fakeDao.rows.getValue(saved.id)
        assertThat(row.secretEnc).isEqualTo("enc:${profileA.safeSuffix}:$secret")
        assertThat(row.secretEnc).doesNotContain(" ")
        assertThat(row.issuer).isEqualTo("Acme")
        assertThat(row.lastUsedAt).isNull()
    }

    @Test
    fun `an edit keeps the row id and its creation and last-used times`() = runTest {
        repo.unlock()
        val first = add()
        repo.markUsed(first.id, at = 5_000L)

        val edited = add(issuer = "Acme Corp", id = first.id)

        assertThat(edited.id).isEqualTo(first.id)
        assertThat(edited.createdAt).isEqualTo(first.createdAt)
        assertThat(edited.lastUsedAt).isEqualTo(5_000L)
        assertThat(fakeDao.rows).hasSize(1)
        assertThat(fakeDao.rows.getValue(first.id).issuer).isEqualTo("Acme Corp")
    }

    @Test
    fun `save validates its input instead of writing a row it cannot use`() = runTest {
        repo.unlock()

        assertThrowsSuspend<IllegalArgumentException> { repo.save(profileA, "A", "a", "   ") }
        assertThrowsSuspend<IllegalArgumentException> {
            repo.save(profileA, "A", "a", secret, digits = 7)
        }
        assertThrowsSuspend<IllegalArgumentException> {
            repo.save(profileA, "A", "a", secret, period = 0)
        }
        assertThat(fakeDao.rows).isEmpty()
    }

    @Test
    fun `observe decrypts this profile's rows most-recently-used first`() = runTest {
        repo.unlock()
        val old = add(issuer = "Zeta")
        val fresh = add(issuer = "Acme")
        repo.markUsed(fresh.id, at = 1_000L)

        val seen = repo.observe(profileA).first()

        assertThat(seen.map { it.id }).containsExactly(fresh.id, old.id).inOrder()
        assertThat(seen.map { it.secret }).containsExactly(secret, secret)
    }

    @Test
    fun `observe fails closed when the lock lands mid-collection`() = runTest {
        repo.unlock()
        add()

        val flow = repo.observe(profileA)
        repo.lock()

        assertThrowsSuspend<VaultLockedException> { flow.first() }
    }

    @Test
    fun `a locked 2FA hides rows even though they are already decrypted once`() = runTest {
        repo.unlock()
        val entry = add()
        assertThat(repo.get(profileA, entry.id)).isNotNull()

        repo.lock()

        assertThrowsSuspend<VaultLockedException> { repo.get(profileA, entry.id) }
    }

    @Test
    fun `another profile cannot read or delete this profile's row`() = runTest {
        repo.unlock()
        val mine = add(profileId = profileA)
        repo.unlock()

        assertThat(repo.get(profileB, mine.id)).isNull()
        assertThat(repo.observe(profileB).first()).isEmpty()

        repo.delete(profileB, mine.id)
        assertThat(fakeDao.rows).containsKey(mine.id)
    }

    @Test
    fun `editing another profile's row is refused rather than hijacked`() = runTest {
        repo.unlock()
        val mine = add(profileId = profileA)

        assertThrowsSuspend<IllegalArgumentException> {
            add(profileId = profileB, id = mine.id)
        }
        assertThat(fakeDao.rows.getValue(mine.id).profileId).isEqualTo(profileA.value)
    }

    @Test
    fun `import re-keys every secret and reissues every id`() = runTest {
        repo.unlock()
        val original = add(profileId = profileA)
        // The file's shape, not a row: an import only ever consumes TotpBackup.Entry.
        val source = TotpBackup.Entry(
            issuer = "Acme",
            account = "owner@acme.test",
            secret = secret
        )
        repo.lock()

        repo.unlock()
        repo.importAll(profileB, listOf(source), at = 5_000L)

        val imported = fakeDao.rows.values.single { it.profileId == profileB.value }
        assertThat(imported.id).isNotEqualTo(original.id)
        assertThat(imported.secretEnc).isEqualTo("enc:${profileB.safeSuffix}:$secret")
        assertThat(imported.createdAt).isEqualTo(5_000L)
        // last_used_at is deliberately not carried across an import.
        assertThat(imported.lastUsedAt).isNull()
        // The original row is untouched.
        assertThat(fakeDao.rows.getValue(original.id).secretEnc)
            .isEqualTo("enc:${profileA.safeSuffix}:$secret")
    }

    @Test
    fun `export returns the profile's plaintext entries for sealing`() = runTest {
        repo.unlock()
        val mine = add(profileId = profileA)
        val second = add(profileId = profileA, issuer = "Other")
        repo.unlock()
        val theirs = add(profileId = profileB, issuer = "Theirs")

        val exported = repo.exportAll(profileA)

        assertThat(exported.map { it.id }).containsExactly(mine.id, second.id)
        assertThat(exported.map { it.issuer }).containsExactly("Acme", "Other")
        assertThat(exported.map { it.secret }).containsExactly(secret, secret)
        assertThat(exported.map { it.id }).doesNotContain(theirs.id)
    }

    @Test
    fun `markUsed reorders without needing an unlock`() = runTest {
        repo.unlock()
        val entry = add()

        repo.markUsed(entry.id, at = 9_000L)

        assertThat(fakeDao.rows.getValue(entry.id).lastUsedAt).isEqualTo(9_000L)
    }

    @Test
    fun `the auto-lock window re-locks and stops the screen apis`() = runTest {
        val timed = TotpRepository(
            fakeDao.dao,
            cryptor,
            autoLockTimeoutMs = 1_000L,
            autoLockScope = backgroundScope
        )
        timed.unlock()
        assertThat(timed.isUnlocked.value).isTrue()

        advanceTimeBy(999L)
        assertThat(timed.isUnlocked.value).isTrue()

        advanceTimeBy(2L)
        assertThat(timed.isUnlocked.value).isFalse()
    }

    @Test
    fun `unlocking again restarts the window instead of leaving a stale timer`() = runTest {
        val timed = TotpRepository(
            fakeDao.dao,
            cryptor,
            autoLockTimeoutMs = 1_000L,
            autoLockScope = backgroundScope
        )
        timed.unlock()
        advanceTimeBy(900L)
        timed.unlock()
        advanceTimeBy(900L)

        assertThat(timed.isUnlocked.value).isTrue()

        advanceTimeBy(200L)
        assertThat(timed.isUnlocked.value).isFalse()
    }

    // ---- the agent's door ----

    @Test
    fun `codeForAgent works while locked and returns the RFC vector`() = runTest {
        repo.unlock()
        val entry = add()
        repo.lock()

        // RFC 6238 Appendix B: T = 59 s, SHA1, 6 digits -> 287082.
        val code = repo.codeForAgent(profileA, entry.id, timeMillis = 59_000L)

        assertThat(code).isEqualTo("287082")
        // The screen-facing door stayed shut throughout.
        assertThat(repo.isUnlocked.value).isFalse()
        assertThrowsSuspend<VaultLockedException> { repo.get(profileA, entry.id) }
    }

    @Test
    fun `codeForAgent honours the stored algorithm digits and period`() = runTest {
        repo.unlock()
        val eight = repo.save(
            profileA, "Acme", "a", sha256Secret, algorithm = TotpAlgorithm.SHA256,
            digits = 8, period = 30
        )
        repo.lock()

        // RFC 6238 Appendix B: T = 59 s, SHA256, 8 digits -> 46119246.
        assertThat(repo.codeForAgent(profileA, eight.id, timeMillis = 59_000L))
            .isEqualTo("46119246")
    }

    @Test
    fun `codeForAgent answers null for an unknown id or another profile's row`() = runTest {
        repo.unlock()
        val mine = add(profileId = profileA)
        repo.lock()

        assertThat(repo.codeForAgent(profileA, "no-such-id")).isNull()
        assertThat(repo.codeForAgent(profileB, mine.id)).isNull()
    }

    @Test
    fun `labelsForAgent lists this profile only and never carries a code`() = runTest {
        repo.unlock()
        add(profileId = profileA, issuer = "Acme", account = "owner@acme.test")
        repo.lock()

        val labels = repo.labelsForAgent(profileA)

        assertThat(labels.map { it.issuer }).containsExactly("Acme")
        assertThat(labels.map { it.account }).containsExactly("owner@acme.test")
    }
}
