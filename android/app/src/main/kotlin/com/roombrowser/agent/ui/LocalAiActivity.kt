@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.roombrowser.agent.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.RoomBrowserApp
import com.roombrowser.agent.AgentProviderStore
import com.roombrowser.agent.LocalAiController
import com.roombrowser.data.db.AgentProviderEntity
import com.roombrowser.domain.agent.LocalAiBackup
import com.roombrowser.domain.agent.LocalAiTuning
import com.roombrowser.domain.agent.OllamaLibraryEntry
import com.roombrowser.domain.agent.OllamaLibraryHeuristics
import com.roombrowser.domain.agent.OllamaModelInfo
import com.roombrowser.domain.agent.OllamaModelPreset
import com.roombrowser.domain.agent.OllamaModelPresets
import com.roombrowser.domain.agent.OllamaPresetTier
import com.roombrowser.domain.agent.OllamaRegistry
import com.roombrowser.domain.paging.Paging
import com.roombrowser.localai.engine.LlamaEngine
import com.roombrowser.localai.store.OnDeviceDownloadController
import com.roombrowser.localai.store.OnDeviceDownloadEntry
import com.roombrowser.localai.store.OnDeviceModel
import com.roombrowser.localai.store.OnDeviceModelStore
import com.roombrowser.ui.common.EmptyState
import com.roombrowser.ui.common.PagingFooter
import com.roombrowser.ui.common.RoomBrowserTheme
import com.roombrowser.ui.common.RoomCard
import com.roombrowser.ui.common.SectionHeader
import com.roombrowser.ui.common.SettingSwitchRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Local AI (Ollama) manager — a DEDICATED ACTIVITY (own window, own back
 * stack entry, own IME handling), exactly like the other agent screens:
 *
 *   1. Connection — Ollama server address (Termux on this phone or a PC in
 *      the LAN), connect + live status, collapsible setup guide.
 *   2. Installed models — the server's /api/tags list; "Use in chat" makes
 *      a native-Ollama provider for the model, delete asks first.
 *   3. Downloads — live /api/pull progress with pause / resume / cancel.
 *   4. Model catalog — curated presets that run well on phones, grouped in
 *      tiers and matched against the device's RAM. Install resolves the tag
 *      on the PUBLIC registry and downloads the real .gguf onto the BUILT-IN
 *      engine; "Pull to Ollama server instead" keeps the daemon path.
 *   5. Performance — GPU layers / CPU threads / context window / keep-alive
 *      (applied to native-Ollama provider chats).
 *   6. Import / Export — a small JSON manifest of the whole setup (SAF).
 *
 * HONEST architecture: Room Browser is the MANAGEMENT CLIENT. No multi-GB
 * binaries are ever bundled into the APK. There are TWO destinations, and the
 * UI never blurs them:
 *
 *  - the BUILT-IN on-device engine (the default for a phone): Install resolves
 *    the catalog tag against registry.ollama.ai — the same source `ollama
 *    pull` uses — and downloads the real blob over HTTPS into app-private
 *    storage, no server involved;
 *  - the user's OWN Ollama daemon (Termux on this phone, or a PC on the LAN),
 *    for people who run one: "Pull to server" and the Downloads section drive
 *    `POST /api/pull`. Pause stops THIS APP's download view; the server keeps
 *    already-fetched layers, and Resume re-attaches and continues from the
 *    last completed layer.
 *
 * Runs in the DEFAULT process (no WebView here). Tuning and the model list
 * are persisted by [LocalAiController] and shared with the ':browser'
 * process agent through the app-state store.
 */
class LocalAiActivity : ComponentActivity() {

