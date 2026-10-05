package com.roombrowser.domain.security

/**
 * Which unlock methods this wallet accepts. The device credential (fingerprint,
 * face, or the system PIN/pattern/password behind androidx.biometric) is ALWAYS
 * available: it is both the pre-existing unlock path and the documented
 * RECOVERY path — losing or forgetting the wallet PIN must never lock the owner
 * out of their own keys, since the keys themselves are sealed under the
 * AndroidKeyStore and only released to this app's uid.
 */
data class WalletLockPolicy(
    val pinConfigured: Boolean = false
) {
    val pinEnabled: Boolean get() = pinConfigured
}

/** Where the locked wallet stands right now. */
sealed interface WalletLockStatus {
    /** An attempt may run now. */
    data object Ready : WalletLockStatus

    /** Too many wrong PINs; attempts are refused until [untilMs]. */
    data class Backoff(val untilMs: Long, val remainingMs: Long) : WalletLockStatus
}

/**
 * The wallet PIN's retry policy. Pure, so the backoff curve is unit-testable.
 *
 * Below [THRESHOLD] wrong attempts there is NO delay — a typo must not cost 30
 * seconds. From the threshold on, each further failure starts a doubling window
 * (30 s, 1 m, 2 m, …) capped at [MAX_DELAY_MS]. The window is a function of
 * (failedAttempts, lastFailureAtMs, now) only, so it survives process death as
 * long as the counter is persisted — which is why the counter lives in the
 * cross-process app_state table, not in an Activity or a ViewModel.
 *
 * There is deliberately no "wipe after N failures": that would hand an attacker
 * who has the phone a way to destroy the owner's keys, and the stored verifier
 * is a one-way hash that gains nothing from being deleted.
 */
object PinAttemptPolicy {
    const val THRESHOLD = 5
    const val BASE_DELAY_MS = 30_000L
    const val MAX_DELAY_MS = 30 * 60_000L

    fun delayFor(failedAttempts: Int): Long {
        if (failedAttempts < THRESHOLD) return 0L
        val steps = (failedAttempts - THRESHOLD).coerceAtMost(20)
        val delay = BASE_DELAY_MS shl steps
        return if (delay <= 0L || delay > MAX_DELAY_MS) MAX_DELAY_MS else delay
    }

    /** Attempts a user has left before the first delay kicks in. */
    fun attemptsUntilBackoff(failedAttempts: Int): Int =
        (THRESHOLD - failedAttempts).coerceAtLeast(0)
}

/**
 * The wallet lock's state machine: a persisted (failedAttempts, lastFailureAtMs)
 * pair plus an injected clock. Everything interesting is pure; the Android layer
 * only stores the counters and renders [status].
 *
 * A correct PIN always wins once [status] is [WalletLockStatus.Ready]: the
 * backoff delays attempts, it never permanently seals the wallet.
 */
class WalletLockStateMachine(
    val failedAttempts: Int,
    val lastFailureAtMs: Long?,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun status(): WalletLockStatus {
        val delay = PinAttemptPolicy.delayFor(failedAttempts)
        if (delay == 0L) return WalletLockStatus.Ready
        val base = lastFailureAtMs ?: return WalletLockStatus.Ready
        val until = base + delay
        val now = clock()
        return if (now >= until) {
            WalletLockStatus.Ready
        } else {
            WalletLockStatus.Backoff(until, until - now)
        }
    }

    /** Applies one verification result and returns the NEXT machine to persist. */
    fun afterAttempt(correct: Boolean, now: Long = clock()): WalletLockStateMachine =
        if (correct) {
            WalletLockStateMachine(0, null, clock)
        } else {
            WalletLockStateMachine(failedAttempts + 1, now, clock)
        }
}
