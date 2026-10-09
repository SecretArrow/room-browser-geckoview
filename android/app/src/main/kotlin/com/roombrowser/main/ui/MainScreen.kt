package com.roombrowser.main.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.profile.CopyOptions
import com.roombrowser.main.ExportSections
import com.roombrowser.main.MainActivity
import com.roombrowser.main.MainViewModel
import com.roombrowser.main.MessageAction
import com.roombrowser.main.PendingExport
import com.roombrowser.ui.common.EmptyState
import com.roombrowser.ui.common.ProfileAvatar
import com.roombrowser.ui.common.RoomBottomSheetShape
import com.roombrowser.ui.common.RoomSheetHeader
import com.roombrowser.ui.common.VaultPassphraseDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PROFILE_COLORS = listOf(
    0xFF6750A4L, 0xFF2196F3L, 0xFF00897BL, 0xFF43A047L,
    0xFFFF7043L, 0xFFF4511EL, 0xFFD81B60L, 0xFF8D6E63L,
    0xFF5C6BC0L, 0xFF3949ABL
)

private val PROFILE_ICONS = listOf(
    "\uD83D\uDC64", "\uD83D\uDC68\u200D\uD83D\uDCBC", "\uD83D\uDD0D", "\uD83D\uDCB0",
    "\uD83E\uDDEA", "\uD83D\uDED2", "\uD83D\uDC7B", "\uD83D\uDCBB",
    "\uD83C\uDFA4", "\uD83C\uDFD4\uFE0F", "\uD83D\uDE80", "\uD83D\uDCDA"
)