    private lateinit var controller: LocalAiController
    private lateinit var onDeviceStore: OnDeviceModelStore
    private lateinit var onDeviceDownloads: OnDeviceDownloadController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: insets are consumed by the Compose UI below — nothing
        // ever overlaps the system Back / Home / Recents buttons.
        enableEdgeToEdge()
        // EXTRA_LIBRARY_URL / EXTRA_REGISTRY_URL are TEST hooks (e2e points the
        // live-library scrape and the model registry at a MockWebServer);
        // production launches never set them and fall back to ollama.com and
        // registry.ollama.ai respectively. Without the registry hook an e2e
        // Install would resolve against the REAL registry and start a
        // multi-hundred-MB download — the tests must be able to say no.
        controller = LocalAiController(
            application,
            intent.getStringExtra(EXTRA_LIBRARY_URL),
            intent.getStringExtra(EXTRA_REGISTRY_URL)
        )
        controller.start()
        // On-device engine store + download controller — owned at the
        // ACTIVITY level (like the Ollama controller) so in-flight .gguf
        // downloads die with the window instead of leaking.
        onDeviceStore = OnDeviceModelStore(application)
        onDeviceDownloads = OnDeviceDownloadController(onDeviceStore)
        setContent {
            RoomBrowserTheme {
                LocalAiRoot(
                    controller = controller,
                    onDeviceStore = onDeviceStore,
                    onDeviceDownloads = onDeviceDownloads,
                    onClose = { finish() }
                )
            }
        }
    }

    override fun onDestroy() {
        onDeviceDownloads.shutdown()
        controller.shutdown()
        // BACKSTOP for the native model handle. The try-model flow releases it
        // when its result dialog is dismissed (see OnDeviceEngineSection), but
        // that dialog's state is plain `remember`: a rotation or a swipe-away
        // can take the window down with the model still loaded, and LlamaEngine
        // is a process-lifetime `object` holding a native GGUF handle — hundreds
        // of MB to several GB that the GC and onTrimMemory cannot see and that
        // nothing else in the DEFAULT process would ever free. Idempotent (a
        // no-op on a zero handle), and no race with the agent: that one loads
        // into its OWN engine instance over in the ':browser' process.
        runCatching { LlamaEngine.unload() }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"

        /** Test-only override of the live-library base URL (see onCreate). */
        const val EXTRA_LIBRARY_URL = "library_url"

        /** Test-only override of the model-registry base URL (see onCreate). */
        const val EXTRA_REGISTRY_URL = "registry_url"

        fun launch(from: Activity, profileId: String?) {
            from.startActivity(Intent(from, LocalAiActivity::class.java).apply {
                putExtra(EXTRA_PROFILE_ID, profileId)
            })
        }

        /** Convenience for callers holding only a context (e.g. Compose). */
        fun launch(context: Context, profileId: String?) {
            val intent = Intent(context, LocalAiActivity::class.java).apply {
                putExtra(EXTRA_PROFILE_ID, profileId)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }
}

@Composable
private fun LocalAiRoot(
    controller: LocalAiController,
    onDeviceStore: OnDeviceModelStore,
    onDeviceDownloads: OnDeviceDownloadController,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var installedPage by remember { mutableIntStateOf(0) }

    LaunchedEffect(notice) {
        notice?.let {
            snackbarHostState.showSnackbar(it)
            notice = null
        }
    }

    // Model list refresh hooks — the installed list must follow the server
    // without the user tapping Refresh: once when the connection comes
    // online, and once more after any download finishes (the controller
    // keeps SUCCESS rows in #downloads until they are cleared, so the
    // catalog entry flips to "Installed" right after a completed pull).
    LaunchedEffect(controller.connection) {
        if (controller.connection is LocalAiController.ConnectionState.Online) {
            controller.refreshInstalled()
        }
    }
    val finishedTags = controller.downloads.entries
        .filter { it.value.phase == LocalAiController.Phase.SUCCESS }
        .map { it.key }
        .toSet()
    LaunchedEffect(finishedTags) {
        if (finishedTags.isNotEmpty()) controller.refreshInstalled()
    }

    // SAF export: writes the JSON manifest built by the controller.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(LocalAiBackup.encode(controller.buildExportManifest()).toByteArray())
                    }
                }
                    .onSuccess { notice = "Setup exported" }
                    .onFailure { notice = "Export failed: ${it.message}" }
            }
        }
    }
    // SAF import: reads the picked file and re-applies the setup (queues the
    // same models for download on the server).
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val text = runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                if (text == null) {
                    notice = "Import failed: could not read the file"
                } else {
                    controller.importBackup(text) { queued ->
                        notice = if (queued >= 0) "Import OK — $queued model download(s) queued"
                        else "Not a valid Local AI backup"
                    }
                }
            }
        }
    }

    Scaffold(
        // Keyboard rides under the whole screen (adjustResize semantics).
        modifier = Modifier.imePadding(),
        snackbarHost = {
            // Padded above the system navigation bar. contentWindowInsets is
            // zeroed on this Scaffold, so a bare SnackbarHost would draw UNDER
            // the Back/Home/Recents bar — hiding exactly the install and error
            // notices the user needs to read.
            SnackbarHost(
                snackbarHostState,
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Bottom)
                )
            )
        },
        // Insets are applied EXPLICITLY below (TopAppBar handles the status
        // bar itself) — deterministic, nothing overlaps the nav buttons.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Local AI (Ollama)") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
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
            // ================= Connection =================
            SectionHeader("Connection")
            var hostInput by remember(controller.tuning.host) {
                mutableStateOf(controller.tuning.host)
            }
            OutlinedTextField(
                value = hostInput,
                onValueChange = { hostInput = it },
                label = { Text("Ollama server address") },
                supportingText = {
                    Text("Termux on this phone: http://localhost:11434 · PC on your LAN: http://192.168.x.x:11434")
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .semantics { contentDescription = "localai_host_field" }
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        // The typed host becomes the persistent tuning host,
                        // then the connection (and model list) is checked.
                        controller.saveTuning(controller.tuning.copy(host = hostInput.trim()))
                        controller.checkConnection()
                    },
                    modifier = Modifier.semantics { contentDescription = "localai_connect" }
                ) { Text("Connect") }
                Spacer(Modifier.width(12.dp))
                ConnectionStatusText(controller.connection, Modifier.weight(1f))
            }
            SetupGuideCard()

            // ================= On-device engine =================
            // The EMBEDDED llama.cpp runtime — inference inside the app,
            // CPU-only, .gguf models in app-private storage. Everything the
            // agent needs without Termux, a server, or a network.
            SectionHeader("On-device engine (built-in, no Termux)")
            OnDeviceEngineSection(
                store = onDeviceStore,
                downloads = onDeviceDownloads,
                onNotice = { notice = it }
            )

            // ================= Installed models =================
            SectionHeader("Installed models")
            TextButton(
                onClick = { controller.refreshInstalled() },
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .semantics { contentDescription = "localai_refresh" }
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Refresh")
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .semantics { contentDescription = "localai_installed_list" }
            ) {
                if (controller.installed.isEmpty()) {
                    EmptyState(
                        title = "No models yet",
                        subtitle = "Install one from the catalog below — or import a previous setup."
                    )
                } else {
                    Paging.slice(controller.installed, installedPage).forEach { model ->
                        InstalledModelRow(
                            model = model,
                            onUseInChat = {
                                controller.useInChat(model.name) { _, message -> notice = message }
                            },
                            onDelete = { confirmDelete = model.name }
                        )
                    }
                    PagingFooter(
                        page = installedPage,
                        total = controller.installed.size,
                        onPage = { installedPage = it },
                        semanticsPrefix = "localai_installed_page"
                    )
                }
            }

            // ================= Downloads =================
            if (controller.downloads.isNotEmpty()) {
                SectionHeader("Downloads")
                Text(
                    "Pausing stops this app's download view. The Ollama server keeps layers it " +
                        "already fetched — Resume re-attaches and continues from the last completed layer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                controller.downloads.forEach { (tag, state) ->
                    DownloadRow(
                        tag = tag,
                        state = state,
                        onPause = { controller.pause(tag) },
                        onResume = { controller.resume(tag) },
                        onCancel = { controller.cancelDownload(tag) }
                    )
                }
            }

            // ================= Model catalog =================
            SectionHeader("Model catalog — best for phones")
            CatalogSection(
                controller = controller,
                store = onDeviceStore,
                downloads = onDeviceDownloads,
                onNotice = { notice = it }
            )

            // ================= Performance =================
            SectionHeader("Performance (GPU · CPU)")
            TuningSection(controller = controller, onNotice = { notice = it })

            // ================= Import / Export =================
            SectionHeader("Import / Export")
            Text(
                "The export is a small JSON manifest of your setup — server address, performance " +
                    "tuning and the model list (not the multi-GB model files). Import re-applies the " +
                    "setup and queues the same models for download.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { exportLauncher.launch("room-browser-local-ai.json") },
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "localai_export" }
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export setup")
                }
                OutlinedButton(
                    onClick = {
                        importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "localai_import" }
                ) {
                    Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import setup")
                }
            }

            Spacer(Modifier.height(48.dp))
        }
    }

    confirmDelete?.let { modelName ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete model?") },
            text = { Text("Removes $modelName from the Ollama server. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    controller.deleteModel(modelName) { ok ->
                        notice = if (ok) "Deleted $modelName" else "Could not delete $modelName"
                    }
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            }
        )
    }
}

