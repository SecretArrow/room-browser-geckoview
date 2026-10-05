package com.roombrowser.browser.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.roombrowser.RoomBrowserApp
import com.roombrowser.data.repo.CredentialRepository
import com.roombrowser.data.repo.VaultLockedException
import com.roombrowser.domain.credentials.PasswordGenerator
import com.roombrowser.domain.credentials.SavedCredential
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.theme.BuiltInThemes
import com.roombrowser.security.BiometricGate
import com.roombrowser.ui.common.EmptyState
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomBottomSheetShape
import com.roombrowser.ui.common.RoomBrowserTheme
import com.roombrowser.ui.common.RoomCard
import com.roombrowser.ui.common.RoomCardShape
import com.roombrowser.ui.common.RoomSheetHeader
import com.roombrowser.ui.common.copySensitive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Password manager — the full-screen vault UI for ONE profile.
 *
 * PROCESS (critical): this activity is declared android:process=":browser" on
 * purpose. [CredentialRepository]'s session lock is per-process state inside
 * the AppGraph of the process that owns it — running here means this screen
 * shares the lock with the browsing engine, so unlocking the vault here also
 * enables the in-page login offers/save prompts for the session (and an
 * unlock performed through a save prompt enables this screen). It also never
 * hosts a WebView, so the profile's engine isolation is untouched.
 *
 * UNLOCK GATE: on entry, a locked vault triggers the biometric /
 * device-credential gate ONCE ("Password vault"). Failure or absence of any
 * device credential keeps the locked empty-state with a manual "Unlock"
 * retry — credential CONTENTS are never rendered while locked. After one
 * successful unlock the content stays for the session (the lock naturally
 * re-arms on process death); the gate is never re-run from recomposition,
 * only from the user's own retry taps.
 *
 * SECURITY: passwords are shown only through the row's reveal toggle, copied
 * only through ClipboardManager clips flagged EXTRA_IS_SENSITIVE (no
 * clipboard preview), never logged, and never placed in Intent extras or
 * saved instance state (the transient edit form keeps its fields in plain
 * composition state only).
 */
class PasswordsActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: insets are consumed by the Compose UI below — nothing
        // ever overlaps the status bar, cutouts or the navigation buttons.
        enableEdgeToEdge()
        val profileIdValue = intent.getStringExtra(EXTRA_PROFILE_ID)
        if (profileIdValue.isNullOrBlank()) {
            // No profile to show a vault for — nothing to do here.
            finish()
            return
        }
        val profileId = ProfileId(profileIdValue)
        val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME).orEmpty()
        val graph = (application as RoomBrowserApp).graph
        val biometricsAvailable = BiometricGate.canAuthenticate(this)
        setContent {
            // Wear this profile's own theme. The snapshot is loaded once
            // (this activity is short-lived; live re-theming belongs to the
            // browsing surface).
            var spec by remember { mutableStateOf(BuiltInThemes.default()) }
            LaunchedEffect(Unit) {
                runCatching { graph.profileRepo.getProfile(profileId) }.getOrNull()?.let { profile ->
                    spec = BuiltInThemes.resolveOrDefault(profile.themeJson)
                }
            }
            RoomBrowserTheme(spec = spec) {
                PasswordsRoot(
                    profileId = profileId,
                    profileName = profileName,
                    repo = graph.credentialRepo,
                    biometricsAvailable = biometricsAvailable,
                    onClose = { finish() },
                    onUnlockRequest = {
                        // Failure keeps the vault locked (and this screen on
                        // its locked pane); success unlocks for the session.
                        BiometricGate.unlock(
                            this,
                            "Password vault",
                            { graph.credentialRepo.unlock() },
                            { }
                        )
                    }
                )
            }
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_PROFILE_NAME = "profile_name"

        fun launch(context: Context, profileId: String, profileName: String) {
            context.startActivity(
                Intent(context, PasswordsActivity::class.java)
                    .putExtra(EXTRA_PROFILE_ID, profileId)
                    .putExtra(EXTRA_PROFILE_NAME, profileName)
            )
        }
    }
}

