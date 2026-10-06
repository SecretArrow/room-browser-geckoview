package com.roombrowser.browser.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.roombrowser.browser.wallet.RestoreReport
import com.roombrowser.browser.wallet.WalletEngineApi
import com.roombrowser.domain.credentials.PasswordVaultCrypto
import com.roombrowser.domain.credentials.VaultAuthException
import com.roombrowser.domain.export.WalletBackup
import com.roombrowser.domain.export.WalletBackupFormatException
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomCard
import com.roombrowser.ui.common.RoomCardShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * The whole wallet-keys restore: system file picker → passphrase → what the
 * file says → restore.
 *
 * WHY THE PREVIEW EXISTS AND IS NOT SKIPPABLE: the user is about to replace
 * an empty profile with a wallet built from a file they picked out of their
 * Downloads folder. Showing them the accounts the file names — and saying
 * plainly when it carries no recovery phrase, because that is a wallet whose
 * funds cannot be recovered from words — is the difference between a restore
 * and a guess. A wrong passphrase and a wrong file both fail here, before
 * anything is written.
 *
 * THE FILE NEVER LEAVES THE DEVICE. It is read through the content resolver,
 * decrypted with PBKDF2 + AES-GCM in this process, and nothing in this path
 * opens a socket. There is no server side to this feature and no telemetry
 * carrying any part of it.
 *
 * [enabledChains] decides which derived accounts are created when the file
 * carries a phrase; keys imported as private keys are restored on their own
 * chain regardless, because that chain is what the key IS.
 */
@Composable
internal fun WalletBackupImportFlow(
    engine: WalletEngineApi,
    onRestored: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var fileText by remember { mutableStateOf<String?>(null) }
    var fileName by remember { mutableStateOf("") }
    var restored by remember { mutableStateOf<WalletBackup.Restored?>(null) }
    var chains by remember { mutableStateOf(DefaultWalletChains) }
    var failure by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<RestoreReport?>(null) }

    val picker = rememberLauncherForActivityResult(
        // Any type on purpose: the file this app writes is a .txt, but a
        // provider that reports it as octet-stream would hide it from a
        // narrower filter, and a file the user cannot select is a feature
        // they cannot use. The format check below is what actually decides.
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            onCancel()
            return@rememberLauncherForActivityResult
        }
        working = true
        failure = null
        scope.launch {
            val read = runCatching { withContext(Dispatchers.IO) { readBackupFile(context, uri) } }
            working = false
            read.onSuccess { text ->
                fileText = text
                fileName = displayName(context, uri)
            }.onFailure { failure = it.message ?: "the file could not be read" }
        }
    }

    // The picker opens once, on first composition: there is nothing to show
    // before a file is chosen, so a "choose a file" screen in between would
    // only be a screen with one button on it.
    LaunchedEffect(Unit) { picker.launch(arrayOf("*/*")) }

    report?.let { done ->
        RestoreDoneScreen(report = done, onContinue = onRestored)
        return
    }

    val decrypted = restored
    if (decrypted != null) {
        RestorePreviewScreen(
            restored = decrypted,
            fileName = fileName,
            chains = chains,
            working = working,
            failure = failure,
            onToggleChain = { chain ->
                chains = if (chain in chains) chains - chain else chains + chain
            },
            onConfirm = {
                working = true
                failure = null
                scope.launch {
                    val outcome = runCatching {
                        engine.restoreFromBackup(decrypted.payload, chains.toList())
                    }
                    working = false
                    outcome.onSuccess { result ->
                        if (!result.restoredAnything) {
                            // The file decrypted but held nothing usable —
                            // reported, not silently accepted as a success.
                            failure = "That backup held no wallet this build could restore"
                        } else {
                            report = result
                        }
                    }.onFailure { e ->
                        failure = e.message ?: "the restore failed"
                    }
                }
            },
            onBack = {
                fileText = null
                restored = null
                failure = null
                picker.launch(arrayOf("*/*"))
            }
        )
        return
    }

    UnlockBackupScreen(
        fileName = fileName,
        busy = working,
        failure = failure,
        onUnlock = { passphrase ->
            val text = fileText
            if (text == null) {
                onCancel()
                return@UnlockBackupScreen
            }
            working = true
            failure = null
            scope.launch {
                // PBKDF2 at 210k iterations is real CPU work on a file that
                // may be several KB; it runs off the main thread and the
                // passphrase is wiped on the same worker straight after.
                val outcome = withContext(Dispatchers.Default) {
                    try {
                        runCatching { WalletBackup.open(text, passphrase) }
                    } finally {
                        PasswordVaultCrypto.wipe(passphrase)
                    }
                }
                working = false
                outcome.onSuccess { restored = it }
                    .onFailure { e -> failure = describeOpenFailure(e) }
            }
        },
        onCancel = onCancel
    )
}

