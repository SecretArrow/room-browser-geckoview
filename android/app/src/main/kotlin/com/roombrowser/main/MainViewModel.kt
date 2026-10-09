package com.roombrowser.main

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roombrowser.RoomBrowserApp
import com.roombrowser.browser.engine.ProfileEngine
import com.roombrowser.data.repo.AppStateRepository
import com.roombrowser.data.repo.ProfileRepositoryImpl
import com.roombrowser.domain.credentials.PasswordCsv
import com.roombrowser.domain.credentials.PasswordImportMerge
import com.roombrowser.domain.credentials.PasswordVaultCrypto
import com.roombrowser.domain.credentials.SavedCredential
import com.roombrowser.domain.credentials.VaultAuthException
import com.roombrowser.domain.credentials.VaultFormatException
import com.roombrowser.domain.engine.UrlIntelligence
import com.roombrowser.domain.export.PasswordTransfer
import com.roombrowser.domain.export.PasswordTransferFormatException
import com.roombrowser.domain.export.ProfileBackup
import com.roombrowser.domain.export.ProfileBackupResult
import com.roombrowser.domain.export.WalletBackup
import com.roombrowser.domain.export.WalletBackupFormatException
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.model.ProfileSettings
import com.roombrowser.domain.profile.CopyOptions
import com.roombrowser.domain.profile.ProfileManager
import com.roombrowser.domain.theme.BuiltInThemes
import com.roombrowser.domain.totp.TotpBackup
import com.roombrowser.domain.totp.TotpBackupFormatException
import com.roombrowser.domain.wallet.model.ChainType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A fully built export waiting for its delivery action (save file / share). */
class PendingExport(val fileName: String, val json: String, val sizeBytes: Int)

/**
 * The passphrase step of an in-flight export or import.
 *
 * @param forExport true = the user is CHOOSING a passphrase to seal the
 *   export (two fields, min length enforced by the dialog); false = the user
 *   is ENTERING the passphrase an import file was sealed with.
 * @param error inline retry hint (import only), e.g. "Wrong passphrase".
 * @param passwordsFile the import is a standalone passwords file, so the
 *   dialog must not name a profile origin it cannot know.
 * @param id identity of THIS prompt instance. Like [VaultGateRequest.id] it
 *   exists so an equal-looking prompt is still a NEW prompt: the dialog keys
 *   its passphrase fields on it. Without it, a second wrong passphrase
 *   produced a byte-identical copy of the first retry's prompt, the remember
 *   key never changed and the typed secret stayed in the field instead of
 *   being cleared. No default — every prompt must claim a fresh id.
 */
data class PassphrasePrompt(
    val forExport: Boolean,
    val profileName: String,
    val credentialCount: Int,
    /** Authenticator accounts riding in the same file; the dialog has to say
     *  that the one passphrase also unlocks those, or a 2FA-only profile is
     *  asked for a passphrase with nothing on screen explaining why. */
    val totpCount: Int = 0,
    /** How the wallet block is described on the export side, already worded:
     *  a wallet can be a phrase, imported keys, or both, and a count alone
     *  cannot say which. Null when no wallet is going into the file. */
    val walletPhrase: String? = null,
    val error: String? = null,
    val passwordsFile: Boolean = false,
    /** Overrides the dialog title when the caller's phrasing is not the
     *  profile export/import one (null = today's wording). */
    val titleOverride: String? = null,
    /** Overrides the dialog body for the same reason (null = today's). */
    val bodyOverride: String? = null,
    val id: Int
)

/**
 * The one-tap follow-up a [MainViewModel.message] can carry. The ViewModel
 * cannot hold the Activity, so the screen maps this to the real navigation —
 * same division of labour as [VaultGateRequest].
 */
enum class MessageAction { OPEN_NOTIFICATION_SETTINGS }

/**
 * Signals MainScreen that the vault's biometric gate must run NOW (a
 * ViewModel cannot hold the Activity). The screen calls BiometricGate and
 * reports back via [MainViewModel.onVaultGateResult]. [id] makes each
 * request a fresh LaunchedEffect key even when they otherwise look equal.
 */
data class VaultGateRequest(val id: Int)

/**
 * @param credentialCount saved logins the profile holds, or null when the vault
 *   is locked and the count could not be read — a different sentence to the user.
 */
data class DeletePrompt(val profile: Profile, val credentialCount: Int?, val totpCount: Int)

/**
 * Which parts of a profile an export carries. Every flag but one defaults to
 * true, so the dialog opens on the complete file.
 *
 * The profile's own settings and theme are NOT here: they are the profile, and
 * a file without them would import as a nameless shell.
 *
 * THE WALLET IS THE EXCEPTION, and defaults OFF on purpose. Every other
 * section costs the user privacy if it leaks; this one costs them the money.
 * A file carrying the wallet block is worth exactly what the wallet holds, and
 * the file the user is about to email or drop in cloud storage is the same
 * file — so the box is there, it says what it does, and it is not ticked for
 * them.
 */
data class ExportSections(
    val bookmarks: Boolean = true,
    val notes: Boolean = true,
    val passwords: Boolean = true,
    val totp: Boolean = true,
    val sitePermissions: Boolean = true,
    val siteSettings: Boolean = true,
    val wallet: Boolean = false
) {
    /** True when a sealed block has to be built, and so a passphrase set. */
    val needsVault: Boolean get() = passwords || totp || wallet

    /** The selections a passwords-only file implies: nothing but the logins. */
    companion object {
        val PASSWORDS_FILE = ExportSections(
            bookmarks = false,
            notes = false,
            totp = false,
            sitePermissions = false,
            siteSettings = false,
            wallet = false
        )
    }
}

/**
 * The line shown after an import, naming every part that was actually written.
 *
 * Every count comes from the writes themselves, not from what the file held —
 * the credential store drops a login whose domain canonicalizes to nothing, and
 * a report that promised more than the device now holds is worse than none.
 * A profile whose sections were all empty still imports its own settings, so
 * that case says so rather than listing nothing.
 */
internal fun importedSummaryLine(
    summary: ProfileRepositoryImpl.ImportSummary
): String {
    val parts = buildList {
        if (summary.bookmarks > 0) add(count(summary.bookmarks, "bookmark"))
        if (summary.notes > 0) add(count(summary.notes, "note"))
        if (summary.credentials > 0) add(count(summary.credentials, "password"))
        if (summary.totp > 0) add(count(summary.totp, "authenticator account"))
        if (summary.sitePermissions > 0) add(count(summary.sitePermissions, "site permission"))
        if (summary.siteSettings > 0) add(count(summary.siteSettings, "per-site setting"))
        summary.wallet?.let { wallet ->
            val restored = wallet.derivedAccountCount + wallet.importedAccountCount
            if (restored > 0) add(count(restored, "wallet account"))
            if (wallet.phraseRestored) add("recovery phrase")
            // Named rather than absorbed: a key the file carried and this build
            // could not take is the one thing about a wallet import the user
            // must not learn from a missing balance later.
            if (wallet.skipped.isNotEmpty()) {
                add(count(wallet.skipped.size, "wallet key") + " skipped")
            }
        }
    }
    val details = if (parts.isEmpty()) "settings only" else parts.joinToString(", ")
    return "Imported \"${summary.profile.name}\" ($details)"
}

/** "1 bookmark" / "4 bookmarks" — the plural rule the import report uses for
 *  every section, so its parts read as one sentence. */
