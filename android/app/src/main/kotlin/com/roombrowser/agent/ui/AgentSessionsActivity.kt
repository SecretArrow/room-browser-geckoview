@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.roombrowser.agent.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.agent.AgentChatExport
import com.roombrowser.agent.AgentSettingsController
import com.roombrowser.data.db.AgentSessionEntity
import com.roombrowser.ui.common.EmptyState
import com.roombrowser.ui.common.RoomBrowserTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Agent chat history — its OWN activity. Tapping a chat returns the selected
 * session id via setResult() so the browser (':browser' process) reopens the
 * conversation inside the floating agent panel.
 */
class AgentSessionsActivity : ComponentActivity() {

    private lateinit var controller: AgentSettingsController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: insets are consumed by the Compose UI below — the
        // list never runs under the system Back / Home / Recents buttons.
        enableEdgeToEdge()
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        controller = AgentSettingsController(application, profileId)
        controller.start()
        setContent {
            RoomBrowserTheme {
                AgentSessionsRoot(
                    controller = controller,
                    onPick = { sessionId ->
                        setResult(
                            Activity.RESULT_OK,
                            Intent().putExtra(EXTRA_SESSION_ID, sessionId)
                        )
                        finish()
                    },
                    onClose = { finish() }
                )
            }
        }
    }

    override fun onDestroy() {
        controller.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_SESSION_ID = "session_id"
    }
}

@Composable
private fun AgentSessionsRoot(
    controller: AgentSettingsController,
    onPick: (Long) -> Unit,
    onClose: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()) }
    // Destructive action guard: the delete icon only ARMS the confirmation
    // dialog — the actual delete runs after the user confirms.
    var deleteTarget by remember { mutableStateOf<AgentSessionEntity?>(null) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hostState = remember { SnackbarHostState() }
    // Preparing the document needs database reads, so it happens AFTER the
    // user has chosen a destination rather than before the picker opens. The
    // other order would gather every message on a tap the user might cancel,
    // and would hold a stale copy across a picker the user can leave open.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val outcome = runCatching {
                val text = controller.buildChatExport()
                withContext(Dispatchers.IO) {
                    val out = context.contentResolver.openOutputStream(uri)
                        ?: error("the chosen location could not be opened for writing")
                    out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
            }
            hostState.showSnackbar(
                outcome.fold(
                    onSuccess = { "Chats exported" },
                    // The user is told WHAT failed, not just that something
                    // did: "no space left" and "permission denied" are the
                    // same sentence otherwise, and only one is worth retrying
                    // somewhere else.
                    onFailure = { "Export failed: ${it.message ?: it.javaClass.simpleName}" }
                )
            )
        }
    }

    Scaffold(
        // Insets are applied EXPLICITLY below (TopAppBar handles the status
        // bar itself) — deterministic on every API level.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(hostState) },
        topBar = {
            TopAppBar(
                title = { Text("Agent chats") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                    }
                },
                actions = {
                    // An ICON, not a row: this is a rarely used action on a
                    // screen whose whole point is the list. It is hidden
                    // while there is nothing to export, so it can never
                    // offer the user an empty file.
                    if (controller.sessions.isNotEmpty()) {
                        IconButton(onClick = {
                            exportLauncher.launch(
                                AgentChatExport.fileName(System.currentTimeMillis())
                            )
                        }) {
                            Icon(Icons.Filled.FileDownload, contentDescription = "Export chats")
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
                // Above the navigation bar (3-button Back/Home/Recents or
                // gesture hint) and beside display cutouts in landscape.
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
        ) {
            if (controller.sessions.isEmpty()) {
                EmptyState(
                    title = "No agent chats yet",
                    subtitle = "Conversations with the browsing agent appear here, scoped to this profile."
                )
                return@Column
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(controller.sessions, key = { it.id }) { session ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(session.id) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                session.title,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${session.model} · ${dateFormat.format(Date(session.updatedAt))}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { deleteTarget = session }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete chat")
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete chat?") },
            text = { Text("This permanently removes the chat history.") },
            confirmButton = {
                TextButton(onClick = {
                    controller.deleteSession(target.id)
                    deleteTarget = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}