/**
 * Vault surface: locked gate pane or (search + grouped list + editor/delete
 * dialogs). All repo work is launched from [rememberCoroutineScope]; sheet
 * and dialog opens happen only in callbacks, never in composition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasswordsRoot(
    profileId: ProfileId,
    profileName: String,
    repo: CredentialRepository,
    biometricsAvailable: Boolean,
    onClose: () -> Unit,
    onUnlockRequest: () -> Unit
) {
    val extras = LocalRoomExtras.current
    val context = LocalContext.current
    val unlocked by repo.isUnlocked.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Gate on entry — exactly once per composition; every later prompt is a
    // user-driven retry from the locked pane's Unlock button.
    LaunchedEffect(Unit) {
        if (!repo.isUnlocked.value) onUnlockRequest()
    }

    var query by rememberSaveable { mutableStateOf("") }
    var allCredentials by remember { mutableStateOf<List<SavedCredential>>(emptyList()) }
    var searchResults by remember { mutableStateOf<List<SavedCredential>?>(null) }
    // Bumped after a save/delete so a non-blank search refreshes its snapshot.
    var searchNonce by remember { mutableStateOf(0) }
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SavedCredential?>(null) }
    var deleteTarget by remember { mutableStateOf<SavedCredential?>(null) }
    var revealedIds by remember { mutableStateOf(setOf<String>()) }

    // Live list while unlocked. observe() re-checks the lock on every
    // emission and throws when it lands — runCatching ends the collector
    // (fail closed), and this effect restarts it on the next unlock.
    //
    // flowWithLifecycle, not a bare collect: LaunchedEffect is scoped to the
    // COMPOSITION, which outlives visibility here (the activity keeps its
    // composition while merely stopped), so a backgrounded vault screen kept
    // decrypting every credential on every Room invalidation behind whatever
    // the user had moved on to. Collection now stops at STOP and resumes at
    // START; the last snapshot stays in state, so returning does not flash
    // an empty list.
    val vaultLifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(unlocked, vaultLifecycle) {
        if (!unlocked) {
            allCredentials = emptyList()
            searchResults = null
            revealedIds = emptySet()
        } else {
            runCatching {
                repo.observe(profileId)
                    .flowWithLifecycle(vaultLifecycle, Lifecycle.State.STARTED)
                    .collect { allCredentials = it }
            }
        }
    }

    // Search box: repo.search while non-blank (150ms debounce), the live
    // observe list otherwise.
    LaunchedEffect(query, unlocked, searchNonce) {
        val needle = query.trim()
        if (!unlocked || needle.isEmpty()) {
            searchResults = null
        } else {
            delay(150)
            searchResults = runCatching {
                repo.search(profileId, needle)
            }.getOrDefault(emptyList())
        }
    }

    val visible = (searchResults ?: allCredentials)
        .sortedWith(compareBy({ it.domain.lowercase() }, { it.username.lowercase() }))
    val byDomain = visible.groupBy { it.domain }

    fun saveCredential(
        domain: String,
        username: String,
        password: String,
        title: String,
        id: String?
    ) {
        scope.launch {
            runCatching {
                repo.save(profileId, domain, username, password, title.ifBlank { null }, id)
            }.onSuccess {
                editorOpen = false
                editing = null
                searchNonce++
                snackbarHostState.showSnackbar(if (id == null) "Login saved" else "Login updated")
            }.onFailure { e ->
                // The sheet stays open: the user's typed data must survive a
                // failed save (only reachable if the lock landed mid-session).
                snackbarHostState.showSnackbar(
                    if (e is VaultLockedException) "Vault is locked" else "Could not save login"
                )
            }
        }
    }

    fun deleteCredential(credential: SavedCredential) {
        scope.launch {
            runCatching { repo.delete(profileId, credential.id) }
                .onSuccess {
                    searchNonce++
                    snackbarHostState.showSnackbar("Login deleted")
                }
                .onFailure { snackbarHostState.showSnackbar("Could not delete login") }
        }
    }

    Scaffold(
        // Keyboard rides under the whole screen (adjustResize semantics);
        // insets are applied EXPLICITLY below — the TopAppBar handles the
        // status bar itself.
        modifier = Modifier.imePadding(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            // Padded above the navigation bar (contentWindowInsets is zeroed
            // on this Scaffold, so a bare host would draw under the buttons).
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
                title = { Text("Passwords") },
                navigationIcon = {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.semantics { contentDescription = "Close passwords" }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (unlocked) {
                        IconButton(
                            onClick = {
                                editing = null
                                editorOpen = true
                            },
                            modifier = Modifier.semantics { contentDescription = "Add login" }
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
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
                // Above the navigation bar and beside display cutouts.
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
        ) {
            if (!unlocked) {
                LockedVaultPane(
                    biometricsAvailable = biometricsAvailable,
                    onUnlock = onUnlockRequest
                )
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search logins") },
                    singleLine = true,
                    shape = RoundedCornerShape((extras.radius * 0.6f).dp),
                    trailingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null, tint = extras.icon)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
                if (profileName.isNotBlank()) {
                    Text(
                        "Vault of profile \"$profileName\"",
                        style = MaterialTheme.typography.labelMedium,
                        color = extras.textSecondary,
                        modifier = Modifier.padding(start = 20.dp, bottom = 4.dp)
                    )
                }
                if (visible.isEmpty()) {
                    if (query.isNotBlank()) {
                        EmptyState(
                            "No matches",
                            "Nothing in this vault matches \"${query.trim()}\"."
                        )
                    } else {
                        EmptyState(
                            "No saved passwords yet",
                            "Logins saved from web pages, or added here, are stored encrypted per profile."
                        )
                        Button(
                            onClick = {
                                editing = null
                                editorOpen = true
                            },
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(top = 4.dp)
                                .heightIn(min = 48.dp)
                        ) { Text("Add password") }
                    }
                } else {
                    LazyColumn(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        byDomain.forEach { (domain, credentials) ->
                            item(key = "domain-$domain") {
                                DomainHeader(domain)
                            }
                            items(credentials, key = { it.id }) { credential ->
                                CredentialRow(
                                    credential = credential,
                                    showPassword = credential.id in revealedIds,
                                    onToggleReveal = {
                                        revealedIds =
                                            if (credential.id in revealedIds) {
                                                revealedIds - credential.id
                                            } else {
                                                revealedIds + credential.id
                                            }
                                    },
                                    onCopyUsername = {
                                        copySensitive(context, "username", credential.username)
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Username copied")
                                        }
                                    },
                                    onCopyPassword = {
                                        copySensitive(context, "password", credential.password)
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Password copied")
                                        }
                                    },
                                    onEdit = {
                                        editing = credential
                                        editorOpen = true
                                    },
                                    onDelete = { deleteTarget = credential }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------- Editor sheet (add / edit) ----------

    if (editorOpen) {
        CredentialEditorSheet(
            initial = editing,
            onDismiss = {
                editorOpen = false
                editing = null
            },
            onSave = ::saveCredential
        )
    }

    // ---------- Delete confirmation ----------

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete login?") },
            text = {
                Text(
                    "Delete the saved login for \"${target.username}\" on ${target.domain}? " +
                        "This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        deleteCredential(target)
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}

/** The locked pane: no credential content is ever rendered here. */
@Composable
private fun LockedVaultPane(
    biometricsAvailable: Boolean,
    onUnlock: () -> Unit
) {
    val extras = LocalRoomExtras.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                // 64dp — the same hero tile the error page and the network
                // warning use; this was the app's one 72dp tile for no reason.
                .size(64.dp)
                .clip(RoomCardShape)
                .background(extras.surfaceAlt.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = extras.primary,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Vault locked",
            style = MaterialTheme.typography.titleMedium,
            color = extras.textPrimary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (biometricsAvailable) {
                "Unlock with your fingerprint, face or device PIN to view and edit " +
                    "this profile's saved logins."
            } else {
                "This device has no screen lock. Set a PIN, pattern or password in " +
                    "system settings to use the password vault."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = extras.textSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onUnlock,
            modifier = Modifier.heightIn(min = 48.dp)
        ) { Text("Unlock") }
    }
}

