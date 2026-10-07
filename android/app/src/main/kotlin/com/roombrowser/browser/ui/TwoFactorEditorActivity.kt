package com.roombrowser.browser.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.roombrowser.RoomBrowserApp
import com.roombrowser.data.repo.TotpRepository
import com.roombrowser.domain.credentials.PasswordVaultCrypto
import com.roombrowser.domain.credentials.VaultAuthException
import com.roombrowser.domain.credentials.VaultFormatException
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.theme.BuiltInThemes
import com.roombrowser.domain.totp.Base32
import com.roombrowser.domain.totp.OtpAuthUri
import com.roombrowser.domain.totp.TotpAlgorithm
import com.roombrowser.domain.totp.TotpBackup
import com.roombrowser.domain.totp.TotpBackupFormatException
import com.roombrowser.domain.totp.TotpEntry
import com.roombrowser.main.PassphrasePrompt
import com.roombrowser.qr.QrScannerActivity
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomBrowserTheme
import com.roombrowser.ui.common.VaultPassphraseDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Add / edit ONE 2FA account. Its OWN activity, in the default process like
 * [TwoFactorActivity] — no engine state is involved, and the account rows are
 * plain Room data.
 *
 * Four ways in, all ending at the same validated form: scan the QR, paste an
 * `otpauth://` link, type the setup key, or import a sealed 2FA file.
 *
 * FLAG_SECURE like the list, for the same reason: the form shows a live setup
 * key, which is the seed itself and must not reach a screenshot or the recents
 * thumbnail. Scoped to this window, never set globally.
 *
 * EXTRA_ENTRY_ID absent → add mode; present → edit mode, and the row is
 * re-read from the repository rather than carried across the Intent (the entry
 * holds the decrypted secret, and a stale copy could overwrite a concurrent
 * edit).
 */
class TwoFactorEditorActivity : ComponentActivity() {

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
        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID)
        val graph = (application as RoomBrowserApp).graph
        setContent {
            var spec by remember { mutableStateOf(BuiltInThemes.default()) }
            LaunchedEffect(Unit) {
                runCatching { graph.profileRepo.getProfile(profileId) }.getOrNull()?.let { profile ->
                    spec = BuiltInThemes.resolveOrDefault(profile.themeJson)
                }
            }
            RoomBrowserTheme(spec = spec) {
                TwoFactorEditorRoot(
                    profileId = profileId,
                    entryId = entryId,
                    repo = graph.totpRepo,
                    onClose = { finish() },
                    onDone = { message ->
                        setResult(
                            Activity.RESULT_OK,
                            Intent().putExtra(EXTRA_RESULT_MESSAGE, message)
                        )
                        finish()
                    }
                )
            }
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_ENTRY_ID = "totp_entry_id"
        const val EXTRA_RESULT_MESSAGE = "totp_editor_message"

        /** [entryId] null opens the add form; anything else edits that account. */
        fun launch(context: Context, profileId: String, entryId: String?) {
            context.startActivity(
                Intent(context, TwoFactorEditorActivity::class.java)
                    .putExtra(EXTRA_PROFILE_ID, profileId)
                    .putExtra(EXTRA_ENTRY_ID, entryId)
            )
        }
    }
}

