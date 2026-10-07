package com.roombrowser.browser.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.roombrowser.RoomBrowserApp
import com.roombrowser.data.repo.TotpRepository
import com.roombrowser.domain.credentials.PasswordVaultCrypto
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.security.PinLockCrypto
import com.roombrowser.domain.security.ProfileLockGate
import com.roombrowser.domain.security.WalletLockStatus
import com.roombrowser.domain.theme.BuiltInThemes
import com.roombrowser.domain.totp.TotpBackup
import com.roombrowser.domain.totp.TotpEntry
import com.roombrowser.domain.totp.TotpGenerator
import com.roombrowser.main.PassphrasePrompt
import com.roombrowser.security.BiometricGate
import com.roombrowser.security.PinUnlockResult
import com.roombrowser.security.WalletLockManager
import com.roombrowser.ui.common.EmptyState
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomBrowserTheme
import com.roombrowser.ui.common.RoomCard
import com.roombrowser.ui.common.RoomCardShape
import com.roombrowser.ui.common.VaultPassphraseDialog
import com.roombrowser.ui.common.copySensitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 2FA Management — the profile's authenticator accounts, with the code, a
 * countdown ring and copy/edit/delete.
 *
 * PROCESS: the DEFAULT process, like [NotesActivity] — nothing here touches the
 * engine, and the vault-style lock is this process's own [TotpRepository]
 * instance.
 *
 * The window is FLAG_SECURE (this screen only): a code is a live credential and
 * would otherwise show up in a screenshot or the recents thumbnail. Nothing
 * else in the app sets it.
 */
class TwoFactorActivity : FragmentActivity() {

    /** What the editor reported on the way out; cleared once announced. */
    private var editorMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        val profileIdValue = intent.getStringExtra(EXTRA_PROFILE_ID)
        if (profileIdValue.isNullOrBlank()) {
            finish()
            return
        }
        val profileId = ProfileId(profileIdValue)
        val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME).orEmpty()
        val graph = (application as RoomBrowserApp).graph
        val biometricsAvailable = BiometricGate.canAuthenticate(this)
        val editor = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            editorMessage =
                result.data?.getStringExtra(TwoFactorEditorActivity.EXTRA_RESULT_MESSAGE)
        }
        setContent {
            var spec by remember { mutableStateOf(BuiltInThemes.default()) }
            LaunchedEffect(Unit) {
                runCatching { graph.profileRepo.getProfile(profileId) }.getOrNull()?.let { profile ->
                    spec = BuiltInThemes.resolveOrDefault(profile.themeJson)
                }
            }
            RoomBrowserTheme(spec = spec) {
                TwoFactorRoot(
                    profileId = profileId,
                    profileName = profileName,
                    repo = graph.totpRepo,
                    lockManager = graph.walletLock,
                    biometricsAvailable = biometricsAvailable,
                    editorMessage = editorMessage,
                    onEditorMessageShown = { editorMessage = null },
                    onAddAccount = { TwoFactorEditorActivity.launch(this, profileId.value, null) },
                    onEditAccount = {
                        TwoFactorEditorActivity.launch(this, profileId.value, it.id)
                    },
                    onClose = { finish() },
                    onUnlockRequest = {
                        BiometricGate.unlock(
                            this,
                            "2FA codes",
                            { graph.totpRepo.unlock() },
                            { }
                        )
                    },
                    onEnsureUnlocked = { onReady, onFailure ->
                        // UI gate only: it guards the screen, not the ciphertext.
                        when {
                            graph.totpRepo.isUnlocked.value -> onReady()
                            biometricsAvailable -> BiometricGate.unlock(
                                this,
                                "2FA codes",
                                {
                                    graph.totpRepo.unlock()
                                    onReady()
                                },
                                onFailure
                            )
                            else -> {
                                graph.totpRepo.unlock()
                                onReady()
                            }
                        }
                    }
                )
            }
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_PROFILE_NAME = "profile_name"

        fun launch(context: Context, profileId: String, profileName: String) {
            context.startActivity(
                Intent(context, TwoFactorActivity::class.java)
                    .putExtra(EXTRA_PROFILE_ID, profileId)
                    .putExtra(EXTRA_PROFILE_NAME, profileName)
            )
        }
    }
}