/** Domain group label inside the list. */
@Composable
private fun DomainHeader(domain: String) {
    val extras = LocalRoomExtras.current
    Text(
        domain,
        style = MaterialTheme.typography.labelMedium,
        color = extras.primary,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 2.dp)
    )
}

/**
 * One saved login. Layout keeps every action a ≥48dp target without cramming
 * five icons into one line: the info block carries the reveal toggle, and a
 * quiet action row underneath carries copy-username / copy-password / edit /
 * delete. The masked password is a FIXED dot run — it never hints at length.
 */
@Composable
private fun CredentialRow(
    credential: SavedCredential,
    showPassword: Boolean,
    onToggleReveal: () -> Unit,
    onCopyUsername: () -> Unit,
    onCopyPassword: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val extras = LocalRoomExtras.current
    RoomCard(
        Modifier
            .fillMaxWidth()
            // 8dp between cards, the same rhythm as every other card list in
            // the app — 5dp read as one merged block of logins.
            .padding(horizontal = 16.dp, vertical = 8.dp),
        withGradient = false
    ) {
        Column(Modifier.padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        credential.title ?: credential.domain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                        color = extras.textPrimary
                    )
                    Text(
                        credential.username.ifBlank { "(no username)" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = extras.textSecondary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (showPassword) credential.password else "••••••••",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = extras.textSecondary
                    )
                    if (credential.title != null) {
                        Text(
                            credential.domain,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = extras.textSecondary
                        )
                    }
                }
                IconButton(
                    onClick = onToggleReveal,
                    modifier = Modifier.semantics {
                        contentDescription =
                            if (showPassword) "Hide password" else "Show password"
                    }
                ) {
                    Icon(
                        if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = null,
                        tint = extras.icon
                    )
                }
            }
            Row {
                IconButton(
                    onClick = onCopyUsername,
                    modifier = Modifier.semantics { contentDescription = "Copy username" }
                ) {
                    Icon(Icons.Filled.Person, contentDescription = null, tint = extras.icon)
                }
                IconButton(
                    onClick = onCopyPassword,
                    modifier = Modifier.semantics { contentDescription = "Copy password" }
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = extras.icon)
                }
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.semantics { contentDescription = "Edit login" }
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null, tint = extras.icon)
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.semantics { contentDescription = "Delete login" }
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = extras.icon)
                }
            }
        }
    }
}

