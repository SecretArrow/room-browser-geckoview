@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.roombrowser.agent.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.roombrowser.RoomBrowserApp
import com.roombrowser.agent.AiTaskController
import com.roombrowser.data.db.AiTaskEntity
import com.roombrowser.data.repo.permissions
import com.roombrowser.data.repo.runConfig
import com.roombrowser.data.repo.schedule
import com.roombrowser.domain.task.AiTaskExecutionMode
import com.roombrowser.domain.task.AiTaskPermissions
import com.roombrowser.domain.task.AiTaskRunConfig
import com.roombrowser.domain.task.ScheduleKind
import com.roombrowser.domain.task.TaskSchedule
import com.roombrowser.domain.paging.Paging
import com.roombrowser.domain.task.needsVisibleBrowser
import com.roombrowser.ui.common.PagingFooter
import com.roombrowser.ui.common.RoomBrowserTheme
import java.time.DayOfWeek
import java.time.ZoneId
import kotlinx.coroutines.launch

/**
 * Add / edit one scheduled AI task. Its OWN activity, in the default process
 * like the other agent screens: manual input (name, prompt, profile, schedule,
 * permissions), a live "next run" preview, and an enabled switch.
 *
 * EXTRA_TASK_ID > 0 → edit mode; absent / 0 → add mode.
 */
class AiTaskEditorActivity : ComponentActivity() {

    private lateinit var controller: AiTaskController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = AiTaskController(application)
        controller.start()
        controller.loadEditing(intent.getLongExtra(EXTRA_TASK_ID, 0L))
        setContent {
            RoomBrowserTheme {
                if (controller.editingLoaded) {
                    TaskEditorRoot(
                        controller = controller,
                        editing = controller.editing,
                        onDone = { finish() }
                    )
                } else {
                    Column(
                        Modifier.fillMaxSize().windowInsetsPadding(
                            WindowInsets.systemBars.union(WindowInsets.displayCutout)
                        ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) { CircularProgressIndicator() }
                }
            }
        }
    }

    override fun onDestroy() {
        controller.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TASK_ID = "ai_task_id"
    }
}

