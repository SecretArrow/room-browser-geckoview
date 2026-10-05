package com.roombrowser.data.repo

import com.roombrowser.data.db.TotpDao
import com.roombrowser.data.db.TotpEntity
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.totp.TotpAlgorithm
import com.roombrowser.domain.totp.TotpBackup
import com.roombrowser.domain.totp.TotpEntry
import com.roombrowser.domain.totp.TotpGenerator
import com.roombrowser.security.VaultCryptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The profile's authenticator accounts: [CredentialRepository]'s lock
 * discipline over a different table and a different Keystore key
 * (`TotpKeyCrypto`, "roomtotp-"), so a defect in credential code cannot read
 * OTP seeds.
 *
 * The lock here is the one the 2FA screen's gate opens — biometric or device
 * credential first, the profile PIN if the device has none. Its limit is the
 * one every UI gate in this app already has: NO key uses
 * `setUserAuthenticationRequired`, so code running as the app uid can still
 * reach the Keystore key. The gate protects the screen, not the ciphertext —
 * which is exactly why [codeForAgent] can exist below.
 */
class TotpRepository(
    private val dao: TotpDao,
    private val crypto: VaultCryptor,
    private val autoLockTimeoutMs: Long = DEFAULT_AUTO_LOCK_TIMEOUT_MS,
    private val autoLockScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    private val lockState = MutableStateFlow(false)
    private var autoLockJob: Job? = null

    /** Live lock state for UI (the 2FA screen listens to flip to the gate). */
    val isUnlocked: StateFlow<Boolean> = lockState.asStateFlow()

    /**
     * Marks 2FA unlocked for this process and arms the auto-lock watchdog. The
     * caller MUST have completed its own gate before calling — this records the
     * gate's result, it does not run the gate.
     */
    fun unlock() {
        lockState.value = true
        armAutoLock()
    }

    /** Re-locks 2FA for this process (explicit lock / session end). */
    fun lock() {
        autoLockJob?.cancel()
        autoLockJob = null
        lockState.value = false
    }

    private fun armAutoLock() {
        autoLockJob?.cancel()
        autoLockJob = null
        if (autoLockTimeoutMs <= 0L) return
        autoLockJob = autoLockScope.launch {
            delay(autoLockTimeoutMs)
            // Fail closed by writing the flag directly: this coroutine IS the
            // job referenced above, so lock() here would cancel itself.
            lockState.value = false
        }
    }

    /**
     * Inserts a new account, or updates the row named by [id] when editing.
     * The seed is encrypted under the profile's 2FA key and never stored in the
     * clear.
     *
     * @throws VaultLockedException when 2FA is locked.
     * @throws IllegalArgumentException on a blank secret, a digit count other
     *   than 6/8, a non-positive period, or an [id] owned by another profile.
     */
    suspend fun save(
        profileId: ProfileId,
        issuer: String,
        account: String,
        secret: String,
        algorithm: TotpAlgorithm = TotpAlgorithm.SHA1,
        digits: Int = 6,
        period: Int = 30,
        id: String? = null
    ): TotpEntry {
        requireUnlocked()
        val trimmedSecret = secret.trim()
        require(trimmedSecret.isNotEmpty()) { "A 2FA secret is required" }
        require(digits == 6 || digits == 8) { "digits must be 6 or 8, was $digits" }
        require(period > 0) { "period must be positive, was $period" }
        return withContext(Dispatchers.IO) {
            val existing = id?.let { dao.byId(it) }
            if (existing != null && existing.profileId != profileId.value) {
                // Refuse to hijack another profile's row.
                throw IllegalArgumentException("2FA entry $id belongs to a different profile")
            }
            val now = System.currentTimeMillis()
            val rowId = id ?: UUID.randomUUID().toString()
            val createdAt = existing?.createdAt ?: now
            val entity = TotpEntity(
                id = rowId,
                profileId = profileId.value,
                issuer = issuer,
                account = account,
                secretEnc = crypto.encrypt(profileId.safeSuffix, trimmedSecret),
                algorithm = algorithm.name,
                digits = digits,
                period = period,
                createdAt = createdAt,
                lastUsedAt = existing?.lastUsedAt
            )
            dao.upsert(entity)
            // The plaintext is already in hand; do not round-trip it through
            // the Keystore just to hand it back.
            entity.toDomain(profileId.safeSuffix)
        }
    }

    /**
     * Live list of the profile's accounts, recently used first, decrypted on
     * arrival.
     *
     * The lock is checked when the flow is obtained AND on every emission: a
     * lock landing mid-collection stops seeds from streaming out (fail closed —
     * the collector receives [VaultLockedException] and is expected to restart
     * after the next unlock).
     *
     * @throws VaultLockedException when 2FA is locked.
     */
    suspend fun observe(profileId: ProfileId): Flow<List<TotpEntry>> {
        requireUnlocked()
        return dao.observe(profileId.value)
            .map { rows ->
                if (!lockState.value) throw VaultLockedException()
                rows.map { it.toDomain(profileId.safeSuffix) }
            }
            .flowOn(Dispatchers.IO)
    }

    /**
     * One account of this profile, or null when the id is unknown or belongs to
     * a different profile.
     *
     * @throws VaultLockedException when 2FA is locked.
     */
    suspend fun get(profileId: ProfileId, id: String): TotpEntry? {
        requireUnlocked()
        return withContext(Dispatchers.IO) { load(profileId, id) }
    }

    /**
     * Deletes one of this profile's accounts. A cross-profile id is a silent
     * no-op, never a cross-profile delete.
     *
     * @throws VaultLockedException when 2FA is locked.
     */
    suspend fun delete(profileId: ProfileId, id: String) {
        requireUnlocked()
        withContext(Dispatchers.IO) {
            if (dao.byId(id)?.profileId == profileId.value) dao.delete(id)
        }
    }

    /**
     * Records that a code was copied or filled, which is what orders the
     * "recently used" sort. Called on copy/fill — NOT on every tick, which
     * would write to the database every second per visible row.
     *
     * Deliberately NOT gated on the lock: it writes a timestamp, no secret, and
     * the agent's fill path may legitimately run it while the screen is locked.
     */
    suspend fun markUsed(id: String, at: Long = System.currentTimeMillis()) {
        withContext(Dispatchers.IO) { dao.markUsed(id, at) }
    }

    /**
     * All of the profile's accounts, decrypted — the source side of the export
     * flow (the caller seals the result with [com.roombrowser.domain.totp.TotpBackup]).
     *
     * @throws VaultLockedException when 2FA is locked.
     */
    suspend fun exportAll(profileId: ProfileId): List<TotpEntry> {
        requireUnlocked()
        return withContext(Dispatchers.IO) {
            dao.allForProfile(profileId.value).map { it.toDomain(profileId.safeSuffix) }
        }
    }

    /**
     * Bulk insert from a decoded backup file: every seed is RE-ENCRYPTED under
     * THIS profile's key (the source entries came from another profile or
     * another phone) and every row gets a fresh UUID, so an import can never
     * collide with or overwrite an existing row. The format carries no
     * timestamps, so [at] stamps them all and `last_used_at` is deliberately
     * left null rather than inherited.
     *
     * @throws VaultLockedException when 2FA is locked.
     */
    suspend fun importAll(
        profileId: ProfileId,
        entries: List<TotpBackup.Entry>,
        at: Long = System.currentTimeMillis()
    ) {
        requireUnlocked()
        if (entries.isEmpty()) return
        withContext(Dispatchers.IO) {
            entries.forEach { entry ->
                dao.upsert(
                    TotpEntity(
                        id = UUID.randomUUID().toString(),
                        profileId = profileId.value,
                        issuer = entry.issuer,
                        account = entry.account,
                        secretEnc = crypto.encrypt(profileId.safeSuffix, entry.secret),
                        algorithm = entry.algorithm.name,
                        digits = entry.digits,
                        period = entry.period,
                        createdAt = at,
                        lastUsedAt = null
                    )
                )
            }
        }
    }

    /**
     * How many accounts the profile holds. Not gated on the lock: a count is
     * not a secret, and the delete-profile warning has to be able to say "and
     * N authenticator accounts" while the screen is still locked.
     */
    suspend fun countForProfile(profileId: ProfileId): Int =
        withContext(Dispatchers.IO) { dao.countForProfile(profileId.value) }

    /**
     * The current code for one of the profile's accounts, WITHOUT requiring an
     * unlock.
     *
     * This is the in-app agent's door and it is deliberately separate from the
     * methods above rather than a widened version of one: the owner decided the
     * agent may always retrieve a code, and expressing that as "skip the lock
     * here" keeps the fail-closed design intact for the screen. The consequence
     * is stated plainly because it is real — the 2FA lock protects the SCREEN,
     * not the codes, so anything able to drive the agent can obtain a live code
     * without unlocking.
     *
     * What this can never return is the SEED: it generates and returns digits
     * only. Owner toggle `agent_otp_digits` decides whether those digits reach
     * the model at all; that gate lives in the agent tool, not here, so the
     * policy stays a setting rather than a code path.
     *
     * @return the code, or null when [id] is unknown or belongs to another
     *   profile — a wrong profile is indistinguishable from a missing row, so
     *   the agent cannot probe for the existence of another profile's accounts.
     */
    suspend fun codeForAgent(
        profileId: ProfileId,
        id: String,
        timeMillis: Long = System.currentTimeMillis()
    ): String? = withContext(Dispatchers.IO) {
        load(profileId, id)?.let { TotpGenerator.generate(it, timeMillis) }
    }

    /**
     * Labels only — issuer and account, never a secret and never a code — for
     * the agent to name an account before asking for one.
     */
    suspend fun labelsForAgent(profileId: ProfileId): List<TotpEntry> =
        withContext(Dispatchers.IO) {
            dao.allForProfile(profileId.value).map { it.toDomain(profileId.safeSuffix) }
        }

    private suspend fun load(profileId: ProfileId, id: String): TotpEntry? =
        dao.byId(id)
            ?.takeIf { it.profileId == profileId.value }
            ?.toDomain(profileId.safeSuffix)

    private fun requireUnlocked() {
        if (!lockState.value) throw VaultLockedException()
    }

    /**
     * Decrypts one row. The seed is the ONLY encrypted column; issuer and
     * account arrive as stored, which is what makes search and grouping work
     * without touching the Keystore.
     */
    private fun TotpEntity.toDomain(profileKey: String): TotpEntry = TotpEntry(
        id = id,
        profileId = profileId,
        issuer = issuer,
        account = account,
        secret = crypto.decrypt(profileKey, secretEnc),
        // The otpauth format's own default is SHA1, so an unreadable value
        // resolves the way an absent one does. Only a corrupt row or one
        // written by a future version can get here: OtpAuthUri rejects an
        // unknown algorithm at import time.
        algorithm = TotpAlgorithm.entries.firstOrNull { it.name == algorithm }
            ?: TotpAlgorithm.SHA1,
        digits = digits,
        period = period,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt
    )
}
