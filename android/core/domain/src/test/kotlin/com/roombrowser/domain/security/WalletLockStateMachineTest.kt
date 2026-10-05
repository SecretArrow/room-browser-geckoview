package com.roombrowser.domain.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WalletLockStateMachineTest {

    private val t0 = 1_700_000_000_000L

    private fun machine(failed: Int, lastFailureAtMs: Long?, now: Long = t0) =
        WalletLockStateMachine(failed, lastFailureAtMs) { now }

    @Test
    fun `below the threshold there is no delay`() {
        assertThat(machine(0, null).status()).isEqualTo(WalletLockStatus.Ready)
        assertThat(machine(1, t0).status()).isEqualTo(WalletLockStatus.Ready)
        assertThat(machine(PinAttemptPolicy.THRESHOLD - 1, t0).status())
            .isEqualTo(WalletLockStatus.Ready)
    }

    @Test
    fun `reaching the threshold starts a backoff`() {
        val status = machine(PinAttemptPolicy.THRESHOLD, t0).status()
        assertThat(status).isInstanceOf(WalletLockStatus.Backoff::class.java)
        status as WalletLockStatus.Backoff
        assertThat(status.remainingMs).isEqualTo(PinAttemptPolicy.BASE_DELAY_MS)
        assertThat(status.untilMs).isEqualTo(t0 + PinAttemptPolicy.BASE_DELAY_MS)
    }

    @Test
    fun `the backoff expires with the clock`() {
        val window = PinAttemptPolicy.delayFor(PinAttemptPolicy.THRESHOLD)
        val m = machine(PinAttemptPolicy.THRESHOLD, t0, now = t0 + window)
        assertThat(m.status()).isEqualTo(WalletLockStatus.Ready)

        val justBefore = machine(PinAttemptPolicy.THRESHOLD, t0, now = t0 + window - 1)
        assertThat(justBefore.status()).isInstanceOf(WalletLockStatus.Backoff::class.java)
    }

    @Test
    fun `each further failure doubles the window and caps it`() {
        val delays = (0..10).map { PinAttemptPolicy.delayFor(PinAttemptPolicy.THRESHOLD + it) }
        // 30s, 60s, 120s, …
        assertThat(delays.first()).isEqualTo(30_000L)
        assertThat(delays[1]).isEqualTo(60_000L)
        assertThat(delays[2]).isEqualTo(120_000L)
        assertThat(delays.all { it <= PinAttemptPolicy.MAX_DELAY_MS }).isTrue()
        assertThat(delays.last()).isEqualTo(PinAttemptPolicy.MAX_DELAY_MS)
    }

    @Test
    fun `a backoff never outlives its own cap`() {
        val m = machine(1_000, t0)
        val status = m.status() as WalletLockStatus.Backoff
        assertThat(status.remainingMs).isEqualTo(PinAttemptPolicy.MAX_DELAY_MS)
    }

    @Test
    fun `a wrong attempt increments the counter and records the time`() {
        val next = machine(2, t0, now = t0 + 5_000).afterAttempt(correct = false, now = t0 + 5_000)
        assertThat(next.failedAttempts).isEqualTo(3)
        assertThat(next.lastFailureAtMs).isEqualTo(t0 + 5_000)
    }

    @Test
    fun `a correct attempt clears the counter`() {
        val next = machine(9, t0).afterAttempt(correct = true)
        assertThat(next.failedAttempts).isEqualTo(0)
        assertThat(next.lastFailureAtMs).isNull()
        assertThat(next.status()).isEqualTo(WalletLockStatus.Ready)
    }

    @Test
    fun `attemptsUntilBackoff counts down and floors at zero`() {
        assertThat(PinAttemptPolicy.attemptsUntilBackoff(0)).isEqualTo(5)
        assertThat(PinAttemptPolicy.attemptsUntilBackoff(4)).isEqualTo(1)
        assertThat(PinAttemptPolicy.attemptsUntilBackoff(5)).isEqualTo(0)
        assertThat(PinAttemptPolicy.attemptsUntilBackoff(50)).isEqualTo(0)
    }

    @Test
    fun `a missing failure timestamp fails open to ready`() {
        // A record with a counter but no timestamp can only come from a
        // corrupted row; refusing every attempt forever would be a lockout.
        assertThat(machine(PinAttemptPolicy.THRESHOLD, null).status())
            .isEqualTo(WalletLockStatus.Ready)
    }

    @Test
    fun `policy always offers the device credential as recovery`() {
        assertThat(WalletLockPolicy(pinConfigured = false).pinEnabled).isFalse()
        assertThat(WalletLockPolicy(pinConfigured = true).pinEnabled).isTrue()
    }
}