/**
 * Add/edit sheet — the app-wide sheet shape. Domain/Username/Password are
 * required (inline errors after the first attempted save); the title/label
 * is optional. Under the password field sit the generator controls
 * (PasswordGenerator): a Generate button, a strength readout, the character
 * classes, the length and the look-alike filter — the generated value lands
 * in the same `password` state the keyboard writes, so there is exactly one
 * storage path. Form state is deliberately plain `remember` (NOT
 * rememberSaveable): passwords never belong in saved instance state, and the
 * manifest's configChanges means rotation does not recreate the activity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CredentialEditorSheet(
    initial: SavedCredential?,
    onDismiss: () -> Unit,
    onSave: (domain: String, username: String, password: String, title: String, id: String?) -> Unit
) {
    val extras = LocalRoomExtras.current
    var domain by remember { mutableStateOf(initial?.domain ?: "") }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var passwordVisible by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    // Generator options — plain composition state like the field values
    // (never saved: they are not secrets, but they belong to this edit only).
    // Defaults mirror PasswordGenerator.Options: 20 chars, all four classes,
    // look-alikes allowed (the domain default; the UI starts with the safer
    // "avoid" ON because the user cannot see the characters before inserting).
    var generatorLength by remember { mutableStateOf(20) }
    var generatorUpper by remember { mutableStateOf(true) }
    var generatorLower by remember { mutableStateOf(true) }
    var generatorDigits by remember { mutableStateOf(true) }
    var generatorSymbols by remember { mutableStateOf(true) }
    var generatorAvoidAmbiguous by remember { mutableStateOf(true) }

    val domainBlank = domain.trim().isEmpty()
    val usernameBlank = username.trim().isEmpty()
    val passwordBlank = password.isEmpty()
    val fieldShape = RoundedCornerShape((extras.radius * 0.6f).dp)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // The app-wide sheet standard. Insets are NOT overridden: the default
        // BottomSheetDefaults.windowInsets pads the content above the
        // navigation bar and the sheet dialog resizes for the IME — the same
        // one token every other sheet in the app uses.
        shape = RoomBottomSheetShape
    ) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader(if (initial == null) "Add login" else "Edit login")

            OutlinedTextField(
                value = domain,
                onValueChange = { domain = it },
                label = { Text("Domain") },
                singleLine = true,
                isError = attempted && domainBlank,
                supportingText = {
                    if (attempted && domainBlank) {
                        Text("Domain is required (e.g. accounts.example.com)")
                    }
                },
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
                isError = attempted && usernameBlank,
                supportingText = {
                    if (attempted && usernameBlank) Text("Username is required")
                },
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (passwordVisible) {
                    androidx.compose.ui.text.input.VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(
                        onClick = { passwordVisible = !passwordVisible },
                        modifier = Modifier.semantics {
                            contentDescription =
                                if (passwordVisible) "Hide password" else "Show password"
                        }
                    ) {
                        Icon(
                            if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = null,
                            tint = extras.icon
                        )
                    }
                },
                isError = attempted && passwordBlank,
                supportingText = {
                    if (attempted && passwordBlank) Text("Password is required")
                },
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )

            // ---------- Generator ----------
            // A real CSPRNG generator (domain.PasswordGenerator) so the "Add
            // login" flow can produce a strong secret instead of whatever the
            // user types. Every control below only picks generation OPTIONS;
            // the value itself is written through the same `password` state
            // the keyboard writes, so nothing here is a special storage path.
            val generatorOptions = PasswordGenerator.Options(
                length = generatorLength,
                upper = generatorUpper,
                lower = generatorLower,
                digits = generatorDigits,
                symbols = generatorSymbols,
                avoidAmbiguous = generatorAvoidAmbiguous
            )
            val anyClassSelected = generatorUpper || generatorLower ||
                generatorDigits || generatorSymbols

            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        // The button is disabled without a class, so the
                        // generator's class requirement cannot throw here.
                        password = PasswordGenerator.generate(generatorOptions)
                        // Reveal what was generated: a password the user
                        // never sees is a password they cannot record.
                        passwordVisible = true
                    },
                    enabled = anyClassSelected,
                    modifier = Modifier.heightIn(min = 44.dp)
                ) { Text("Generate") }
                StrengthLabel(password)
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GeneratorClassChip("A-Z", generatorUpper) { generatorUpper = it }
                GeneratorClassChip("a-z", generatorLower) { generatorLower = it }
                GeneratorClassChip("0-9", generatorDigits) { generatorDigits = it }
                GeneratorClassChip("!@#", generatorSymbols) { generatorSymbols = it }
            }
            if (!anyClassSelected) {
                Text(
                    "Pick at least one character class to generate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Length",
                    style = MaterialTheme.typography.labelLarge,
                    color = extras.textSecondary
                )
                Slider(
                    value = generatorLength.toFloat(),
                    onValueChange = { generatorLength = it.roundToInt() },
                    // 8 is short but legal for every class selection (max 4
                    // classes need one character each); 64 keeps a generated
                    // secret typeable when a site has no length limit.
                    valueRange = MIN_GENERATOR_LENGTH.toFloat()..MAX_GENERATOR_LENGTH.toFloat(),
                    steps = MAX_GENERATOR_LENGTH - MIN_GENERATOR_LENGTH - 1,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    generatorLength.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = extras.textPrimary
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GeneratorClassChip("Avoid look-alikes", generatorAvoidAmbiguous) {
                    generatorAvoidAmbiguous = it
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title / label (optional)") },
                singleLine = true,
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        if (!domainBlank && !usernameBlank && !passwordBlank) {
                            onSave(domain, username, password, title, initial?.id)
                        } else {
                            attempted = true
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) { Text(if (initial == null) "Save" else "Update") }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) { Text("Cancel") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Generator length bounds (characters) offered by the editor slider. */
private const val MIN_GENERATOR_LENGTH = 8
private const val MAX_GENERATOR_LENGTH = 64

