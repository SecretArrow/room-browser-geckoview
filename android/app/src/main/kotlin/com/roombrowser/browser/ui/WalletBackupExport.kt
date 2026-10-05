package com.roombrowser.browser.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.roombrowser.browser.wallet.WalletEngineApi
import com.roombrowser.domain.credentials.PasswordVaultCrypto
import com.roombrowser.domain.export.WalletBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shortest passphrase accepted, matching the profile export's own floor. */
private const val MIN_BACKUP_PASSPHRASE = 8

/**
 * The whole wallet-keys export: passphrase → system file picker → sealed file.
 *
 * Rendered only while [open]; every exit path (written, cancelled, nothing to
 * back up, failure) calls [onDone], so the caller owns a single boolean.
 *
 * [onExported] fires on the ONE path where the file was actually written, and
 * nowhere else — a cancelled picker, a seal failure and a write failure all
 * leave it silent. It is what lets a caller treat "backed up" as something
 * this app witnessed rather than something the user asserted.
 *
 * [mnemonicInHand] is the freshly generated phrase during onboarding, where
 * the session is deliberately still locked. Everywhere else it is null and
 * the phrase is read from the vault, which requires an unlocked session.
 *
 * The file is sealed AFTER the user has chosen a destination, so a cancelled
 * picker costs no PBKDF2 work and cannot leave a half-written file behind.
 * That does mean the passphrase is held across the picker being open; it is
 * wiped as soon as the cipher has consumed it.
 */
@Composable
internal fun WalletBackupFlow(
    open: Boolean,
    engine: WalletEngineApi,
    walletLabel: String,
    profileLabel: String,
    mnemonicInHand: String?,
    onMessage: (String) -> Unit,
    onExported: () -> Unit = {},
    onDone: () -> Unit
) {
    if (!open) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Held as CharArray so the copy this code owns can be wiped; see the note
    // in the dialog below for the copy it cannot.
    var passphrase by remember { mutableStateOf<CharArray?>(null) }
    var working by remember { mutableStateOf(false) }

    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val secret = passphrase
        passphrase = null
        if (uri == null || secret == null) {
            onDone()
            return@rememberLauncherForActivityResult
        }
        working = true
        scope.launch {
            val outcome = runCatching {
                val contents = engine.backupContents(mnemonicInHand)
                require(!contents.isEmpty) {
                    "This wallet has no recovery phrase and no imported keys to back up"
                }
                val header = WalletBackup.Header(
                    profileLabel = profileLabel.ifBlank { "this profile" },
                    exportedAt = System.currentTimeMillis()
                )
                // PBKDF2 at 210k iterations is real CPU work; the seal runs
                // off the main thread and the passphrase is wiped on the same
                // worker, right after the cipher has taken what it needs.
                val text = withContext(Dispatchers.Default) {
                    try {
                        WalletBackup.seal(contents, header, secret)
                    } finally {
                        PasswordVaultCrypto.wipe(secret)
                    }
                }
                withContext(Dispatchers.IO) {
                    val out = context.contentResolver.openOutputStream(uri)
                        ?: error("the chosen location could not be opened for writing")
                    out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
                "Wallet keys exported"
            }
            working = false
            onMessage(
                outcome.fold(
                    onSuccess = { it },
                    // What failed, not just that something did: "no space
                    // left" and "permission denied" are the same sentence
                    // otherwise, and only one is worth retrying elsewhere.
                    onFailure = { "Export failed: ${it.message ?: it.javaClass.simpleName}" }
                )
            )
            // Only a written file counts as a backup; every failure above is
            // already reported through the message and leaves this silent.
            if (outcome.isSuccess) onExported()
            onDone()
        }
    }

    BackupPassphraseDialog(
        enabled = !working,
        onConfirm = { chosen ->
            passphrase = chosen.toCharArray()
            saver.launch(WalletBackup.fileName(walletLabel, System.currentTimeMillis()))
        },
        onDismiss = {
            passphrase?.let { PasswordVaultCrypto.wipe(it) }
            passphrase = null
            onDone()
        }
    )
}

/**
 * Choose the passphrase the file is sealed with. Two fields, a length floor,
 * and a plain statement that forgetting it is unrecoverable — because it is:
 * the file is the only copy of the phrase that survives the phone, and
 * nothing on this device can open it afterwards.
 */
@Composable
private fun BackupPassphraseDialog(
    enabled: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val tooShort = passphrase.isNotEmpty() && passphrase.length < MIN_BACKUP_PASSPHRASE
    val mismatch = confirmation.isNotEmpty() && confirmation != passphrase
    val valid = passphrase.length >= MIN_BACKUP_PASSPHRASE && passphrase == confirmation

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Password for the backup file") },
        text = {
            // Scrollable, because this body does not fit everywhere: the
            // warning paragraph plus two password fields overflow a landscape
            // dialog and a large font scale, and an AlertDialog's text slot
            // CLIPS what it cannot fit. The second field — and with it the
            // only way to satisfy "Choose location…" — simply vanished, with
            // no hint that anything was below the fold.
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding()
            ) {
                Text(
                    "Your recovery phrase and imported keys will be sealed into one " +
                        "text file under this password. Without it the file cannot be " +
                        "opened - by you, or by anyone else - so store it somewhere " +
                        "other than next to the file."
                )
                Spacer(Modifier.height(12.dp))
                // The typed characters live in a String here, because that is
                // what a TextField holds and there is no way to keep them out
                // of one. The CharArray handed to the cipher is the copy this
                // code owns, and that one is wiped.
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Password (min $MIN_BACKUP_PASSPHRASE characters)") },
                    singleLine = true,
                    enabled = enabled,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = tooShort,
                    supportingText = if (tooShort) {
                        { Text("At least $MIN_BACKUP_PASSPHRASE characters") }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = { Text("Repeat password") },
                    singleLine = true,
                    enabled = enabled,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = mismatch,
                    supportingText = if (mismatch) {
                        { Text("Passwords do not match", color = MaterialTheme.colorScheme.error) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(passphrase) }, enabled = valid && enabled) {
                Text("Choose location…")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
