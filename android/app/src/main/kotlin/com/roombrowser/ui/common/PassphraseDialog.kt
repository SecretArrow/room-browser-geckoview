package com.roombrowser.ui.common

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.roombrowser.main.PassphrasePrompt

/**
 * The passphrase step shared by export (set a NEW passphrase, two fields,
 * min length, must match) and import (enter the FILE's passphrase, one
 * field, inline retry on wrong passphrase). Password fields, imePadding so
 * the keyboard never covers them.
 */
@Composable
internal fun VaultPassphraseDialog(
    prompt: PassphrasePrompt,
    onConfirm: (passphrase: String) -> Unit,
    onDismiss: () -> Unit
) {
    // Keyed by the prompt's id so EVERY new prompt — including every retry —
    // starts with empty fields. Keying on the prompt VALUE was not enough:
    // from the second wrong passphrase on, the retry prompt is a byte-
    // identical copy of the previous one, the key never changed and the
    // rejected secret stayed in the field.
    var passphrase by remember(prompt.id) { mutableStateOf("") }
    var confirmation by remember(prompt.id) { mutableStateOf("") }
    val mismatch = prompt.forExport && confirmation.isNotEmpty() && confirmation != passphrase
    // One sentence covers every sealed block: a profile may carry logins, its
    // authenticator accounts, a wallet, or any combination, and "0 saved
    // passwords will be sealed" is a false statement about a 2FA-only export.
    // The wallet contributes its own wording rather than a count — a phrase is
    // not an item, and this is the line that tells the user they are about to
    // put their money in a file.
    val sealedWhat = buildList {
        if (prompt.credentialCount > 0) {
            add(
                "${prompt.credentialCount} saved " +
                    (if (prompt.credentialCount == 1) "password" else "passwords")
            )
        }
        if (prompt.totpCount > 0) {
            add(
                "${prompt.totpCount} authenticator " +
                    (if (prompt.totpCount == 1) "account" else "accounts")
            )
        }
        prompt.walletPhrase?.let { add(it) }
    }.joinToString(" and ")
    val valid = if (prompt.forExport) {
        passphrase.length >= MIN_EXPORT_PASSPHRASE && passphrase == confirmation
    } else {
        passphrase.isNotEmpty()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                prompt.titleOverride
                    ?: if (prompt.forExport) "Export passphrase" else "Enter file passphrase"
            )
        },
        text = {
            // Scrollable: with the keyboard up, two password fields and their
            // error lines, a landscape dialog cannot show this body at once —
            // unscrollable, the "Repeat passphrase" field was unreachable.
            Column(
                Modifier
                    .imePadding()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    prompt.bodyOverride ?: if (prompt.forExport) {
                        "$sealedWhat of \"${prompt.profileName}\" will be sealed under this " +
                            "passphrase. You will need it on the receiving device — it cannot " +
                            "be recovered."
                    } else {
                        // A passwords file carries no profile name, so naming
                        // one here would be inventing an origin.
                        if (prompt.passwordsFile) {
                            "This is a sealed Room Browser password file. Enter the " +
                                "passphrase it was exported with."
                        } else {
                            // What is inside is not known until the right
                            // passphrase opens it — say what is certain (the
                            // file is sealed) rather than naming a block that
                            // this particular export may not carry.
                            "\"${prompt.profileName}\" was exported with its saved data sealed. " +
                                "Enter the passphrase it was exported with."
                        }
                    }
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = {
                        Text(
                            if (prompt.forExport) "Passphrase (min $MIN_EXPORT_PASSPHRASE chars)"
                            else "Passphrase"
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = prompt.error != null,
                    supportingText = prompt.error?.let { error ->
                        { Text(error, color = MaterialTheme.colorScheme.error) }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (prompt.forExport) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        label = { Text("Repeat passphrase") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = mismatch,
                        supportingText = if (mismatch) {
                            { Text("Passphrases do not match", color = MaterialTheme.colorScheme.error) }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(passphrase) }, enabled = valid) {
                Text(if (prompt.forExport) "Seal & export" else "Unlock & import")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Minimum length of a NEW export passphrase (the file's own passphrase is
 *  only checked by decryption — an importer never re-enforces this). */
private const val MIN_EXPORT_PASSPHRASE = 8