// ===========================================================================
// Connection
// ===========================================================================

@Composable
private fun ConnectionStatusText(
    connection: LocalAiController.ConnectionState,
    modifier: Modifier = Modifier
) {
    val (label, isError) = when (connection) {
        is LocalAiController.ConnectionState.Idle -> "Not connected" to false
        is LocalAiController.ConnectionState.Checking -> "Checking…" to false
        // The controller already prefixes the version ("Ollama 0.5.7").
        is LocalAiController.ConnectionState.Online -> "Connected · ${connection.version}" to false
        is LocalAiController.ConnectionState.Offline -> "Offline: ${connection.reason}" to true
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (connection is LocalAiController.ConnectionState.Checking) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                isError -> MaterialTheme.colorScheme.error
                connection is LocalAiController.ConnectionState.Online -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { contentDescription = "localai_status" }
        )
    }
}

@Composable
private fun SetupGuideCard() {
    var expanded by remember { mutableStateOf(false) }
    RoomCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Setup guide",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse setup guide" else "Expand setup guide"
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "1. Install Termux (F-Droid or GitHub releases) on this phone.\n" +
                        "2. Inside Termux: pkg install ollama — or a community Android build.\n" +
                        "3. Run ollama serve and leave it running.\n" +
                        "4. Tap Connect above. Models are downloaded TO the server — " +
                        "Room Browser only manages them.\n\n" +
                        "Ollama on a PC in your LAN? Start it with OLLAMA_HOST=0.0.0.0 (and allow " +
                        "port 11434 in the PC firewall), then connect to http://<pc-ip>:11434.\n" +
                        "Everything stays on your local network — no cloud, no telemetry.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ===========================================================================
// On-device engine (embedded llama.cpp — no Termux, no server, no network)
// ===========================================================================

/**
 * The "On-device engine" section: live engine status, the .gguf models in
 * app-private storage (use-in-chat / try / delete / export), SAF import,
 * URL download with pause/resume/cancel, and the honest CPU note.
 */
@Composable
private fun OnDeviceEngineSection(
    store: OnDeviceModelStore,
    downloads: OnDeviceDownloadController,
    onNotice: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engineState by LlamaEngine.state.collectAsState()
    val entries by downloads.entries.collectAsState()

    // ---- Model list refresh hooks (mirror the server-model pattern):
    // loaded once on composition, re-listed after every modelsRefresh bump
    // (import / delete) and whenever a download settles.
    var modelsRefresh by remember { mutableStateOf(0) }
    var models by remember { mutableStateOf<List<OnDeviceModel>>(emptyList()) }
    var modelsPage by remember { mutableIntStateOf(0) }
    LaunchedEffect(modelsRefresh) {
        models = withContext(Dispatchers.IO) { store.list() }
    }
    LaunchedEffect(Unit) { downloads.refreshListFromDisk() }
    // The controller DROPS an entry the moment its download completes (the
    // model then surfaces through the store's list()) — so "a download
    // settled" is what the re-list keys on. That signal is the controller's
    // monotonic `settled` counter, NOT a shrinking entries list: a small model
    // served from a local or very fast registry adds and removes its row
    // inside one frame, and a conflated StateFlow of a LIST never shows the
    // intermediate 1, so a size comparison would see only 0 → 0 and never
    // re-list — leaving the model installed on disk but the UI still offering
    // Install. The counter's value always differs from the last one collected.
    val settled by downloads.settled.collectAsState()
    LaunchedEffect(settled) {
        if (settled > 0L) modelsRefresh++
    }

    // ---- Try-model diagnostics: load + one 24-token generation, result in
    // a dialog. PROVES the whole native stack (JNI → llama.cpp → tokens).
    var tryingId by remember { mutableStateOf<String?>(null) }
    var tryResultId by remember { mutableStateOf<String?>(null) }
    var tryOutput by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tryingId) {
        val id = tryingId ?: return@LaunchedEffect
        // runCatching(Throwable) on purpose: any Java-side error thrown out of
        // the engine path (incl. UnsatisfiedLinkError-style Errors) becomes
        // an honest dialog message instead of killing the process.
        val output = runCatching {
            val file = store.fileFor(id)
            if (file == null) {
                "Could not find the model file for '$id'."
            } else {
                val loadError = LlamaEngine.load(id, file, contextTokens = 0, threads = 4)
                when {
                    loadError != null -> "Could not load the model: $loadError"
                    else -> {
                        val generated = LlamaEngine.completeRaw("Once upon a time", 24, 0.1f, 0.95f)
                        generated.fold(
                            onSuccess = { it.ifBlank { "(the model produced no tokens)" } },
                            onFailure = { "Could not generate: ${it.message ?: it.javaClass.simpleName}" }
                        )
                    }
                }
            }
        }.getOrElse { "Model test failed: ${it.message ?: it.javaClass.simpleName}" }
        tryResultId = id
        tryOutput = output
        tryingId = null
    }
    // Dismissing the result dialog is also where the model is RELEASED: the
    // test needed it loaded, and nothing after it does. The only unload() in
    // this file used to be on the DELETE path, so a single "Try" left a native
    // GGUF handle — hundreds of MB to several GB, invisible to the GC and to
    // onTrimMemory — resident in the default process for as long as the process
    // lived. Nothing breaks by letting go: a chat turn loads the model it names
    // on demand (LocalLlamaGateway, in the ':browser' process), and tapping Try
    // again simply loads again.
    fun dismissTryResult() {
        tryOutput = null
        // unload() is idempotent and safe when nothing is loaded (it returns on
        // a zero handle) — a try that failed before loading lands here too.
        runCatching { LlamaEngine.unload() }
    }
    tryOutput?.let { output ->
        AlertDialog(
            onDismissRequest = { dismissTryResult() },
            title = { Text("Model test — ${tryResultId ?: ""}") },
            text = {
                Text(
                    output,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                        .semantics { contentDescription = "localengine_try_output" }
                )
            },
            confirmButton = {
                TextButton(onClick = { dismissTryResult() }) { Text("Close") }
            }
        )
    }

    // ---- SAF export of a model file (mirrors the JSON-backup export above).
    var pendingExportId by remember { mutableStateOf<String?>(null) }
    val exportModelLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val id = pendingExportId
        pendingExportId = null
        if (uri != null && id != null) {
            scope.launch(Dispatchers.IO) {
                val ok = runCatching { store.exportToUri(id, uri) }.getOrDefault(false)
                onNotice(if (ok) "Exported $id" else "Export failed for $id")
            }
        }
    }

    // ---- SAF import of a .gguf file into app-private storage.
    val importModelLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { store.importFromUri(uri) }
                    .onSuccess { id ->
                        modelsRefresh++
                        onNotice("Imported $id")
                    }
                    .onFailure {
                        onNotice("Import failed: ${it.message ?: "could not read the file"}")
                    }
            }
        }
    }

    // ---- Delete confirmation (unloads the model first if it is loaded).
    var confirmDeleteOnDevice by remember { mutableStateOf<String?>(null) }
    confirmDeleteOnDevice?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDeleteOnDevice = null },
            title = { Text("Delete on-device model?") },
            text = { Text("Deletes the file $id.gguf from app-private storage. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteOnDevice = null
                    scope.launch(Dispatchers.IO) {
                        val ok = runCatching { store.delete(id) }.getOrDefault(false)
                        if (ok && LlamaEngine.state.value.modelId == id) {
                            runCatching { LlamaEngine.unload() }
                        }
                        modelsRefresh++
                        onNotice(if (ok) "Deleted $id" else "Could not delete $id")
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteOnDevice = null }) { Text("Cancel") }
            }
        )
    }

    // ---- "Use in chat": creates/updates the LOCAL provider and makes it the
    // default — the same NonCancellable pattern LocalAiController.useInChat
    // uses for the Ollama-native provider (finishing the activity mid-save
    // can never lose the provider or the default selection).
    fun useOnDeviceModelInChat(modelId: String) {
        scope.launch {
            try {
                val providerName = withContext(NonCancellable) {
                    val graph = (context.applicationContext as RoomBrowserApp).graph
                    val repo = graph.agentRepo
                    val appState = graph.appState
                    val existing = repo.providers()
                        .firstOrNull { it.protocol == AgentProviderEntity.PROTOCOL_LOCAL }
                    // Create-or-update the on-device provider; a blank API key
                    // keeps the stored ciphertext on edit (it is unused here).
                    val provider = AgentProviderStore.save(
                        repo,
                        id = existing?.id,
                        name = existing?.name ?: "On-device engine",
                        baseUrl = existing?.baseUrl?.takeIf { it.isNotBlank() } ?: "local://engine",
                        apiKey = "",
                        defaultModel = modelId,
                        protocol = AgentProviderEntity.PROTOCOL_LOCAL
                    ).getOrThrow()
                    // Merged against the stored blob like every other settings
                    // write: a snapshot read here would leave a window between
                    // the read and this save in which another writer's field
                    // could be overwritten (see updateAgentSettings).
                    appState.updateAgentSettings {
                        it.copy(defaultProviderId = provider.id, defaultModel = modelId)
                    }
                    existing?.name ?: "On-device engine"
                }
                onNotice("Agent set to $providerName · $modelId")
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                onNotice(t.message ?: "failed to select the model")
            }
        }
    }

    // ---- Engine status card ----
    RoomCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (LlamaEngine.available) {
                    "llama.cpp ${LlamaEngine.version() ?: "(version unknown)"}"
                } else {
                    "Engine unavailable in this build"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { contentDescription = "localengine_version" }
            )
            if (engineState.loading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Loading model…", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                engineState.modelId?.let {
                    Text(
                        "Loaded model: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            engineState.error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Text(
                "The browsing agent can run entirely on this phone's CPU.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }

    // ---- The models themselves ----
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .semantics { contentDescription = "localengine_models_list" }
    ) {
        if (models.isEmpty()) {
            EmptyState(
                title = "No on-device models yet",
                subtitle = "Import a .gguf file or download one below."
            )
        } else {
            Paging.slice(models, modelsPage).forEach { model ->
                OnDeviceModelRow(
                    model = model,
                    trying = tryingId == model.id,
                    onUseInChat = { useOnDeviceModelInChat(model.id) },
                    onTry = { tryingId = model.id },
                    onDelete = { confirmDeleteOnDevice = model.id },
                    onExport = {
                        pendingExportId = model.id
                        exportModelLauncher.launch("${model.id}.gguf")
                    }
                )
            }
            PagingFooter(
                page = modelsPage,
                total = models.size,
                onPage = { modelsPage = it },
                semanticsPrefix = "localengine_models_page"
            )
        }
    }

    // ---- Import ----
    OutlinedButton(
        onClick = { importModelLauncher.launch(arrayOf("*/*")) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { contentDescription = "localengine_import" }
    ) {
        Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Import .gguf file")
    }

    // ---- Download by URL (Range-resume, .part files) ----
    Column(Modifier.padding(horizontal = 16.dp)) {
        var downloadUrl by remember { mutableStateOf("") }
        var downloadName by remember { mutableStateOf("") }
        OutlinedTextField(
            value = downloadUrl,
            onValueChange = { downloadUrl = it },
            label = { Text("Download URL (.gguf)") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "localengine_url_field" }
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = downloadName,
            onValueChange = { downloadName = it },
            label = { Text("File name") },
            placeholder = { Text("model.gguf") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "localengine_filename_field" }
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val url = downloadUrl.trim()
                val fileName = downloadName.trim().ifBlank { "model.gguf" }
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    downloads.start(url, fileName)
                    onNotice("Downloading $fileName…")
                } else {
                    onNotice("The URL must start with http:// or https://")
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "localengine_download" }
        ) {
            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Download")
        }
    }

    // ---- Live download rows ----
    if (entries.isNotEmpty()) {
        Text(
            "On-device downloads",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        entries.forEach { entry ->
            OnDeviceDownloadRow(
                entry = entry,
                onPause = { downloads.pause(entry.fileName) },
                onResume = { downloads.start(entry.url, entry.fileName) },
                onCancel = { downloads.cancel(entry.fileName) }
            )
        }
    }

    // ---- The honest note ----
    Text(
        "Runs llama.cpp on your phone's CPU. Models are plain .gguf files stored in " +
            "app-private storage — import/export them freely. No Termux, no server, no network.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun OnDeviceModelRow(
    model: OnDeviceModel,
    trying: Boolean,
    onUseInChat: () -> Unit,
    onTry: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit
) {
    RoomCard(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                model.meta?.name ?: model.id,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val details = listOfNotNull(
                model.meta?.quantization ?: "unknown quant",
                formatBytes(model.sizeBytes).ifBlank { null },
                model.meta?.architecture,
                model.meta?.contextLength?.let { "ctx $it" } ?: "ctx ?"
            ).joinToString(" · ")
            Text(
                details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 6.dp)
            ) {
                OutlinedButton(
                    onClick = onUseInChat,
                    modifier = Modifier.semantics { contentDescription = "localengine_use_${model.id}" }
                ) { Text("Use in chat") }
                OutlinedButton(
                    onClick = onTry,
                    enabled = !trying,
                    modifier = Modifier.semantics { contentDescription = "localengine_try_${model.id}" }
                ) {
                    if (trying) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    } else {
                        Text("Try")
                    }
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.semantics { contentDescription = "localengine_delete_${model.id}" }
                ) { Icon(Icons.Filled.Delete, contentDescription = "Delete model") }
            }
            OutlinedButton(
                onClick = onExport,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .semantics { contentDescription = "localengine_export_${model.id}" }
            ) {
                Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Export")
            }
        }
    }
}