/**
 * Turns the three ways opening can fail into three different sentences.
 *
 * They are genuinely different situations and the user's next move differs
 * for each: a wrong passphrase is retyped, a foreign file is swapped, and a
 * corrupted one is re-downloaded from wherever it came from.
 */
private fun describeOpenFailure(e: Throwable): String = when (e) {
    is VaultAuthException ->
        "That password did not open this file. Check it and try again."
    is WalletBackupFormatException ->
        "That is not a Room Browser wallet backup. ${e.message ?: ""}".trim()
    else -> "Could not read that file: ${e.message ?: e.javaClass.simpleName}"
}

/** Largest backup this flow will read into memory. Real files are a few KB. */
private const val MAX_BACKUP_BYTES = 1 shl 20 // 1 MiB

/**
 * Reads the chosen document, refusing anything implausibly large.
 *
 * WHY A CEILING: the picker accepts any file, and a multi-gigabyte video
 * renamed to .txt would otherwise be read into a String and take the app down
 * with it. A wallet backup is a few kilobytes of text; a megabyte is already
 * three orders of magnitude of headroom.
 */
private fun readBackupFile(context: android.content.Context, uri: Uri): String {
    val stream: InputStream = context.contentResolver.openInputStream(uri)
        ?: throw WalletBackupFormatException("the chosen file could not be opened")
    return stream.use { readCapped(it, MAX_BACKUP_BYTES) }
}

private fun readCapped(stream: InputStream, limit: Int): String {
    val buffer = ByteArray(16 * 1024)
    val out = java.io.ByteArrayOutputStream()
    while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        if (out.size() + read > limit) {
            throw WalletBackupFormatException(
                "that file is far too large to be a wallet backup"
            )
        }
        out.write(buffer, 0, read)
    }
    return String(out.toByteArray(), Charsets.UTF_8)
}

