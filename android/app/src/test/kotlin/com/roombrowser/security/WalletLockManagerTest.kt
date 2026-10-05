package com.roombrowser.security

import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.repo.WalletLockRecord
import com.roombrowser.data.repo.WalletLockStore
import com.roombrowser.domain.security.PinAttemptPolicy
import com.roombrowser.domain.security.WalletLockStatus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The persistence boundary: proves the manager advances and persists the retry
 * counter, and that the PIN itself never reaches the stored record. The crypto
 * and the backoff curve have their own tests in `:core:domain`.
 */
class WalletLockManagerTest {

    private class FakeStore : WalletLockStore {
        val records = mutableMapOf<String, WalletLockRecord>()

        override suspend fun load(profileId: String): WalletLockRecord? = records[profileId]

        override suspend fun save(profileId: String, record: WalletLockRecord) {
            records[profileId] = record
        }

        override suspend fun clear(profileId: String) {
            records.remove(profileId)
        }
    }

    private val profile = "profile-a"
    private val store = FakeStore()
    private var now = 1_700_000_000_000L

    private fun TestScope.manager() = WalletLockManager(
        store = store,
        clock = { now },
        iterations = 1_000,
        cryptoDispatcher = UnconfinedTestDispatcher(testScheduler)
    )

    private fun wrong(m: WalletLockManager) = m.verifyPin(profile, "000000".toCharArray())

    @Test
    fun `a correct pin unlocks`() = runTest {
        val m = manager()
        m.setPin(profile, "123456".toCharArray())
        assertThat(m.policy(profile).pinEnabled).isTrue()
        assertThat(m.verifyPin(profile, "123456".toCharArray())).isEqualTo(PinUnlockResult.Unlocked)
    }

    @Test
    fun `a wrong pin is refused and advances the counter`() = runTest {
        val m = manager()
        m.setPin(profile, "123456".toCharArray())
        val result = wrong(m)
        assertThat(result).isInstanceOf(PinUnlockResult.Wrong::class.java)
        assertThat((result as PinUnlockResult.Wrong).attemptsUntilBackoff).isEqualTo(4)
        assertThat(store.records.getValue(profile).failedAttempts).isEqualTo(1)
    }

    @Test
    fun `reaching the threshold starts a backoff and refuses further attempts`() = runTest {
        val m = manager()
        m.setPin(profile, "123456".toCharArray())
        repeat(PinAttemptPolicy.THRESHOLD - 1) { wrong(m) }

        val threshold = wrong(m)
        assertThat(threshold).isInstanceOf(PinUnlockResult.Wrong::class.java)
        assertThat((threshold as PinUnlockResult.Wrong).status)
            .isInstanceOf(WalletLockStatus.Backoff::class.java)

        // The next attempt is refused BEFORE verification — even the right PIN.
        val refused = m.verifyPin(profile, "123456".toCharArray())
        assertThat(refused).isInstanceOf(PinUnlockResult.Backoff::class.java)
        assertThat(store.records.getValue(profile).failedAttempts).isEqualTo(PinAttemptPolicy.THRESHOLD)
    }

    @Test
    fun `the backoff survives a new manager over the same store and then expires`() = runTest {
        val m = manager()
        m.setPin(profile, "123456".toCharArray())
        repeat(PinAttemptPolicy.THRESHOLD) { wrong(m) }

        // Process death = a fresh manager; the counter is in the store, not in
        // the object, so the window must still hold.
        assertThat(m.status(profile)).isInstanceOf(WalletLockStatus.Backoff::class.java)

        now += PinAttemptPolicy.BASE_DELAY_MS + 1
        assertThat(manager().status(profile)).isEqualTo(WalletLockStatus.Ready)
        assertThat(manager().verifyPin(profile, "123456".toCharArray()))
            .isEqualTo(PinUnlockResult.Unlocked)
        assertThat(store.records.getValue(profile).failedAttempts).isEqualTo(0)
    }

    @Test
    fun `stored material never contains the pin`() = runTest {
        val m = manager()
        m.setPin(profile, "778899".toCharArray())
        val record = store.records.getValue(profile)

        assertThat(record.pinConfigured).isTrue()
        assertThat(record.pinSaltB64).doesNotContain("778899")
        assertThat(record.pinVerifierB64).doesNotContain("778899")
        assertThat(record.pinVerifierB64).isNotEmpty()
    }

    @Test
    fun `removing the pin returns the profile to device unlock only`() = runTest {
        val m = manager()
        m.setPin(profile, "123456".toCharArray())
        m.removePin(profile)
        assertThat(m.policy(profile).pinEnabled).isFalse()
        assertThat(m.verifyPin(profile, "123456".toCharArray())).isEqualTo(PinUnlockResult.NoPin)
    }

    @Test
    fun `clearFailures resets a backoff`() = runTest {
        val m = manager()
        m.setPin(profile, "123456".toCharArray())
        repeat(PinAttemptPolicy.THRESHOLD) { wrong(m) }
        m.clearFailures(profile)
        assertThat(m.status(profile)).isEqualTo(WalletLockStatus.Ready)
        assertThat(m.verifyPin(profile, "123456".toCharArray()))
            .isEqualTo(PinUnlockResult.Unlocked)
    }
}