@Composable
private fun OnDeviceDownloadRow(
    entry: OnDeviceDownloadEntry,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            entry.fileName,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val status = when {
            entry.error != null -> "Error: ${entry.error}"
            entry.finished -> "Finished"
            entry.paused -> "Paused"
            else -> {
                val pct = entry.total?.takeIf { it > 0 }?.let { total ->
                    (entry.received * 100 / total).toInt()
                }
                if (pct != null) "Downloading… $pct%" else "Downloading…"
            }
        }
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = if (entry.error != null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        val knownTotal = entry.total?.takeIf { it > 0 }
        if (knownTotal != null) {
            LinearProgressIndicator(
                progress = { (entry.received.toFloat() / knownTotal).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )
            Text(
                "${formatBytes(entry.received)} / ${formatBytes(knownTotal)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        } else if (!entry.finished && !entry.paused && entry.error == null) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp)
        ) {
            when {
                entry.finished -> Unit
                entry.paused || entry.error != null -> Button(
                    onClick = onResume,
                    modifier = Modifier.semantics { contentDescription = "localengine_resume_${entry.fileName}" }
                ) { Text("Resume") }
                else -> FilledTonalButton(
                    onClick = onPause,
                    modifier = Modifier.semantics { contentDescription = "localengine_pause_${entry.fileName}" }
                ) { Text("Pause") }
            }
            TextButton(
                onClick = onCancel,
                modifier = Modifier.semantics { contentDescription = "localengine_cancel_${entry.fileName}" }
            ) { Text(if (entry.finished) "Clear" else "Cancel") }
        }
    }
}

// ===========================================================================
// Installed models
// ===========================================================================

@Composable
private fun InstalledModelRow(
    model: OllamaModelInfo,
    onUseInChat: () -> Unit,
    onDelete: () -> Unit
) {
    // Same RoomCard treatment as OnDeviceModelRow — the two model lists are
    // siblings on one screen and must read as one design system.
    RoomCard(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                model.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val details = listOf(
                formatBytes(model.sizeBytes),
                model.family,
                model.parameterSize,
                model.quantizationLevel
            ).filter { it.isNotBlank() }.joinToString(" · ")
            Text(
                details.ifBlank { "—" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 6.dp)
            ) {
                OutlinedButton(
                    onClick = onUseInChat,
                    modifier = Modifier.semantics { contentDescription = "localai_use_${model.name}" }
                ) { Text("Use in chat") }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.semantics { contentDescription = "localai_delete_${model.name}" }
                ) { Icon(Icons.Filled.Delete, contentDescription = "Delete model") }
            }
        }
    }
}

