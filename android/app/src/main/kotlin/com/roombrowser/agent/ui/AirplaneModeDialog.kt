package com.roombrowser.agent.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.roombrowser.agent.PendingAirplaneRequest

/**
 * The guided Airplane Mode confirmation — the non-root half of the feature,
 * raised by the `/airplane` command when the phone cannot switch its own
 * radios: the system settings screen is already open (or, when the phone has
 * none, the quick-settings tile is the way), and the person is asked to flip
 * the switch themselves.
 *
 * YES IS OPTIONAL. The state is polled while this dialog is up, and the chat
 * continues by itself the moment the phone matches the target — the button
 * is for a person who flipped it and wants the chat to say so NOW, and its
 * answer is verified against the live state rather than taken on faith: a
 * Yes with the phone still the old way gets the honest "still off" error,
 * never a pretend switch. No (and closing the dialog — a dismissal must
 * never read as consent) stops here with nothing changed.
 *
 * A CYCLE request ([PendingAirplaneRequest.cycle]) asks for two flips —
 * on, then straight back off — because the drop-and-return is what rotates
 * the phone's IP, and its auto-continue watches for both halves in that
 * order before the chat says the rotation happened.
 *
 * Both buttons carry their own `airplane_accept`/`airplane_stop` content
 * descriptions — the suite's a11y contract, so an e2e (and a screen reader)
 * addresses the decision, not the button's label text.
 */
@Composable
fun AirplaneModeDialog(
    request: PendingAirplaneRequest,
    onAccept: () -> Unit,
    onStop: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onStop,
        title = { Text("Airplane Mode") },
        text = {
            Text(
                when {
                    request.cycle && request.settingsOpened -> {
                        "Switch Airplane Mode ON, wait a couple of seconds, then switch it OFF " +
                            "again in the settings screen this chat just opened — that " +
                            "drop-and-return is what rotates the phone's IP. The chat continues " +
                            "by itself the moment it is back off — press Yes when you have done " +
                            "it, or No to stop."
                    }
                    request.cycle -> {
                        "Switch Airplane Mode ON, wait a couple of seconds, then switch it OFF " +
                            "again from this phone's quick settings — that drop-and-return is " +
                            "what rotates the phone's IP. The chat continues by itself the " +
                            "moment it is back off — press Yes when you have done it, or No to " +
                            "stop."
                    }
                    request.settingsOpened -> {
                        val word = if (request.targetOn) "on" else "off"
                        "Turn Airplane Mode $word in the settings screen this chat just opened. " +
                            "The chat continues by itself the moment it is $word — press Yes when " +
                            "you have done it, or No to stop."
                    }
                    else -> {
                        val word = if (request.targetOn) "on" else "off"
                        "Turn Airplane Mode $word from this phone's quick settings. " +
                            "The chat continues by itself the moment it is $word — press Yes when " +
                            "you have done it, or No to stop."
                    }
                }
            )
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                modifier = Modifier.semantics { contentDescription = "airplane_accept" }
            ) {
                Text("Yes")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onStop,
                modifier = Modifier.semantics { contentDescription = "airplane_stop" }
            ) {
                Text("No")
            }
        }
    )
}
