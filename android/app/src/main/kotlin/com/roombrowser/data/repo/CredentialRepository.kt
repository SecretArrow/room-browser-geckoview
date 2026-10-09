package com.roombrowser.data.repo

import com.roombrowser.data.db.CredentialDao
import com.roombrowser.data.db.CredentialEntity
import com.roombrowser.domain.credentials.CredentialDomainMatcher
import com.roombrowser.domain.credentials.SavedCredential
import com.roombrowser.domain.model.ProfileId
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
 * Thrown by every [CredentialRepository] API while the vault is locked for
 * this process — the caller must run the unlock (biometric) gate first.
 */
class VaultLockedException : IllegalStateException(
    "The password vault is locked; run the unlock gate before using it"
)

/**
 * Default unlock lifetime for [CredentialRepository]: 5 minutes. Long enough
 * to cover the pattern this vault actually serves — unlock, fill or save a
 * login, carry on browsing — without re-prompting mid-flow; short enough that
 * decrypted passwords are not sitting in memory for an afternoon of
 * backgrounded browsing. A repository constructed with a timeout <= 0 keeps
 * the legacy behaviour (unlocked until locked or the process dies).
 *
 * Top-level rather than a companion member on purpose: it is a policy value
 * shared by the constructor default and by tests, and this form has no
 * declaration-order or companion-scope subtlety.
 */
const val DEFAULT_AUTO_LOCK_TIMEOUT_MS = 5L * 60L * 1000L

/**
 * Per-profile password-manager repository: Room rows + per-profile vault
 * encryption (security.VaultCrypto / [VaultCryptor]).
 *
 * Security invariants:
 *  - the DAO sees ONLY ciphertext ([CredentialEntity.passwordEnc]); the
 *    plaintext password exists in memory solely between the UI/caller and
 *    the [SavedCredential] handed back, and nothing is ever logged;
 *  - every read/write is scoped to the profile it was called with — a row
 *    belonging to another profile is invisible and undeletable through this
 *    profile's calls (credentials are profile data, like tabs and history);
 *  - all suspend work runs on [Dispatchers.IO] (Keystore + Room are blocking).
 *
 * SESSION LOCK POLICY — "ask per session, not per action": the vault starts
 * LOCKED in every process. The UI layer runs its biometric / device-credential
 * gate ONCE and then calls [unlock]; every repository call after that works
 * without re-prompting until [lock] (explicit lock, background timeout, ...).
 * The "background timeout" is [autoLockTimeoutMs] (DEFAULT 5 minutes) and it
 * is NOT sliding: the watchdog re-locks the vault [autoLockTimeoutMs] after
 * the unlock, even if the vault is being used the whole time. That is the
 * point — a sliding timer would let a busy session hold plaintext key
 * material indefinitely, which is exactly what a timeout exists to bound.
 * Within the window nothing re-prompts, so the per-session feel is unchanged
 * for ordinary use (open the vault, fill a login, save a login: seconds).
 * Conversely, EVERY API (reads, saves, deletes, import/export) throws
 * [VaultLockedException] while locked — there is no unlocked back door. Both
 * processes (:browser + default) hold their own lock state, so unlocking the
 * manager UI in the main process does not silently unlock the ':browser'
 * process' copy.
 *
 * AUTO-LOCK LIMITATION (honest): the watchdog is a coroutine `delay` on a
 * process-lived scope. Android freezes backgrounded processes, so on a
 * device that is not in active use the re-lock lands LATE (it runs on thaw),
 * never early and never never. The timeout therefore bounds the plaintext
 * window of an ACTIVE process; process death remains the hard bound.
 *
 * [autoLockScope] is injectable so tests can drive the watchdog with virtual
 * time; production never passes it. The repository is a singleton in the
 * AppGraph, so the default scope's lifetime matches the process.
 */
