package com.roombrowser.browser.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.roombrowser.RoomBrowserApp
import com.roombrowser.data.db.NoteEntity
import com.roombrowser.data.repo.BrowserRepository
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.theme.BuiltInThemes
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomBrowserTheme
import kotlinx.coroutines.launch

/**
 * Add / edit ONE note. Its OWN activity, in the default process like
 * [NotesActivity] and for the same reason: a note is a plain Room row and needs
 * no engine or vault session. It used to be a bottom sheet inside the list,
 * which put a full-screen writing surface in a window that the keyboard could
 * shrink to nothing.
 *
 * EXTRA_NOTE_ID absent → add mode; present → edit mode, and the row is re-read
 * from the repository rather than carried across the Intent ([NoteEntity] is
 * not Parcelable, and a stale copy could overwrite a concurrent edit).
 *
 * It reports its outcome back as a result so the list can announce it — the
 * snackbar cannot live here, because this window is gone by the time it would
 * be read.
 */
class NoteEditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val profileIdValue = intent.getStringExtra(EXTRA_PROFILE_ID)
        if (profileIdValue.isNullOrBlank()) {
            finish()
            return
        }
        val profileId = ProfileId(profileIdValue)
        val noteId = intent.getStringExtra(EXTRA_NOTE_ID)
        val graph = (application as RoomBrowserApp).graph
        setContent {
            var spec by remember { mutableStateOf(BuiltInThemes.default()) }
            LaunchedEffect(Unit) {
                runCatching { graph.profileRepo.getProfile(profileId) }.getOrNull()?.let { profile ->
                    spec = BuiltInThemes.resolveOrDefault(profile.themeJson)
                }
            }
            RoomBrowserTheme(spec = spec) {
                NoteEditorRoot(
                    profileId = profileId,
                    noteId = noteId,
                    repo = graph.browserRepo,
                    onClose = { finish() },
                    onSaved = { message ->
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
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_RESULT_MESSAGE = "note_editor_message"

        /** [noteId] null opens the add form; anything else edits that row. */
        fun launch(context: Context, profileId: String, noteId: String?) {
            context.startActivity(
                Intent(context, NoteEditorActivity::class.java)
                    .putExtra(EXTRA_PROFILE_ID, profileId)
                    .putExtra(EXTRA_NOTE_ID, noteId)
            )
        }
    }
}

@Composable
private fun NoteEditorRoot(
    profileId: ProfileId,
    noteId: String?,
    repo: BrowserRepository,
    onClose: () -> Unit,
    onSaved: (String) -> Unit
) {
    val extras = LocalRoomExtras.current
    val scope = rememberCoroutineScope()

    // The row is read before the form exists, so the fields are seeded once
    // from real data and never re-seeded over what the user is typing.
    var loaded by remember { mutableStateOf(noteId == null) }
    var initial by remember { mutableStateOf<NoteEntity?>(null) }
    LaunchedEffect(profileId, noteId) {
        if (noteId != null) {
            initial = runCatching { repo.note(profileId, noteId) }.getOrNull()
            loaded = true
        }
    }

    var title by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
    var body by remember(initial) { mutableStateOf(initial?.body.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val fieldShape = RoundedCornerShape((extras.radius * 0.6f).dp)
    // A note needs a title or a body: an accidental blank save must not create
    // an empty row.
    val canSave = title.isNotBlank() || body.isNotBlank()

    Scaffold(
        modifier = Modifier.imePadding(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(if (noteId == null) "Add note" else "Edit note") },
                navigationIcon = {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.semantics { contentDescription = "Close note editor" }
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (!canSave || busy) return@Button
                            busy = true
                            error = null
                            val draftTitle = title.trim()
                            val draftBody = body
                            val draftId = noteId
                            scope.launch {
                                runCatching {
                                    repo.saveNote(profileId, draftTitle, draftBody, draftId)
                                }.onSuccess {
                                    onSaved(if (draftId == null) "Note saved" else "Note updated")
                                }.onFailure {
                                    busy = false
                                    error = "Could not save note"
                                }
                            }
                        },
                        enabled = canSave && !busy,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) { Text(if (noteId == null) "Save" else "Update") }
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
        if (!loaded) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .windowInsetsPadding(
                        WindowInsets.systemBars
                            .union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Horizontal)
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { CircularProgressIndicator() }
        } else {
            Column(
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
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    shape = fieldShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "note_title_field" }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Note") },
                    minLines = 8,
                    shape = fieldShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "note_body_field" }
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
