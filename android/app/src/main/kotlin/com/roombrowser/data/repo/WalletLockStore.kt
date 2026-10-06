package com.roombrowser.data.repo

import kotlinx.serialization.Serializable

/**
 * Per-profile lock state, stored as JSON in the app_state KV table so it is
 * readable from BOTH processes and survives process death. It is the PROFILE
 * lock shared by the wallet and the 2FA screen. The PIN is never here: only its
 * per-profile salt, the iteration count it was derived at, and a one-way PBKDF2
 * verifier. The AndroidKeyStore key that actually seals the wallet is untouched
 * by this record.
 */
@Serializable
data class WalletLockRecord(
    val pinSaltB64: String = "",
    val pinIterations: Int = 0,
    val pinVerifierB64: String = "",
    val failedAttempts: Int = 0,
    val lastFailureAtMs: Long? = null
) {
    val pinConfigured: Boolean
        get() = pinSaltB64.isNotEmpty() && pinVerifierB64.isNotEmpty() && pinIterations > 0
}

/** Persistence seam for the profile lock, fakeable in JVM tests. */
interface WalletLockStore {
    suspend fun load(profileId: String): WalletLockRecord?
    suspend fun save(profileId: String, record: WalletLockRecord)
    suspend fun clear(profileId: String)
}

/** [AppStateRepository] implementation — cross-process, survives process death. */
class AppStateWalletLockStore(private val appState: AppStateRepository) : WalletLockStore {

    override suspend fun load(profileId: String): WalletLockRecord? =
        appState.profileLockRecord(profileId)

    override suspend fun save(profileId: String, record: WalletLockRecord) {
        appState.saveProfileLockRecord(profileId, record)
    }

    override suspend fun clear(profileId: String) {
        appState.clearProfileLockRecord(profileId)
    }
}