/** How the list is ordered. */
private enum class TotpSort { RECENT, AZ, ZA }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TwoFactorRoot(
    profileId: ProfileId,
    profileName: String,
    repo: TotpRepository,
    lockManager: WalletLockManager,
    biometricsAvailable: Boolean,
    editorMessage: String?,
    onEditorMessageShown: () -> Unit,
    onAddAccount: () -> Unit,
    onEditAccount: (TotpEntry) -> Unit,
    onClose: () -> Unit,
    onUnlockRequest: () -> Unit,
    onEnsureUnlocked: (onReady: () -> Unit, onFailure: () -> Unit) -> Unit
) {
    val extras = LocalRoomExtras.current
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val unlocked by repo.isUnlocked.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Gate order: device credential first; with no screen lock the profile PIN
    // if one is set, and only the warning banner when the store positively says
    // there is no PIN. A null pinEnabled means the record could not be read —
    // fail closed onto the PIN pane rather than unlocking.
    var pinEnabled by remember { mutableStateOf<Boolean?>(null) }
    var pinStatus by remember { mutableStateOf<WalletLockStatus>(WalletLockStatus.Ready) }
    var pinError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(biometricsAvailable, profileId) {
        if (repo.isUnlocked.value) return@LaunchedEffect
        if (biometricsAvailable) {
            onUnlockRequest()
            return@LaunchedEffect
        }
        pinEnabled = runCatching { lockManager.policy(profileId.value) }.getOrNull()?.pinEnabled
        pinStatus = runCatching { lockManager.status(profileId.value) }
            .getOrDefault(WalletLockStatus.Ready)
        if (pinEnabled == false) repo.unlock()
    }

    // A wrong PIN never leaves the pane; it only advances the retry counter
    // (persisted in app_state, so killing the app does not reset it).
    fun submitPin(pin: String) {
        val chars = pin.toCharArray()
        scope.launch {
            val result = runCatching { lockManager.verifyPin(profileId.value, chars) }
            PinLockCrypto.wipe(chars)
            when (val outcome = result.getOrNull()) {
                PinUnlockResult.Unlocked -> {
                    pinError = null
                    runCatching { lockManager.clearFailures(profileId.value) }
                    repo.unlock()
                }
                is PinUnlockResult.Wrong -> {
                    pinStatus = outcome.status
                    pinError = if (outcome.attemptsUntilBackoff > 0) {
                        "Wrong PIN. ${outcome.attemptsUntilBackoff} attempts left before a delay."
                    } else {
                        "Wrong PIN."
                    }
                }
                is PinUnlockResult.Backoff -> {
                    pinStatus = outcome.status
                    pinError = "Too many attempts — wait for the delay to end."
                }
                PinUnlockResult.NoPin -> {
                    // The store positively reports no PIN now: fall to the banner.
                    pinEnabled = false
                    pinError = null
                    repo.unlock()
                }
                null -> pinError = "Could not check the PIN. Try again."
            }
        }
    }

    // ONE ticker for the whole screen, aligned to the wall clock. It runs only
    // while RESUMED, so a backgrounded screen computes no HMAC at all. Rows read
    // it through a lambda rather than a value, which keeps the per-second
    // recomposition inside the row instead of the whole list.
    var tick by remember { mutableStateOf(System.currentTimeMillis() / 1_000L) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                tick = System.currentTimeMillis() / 1_000L
                delay(1_000L - System.currentTimeMillis() % 1_000L)
            }
        }
    }

    var entries by remember { mutableStateOf<List<TotpEntry>>(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(TotpSort.RECENT) }
    var detailsOf by remember { mutableStateOf<TotpEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<TotpEntry?>(null) }

    LaunchedEffect(editorMessage) {
        val message = editorMessage ?: return@LaunchedEffect
        onEditorMessageShown()
        snackbarHostState.showSnackbar(message)
    }

    LaunchedEffect(unlocked, lifecycle) {
        if (!unlocked) {
            entries = emptyList()
        } else {
            runCatching {
                repo.observe(profileId)
                    .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                    .collect { entries = it }
            }
        }
    }

    fun copyCode(entry: TotpEntry, code: String) {
        copySensitive(context, "OTP code", code)
        scope.launch { runCatching { repo.markUsed(entry.id) } }
        scope.launch {
            snackbarHostState.showSnackbar(
                "Code for ${entry.issuer.ifBlank { entry.account }} copied"
            )
        }
    }

    val profileLabel = profileName.ifBlank { "profile" }
    var promptSeq by remember { mutableStateOf(0) }
    var exportMenuOpen by remember { mutableStateOf(false) }
    var exportEntries by remember { mutableStateOf<List<TotpEntry>>(emptyList()) }
    var exportText by remember { mutableStateOf<String?>(null) }
    var exportSaveName by remember { mutableStateOf("") }
    var exportPrompt by remember { mutableStateOf<PassphrasePrompt?>(null) }

    fun showMessage(text: String) {
        scope.launch { snackbarHostState.showSnackbar(text) }
    }

    val createDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val text = exportText
        val name = exportSaveName
        exportText = null
        if (uri == null || text == null) {
            showMessage("Export canceled")
        } else {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val out = context.contentResolver.openOutputStream(uri)
                            ?: error("the selected location is not writable")
                        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    }
                }.onSuccess { showMessage("Exported \"$name\"") }
                    .onFailure {
                        showMessage("Export failed — ${it.message ?: "could not write the file"}")
                    }
            }
        }
    }

    fun startExport() {
        onEnsureUnlocked(
            {
                scope.launch {
                    runCatching { repo.exportAll(profileId) }
                        .onSuccess { all ->
                            if (all.isEmpty()) {
                                // Sealing nothing would write a decoy file.
                                showMessage("This profile has no 2FA accounts to export")
                            } else {
                                exportEntries = all
                                exportPrompt = PassphrasePrompt(
                                    forExport = true,
                                    profileName = profileLabel,
                                    credentialCount = 0,
                                    totpCount = all.size,
                                    titleOverride = "Export 2FA passphrase",
                                    id = ++promptSeq
                                )
                            }
                        }
                        .onFailure {
                            showMessage(
                                "Could not read the accounts — ${it.message ?: "2FA is locked"}"
                            )
                        }
                }
            },
            { showMessage("Unlock 2FA to export") }
        )
    }

    fun confirmExportPassphrase(passphrase: String) {
        val entries = exportEntries
        if (entries.isEmpty()) {
            exportPrompt = null
            return
        }
        scope.launch {
            val chars = passphrase.toCharArray()
            val sealResult = runCatching {
                withContext(Dispatchers.Default) {
                    try {
                        TotpBackup.seal(
                            contents = TotpBackup.Contents(
                                entries.map { entry ->
                                    TotpBackup.Entry(
                                        issuer = entry.issuer,
                                        account = entry.account,
                                        secret = entry.secret,
                                        algorithm = entry.algorithm,
                                        digits = entry.digits,
                                        period = entry.period
                                    )
                                }
                            ),
                            header = TotpBackup.Header(
                                profileLabel = profileLabel,
                                exportedAt = System.currentTimeMillis()
                            ),
                            passphrase = chars
                        )
                    } finally {
                        PasswordVaultCrypto.wipe(chars)
                    }
                }
            }
            exportEntries = emptyList()
            exportPrompt = null
            sealResult.onSuccess { text ->
                exportText = text
                exportSaveName = TotpBackup.fileName(profileLabel, System.currentTimeMillis())
                createDocLauncher.launch(exportSaveName)
            }.onFailure {
                showMessage("Export failed — ${it.message ?: "could not seal the file"}")
            }
        }
    }

    val needle = query.trim().lowercase()
    val visible = entries
        .filter {
            needle.isEmpty() ||
                it.issuer.lowercase().contains(needle) ||
                it.account.lowercase().contains(needle)
        }
        .let { filtered ->
            when (sort) {
                TotpSort.AZ -> filtered.sortedWith(
                    compareBy({ it.issuer.lowercase() }, { it.account.lowercase() })
                )
                TotpSort.ZA -> filtered.sortedWith(
                    compareByDescending<TotpEntry> { it.issuer.lowercase() }
                        .thenByDescending { it.account.lowercase() }
                )
                TotpSort.RECENT -> filtered.sortedWith(
                    compareByDescending<TotpEntry> { it.lastUsedAt ?: Long.MIN_VALUE }
                        .thenBy { it.issuer.lowercase() }
                )
            }
        }

    Scaffold(
        modifier = Modifier.imePadding(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(
                snackbarHostState,
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Bottom)
                )
            )
        },
        topBar = {
            TopAppBar(
                title = { Text("2FA Management") },
                navigationIcon = {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.semantics { contentDescription = "Close 2FA" }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick = onAddAccount,
                        modifier = Modifier.semantics { contentDescription = "Add 2FA" }
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                    }
                    Box {
                        IconButton(
                            onClick = { exportMenuOpen = true },
                            modifier = Modifier.semantics { contentDescription = "2FA options" }
                        ) {
                            Icon(Icons.Filled.MoreVert, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = exportMenuOpen,
                            onDismissRequest = { exportMenuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Export 2FA") },
                                onClick = {
                                    exportMenuOpen = false
                                    startExport()
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
        ) {
            when (ProfileLockGate.choose(biometricsAvailable, pinEnabled)) {
                ProfileLockGate.DEVICE_CREDENTIAL -> if (!unlocked) {
                    LockedTwoFactorPane(
                        description = "Unlock with your fingerprint, face or device PIN to " +
                            "view this profile's authenticator codes.",
                        onUnlock = onUnlockRequest
                    )
                    return@Column
                }

                ProfileLockGate.PIN -> if (!unlocked) {
                    LockedTwoFactorPane(
                        description = "This profile has its own PIN.",
                        onUnlock = {},
                        pinSection = {
                            WalletPinUnlockSection(
                                status = pinStatus,
                                error = pinError,
                                onPinSubmit = { submitPin(it) },
                                description = "Enter this profile's PIN to view its " +
                                    "authenticator codes.",
                                fieldLabel = "Profile PIN",
                                submitLabel = "Unlock 2FA"
                            )
                        }
                    )
                    return@Column
                }

                ProfileLockGate.UNPROTECTED -> NoScreenLockBanner()
            }
            if (entries.isEmpty()) {
                EmptyState(
                    "No 2FA accounts yet",
                    "Codes are generated on this device and belong to " +
                        "\"${profileName.ifBlank { "this profile" }}\"."
                )
                Button(
                    onClick = onAddAccount,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 4.dp)
                        .heightIn(min = 48.dp)
                ) { Text("Add 2FA") }
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search accounts") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    shape = RoundedCornerShape((extras.radius * 0.6f).dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SortChip("Recently used", sort == TotpSort.RECENT) { sort = TotpSort.RECENT }
                    SortChip("A-Z", sort == TotpSort.AZ) { sort = TotpSort.AZ }
                    SortChip("Z-A", sort == TotpSort.ZA) { sort = TotpSort.ZA }
                }
                if (visible.isEmpty()) {
                    EmptyState("No matches", "No account matches \"${query.trim()}\".")
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(visible, key = { it.id }) { entry ->
                            TotpRow(
                                entry = entry,
                                clock = { tick },
                                onCopy = { code -> copyCode(entry, code) },
                                onEdit = { onEditAccount(entry) },
                                onDetails = { detailsOf = entry },
                                onDelete = { deleteTarget = entry }
                            )
                        }
                    }
                }
            }
        }
    }

    exportPrompt?.let { prompt ->
        VaultPassphraseDialog(
            prompt = prompt,
            onConfirm = { confirmExportPassphrase(it) },
            onDismiss = {
                exportPrompt = null
                exportEntries = emptyList()
            }
        )
    }

    detailsOf?.let { entry ->
        TwoFactorDetailsDialog(
            entry = entry,
            clock = { tick },
            onDismiss = { detailsOf = null }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete 2FA account?") },
            text = {
                Text(
                    "Delete \"${target.issuer.ifBlank { target.account }}\"? " +
                        "Its setup key is removed from this device and this cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            runCatching { repo.delete(profileId, target.id) }
                                .onSuccess { snackbarHostState.showSnackbar("Account deleted") }
                                .onFailure {
                                    snackbarHostState.showSnackbar("Could not delete account")
                                }
                        }
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortChip(label: String, selected: Boolean, onSelect: () -> Unit) {
    FilterChip(selected = selected, onClick = onSelect, label = { Text(label) })
}

/**
 * Shown on a device with no screen lock. The screen still opens — there is no
 * credential to ask for — so this says plainly what that means rather than
 * letting the absence of a prompt read as security.
 */
@Composable
private fun NoScreenLockBanner() {
    val extras = LocalRoomExtras.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "This device has no screen lock, so these codes are not protected. " +
                "Set a PIN, pattern or password in system settings.",
            style = MaterialTheme.typography.bodySmall,
            color = extras.textSecondary
        )
    }
}

@Composable
private fun LockedTwoFactorPane(
    description: String,
    onUnlock: () -> Unit,
    pinSection: (@Composable () -> Unit)? = null
) {
    val extras = LocalRoomExtras.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoomCardShape)
                .background(extras.surfaceAlt.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = extras.primary,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "2FA locked",
            style = MaterialTheme.typography.titleMedium,
            color = extras.textPrimary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = extras.textSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        if (pinSection != null) {
            pinSection()
        } else {
            Button(onClick = onUnlock, modifier = Modifier.heightIn(min = 48.dp)) { Text("Unlock") }
        }
    }
}

/**
 * One account. [clock] is read HERE (not in the list) so a tick recomposes this
 * row and not the whole screen, and the HMAC behind the digits is keyed on the
 * step index — it runs once per period, not once per second.
 */
@Composable
private fun TotpRow(
    entry: TotpEntry,
    clock: () -> Long,
    onCopy: (String) -> Unit,
    onEdit: () -> Unit,
    onDetails: () -> Unit,
    onDelete: () -> Unit
) {
    val extras = LocalRoomExtras.current
    val tick = clock()
    val nowMillis = tick * 1_000L
    val code = remember(entry.id, tick / entry.period) {
        runCatching { TotpGenerator.generate(entry, nowMillis) }.getOrNull()
    }
    val secondsLeft = TotpGenerator.secondsRemaining(nowMillis, entry.period)
    var menuOpen by remember { mutableStateOf(false) }
    val label = entry.issuer.ifBlank { entry.account }

    RoomCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        withGradient = false
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 8.dp, end = 2.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    color = extras.textPrimary
                )
                if (entry.issuer.isNotBlank() && entry.account.isNotBlank()) {
                    Text(
                        entry.account,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = extras.textSecondary
                    )
                }
                Text(
                    code ?: "------",
                    style = MaterialTheme.typography.headlineSmall,
                    color = extras.textPrimary
                )
            }
            CountdownRing(secondsLeft = secondsLeft, period = entry.period)
            IconButton(
                onClick = { code?.let(onCopy) },
                enabled = code != null,
                modifier = Modifier.semantics { contentDescription = "Copy OTP for $label" }
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = extras.icon)
            }
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.semantics { contentDescription = "More options for $label" }
                ) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null, tint = extras.icon)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Copy OTP") },
                        enabled = code != null,
                        onClick = {
                            menuOpen = false
                            code?.let(onCopy)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("View details") },
                        onClick = {
                            menuOpen = false
                            onDetails()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

/**
 * Seconds left in the current step, as a ring. It turns red for the last five
 * seconds, when a code about to expire is worth noticing.
 */
@Composable
private fun CountdownRing(secondsLeft: Int, period: Int) {
    val extras = LocalRoomExtras.current
    val progress = (secondsLeft.toFloat() / period.toFloat()).coerceIn(0f, 1f)
    val ringColor = if (secondsLeft <= 5) MaterialTheme.colorScheme.error else extras.primary
    val trackColor = extras.surfaceAlt.copy(alpha = 0.6f)
    Canvas(Modifier.size(28.dp)) {
        val stroke = 3.dp.toPx()
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke)
        )
        drawArc(
            color = ringColor,
            startAngle = -90f,
            sweepAngle = 360f * progress,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

/** Issuer, account, parameters and the setup key — on request, never by default. */
@Composable
private fun TwoFactorDetailsDialog(entry: TotpEntry, clock: () -> Long, onDismiss: () -> Unit) {
    val extras = LocalRoomExtras.current
    val tick = clock()
    var secretShown by remember { mutableStateOf(false) }
    val code = remember(entry.id, tick / entry.period) {
        runCatching { TotpGenerator.generate(entry, tick * 1_000L) }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.issuer.ifBlank { entry.account }) },
        text = {
            Column {
                DetailLine("Account", entry.account.ifBlank { "—" })
                DetailLine("Code", code ?: "—")
                DetailLine("Type", "TOTP · ${entry.algorithm.name}")
                DetailLine("Digits", entry.digits.toString())
                DetailLine("Refresh", "every ${entry.period} s")
                Spacer(Modifier.height(10.dp))
                if (secretShown) {
                    DetailLine("Setup key", entry.secret)
                    Text(
                        "Anyone with this key can generate these codes. " +
                            "Treat it like the account's password.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    OutlinedButton(
                        onClick = { secretShown = true },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Show setup key") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    val extras = LocalRoomExtras.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = extras.textSecondary,
            modifier = Modifier.width(88.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = extras.textPrimary
        )
    }
}
