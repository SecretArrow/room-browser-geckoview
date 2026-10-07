package com.roombrowser.browser.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.RoomBrowserApp
import com.roombrowser.data.db.NoteEntity
import com.roombrowser.data.repo.BrowserRepository
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.theme.BuiltInThemes
import com.roombrowser.ui.common.EmptyState
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomBrowserTheme
import com.roombrowser.ui.common.RoomCard
import kotlinx.coroutines.launch

/**
 * Per-profile notes — a Keep-like list with create / edit / delete.
 *
 * PROCESS: the DEFAULT process, unlike [PasswordsActivity]. Notes need no
 * per-process engine or vault session; they are plain Room rows that both
 * processes read through multi-instance invalidation, so there is no reason to
 * tie them to ':browser', which restarts on every profile switch.
 *
 * The profile id arrives as an Intent extra and is the only scoping: no
 * WebView is bound, so nothing here can affect engine isolation.
 *
 * Writing happens in [NoteEditorActivity], its OWN window: a full-screen text
 * surface no longer shares the list's window, where the keyboard could squeeze
 * the fields out of view.
 */
class NotesActivity : ComponentActivity() {

    /** What the editor reported on the way out; cleared once announced. */
    private var editorMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val profileIdValue = intent.getStringExtra(EXTRA_PROFILE_ID)
        if (profileIdValue.isNullOrBlank()) {
            finish()
            return
        }
        val profileId = ProfileId(profileIdValue)
        val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME).orEmpty()
        val graph = (application as RoomBrowserApp).graph
        val editor = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            editorMessage = result.data?.getStringExtra(NoteEditorActivity.EXTRA_RESULT_MESSAGE)
        }
        setContent {
            var spec by remember { mutableStateOf(BuiltInThemes.default()) }
            LaunchedEffect(Unit) {
                runCatching { graph.profileRepo.getProfile(profileId) }.getOrNull()?.let { profile ->
                    spec = BuiltInThemes.resolveOrDefault(profile.themeJson)
                }
            }
            RoomBrowserTheme(spec = spec) {
                NotesRoot(
                    profileId = profileId,
                    profileName = profileName,
                    repo = graph.browserRepo,
                    editorMessage = editorMessage,
                    onEditorMessageShown = { editorMessage = null },
                    onAddNote = { NoteEditorActivity.launch(this, profileId.value, null) },
                    onEditNote = { NoteEditorActivity.launch(this, profileId.value, it.id) },
                    onClose = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_PROFILE_NAME = "profile_name"

        fun launch(context: Context, profileId: String, profileName: String) {
            context.startActivity(
                Intent(context, NotesActivity::class.java)
                    .putExtra(EXTRA_PROFILE_ID, profileId)
                    .putExtra(EXTRA_PROFILE_NAME, profileName)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesRoot(
    profileId: ProfileId,
    profileName: String,
    repo: BrowserRepository,
    editorMessage: String?,
    onEditorMessageShown: () -> Unit,
    onAddNote: () -> Unit,
    onEditNote: (NoteEntity) -> Unit,
    onClose: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val notes by repo.observeNotes(profileId).collectAsState(initial = emptyList())

    var deleteTarget by remember { mutableStateOf<NoteEntity?>(null) }

    LaunchedEffect(editorMessage) {
        val message = editorMessage ?: return@LaunchedEffect
        onEditorMessageShown()
        snackbarHostState.showSnackbar(message)
    }

    fun deleteNote(note: NoteEntity) {
        scope.launch {
            runCatching { repo.deleteNote(note.id) }
                .onSuccess { snackbarHostState.showSnackbar("Note deleted") }
                .onFailure { snackbarHostState.showSnackbar("Could not delete note") }
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
                title = { Text("Notes") },
                navigationIcon = {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.semantics { contentDescription = "Close notes" }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick = onAddNote,
                        modifier = Modifier.semantics { contentDescription = "Add note" }
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
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
            if (notes.isEmpty()) {
                EmptyState(
                    "No notes yet",
                    "Notes you write here belong to \"${profileName.ifBlank { "this profile" }}\" " +
                        "and travel with its export."
                )
                Button(
                    onClick = onAddNote,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 4.dp)
                        .heightIn(min = 48.dp)
                ) { Text("Add note") }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(notes, key = { it.id }) { note ->
                        NoteRow(
                            note = note,
                            onOpen = { onEditNote(note) },
                            onDelete = { deleteTarget = note }
                        )
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete note?") },
            text = {
                Text(
                    "Delete \"${target.title.ifBlank { "this untitled note" }}\"? " +
                        "This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        deleteNote(target)
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun NoteRow(note: NoteEntity, onOpen: () -> Unit, onDelete: () -> Unit) {
    val extras = LocalRoomExtras.current
    RoomCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        withGradient = false
    ) {
        Row(
            Modifier
                .clickable(onClick = onOpen)
                .padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    note.title.ifBlank { "(untitled)" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    color = extras.textPrimary
                )
                if (note.body.isNotBlank()) {
                    Text(
                        note.body,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = extras.textSecondary
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    DateUtils.getRelativeTimeSpanString(note.updatedAt).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.textSecondary
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.semantics { contentDescription = "Delete note" }
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = extras.icon)
            }
        }
    }
}
