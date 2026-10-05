package com.roombrowser.browser.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.roombrowser.RoomBrowserApp
import com.roombrowser.data.repo.TotpRepository
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.theme.BuiltInThemes
import com.roombrowser.domain.totp.Base32
import com.roombrowser.domain.totp.OtpAuthUri
import com.roombrowser.domain.totp.TotpAlgorithm
import com.roombrowser.domain.totp.TotpEntry
import com.roombrowser.domain.totp.TotpGenerator
import com.roombrowser.qr.QrScannerActivity
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

/**
 * 2FA Management — the profile's authenticator accounts, with the code, a
 * countdown ring and copy/edit/delete.
 *
 * PROCESS: the DEFAULT process, like [NotesActivity] — nothing here touches the
 * engine, and the vault-style lock is this process's own [TotpRepository]
 * instance.
 *
 * The window is FLAG_SECURE (this screen only): a code is a live credential and
 * would otherwise show up in a screenshot or the recents thumbnail. Nothing
 * else in the app sets it.
 */
class TwoFactorActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        val profileIdValue = intent.getStringExtra(EXTRA_PROFILE_ID)
        if (profileIdValue.isNullOrBlank()) {
            finish()
            return
        }
        val profileId = ProfileId(profileIdValue)
        val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME).orEmpty()
        val graph = (application as RoomBrowserApp).graph
        val biometricsAvailable = BiometricGate.canAuthenticate(this)
        setContent {
            var spec by remember { mutableStateOf(BuiltInThemes.default()) }
            LaunchedEffect(Unit) {
                runCatching { graph.profileRepo.getProfile(profileId) }.getOrNull()?.let { profile ->
                    spec = BuiltInThemes.resolveOrDefault(profile.themeJson)
                }
            }
            RoomBrowserTheme(spec = spec) {
                TwoFactorRoot(
                    profileId = profileId,
                    profileName = profileName,
                    repo = graph.totpRepo,
                    biometricsAvailable = biometricsAvailable,
                    onClose = { finish() },
                    onUnlockRequest = {
                        BiometricGate.unlock(
                            this,
                            "2FA codes",
                            { graph.totpRepo.unlock() },
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
                Intent(context, TwoFactorActivity::class.java)
                    .putExtra(EXTRA_PROFILE_ID, profileId)
                    .putExtra(EXTRA_PROFILE_NAME, profileName)
            )
        }
    }
}

/** How the list is ordered. */
private enum class TotpSort { RECENT, AZ, ZA }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TwoFactorRoot(
    profileId: ProfileId,
    profileName: String,
    repo: TotpRepository,
    biometricsAvailable: Boolean,
    onClose: () -> Unit,
    onUnlockRequest: () -> Unit
) {
    val extras = LocalRoomExtras.current
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val unlocked by repo.isUnlocked.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Owner decision 4: biometric/device credential first; with no screen lock
    // on the device there is nothing to gate with here, so the screen opens and
    // says so — a silent open would be the dangerous version of that.
    LaunchedEffect(Unit) {
        if (!repo.isUnlocked.value) {
            if (biometricsAvailable) onUnlockRequest() else repo.unlock()
        }
    }

    // ONE ticker for the whole screen, aligned to the wall clock. It runs only
    // while RESUMED, so a backgrounded screen computes no HMAC at all. Rows read
    // it through a lambda rather than a value, which keeps the per-second
    // recomposition inside the row instead of the whole list.
    var tick by remember { mutableStateOf(System.currentTimeMillis() / 1_000L) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                tick = System.currentTimeMillis() / 1_000L
                delay(1_000L - System.currentTimeMillis() % 1_000L)
            }
        }
    }

    var entries by remember { mutableStateOf<List<TotpEntry>>(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(TotpSort.RECENT) }
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TotpEntry?>(null) }
    var detailsOf by remember { mutableStateOf<TotpEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<TotpEntry?>(null) }

    LaunchedEffect(unlocked, lifecycle) {
        if (!unlocked) {
            entries = emptyList()
        } else {
            runCatching {
                repo.observe(profileId)
                    .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                    .collect { entries = it }
            }
        }
    }

    fun copyCode(entry: TotpEntry, code: String) {
        copySensitive(context, "OTP code", code)
        scope.launch { runCatching { repo.markUsed(entry.id) } }
        scope.launch {
            snackbarHostState.showSnackbar(
                "Code for ${entry.issuer.ifBlank { entry.account }} copied"
            )
        }
    }

    val needle = query.trim().lowercase()
    val visible = entries
        .filter {
            needle.isEmpty() ||
                it.issuer.lowercase().contains(needle) ||
                it.account.lowercase().contains(needle)
        }
        .let { filtered ->
            when (sort) {
                TotpSort.AZ -> filtered.sortedWith(
                    compareBy({ it.issuer.lowercase() }, { it.account.lowercase() })
                )
                TotpSort.ZA -> filtered.sortedWith(
                    compareByDescending<TotpEntry> { it.issuer.lowercase() }
                        .thenByDescending { it.account.lowercase() }
                )
                TotpSort.RECENT -> filtered.sortedWith(
                    compareByDescending<TotpEntry> { it.lastUsedAt ?: Long.MIN_VALUE }
                        .thenBy { it.issuer.lowercase() }
                )
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
                title = { Text("2FA Management") },
                navigationIcon = {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.semantics { contentDescription = "Close 2FA" }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            editing = null
                            editorOpen = true
                        },
                        modifier = Modifier.semantics { contentDescription = "Add 2FA" }
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
            if (!unlocked && biometricsAvailable) {
                LockedTwoFactorPane(onUnlock = onUnlockRequest)
                return@Column
            }
            if (!biometricsAvailable) {
                NoScreenLockBanner()
            }
            if (entries.isEmpty()) {
                EmptyState(
                    "No 2FA accounts yet",
                    "Codes are generated on this device and belong to " +
                        "\"${profileName.ifBlank { "this profile" }}\"."
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
                ) { Text("Add 2FA") }
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search accounts") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    shape = RoundedCornerShape((extras.radius * 0.6f).dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SortChip("Recently used", sort == TotpSort.RECENT) { sort = TotpSort.RECENT }
                    SortChip("A-Z", sort == TotpSort.AZ) { sort = TotpSort.AZ }
                    SortChip("Z-A", sort == TotpSort.ZA) { sort = TotpSort.ZA }
                }
                if (visible.isEmpty()) {
                    EmptyState("No matches", "No account matches \"${query.trim()}\".")
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(visible, key = { it.id }) { entry ->
                            TotpRow(
                                entry = entry,
                                clock = { tick },
                                onCopy = { code -> copyCode(entry, code) },
                                onEdit = {
                                    editing = entry
                                    editorOpen = true
                                },
                                onDetails = { detailsOf = entry },
                                onDelete = { deleteTarget = entry }
                            )
                        }
                    }
                }
            }
        }
    }

    if (editorOpen) {
        TwoFactorEditorSheet(
            profileId = profileId,
            initial = editing,
            repo = repo,
            onDismiss = {
                editorOpen = false
                editing = null
            },
            onSaved = { message ->
                editorOpen = false
                editing = null
                scope.launch { snackbarHostState.showSnackbar(message) }
            },
            onError = { message -> scope.launch { snackbarHostState.showSnackbar(message) } }
        )
    }

    detailsOf?.let { entry ->
        TwoFactorDetailsDialog(
            entry = entry,
            clock = { tick },
            onDismiss = { detailsOf = null }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete 2FA account?") },
            text = {
                Text(
                    "Delete \"${target.issuer.ifBlank { target.account }}\"? " +
                        "Its setup key is removed from this device and this cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            runCatching { repo.delete(profileId, target.id) }
                                .onSuccess { snackbarHostState.showSnackbar("Account deleted") }
                                .onFailure {
                                    snackbarHostState.showSnackbar("Could not delete account")
                                }
                        }
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortChip(label: String, selected: Boolean, onSelect: () -> Unit) {
    FilterChip(selected = selected, onClick = onSelect, label = { Text(label) })
}

/**
 * Shown on a device with no screen lock. The screen still opens — there is no
 * credential to ask for — so this says plainly what that means rather than
 * letting the absence of a prompt read as security.
 */
@Composable
private fun NoScreenLockBanner() {
    val extras = LocalRoomExtras.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "This device has no screen lock, so these codes are not protected. " +
                "Set a PIN, pattern or password in system settings.",
            style = MaterialTheme.typography.bodySmall,
            color = extras.textSecondary
        )
    }
}

@Composable
private fun LockedTwoFactorPane(onUnlock: () -> Unit) {
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
            "2FA locked",
            style = MaterialTheme.typography.titleMedium,
            color = extras.textPrimary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Unlock with your fingerprint, face or device PIN to view this " +
                "profile's authenticator codes.",
            style = MaterialTheme.typography.bodyMedium,
            color = extras.textSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onUnlock, modifier = Modifier.heightIn(min = 48.dp)) { Text("Unlock") }
    }
}