@Composable
private fun TaskEditorRoot(
    controller: AiTaskController,
    editing: AiTaskEntity?,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val existingSchedule = editing?.schedule
    val existingPermissions = editing?.permissions

    var name by rememberSaveable(editing) { mutableStateOf(editing?.name ?: "") }
    var prompt by rememberSaveable(editing) { mutableStateOf(editing?.prompt ?: "") }
    var profileId by rememberSaveable(editing) { mutableStateOf(editing?.profileId ?: "") }
    var kindName by rememberSaveable(editing) {
        mutableStateOf((existingSchedule?.kind ?: ScheduleKind.DAILY).name)
    }
    var intervalText by rememberSaveable(editing) {
        mutableStateOf((existingSchedule?.intervalMinutes ?: 15).toString())
    }
    var timeText by rememberSaveable(editing) {
        mutableStateOf(formatMinuteOfDay(existingSchedule?.minuteOfDay ?: 9 * 60))
    }
    var daysCsv by rememberSaveable(editing) {
        mutableStateOf(existingSchedule?.daysOfWeek?.joinToString(",") { it.value.toString() } ?: "")
    }
    var dayOfMonthText by rememberSaveable(editing) {
        mutableStateOf((existingSchedule?.dayOfMonth ?: 1).toString())
    }
    var quietEnabled by rememberSaveable(editing) {
        mutableStateOf(existingSchedule?.quietFromMinute != null && existingSchedule.quietToMinute != null)
    }
    var quietFromText by rememberSaveable(editing) {
        mutableStateOf(formatMinuteOfDay(existingSchedule?.quietFromMinute ?: 22 * 60))
    }
    var quietToText by rememberSaveable(editing) {
        mutableStateOf(formatMinuteOfDay(existingSchedule?.quietToMinute ?: 7 * 60))
    }
    var allowReadPage by rememberSaveable(editing) {
        mutableStateOf(existingPermissions?.allowReadPage ?: true)
    }
    var allowNavigate by rememberSaveable(editing) {
        mutableStateOf(existingPermissions?.allowNavigate ?: true)
    }
    var allowInteract by rememberSaveable(editing) {
        mutableStateOf(existingPermissions?.allowInteract ?: true)
    }
    var allowPost by rememberSaveable(editing) {
        mutableStateOf(existingPermissions?.allowPost ?: false)
    }
    var enabled by rememberSaveable(editing) { mutableStateOf(editing?.enabled ?: true) }
    // Blank means Auto for both: the provider the agent is configured with, and
    // a model on it that answers.
    var providerIdText by rememberSaveable(editing) {
        mutableStateOf(editing?.runConfig?.providerId?.toString() ?: "")
    }
    var modelText by rememberSaveable(editing) { mutableStateOf(editing?.runConfig?.model ?: "") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelsLoading by remember { mutableStateOf(false) }
    var modelsError by remember { mutableStateOf<String?>(null) }
    var modelsRefresh by remember { mutableIntStateOf(0) }
    /* Which page of the provider's model list is on screen. Reset whenever the
     * provider changes: a page number is only meaningful for the list it was
     * turned to. */
    var modelsPage by remember { mutableIntStateOf(0) }
    var modeName by rememberSaveable(editing) {
        mutableStateOf((editing?.runConfig?.executionMode ?: AiTaskExecutionMode.HEADLESS).name)
    }
    var saveError by remember { mutableStateOf<String?>(null) }

    // A new task needs a profile; default to the first one as soon as the
    // list arrives, so Save is reachable without an extra tap.
    LaunchedEffect(controller.profiles, editing) {
        if (profileId.isBlank()) {
            controller.profiles.firstOrNull()?.let { profileId = it.id.value }
        }
    }

    val selectedProvider = controller.providers.firstOrNull { it.id.toString() == providerIdText }
    val knownModels = listOfNotNull(selectedProvider?.defaultModel)
        .plus(models)
        .plus(listOfNotNull(modelText.takeIf { it.isNotBlank() }))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    LaunchedEffect(selectedProvider?.id, modelsRefresh, knownModels.size) {
        modelsPage = Paging.clampPage(modelsPage, knownModels.size)
    }

    // The provider's own list, read when the choice changes (and on demand),
    // never on every keystroke.
    LaunchedEffect(selectedProvider?.id, modelsRefresh) {
        val provider = selectedProvider
        if (provider == null) {
            models = emptyList()
            modelsError = null
            return@LaunchedEffect
        }
        modelsLoading = true
        modelsError = null
        controller.fetchModels(provider)
            .onSuccess { models = it }
            .onFailure { modelsError = it.message ?: "listing failed" }
        modelsLoading = false
    }

    val kind = ScheduleKind.entries.firstOrNull { it.name == kindName } ?: ScheduleKind.DAILY
    val mode = AiTaskExecutionMode.entries.firstOrNull { it.name == modeName }
        ?: AiTaskExecutionMode.HEADLESS
    val schedule = buildSchedule(
        kind = kind,
        intervalText = intervalText,
        timeText = timeText,
        daysCsv = daysCsv,
        dayOfMonthText = dayOfMonthText,
        quietEnabled = quietEnabled,
        quietFromText = quietFromText,
        quietToText = quietToText
    )
    val valid = name.isNotBlank() && prompt.isNotBlank() && profileId.isNotBlank() && schedule != null

    Scaffold(
        modifier = Modifier.imePadding(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(if (editing == null) "Add AI task" else "Edit AI task") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                    }
                }
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth()) {
                saveError?.let {
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
                            // Snapshot the typed values FIRST: the write runs on
                            // the graph's APPLICATION scope (it must outlive this
                            // window) and must never read Compose state.
                            val draftSchedule = schedule ?: return@Button
                            val draftId = editing?.id
                            val draftName = name
                            val draftPrompt = prompt
                            val draftProfileId = profileId
                            val draftEnabled = enabled
                            val draftPermissions = AiTaskPermissions(
                                allowReadPage = allowReadPage,
                                allowNavigate = allowNavigate,
                                allowInteract = allowInteract,
                                allowPost = allowPost
                            )
                            val draftRunConfig = AiTaskRunConfig(
                                providerId = providerIdText.toLongOrNull(),
                                model = modelText.trim().takeIf { it.isNotBlank() },
                                executionMode = mode
                            )
                            val appScope = (context.applicationContext as RoomBrowserApp).graph.appScope
                            appScope.launch {
                                runCatching {
                                    controller.save(
                                        id = draftId,
                                        name = draftName,
                                        prompt = draftPrompt,
                                        profileId = draftProfileId,
                                        schedule = draftSchedule,
                                        permissions = draftPermissions,
                                        runConfig = draftRunConfig,
                                        enabled = draftEnabled
                                    )
                                }.onSuccess {
                                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                                        scope.launch { onDone() }
                                    }
                                }.onFailure {
                                    val message = it.message ?: "could not save"
                                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                                        scope.launch { saveError = message }
                                    }
                                }
                            }
                        },
                        enabled = valid,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (editing == null) "Save task" else "Save changes") }
                    OutlinedButton(onClick = onDone) { Text("Cancel") }
                }
            }
        }
    ) { padding ->
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
                value = name,
                onValueChange = { name = it },
                label = { Text("Task name") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "ai_task_name_field" }
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Prompt") },
                placeholder = { Text("e.g. Check the news headlines and summarize the top three") },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "ai_task_prompt_field" }
            )

            Spacer(Modifier.height(14.dp))
            Text("Profile", style = MaterialTheme.typography.labelLarge)
            if (controller.profiles.isEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "No profiles yet — create one before scheduling a task.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                controller.profiles.forEach { profile ->
                    FilterChip(
                        selected = profileId == profile.id.value,
                        onClick = { profileId = profile.id.value },
                        label = { Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.semantics {
                            contentDescription = "ai_task_profile_${profile.id.value}"
                        }
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Text("AI provider & model", style = MaterialTheme.typography.labelLarge)
            Text(
                "Auto uses whichever configured provider answers, and finds a model there that really does.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            if (controller.providers.isEmpty()) {
                Text(
                    "No AI provider is set up yet — add one in AI Agent Settings. Until then this task " +
                        "stays queued rather than failing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilterChip(
                    selected = providerIdText.isBlank(),
                    onClick = { providerIdText = "" },
                    label = { Text("Auto") },
                    modifier = Modifier.semantics { contentDescription = "ai_task_provider_auto" }
                )
                controller.providers.forEach { provider ->
                    FilterChip(
                        selected = providerIdText == provider.id.toString(),
                        onClick = {
                            providerIdText = provider.id.toString()
                            // Keep a model that belongs to no other provider's
                            // list out of this one: the id would be sent to a
                            // provider that has never heard of it.
                            if (modelText.isNotBlank() && modelText !in models) modelText = ""
                        },
                        label = { Text(provider.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.semantics {
                            contentDescription = "ai_task_provider_${provider.id}"
                        }
                    )
                }
            }

            if (selectedProvider != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Models on ${selectedProvider.name}", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(8.dp))
                    if (modelsLoading) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    } else {
                        IconButton(
                            onClick = { modelsRefresh++ },
                            modifier = Modifier.semantics {
                                contentDescription = "ai_task_fetch_models"
                            }
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Find models")
                        }
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = modelText.isBlank(),
                        onClick = { modelText = "" },
                        label = { Text("Auto") },
                        modifier = Modifier.semantics { contentDescription = "ai_task_model_auto" }
                    )
                    Paging.slice(knownModels, modelsPage).forEach { candidate ->
                        FilterChip(
                            selected = modelText == candidate,
                            onClick = { modelText = candidate },
                            label = { Text(candidate, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.semantics {
                                contentDescription = "ai_task_model_$candidate"
                            }
                        )
                    }
                }
                PagingFooter(
                    page = modelsPage,
                    total = knownModels.size,
                    onPage = { modelsPage = it },
                    semanticsPrefix = "ai_task_model_page"
                )
                modelsError?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Could not list models: $it — type the id below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = modelText,
                    onValueChange = { modelText = it.trim() },
                    label = { Text("Model") },
                    placeholder = { Text("Leave blank for Auto") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "ai_task_model_field" }
                )
            }

            Spacer(Modifier.height(14.dp))
            Text("Runs in", style = MaterialTheme.typography.labelLarge)
            Text(
                aiTaskModeHint(mode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AiTaskExecutionMode.entries.forEach { entry ->
                    FilterChip(
                        selected = mode == entry,
                        onClick = { modeName = entry.name },
                        label = { Text(aiTaskModeLabel(entry)) },
                        modifier = Modifier.semantics {
                            contentDescription = "ai_task_mode_${entry.name.lowercase()}"
                        }
                    )
                }
            }
            if (mode.needsVisibleBrowser) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Needs Room Browser open on this profile; until then the run waits.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(14.dp))
            Text("Schedule", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ScheduleKind.entries.forEach { entry ->
                    FilterChip(
                        selected = kind == entry,
                        onClick = { kindName = entry.name },
                        label = { Text(entry.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        modifier = Modifier.semantics {
                            contentDescription = "ai_task_kind_${entry.name.lowercase()}"
                        }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            when (kind) {
                ScheduleKind.INTERVAL -> {
                    OutlinedTextField(
                        value = intervalText,
                        onValueChange = { intervalText = it.filter { c -> c.isDigit() }.take(6) },
                        label = { Text("Every N minutes") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "ai_task_interval_field" }
                    )
                    schedule?.let { scheduleClampNotice(it) }?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                ScheduleKind.DAILY -> TimeField(
                    value = timeText,
                    onValueChange = { timeText = it },
                    semantics = "ai_task_time_field"
                )
                ScheduleKind.WEEKLY -> {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        WEEKDAY_ORDER.forEach { day ->
                            val selected = day.value.toString() in parseDays(daysCsv)
                            FilterChip(
                                selected = selected,
                                onClick = { daysCsv = toggleDay(daysCsv, day) },
                                label = { Text(day.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())) },
                                modifier = Modifier.semantics {
                                    contentDescription = "ai_task_day_${day.value}"
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    TimeField(
                        value = timeText,
                        onValueChange = { timeText = it },
                        semantics = "ai_task_time_field"
                    )
                }
                ScheduleKind.MONTHLY -> {
                    OutlinedTextField(
                        value = dayOfMonthText,
                        onValueChange = { dayOfMonthText = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("Day of month (1-31)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "ai_task_day_of_month_field" }
                    )
                    Spacer(Modifier.height(6.dp))
                    TimeField(
                        value = timeText,
                        onValueChange = { timeText = it },
                        semantics = "ai_task_time_field"
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = quietEnabled, onCheckedChange = { quietEnabled = it })
                Text("Quiet hours (defer runs in this window)")
            }
            if (quietEnabled) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TimeField(
                        value = quietFromText,
                        onValueChange = { quietFromText = it },
                        label = "Quiet from",
                        semantics = "ai_task_quiet_from_field",
                        modifier = Modifier.weight(1f)
                    )
                    TimeField(
                        value = quietToText,
                        onValueChange = { quietToText = it },
                        label = "Quiet to",
                        semantics = "ai_task_quiet_to_field",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                schedule?.let { nextRunText(it, System.currentTimeMillis(), ZoneId.systemDefault()) }
                    ?: "Fill the schedule fields to see when this task will run.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(14.dp))
            Text("Permissions", style = MaterialTheme.typography.labelLarge)
            Text(
                "What this task may do on the page, decided now and fixed for the task.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            PermissionRow(
                label = "Read pages",
                description = "Read page content, scroll and wait",
                checked = allowReadPage,
                semantics = "ai_task_perm_read",
                onCheckedChange = { allowReadPage = it }
            )
            PermissionRow(
                label = "Navigate",
                description = "Open URLs, search, go back, open/switch/close tabs",
                checked = allowNavigate,
                semantics = "ai_task_perm_navigate",
                onCheckedChange = { allowNavigate = it }
            )
            PermissionRow(
                label = "Interact",
                description = "Click elements, type into fields and submit forms",
                checked = allowInteract,
                semantics = "ai_task_perm_interact",
                onCheckedChange = { allowInteract = it }
            )
            PermissionRow(
                label = "Post",
                description = "Like, repost, reply and create posts on your behalf",
                checked = allowPost,
                semantics = "ai_task_perm_post",
                onCheckedChange = { allowPost = it }
            )

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = enabled, onCheckedChange = { enabled = it })
                Text("Enabled", modifier = Modifier.padding(start = 12.dp))
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TimeField(
    value: String,
    onValueChange: (String) -> Unit,
    semantics: String,
    label: String = "Time (HH:mm)",
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(5)) },
        label = { Text(label) },
        singleLine = true,
        isError = parseMinuteOfDay(value) == null,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = semantics }
    )
}

@Composable
private fun PermissionRow(
    label: String,
    description: String,
    checked: Boolean,
    semantics: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp)
            .semantics { contentDescription = semantics },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(Modifier.padding(start = 4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun aiTaskModeLabel(mode: AiTaskExecutionMode): String = when (mode) {
    AiTaskExecutionMode.HEADLESS -> "Headless"
    AiTaskExecutionMode.HEADED -> "Headed browser"
    AiTaskExecutionMode.STANDARD -> "Standard browser"
}

private fun aiTaskModeHint(mode: AiTaskExecutionMode): String = when (mode) {
    AiTaskExecutionMode.HEADLESS ->
        "Runs in a hidden page, so nothing appears on screen while it works."
    AiTaskExecutionMode.HEADED ->
        "Runs in a tab you can watch, which pages that only fill themselves in once they are " +
            "drawn need. The tab is closed when the run ends."
    AiTaskExecutionMode.STANDARD ->
        "Runs in a tab you can watch and leaves it open when the run ends, so you can carry on " +
            "from where it stopped."
}

/** The typed fields as a [TaskSchedule], or null while any field the chosen
 *  kind needs is missing or malformed — which is also what gates Save. */
private fun buildSchedule(
    kind: ScheduleKind,
    intervalText: String,
    timeText: String,
    daysCsv: String,
    dayOfMonthText: String,
    quietEnabled: Boolean,
    quietFromText: String,
    quietToText: String
): TaskSchedule? {
    val interval = if (kind == ScheduleKind.INTERVAL) {
        intervalText.toIntOrNull()?.takeIf { it > 0 } ?: return null
    } else {
        15
    }
    val minuteOfDay = if (kind == ScheduleKind.INTERVAL) {
        null
    } else {
        parseMinuteOfDay(timeText) ?: return null
    }
    val dayOfMonth = if (kind == ScheduleKind.MONTHLY) {
        dayOfMonthText.toIntOrNull()?.takeIf { it in 1..31 } ?: return null
    } else {
        null
    }
    val days = if (kind == ScheduleKind.WEEKLY) {
        parseDays(daysCsv).mapNotNull { it.toIntOrNull() }
            .mapNotNull { runCatching { DayOfWeek.of(it) }.getOrNull() }
            .toSet()
    } else {
        emptySet()
    }
    val quietFrom = if (quietEnabled) parseMinuteOfDay(quietFromText) ?: return null else null
    val quietTo = if (quietEnabled) parseMinuteOfDay(quietToText) ?: return null else null
    return TaskSchedule(
        kind = kind,
        intervalMinutes = interval,
        minuteOfDay = minuteOfDay,
        daysOfWeek = days,
        dayOfMonth = dayOfMonth,
        quietFromMinute = quietFrom,
        quietToMinute = quietTo
    )
}

private fun parseDays(csv: String): Set<String> =
    csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

private fun toggleDay(csv: String, day: DayOfWeek): String {
    val values = parseDays(csv).mapNotNull { it.toIntOrNull() }.toMutableSet()
    if (!values.add(day.value)) values.remove(day.value)
    return values.sorted().joinToString(",")
}