private fun count(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"

/**
 * Main-process ViewModel: profile CRUD, first-run state, external-link
 * routing ("Open with profile" — never silently opens the wrong profile)
 * and the backup v2 export / import flows.
 *
 * Export: gate → unlock → read credentials → (if any) set an export
 * passphrase → seal → deliver (SAF save / share). Import: parse (reject
 * anything unsupported BEFORE writing) → (if vault) file passphrase →
 * decrypt → gate → ONE Room transaction restoring everything.
 *
 * Nothing here logs the passphrase or any credential; the passphrase
 * CharArray is wiped right after each crypto call.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as RoomBrowserApp).graph
    private val profileManager: ProfileManager = graph.profileManager
    private val repo = graph.profileRepo
    val appState: AppStateRepository = graph.appState

    var profiles by mutableStateOf<List<Profile>>(emptyList())
        private set

    /** The picker itself is themed by the DEFAULT profile's theme — a live
     *  preview of the per-profile theme system. */
    var appTheme by mutableStateOf(BuiltInThemes.default())
        private set
    var tabCounts by mutableStateOf<Map<String, Int>>(emptyMap())
        private set

    /** Current generation of per-profile tab-count collectors — replaced,
     *  never stacked (see [observeTabCounts]). */
    private var tabCountsJob: Job? = null
    var firstRunDone by mutableStateOf(true)
        private set
    var pendingExternalUrl by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    /**
     * The follow-up the current [message] offers, or null when the message is
     * just text. Set beside [message] and cleared by [clearMessage] so a
     * stale action can never ride along with the next message.
     */
    var messageAction by mutableStateOf<MessageAction?>(null)
        private set

    // ---------- Backup v2: shared flow state ----------

    /** A finished export, staged until the user saves or shares it. */
    var pendingExport by mutableStateOf<PendingExport?>(null)
        private set

    /** The active passphrase step (export sealing / import unlocking). */
    var passphrasePrompt by mutableStateOf<PassphrasePrompt?>(null)
        private set

    /** Import rejection shown in a dialog — set only when NOTHING was written. */
    var importError by mutableStateOf<String?>(null)
        private set

    /** Non-null = MainScreen must run the vault's biometric gate now. */
    var vaultGateRequest by mutableStateOf<VaultGateRequest?>(null)
        private set

    // ---------- Passwords transfer ----------

    /** Profile id whose post-create "import your passwords?" offer is owed. */
    var passwordImportOfferId by mutableStateOf<String?>(null)
        private set

    /** The pre-delete prompt, or null when nothing is being deleted. */
    var deletePrompt by mutableStateOf<DeletePrompt?>(null)
        private set

    /** [passwordsOnly] exports the passwords file instead of a whole backup. */
    private class ExportDraft(
        val profile: Profile,
        val sections: ExportSections,
        val passwordsOnly: Boolean = false
    )

    private var exportDraft: ExportDraft? = null

    /** [sealedText] is null for a plain CSV, which needs no passphrase. */
    private class PasswordImport(val profile: Profile, val sealedText: String?)

    private var passwordImport: PasswordImport? = null

    /**
     * The profile whose export must be followed by its deletion — set when the
     * user chose "Export" in the pre-delete prompt, cleared by anything that
     * does not produce a delivered file. Deleting only after a file has
     * actually been written is the whole point of asking.
     */
    private var deleteAfterPasswordExport: ProfileId? = null

    /**
     * What a staged passwords export could not carry, held until the file is
     * delivered so it is said in the same sentence as the confirmation — and
     * again in the delete message, which is the one that outlives the snackbar.
     * Cleared when a new export starts and by [dropExportState].
     */
    private var pendingExportNote: String? = null

    /**
     * Plaintext credentials of the in-flight export. Deliberately NOT Compose
     * state: never rendered, never logged, never persisted — dropped the
     * moment the sealed vault blob exists.
     */
    private var exportCredentials: List<SavedCredential> = emptyList()

    /**
     * Plaintext authenticator entries of the in-flight export, read from the
     * 2FA store and dropped as soon as the sealed `totp` blob exists — same
     * rule as [exportCredentials], and for the same reason: a seed in memory
     * past the seal is a seed that can outlive the export.
     */
    private var exportTotp: List<TotpBackup.Entry> = emptyList()

    /**
     * The profile's wallet contents while an export is being assembled, under
     * the same rule as [exportTotp]: held only between the read and the seal,
     * then dropped. This one holds a recovery phrase and private keys in the
     * clear, so it is dropped whether the export succeeded or not.
     */
    private var exportWallet: WalletBackup.Contents? = null

    /** A parsed import payload waiting for its file passphrase / gate. */
    private var importPayload: ProfileBackup.BackupPayload? = null

    private var gateSeq = 0
    private var afterGate: (() -> Unit)? = null

    /** Source of [PassphrasePrompt.id] — see that field's doc. */
    private var promptSeq = 0

    /** Flipped by [claimNotificationPrompt]; see it for why once is enough. */
    private var notificationPromptClaimed = false

    /** Credential array codec for the sealed vault blob — the plaintext
     *  array exists only as the transient string between (de)serialization
     *  and the cipher. */
    private val credentialsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        viewModelScope.launch {
            firstRunDone = appState.firstRunDone()
        }
        viewModelScope.launch {
            repo.observeProfiles().collect { list ->
                profiles = list
                appTheme = BuiltInThemes.resolveOrDefault(
                    (list.firstOrNull { it.isDefault } ?: list.firstOrNull())?.themeJson ?: ""
                )
                observeTabCounts(list)
            }
        }
        viewModelScope.launch {
            val url = appState.externalUrl()
            if (url != null) pendingExternalUrl = url
        }
        // Observed rather than read once: the offer can be written by THIS
        // process (the create dialog) or by the browser process (the
        // quick-switcher, which restarts before any UI could show it), and a
        // profile list that is already on screen must pick it up either way.
        viewModelScope.launch {
            appState.observePasswordImportOffer().collect { passwordImportOfferId = it }
        }
    }

    /**
     * Live tab counts, one generation at a time. Each observeProfiles
     * emission REPLACES the previous generation: without cancelling, every
     * emission stacked a fresh set of per-profile collectors on top of the
     * old ones (which kept counting even for deleted profiles).
     */
    private fun observeTabCounts(list: List<Profile>) {
        tabCountsJob?.cancel()
        if (list.isEmpty()) {
            tabCounts = emptyMap()
            tabCountsJob = null
            return
        }
        tabCountsJob = viewModelScope.launch {
            val snapshot = mutableMapOf<String, Int>()
            kotlinx.coroutines.coroutineScope {
                list.forEach { p ->
                    launch {
                        graph.browserRepo.observeTabCount(p.id).collect { count ->
                            snapshot[p.id.value] = count
                            tabCounts = snapshot.toMap()
                        }
                    }
                }
            }
        }
    }

    /**
     * Records the passwords-import offer rather than raising it: the same
     * creation also happens from the quick-switcher, which restarts the process.
     */
    fun createProfile(
        name: String,
        icon: String,
        colorArgb: Long,
        settings: ProfileSettings = ProfileSettings(),
        onCreated: (Profile) -> Unit
    ) {
        viewModelScope.launch {
            runCatching { profileManager.create(name, icon, colorArgb, settings) }
                .onSuccess {
                    message = "Profile \"${it.name}\" is ready"
                    appState.setPasswordImportOffer(it.id.value)
                    onCreated(it)
                }
                .onFailure { message = it.message ?: "Could not create profile" }
        }
    }

    /** The offer was shown (imported or declined) — never show it again. */
    fun consumePasswordImportOffer() {
        viewModelScope.launch { appState.setPasswordImportOffer(null) }
    }

    fun duplicateProfile(id: ProfileId, options: CopyOptions) {
        viewModelScope.launch {
            runCatching { profileManager.duplicate(id, options) }
                .onSuccess { message = "Duplicated as \"${it.name}\" (new isolated storage)" }
                .onFailure { message = it.message ?: "Could not duplicate profile" }
        }
    }

    /**
     * The "Edit Profile" dialog's single save.
     *
     * It used to be two calls — renameProfile() followed by restyleProfile()
     * — and that was a lost-update race: two coroutines, two independent
     * get → copy → put round trips on the same row, both reading before
     * either wrote. Whichever put landed second discarded the other's field,
     * so the new name OR the new icon/color vanished while the snackbar still
     * said the save had worked. [ProfileManager.update] does it in one read
     * and one write, and both former entry points are gone so the race cannot
     * be reintroduced by calling them in sequence again.
     */
    fun updateProfile(id: ProfileId, name: String, icon: String?, colorArgb: Long?) {
        viewModelScope.launch {
            runCatching { profileManager.update(id, name, icon, colorArgb) }
                .onSuccess { message = "Renamed (storage identity unchanged)" }
                .onFailure { message = it.message ?: "Could not update profile" }
        }
    }

    /**
     * Lock / unlock a profile.
     *
     * The bare launch this used to be let ProfileManager's "Profile not
     * found" escape a coroutine with no handler: tapping the lock entry on a
     * row the ':browser' process had just deleted CRASHED the launcher
     * instead of reporting it. Same runCatching shape as every sibling.
     */
    fun setLocked(id: ProfileId, locked: Boolean) {
        viewModelScope.launch {
            runCatching { profileManager.setLocked(id, locked) }
                .onSuccess { message = if (locked) "Profile locked" else "Profile unlocked" }
                .onFailure { message = it.message ?: "Could not change the profile lock" }
        }
    }

    /** Same unguarded-throw fix as [setLocked]: a vanished row must report,
     *  not crash the launcher. */
    fun setDefault(id: ProfileId) {
        viewModelScope.launch {
            runCatching { profileManager.setDefault(id) }
                .onSuccess { message = "Default profile updated" }
                .onFailure { message = it.message ?: "Could not change the default profile" }
        }
    }

    /**
     * @param successMessage what to say when it worked. The pre-delete password
     *   prompt's two ways out both end here and the user needs to be told WHICH
     *   one happened: "Profile deleted with all its data" after an export that
     *   succeeded reads as though the passwords went with it.
     */
    fun deleteProfile(
        id: ProfileId,
        successMessage: String = "Profile deleted with all its data"
    ) {
        viewModelScope.launch {
            if (refuseDeleteOfActiveProfile(id)) return@launch
            // Engine directories first: once the profile row is gone nothing
            // is left that could find them again.
            val wiped = withContext(Dispatchers.IO) {
                runCatching {
                    ProfileEngine.wipeProfileStorage(getApplication<Application>(), id)
                }.isSuccess
            }
            runCatching { profileManager.delete(id) }
                .onSuccess {
                    message = if (wiped) {
                        successMessage
                    } else {
                        "Profile deleted, but its engine data could not be removed"
                    }
                    if (passwordImportOfferId == id.value) consumePasswordImportOffer()
                }
                .onFailure { message = it.message ?: "Could not delete profile" }
        }
    }

    /**
     * True when [id] is the profile the browser process is bound to, in which
     * case it must not be deleted: its engine is live, so wiping its
     * directories would not erase them, and the running browser would be left
     * holding a profile that no longer exists.
     */
    private suspend fun refuseDeleteOfActiveProfile(id: ProfileId): Boolean {
        if (appState.activeProfileIdSnapshot() != id.value) return false
        message = "Switch to another profile before deleting this one"
        return true
    }

    /**
     * Same unguarded-throw fix as [setLocked], plus the success message is
     * now actually conditional on success: it was posted unconditionally
     * right after the call, so a reset that threw told the user their
     * browsing data was gone when nothing had been touched.
     */
    fun resetProfile(id: ProfileId) {
        viewModelScope.launch {
            runCatching { profileManager.resetData(id) }
                .onSuccess { message = "Profile browsing data reset" }
                .onFailure { message = it.message ?: "Could not reset profile data" }
        }
    }

    // ---------- Notification permission (API 33+) ----------

    /**
     * Claims this session's single POST_NOTIFICATIONS prompt: true for the
     * first caller, false ever after.
     *
     * The framework answers a twice-denied permission INSTANTLY with no UI,
     * so an onCreate that asks unconditionally re-asks on every Activity
     * recreation — a rotation would re-post the hint below forever. The
     * ViewModel outlives configuration changes, which makes "once per
     * session" the natural scope for both the ask and its explanation.
     */
    fun claimNotificationPrompt(): Boolean {
        if (notificationPromptClaimed) return false
        notificationPromptClaimed = true
        return true
    }

    /**
     * The POST_NOTIFICATIONS prompt came back denied.
     *
     * The launcher used to discard that result entirely, so download
     * completions and the agent's progress notification simply never appeared
     * and nothing said why. The hint rides the normal snackbar channel and
     * carries the only tap that can fix it (an app cannot re-prompt once the
     * user has denied twice — only the system screen can grant it).
     */
    fun onNotificationPermissionDenied() {
        message = "Notifications are off — downloads and agent progress stay silent"
        messageAction = MessageAction.OPEN_NOTIFICATION_SETTINGS
    }

    // ---------- Backup v2: export ----------

    /** True once the vault's session gate has passed (no re-gate needed). */
    private fun vaultUnlocked(): Boolean = graph.credentialRepo.isUnlocked.value

    /**
     * Export step 1 — the dialog confirmed. Anything the selection put behind
     * the device vault (saved logins, authenticator accounts — and the wallet,
     * which is not gated by that vault but is key material all the same) has
     * to be read first, and that reading is gated: if the vault is still
     * locked for this session, the UI gate runs first ([vaultGateRequest]); on
     * failure the export aborts with a message and nothing is built. A
     * selection with none of them raises no gate, because there is nothing to
     * unlock for.
     */
    fun startExport(profile: Profile, sections: ExportSections) {
        exportDraft = ExportDraft(profile, sections)
        pendingExport = null
        passphrasePrompt = null
        pendingExportNote = null
        if (!sections.needsVault) {
            viewModelScope.launch { buildExport(emptyList(), vault = null, totp = null, wallet = null) }
            return
        }
        if (vaultUnlocked()) {
            readVaultForExport()
        } else {
            requestVaultGate { readVaultForExport() }
        }
    }

    /** Export step 2 — the gate passed (or the session was already unlocked):
     *  unlock the repo and read the profile's credentials, authenticator
     *  accounts and wallet. Only the stores the selection asked for are read
     *  at all. */
    private fun readVaultForExport() {
        val draft = exportDraft ?: return
        viewModelScope.launch {
            val outcome = runCatching {
                // The gate just ran (MainScreen); record it for the session.
                graph.credentialRepo.unlock()
                val creds = if (draft.sections.passwords) {
                    graph.credentialRepo.exportAll(draft.profile.id)
                } else {
                    emptyList()
                }
                // The authenticator store has its own lock, and the gate that
                // just ran is the same BiometricGate the 2FA screen opens — the
                // user-presence proof is already made. An export without the
                // 2FA section carries no seeds, so it must not read this store.
                val totp = if (draft.sections.totp) {
                    graph.totpRepo.unlock()
                    graph.totpRepo.exportAll(draft.profile.id).map { entry ->
                        TotpBackup.Entry(
                            issuer = entry.issuer,
                            account = entry.account,
                            secret = entry.secret,
                            algorithm = entry.algorithm,
                            digits = entry.digits,
                            period = entry.period
                        )
                    }
                } else {
                    emptyList()
                }
                // The wallet is not behind the credential vault — its key
                // material sits under the profile's own device key — so this
                // read is not gated by the unlock above. It is still gated by
                // the user-presence check that ran before it, which is what
                // makes handing over a recovery phrase a deliberate act.
                // Contents that could restore nothing are dropped rather than
                // sealed: that block would be a decoy.
                val wallet = if (draft.sections.wallet) {
                    graph.walletRepo.backupContents(draft.profile.id, null).takeIf { !it.isEmpty }
                } else {
                    null
                }
                Triple(creds, totp, wallet)
            }
            outcome.onSuccess { (creds, totp, wallet) ->
                if (creds.isEmpty() && totp.isEmpty() && wallet == null) {
                    // A passwords-only export has nothing to fall back on. The
                    // whole-profile path below writes a file with no sealed
                    // block, which is right for a backup and wrong here: the
                    // user asked for their passwords, and handing them a
                    // settings backup named "passwords" is the silent partial
                    // transfer this feature exists to avoid.
                    if (draft.passwordsOnly) {
                        abortExport("this profile has no saved passwords yet")
                        return@onSuccess
                    }
                    buildExport(creds, vault = null, totp = null, wallet = null)
                    return@onSuccess
                }
                exportCredentials = creds
                exportTotp = totp
                exportWallet = wallet
                passphrasePrompt = PassphrasePrompt(
                    forExport = true,
                    profileName = draft.profile.name,
                    credentialCount = creds.size,
                    totpCount = totp.size,
                    walletPhrase = wallet?.let { walletPhraseFor(it) },
                    id = ++promptSeq
                )
            }.onFailure {
                abortExport(it.message ?: "could not read the profile's saved data")
            }
        }
    }

    /** Export step 3 (only when a selected section had something to seal) —
     *  the passphrase was set: seal each block (plaintext only inside the
     *  ciphers) and continue. One passphrase, up to three independent
     *  ciphertexts; the passphrase CharArray is wiped immediately after
     *  use. */
    fun confirmExportPassphrase(passphrase: String) {
        val creds = exportCredentials
        val totpEntries = exportTotp
        val walletContents = exportWallet
        if (creds.isEmpty() && totpEntries.isEmpty() && walletContents == null) {
            passphrasePrompt = null
            return
        }
        val draft = exportDraft
        if (draft != null && draft.passwordsOnly) {
            sealPasswordExport(draft, creds, passphrase)
            return
        }
        viewModelScope.launch {
            val outcome = runCatching {
                val chars = passphrase.toCharArray()
                // PBKDF2 (210k iterations) is real CPU work — off the main
                // thread; wipe happens on the same worker, right after use.
                withContext(Dispatchers.Default) {
                    try {
                        // Two independent ciphers under ONE passphrase: the
                        // login array and the authenticator accounts. A profile
                        // that holds only one of the two writes only that block.
                        val vault = if (creds.isEmpty()) {
                            null
                        } else {
                            val plaintext = credentialsJson.encodeToString(
                                ListSerializer(SavedCredential.serializer()), creds
                            )
                            ProfileBackup.VaultBackup.from(PasswordVaultCrypto.encrypt(plaintext, chars))
                        }
                        val totp = if (totpEntries.isEmpty()) {
                            null
                        } else {
                            TotpBackup.sealContents(
                                contents = TotpBackup.Contents(totpEntries),
                                header = TotpBackup.Header(
                                    profileLabel = draft?.profile?.name.orEmpty(),
                                    exportedAt = System.currentTimeMillis()
                                ),
                                passphrase = chars
                            )
                        }
                        // The wallet last and under the same passphrase as the
                        // other two, but again as its own ciphertext: one
                        // prompt cannot be made to fail for the wrong block,
                        // and a profile with only a wallet writes only this.
                        val wallet = walletContents?.let { contents ->
                            WalletBackup.sealBlock(
                                contents = contents,
                                header = WalletBackup.Header(
                                    profileLabel = draft?.profile?.name.orEmpty(),
                                    exportedAt = System.currentTimeMillis()
                                ),
                                passphrase = chars
                            )
                        }
                        Triple(vault, totp, wallet)
                    } finally {
                        PasswordVaultCrypto.wipe(chars)
                    }
                }
            }
            passphrasePrompt = null
            outcome
                .onSuccess { (vault, totp, wallet) -> buildExport(creds, vault, totp, wallet) }
                .onFailure {
                    abortExport(it.message ?: "could not seal the profile's saved data")
                }
        }
    }

    /** Export step 4 — assemble the payload from the selected sections (plus
     *  whichever of the three sealed blocks exist) and stage it for
     *  delivery. */
    private suspend fun buildExport(
        creds: List<SavedCredential>,
        vault: ProfileBackup.VaultBackup?,
        totp: ProfileBackup.VaultBackup?,
        wallet: ProfileBackup.VaultBackup?
    ) {
        val draft = exportDraft ?: return
        runCatching {
            val id = draft.profile.id
            val sections = draft.sections
            ProfileBackup.serialize(
                ProfileBackup.BackupPayload(
                    profile = draft.profile,
                    bookmarks = if (sections.bookmarks) {
                        graph.browserRepo.bookmarks(id).map {
                            ProfileBackup.BookmarkExport(it.url, it.title, it.folder, it.position)
                        }
                    } else {
                        emptyList()
                    },
                    sitePermissions = if (sections.sitePermissions) {
                        graph.browserRepo.permissions(id).map {
                            ProfileBackup.SitePermissionExport(it.host, it.permission, it.decision)
                        }
                    } else {
                        emptyList()
                    },
                    siteSettings = if (sections.siteSettings) {
                        graph.browserRepo.allSiteSettings(id).map {
                            ProfileBackup.SiteSettingExport(
                                host = it.host,
                                shieldsDisabled = it.shieldsDisabled,
                                jsEnabled = it.jsEnabled,
                                cookiesBlocked = it.cookiesBlocked,
                                desktopMode = it.desktopMode,
                                autoplayBlocked = it.autoplayBlocked,
                                popupBlocked = it.popupBlocked
                            )
                        }
                    } else {
                        emptyList()
                    },
                    notes = if (sections.notes) {
                        graph.browserRepo.notes(id).map {
                            ProfileBackup.NoteExport(it.title, it.body)
                        }
                    } else {
                        emptyList()
                    },
                    vault = vault,
                    totp = totp,
                    wallet = wallet
                )
            )
        }.onSuccess { json ->
            exportCredentials = emptyList() // plaintext list dropped for good
            exportTotp = emptyList()        // the seeds with it
            exportWallet = null             // and the phrase with those
            exportDraft = null
            pendingExport = PendingExport(
                fileName = exportFileName(draft.profile.name),
                json = json,
                sizeBytes = json.toByteArray(Charsets.UTF_8).size
            )
        }.onFailure {
            abortExport(it.message ?: "could not build the export")
        }
    }

    /**
     * Export step 3 for a passwords-only file: seal just the logins and stage
     * the result.
     *
     * Same PBKDF2 + AES-GCM as the backup path ([confirmExportPassphrase]) and
     * the same wipe discipline, but a different payload: [PasswordTransfer]
     * writes a CSV inside the cipher rather than the profile's JSON, so what
     * comes out is a file that Chrome, Brave or Firefox can also read once the
     * user opens it.
     *
     * The count reported here is the number of logins ACTUALLY in the file,
     * not the number the profile holds. [PasswordTransfer.carryable] drops a
     * login with an empty password (the CSV reader would count it incomplete on
     * the way back in), and the difference is named rather than absorbed: an
     * export that quietly carries less than it claims is how a user deletes a
     * profile believing everything was saved.
     */
    private fun sealPasswordExport(
        draft: ExportDraft,
        creds: List<SavedCredential>,
        passphrase: String
    ) {
        viewModelScope.launch {
            val entries = creds.map {
                PasswordTransfer.Entry(
                    domain = it.domain,
                    username = it.username,
                    password = it.password,
                    title = it.title
                )
            }
            val carried = PasswordTransfer.carryable(entries)
            val dropped = entries.size - carried.size
            if (carried.isEmpty()) {
                abortExport("this profile has no saved passwords to export")
                return@launch
            }
            val outcome = runCatching {
                val chars = passphrase.toCharArray()
                withContext(Dispatchers.Default) {
                    try {
                        PasswordTransfer.seal(PasswordTransfer.Contents(carried), chars)
                    } finally {
                        PasswordVaultCrypto.wipe(chars)
                    }
                }
            }
            passphrasePrompt = null
            outcome.onSuccess { text ->
                exportCredentials = emptyList() // plaintext list dropped for good
                exportWallet = null
                exportDraft = null
                pendingExport = PendingExport(
                    fileName = PasswordTransfer.fileName(draft.profile.name, System.currentTimeMillis()),
                    json = text,
                    sizeBytes = text.toByteArray(Charsets.UTF_8).size
                )
                if (dropped > 0) {
                    // Carried to the delivery message rather than posted now:
                    // this is the one fact that has to reach the user BEFORE
                    // the delete that may follow, and a snackbar posted here
                    // would be replaced by the export's own confirmation.
                    pendingExportNote = "$dropped saved login(s) had no password and were left out — " +
                        "the file holds ${carried.size}"
                }
            }.onFailure {
                abortExport(it.message ?: "could not seal the password file")
            }
        }
    }

    /** What to say when [fileName] reached the disk or the share sheet. */
    private fun exportDeliveredMessage(fileName: String): String = buildString {
        append("Exported \"")
        append(fileName)
        append('"')
        pendingExportNote?.let { append(" — "); append(it) }
    }

    /** SAF save — write the staged export where the user picked. On failure
     *  the export stays staged so Save can be retried or Share used. */
    fun writeExportTo(uri: Uri) {
        val export = pendingExport ?: return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri)
                        ?: error("the selected location is not writable")
                    out.use { it.write(export.json.toByteArray(Charsets.UTF_8)) }
                }
            }.onSuccess {
                pendingExport = null
                message = exportDeliveredMessage(export.fileName)
                afterPasswordExportDelivered()
            }.onFailure {
                message = "Export failed — ${it.message ?: "could not write the file"}"
            }
        }
    }

    /** The staged export was delivered another way (Share): drop it. */
    fun consumePendingExport() {
        pendingExport = null
        afterPasswordExportDelivered()
    }

    /** User canceled at a dialog — drop everything quietly but visibly. */
    fun cancelExport() {
        dropExportState()
        message = "Export canceled"
    }

    /** The SAF picker closed without a location — drop the staged export
     *  silently (the user just backed out; that is not a failure). */
    fun discardExport() {
        dropExportState()
    }

    /** A step of the export failed — abort with a clear message; no file
     *  was (partially) built anywhere. */
    private fun abortExport(reason: String) {
        dropExportState()
        message = "Export aborted — $reason"
    }

    private fun dropExportState() {
        exportDraft = null
        exportCredentials = emptyList()
        exportTotp = emptyList()
        exportWallet = null
        passphrasePrompt = null
        pendingExport = null
        // A cancel or a failure is exactly the case where the pending delete
        // must NOT survive: the profile is the only remaining copy of those
        // passwords, so it stays.
        deleteAfterPasswordExport = null
        pendingExportNote = null
    }

    // ---------- Backup v4: import ----------

    /**
     * SAF import — read the picked file (any JSON picker result), then the
     * same validate-first path as pasted text.
     *
     * The read goes through [readCapped] like every other picked file: the
     * picker accepts anything on the device, and reading a multi-gigabyte one
     * into a String is a crash rather than the "not a Room Browser export"
     * message that belongs here.
     */
    fun readImportFile(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    readCapped(uri, MAX_IMPORT_BYTES, "a profile backup")
                }
            }.onSuccess { importProfile(it) }
                .onFailure {
                    importError = "Import failed — ${it.message ?: "could not read the file"}"
                }
        }
    }

    /**
     * Import step 1 — validate. Anything unsupported is rejected with a
     * dialog BEFORE a single row is written.
     */
    fun importProfile(text: String) {
        if (text.length > MAX_IMPORT_BYTES) {
            // The paste path has no picker in front of it, so the cap has to be
            // here too — a paste of a huge file is the same crash the capped
            // picker read exists to avoid.
            importError = "That text is too large to be a Room Browser export. Nothing was imported."
            return
        }
        when (val parsed = ProfileBackup.parse(text)) {
            is ProfileBackupResult.Malformed ->
                importError = "Not a valid Room Browser export (${parsed.detail}). Nothing was imported."
            is ProfileBackupResult.InvalidVersion ->
                importError =
                    "The export uses format v${parsed.found}, newer than this app supports " +
                        "(v${parsed.maxSupported}). Update the app and try again. Nothing was imported."
            is ProfileBackupResult.Parsed -> {
                importPayload = parsed.payload
                if (
                    parsed.payload.vault == null &&
                    parsed.payload.totp == null &&
                    parsed.payload.wallet == null
                ) {
                    // No sealed block → nothing touches the device keys; the
                    // restore needs no gate and no passphrase.
                    finalizeImport(emptyList())
                } else {
                    // No counts here: the file does not say how many entries
                    // either block carries, and a number is only honest once
                    // the passphrase has opened it.
                    passphrasePrompt = PassphrasePrompt(
                        forExport = false,
                        profileName = parsed.payload.profile.name,
                        credentialCount = 0,
                        id = ++promptSeq
                    )
                }
            }
        }
    }

    /**
     * Import step 2 (a file with a sealed block) — the file's passphrase:
     * decrypt the password vault, the authenticator block and the wallet, or
     * stay in the dialog for a retry. Wrong passphrase NEVER writes anything;
     * a corrupt blob aborts the whole import.
     */
    fun confirmImportPassphrase(passphrase: String) {
        // A sealed passwords file reaches this dialog by the same route as a
        // whole-profile backup — one passphrase prompt, more than one thing it
        // can be about — so the branch is here rather than in the dialog, and the
        // dialog stays untouched by the passwords flow.
        if (passwordImport != null) {
            confirmPasswordImportPassphrase(passphrase)
            return
        }
        val payload = importPayload
        val vault = payload?.vault
        val totpVault = payload?.totp
        val walletVault = payload?.wallet
        if (vault == null && totpVault == null && walletVault == null) {
            passphrasePrompt = null
            return
        }
        viewModelScope.launch {
            val chars = passphrase.toCharArray()
            // Every block is sealed under this one passphrase, so one decrypt
            // round proves it for all of them. The wrong-passphrase retry is the
            // same dialog either way: a VaultAuthException from the FIRST block
            // that exists is the answer, and the rest are only tried once the
            // first has already said the passphrase is right.
            val decrypted = try {
                withContext(Dispatchers.Default) {
                    try {
                        val creds = vault?.let { PasswordVaultCrypto.decrypt(it.toCipherData(), chars) }
                        val totp = totpVault?.let { TotpBackup.openContents(it, chars).entries }
                            ?: emptyList()
                        // The third cipher, opening to the wallet payload an
                        // import restores from. Its plaintext leaves this block
                        // only as the structured payload: the readable document
                        // the format also returns is for a human reading the
                        // file, not for the write path.
                        val wallet = walletVault?.let { WalletBackup.openBlock(it, chars) }
                        Triple(creds, totp, wallet)
                    } finally {
                        PasswordVaultCrypto.wipe(chars)
                    }
                }
            } catch (e: VaultAuthException) {
                // Wrong passphrase — let the user retry in the same dialog.
                // A fresh id goes with it: the retry prompt is otherwise
                // identical to the previous one from the SECOND wrong attempt
                // on, and the dialog's remember key would stop changing (the
                // typed secret would survive into the retry).
                passphrasePrompt = passphrasePrompt?.copy(
                    error = "Wrong passphrase — try again",
                    id = ++promptSeq
                )
                return@launch
            } catch (e: VaultFormatException) {
                passphrasePrompt = null
                importPayload = null
                importError = "A sealed block in that export is corrupt. Nothing was imported."
                return@launch
            } catch (e: TotpBackupFormatException) {
                passphrasePrompt = null
                importPayload = null
                importError = "The export's authenticator accounts are damaged. Nothing was imported."
                return@launch
            } catch (e: WalletBackupFormatException) {
                passphrasePrompt = null
                importPayload = null
                importError = "The export's wallet keys are damaged. Nothing was imported."
                return@launch
            }
            val (credBlob, totpEntries, walletRestored) = decrypted
            val creds = if (credBlob == null) {
                emptyList()
            } else {
                try {
                    credentialsJson.decodeFromString(
                        ListSerializer(SavedCredential.serializer()), credBlob
                    )
                } catch (e: SerializationException) {
                    passphrasePrompt = null
                    importPayload = null
                    importError = "The export's password vault is unreadable. Nothing was imported."
                    return@launch
                }
            }
            passphrasePrompt = null
            // The wallet is deliberately NOT part of this condition: its rows
            // are written under the new profile's own device key, not under the
            // credential vault, so unlocking that vault would be a gate on the
            // wrong thing. The file's passphrase, already proved above, is the
            // user-presence proof this path stands on.
            if ((creds.isNotEmpty() || totpEntries.isNotEmpty()) && !vaultUnlocked()) {
                // Writing into the device vault is gated like reading it. ONE
                // gate covers both stores — the 2FA screen opens the same one.
                requestVaultGate { finalizeImport(creds, totpEntries, walletRestored?.payload) }
            } else {
                finalizeImport(creds, totpEntries, walletRestored?.payload)
            }
        }
    }

    /**
     * Import final step — ONE Room transaction (see
     * ProfileRepositoryImpl.importBackup): profile row + bookmarks + site
     * permissions + site settings + credentials, authenticator accounts and
     * wallet, each re-encrypted under the new profile's own device keys. Any
     * failure rolls the whole import back.
     *
     * Duplicate safety: the import NEVER reuses the file's UUID, so an
     * existing profile can never be overwritten — if the file's UUID or its
     * name already exists locally the import lands as a NEW profile named
     * "<name> (imported)" (with numeric disambiguation), mirroring
     * ProfileManager.duplicate's convention.
     */
    private fun finalizeImport(
        creds: List<SavedCredential>,
        totpEntries: List<TotpBackup.Entry> = emptyList(),
        walletPayload: WalletBackup.Payload? = null
    ) {
        val payload = importPayload ?: return
        viewModelScope.launch {
            runCatching {
                val existing = profileManager.profiles()
                val name = uniqueImportName(
                    base = payload.profile.name,
                    existingNames = existing.map { it.name },
                    needsSuffix = existing.any { it.id == payload.profile.id }
                )
                val now = System.currentTimeMillis()
                val fresh = payload.profile.copy(
                    id = ProfileId.new(), // fresh identity — never overwrite
                    name = name,
                    isLocked = false, // a lock is a device-local concern
                    isDefault = existing.isEmpty(),
                    createdAt = now,
                    lastActiveAt = now
                    // settings + themeJson are preserved verbatim from the
                    // file (the import path's convention: no device
                    // randomization, no theme loss).
                )
                val summary = repo.importBackup(
                    profile = fresh,
                    bookmarks = payload.bookmarks,
                    sitePermissions = payload.sitePermissions,
                    siteSettings = payload.siteSettings,
                    notes = payload.notes,
                    writeCredentials = {
                        // Runs INSIDE the transaction: importAll re-encrypts every
                        // password under THIS device's key for the new profile id
                        // (fresh UUIDs, canonical domains). Skipped entirely when
                        // there is nothing to write, so a vault-less import needs
                        // no unlock. The number it returns is what the summary
                        // reports — it drops a row with no usable domain.
                        if (creds.isEmpty()) {
                            0
                        } else {
                            graph.credentialRepo.unlock()
                            graph.credentialRepo.importAll(fresh.id, creds)
                        }
                    },
                    writeTotp = {
                        // Same rule, different store and key: the seeds the file
                        // carried are re-encrypted under the new profile's 2FA
                        // key, so an imported profile's codes are readable by
                        // this device alone.
                        if (totpEntries.isEmpty()) {
                            0
                        } else {
                            graph.totpRepo.unlock()
                            graph.totpRepo.importAll(fresh.id, totpEntries)
                        }
                    },
                    writeWallet = {
                        // A report rather than a count, because a restore can
                        // take most of a file's keys and skip the rest — and the
                        // skipped ones are named in the summary instead of being
                        // counted as restored.
                        walletPayload?.let { backup ->
                            graph.walletRepo.restore(
                                profileId = fresh.id,
                                payload = backup,
                                enabledChains = derivedChainsOf(backup)
                            )
                        }
                    }
                )
                summary
            }.onSuccess { summary ->
                importPayload = null
                message = importedSummaryLine(summary)
            }.onFailure {
                // Room rolled the transaction back — nothing half-imported.
                importPayload = null
                importError =
                    "Import failed — ${it.message ?: "the import was rolled back"}. Nothing was imported."
            }
        }
    }

    /**
     * The chains a backup's PHRASE was derived for, read off the file itself.
     *
     * The derived entries are exactly the ones that carry no private key — a
     * derived account is re-derived from the phrase, so the export never wrote
     * its key — which makes the file, rather than the importing device, the
     * authority on which chains the phrase produced. A profile restored on a
     * build whose chain list has since changed still gets its own chains back.
     *
     * The name is matched the way the wallet repository matches it: the enum
     * name first, then the display name, because the file stores the label the
     * user saw.
     */
    private fun derivedChainsOf(payload: WalletBackup.Payload): List<ChainType> =
        payload.accounts
            .filter { it.privateKey.isNullOrBlank() }
            .mapNotNull { entry ->
                ChainType.fromName(entry.chain)
                    ?: ChainType.entries.firstOrNull {
                        it.displayName.equals(entry.chain, ignoreCase = true)
                    }
            }
            .distinct()

    /** "<name>" when free, otherwise "<name> (imported)", "… 2", "… 3" —
     *  the same disambiguation ProfileManager.duplicate uses. */
    private fun uniqueImportName(base: String, existingNames: List<String>, needsSuffix: Boolean): String {
        val trimmed = base.trim().ifBlank { "Imported profile" }
        val taken = existingNames.map { it.lowercase() }.toSet()
        if (!needsSuffix && trimmed.lowercase() !in taken) return trimmed
        var candidate = "$trimmed (imported)"
        var i = 2
        while (candidate.lowercase() in taken) {
            candidate = "$trimmed (imported) $i"
            i++
        }
        return candidate
    }

    /**
     * How the wallet is described at the passphrase prompt.
     *
     * The other two blocks get a count, and a count does not work here: a
     * wallet is a recovery phrase, imported private keys, or both, and the
     * user deciding whether to seal this file needs to know which — "0" would
     * be the wrong answer for a wallet whose whole backup is its phrase.
     */
    private fun walletPhraseFor(contents: WalletBackup.Contents): String {
        val keys = contents.accounts.count { !it.privateKey.isNullOrBlank() }
        return when {
            !contents.mnemonic.isNullOrBlank() && keys > 0 ->
                "the recovery phrase and $keys imported private key(s)"
            !contents.mnemonic.isNullOrBlank() -> "the recovery phrase"
            else -> "$keys imported private key(s)"
        }
    }

    /** The user backed out of the passphrase step — cancel the whole
     *  action quietly but visibly. */
    fun cancelPassphrasePrompt() {
        val wasExport = passphrasePrompt?.forExport == true
        passphrasePrompt = null
        dropExportState()
        importPayload = null
        // The passwords import rides this same dialog, so it is abandoned by
        // this same action. The file is not held on to: a passphrase the user
        // backed out of is not a passphrase to keep waiting for.
        passwordImport = null
        message = if (wasExport) "Export canceled" else "Import canceled"
    }

    fun dismissImportError() {
        importError = null
    }

    // ---------- Passwords transfer (import / export) ----------

    /**
     * Export a profile's saved passwords to a sealed file of its own, as
     * opposed to the whole-profile backup that [startExport] builds.
     *
     * @param deleteAfter true when this export is the pre-delete prompt's
     *   "Export" choice: the profile is then deleted only once a file has
     *   actually been delivered — saved or shared — never merely because the
     *   export was attempted.
     */
    fun startPasswordExport(profile: Profile, deleteAfter: Boolean = false) {
        exportDraft = ExportDraft(profile, ExportSections.PASSWORDS_FILE, passwordsOnly = true)
        deleteAfterPasswordExport = if (deleteAfter) profile.id else null
        pendingExport = null
        passphrasePrompt = null
        pendingExportNote = null
        passwordImport = null
        if (vaultUnlocked()) readVaultForExport() else requestVaultGate { readVaultForExport() }
    }

    /** "Import passwords…" — records the destination; the screen opens the picker. */
    fun startPasswordImport(profile: Profile) {
        passwordImport = PasswordImport(profile, sealedText = null)
    }

    /** The picker closed with nothing chosen — stop waiting for a file. */
    fun cancelPasswordImport() {
        passwordImport = null
    }

    /**
     * Reads a content URI into a String, refusing anything past [limit].
     *
     * Hand-rolled rather than `InputStream.readNBytes`, which is API 33 and this
     * app's minSdk is 28. The cap is not politeness: the URI comes from a file
     * picker, so the user can hand us any file on the device, and reading a
     * multi-gigabyte one into a String on the main-process heap is a crash
     * rather than an error message.
     */
    private fun readCapped(uri: Uri, limit: Int, what: String): String {
        val resolver = getApplication<Application>().contentResolver
        val input = resolver.openInputStream(uri)
            ?: error("the selected file could not be opened")
        return input.use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) error("that file is too large to be $what")
                out.write(buffer, 0, read)
            }
            out.toString("UTF-8")
        }
    }

    /**
     * Reads the picked password file and routes it by CONTENT.
     *
     * One entry point serves both "import from Chrome/Brave/Firefox/Edge" and
     * "import from Room Browser", because a sealed export decrypts to exactly
     * the CSV the browsers write — so the second is the first with a passphrase
     * step in front. Detection is on the text, before any passphrase exists,
     * because asking for the passphrase is the screen's first question and it
     * cannot be asked after the fact.
     */
    fun readPasswordFile(uri: Uri) {
        val target = passwordImport?.profile ?: return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    readCapped(uri, MAX_PASSWORD_FILE_BYTES, "a password export")
                }
            }.onSuccess { text -> dispatchPasswordImport(target, text) }
                .onFailure {
                    passwordImport = null
                    importError = "Import failed — ${it.message ?: "the file could not be read"}"
                }
        }
    }

    private fun dispatchPasswordImport(profile: Profile, text: String) {
        if (PasswordTransfer.isSealedFile(text)) {
            passwordImport = PasswordImport(profile, sealedText = text)
            passphrasePrompt = PassphrasePrompt(
                forExport = false,
                // Deliberately not the profile's name: a sealed file carries no
                // profile name outside its encryption, so naming one here would
                // be inventing an origin for it. The dialog words itself
                // differently for this case.
                profileName = "",
                credentialCount = 0,
                passwordsFile = true,
                id = ++promptSeq
            )
            return
        }
        when (val parsed = PasswordCsv.parse(text)) {
            is PasswordCsv.Result.NotAPasswordCsv -> {
                passwordImport = null
                importError = "That file is not a password export, so nothing was imported.\n\n" +
                    "Room Browser reads the CSV that Chrome, Brave, Edge and Firefox " +
                    "export, and the sealed password file it writes itself."
            }
            is PasswordCsv.Result.Parsed -> {
                if (parsed.rows.isEmpty()) {
                    // A real password file that held nothing importable. Reported
                    // with its reasons rather than as a silent success: the user
                    // would otherwise delete an export that was never read.
                    passwordImport = null
                    message = "Nothing to import into \"${profile.name}\" — ${describeSkips(parsed.skipped)}"
                } else if (vaultUnlocked()) {
                    writePasswordImport(profile, parsed.rows, parsed.skipped)
                } else {
                    // Writing into the device vault is gated like reading it.
                    requestVaultGate { writePasswordImport(profile, parsed.rows, parsed.skipped) }
                }
            }
        }
    }

    /**
     * Import step 2 for a sealed file — the passphrase. Mirrors
     * [confirmImportPassphrase]'s retry contract exactly: a wrong passphrase
     * keeps the dialog open with a fresh id, and nothing is written.
     */
    private fun confirmPasswordImportPassphrase(passphrase: String) {
        val pending = passwordImport ?: return
        val text = pending.sealedText ?: return
        viewModelScope.launch {
            val chars = passphrase.toCharArray()
            val contents = try {
                withContext(Dispatchers.Default) {
                    try {
                        PasswordTransfer.open(text, chars)
                    } finally {
                        PasswordVaultCrypto.wipe(chars)
                    }
                }
            } catch (e: VaultAuthException) {
                passphrasePrompt = passphrasePrompt?.copy(
                    error = "Wrong passphrase — try again",
                    id = ++promptSeq
                )
                return@launch
            } catch (e: PasswordTransferFormatException) {
                passphrasePrompt = null
                passwordImport = null
                importError = "${e.message ?: "That file is not a Room Browser password export"}." +
                    " Nothing was imported."
                return@launch
            }
            passphrasePrompt = null
            val rows = contents.entries.map {
                PasswordCsv.Row(
                    domain = it.domain,
                    username = it.username,
                    password = it.password,
                    title = it.title
                )
            }
            if (rows.isEmpty()) {
                passwordImport = null
                message = "Nothing to import into \"${pending.profile.name}\" — that file held no passwords"
            } else if (vaultUnlocked()) {
                writePasswordImport(pending.profile, rows, emptyMap())
            } else {
                requestVaultGate { writePasswordImport(pending.profile, rows, emptyMap()) }
            }
        }
    }

    /**
     * Import final step — decide what is new, then write.
     *
     * The merge happens here rather than in the insert because the insert
     * cannot see the profile's existing logins: `importAll` mints a fresh row
     * id per entry by design, so a plain re-import of the same file would save
     * every password twice. See [PasswordImportMerge] for what counts as the
     * same login and why a changed password is kept rather than overwritten.
     */
    private fun writePasswordImport(
        profile: Profile,
        rows: List<PasswordCsv.Row>,
        skipped: Map<PasswordCsv.SkipReason, Int>
    ) {
        viewModelScope.launch {
            runCatching {
                graph.credentialRepo.unlock()
                val plan = PasswordImportMerge.plan(
                    existing = graph.credentialRepo.exportAll(profile.id),
                    incoming = rows
                )
                if (plan.fresh.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    graph.credentialRepo.importAll(
                        profile.id,
                        plan.fresh.map { row ->
                            SavedCredential(
                                // Minted inside importAll — an import can never
                                // collide with, or overwrite, an existing row.
                                id = "",
                                profileId = profile.id.value,
                                domain = row.domain,
                                username = row.username,
                                password = row.password,
                                title = row.title,
                                createdAt = now,
                                updatedAt = now
                            )
                        }
                    )
                }
                Triple(plan, skipped, profile)
            }.onSuccess { (plan, skips, target) ->
                passwordImport = null
                message = describeImport(target, plan, skips)
            }.onFailure {
                passwordImport = null
                importError = "Import failed — ${it.message ?: "nothing was imported"}"
            }
        }
    }

    /**
     * The one-line result. Every row of the file is accounted for in it —
     * imported, already saved, kept beside a different saved password, or
     * skipped for a named reason. A partial import reported as a total one is
     * how a user ends up deleting the export they still needed.
     */
    private fun describeImport(
        profile: Profile,
        plan: PasswordImportMerge.Plan,
        skipped: Map<PasswordCsv.SkipReason, Int>
    ): String {
        val parts = mutableListOf(
            if (plan.fresh.size == 1) "1 password imported" else "${plan.fresh.size} passwords imported"
        )
        if (plan.duplicates > 0) parts += "${plan.duplicates} already saved"
        if (plan.conflicts > 0) {
            parts += "${plan.conflicts} added beside a different saved password for the same site"
        }
        val skippedText = describeSkips(skipped)
        val head = parts.joinToString(", ")
        return if (skippedText.isEmpty()) {
            "$head into \"${profile.name}\""
        } else {
            "$head into \"${profile.name}\" — skipped $skippedText"
        }
    }

    /** The skip counters as words; empty when nothing was skipped. */
    private fun describeSkips(skipped: Map<PasswordCsv.SkipReason, Int>): String {
        if (skipped.isEmpty()) return ""
        return skipped.entries
            .sortedBy { it.key.ordinal }
            .mapNotNull { (reason, count) ->
                val what = when (reason) {
                    PasswordCsv.SkipReason.ENCRYPTED -> "locked by a Firefox primary password"
                    PasswordCsv.SkipReason.NO_URL -> "with no site"
                    PasswordCsv.SkipReason.UNSUPPORTED_URL -> "for non-web origins"
                    PasswordCsv.SkipReason.INCOMPLETE -> "with no password"
                }
                count.takeIf { it > 0 }?.let { "$it $what" }
            }
            .joinToString(", ")
    }

    // ---------- Deleting a profile, which its passwords do not survive ----------

    /**
     * The delete action asks first.
     *
     * `ProfileRepositoryImpl.delete` destroys the profile's credential rows and
     * the Keystore key that decrypts them, in that order and in one step, so a
     * password offered for export AFTER the delete has nothing left to read.
     * Hence a prompt that runs before it — and hence this method, which is what
     * the delete button now calls.
     *
     * The count needs an unlocked vault. When it is locked the count is left
     * null rather than unlocking to ask a question, and the prompt says "may
     * have" instead of naming a number: a profile whose passwords we could not
     * count is exactly the one most worth warning about.
     */
    fun requestDeleteProfile(profile: Profile) {
        viewModelScope.launch {
            if (refuseDeleteOfActiveProfile(profile.id)) return@launch
            val count = runCatching {
                if (!vaultUnlocked()) null else graph.credentialRepo.exportAll(profile.id).size
            }.getOrNull()
            // A count, not a secret, so this needs no unlock: the warning must
            // not go quiet just because the screen happens to be locked.
            val totp = runCatching { graph.totpRepo.countForProfile(profile.id) }.getOrDefault(0)
            deletePrompt = DeletePrompt(profile, count, totp)
        }
    }

    /** "Cancel" — the profile stays, nothing is exported. */
    fun dismissDeletePrompt() {
        deletePrompt = null
    }

    /** "Delete without exporting" — the user has been told what that costs. */
    fun confirmDeleteWithoutExport() {
        val profile = deletePrompt?.profile ?: return
        deletePrompt = null
        deleteProfile(profile.id)
    }

    /** "Export first" — the export flow runs, and deletes only on delivery. */
    fun confirmDeleteWithExport() {
        val profile = deletePrompt?.profile ?: return
        deletePrompt = null
        startPasswordExport(profile, deleteAfter = true)
    }

    /**
     * A staged passwords export was delivered (saved to a file, or shared).
     * Carries out a pending delete — and only here, because a file that was
     * never written is not a backup.
     */
    private fun afterPasswordExportDelivered() {
        val id = deleteAfterPasswordExport ?: return
        deleteAfterPasswordExport = null
        deleteProfile(
            id,
            buildString {
                append("Passwords exported")
                pendingExportNote?.let { append(" — "); append(it) }
                append(" — profile deleted with all its data")
            }
        )
    }

    // ---------- Vault gate plumbing (the screen owns BiometricGate) ----------

    private fun requestVaultGate(andThen: () -> Unit) {
        afterGate = andThen
        vaultGateRequest = VaultGateRequest(++gateSeq)
    }

    /** The screen reports the biometric gate's outcome. A failed gate aborts
     *  whatever asked for it — the vault stays locked, nothing is read,
     *  written or built. */
    fun onVaultGateResult(success: Boolean) {
        val andThen = afterGate
        afterGate = null
        vaultGateRequest = null
        if (!success) {
            dropExportState()
            importPayload = null
            // A gate that could not run is not a reason to forget the file:
            // but the file cannot be READ without the vault either, so the
            // import is abandoned and can be started again from the menu.
            passwordImport = null
            message = "Vault not unlocked — nothing was exported or imported"
            return
        }
        andThen?.invoke()
    }

    // ---------- Export file naming ----------

    /** "room-browser-profile-<sanitized name>.json" — safe for any file
     *  picker: letters/digits/-/_ only, blank names fall back. */
    private fun exportFileName(profileName: String): String {
        val safe = profileName.map { c ->
            if (c.isLetterOrDigit() || c == '-' || c == '_') c else '-'
        }.joinToString("").trim('-').take(40)
        return "room-browser-profile-${if (safe.isBlank()) "export" else safe}.json"
    }

    // ---------- External link routing ----------

    fun consumeExternalUrl() {
        viewModelScope.launch {
            appState.setExternalUrl(null)
            pendingExternalUrl = null
        }
    }

    fun submitExternalUrl(url: String?) {
        pendingExternalUrl = url
        viewModelScope.launch { appState.setExternalUrl(url) }
    }

    fun isUrl(text: String): Boolean = UrlIntelligence.looksLikeUrl(text)

    fun setFirstRunDone() {
        viewModelScope.launch { appState.setFirstRunDone() }
    }

    /** Drops the message AND whatever follow-up it carried — the action must
     *  never outlive the message it belonged to. */
    fun clearMessage() {
        message = null
        messageAction = null
    }
}

/**
 * The largest password file this app will read into memory.
 *
 * Chosen against the real thing rather than a round number: a browser's
 * `passwords.csv` is a few hundred KB for a heavy user (each row is well under
 * 200 bytes), and our own sealed export adds a base64 armoured GCM blob over
 * that CSV — still comfortably inside a megabyte. Eight megabytes is far above
 * any real file and far below the point where reading it costs anything.
 */
private const val MAX_PASSWORD_FILE_BYTES = 8 * 1024 * 1024

/**
 * The largest profile export this app will read into memory.
 *
 * A whole-profile backup is bigger than a password file by nature: it carries
 * bookmarks, history-derived settings, notes and now the authenticator
 * accounts, all as JSON before the sealed blocks. Sixteen megabytes leaves an
 * order of magnitude of headroom over any real profile while keeping a
 * mis-picked file (a video, a disk image) from being slurped into a heap that
 * cannot hold it. The paste path is capped with the same number — it has no
 * picker in front of it at all.
 */
private const val MAX_IMPORT_BYTES = 16 * 1024 * 1024