/**
 * One account. [clock] is read HERE (not in the list) so a tick recomposes this
 * row and not the whole screen, and the HMAC behind the digits is keyed on the
 * step index — it runs once per period, not once per second.
 */
@Composable
private fun TotpRow(
    entry: TotpEntry,
    clock: () -> Long,
    onCopy: (String) -> Unit,
    onEdit: () -> Unit,
    onDetails: () -> Unit,
    onDelete: () -> Unit
) {
    val extras = LocalRoomExtras.current
    val tick = clock()
    val nowMillis = tick * 1_000L
    val code = remember(entry.id, tick / entry.period) {
        runCatching { TotpGenerator.generate(entry, nowMillis) }.getOrNull()
    }
    val secondsLeft = TotpGenerator.secondsRemaining(nowMillis, entry.period)
    var menuOpen by remember { mutableStateOf(false) }
    val label = entry.issuer.ifBlank { entry.account }

    RoomCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        withGradient = false
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 8.dp, end = 2.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    color = extras.textPrimary
                )
                if (entry.issuer.isNotBlank() && entry.account.isNotBlank()) {
                    Text(
                        entry.account,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = extras.textSecondary
                    )
                }
                Text(
                    code ?: "------",
                    style = MaterialTheme.typography.headlineSmall,
                    color = extras.textPrimary
                )
            }
            CountdownRing(secondsLeft = secondsLeft, period = entry.period)
            IconButton(
                onClick = { code?.let(onCopy) },
                enabled = code != null,
                modifier = Modifier.semantics { contentDescription = "Copy OTP for $label" }
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = extras.icon)
            }
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.semantics { contentDescription = "More options for $label" }
                ) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null, tint = extras.icon)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Copy OTP") },
                        enabled = code != null,
                        onClick = {
                            menuOpen = false
                            code?.let(onCopy)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("View details") },
                        onClick = {
                            menuOpen = false
                            onDetails()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

/**
 * Seconds left in the current step, as a ring. It turns red for the last five
 * seconds, when a code about to expire is worth noticing.
 */
@Composable
private fun CountdownRing(secondsLeft: Int, period: Int) {
    val extras = LocalRoomExtras.current
    val progress = (secondsLeft.toFloat() / period.toFloat()).coerceIn(0f, 1f)
    val ringColor = if (secondsLeft <= 5) MaterialTheme.colorScheme.error else extras.primary
    val trackColor = extras.surfaceAlt.copy(alpha = 0.6f)
    Canvas(Modifier.size(28.dp)) {
        val stroke = 3.dp.toPx()
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke)
        )
        drawArc(
            color = ringColor,
            startAngle = -90f,
            sweepAngle = 360f * progress,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

/** Issuer, account, parameters and the setup key — on request, never by default. */
@Composable
private fun TwoFactorDetailsDialog(entry: TotpEntry, clock: () -> Long, onDismiss: () -> Unit) {
    val extras = LocalRoomExtras.current
    val tick = clock()
    var secretShown by remember { mutableStateOf(false) }
    val code = remember(entry.id, tick / entry.period) {
        runCatching { TotpGenerator.generate(entry, tick * 1_000L) }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.issuer.ifBlank { entry.account }) },
        text = {
            Column {
                DetailLine("Account", entry.account.ifBlank { "—" })
                DetailLine("Code", code ?: "—")
                DetailLine("Type", "TOTP · ${entry.algorithm.name}")
                DetailLine("Digits", entry.digits.toString())
                DetailLine("Refresh", "every ${entry.period} s")
                Spacer(Modifier.height(10.dp))
                if (secretShown) {
                    DetailLine("Setup key", entry.secret)
                    Text(
                        "Anyone with this key can generate these codes. " +
                            "Treat it like the account's password.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    OutlinedButton(
                        onClick = { secretShown = true },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Show setup key") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    val extras = LocalRoomExtras.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = extras.textSecondary,
            modifier = Modifier.width(88.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = extras.textPrimary
        )
    }
}

/**
 * Add/edit sheet. Three ways in, all of which end at the same validated form:
 * scan the QR, paste an `otpauth://` link, or type the setup key.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TwoFactorEditorSheet(
    profileId: ProfileId,
    initial: TotpEntry?,
    repo: TotpRepository,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
    onError: (String) -> Unit
) {
    val extras = LocalRoomExtras.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fieldShape = RoundedCornerShape((extras.radius * 0.6f).dp)

    var issuer by remember { mutableStateOf(initial?.issuer ?: "") }
    var account by remember { mutableStateOf(initial?.account ?: "") }
    var secret by remember { mutableStateOf(initial?.secret ?: "") }
    var algorithm by remember { mutableStateOf(initial?.algorithm ?: TotpAlgorithm.SHA1) }
    var digits by remember { mutableStateOf((initial?.digits ?: 6).toString()) }
    var period by remember { mutableStateOf((initial?.period ?: 30).toString()) }
    var busy by remember { mutableStateOf(false) }

    fun applyParsed(text: String) {
        runCatching { OtpAuthUri.parse(text) }
            .onSuccess { parsed ->
                issuer = parsed.issuer.orEmpty()
                account = parsed.account
                secret = parsed.secret
                algorithm = parsed.algorithm
                digits = parsed.digits.toString()
                period = parsed.period.toString()
            }
            .onFailure { onError("That is not a usable 2FA link: ${it.message}") }
    }

    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_TEXT)
        if (!text.isNullOrBlank()) applyParsed(text) else onError("No QR code was read")
    }

    val editShape = fieldShape
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader(if (initial == null) "Add 2FA account" else "Edit 2FA account")
            if (initial == null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            scanLauncher.launch(
                                Intent(context, QrScannerActivity::class.java)
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Scan QR")
                    }
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                            val pasted = clipboard.primaryClip
                                ?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)
                                ?.coerceToText(context)
                                ?.toString()
                            if (pasted.isNullOrBlank()) {
                                onError("The clipboard is empty")
                            } else {
                                applyParsed(pasted)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) { Text("Paste link") }
                }
                Spacer(Modifier.height(12.dp))
            }
            OutlinedTextField(
                value = issuer,
                onValueChange = { issuer = it },
                label = { Text("Service (optional)") },
                singleLine = true,
                shape = editShape,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = account,
                onValueChange = { account = it },
                label = { Text("Account") },
                singleLine = true,
                shape = editShape,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = { Text("Setup key (Base32)") },
                singleLine = true,
                shape = editShape,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TotpAlgorithm.entries.forEach { option ->
                    FilterChip(
                        selected = algorithm == option,
                        onClick = { algorithm = option },
                        label = { Text(option.name) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = digits,
                    onValueChange = { digits = it.filter(Char::isDigit).take(1) },
                    label = { Text("Digits") },
                    singleLine = true,
                    shape = editShape,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = period,
                    onValueChange = { period = it.filter(Char::isDigit).take(3) },
                    label = { Text("Period (s)") },
                    singleLine = true,
                    shape = editShape,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        val key = secret.filterNot(Char::isWhitespace).uppercase()
                        val digitCount = digits.toIntOrNull() ?: 0
                        val step = period.toIntOrNull() ?: 0
                        when {
                            account.isBlank() ->
                                onError("An account name is required")
                            key.isEmpty() ->
                                onError("A setup key is required")
                            runCatching { Base32.decode(key) }.isFailure ->
                                onError("That setup key is not valid Base32")
                            digitCount != 6 && digitCount != 8 ->
                                onError("Digits must be 6 or 8")
                            step !in 1..300 ->
                                onError("The period must be between 1 and 300 seconds")
                            busy -> Unit
                            else -> {
                                busy = true
                                scope.launch {
                                    runCatching {
                                        repo.save(
                                            profileId = profileId,
                                            issuer = issuer.trim(),
                                            account = account.trim(),
                                            secret = key,
                                            algorithm = algorithm,
                                            digits = digitCount,
                                            period = step,
                                            id = initial?.id
                                        )
                                    }.onSuccess {
                                        onSaved(
                                            if (initial == null) "Account added"
                                            else "Account updated"
                                        )
                                    }.onFailure {
                                        busy = false
                                        onError("Could not save the account")
                                    }
                                }
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) { Text(if (initial == null) "Add" else "Update") }
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
