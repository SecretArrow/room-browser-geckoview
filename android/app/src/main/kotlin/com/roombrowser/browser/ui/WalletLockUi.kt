package com.roombrowser.browser.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.roombrowser.domain.security.PinLockCrypto
import com.roombrowser.domain.security.WalletLockStatus
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomBottomSheetShape
import com.roombrowser.ui.common.RoomSheetHeader
import com.roombrowser.ui.common.SettingActionRow
import kotlinx.coroutines.delay

/**
 * The PIN form on the locked pane, for the profile lock shared by the wallet and
 * the 2FA screen. Rendered only when this profile has a PIN configured.
 * [onUseDevice] is the recovery path where a device credential exists; it is
 * null on a device that has none, where the PIN is the only gate. The dots field
 * is disabled inside the backoff window, and the window is recomputed from its
 * absolute end time so it survives recreation.
 */
@Composable
fun WalletPinUnlockSection(
    status: WalletLockStatus,
    error: String?,
    onPinSubmit: (String) -> Unit,
    description: String = "Enter this wallet's PIN to unlock it.",
    fieldLabel: String = "Wallet PIN",
    submitLabel: String = "Unlock with PIN",
    onUseDevice: (() -> Unit)? = null
) {
    val extras = LocalRoomExtras.current
    val fieldShape = RoundedCornerShape((extras.radius * 0.6f).dp)
    var pin by remember { mutableStateOf("") }
    val backoff = status as? WalletLockStatus.Backoff
    var remainingMs by remember(status) { mutableStateOf(backoff?.remainingMs ?: 0L) }

    LaunchedEffect(status) {
        val window = status as? WalletLockStatus.Backoff ?: return@LaunchedEffect
        while (true) {
            val left = window.untilMs - System.currentTimeMillis()
            remainingMs = left.coerceAtLeast(0L)
            if (left <= 0L) break
            delay(1_000L)
        }
    }

    val ready = backoff == null || remainingMs <= 0L

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = extras.textSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { value ->
                if (value.length <= 12 && value.all { it.isDigit() }) pin = value
            },
            label = { Text(fieldLabel) },
            singleLine = true,
            enabled = ready,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            shape = fieldShape,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "$fieldLabel field" }
        )
        if (!ready) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Too many attempts — try again in ${remainingMs / 1000 + 1}s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                onPinSubmit(pin)
                pin = ""
            },
            enabled = ready && pin.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) { Text(submitLabel) }
        onUseDevice?.let { useDevice ->
            TextButton(onClick = useDevice) { Text("Use device unlock instead") }
        }
    }
}

/**
 * Set / change / remove the wallet PIN. Reachable only from the UNLOCKED
 * dashboard, so the user has already passed a gate this session — no second
 * device prompt is needed to change or drop a PIN they can no longer type.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletLockSettingsSheet(
    pinEnabled: Boolean,
    onSetPin: (CharArray) -> Unit,
    onRemovePin: () -> Unit,
    onDismiss: () -> Unit
) {
    val extras = LocalRoomExtras.current
    val fieldShape = RoundedCornerShape((extras.radius * 0.6f).dp)
    var entering by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }

    val longEnough = first.length >= PinLockCrypto.MIN_PIN_LENGTH
    val matches = first == second

    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader(if (entering) "Wallet PIN" else "Wallet lock")
            if (!entering) {
                Text(
                    "A wallet PIN unlocks this profile's wallet alongside your " +
                        "device fingerprint, face or screen lock. The PIN is never " +
                        "stored — only a one-way verifier of it. Losing it never " +
                        "locks you out: the device unlock always still works.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = extras.textSecondary
                )
                Spacer(Modifier.height(8.dp))
                SettingActionRow(
                    title = if (pinEnabled) "Change wallet PIN" else "Set wallet PIN",
                    subtitle = "At least ${PinLockCrypto.MIN_PIN_LENGTH} digits",
                    onClick = { entering = true }
                )
                if (pinEnabled) {
                    SettingActionRow(
                        title = "Remove wallet PIN",
                        subtitle = "Fall back to the device unlock only",
                        onClick = {
                            onRemovePin()
                            onDismiss()
                        }
                    )
                }
            } else {
                Text(
                    "Choose a PIN of at least ${PinLockCrypto.MIN_PIN_LENGTH} digits. " +
                        "A longer PIN is much stronger: the stored verifier is offline-" +
                        "checkable if the device is compromised.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = extras.textSecondary
                )
                Spacer(Modifier.height(12.dp))
                PinField(
                    value = first,
                    label = "New PIN",
                    isError = attempted && !longEnough,
                    errorText = "At least ${PinLockCrypto.MIN_PIN_LENGTH} digits",
                    shape = fieldShape,
                    onValueChange = { first = it }
                )
                Spacer(Modifier.height(8.dp))
                PinField(
                    value = second,
                    label = "Confirm PIN",
                    isError = attempted && !matches,
                    errorText = "PINs do not match",
                    shape = fieldShape,
                    onValueChange = { second = it }
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            attempted = true
                            if (longEnough && matches) {
                                onSetPin(first.toCharArray())
                                onDismiss()
                            }
                        },
                        enabled = first.isNotEmpty() && second.isNotEmpty(),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) { Text(if (pinEnabled) "Change PIN" else "Set PIN") }
                    OutlinedButton(
                        onClick = { entering = false },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) { Text("Back") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PinField(
    value: String,
    label: String,
    isError: Boolean,
    errorText: String,
    shape: RoundedCornerShape,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { next ->
            if (next.length <= 12 && next.all { it.isDigit() }) onValueChange(next)
        },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = { if (isError) Text(errorText) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        shape = shape,
        modifier = Modifier.fillMaxWidth()
    )
}