class CredentialRepository(
    private val dao: CredentialDao,
    private val crypto: VaultCryptor,
    /** Unlock lifetime in ms; <= 0 disables the timeout (legacy behaviour). */
    private val autoLockTimeoutMs: Long = DEFAULT_AUTO_LOCK_TIMEOUT_MS,
    private val autoLockScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    private val lockState = MutableStateFlow(false)

    /**
     * The pending auto-lock watchdog, when one is armed. Touched only by
     * [unlock]/[lock] (UI-thread calls) and by [armAutoLock]; a completed or
     * cancelled job is left in place — replacing/cancelling it again is a
     * no-op, and clearing it from inside the coroutine would be a self-cancel.
     */
    private var autoLockJob: Job? = null

    /** Live lock state for UI (lock screens listen to flip to the gate). */
    val isUnlocked: StateFlow<Boolean> = lockState.asStateFlow()

    /**
     * Marks the vault unlocked for this process/session and arms the
     * auto-lock watchdog. The caller MUST have completed its own biometric
     * (or device-credential) gate before calling — this method records the
     * gate's result, it does not run the gate.
     */
    fun unlock() {
        lockState.value = true
        armAutoLock()
    }

    /** Re-locks the vault for this process (explicit lock / session end). */
    fun lock() {
        autoLockJob?.cancel()
        autoLockJob = null
        lockState.value = false
    }

    /**
     * (Re-)arms the watchdog for one full [autoLockTimeoutMs] window. Any
     * previous watchdog is cancelled first, so a lock→unlock cycle can never
     * leave an older, earlier deadline behind.
     */
    private fun armAutoLock() {
        autoLockJob?.cancel()
        autoLockJob = null
        if (autoLockTimeoutMs <= 0L) return
        autoLockJob = autoLockScope.launch {
            delay(autoLockTimeoutMs)
            // Fail closed by writing the flag directly: this coroutine IS the
            // job referenced above, so calling lock() here would cancel
            // itself mid-function.
            lockState.value = false
        }
    }

    /**
     * Inserts a new credential, or updates the row named by [id] when editing
     * (createdAt is preserved, updatedAt is bumped). The password is encrypted
     * under the profile's vault key; the domain is canonicalized
     * (lowercase host, scheme/path/trailing dot stripped).
     *
     * @throws VaultLockedException when the vault is locked.
     * @throws IllegalArgumentException when [domain] has no usable host, or
     * [id] names a row that belongs to a different profile.
     */
    suspend fun save(
        profileId: ProfileId,
        domain: String,
        username: String,
        password: String,
        title: String? = null,
        id: String? = null
    ): SavedCredential {
        requireUnlocked()
        val canonical = canonicalDomain(domain)
        require(canonical.isNotEmpty()) { "Credential domain must not be blank" }
        return withContext(Dispatchers.IO) {
            val existing = id?.let { dao.byId(it) }
            if (existing != null && existing.profileId != profileId.value) {
                // Refuse to hijack another profile's row: upserting by this id
                // would silently move that profile's login into this vault.
                throw IllegalArgumentException("Credential $id belongs to a different profile")
            }
            val now = System.currentTimeMillis()
            val createdAt = existing?.createdAt ?: now
            val rowId = id ?: UUID.randomUUID().toString()
            val entity = CredentialEntity(
                id = rowId,
                profileId = profileId.value,
                domain = canonical,
                username = username,
                passwordEnc = crypto.encrypt(profileId.safeSuffix, password),
                title = title,
                createdAt = createdAt,
                updatedAt = now
            )
            dao.upsert(entity)
            // The plaintext is already in hand — do not round-trip it through
            // the Keystore just to hand it back to the caller.
            SavedCredential(
                id = rowId,
                profileId = profileId.value,
                domain = canonical,
                username = username,
                password = password,
                title = title,
                createdAt = createdAt,
                updatedAt = now
            )
        }
    }

    /**
     * Live list of the profile's credentials (domain/username order),
     * decrypted on arrival. Passwords are included — the vault key is
     * device-bound hardware crypto, so this stream only ever exists while
     * the vault is unlocked for the session.
     *
     * The lock is checked when the flow is obtained AND on every emission: a
     * lock that lands mid-collection stops plaintext from streaming out
     * (fail closed — the collector receives [VaultLockedException] and is
     * expected to restart the flow after the next unlock).
     *
     * @throws VaultLockedException when the vault is locked at call time.
     */
    suspend fun observe(profileId: ProfileId): Flow<List<SavedCredential>> {
        requireUnlocked()
        val profileKey = profileId.safeSuffix
        return dao.observe(profileId.value)
            .map { rows ->
                if (!lockState.value) throw VaultLockedException()
                rows.map { it.toDomain(profileKey) }
            }
            .flowOn(Dispatchers.IO)
    }

    /**
     * One credential of this profile, or null when the id is unknown or
     * belongs to a different profile.
     *
     * @throws VaultLockedException when the vault is locked.
     */
    suspend fun get(profileId: ProfileId, id: String): SavedCredential? {
        requireUnlocked()
        return withContext(Dispatchers.IO) {
            dao.byId(id)
                ?.takeIf { it.profileId == profileId.value }
                ?.toDomain(profileId.safeSuffix)
        }
    }

    /**
     * Substring search over domain / username / title (see CredentialDao).
     *
     * @throws VaultLockedException when the vault is locked.
     */
    suspend fun search(profileId: ProfileId, query: String): List<SavedCredential> {
        requireUnlocked()
        val needle = query.trim()
        return withContext(Dispatchers.IO) {
            dao.search(profileId.value, needle).map { it.toDomain(profileId.safeSuffix) }
        }
    }

    /**
     * Deletes the credential — but ONLY when it belongs to [profileId];
     * deleting through the wrong profile is a silent no-op, never a
     * cross-profile delete.
     *
     * @throws VaultLockedException when the vault is locked.
     */
    suspend fun delete(profileId: ProfileId, id: String) {
        requireUnlocked()
        withContext(Dispatchers.IO) {
            if (dao.byId(id)?.profileId == profileId.value) {
                dao.delete(id)
            }
        }
    }

    /**
     * All credentials of the profile that match the page host — equal or
     * parent/child domain (CredentialDomainMatcher). This is the autofill
     * lookup: full scan of the profile's rows, then match, then decrypt.
     *
     * @throws VaultLockedException when the vault is locked.
     */
    suspend fun findForDomain(profileId: ProfileId, host: String): List<SavedCredential> {
        requireUnlocked()
        return withContext(Dispatchers.IO) {
            dao.allForProfile(profileId.value)
                .filter { CredentialDomainMatcher.matches(it.domain, host) }
                .map { it.toDomain(profileId.safeSuffix) }
        }
    }

    /**
     * All of the profile's credentials, decrypted — the source side of the
     * export flow (the caller seals the result with the passphrase-based
     * domain/credentials.PasswordVaultCrypto before writing any file).
     *
     * @throws VaultLockedException when the vault is locked.
     */
    suspend fun exportAll(profileId: ProfileId): List<SavedCredential> {
        requireUnlocked()
        return withContext(Dispatchers.IO) {
            dao.allForProfile(profileId.value).map { it.toDomain(profileId.safeSuffix) }
        }
    }

    /**
     * Bulk insert into the profile's vault: every password is RE-ENCRYPTED
     * under THIS profile's key (source rows were encrypted under a different
     * key — a different profile or device), and every row gets a fresh UUID
     * so an import can never collide with (or overwrite) existing rows.
     * Timestamps are preserved from the source for import fidelity. Rows
     * whose domain canonicalizes to nothing (corrupt source data) are skipped
     * rather than aborting the whole import — and are NOT counted in the
     * return value, so the caller reports what it actually wrote.
     *
     * @return how many logins were written.
     * @throws VaultLockedException when the vault is locked.
     */
    suspend fun importAll(profileId: ProfileId, creds: List<SavedCredential>): Int {
        requireUnlocked()
        if (creds.isEmpty()) return 0
        return withContext(Dispatchers.IO) {
            var written = 0
            creds.forEach { c ->
                val domain = canonicalDomain(c.domain)
                if (domain.isEmpty()) return@forEach
                dao.upsert(
                    CredentialEntity(
                        id = UUID.randomUUID().toString(),
                        profileId = profileId.value,
                        domain = domain,
                        username = c.username,
                        passwordEnc = crypto.encrypt(profileId.safeSuffix, c.password),
                        title = c.title,
                        createdAt = c.createdAt,
                        updatedAt = c.updatedAt
                    )
                )
                written++
            }
            written
        }
    }

    private fun requireUnlocked() {
        if (!lockState.value) throw VaultLockedException()
    }

    /** Decrypts one row. [profileKey] is the row's profile safeSuffix. */
    private fun CredentialEntity.toDomain(profileKey: String): SavedCredential =
        SavedCredential(
            id = id,
            profileId = profileId,
            domain = domain,
            username = username,
            password = crypto.decrypt(profileKey, passwordEnc),
            title = title,
            createdAt = createdAt,
            updatedAt = updatedAt
        )

    /**
     * Forces the domain into its canonical host form — lowercase, scheme and
     * path stripped, trailing dot removed — so matching and ordering never
     * depend on how the site was written when the login was saved.
     */
    private fun canonicalDomain(raw: String): String {
        var host = raw.trim()
        val schemeAt = host.indexOf("://")
        if (schemeAt >= 0) host = host.substring(schemeAt + 3)
        val pathAt = host.indexOf('/')
        if (pathAt >= 0) host = host.substring(0, pathAt)
        return CredentialDomainMatcher.normalize(host)
    }
}