// ===========================================================================
// Downloads
// ===========================================================================

@Composable
private fun DownloadRow(
    tag: String,
    state: LocalAiController.DownloadState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            tag,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            state.statusLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(6.dp))
        val knownTotal = state.totalBytes
        val determinate = knownTotal != null && knownTotal > 0 && state.completedBytes > 0 &&
            state.phase != LocalAiController.Phase.STARTING &&
            state.phase != LocalAiController.Phase.VERIFYING
        if (determinate && knownTotal != null) {
            LinearProgressIndicator(
                progress = { (state.completedBytes.toFloat() / knownTotal).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (state.receivedBytes > 0 && knownTotal != null && knownTotal > 0) {
            Text(
                "${formatBytes(state.receivedBytes)} / ${formatBytes(knownTotal)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        state.error?.let {
            Text(
                "Error: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp)
        ) {
            when (state.phase) {
                LocalAiController.Phase.STARTING,
                LocalAiController.Phase.DOWNLOADING,
                LocalAiController.Phase.VERIFYING ->
                    FilledTonalButton(
                        onClick = onPause,
                        modifier = Modifier.semantics { contentDescription = "localai_pause_$tag" }
                    ) { Text("Pause") }
                LocalAiController.Phase.PAUSED,
                LocalAiController.Phase.FAILED ->
                    Button(
                        onClick = onResume,
                        modifier = Modifier.semantics { contentDescription = "localai_resume_$tag" }
                    ) { Text("Resume") }
                LocalAiController.Phase.SUCCESS ->
                    // cancelDownload also removes FINISHED rows — hence
                    // "Clear" on a SUCCESS entry calls the same method.
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.semantics { contentDescription = "localai_clear_$tag" }
                    ) { Text("Clear") }
            }
            if (state.phase != LocalAiController.Phase.SUCCESS) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.semantics { contentDescription = "localai_cancel_$tag" }
                ) { Text("Cancel") }
            }
        }
    }
}

