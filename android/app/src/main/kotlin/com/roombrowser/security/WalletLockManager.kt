package com.roombrowser.security

import com.roombrowser.data.repo.WalletLockRecord
import com.roombrowser.data.repo.WalletLockStore
import com.roombrowser.domain.security.PinAttemptPolicy
import com.roombrowser.domain.security.PinLockCrypto
import com.roombrowser.domain.security.WalletLockPolicy
import com.roombrowser.domain.security.WalletLockStateMachine
import com.roombrowser.domain.security.WalletLockStatus
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Outcome of one PIN attempt, shaped for the UI. */
sealed interface PinUnlockResult {
    data object Unlocked : PinUnlockResult

    /** No PIN is configured for this profile. */
    data object NoPin : PinUnlockResult

    /** Refused before verification — still inside the backoff window. */
    data class Backoff(val status: WalletLockStatus) : PinUnlockResult

    data class Wrong(
        val status: WalletLockStatus,
        val attemptsUntilBackoff: Int
    ) : PinUnlockResult
}

/**
 * The thin Android-side half of the PROFILE lock (shared by the wallet and the
 * 2FA screen): it persists the record via [WalletLockStore] and runs the PBKDF2
 * derivation off the main thread. Every decision (the backoff curve, the
 * verifier check) lives in the pure `:core:domain` types, which are the
 * unit-tested part.
 *
 * NOT KEY WRAPPING: the AndroidKeyStore key that seals the mnemonic and the
 * imported private keys is not user-authentication-bound, so this lock gates
 * the UI and the session. It does not make the ciphertext unreadable to code
 * already running as this app's uid — a rooted device or a compromised process
 * can still reach the keys through [WalletKeyCrypto]. That limitation is
 * unchanged from the pre-existing gate.
 */
class WalletLockManager(
    private val store: WalletLockStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val iterations: Int = PinLockCrypto.ITERATIONS,
    private val cryptoDispatcher: CoroutineDispatcher = Dispatchers.Default
) {

    suspend fun policy(profileId: String): WalletLockPolicy =
        WalletLockPolicy(pinConfigured = store.load(profileId)?.pinConfigured == true)

    suspend fun status(profileId: String): WalletLockStatus {
        val record = store.load(profileId) ?: return WalletLockStatus.Ready
        return machine(record).status()
    }

    /** Verifies [pin], applies the retry policy and persists the outcome. */
    suspend fun verifyPin(profileId: String, pin: CharArray): PinUnlockResult {
        val record = store.load(profileId)
        if (record == null || !record.pinConfigured) return PinUnlockResult.NoPin
        val machine = machine(record)
        (machine.status() as? WalletLockStatus.Backoff)?.let { return PinUnlockResult.Backoff(it) }
        val correct = withContext(cryptoDispatcher) {
            PinLockCrypto.verify(pin, record.pinSaltB64, record.pinIterations, record.pinVerifierB64)
        }
        val next = machine.afterAttempt(correct, clock())
        store.save(
            profileId,
            record.copy(
                failedAttempts = next.failedAttempts,
                lastFailureAtMs = next.lastFailureAtMs
            )
        )
        return if (correct) {
            PinUnlockResult.Unlocked
        } else {
            PinUnlockResult.Wrong(
                next.status(),
                PinAttemptPolicy.attemptsUntilBackoff(next.failedAttempts)
            )
        }
    }

    /** Stores a new PIN verifier and clears any retry counter. Caller wipes [pin]. */
    suspend fun setPin(profileId: String, pin: CharArray) {
        val salt = PinLockCrypto.newSalt(random)
        val verifier = withContext(cryptoDispatcher) {
            PinLockCrypto.deriveVerifier(pin, salt, iterations)
        }
        val b64 = Base64.getEncoder()
        store.save(
            profileId,
            WalletLockRecord(
                pinSaltB64 = b64.encodeToString(salt),
                pinIterations = iterations,
                pinVerifierB64 = b64.encodeToString(verifier),
                failedAttempts = 0,
                lastFailureAtMs = null
            )
        )
    }

    suspend fun removePin(profileId: String) {
        store.clear(profileId)
    }

    /** A successful unlock by ANY method clears the retry counter. */
    suspend fun clearFailures(profileId: String) {
        val record = store.load(profileId) ?: return
        if (record.failedAttempts == 0 && record.lastFailureAtMs == null) return
        store.save(profileId, record.copy(failedAttempts = 0, lastFailureAtMs = null))
    }

    private fun machine(record: WalletLockRecord): WalletLockStateMachine =
        WalletLockStateMachine(record.failedAttempts, record.lastFailureAtMs, clock)
}