private fun displayName(context: android.content.Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

/** Step 2: the passphrase the file was sealed with. */
@Composable
private fun UnlockBackupScreen(
    fileName: String,
    busy: Boolean,
    failure: String?,
    onUnlock: (CharArray) -> Unit,
    onCancel: () -> Unit
) {
    val extras = LocalRoomExtras.current
    var passphrase by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
            .imePadding()
    ) {
        Spacer(Modifier.height(28.dp))
        Text(
            "Open your backup",
            style = MaterialTheme.typography.titleLarge,
            color = extras.textPrimary
        )
        Spacer(Modifier.height(8.dp))
        WalletInfoNote(
            "Enter the password you chose when you exported this file. It is " +
                "checked on this device — the file and everything in it stay here."
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .heightIn(min = 40.dp)
                    .clip(RoomCardShape)
                    .background(extras.surfaceAlt.copy(alpha = 0.7f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Description,
                    contentDescription = null,
                    tint = extras.icon,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.textSecondary
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text("Backup password") },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        failure?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { onUnlock(passphrase.toCharArray()) },
            enabled = !busy && passphrase.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Text("Open backup")
            }
        }
        Spacer(Modifier.height(6.dp))
        TextButton(
            onClick = onCancel,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Cancel") }
        Spacer(Modifier.height(24.dp))
    }
}

/** Step 3: exactly what the file holds, before anything is written. */
@Composable
private fun RestorePreviewScreen(
    restored: WalletBackup.Restored,
    fileName: String,
    chains: Set<ChainType>,
    working: Boolean,
    failure: String?,
    onToggleChain: (ChainType) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit
) {
    val extras = LocalRoomExtras.current
    val payload = restored.payload
    val importedKeys = payload.accounts.filter { !it.privateKey.isNullOrBlank() }
    val hasPhrase = !payload.mnemonic.isNullOrBlank()

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            "Check this is your wallet",
            style = MaterialTheme.typography.titleLarge,
            color = extras.textPrimary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "$fileName · wallet \"${payload.walletLabel}\"",
            style = MaterialTheme.typography.bodySmall,
            color = extras.textSecondary
        )
        Spacer(Modifier.height(12.dp))

        RoomCard(Modifier.fillMaxWidth(), withGradient = false) {
            Column(Modifier.padding(14.dp)) {
                SummaryLine(
                    "Recovery phrase",
                    if (hasPhrase) {
                        val count = payload.mnemonic!!.trim().split(Regex("\\s+")).size
                        "$count words"
                    } else {
                        "none in this file"
                    }
                )
                SummaryLine(
                    "Accounts listed",
                    "${payload.accounts.size}",
                    last = importedKeys.isEmpty()
                )
                if (importedKeys.isNotEmpty()) {
                    SummaryLine("Imported private keys", "${importedKeys.size}", last = true)
                }
            }
        }

        if (!hasPhrase) {
            Spacer(Modifier.height(10.dp))
            WarningNote(
                "This backup has no recovery phrase. Only the private keys in " +
                    "it can be restored, and nothing else can ever recover those " +
                    "accounts — keep the file."
            )
        }
        if (restored.legacy) {
            Spacer(Modifier.height(10.dp))
            WarningNote(
                "This file was written by an older version of the app. It is " +
                    "read from the text below, which cannot carry everything a " +
                    "newer backup does — check the accounts after restoring."
            )
        }

        if (hasPhrase && chains.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Chains to derive",
                style = MaterialTheme.typography.labelMedium,
                color = extras.textSecondary
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChainType.entries.forEach { chain ->
                    FilterChip(
                        selected = chain in chains,
                        onClick = { onToggleChain(chain) },
                        label = { Text(chain.displayName) }
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "What the file says",
            style = MaterialTheme.typography.labelMedium,
            color = extras.textSecondary
        )
        Spacer(Modifier.height(6.dp))
        RoomCard(Modifier.fillMaxWidth(), withGradient = false) {
            Text(
                WalletBackup.readablePart(restored.document),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = extras.textPrimary,
                modifier = Modifier.padding(14.dp)
            )
        }

        failure?.let {
            Spacer(Modifier.height(10.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onConfirm,
            enabled = !working && (hasPhrase || importedKeys.isNotEmpty()),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            if (working) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("Restore this wallet")
            }
        }
        Spacer(Modifier.height(6.dp))
        TextButton(
            onClick = onBack,
            enabled = !working,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Choose a different file") }
        Spacer(Modifier.height(24.dp))
    }
}

/** Step 4: what actually happened. */
@Composable
private fun RestoreDoneScreen(report: RestoreReport, onContinue: () -> Unit) {
    val extras = LocalRoomExtras.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(28.dp))
        Text(
            "Wallet restored",
            style = MaterialTheme.typography.titleLarge,
            color = extras.textPrimary
        )
        Spacer(Modifier.height(12.dp))
        RoomCard(Modifier.fillMaxWidth(), withGradient = false) {
            Column(Modifier.padding(14.dp)) {
                SummaryLine(
                    "Wallet",
                    report.walletLabel,
                    last = report.derivedAccountCount == 0 && report.importedAccountCount == 0
                )
                if (report.phraseRestored) {
                    SummaryLine(
                        "Derived from the phrase",
                        "${report.derivedAccountCount} account(s)",
                        last = report.importedAccountCount == 0
                    )
                }
                if (report.importedAccountCount > 0) {
                    SummaryLine(
                        "Imported keys restored",
                        "${report.importedAccountCount}",
                        last = true
                    )
                }
            }
        }
        if (report.skipped.isNotEmpty()) {
            // Named one by one: "some keys could not be restored" tells the
            // user nothing about which funds are missing.
            Spacer(Modifier.height(10.dp))
            WarningNote(
                "${report.skipped.size} key(s) in this file could not be " +
                    "restored:\n" + report.skipped.joinToString("\n") {
                    "• ${it.chain} ${it.label} — ${it.reason}"
                }
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Balances appear as the wallet reconnects. Check the addresses " +
                "against wherever you sent funds before using it.",
            style = MaterialTheme.typography.bodySmall,
            color = extras.textSecondary
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) { Text("Open my wallet") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SummaryLine(label: String, value: String, last: Boolean = false) {
    val extras = LocalRoomExtras.current
    Row(Modifier.fillMaxWidth().padding(bottom = if (last) 0.dp else 8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = extras.textSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, color = extras.textPrimary)
    }
}

@Composable
private fun WarningNote(text: String) {
    val extras = LocalRoomExtras.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoomCardShape)
            .background(extras.surfaceAlt.copy(alpha = 0.8f))
            .padding(12.dp)
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = extras.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = extras.textSecondary,
            modifier = Modifier.weight(1f)
        )
    }
}