// ===========================================================================
// Model catalog
// ===========================================================================

@Composable
private fun CatalogSection(
    controller: LocalAiController,
    store: OnDeviceModelStore,
    downloads: OnDeviceDownloadController,
    onNotice: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cores = Runtime.getRuntime().availableProcessors()
    val ramGb = remember {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        memoryInfo.totalMem / (1024L * 1024L * 1024L)
    }
    val suggestedTier = OllamaModelPresets.suggestedTierForRam(ramGb)

    // ---- Which catalog tags are already in the BUILT-IN engine's directory.
    // Install targets the on-device engine (the whole point of this screen on
    // a phone: no Termux, no server), so "Installed" is the store's list, NOT
    // the Ollama server's. Re-listed whenever a download settles — keyed on
    // the controller's monotonic `settled` counter rather than on a shrinking
    // entries list, which a download that finishes inside a single frame (the
    // common case for a small preset, and for a resumed `.part` with one chunk
    // left) never produces. See OnDeviceDownloadController.settled.
    var installedRefresh by remember { mutableStateOf(0) }
    var onDeviceIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(installedRefresh) {
        onDeviceIds = withContext(Dispatchers.IO) { store.list().map { it.id }.toSet() }
    }
    val settled by downloads.settled.collectAsState()
    LaunchedEffect(settled) {
        if (settled > 0L) installedRefresh++
    }

    // Tags with a registry resolve in flight, so a card can show it is busy
    // and a double-tap cannot start two resolves.
    var resolving by remember { mutableStateOf<Set<String>>(emptySet()) }

    // ---- ONE install path for every card on this screen: the curated presets
    // AND the live-discovery cards below. Both resolve the tag against the
    // PUBLIC registry and hand the real blob URL to the on-device download
    // controller. `POST /api/pull` (the old behaviour) needs an Ollama daemon
    // on localhost — a server a phone has no reason to be running, which is
    // why Install used to look like it went nowhere.
    val install: (tag: String, label: String) -> Unit = { tag, label ->
        if (tag !in resolving) {
            resolving = resolving + tag
            scope.launch {
                try {
                    val resolved = controller.resolveOnDeviceDownload(tag)
                    if (resolved == null) {
                        onNotice(
                            "Could not resolve $label — the registry has no " +
                                "model file for $tag."
                        )
                    } else {
                        downloads.start(resolved.url, resolved.fileName)
                        onNotice("Downloading $label to the on-device engine…")
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    // A 404 (tag gone), a dead network, a DNS failure — all
                    // end up here with an honest reason.
                    onNotice("Install failed: ${t.message ?: t.javaClass.simpleName}")
                } finally {
                    resolving = resolving - tag
                }
            }
        }
    }

    Text(
        "Curated presets that run well on phones. Install downloads the real .gguf " +
            "straight from the public Ollama registry onto the BUILT-IN engine — no " +
            "server, no Termux. (Running your own Ollama daemon? Use “Pull to server”.) " +
            "Sizes are approximate (default 4-bit tags). " +
            "On this device: ${Build.MODEL} · $cores cores · $ramGb GB — suggested tier: ${suggestedTier.label}.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp)
    )

    // ---- Live discovery: new families from the public ollama.com library.
    // Shown BEFORE the curated tiers — "what's new" is the question this
    // section answers; the tiers below remain the vetted, offline-safe path.
    RefreshCatalogSection(
        controller = controller,
        installedIds = onDeviceIds,
        busyTags = resolving,
        onInstall = install
    )

    OllamaPresetTier.values().forEach { tier ->
        Text(
            tier.label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
        )
        Text(
            tier.hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        OllamaModelPresets.byTier(tier).forEach { preset ->
            PresetCard(
                preset = preset,
                // The store's ids never carry ".gguf" — comparing the raw file
                // name here would never match (see OllamaRegistry.modelId).
                installed = OllamaRegistry.modelId(preset.tag) in onDeviceIds,
                busy = preset.tag in resolving,
                onInstall = { install(preset.tag, preset.label) },
                // The server path is kept for users who DO run Ollama (Termux
                // on the phone, or a PC on the LAN) — same one-tap pull as
                // before, just no longer the only option.
                onPullToServer = {
                    controller.pull(preset.tag)
                    onNotice("Pulling ${preset.label} to the Ollama server…")
                }
            )
        }
    }
}

// ===========================================================================
// Live catalog refresh — "Find new models"
// ===========================================================================

/** How many discovered family cards render (newest first); the rest are summed up. */
private const val DISCOVERED_CARDS_MAX = 20

@Composable
private fun RefreshCatalogSection(
    controller: LocalAiController,
    installedIds: Set<String>,
    busyTags: Set<String>,
    onInstall: (tag: String, label: String) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { controller.refreshCatalog() },
                enabled = controller.catalog !is LocalAiController.CatalogState.Loading,
                modifier = Modifier.semantics { contentDescription = "localai_refresh_catalog" }
            ) {
                if (controller.catalog is LocalAiController.CatalogState.Loading) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp)
                    )
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text("Find new models")
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                when (val state = controller.catalog) {
                    is LocalAiController.CatalogState.Idle -> Text(
                        "Fetch the live ollama.com library to discover newly published phone-suitable models.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    is LocalAiController.CatalogState.Loading -> Text(
                        "Fetching the newest models from ollama.com…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    is LocalAiController.CatalogState.Ready -> Text(
                        "Library updated ${relativeTime(state.fetchedAtMs)} · ${state.entries.size} families",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    is LocalAiController.CatalogState.Failed -> Text(
                        "Couldn't read the library (${state.reason}) — the curated presets below still work.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        when (val state = controller.catalog) {
            is LocalAiController.CatalogState.Ready -> {
                val discovered = controller.discoveredPhoneEntries()
                if (discovered.isEmpty()) {
                    Text(
                        "No new phone-suitable models beyond the presets right now — check again later.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                } else {
                    Text(
                        "New in the Ollama library — ${discovered.size} phone-suitable families",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                    discovered.take(DISCOVERED_CARDS_MAX).forEach { entry ->
                        LibraryEntryCard(
                            entry = entry,
                            installedIds = installedIds,
                            busyTags = busyTags,
                            onInstall = onInstall
                        )
                    }
                    if (discovered.size > DISCOVERED_CARDS_MAX) {
                        Text(
                            "+ ${discovered.size - DISCOVERED_CARDS_MAX} more families — open ollama.com/library in the browser to see them all.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun LibraryEntryCard(
    entry: OllamaLibraryEntry,
    installedIds: Set<String>,
    busyTags: Set<String>,
    onInstall: (tag: String, label: String) -> Unit
) {
    val phoneSizes = OllamaLibraryHeuristics.phoneSizes(entry)
    val oversized = entry.sizeTags.filterNot { it in phoneSizes }
    RoomCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (entry.updatedAt.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "updated ${entry.updatedAt}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (entry.description.isNotBlank()) {
                Text(
                    entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (entry.capabilities.isNotEmpty()) {
                Text(
                    entry.capabilities.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            // Phone-usable size tags → Install buttons (the badge text IS the
            // pullable tag suffix, verified against the real library). Install
            // resolves the tag on the PUBLIC registry and downloads the real
            // .gguf onto the BUILT-IN engine — the estimated size uses the q4
            // heuristic and is labeled as such; the download row shows the real
            // bytes.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 6.dp)
            ) {
                phoneSizes.forEach { badge ->
                    val fullTag = "${entry.name}:$badge"
                    // Store ids carry no ".gguf" — see OllamaRegistry.modelId.
                    val installed = OllamaRegistry.modelId(fullTag) in installedIds
                    if (installed) {
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text("$badge · installed") },
                            modifier = Modifier.semantics {
                                contentDescription = "localai_installed_$fullTag"
                            }
                        )
                    } else {
                        FilledTonalButton(
                            onClick = { onInstall(fullTag, entry.name) },
                            enabled = fullTag !in busyTags,
                            modifier = Modifier.semantics {
                                contentDescription = "localai_install_$fullTag"
                            }
                        ) {
                            Text(
                                "$badge · ~${
                                    OllamaModelPresets.formatSizeMb(
                                        OllamaLibraryHeuristics.estimatedDownloadMb(
                                            OllamaLibraryHeuristics.badgeParams(badge) ?: 0.0
                                        )
                                    )
                                } est.",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
            if (oversized.isNotEmpty()) {
                Text(
                    "Also available: ${oversized.joinToString(", ")} — too big for phones.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/** "just now" / "5 min ago" / "3 h ago" / "2 d ago" — integer math, no clock drift drama. */
private fun relativeTime(epochMs: Long): String {
    val minutes = ((System.currentTimeMillis() - epochMs) / 60_000L).toInt()
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 60 * 24 -> "${minutes / 60} h ago"
        else -> "${minutes / (60 * 24)} d ago"
    }
}

@Composable
private fun PresetCard(
    preset: OllamaModelPreset,
    installed: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
    onPullToServer: () -> Unit
) {
    RoomCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    preset.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (installed) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text("Installed") },
                        modifier = Modifier.semantics { contentDescription = "localai_installed_${preset.tag}" }
                    )
                } else {
                    Button(
                        onClick = onInstall,
                        // A resolve is a network round-trip — disable so a
                        // double-tap cannot start two.
                        enabled = !busy,
                        modifier = Modifier.semantics { contentDescription = "localai_install_${preset.tag}" }
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (busy) "Resolving…" else "Install")
                    }
                }
            }
            if (preset.recommended || preset.indonesianFriendly) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    if (preset.recommended) {
                        FilterChip(
                            selected = true,
                            enabled = false,
                            onClick = {},
                            label = { Text("Recommended") }
                        )
                    }
                    if (preset.indonesianFriendly) {
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text("ID-friendly") }
                        )
                    }
                }
            }
            Text(
                "${preset.params} · ~${OllamaModelPresets.formatSizeMb(preset.sizeMb)} download · " +
                    "min ${preset.minRamGb} GB RAM · ${preset.contextTokens} ctx",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                preset.strengths,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            // Secondary path, kept for users who DO run an Ollama daemon
            // (Termux on this phone, or a PC on the LAN). Quiet by design —
            // Install above is the on-device default.
            TextButton(
                onClick = onPullToServer,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                modifier = Modifier
                    .padding(top = 2.dp)
                    .semantics { contentDescription = "localai_pull_server_${preset.tag}" }
            ) {
                Text(
                    "Pull to Ollama server instead",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ===========================================================================
// Performance tuning (GPU · CPU)
// ===========================================================================

@Composable
private fun TuningSection(
    controller: LocalAiController,
    onNotice: (String) -> Unit
) {
    val context = LocalContext.current
    val cores = Runtime.getRuntime().availableProcessors()
    val ramGb = remember {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        memoryInfo.totalMem / (1024L * 1024L * 1024L)
    }
    Text(
        "Device: ${Build.MODEL} · $cores cores · $ramGb GB RAM",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
    Text(
        "GPU offload (num_gpu) speeds models up when your Ollama build supports the phone's GPU " +
            "(e.g. Termux builds with OpenCL/Adreno). Standard builds ignore it and fall back to CPU " +
            "automatically. Values apply to native-Ollama provider chats.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )

    // Local editable copy — committed by the Apply button below.
    var gpuAuto by remember(controller.tuning) { mutableStateOf(controller.tuning.gpuLayers == null) }
    var gpuLayers by remember(controller.tuning) {
        // When the user switches Auto off for the first time, offer a sane
        // phone default (8 layers) instead of a meaningless 0.
        mutableStateOf((controller.tuning.gpuLayers ?: 8).coerceIn(0, 99))
    }
    var cpuAuto by remember(controller.tuning) { mutableStateOf(controller.tuning.cpuThreads == null) }
    var cpuThreads by remember(controller.tuning) {
        mutableStateOf((controller.tuning.cpuThreads ?: 4).coerceIn(1, 16))
    }
    var contextWindow by remember(controller.tuning) {
        mutableStateOf(controller.tuning.contextWindow.coerceIn(512, 16384).toFloat())
    }
    var keepAlive by remember(controller.tuning) {
        mutableStateOf(controller.tuning.keepAliveMinutes.coerceIn(0, 60).toFloat())
    }

    Text(
        "GPU layers to offload",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp)
    )
    SettingSwitchRow(
        title = "Auto (server decides)",
        checked = gpuAuto,
        onCheckedChange = { gpuAuto = it }
    )
    if (!gpuAuto) {
        TuningSlider(
            label = "Layers",
            value = gpuLayers.toFloat(),
            valueLabel = gpuLayers.toString(),
            rangeStart = 0f,
            rangeEnd = 99f,
            steps = 98,
            onValueChange = { gpuLayers = it.toInt() }
        )
    }

    Text(
        "CPU threads",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp)
    )
    SettingSwitchRow(
        title = "Auto (server decides)",
        checked = cpuAuto,
        onCheckedChange = { cpuAuto = it }
    )
    if (!cpuAuto) {
        TuningSlider(
            label = "Threads",
            value = cpuThreads.toFloat(),
            valueLabel = cpuThreads.toString(),
            rangeStart = 1f,
            rangeEnd = 16f,
            steps = 14,
            onValueChange = { cpuThreads = it.toInt() }
        )
    }

    TuningSlider(
        label = "Context window (num_ctx)",
        value = contextWindow,
        valueLabel = contextWindow.toInt().toString(),
        rangeStart = 512f,
        rangeEnd = 16384f,
        // Snaps to multiples of 512: 512, 1024, … 16384 (32 positions).
        steps = 30,
        onValueChange = { contextWindow = it }
    )
    TuningSlider(
        label = "Keep model in memory",
        value = keepAlive,
        valueLabel = "${keepAlive.toInt()} min",
        rangeStart = 0f,
        rangeEnd = 60f,
        steps = 59,
        onValueChange = { keepAlive = it }
    )

    Button(
        onClick = {
            val newTuning = LocalAiTuning(
                host = controller.tuning.host,
                gpuLayers = if (gpuAuto) null else gpuLayers,
                cpuThreads = if (cpuAuto) null else cpuThreads,
                contextWindow = contextWindow.toInt(),
                keepAliveMinutes = keepAlive.toInt()
            ).clampToSanity()
            controller.saveTuning(newTuning)
            onNotice("Performance settings applied")
        },
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { contentDescription = "localai_apply_tuning" }
    ) { Text("Apply") }
}

@Composable
private fun TuningSlider(
    label: String,
    value: Float,
    valueLabel: String,
    rangeStart: Float,
    rangeEnd: Float,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = rangeStart..rangeEnd,
            steps = steps
        )
    }
}

// ===========================================================================
// Helpers
// ===========================================================================

/** Human-readable byte size ("812 MB", "1.2 GB"); blank when unknown. */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return ""
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.US, "%.1f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.0f MB", mb)
    }
}