private const val MAX_TOTP_FILE_BYTES = 4 * 1024 * 1024

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TwoFactorEditorRoot(
    profileId: ProfileId,
    entryId: String?,
    repo: TotpRepository,
    onClose: () -> Unit,
    onDone: (String) -> Unit
) {
    val extras = LocalRoomExtras.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fieldShape = RoundedCornerShape((extras.radius * 0.6f).dp)
    val editShape = fieldShape

    // In add mode there is nothing to read; in edit mode the row is loaded
    // before the form exists so the fields are seeded once from real data.
    var loading by remember { mutableStateOf(entryId != null) }
    var loadFailed by remember { mutableStateOf(false) }
    var initial by remember { mutableStateOf<TotpEntry?>(null) }
    LaunchedEffect(profileId, entryId) {
        val id = entryId ?: return@LaunchedEffect
        val loaded = runCatching { repo.get(profileId, id) }.getOrNull()
        loadFailed = loaded == null
        initial = loaded
        loading = false
    }

    var issuer by remember(initial) { mutableStateOf(initial?.issuer.orEmpty()) }
    var account by remember(initial) { mutableStateOf(initial?.account.orEmpty()) }
    var secret by remember(initial) { mutableStateOf(initial?.secret.orEmpty()) }
    var algorithm by remember(initial) { mutableStateOf(initial?.algorithm ?: TotpAlgorithm.SHA1) }
    var digits by remember(initial) { mutableStateOf((initial?.digits ?: 6).toString()) }
    var period by remember(initial) { mutableStateOf((initial?.period ?: 30).toString()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var promptSeq by remember { mutableStateOf(0) }
    var importText by remember { mutableStateOf<String?>(null) }
    var importPrompt by remember { mutableStateOf<PassphrasePrompt?>(null) }

    fun report(message: String) {
        error = message
    }

    fun applyParsed(text: String) {
        runCatching { OtpAuthUri.parse(text) }
            .onSuccess { parsed ->
                issuer = parsed.issuer.orEmpty()
                account = parsed.account
                secret = parsed.secret
                algorithm = parsed.algorithm
                digits = parsed.digits.toString()
                period = parsed.period.toString()
            }
            .onFailure { report("That is not a usable 2FA link: ${it.message}") }
    }

    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_TEXT)
        if (!text.isNullOrBlank()) applyParsed(text) else report("No QR code was read")
    }

    val pickFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    readCappedText(context, uri, MAX_TOTP_FILE_BYTES, "a two-factor file")
                }
            }.onSuccess { text ->
                val envelopeCheck = runCatching { TotpBackup.isSealedFile(text) }
                if (envelopeCheck.getOrDefault(false)) {
                    importText = text
                    importPrompt = PassphrasePrompt(
                        forExport = false,
                        profileName = "",
                        credentialCount = 0,
                        titleOverride = "Import 2FA accounts",
                        bodyOverride = "This is a sealed Room Browser two-factor file. " +
                            "Enter the passphrase it was exported with.",
                        id = ++promptSeq
                    )
                } else {
                    report(
                        envelopeCheck.exceptionOrNull()?.message
                            ?: "That file is not a Room Browser two-factor export"
                    )
                }
            }.onFailure {
                report("Import failed — ${it.message ?: "the file could not be read"}")
            }
        }
    }

    fun confirmImportPassphrase(passphrase: String) {
        val text = importText ?: return
        val prompt = importPrompt ?: return
        scope.launch {
            val chars = passphrase.toCharArray()
            val opened = runCatching {
                withContext(Dispatchers.Default) {
                    try {
                        TotpBackup.open(text, chars)
                    } finally {
                        PasswordVaultCrypto.wipe(chars)
                    }
                }
            }
            opened.onSuccess { contents ->
                val newEntries = contents.entries
                if (newEntries.isEmpty()) {
                    onDone("That file carried no accounts. Nothing was imported.")
                    return@onSuccess
                }
                importPrompt = null
                importText = null
                busy = true
                runCatching { repo.importAll(profileId, newEntries) }
                    .onSuccess {
                        onDone(
                            if (newEntries.size == 1) "1 account imported"
                            else "${newEntries.size} accounts imported"
                        )
                    }
                    .onFailure {
                        busy = false
                        report("Import failed — ${it.message ?: "nothing was imported"}")
                    }
            }.onFailure { failure ->
                when (failure) {
                    is VaultAuthException -> importPrompt = prompt.copy(
                        error = "Wrong passphrase — try again",
                        id = ++promptSeq
                    )
                    is TotpBackupFormatException, is VaultFormatException -> {
                        importPrompt = null
                        importText = null
                        report("That two-factor file is damaged. Nothing was imported.")
                    }
                    else -> {
                        importPrompt = null
                        importText = null
                        report("Import failed — ${failure.message ?: "nothing was imported"}")
                    }
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(if (entryId == null) "Add 2FA account" else "Edit 2FA account") },
                navigationIcon = {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.semantics { contentDescription = "Close 2FA editor" }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth()) {
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.systemBars
                                .union(WindowInsets.displayCutout)
                                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            val key = secret.filterNot(Char::isWhitespace).uppercase()
                            val digitCount = digits.toIntOrNull() ?: 0
                            val step = period.toIntOrNull() ?: 0
                            when {
                                account.isBlank() -> report("An account name is required")
                                key.isEmpty() -> report("A setup key is required")
                                runCatching { Base32.decode(key) }.isFailure ->
                                    report("That setup key is not valid Base32")
                                digitCount != 6 && digitCount != 8 ->
                                    report("Digits must be 6 or 8")
                                step !in 1..300 ->
                                    report("The period must be between 1 and 300 seconds")
                                busy -> Unit
                                else -> {
                                    busy = true
                                    error = null
                                    val draftIssuer = issuer.trim()
                                    val draftAccount = account.trim()
                                    val draftAlgorithm = algorithm
                                    val draftId = initial?.id
                                    scope.launch {
                                        runCatching {
                                            repo.save(
                                                profileId = profileId,
                                                issuer = draftIssuer,
                                                account = draftAccount,
                                                secret = key,
                                                algorithm = draftAlgorithm,
                                                digits = digitCount,
                                                period = step,
                                                id = draftId
                                            )
                                        }.onSuccess {
                                            onDone(
                                                if (draftId == null) "Account added"
                                                else "Account updated"
                                            )
                                        }.onFailure {
                                            busy = false
                                            report("Could not save the account")
                                        }
                                    }
                                }
                            }
                        },
                        enabled = !busy && !loadFailed,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) { Text(if (entryId == null) "Add" else "Update") }
                    OutlinedButton(
                        onClick = onClose,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) { Text("Cancel") }
                }
            }
        }
    ) { padding ->
        when {
            loading -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { CircularProgressIndicator() }

            // An edit that could not read its row must not fall back to a
            // blank form: saving that would write a new account over the id.
            loadFailed -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("This account could not be opened.", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "It may have been deleted, or 2FA may be locked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .windowInsetsPadding(
                        WindowInsets.systemBars
                            .union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Horizontal)
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                if (entryId == null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                scanLauncher.launch(Intent(context, QrScannerActivity::class.java))
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Scan QR")
                        }
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as ClipboardManager
                                val pasted = clipboard.primaryClip
                                    ?.takeIf { it.itemCount > 0 }
                                    ?.getItemAt(0)
                                    ?.coerceToText(context)
                                    ?.toString()
                                if (pasted.isNullOrBlank()) {
                                    report("The clipboard is empty")
                                } else {
                                    applyParsed(pasted)
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                        ) { Text("Paste link") }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            pickFileLauncher.launch(arrayOf("text/plain", "application/json", "*/*"))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Import from file")
                    }
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = issuer,
                    onValueChange = { issuer = it },
                    label = { Text("Service (optional)") },
                    singleLine = true,
                    shape = editShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "totp_issuer_field" }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = account,
                    onValueChange = { account = it },
                    label = { Text("Account") },
                    singleLine = true,
                    shape = editShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "totp_account_field" }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text("Setup key (Base32)") },
                    singleLine = true,
                    shape = editShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "totp_secret_field" }
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TotpAlgorithm.entries.forEach { option ->
                        FilterChip(
                            selected = algorithm == option,
                            onClick = { algorithm = option },
                            label = { Text(option.name) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = digits,
                        onValueChange = { digits = it.filter(Char::isDigit).take(1) },
                        label = { Text("Digits") },
                        singleLine = true,
                        shape = editShape,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = period,
                        onValueChange = { period = it.filter(Char::isDigit).take(3) },
                        label = { Text("Period (s)") },
                        singleLine = true,
                        shape = editShape,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    importPrompt?.let { prompt ->
        VaultPassphraseDialog(
            prompt = prompt,
            onConfirm = { confirmImportPassphrase(it) },
            onDismiss = {
                importPrompt = null
                importText = null
            }
        )
    }
}

private fun readCappedText(context: Context, uri: Uri, limit: Int, what: String): String {
    val input = context.contentResolver.openInputStream(uri)
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