/**
 * Main screen: welcome flow (first run), profile selector, CRUD dialogs
 * and the "Open with profile" chooser for external links.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    activity: MainActivity,
    viewModel: MainViewModel,
    onOpenProfile: (profileId: String, url: String?) -> Unit
) {
    val profiles = viewModel.profiles
    val firstRunDone = viewModel.firstRunDone
    val extras = com.roombrowser.ui.common.LocalRoomExtras.current
    val snackbarHostState = remember { SnackbarHostState() }
    val message = viewModel.message
    LaunchedEffect(message) {
        message?.let {
            // Some messages carry a one-tap follow-up (today only the denied-
            // notifications hint, whose action opens the system notification
            // screen). Both the label and the duration are explicit: Material3
            // defaults an action-bearing snackbar to Indefinite, which would
            // park it over the bottom of the profile list until it is swiped
            // away. Actionless messages keep their original Short timing.
            val action = viewModel.messageAction
            val actionLabel = when (action) {
                MessageAction.OPEN_NOTIFICATION_SETTINGS -> "Settings"
                null -> null
            }
            val result = snackbarHostState.showSnackbar(
                message = it,
                actionLabel = actionLabel,
                withDismissAction = action != null,
                duration = if (action == null) SnackbarDuration.Short else SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                when (action) {
                    MessageAction.OPEN_NOTIFICATION_SETTINGS -> activity.openNotificationSettings()
                    null -> Unit
                }
            }
            viewModel.clearMessage()
        }
    }

    var showCreate by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Profile?>(null) }
    var duplicateTarget by remember { mutableStateOf<Profile?>(null) }
    var resetTarget by remember { mutableStateOf<Profile?>(null) }
    var exportTarget by remember { mutableStateOf<Profile?>(null) }
    var showImport by remember { mutableStateOf(false) }

    // ---- Backup v2 delivery / intake (SAF + share) ----
    val context = LocalContext.current
    // Save an export where the user picks (Delivery dialog → "Save as file").
    val saveExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            viewModel.writeExportTo(uri)
        } else {
            // Closing the picker without a location is a cancel, not a failure.
            viewModel.discardExport()
        }
    }
    // Pick an export file to import — the PRIMARY import path; paste stays
    // as the secondary one.
    val pickImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.readImportFile(uri)
            showImport = false
        }
    }

    // One launcher serves all five sources; the file's content decides which.
    val pickPasswordFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.readPasswordFile(uri)
        } else {
            // Backing out of the picker is a cancel, not a failure.
            viewModel.cancelPasswordImport()
        }
    }

    // The vault's biometric gate is UI-owned: when the ViewModel needs it
    // (reading/writing saved passwords), run it and report the outcome.
    LaunchedEffect(viewModel.vaultGateRequest) {
        viewModel.vaultGateRequest?.let {
            activity.gateVault(
                onSuccess = { viewModel.onVaultGateResult(true) },
                onFailure = { viewModel.onVaultGateResult(false) }
            )
        }
    }

    val pendingUrl = viewModel.pendingExternalUrl

    Scaffold(
        snackbarHost = {
            // Padded above the system navigation bar. contentWindowInsets is
            // zeroed on this Scaffold, so a bare SnackbarHost would draw UNDER
            // the Back/Home/Recents bar.
            SnackbarHost(
                snackbarHostState,
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Bottom)
                )
            )
        },
        // Insets applied explicitly below (TopAppBar handles the status bar
        // itself) — deterministic on every API level, nothing overlaps the
        // system Back / Home / Recents buttons.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Room Browser") },
                actions = {
                    // Labelled, not a bare glyph: importing a profile is the one
                    // thing on this screen a user arrives looking for by name,
                    // and an unlabelled restore icon gave them nothing to read.
                    TextButton(
                        onClick = { showImport = true },
                        modifier = Modifier.semantics { contentDescription = "Import profile" }
                    ) {
                        Icon(
                            Icons.Filled.SettingsBackupRestore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Import")
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
                .verticalScroll(rememberScrollState())
        ) {
            if (profiles.isEmpty() && !firstRunDone) {
                // TRUE first run: the welcome block carries the copy AND the
                // single create affordance directly beneath it. The profile-
                // list chrome (header + description + empty state) is
                // deliberately omitted here: stacked under the welcome copy
                // it pushed the primary CTA below the fold on small screens
                // (320x640dp — e.g. the CI emulator's default profile), and
                // first-run onboarding whose only action needs scrolling is
                // poor UX. Exactly ONE create affordance per state, as ever:
                // the CreateProfileDialog confirm is the only other place
                // the label exists. Centred, like the empty state below: on a
                // screen with nothing on it yet, a left-hugging CTA reads as a
                // layout bug.
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    WelcomeSection(onSkip = { viewModel.setFirstRunDone() })
                    Spacer(Modifier.height(4.dp))
                    CreateProfileButton(addAnother = false, onCreate = { showCreate = true })
                }
            } else {
                if (profiles.isEmpty()) {
                    // Welcome skipped on an empty list (the "Later" path, or
                    // the last profile was deleted): the list chrome and the
                    // empty state take the welcome's place.
                    ProfileListHeader(extras = extras)
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        EmptyState(title = "No profiles yet", subtitle = "Create one to start isolated browsing")
                        Spacer(Modifier.height(12.dp))
                        // Primary CTA of the single empty-state block — the only
                        // create button composed while no profile exists.
                        CreateProfileButton(addAnother = false, onCreate = { showCreate = true })
                    }
                } else {
                    ProfileListHeader(extras = extras)

                    profiles.forEach { profile ->
                        ProfileCard(
                            profile = profile,
                            tabCount = viewModel.tabCounts[profile.id.value] ?: 0,
                            // Inline, never a dialog: a recorded offer must
                            // not gate the launch surface.
                            offerPasswordImport =
                                viewModel.passwordImportOfferId == profile.id.value,
                            onDismissPasswordImportOffer = {
                                viewModel.consumePasswordImportOffer()
                            },
                            onOpen = {
                                if (profile.isLocked) {
                                    activity.gateProfile(profile.name) {
                                        onOpenProfile(profile.id.value, pendingUrl)
                                    }
                                } else {
                                    onOpenProfile(profile.id.value, pendingUrl)
                                }
                            },
                            onEdit = { editTarget = profile },
                            onDuplicate = { duplicateTarget = profile },
                            onReset = { resetTarget = profile },
                            onDelete = { viewModel.requestDeleteProfile(profile) },
                            onExport = { exportTarget = profile },
                            onImportPasswords = {
                                viewModel.startPasswordImport(profile)
                                pickPasswordFileLauncher.launch(passwordFileTypes)
                            },
                            onExportPasswords = { viewModel.startPasswordExport(profile) },
                            onSetDefault = { viewModel.setDefault(profile.id) },
                            onToggleLock = { viewModel.setLocked(profile.id, !profile.isLocked) }
                        )
                    }

                    // "Add another" affordance below the cards — the SAME
                    // single create entry point, in outlined chrome. It lives in
                    // this branch so it can never stack with the empty-state CTA.
                    CreateProfileButton(addAnother = true, onCreate = { showCreate = true })
                }
            }
        }
    }

    // ---- External link routing: "Open with profile" (never silent) ----
    if (pendingUrl != null && profiles.isNotEmpty()) {
        OpenWithProfileSheet(
            url = pendingUrl,
            profiles = profiles,
            onChoose = { profile ->
                if (profile.isLocked) {
                    activity.gateProfile(profile.name) {
                        viewModel.consumeExternalUrl()
                        onOpenProfile(profile.id.value, pendingUrl)
                    }
                } else {
                    viewModel.consumeExternalUrl()
                    onOpenProfile(profile.id.value, pendingUrl)
                }
            },
            onDismiss = { viewModel.consumeExternalUrl() }
        )
    }

    if (showCreate) {
        CreateProfileDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, icon, color ->
                viewModel.createProfile(name, icon, color) { created ->
                    viewModel.setFirstRunDone()
                    showCreate = false
                    onOpenProfile(created.id.value, pendingUrl)
                }
            }
        )
    }

    editTarget?.let { target ->
        EditProfileDialog(
            profile = target,
            onDismiss = { editTarget = null },
            onSave = { name, icon, color ->
                // ONE call, ONE get → copy → put. Saving used to fire
                // renameProfile() and restyleProfile() as two independent
                // coroutines: both read the row before either wrote it, so one
                // of the two edits was silently thrown away while the snackbar
                // reported success.
                viewModel.updateProfile(target.id, name, icon, color)
                editTarget = null
            }
        )
    }

    duplicateTarget?.let { target ->
        DuplicateProfileDialog(
            profile = target,
            onDismiss = { duplicateTarget = null },
            onConfirm = { options ->
                viewModel.duplicateProfile(target.id, options)
                duplicateTarget = null
            }
        )
    }

    resetTarget?.let { target ->
        ConfirmDialog(
            title = "Reset Profile",
            text = "This removes all browsing data of \"${target.name}\" (tabs, history, permissions, site data). Storage identity stays valid.",
            confirmLabel = "Reset",
            onDismiss = { resetTarget = null },
            onConfirm = {
                viewModel.resetProfile(target.id)
                resetTarget = null
            }
        )
    }

    // The offer must come BEFORE the delete: deleting destroys the saved
    // passwords, rows and Keystore key together.
    viewModel.deletePrompt?.let { prompt ->
        val target = prompt.profile
        val passwords = when (val count = prompt.credentialCount) {
            null -> "This profile may hold saved passwords that will be destroyed with it."
            0 -> null
            1 -> "1 saved password will be destroyed with it and cannot be recovered."
            else -> "$count saved passwords will be destroyed with it and cannot be recovered."
        }
        // 2FA seeds have no counterpart anywhere else once they are gone: the
        // account they authenticate can only be recovered through the service's
        // own backup codes, which this app does not hold.
        val twoFactor = when (prompt.totpCount) {
            0 -> null
            1 -> "1 authenticator account (2FA) will be destroyed with it, and its " +
                "setup key cannot be recovered."
            else -> "${prompt.totpCount} authenticator accounts (2FA) will be destroyed " +
                "with it, and their setup keys cannot be recovered."
        }
        // "Worth exporting" is NOT the same as "has passwords": a profile whose
        // only secrets are authenticator accounts still loses them for good, so
        // the Export-first route has to be offered for it too.
        val exportable = prompt.credentialCount != 0 || prompt.totpCount > 0
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeletePrompt() },
            title = { Text("Delete Profile") },
            text = {
                Column {
                    Text(
                        "This permanently deletes \"${target.name}\" and ALL of its " +
                            "isolated data (cookies, storage, history, downloads)."
                    )
                    listOfNotNull(passwords, twoFactor).forEach { line ->
                        Spacer(Modifier.height(12.dp))
                        Text(line)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDeleteWithoutExport() }) {
                    Text(if (exportable) "Delete without exporting" else "Delete")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.dismissDeletePrompt() }) { Text("Cancel") }
                    if (exportable) {
                        Spacer(Modifier.width(4.dp))
                        Button(onClick = { viewModel.confirmDeleteWithExport() }) {
                            Text("Export first")
                        }
                    }
                }
            }
        )
    }

    exportTarget?.let { target ->
        ExportProfileDialog(
            profile = target,
            onDismiss = { exportTarget = null },
            onExport = { sections ->
                exportTarget = null
                // Whatever the selection put behind the device vault — the
                // vault gate, the passphrase step and delivery are driven from
                // the ViewModel (gate via viewModel.vaultGateRequest below).
                viewModel.startExport(target, sections)
            }
        )
    }

    if (showImport) {
        ImportProfileDialog(
            onPickFile = { pickImportLauncher.launch(arrayOf("application/json", "*/*")) },
            onDismiss = { showImport = false },
            onImport = { json ->
                viewModel.importProfile(json)
                showImport = false
            }
        )
    }

    // A finished export, waiting for its delivery action.
    viewModel.pendingExport?.let { export ->
        ExportDeliveryDialog(
            export = export,
            onSave = { saveExportLauncher.launch(export.fileName) },
            onShare = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, export.fileName)
                    putExtra(Intent.EXTRA_TEXT, export.json)
                }
                context.startActivity(Intent.createChooser(send, "Share \"${export.fileName}\""))
                viewModel.consumePendingExport()
            },
            onCancel = { viewModel.cancelExport() }
        )
    }

    // The passphrase step of an export (set a new one) or import (enter the
    // file's one).
    viewModel.passphrasePrompt?.let { prompt ->
        VaultPassphraseDialog(
            prompt = prompt,
            onConfirm = { passphrase ->
                if (prompt.forExport) {
                    viewModel.confirmExportPassphrase(passphrase)
                } else {
                    viewModel.confirmImportPassphrase(passphrase)
                }
            },
            onDismiss = { viewModel.cancelPassphrasePrompt() }
        )
    }

    // Import rejections — shown ONLY when nothing was written.
    viewModel.importError?.let { error ->
        ErrorDialog(
            title = "Import failed",
            text = error,
            onDismiss = { viewModel.dismissImportError() }
        )
    }
}