/**
 * Strength readout for the password currently in the editor. It calls the
 * domain heuristic (PasswordGenerator.strength) and renders its bucket — a
 * hint that an obviously short or single-class secret is about to be saved,
 * NOT a crack-time claim. A blank field reads "—" rather than "Weak".
 */
@Composable
private fun StrengthLabel(password: String) {
    val extras = LocalRoomExtras.current
    if (password.isEmpty()) {
        Text(
            "Strength: —",
            style = MaterialTheme.typography.labelMedium,
            color = extras.textSecondary
        )
        return
    }
    val (label, tint) = when (PasswordGenerator.strength(password)) {
        PasswordGenerator.Strength.WEAK -> "Weak" to MaterialTheme.colorScheme.error
        PasswordGenerator.Strength.FAIR -> "Fair" to extras.secondary
        PasswordGenerator.Strength.STRONG -> "Strong" to extras.primary
    }
    Text(
        "Strength: $label",
        style = MaterialTheme.typography.labelMedium,
        color = tint
    )
}

/**
 * One generator toggle (a character class, or the look-alike filter). State
 * lives in the editor sheet; this is presentation only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeneratorClassChip(
    label: String,
    selected: Boolean,
    onSelect: (Boolean) -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = { onSelect(!selected) },
        label = { Text(label) }
    )
}