/**
 * First-run welcome copy. Deliberately carries NO create button: the screen
 * composes exactly ONE "Create Profile" affordance ([CreateProfileButton]).
 * This section's own button used to be one of three create buttons rendered
 * simultaneously on first open.
 */
@Composable
private fun WelcomeSection(onSkip: () -> Unit) {
    val extras = com.roombrowser.ui.common.LocalRoomExtras.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(extras.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Text("\uD83C\uDFE0", style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(16.dp))
        Text("Your browser.", style = MaterialTheme.typography.headlineSmall, color = extras.textPrimary)
        Text("Your profiles.", style = MaterialTheme.typography.headlineSmall, color = extras.primary)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your data stays separated. Every profile keeps its own cookies, storage, history, settings and THEME — like separate browser installations.",
            style = MaterialTheme.typography.bodyMedium,
            color = extras.textSecondary
        )
        Spacer(Modifier.height(18.dp))
        OutlinedButton(onClick = onSkip) { Text("Later") }
    }
}

/**
 * The profile-list chrome: section title + one-line explainer. Shared by the
 * non-empty list and the skipped-welcome empty state so the two stay
 * typographically identical (the true-first-run branch intentionally shows
 * neither — the welcome copy speaks there).
 */
@Composable
private fun ProfileListHeader(extras: com.roombrowser.ui.common.RoomExtras) {
    Text(
        "Your profiles",
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
    Text(
        "Each profile is a fully isolated browsing environment — separate cookies, storage, history and settings.",
        style = MaterialTheme.typography.bodyMedium,
        color = extras.textSecondary,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

/**
 * The ONE on-screen "Create Profile" affordance — exactly one instance is
 * composed in any state (the CreateProfileDialog confirm button is the only
 * other place that label exists). [addAnother] selects the chrome: filled
 * and centered in the empty state (primary onboarding CTA) vs outlined,
 * full-width below the profile list ("add another").
 */
@Composable
private fun CreateProfileButton(addAnother: Boolean, onCreate: () -> Unit) {
    if (addAnother) {
        OutlinedButton(
            onClick = onCreate,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            CreateProfileLabel()
        }
    } else {
        Button(onClick = onCreate) { CreateProfileLabel() }
    }
}

/** The on-screen create label defined in exactly one place — it cannot
 *  duplicate even if a second button variant is ever added. */
@Composable
private fun CreateProfileLabel() {
    Text("Create Profile")
}

/**
 * What the password-file picker offers. A provider may report a real CSV as
 * `application/octet-stream`, and a file the user can see but not select has no
 * workaround — so the catch-all is last and the CONTENT decides what the file
 * is. (Its literal is in the array below; written here it would end this comment.)
 */
private val passwordFileTypes = arrayOf("text/csv", "text/plain", "*/*")

@Composable
private fun ProfileCard(
    profile: Profile,
    tabCount: Int,
    offerPasswordImport: Boolean,
    onDismissPasswordImportOffer: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onReset: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onImportPasswords: () -> Unit,
    onExportPasswords: () -> Unit,
    onSetDefault: () -> Unit,
    onToggleLock: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val extras = com.roombrowser.ui.common.LocalRoomExtras.current
    val cardContext = androidx.compose.ui.platform.LocalContext.current
    val accent = androidx.compose.ui.graphics.Color(profile.colorArgb.toInt())
    com.roombrowser.ui.common.RoomCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column {
            // Accent header strip
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            listOf(accent, extras.primary)
                        )
                    )
            )
            Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileAvatar(
                    icon = profile.icon,
                    colorArgb = profile.colorArgb,
                    size = 46
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            profile.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = extras.textPrimary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (profile.isLocked) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = "Profile locked",
                                modifier = Modifier.size(16.dp),
                                tint = extras.primary
                            )
                        }
                        if (profile.isDefault) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = "Default profile",
                                modifier = Modifier.size(14.dp),
                                tint = extras.secondary
                            )
                        }
                    }
                    Text(
                        "$tabCount tabs · Last active ${timeFormat.format(Date(profile.lastActiveAt))}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = extras.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Profile actions", tint = extras.icon)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Open") },
                            leadingIcon = { Icon(Icons.Filled.OpenInNew, contentDescription = null) },
                            onClick = { menuOpen = false; onOpen() }
                        )
                        DropdownMenuItem(
                            text = { Text("Edit / Rename") },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = { menuOpen = false; onEdit() }
                        )
                        DropdownMenuItem(
                            text = { Text("Duplicate") },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                            onClick = { menuOpen = false; onDuplicate() }
                        )
                        DropdownMenuItem(
                            text = { Text(if (profile.isLocked) "Remove lock" else "Lock profile") },
                            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                            onClick = { menuOpen = false; onToggleLock() }
                        )
                        // Only offered when it would DO something: the row said
                        // "Set as default" on the profile that already was one.
                        if (!profile.isDefault) {
                            DropdownMenuItem(
                                text = { Text("Set as default") },
                                leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null) },
                                onClick = { menuOpen = false; onSetDefault() }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Theme studio") },
                            leadingIcon = { Icon(Icons.Filled.Palette, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                com.roombrowser.theme.ui.ThemeStudioActivity.launch(
                                    cardContext,
                                    profile.id.value
                                )
                            }
                        )
                        HorizontalDivider()
                        // "Export profile", not "Export settings": this file now
                        // carries a chosen set of sections, not just settings.
                        DropdownMenuItem(
                            text = { Text("Export profile…") },
                            leadingIcon = { Icon(Icons.Filled.IosShare, contentDescription = null) },
                            onClick = { menuOpen = false; onExport() }
                        )
                        DropdownMenuItem(
                            text = { Text("Import passwords…") },
                            onClick = { menuOpen = false; onImportPasswords() }
                        )
                        DropdownMenuItem(
                            text = { Text("Export passwords…") },
                            onClick = { menuOpen = false; onExportPasswords() }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Reset profile data") },
                            leadingIcon = { Icon(Icons.Filled.RestartAlt, contentDescription = null) },
                            onClick = { menuOpen = false; onReset() }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { menuOpen = false; onDelete() }
                        )
                    }
                }
            }
            if (offerPasswordImport) {
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            extras.primary.copy(alpha = 0.10f),
                            RoundedCornerShape(12.dp)
                        )
                        .border(
                            1.dp,
                            extras.primary.copy(alpha = 0.35f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp)
                ) {
                    Text(
                        "Bring your passwords over?",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = extras.textPrimary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "\"${profile.name}\" starts empty. Import saved logins from " +
                            "Chrome, Brave, Edge or Firefox (their exported " +
                            "passwords.csv), or from a Room Browser password file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = extras.textSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismissPasswordImportOffer) {
                            Text("Later")
                        }
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = {
                                onDismissPasswordImportOffer()
                                onImportPasswords()
                            }
                        ) { Text("Import passwords") }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onOpen,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Open profile ${profile.name}" }
            ) { Text("OPEN") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenWithProfileSheet(
    url: String,
    profiles: List<Profile>,
    onChoose: (Profile) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            RoomSheetHeader("Open with profile")
            Text(
                url,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                profiles.forEach { profile ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChoose(profile) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ProfileAvatar(profile.icon, profile.colorArgb, size = 36)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            profile.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateProfileDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, icon: String, colorArgb: Long) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf(PROFILE_ICONS.first()) }
    var color by remember { mutableStateOf(PROFILE_COLORS.first()) }
    val extras = com.roombrowser.ui.common.LocalRoomExtras.current
    AlertDialog(
        onDismissRequest = onDismiss,
        // Noun title — the "Create Profile" string stays exclusive to the
        // confirm action below and the single on-screen button.
        title = { Text("New Profile") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("Icon", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PROFILE_ICONS.forEach { candidate ->
                        Text(
                            candidate,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier
                                .padding(4.dp)
                                .clip(CircleShape)
                                .background(
                                    if (candidate == icon) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                                .clickable { icon = candidate }
                                .padding(4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Color", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PROFILE_COLORS.take(5).forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(c.toInt()))
                                .border(
                                    2.dp,
                                    if (c == color) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    CircleShape
                                )
                                .clickable { color = c }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PROFILE_COLORS.drop(5).forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(c.toInt()))
                                .border(
                                    2.dp,
                                    if (c == color) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    CircleShape
                                )
                                .clickable { color = c }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Defaults: DuckDuckGo search, recommended privacy shields, system DNS.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = extras.textSecondary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onCreate(name.trim(), icon, color) },
                enabled = name.isNotBlank()
            ) { Text("Create Profile") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditProfileDialog(
    profile: Profile,
    onDismiss: () -> Unit,
    onSave: (name: String, icon: String, colorArgb: Long) -> Unit
) {
    var name by remember { mutableStateOf(profile.name) }
    var icon by remember { mutableStateOf(profile.icon) }
    var color by remember { mutableStateOf(profile.colorArgb) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Profile") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    // Clearing the name used to leave Save tappable but inert
                    // (its onClick dropped blank input on the floor): the
                    // dialog just sat there with no hint why nothing happened.
                    // The field now says what is wrong and the confirm button
                    // below says it cannot be used — the same guard
                    // CreateProfileDialog puts on its own confirm. Unlike that
                    // dialog this one opens with a name already in it, so the
                    // error can only appear after the user empties it.
                    isError = name.isBlank(),
                    supportingText = if (name.isBlank()) {
                        { Text("Name cannot be empty", color = MaterialTheme.colorScheme.error) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("Renaming never changes the profile's storage identity (UUID).")
                Spacer(Modifier.height(12.dp))
                Text("Icon", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PROFILE_ICONS.forEach { candidate ->
                        Text(
                            candidate,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier
                                .padding(4.dp)
                                .clip(CircleShape)
                                .background(
                                    if (candidate == icon) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                                .clickable { icon = candidate }
                                .padding(4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Color", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PROFILE_COLORS.take(5).forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(c.toInt()))
                                .border(
                                    2.dp,
                                    if (c == color) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    CircleShape
                                )
                                .clickable { color = c }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onSave(name.trim(), icon, color) },
                enabled = name.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun DuplicateProfileDialog(
    profile: Profile,
    onDismiss: () -> Unit,
    onConfirm: (CopyOptions) -> Unit
) {
    var copyBookmarks by remember { mutableStateOf(true) }
    var copyHistory by remember { mutableStateOf(false) }
    val extras = com.roombrowser.ui.common.LocalRoomExtras.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Duplicate \"${profile.name}\"") },
        text = {
            Column {
                Text("The duplicate gets a NEW isolated storage namespace (fresh cookies and site data).")
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                ) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = extras.secondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Settings, theme and shields are copied too",
                        style = MaterialTheme.typography.bodySmall,
                        color = extras.textSecondary
                    )
                }
                LabeledCheckboxRow("Bookmarks", copyBookmarks) { copyBookmarks = it }
                LabeledCheckboxRow("History", copyHistory) { copyHistory = it }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Cookies, cache, sessions and site data are never copied between profiles.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = extras.textSecondary
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onConfirm(
                    CopyOptions(
                        settings = true,
                        bookmarks = copyBookmarks,
                        history = copyHistory
                    )
                )
            }) { Text("Duplicate") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun LabeledCheckboxRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 6.dp)
    ) {
        androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            Button(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Human-readable size for the delivery dialog. */
private fun formatSize(bytes: Int): String =
    if (bytes < 2048) "$bytes B"
    else String.format(Locale.US, "%.1f KB", bytes / 1024.0)

@Composable
private fun ExportProfileDialog(
    profile: Profile,
    onDismiss: () -> Unit,
    onExport: (ExportSections) -> Unit
) {
    var sections by remember { mutableStateOf(ExportSections()) }
    val extras = com.roombrowser.ui.common.LocalRoomExtras.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export \"${profile.name}\"") },
        text = {
            // Scrollable, like every other dialog body here: an AlertDialog's
            // height is capped by the window, so in landscape (or at a large
            // font scale) this body simply clipped the checkboxes and the
            // passwords paragraph out of reach.
            Column(
                Modifier
                    .imePadding()
                    .verticalScroll(rememberScrollState())
            ) {
                Text("The profile itself — its settings, theme and name — is always included.")
                Spacer(Modifier.height(12.dp))
                LabeledCheckboxRow("Bookmarks", sections.bookmarks) { sections = sections.copy(bookmarks = it) }
                LabeledCheckboxRow("Notes", sections.notes) { sections = sections.copy(notes = it) }
                LabeledCheckboxRow("Saved passwords", sections.passwords) { sections = sections.copy(passwords = it) }
                LabeledCheckboxRow("Authenticator accounts (2FA)", sections.totp) { sections = sections.copy(totp = it) }
                LabeledCheckboxRow("Site permissions", sections.sitePermissions) {
                    sections = sections.copy(sitePermissions = it)
                }
                LabeledCheckboxRow("Per-site settings", sections.siteSettings) {
                    sections = sections.copy(siteSettings = it)
                }
                Spacer(Modifier.height(8.dp))
                // Unticked, unlike every row above it: the others cost the user
                // privacy if the file leaks, this one costs them the money.
                LabeledCheckboxRow("Wallet (recovery phrase and private keys)", sections.wallet) {
                    sections = sections.copy(wallet = it)
                }
                if (sections.wallet) {
                    Text(
                        "Ticking this makes the file equal to the wallet: anyone who opens it " +
                            "with the passphrase can spend the funds. Share this file only with " +
                            "yourself.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        sections.wallet && (sections.passwords || sections.totp) ->
                            "In the next step you set one export passphrase: the saved logins, " +
                                "the authenticator accounts and the wallet are each sealed under " +
                                "it. Cookies, sessions and history are never exported."
                        sections.wallet ->
                            "In the next step you set the export passphrase the wallet is sealed " +
                                "under. Cookies, sessions and history are never exported."
                        sections.passwords || sections.totp ->
                            "If this profile has saved logins or authenticator accounts, you set " +
                                "an export passphrase for them in the next step. Cookies, sessions " +
                                "and history are never exported."
                        else ->
                            "Nothing selected lives behind the device vault, so no passphrase is " +
                                "needed. Cookies, sessions and history are never exported."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = extras.textSecondary
                )
            }
        },
        confirmButton = { Button(onClick = { onExport(sections) }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ExportDeliveryDialog(
    export: PendingExport,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Export ready") },
        text = {
            // Scrollable for the same reason as the dialogs above: the file
            // name plus the three-line explanation did not fit a landscape
            // dialog, and an unscrollable body just hid the rest.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("\"${export.fileName}\" · ${formatSize(export.sizeBytes)}")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Save it somewhere safe or share it directly. The passwords inside stay " +
                        "sealed under your export passphrase; cookies, sessions and history " +
                        "are not in the file.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = { Button(onClick = onSave) { Text("Save as file") } },
        dismissButton = {
            Row {
                TextButton(onClick = onShare) { Text("Share") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun ImportProfileDialog(
    onPickFile: () -> Unit,
    onDismiss: () -> Unit,
    onImport: (json: String) -> Unit
) {
    var json by remember { mutableStateOf("") }
    val extras = com.roombrowser.ui.common.LocalRoomExtras.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Profile") },
        text = {
            Column(
                Modifier
                    .imePadding()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Restore a profile from a Room Browser export file — settings, bookmarks, " +
                        "site rules and, with its passphrase, saved passwords."
                )
                Spacer(Modifier.height(12.dp))
                // File picking is the PRIMARY path: a full-width 48dp+ target.
                OutlinedButton(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Choose file…")
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "…or paste the export JSON:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = extras.textSecondary
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = json,
                    onValueChange = { json = it },
                    label = { Text("JSON") },
                    // heightIn, not height: a fixed 160dp box stays 160dp tall
                    // even when the dialog has less room than that (landscape,
                    // large font scale), where it crowded the paste hint and
                    // the "Choose file…" button out of view. It may now shrink
                    // and grow with the pasted JSON, but never past the cap.
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp, max = 160.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = { if (json.isNotBlank()) onImport(json) }, enabled = json.isNotBlank()) {
                Text("Import")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** A one-button message dialog (import rejections — nothing was written). */
@Composable
private fun ErrorDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { Button(onClick = onDismiss) { Text("OK") } }
    )
}
