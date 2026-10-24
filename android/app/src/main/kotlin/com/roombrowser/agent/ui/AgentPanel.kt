@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.roombrowser.agent.ui

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.roombrowser.RoomBrowserApp
import com.roombrowser.agent.AgentAttachment
import com.roombrowser.agent.AgentEntry
import com.roombrowser.agent.ApprovalAnswer
import com.roombrowser.agent.BrowserAgentController
import com.roombrowser.agent.PluginFormContext
import com.roombrowser.browser.BrowserViewModel
import com.roombrowser.data.db.AgentProviderEntity
import com.roombrowser.data.plugins.PluginInstallResult
import com.roombrowser.data.repo.AgentMode
import com.roombrowser.domain.agent.AgentPause
import com.roombrowser.domain.agent.AgentPauseLabels
import com.roombrowser.domain.agent.AgentPlan
import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.domain.agent.SessionApprovals
import com.roombrowser.domain.paging.Paging
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.PagingFooter
import com.roombrowser.ui.common.RoomBottomSheetShape
import com.roombrowser.ui.common.RoomCardShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The floating AI Agent panel — the agent-mode chat experience layered over
 * the live browser:
 *
 *  - COLLAPSED: a status pill (bottom-right) that streams live progress
 *    ("clicking [12] Sign in…") while the page stays visible behind it
 *  - EXPANDED: a panel with the conversation, tool-step cards, streaming
 *    answers, approvals and the composer — 72% of the window where there is
 *    room for that, nearly all of a short (landscape) one
 *
 * The user keeps seeing the browser being driven autonomously — the same
 * interaction model as chat.z.ai's agent mode, adapted to a phone browser.
 */
@Composable
fun AgentPanelHost(
    viewModel: BrowserViewModel,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSessions: () -> Unit
) {
    val agent = viewModel.agent

    // Defensive cross-process re-sync: a provider configured in the settings
    // activities (default process) must be visible here immediately, even if
    // Room's multi-instance invalidation ping was lost on a slow filesystem.
    // Fires BOTH when the panel expands AND when the browser resumes while
    // the panel is already open (returning from the settings activities).
    RefreshProvidersOnResume(agent = agent, active = expanded)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The panel's height follows the window it is given instead of being
        // a flat 72% of it. 72% reads well on a portrait phone and strands
        // the composer in landscape: 72% of a ~320dp-tall window is ~230dp,
        // and the header plus one conversation row leave the input — the one
        // control the panel exists for — a sliver at the bottom edge. Short
        // windows therefore get almost the whole slot. maxHeight is this
        // Box's own measured space, so it is already net of the browser
        // chrome and the system bars, unlike a screen-size constant.
        val panelHeightFraction = if (maxHeight < 480.dp) 0.94f else 0.72f
        if (expanded) {
            BackHandler { onExpandedChange(false) }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(panelHeightFraction),
                // The panel is a sheet in everything but name (it carries the sheet's
                // own drag handle), so it wears the sheet's shape — the 28dp it
                // used to carry was the app's most oversized corner.
                shape = RoomBottomSheetShape,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 16.dp
            ) {
                Column(Modifier.fillMaxSize()) {
                    AgentPanelHeader(
                        agent = agent,
                        activeTabId = viewModel.activeTabId,
                        onCollapse = { onExpandedChange(false) },
                        onNewSession = { agent.newSession() },
                        onOpenSessions = onOpenSessions,
                        onOpenSettings = onOpenSettings
                    )
                    AgentConversation(
                        agent = agent,
                        onOpenSettings = onOpenSettings,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                    // Keyed on the tab AND the chat so unsent input cannot
                    // follow the user into another conversation: the composer
                    // text and its attached files are remembered across both
                    // switches otherwise, and the next Send would file them
                    // under a chat they were never written for. The tab is
                    // only HALF of "whose input is this" — opening a chat from
                    // history (openSession) and starting a new one (newSession)
                    // change the conversation without changing the tab, so the
                    // session id is the second key: either one changing hands
                    // the composer over empty, discarding every remember{}/
                    // rememberSaveable{} the draft lives in.
                    key(viewModel.activeTabId, agent.activeSessionId) {
                        AgentComposer(
                            agent = agent,
                            // No imePadding: the browser Scaffold already
                            // lifts this whole panel above the keyboard and
                            // marks the IME inset consumed. Padding for it
                            // again here cost the composer the keyboard's
                            // height a second time, which is what pushed the
                            // text field out of its own box.
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        } else if (agent.settings.showAgentButton || agent.running) {
            // The floating button is OPT-IN (hidden by default — see
            // "Show AI Agent button" in Browser/AI settings). While a task
            // is actively running the pill always shows, so live progress
            // stays visible; it hides again when the turn finishes.
            //
            // Its home is the bottom-end corner, and it stays THERE until the
            // user drags it — the saved position is a fraction of the
            // draggable range, so a position set in portrait survives
            // landscape, a resize and a different phone (see AgentSettings).
            DraggableAgentPill(
                agent = agent,
                onClick = { onExpandedChange(true) },
                bounds = with(androidx.compose.ui.platform.LocalDensity.current) {
                    IntSize(maxWidth.roundToPx(), maxHeight.roundToPx())
                },
                modifier = Modifier.align(Alignment.TopStart)
            )
        }

        // An approval raised while the panel is closed has no card to appear
        // on, so it would wait out its timeout unseen. Never both at once.
        val pending = agent.approval
        if (!expanded && pending != null) {
            AgentApprovalDialog(pending, agent)
        }

        // The plugin surfaces the panel and the full-screen agent share:
        // picker sheet, grant dialog, ZIP install.
        PluginSurfaces(agent)
    }
}

/**
 * Room Agent as its own screen: the same header, transcript and composer the
 * panel draws, on a window with no browser behind it.
 *
 * It renders the SAME [BrowserViewModel] the browser is using — see
 * [com.roombrowser.browser.BrowserSession] — so a turn started here and a turn
 * started in the panel are one conversation on one page. That is the whole
 * distinction between the two surfaces: where the chat is drawn, and nothing
 * about what the turn may do.
 */
@Composable
fun AgentScreenHost(
    viewModel: BrowserViewModel,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSessions: () -> Unit
) {
    val agent = viewModel.agent
    BackHandler { onClose() }
    RefreshProvidersOnResume(agent = agent, active = true)

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
    ) {
        AgentPanelHeader(
            agent = agent,
            activeTabId = viewModel.activeTabId,
            onCollapse = onClose,
            onNewSession = { agent.newSession() },
            onOpenSessions = onOpenSessions,
            onOpenSettings = onOpenSettings
        )
        AgentConversation(
            agent = agent,
            onOpenSettings = onOpenSettings,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )
        // Same two-dimensional composer key as the panel: the tab AND the
        // chat decide whose input this is — see AgentPanelHost.
        key(viewModel.activeTabId, agent.activeSessionId) {
            AgentComposer(
                agent = agent,
                // The panel is lifted by the browser Scaffold, which is why it
                // needs no imePadding. There is no Scaffold here, so this one
                // asks for the keyboard's height itself.
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
            )
        }
    }

    agent.approval?.let { AgentApprovalDialog(it, agent) }
    PluginSurfaces(agent)
}

/**
 * Re-reads the provider list when this surface becomes active, and again every
 * time the activity resumes. A provider configured in the settings activities
 * (default process) has to be visible here immediately even if Room's
 * cross-process invalidation ping was lost on a slow filesystem, and returning
 * from those activities is exactly when it matters. The installed plugins
 * have the same cross-process writer — the plugin manager — so their state is
 * re-read on the same resume.
 */
@Composable
private fun RefreshProvidersOnResume(agent: BrowserAgentController, active: Boolean) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeCount by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeCount++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(active, resumeCount) {
        if (active) {
            agent.refreshProviders()
            // The manager activity installs and uninstalls from the DEFAULT
            // process; a lost invalidation ping would leave this panel
            // deciding no plugin exists. One re-read on resume closes the
            // window, the same defense the providers get.
            agent.pluginUi.refresh()
        }
    }
}

/** Breathing room kept between the pill and every edge of its box. */
private val AGENT_PILL_MARGIN = 12.dp

/**
 * The AI Agent pill, positionable by a LONG-PRESS drag.
 *
 * WHY LONG PRESS, and not a plain drag: the pill is mostly a button — a
 * plain drag would steal the tap that opens the panel, because every finger
 * wobbles a pixel or two and a drag detector that claims the gesture on
 * movement alone turns a slightly imprecise tap into a reposition. Requiring
 * the press to be HELD first makes the two gestures unambiguous, and it is
 * the convention the platform already taught every user for moving an icon
 * (and the mouse equivalent on desktop: press, hold, move).
 *
 * WHY FRACTIONS: the position is stored as a fraction of the range the pill
 * can move through, never as pixels. Pixels are wrong the moment the box
 * changes shape — a portrait position is off-screen in landscape, and a
 * position from a 1080p phone lands in a corner of a tablet. A fraction
 * needs no migration and no per-orientation copy.
 *
 * THE PILL CANNOT LEAVE THE VIEWPORT: both fractions are clamped to 0..1 as
 * they are applied AND as they are stored, so no drag, rotation or resize
 * can strand it at an edge where it cannot be grabbed again.
 */
@Composable
private fun DraggableAgentPill(
    agent: BrowserAgentController,
    onClick: () -> Unit,
    bounds: IntSize,
    modifier: Modifier = Modifier
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val marginPx = with(density) { AGENT_PILL_MARGIN.roundToPx() }
    var pillSize by remember { mutableStateOf(IntSize.Zero) }

    // The distance the pill's top-left corner may travel. Zero on an axis
    // means the pill already fills it — the drag is then a no-op there
    // rather than a division by zero.
    val rangeX = AgentPillPosition.range(bounds.width, pillSize.width, marginPx)
    val rangeY = AgentPillPosition.range(bounds.height, pillSize.height, marginPx)

    var fracX by remember { mutableFloatStateOf((agent.settings.agentButtonXFrac ?: 1f).coerceIn(0f, 1f)) }
    var fracY by remember { mutableFloatStateOf((agent.settings.agentButtonYFrac ?: 1f).coerceIn(0f, 1f)) }
    // A long press that has already claimed the gesture must not ALSO deliver
    // a click on release: holding the pill still and letting go would
    // otherwise open the panel, which reads as the app ignoring the drag.
    var longPressClaimed by remember { mutableStateOf(false) }

    AgentStatusPill(
        agent = agent,
        onClick = {
            if (longPressClaimed) longPressClaimed = false else onClick()
        },
        modifier = modifier
            .onSizeChanged { pillSize = it }
            .offset {
                IntOffset(
                    AgentPillPosition.offsetPx(fracX, rangeX, marginPx),
                    AgentPillPosition.offsetPx(fracY, rangeY, marginPx)
                )
            }
            // Restores the designed corner without a drag. Screen-reader users
            // have no long-press-drag, so the position is also reachable as a
            // named action rather than being mouse-only.
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Reset agent button position") {
                        fracX = 1f
                        fracY = 1f
                        agent.updateSettings {
                            it.copy(agentButtonXFrac = null, agentButtonYFrac = null)
                        }
                        true
                    }
                )
            }
            .pointerInput(rangeX, rangeY) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { longPressClaimed = true },
                    onDragEnd = {
                        // Persist on RELEASE, not per frame: a drag is dozens
                        // of events and every one of them would otherwise be a
                        // Room write.
                        agent.updateSettings {
                            it.copy(agentButtonXFrac = fracX, agentButtonYFrac = fracY)
                        }
                    },
                    onDragCancel = { longPressClaimed = false },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        fracX = AgentPillPosition.dragBy(fracX, dragAmount.x, rangeX)
                        fracY = AgentPillPosition.dragBy(fracY, dragAmount.y, rangeY)
                    }
                )
            }
    )
}

// ------------------------------------------------------------------- pill

@Composable
private fun AgentStatusPill(
    agent: BrowserAgentController,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = modifier.semantics { contentDescription = "AI Agent" }
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            val status = when {
                agent.running -> agent.statusLine ?: "Working…"
                agent.activeProvider == null -> "Set up Agent"
                else -> "Agent"
            }
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp)
            )
            if (agent.running) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            }
        }
    }
}

// ------------------------------------------------------------------- header

@Composable
private fun AgentPanelHeader(
    agent: BrowserAgentController,
    activeTabId: String?,
    onCollapse: () -> Unit,
    onNewSession: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var showModelPicker by remember { mutableStateOf(false) }
    var showSurfacePicker by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Room Agent", style = MaterialTheme.typography.titleMedium)
            val provider = agent.activeProvider
            val modelLine = when {
                provider == null -> "No provider configured"
                agent.chatModelAuto -> "${provider.name} · Auto"
                else -> "${provider.name} · ${agent.activeModel ?: provider.defaultModel}"
            }
            Text(
                modelLine,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    // clickable BEFORE padding so the vertical padding
                    // counts toward the touch target (≥32dp effective row).
                    .clickable(enabled = provider != null) { showModelPicker = true }
                    .semantics { contentDescription = "agent_model" }
                    .padding(vertical = 8.dp)
            )
            // Where a turn runs, on the same tappable line pattern as the
            // model above. It has to be visible BEFORE a turn is sent: the two
            // surfaces disagree about what "click that button" means, and only
            // one of them can be watched.
            Row(verticalAlignment = Alignment.CenterVertically) {
                // What the turn may DO leads, and the surface it runs on
                // follows: a turn that suddenly refuses to act reads as a
                // broken agent, so the mode is the control the eye should land
                // on first rather than the setting beside it.
                ChatModePicker(agent)
                Text(
                    if (agent.chatHeadless) "Headless · hidden page" else "Headed · this tab",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showSurfacePicker = true }
                        .semantics { contentDescription = "agent_surface" }
                        .padding(vertical = 6.dp)
                )
            }
            // While a turn runs the panel stays with it, even if the user
            // walks off to another tab — otherwise the running work would
            // vanish from the screen it was started on. Says so, because a
            // conversation that ignores the tab behind it otherwise reads as
            // a bug.
            val shown = agent.conversationTabId
            if (agent.running && shown != null && shown != activeTabId) {
                Text(
                    "Running in another tab",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        // Both are off while a turn runs, because both are refused then:
        // newSession/openSession bail on `running` so the running turn keeps
        // the one transcript list the panel has. A tap that silently did
        // nothing is worse than a control that says it is unavailable.
        IconButton(onClick = onNewSession, enabled = !agent.running) {
            Icon(Icons.Filled.Add, contentDescription = "New agent chat")
        }
        IconButton(onClick = onOpenSessions, enabled = !agent.running) {
            Icon(Icons.Filled.History, contentDescription = "Agent chat history")
        }
        // The plan panel toggle. A pure user flip (planPanelOpen's contract),
        // deliberately NOT gated on running: a running turn is exactly when
        // the step list moves, and the sheet only READS the plan. Lit while
        // open so the button doubles as the sheet's state indicator.
        IconButton(onClick = { agent.planPanelOpen = !agent.planPanelOpen }) {
            Icon(
                Icons.Filled.Checklist,
                contentDescription = "Plan panel",
                tint = if (agent.planPanelOpen) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Agent settings")
        }
        IconButton(onClick = onCollapse) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Minimize agent panel")
        }
    }

    if (showModelPicker) {
        ModelPickerSheet(agent = agent, onDismiss = { showModelPicker = false })
    }
    if (showSurfacePicker) {
        ChatSurfaceSheet(agent = agent, onDismiss = { showSurfacePicker = false })
    }
    // The plan sheet lives HERE, in the header both hosts share, so one site
    // covers the panel and the full-screen agent — the same reason the two
    // sheets above do. Dismissal is its only closer: plan updates, chat
    // switches and the turn ending must never write planPanelOpen (its
    // contract on the controller).
    if (agent.planPanelOpen) {
        PlanPanelSheet(agent = agent, onDismiss = { agent.planPanelOpen = false })
    }
}

/**
 * What a turn may do, as a dropdown beside the model it runs on: Ask, Plan or
 * YOLO. A menu rather than the sheet the two lines above open, because the
 * choice is three short words and this is where the user is standing when they
 * decide the agent should stop — or stop asking.
 */
@Composable
private fun ChatModePicker(agent: BrowserAgentController) {
    var expanded by remember { mutableStateOf(false) }
    val mode = agent.settings.chatMode
    Box {
        FilterChip(
            // Always "on": the chip shows the mode in force, and the chosen
            // entry in the menu is what tells the three apart.
            selected = true,
            onClick = { expanded = true },
            label = { Text(mode.title) },
            trailingIcon = {
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    // YOLO is the one mode that removes every check at once,
                    // so it is the one that does not look like the other two.
                    tint = if (mode == AgentMode.YOLO) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp)
                )
            },
            colors = if (mode == AgentMode.YOLO) {
                FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer
                )
            } else {
                FilterChipDefaults.filterChipColors()
            },
            modifier = Modifier.semantics { contentDescription = "agent_mode" }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AgentMode.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.title)
                            Text(
                                option.blurb,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    leadingIcon = {
                        if (option == mode) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Spacer(Modifier.size(18.dp))
                        }
                    },
                    onClick = {
                        expanded = false
                        agent.setAgentMode(option)
                    }
                )
            }
        }
    }
}

// ------------------------------------------------------------------- conversation

/**
 * One stable key per transcript entry, for the LazyColumn.
 *
 * The entries carry a timestamp (and, for tools, the call id) rather than an
 * id of their own, and a timestamp is not unique on its own — two rows written
 * in the same millisecond would collide, and a duplicate key is a crash, not a
 * cosmetic problem. A repeat is therefore disambiguated by how many identical
 * ones preceded it. That number cannot move under a survivor when the list
 * only grows at the end or is updated in place; the one splice the controller
 * makes is "load older" prepending rows in front, and a prepended row shares
 * no survivor's stem unless it carries the same class AND the same millisecond
 * — possible only for rows written at opposite ends of a long chat within one
 * clock tick, where the honest cost is a survivor losing remembered item state
 * (an expanded "Thought process" closing), never a duplicate key: the pass
 * recomputes from scratch, so uniqueness holds regardless.
 *
 * F-16, why this stays a POSITIONAL pass rather than a per-entry key lambda:
 * the disambiguating suffix counts the identical PREDECESSORS, which a lone
 * entry cannot know about itself — `itemsIndexed` with `keys[index]` is the
 * only cheap scheme that can express it. The contract it leans on is the
 * controller's whole-list rule (append a row, copy()-replace one in place,
 * prepend a page of older rows, or swap the entire list at a chat switch —
 * never rearrange the middle), and [TranscriptKeyCache] leans on the same
 * contract through [sameKeyStem], which must keep reading exactly the fields
 * this function reads — the branch plus `at`/`callId`, nothing else.
 *
 * `internal` so the JVM test can pin it; not `private`, because a key that
 * collides takes the whole panel down.
 */
internal fun transcriptKeys(entries: List<AgentEntry>): List<String> {
    val seen = HashMap<String, Int>()
    return entries.map { entry ->
        val base = when (entry) {
            is AgentEntry.Tool -> "tool:${entry.callId}"
            is AgentEntry.User -> "user:${entry.at}"
            is AgentEntry.Assistant -> "assistant:${entry.at}"
            is AgentEntry.Notice -> "notice:${entry.at}"
            is AgentEntry.Plugin -> "plugin:${entry.at}"
        }
        val repeat = (seen.merge(base, 1) { a, b -> a + b } ?: 1) - 1
        if (repeat == 0) base else "$base#$repeat"
    }
}

/**
 * [transcriptKeys] with a memory, so a streamed token does not re-run it.
 *
 * F-03: every delta copy()-replaces the whole entries LIST, so
 * `remember(entries) { transcriptKeys(entries) }` re-ran an O(n) HashMap pass
 * per token. The cache answers from memory whenever the new list provably
 * yields the same keys: the same object, or the same SIZE with the same key
 * stem at every index — a stem being exactly what [transcriptKeys] reads
 * (the branch plus `at`/`callId`; token appends and the tool/assistant
 * in-place copies never touch those). Keys are a pure function of the stems,
 * so equal stems mean equal keys: the hit is correct by construction, not by
 * hope, and any controller change that does move a stem falls through to a
 * full recompute. The stem scan itself is allocation-free pointer and Long
 * compares; only a genuinely new shape — a row landed, older rows were
 * prepended, a chat switched, the list shrank — pays the HashMap again.
 *
 * `internal` for the same reason as [transcriptKeys]: the JVM test pins the
 * hit/miss behavior that keeps item state attached to its row.
 */
internal class TranscriptKeyCache {
    private var source: List<AgentEntry>? = null
    private var keys: List<String> = emptyList()

    fun keysFor(entries: List<AgentEntry>): List<String> {
        val cached = source ?: return recompute(entries)
        if (entries === cached) return keys
        if (entries.size == cached.size && sameKeyStems(cached, entries)) {
            source = entries
            return keys
        }
        return recompute(entries)
    }

    private fun recompute(entries: List<AgentEntry>): List<String> {
        source = entries
        keys = transcriptKeys(entries)
        return keys
    }
}

/** [TranscriptKeyCache]'s hit test: same stem, index for index, no allocation. */
private fun sameKeyStems(a: List<AgentEntry>, b: List<AgentEntry>): Boolean {
    for (i in a.indices) {
        if (!sameKeyStem(a[i], b[i])) return false
    }
    return true
}

/**
 * Whether two entries answer to the same [transcriptKeys] base string. The
 * runtime class picks the branch; `callId`/`at` feed it. NOTHING else may be
 * read here: a field this misses is a field a streaming update could change
 * without the cache noticing.
 */
private fun sameKeyStem(a: AgentEntry, b: AgentEntry): Boolean {
    if (a::class != b::class) return false
    return when (a) {
        is AgentEntry.Tool -> a.callId == (b as AgentEntry.Tool).callId
        is AgentEntry.User -> a.at == (b as AgentEntry.User).at
        is AgentEntry.Assistant -> a.at == (b as AgentEntry.Assistant).at
        is AgentEntry.Notice -> a.at == (b as AgentEntry.Notice).at
        is AgentEntry.Plugin -> a.at == (b as AgentEntry.Plugin).at
    }
}

// How often a still-streaming surface re-renders and re-pins itself while it
// is actually growing. 250ms sits well past the per-token storm the old code
// paid (a full re-parse and a scrollToItem per delta) and well inside what a
// person reads as live typing.
private const val STREAM_REFRESH_MS = 250L

// A stream whose content has not grown for this many rounds parks its
// heartbeat. The tick never bumps without growth, so a parked stream costs
// nothing while it sleeps; the park exists because a CANCELLED turn can leave
// a row `streaming = true` for as long as the chat stays open (the
// cancellation path appends its notice without finalizing the bubble), and an
// unparked ticker would wake the CPU four times a second for that, forever —
// including with the app backgrounded. A stream that stalls longer than the
// park is not lost: the coarse length bucket (STREAM_REPARSE_CHARS) keeps
// re-rendering on growth, and completion always renders exactly.
private const val STREAM_TICK_IDLE_ROUNDS = 240

// Coarse growth bucket backing the heartbeat: while a ticker is parked (or
// simply between beats) this much new text still forces a re-render and a
// re-pin. 256 characters is about half a phone screen of text — invisible in
// the flow of a stream, and the guarantee that the text can never freeze
// mid-answer.
private const val STREAM_REPARSE_CHARS = 256

// How many items from the end still count as "at the bottom" for the
// auto-scroll gate below (and the jump chip): a reader standing this close
// is having the bottom move under them as rows land, so following them is
// continuation, not a yank. It also covers the one-frame window where a row
// has landed but is not laid out yet — the gate reads the layout as the
// frame BEFORE the append left it, and a normal turn adds 1–2 items at the
// tail (a row, then its approval card) between two effect firings.
private const val NEAR_BOTTOM_SLACK_ITEMS = 3

/**
 * The heartbeat itself: a tick counter that advances ONLY when [observed] has
 * grown since the last beat, while [active] holds. Reading the returned
 * [State] in composition is what carries a beat into that scope's keys.
 *
 * [observed] is a lambda because the coroutine outlives many recompositions —
 * `rememberUpdatedState` keeps the latest one, so every beat measures the
 * current content rather than the content at launch. When [active] flips the
 * effect relaunches (its key), which both cancels the old loop in flight and
 * resets the idle patience for the next activation.
 */
@Composable
private fun rememberStreamTick(active: Boolean, observed: () -> Int): State<Int> {
    val latest = rememberUpdatedState(observed)
    val tick = remember { mutableStateOf(0) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var idle = 0
        var seen = latest.value.invoke()
        while (idle < STREAM_TICK_IDLE_ROUNDS) {
            delay(STREAM_REFRESH_MS)
            val now = latest.value.invoke()
            if (now == seen) {
                idle++
            } else {
                idle = 0
                seen = now
                tick.value++
            }
        }
    }
    return tick
}

@Composable
private fun AgentConversation(
    agent: BrowserAgentController,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val entries = agent.entries
    val plan = agent.plan

    // Keys are per ENTRY, not per slot. Position was the key before, so item
    // state remembered inside an item — an expanded "Thought process" — stayed
    // behind at that offset and was handed to whatever the next chat put there.
    // The cache rather than a bare remember is what takes this off the
    // per-token path: a streaming delta hands it a same-size, same-stem list
    // and gets the very same key list back, with no HashMap pass and no
    // allocation.
    val keyCache = remember { TranscriptKeyCache() }
    val keys = keyCache.keysFor(entries)
    // The last entry's key — the one thing whose VALUE only moves when the
    // tail itself moves. It is the auto-scroll's landing signal below.
    val tailKey = keys.lastOrNull()
    // The "load older" affordance occupies the list's slot 0 whenever older
    // rows exist beyond the loaded window, so every index arithmetic below
    // (and the auto-scroll's) counts it. A chat under one window renders
    // with no leading item at all — the item list the pre-paging panel drew.
    val leading = if (agent.hasOlderEntries) 1 else 0

    // Live auto-scroll: instant while streaming, animated for new entries.
    // The plan card and the ApprovalCard are composed as items AFTER the last
    // entry, so when either is showing it is the true last item and the list
    // has to scroll past the last entry to keep it above the fold.
    //
    // F-03: the old keys included entries.lastOrNull(), whose identity — and
    // equality, since the streaming Assistant is copy()-replaced on every
    // token — changed per delta, restarting this effect (and its
    // scrollToItem) per token. The keys now move only when a row LANDS at
    // the END (tailKey — a same-millisecond repeat still bumps its
    // disambiguation suffix, so nothing that lands can go unnoticed), when
    // the plan/approval tail appears, or when the streaming heartbeat/bucket
    // below moves: the newest row is re-pinned at most STREAM_REFRESH_MS
    // behind its own growth while it streams, and the -1s make the
    // completion flip re-fire (the fence leaving shrinks the row).
    //
    // tailKey rather than entries.size is what keeps paging history in from
    // jumping to the bottom: older rows land in FRONT, the tail's key —
    // class, timestamp and predecessor count — is untouched by them, and the
    // effect that pins the newest row simply does not fire.
    val lastRow = entries.lastOrNull()
    val streamingRow = if (lastRow is AgentEntry.Assistant && lastRow.streaming) lastRow else null
    val streamTick = rememberStreamTick(streamingRow != null) {
        (entries.lastOrNull() as? AgentEntry.Assistant)?.let { it.text.length + it.thinking.length } ?: 0
    }

    // "Near the bottom" as derived state, so the auto-scroll gate below and
    // the jump chip read the SAME definition of where the reader stands.
    // Reading layoutInfo inside derivedStateOf recomputes on every layout
    // pass but only NOTIFIES its readers when the boolean itself flips — no
    // recomposition loop, and no extra work beyond what scrolling already
    // pays. Without the flip-only propagation, tracking this in composition
    // would re-compose the transcript on every scroll frame.
    val nearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 1 - NEAR_BOTTOM_SLACK_ITEMS
        }
    }
    // The gate resets per chat: opening or switching a conversation has no
    // reader to protect, so a fresh chat's first auto-scroll is unconditional
    // — it is the chat opening on its newest row, the behavior the panel has
    // always had. Consumed by the first run that scrolls; deliberately kept
    // when a run is cancelled mid-scroll, so the next firing re-forces it.
    var forceFirstScroll by remember(agent.activeSessionId) { mutableStateOf(true) }

    LaunchedEffect(
        tailKey,
        agent.approval,
        plan.size,
        if (streamingRow == null) -1 else streamTick.value,
        if (streamingRow == null) -1
        else (streamingRow.text.length + streamingRow.thinking.length) / STREAM_REPARSE_CHARS
    ) {
        // The reader's courtesy gate: auto-scroll only follows someone ALREADY
        // near the bottom. Content that lands while they have scrolled up to
        // read leaves the viewport where they put it — the old unconditional
        // scrollToItem yanked the row they were reading off the screen on
        // every plan step, approval and stream beat. The jump chip below is
        // the way back down; a reader at the bottom (and every fresh chat,
        // via forceFirstScroll) keeps the exact pin behavior as before.
        if ((entries.isNotEmpty() || plan.isNotEmpty()) && (forceFirstScroll || nearBottom)) {
            val lastItem = lastTranscriptItem(
                leading = leading,
                entryCount = entries.size,
                planShown = plan.isNotEmpty(),
                approvalShown = agent.approval != null
            )
            listState.scrollToItem(lastItem)
            // scrollToItem pins that row to the TOP of the viewport whenever
            // the transcript outgrows it: the newest row floats over a blank
            // lower half while every older row is scrolled out of the
            // composed window — off screen, out of the semantics tree, gone
            // from anything a11y (and an e2e poll) can see. A transcript
            // belongs bottom-aligned, its end at the viewport's end: one
            // layout-info pass measures the blank the pin left below the
            // last row and lifts the list by exactly that much. A list
            // shorter than the viewport cannot scroll at all, and a last row
            // taller than it has no blank to lift — both leave this a no-op.
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            if (last != null && last.index == lastItem) {
                val leftover = info.viewportEndOffset - (last.offset + last.size)
                if (leftover > 0) listState.scrollToItem(lastItem, -leftover)
            }
            // Consumed only AFTER the scroll ran to completion: an effect run
            // cancelled mid-scroll leaves the flag standing, so the next
            // firing still forces the fresh chat onto its newest row.
            forceFirstScroll = false
        }
    }

    // The empty state yields to the transcript when EITHER half of it has
    // something to show: a chat whose stored plan outlived its renderable
    // rows (nothing else parsed, or the window simply has none) must still
    // show its plan card. Gating the early return on entries alone used to
    // swallow the whole LazyColumn — plan card included — so a plan-only chat
    // looked exactly like an empty one. Including plan.isEmpty() here is the
    // least invasive fix: the list below already renders PlanCard for a
    // non-empty plan, so the same code path serves both cases.
    if (entries.isEmpty() && plan.isEmpty()) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            if (agent.activeProvider == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.SmartToy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("No AI provider configured", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Add an OpenAI-compatible provider (Z.ai, OpenAI, OpenRouter, Groq, DeepSeek, Ollama, LM Studio or a custom endpoint). " +
                            "The model list is fetched automatically from the provider.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onOpenSettings,
                        modifier = Modifier.semantics { contentDescription = "agent_configure" }
                    ) { Text("Configure providers") }
                }
            } else {
                Text(
                    "Ask the agent to browse autonomously — e.g.\n\"Open example.com and summarize it\", \"Search for Kotlin coroutines and open the top result\",\nor \"Like the visible posts about AI on this feed\", \"Reply to this thread: thanks!\", \"Post: hello world\".\nTasks keep running in the background (see the notification) when you leave the app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    // The Box is the transcript's stage: the list fills it, and the jump chip
    // overlays its bottom edge when the reader has scrolled away from the
    // live tail and the auto-scroll stopped following them (see nearBottom).
    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (agent.hasOlderEntries) {
                item(key = LOAD_OLDER_ITEM_KEY) { LoadOlderMessages(agent, listState) }
            }
            itemsIndexed(entries, key = { index, _ -> keys[index] }) { _, entry ->
                when (entry) {
                    is AgentEntry.User -> UserBubble(entry)
                    is AgentEntry.Assistant -> AssistantMessage(entry)
                    is AgentEntry.Tool -> ToolCard(entry)
                    is AgentEntry.Notice -> NoticeLine(entry)
                    is AgentEntry.Plugin -> PluginCardView(
                        card = entry.card,
                        pluginName = entry.pluginName,
                        tag = pluginCardTag(agent.pluginUi.installed, entry.pluginName)
                    )
                }
            }
            if (plan.isNotEmpty()) item { PlanCard(plan) }
            agent.approval?.let { approval -> item { ApprovalCard(approval, agent) } }
        }
        if (!nearBottom && entries.isNotEmpty()) {
            JumpToLatestChip(
                entryCount = entries.size,
                planShown = plan.isNotEmpty(),
                approvalShown = agent.approval != null,
                leading = leading,
                listState = listState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

/** The LazyColumn slot the "load older" affordance always occupies. */
private const val LOAD_OLDER_ITEM_KEY = "load-older"

/**
 * The transcript's true LAST item: the newest entry's slot, plus the plan
 * card and the approval card, which are composed as items AFTER the last
 * entry (see the item block in [AgentConversation]). One home for the
 * arithmetic the auto-scroll and the jump chip must agree on — a scroll that
 * lands one short of the real tail leaves the plan card below the fold,
 * which reads as a broken pin rather than a stylistic choice.
 *
 * Returns a negative index only when there is nothing to show at all
 * (no entries and neither tail card), which callers treat as "do nothing".
 */
private fun lastTranscriptItem(
    leading: Int,
    entryCount: Int,
    planShown: Boolean,
    approvalShown: Boolean
): Int {
    var last = leading + entryCount - 1
    if (planShown) last++
    if (approvalShown) last++
    return last
}

/**
 * The "jump to latest" pill: shown only while the reader has scrolled away
 * from the live tail — the same [NEAR_BOTTOM_SLACK_ITEMS] definition the
 * auto-scroll gate uses — because content landing under the fold would
 * otherwise arrive invisibly. One tap animates back down to the true last
 * item ([lastTranscriptItem], the same target the auto-scroll pins to), and
 * the pill removes itself as the scroll lands and `nearBottom` flips.
 */
@Composable
private fun JumpToLatestChip(
    entryCount: Int,
    planShown: Boolean,
    approvalShown: Boolean,
    leading: Int,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    Box(modifier.padding(bottom = 10.dp)) {
        Surface(
            shape = RoundedCornerShape(50),
            tonalElevation = 3.dp,
            shadowElevation = 2.dp
        ) {
            TextButton(
                onClick = {
                    scope.launch {
                        val target = lastTranscriptItem(
                            leading = leading,
                            entryCount = entryCount,
                            planShown = planShown,
                            approvalShown = approvalShown
                        )
                        if (target >= 0) listState.animateScrollToItem(target)
                    }
                },
                modifier = Modifier.semantics { contentDescription = "agent_jump_latest" }
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Jump to latest")
            }
        }
    }
}

/**
 * The transcript's one promise about the past: rows older than the loaded
 * window exist, and this brings the next window in.
 *
 * The viewport's anchor — its first visible item and that item's offset — is
 * read on the tap, while the OLD layout is still the truth, and repaid the
 * moment the rows land ([olderPageScrollTarget] says where to). One plain
 * button, worded as the plain thing it does, because that is the panel's
 * voice everywhere else.
 */
@Composable
private fun LoadOlderMessages(agent: BrowserAgentController, listState: LazyListState) {
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
        TextButton(onClick = {
            val firstIndex = listState.firstVisibleItemIndex
            val firstOffset = listState.firstVisibleItemScrollOffset
            scope.launch {
                val added = agent.loadOlderEntries()
                val target = olderPageScrollTarget(firstIndex, added, agent.hasOlderEntries)
                // scrollToItem lands before the next frame lays the grown
                // list out, so the one frame the user sees is the one that
                // already has both the new rows and the old position.
                if (target != null) listState.scrollToItem(target, firstOffset)
            }
        }) { Text("Load older messages") }
    }
}

/**
 * Where the viewport's top goes after [added] older entries landed in front
 * of it: null when nothing should move — nothing landed, or the reader was
 * at the list's very top, where staying put is showing them exactly the
 * older rows they asked for. Otherwise the entry that was at [firstIndex] is
 * [added] slots further down, one less when the affordance left with the
 * last page (it was the slot at 0).
 *
 * `internal` for the same reason as [transcriptKeys]: the panel's no-jump
 * promise is only as good as this arithmetic, and a JVM test pins it.
 */
internal fun olderPageScrollTarget(firstIndex: Int, added: Int, stillMore: Boolean): Int? {
    if (added <= 0 || firstIndex <= 0) return null
    return firstIndex + added + (if (stillMore) 0 else -1)
}

/**
 * The agent's own step list, as the model last wrote it.
 *
 * It is the one surface that shows the SHAPE of a long run rather than its
 * individual steps: a scroll of tool cards says what happened, and this says
 * what the agent set out to do, what it has finished and what it is on now.
 * It changes nothing on the page, and it is kept with the chat: reopening the
 * chat shows the same card, and the model is handed its own list on the next
 * turn.
 */
@Composable
private fun PlanCard(items: List<AgentPlan.Item>) {
    val done = items.count { it.status == AgentPlan.Status.DONE }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "agent_plan" }
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Checklist,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text("Plan", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                Text(
                    "$done of ${items.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            items.forEach { PlanStepRow(it) }
        }
    }
}

/**
 * One plan step — the status mark and the model's own sentence about the
 * step. Extracted from [PlanCard] so the transcript card and the plan panel
 * sheet ([PlanPanelSheet]) render the SAME row: they are two windows onto
 * one list, and a step that looks different in each would read as two
 * different lists.
 */
@Composable
private fun PlanStepRow(item: AgentPlan.Item) {
    val (mark, tint) = when (item.status) {
        AgentPlan.Status.DONE ->
            Icons.Filled.CheckCircle to MaterialTheme.colorScheme.primary
        AgentPlan.Status.IN_PROGRESS ->
            Icons.Filled.PlayArrow to MaterialTheme.colorScheme.onSurface
        AgentPlan.Status.PENDING ->
            Icons.Filled.RadioButtonUnchecked to MaterialTheme.colorScheme.onSurfaceVariant
        // A status the model invented ("failed", "blocked", an emoji) lands
        // here as FAILED: an error mark, the row still on the list — the
        // failure is VISIBLE, which is the whole point of the card.
        AgentPlan.Status.FAILED ->
            Icons.Filled.ErrorOutline to MaterialTheme.colorScheme.error
    }
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Icon(mark, contentDescription = null, modifier = Modifier.size(14.dp), tint = tint)
        Spacer(Modifier.width(8.dp))
        Text(
            item.text,
            style = MaterialTheme.typography.bodySmall,
            color = if (item.status == AgentPlan.Status.PENDING) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

/**
 * Small "copy this text" affordance rendered under a chat bubble: one tap
 * puts the bubble's exact text on the system clipboard (no long-press
 * guesswork — discoverable, and e2e-assertable) so a previously SENT prompt
 * can be pasted straight back into the composer and re-processed by the
 * agent, and a received answer can be reused elsewhere. Feedback = the icon
 * flips to a check plus a tiny "Copied" label for ~1.8s.
 */
@Composable
private fun CopyTextButton(
    copied: Boolean,
    onCopy: () -> Unit,
    desc: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = onCopy,
            modifier = Modifier.semantics { contentDescription = desc }
        ) {
            Icon(
                if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (copied) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
        if (copied) {
            Text(
                "Copied",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun UserBubble(entry: AgentEntry.User) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(entry.at) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_800)
            copied = false
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            // Fractional width instead of a fixed 320dp cap so user bubbles
            // keep a sensible share of the panel on tablets as well.
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Text(
                entry.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
        // Copy affordance (right-aligned under the bubble): grabs the exact
        // text previously sent so it can be re-pasted and re-run in the
        // composer — "copy teks yang pernah dikirim ke Chat".
        CopyTextButton(
            copied = copied,
            onCopy = {
                clipboard.setText(AnnotatedString(entry.text))
                copied = true
            },
            desc = "agent_copy_user"
        )
    }
}

@Composable
private fun AssistantMessage(entry: AgentEntry.Assistant) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(entry.at) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_800)
            copied = false
        }
    }
    Column(Modifier.fillMaxWidth()) {
        if (entry.thinking.isNotBlank()) {
            ThinkingBlock(entry.thinking)
            Spacer(Modifier.height(6.dp))
        }
        val text = entry.text + if (entry.streaming) " ▍" else ""
        if (text.isNotBlank()) {
            MarkdownText(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                streaming = entry.streaming
            )
            // Copy affordance for FINISHED answers only — copying a
            // half-streamed reply would grab a truncated text.
            if (!entry.streaming && entry.text.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                CopyTextButton(
                    copied = copied,
                    onCopy = {
                        clipboard.setText(AnnotatedString(entry.text))
                        copied = true
                    },
                    desc = "agent_copy_assistant"
                )
            }
        }
    }
}

@Composable
private fun ThinkingBlock(thinking: String) {
    // NOT keyed on `thinking`: during streaming a new key per token would
    // reset the rememberSaveable and collapse the user's expanded state.
    var open by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            // A panel surface, so it takes the app's card radius rather than a
            // hardcoded 10dp of its own.
            .clip(RoomCardShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (open) "Hide thought process" else "Thought process",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (open) {
            Text(
                thinking,
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun ToolCard(entry: AgentEntry.Tool) {
    Surface(
        shape = RoomCardShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            val icon = toolIcon(entry.name)
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                if (entry.running) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                } else {
                    Icon(
                        if (entry.ok) Icons.Filled.Check else Icons.Filled.ErrorOutline,
                        contentDescription = if (entry.ok) "step done" else "step failed",
                        tint = if (entry.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        entry.label,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (entry.summary.isNotBlank() && !entry.running) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        entry.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun toolIcon(name: String): ImageVector = when (name) {
    AgentTools.NAVIGATE, AgentTools.SEARCH_WEB -> Icons.Filled.Public
    AgentTools.READ_PAGE -> Icons.Filled.Description
    AgentTools.CLICK -> Icons.Filled.TouchApp
    AgentTools.FILL_INPUT -> Icons.Filled.Edit
    AgentTools.PRESS_ENTER -> Icons.Filled.KeyboardReturn
    AgentTools.SCROLL -> Icons.Filled.ArrowDownward
    AgentTools.GO_BACK -> Icons.AutoMirrored.Filled.ArrowBack
    AgentTools.OPEN_NEW_TAB -> Icons.Filled.Add
    AgentTools.LIST_TABS, AgentTools.SWITCH_TAB -> Icons.Filled.Tab
    AgentTools.CLOSE_TAB -> Icons.Filled.Close
    AgentTools.PLAN -> Icons.Filled.Checklist
    in AgentTools.DEVTOOLS_TOOLS -> Icons.Filled.BugReport
    else -> Icons.Filled.Search
}

@Composable
private fun NoticeLine(entry: AgentEntry.Notice) {
    Text(
        entry.text,
        style = MaterialTheme.typography.labelMedium,
        color = if (entry.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 6,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    )
}

@Composable
private fun ApprovalCard(approval: com.roombrowser.agent.AgentApproval, agent: BrowserAgentController) {
    Surface(
        shape = RoomCardShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Confirm agent action", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Text(approval.label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = { agent.respondApproval(ApprovalAnswer.Allow) }) { Text("Allow") }
                OutlinedButton(onClick = { agent.respondApproval(ApprovalAnswer.Deny) }) { Text("Deny") }
                if (!approval.destructive) {
                    // Third answer, and the one that outlives the action. It
                    // sits last and is labelled with its consequence rather
                    // than a joke: tapping it stops the agent asking about
                    // anything ever again, which is too much to hide behind
                    // the word "YOLO". An action that cannot be undone never
                    // shows it — there is no way to pre-approve those.
                    TextButton(onClick = { agent.respondApproval(ApprovalAnswer.AlwaysAllow) }) {
                        Text("Always allow")
                    }
                }
            }
            // The narrow answer, on its own line: it is the one a person
            // reaches for when the same question has arrived three times, and
            // it must be readable rather than the button pushed off the edge.
            if (remembersSite(approval)) {
                TextButton(onClick = { agent.respondApproval(ApprovalAnswer.AllowForSite) }) {
                    Text("Allow on this site")
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                approvalFootnote(approval),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

/**
 * Whether the prompt may offer the site-scoped answer at all. It is the one
 * answer that has to be kept honestly, so it is withheld where it could not be:
 * an action that cannot be undone, a wallet request, a tool whose own prompt is
 * the point (a live authenticator code typed into a login form), and a page
 * with no origin to file the answer under.
 */
private fun remembersSite(approval: com.roombrowser.agent.AgentApproval): Boolean =
    approval.origin != null &&
        !approval.destructive &&
        approval.name != BrowserAgentController.WALLET_APPROVAL_NAME &&
        approval.name !in SessionApprovals.NEVER_REMEMBERED

/**
 * What the third answer does, which is not one thing: for a wallet request
 * "Always allow" covers that request alone, and there is no trust store to
 * switch off.
 */
private fun approvalFootnote(approval: com.roombrowser.agent.AgentApproval): String = when {
    approval.destructive ->
        "This cannot be undone, and it asks every time — there is no answer here " +
            "that stops the asking."
    approval.name == BrowserAgentController.WALLET_APPROVAL_NAME ->
        "\"Always allow\" covers this request only — wallet requests always ask, and " +
            "the next one asks again."
    remembersSite(approval) ->
        "\"Allow on this site\" stops this one tool asking again on ${approval.origin} " +
            "until the chat ends; \"Always allow\" stops the agent asking about " +
            "anything, until you turn it off in AI Agent settings."
    else ->
        "\"Always allow\" stops the agent asking about anything, until you turn it " +
            "off in AI Agent settings."
}

/**
 * The confirmation prompt for the browser surface, for a request that arrived
 * with the chat minimized. Dismissal denies, so an unanswered prompt can never
 * read as consent.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AgentApprovalDialog(
    approval: com.roombrowser.agent.AgentApproval,
    agent: BrowserAgentController
) {
    AlertDialog(
        onDismissRequest = { agent.respondApproval(ApprovalAnswer.Deny) },
        title = { Text("Confirm agent action") },
        text = {
            // Material3 gives this slot no scrolling of its own and clips an
            // overlong body, and a wallet label carries the whole request
            // detail — so it scrolls here, capped, rather than lose the part
            // that says what the answers do.
            Column(
                Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(approval.label, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "The AI agent is waiting for this answer while its chat is minimized, " +
                        "so this dialog is the only place to give it. Nothing has happened " +
                        "yet, and nothing happens unless you allow it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    approvalFootnote(approval),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Asked by the \"${approval.name}\" tool. Closing this or not answering " +
                        "denies it — the agent is told the action was refused and carries on " +
                        "without it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            // Wraps rather than clips: three answers do not fit one row on a
            // narrow phone, and "Always allow" is the one that must stay
            // readable rather than be the one pushed off the edge.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (remembersSite(approval)) {
                    TextButton(onClick = { agent.respondApproval(ApprovalAnswer.AllowForSite) }) {
                        Text("Allow on this site")
                    }
                }
                if (!approval.destructive) {
                    TextButton(onClick = { agent.respondApproval(ApprovalAnswer.AlwaysAllow) }) {
                        Text("Always allow")
                    }
                }
                OutlinedButton(onClick = { agent.respondApproval(ApprovalAnswer.Deny) }) {
                    Text("Deny")
                }
                Button(onClick = { agent.respondApproval(ApprovalAnswer.Allow) }) {
                    Text("Allow")
                }
            }
        }
    )
}

// ------------------------------------------------------------------- chat plugins

/**
 * The plugin surfaces of BOTH agent windows — the picker sheet, the
 * enable-time grant dialog (with its form half when the manifest declares
 * one), the input-form dialog the gates raise, and the ZIP install the
 * sheet's footer starts — collected in one place so the floating panel and
 * the full-screen agent behave identically without either duplicating them.
 *
 * The ZIP install goes through the app graph's plugin repository, the same
 * door the manager activity uses, because the repository is the one writer
 * that keeps the row and the code directory honest; the controller's
 * [com.roombrowser.agent.PluginUiState] is only asked to REPORT what the
 * install produced, in the chat the person is looking at.
 */
@Composable
private fun PluginSurfaces(agent: BrowserAgentController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The plugin whose form the sheet's edit pencil opened, or null. The
    // state raises an edit as an ordinary ACTIVATION-context form, so the
    // dialog needs this one UI-local fact to spell its chrome ("Edit inputs
    // for …" / "Save and run") — set on the pencil's own tap, cleared the
    // moment the form it named retires, so it can never mislabel a form some
    // other gate later raised for the same plugin.
    var editingPluginId by remember { mutableStateOf<String?>(null) }

    // SAF: the system file picker — the one door a plugin ZIP enters through,
    // and it opens for a file the person chose, never for a path the app
    // guessed. Both ZIP spellings are offered because a downloaded plugin can
    // arrive as either depending on where it came from.
    val installLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    (context.applicationContext as RoomBrowserApp).graph.pluginRepo.install(stream)
                }
            }.getOrNull() ?: PluginInstallResult.Invalid(listOf("the ZIP could not be opened"))
            agent.pluginUi.onInstallResult(result)
        }
    }

    if (agent.pluginUi.pickerVisible) {
        ChatPluginsSheet(
            plugins = agent.pluginUi.installed,
            chatPlugins = agent.pluginUi.chatPlugins,
            onEnable = { pluginId, enabled -> agent.pluginUi.setEnabled(pluginId, enabled) },
            onDismiss = { agent.pluginUi.dismissPicker() },
            onInstallRequest = {
                installLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
            },
            onManage = { PluginManagerActivity.launch(context) },
            onEditInputs = { pluginId ->
                editingPluginId = pluginId
                agent.pluginUi.editInputs(pluginId)
            }
        )
    }
    val pending = agent.pluginUi.pendingGrant
    if (pending != null) {
        PluginPermissionDialog(
            pending = pending,
            onAccept = { granted, inputs ->
                agent.pluginUi.submitGrants(granted, inputs)
                // The confirmation is the conversation itself — the activation
                // card and the "is on" notice land there — so the sheet steps
                // out of the way rather than covering them.
                agent.pluginUi.dismissPicker()
            },
            onCancel = { agent.pluginUi.cancelGrant() }
        )
    }
    // The form a gate raised: the activation backstop, the agent tool's
    // missing-inputs gate, the runtime's mid-run question, the sheet's edit.
    // The CONSENT dialog wins when both could ever be up — the gates' forms
    // only appear after consent was given — so this one renders only when no
    // grant dialog is showing; its raiser keeps waiting either way, with the
    // state's own 300 s cap as the ceiling on the wait.
    val form = agent.pluginUi.pendingForm
    if (pending == null && form != null) {
        PluginFormDialog(
            form = form,
            isEdit = form.context == PluginFormContext.ACTIVATION && form.pluginId == editingPluginId,
            onSubmit = { values ->
                editingPluginId = null
                agent.pluginUi.submitForm(values)
            },
            onCancel = {
                editingPluginId = null
                agent.pluginUi.cancelForm()
            }
        )
    }
    // A form that retired without either handler — its raiser's own timeout
    // retires it — must not leave the edit flag behind to mislabel the next
    // form that rises for the same plugin.
    LaunchedEffect(form) {
        if (form == null) editingPluginId = null
    }
    // The /make_plugins approval form: one composed draft waiting on the
    // person's Allow and Create — beside the grant dialog's slot in the same
    // convention, and independent of both form slots (a creation is not a
    // plugin event waiting on a gate; nothing is suspended behind it).
    val creation = agent.pluginUi.pendingCreation
    if (creation != null) {
        PluginCreateDialog(
            pending = creation,
            onSubmit = { name, description, permissions, hosts ->
                agent.pluginUi.submitCreation(name, description, permissions, hosts)
            },
            onCancel = { agent.pluginUi.cancelCreation() }
        )
    }
    // The guided Airplane Mode switch: the non-root /airplane hand-off,
    // waiting on the person (or on the phone itself — the auto-continue poll
    // resolves it without either button). Same convention as the slots
    // above: the state raises it, the dialog renders it, and only the
    // accept/stop pair retires it.
    val airplaneRequest = agent.pluginUi.pendingAirplane
    if (airplaneRequest != null) {
        AirplaneModeDialog(
            request = airplaneRequest,
            onAccept = { agent.pluginUi.resolveAirplane() },
            onStop = { agent.pluginUi.stopAirplane() }
        )
    }
}

// ------------------------------------------------------------------- composer

// Green for the INCLUDED state (user request: "jika include page
// di-ikutkan maka warna hijau" — an included page must show green).
// Material-tuned pair for the dark-centric palette (primary #A78BFA on
// dark surface #1D1D27): muted green container + mint content in dark
// theme, pastel container + deep green content in light theme — the same
// container/on-container contrast logic Theme.kt uses for its schemes.
private val IncludeGreenDarkContainer = Color(0xFF2F6B33)
private val IncludeGreenDarkContent = Color(0xFFD7F5DC)
private val IncludeGreenLightContainer = Color(0xFFB9F6CA)
private val IncludeGreenLightContent = Color(0xFF0A3818)

@Composable
private fun AgentComposer(agent: BrowserAgentController, modifier: Modifier = Modifier) {
    var input by rememberSaveable { mutableStateOf("") }
    // Both chips are keyed on their SETTING, not merely remembered: they are
    // also editable from AI Agent settings, and this panel stays composed
    // underneath those screens. Reading the setting back means an edit made
    // anywhere shows up here instead of the composer going stale, and a keyed
    // value is discarded when the setting moves — which is what should happen
    // to a per-turn toggle whose default just changed.
    var includePage by rememberSaveable(agent.settings.includePageContext) {
        mutableStateOf(agent.settings.includePageContext)
    }
    var useDefaultContext by remember(agent.settings.useDefaultContext) {
        mutableStateOf(agent.settings.useDefaultContext)
    }
    // Read straight from the setting: the editor is now its own activity, so
    // there is no local copy to keep in step — the write comes back through
    // the setting and redraws this row.
    val defaultContext = agent.settings.defaultContext
    val dark = LocalRoomExtras.current.dark
    // Content URIs are not saveable — attachments intentionally reset on
    // process death (they are re-read and sent with the next turn anyway).
    var attachments by remember { mutableStateOf<List<AgentAttachment>>(emptyList()) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val attachLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val resolved = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    // Any hard failure degrades to a metadata-only entry.
                    runCatching { resolveAttachment(context, uri) }.getOrElse {
                        AgentAttachment(
                            name = uri.lastPathSegment ?: "file",
                            mime = "",
                            sizeBytes = 0L,
                            text = null
                        )
                    }
                }
            }
            attachments = attachments + resolved
        }
    }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            // Everything above the text field is optional and can be
            // clipped; the field is measured FIRST (it is the unweighted
            // child below), so a short panel squeezes the chips empty
            // instead of leaving nowhere to type.
            Column(Modifier.weight(1f, fill = false)) {
                // Row 1 — controls: chat plugins + the two context switches +
                // file upload. The text field lives on its own full-width row
                // below, so it reaches the panel edges ("lebar sampai ke
                // pinggir layar").
                //
                // The chips SCROLL rather than squeeze: two labels plus a 48dp
                // attach button do not fit a 320dp phone at a large font scale,
                // and a wrapped or truncated "Default context" is unreadable in
                // exactly the state the user needs to read it in.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Chat plugins as the row's LEADING control: the badge
                    // counts the plugins switched on for THIS chat, and the
                    // button is absent while nothing is installed — an entry
                    // point to a feature with nothing to choose is a button
                    // that does nothing.
                    if (agent.pluginUi.installed.isNotEmpty()) {
                        val enabledPlugins = agent.pluginUi.chatPlugins.count { it.enabled }
                        IconButton(
                            onClick = { agent.pluginUi.openPicker() },
                            modifier = Modifier.semantics { contentDescription = "agent_chat_plugins" }
                        ) {
                            BadgedBox(
                                badge = {
                                    if (enabledPlugins > 0) Badge { Text("$enabledPlugins") }
                                }
                            ) {
                                Icon(Icons.Filled.Extension, contentDescription = null)
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    Row(
                        Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // DEFAULT CONTEXT — the standing instruction the agent
                        // carries into every request until it is switched off.
                        //
                        // Tap toggles it. Tapping it while nothing is saved opens
                        // the editor instead, because "on" and "empty" is not a
                        // state worth being able to reach: the switch would turn
                        // green and change nothing about the requests.
                        FilterChip(
                            selected = useDefaultContext,
                            onClick = {
                                if (defaultContext.isBlank()) {
                                    DefaultContextActivity.launch(context)
                                } else {
                                    val next = !useDefaultContext
                                    useDefaultContext = next
                                    agent.updateSettings { it.copy(useDefaultContext = next) }
                                }
                            },
                            label = { Text("Default context") },
                            leadingIcon = {
                                Icon(
                                    if (useDefaultContext) Icons.Filled.Check else Icons.Filled.BookmarkBorder,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            modifier = Modifier
                                .semantics { contentDescription = "agent_default_context" },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = if (dark) IncludeGreenDarkContainer else IncludeGreenLightContainer,
                                selectedLabelColor = if (dark) IncludeGreenDarkContent else IncludeGreenLightContent,
                                selectedLeadingIconColor = if (dark) IncludeGreenDarkContent else IncludeGreenLightContent
                            )
                        )
                        // Included page = GREEN (user request: "jika include page
                        // di-ikutkan maka warna hijau") — green container + green
                        // label/leading icon, and the page icon swaps for a check so
                        // the state is unmistakable even without color vision.
                        FilterChip(
                            selected = includePage,
                            onClick = { includePage = !includePage },
                            label = { Text("Include page") },
                            leadingIcon = {
                                Icon(
                                    if (includePage) Icons.Filled.Check else Icons.Filled.Description,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = if (dark) IncludeGreenDarkContainer else IncludeGreenLightContainer,
                                selectedLabelColor = if (dark) IncludeGreenDarkContent else IncludeGreenLightContent,
                                selectedLeadingIconColor = if (dark) IncludeGreenDarkContent else IncludeGreenLightContent
                            )
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalIconButton(
                        onClick = { attachLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "agent_attach_files" }
                    ) {
                        BadgedBox(
                            badge = {
                                if (attachments.isNotEmpty()) Badge { Text("${attachments.size}") }
                            }
                        ) {
                            Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                // What the two switches will actually send, in one line, whenever
                // either is on. A green chip says "this is enabled"; it does not
                // say WHAT is enabled, and the default context is text the user
                // wrote days ago and cannot see from here. Tapping the line opens
                // the editor.
                val activeContexts = buildList {
                    if (useDefaultContext && defaultContext.isNotBlank()) {
                        add("Context: " + defaultContext.replace(Regex("\\s+"), " ").trim())
                    }
                    if (includePage) add("This page")
                }
                if (activeContexts.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        activeContexts.joinToString("  ·  "),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (dark) IncludeGreenDarkContent else IncludeGreenLightContent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { DefaultContextActivity.launch(context) }
                            .padding(vertical = 4.dp)
                            .semantics { contentDescription = "agent_active_contexts" }
                    )
                }

                // Picked files — removable chips, horizontally scrollable.
                if (attachments.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        attachments.forEachIndexed { index, attachment ->
                            InputChip(
                                selected = false,
                                onClick = { attachments = attachments.filterIndexed { i, _ -> i != index } },
                                label = {
                                    Text(
                                        attachment.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 160.dp)
                                    )
                                },
                                trailingIcon = {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "agent_remove_attachment",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                // A turn that errored or was stopped can be sent again exactly as
                // it was asked. Retyping it is the only alternative, and the
                // failure — a provider hiccup, a rate limit — is usually not the
                // request's fault.
                if (!agent.running && agent.retryable != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "That turn did not finish.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { agent.retry() },
                            modifier = Modifier.semantics { contentDescription = "agent_retry" }
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Retry")
                        }
                    }
                }

                // A pause or a challenge is a STATE, not a message: it stays on
                // screen for as long as it is true, in the exact words the core
                // decided on (see AgentPauseLabels), so the panel, the status
                // line and the notification cannot drift apart.
                val pauseLabel = AgentPauseLabels.labelOf(agent.pauseState)
                if (pauseLabel != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            pauseLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .semantics { contentDescription = "agent_pause_state" }
                        )
                        if (agent.pauseState != AgentPause.Paused) {
                            TextButton(
                                onClick = { agent.resume() },
                                modifier = Modifier.semantics { contentDescription = "agent_resume" }
                            ) {
                                Text("Resume")
                            }
                        }
                    }
                }
            }

            // Row 2 — full-width input row.
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Ask the agent to browse…") },
                    maxLines = 4,
                    shape = MaterialTheme.shapes.small,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            // A running turn replaces Send with pause/stop, but
                            // the IME's own Send key is still there — and
                            // clearing the box for a message that was never
                            // sent would silently eat what the user typed.
                            // It stays in the field, to send when the turn ends.
                            if (!agent.running && input.isNotBlank()) {
                                agent.send(input, includePage, attachments)
                                input = ""
                                attachments = emptyList()
                            }
                        }
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "agent_composer_field" }
                )
                Spacer(Modifier.width(8.dp))
                if (agent.running) {
                    // One toggle beside Stop: it holds the turn when it is
                    // running and lets it go when it is held — a challenge
                    // included, where "carry on" is the only useful answer.
                    val held = agent.pauseState != AgentPause.None
                    FilledTonalIconButton(
                        onClick = { agent.togglePause() },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "agent_pause_toggle" }
                    ) {
                        Icon(
                            if (held) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = null
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = { agent.stop() },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "agent_stop" }
                    ) { Icon(Icons.Filled.Stop, contentDescription = null) }
                } else {
                    // AutoMirrored, like the GO_BACK arrow this file already
                    // maps: a paper plane aimed at the end of the line is
                    // backwards in an RTL layout, where "onward" points left.
                    // Icons.Filled.Send never flips; the AutoMirrored twin
                    // does.
                    FilledIconButton(
                        onClick = {
                            agent.send(input, includePage, attachments)
                            input = ""
                            attachments = emptyList()
                        },
                        enabled = input.isNotBlank(),
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "agent_send" }
                    ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) }
                }
            }
        }
    }
}

// ------------------------------------------------------------- attachment IO

/** Max bytes read from one attachment; larger files stay metadata-only. */
private const val ATTACHMENT_MAX_BYTES = 256L * 1024L

/** Max characters kept per attachment (extraction-side truncation). */
private const val ATTACHMENT_MAX_CHARS = 20_000

/** MIME types that are textual despite not starting with "text/". */
private val TEXTUAL_EXTRA_MIMES = setOf(
    "application/json", "application/xml", "application/javascript",
    "application/x-yaml", "application/xhtml+xml"
)

/** File extensions treated as text when the MIME type is unknown or generic. */
private val TEXTUAL_EXTENSIONS = setOf(
    "txt", "md", "json", "csv", "xml", "yaml", "yml", "html", "js", "ts",
    "kt", "java", "py", "sql", "log"
)

private fun isTextLikeAttachment(mime: String, name: String): Boolean =
    mime.startsWith("text/") || mime in TEXTUAL_EXTRA_MIMES ||
        name.substringAfterLast('.', "").lowercase() in TEXTUAL_EXTENSIONS

/**
 * Resolves one picked [Uri] into an [AgentAttachment]: display name + size
 * from the content provider, plus extracted text when the file looks textual
 * and is ≤ 256 KB (UTF-8 decode with malformed bytes replaced, capped at
 * 20 000 chars). Binary/oversized files — and any IO failure — degrade to a
 * metadata-only entry.
 */
private fun resolveAttachment(context: Context, uri: Uri): AgentAttachment {
    val resolver = context.contentResolver
    var name: String? = null
    var size = 0L
    runCatching {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx >= 0) name = cursor.getString(nameIdx)
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
            }
        }
    }
    val mime = runCatching { resolver.getType(uri) }.getOrNull().orEmpty()
    val displayName = (name ?: uri.lastPathSegment ?: "file").substringAfterLast('/')

    var text: String? = null
    if (size <= ATTACHMENT_MAX_BYTES && isTextLikeAttachment(mime, displayName)) {
        val extracted = readAttachmentText(resolver, uri)
        if (extracted != null) {
            text = extracted.first
            if (size <= 0L) size = extracted.second
        }
    }
    return AgentAttachment(name = displayName, mime = mime, sizeBytes = size, text = text)
}

/**
 * Reads up to [ATTACHMENT_MAX_BYTES] bytes and decodes UTF-8 (malformed
 * sequences replaced). Returns `(text, bytesRead)` or null when the stream is
 * empty/unreadable or the file exceeds the byte cap (metadata-only).
 */
private fun readAttachmentText(resolver: ContentResolver, uri: Uri): Pair<String, Long>? = runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            val limit = ATTACHMENT_MAX_BYTES.toInt() + 1
            val buffer = ByteArray(limit)
            var total = 0
            while (total < limit) {
                val n = stream.read(buffer, total, limit - total)
                if (n < 0) break
                total += n
            }
            when {
                total <= 0 -> null
                total > ATTACHMENT_MAX_BYTES -> null // oversize → metadata only
                else -> {
                    val decoded = String(buffer, 0, total, Charsets.UTF_8)
                    val text = if (decoded.length > ATTACHMENT_MAX_CHARS) {
                        decoded.take(ATTACHMENT_MAX_CHARS) + "\n…[truncated]"
                    } else decoded
                    text to total.toLong()
                }
            }
        }
    }.getOrNull()

// ------------------------------------------------------------------- plan panel

/**
 * The plan panel: the live sheet behind the header's checklist button.
 *
 * It reads [BrowserAgentController.plan] DIRECTLY in composition — never a
 * remembered copy — so a model rewriting the list mid-turn re-renders it the
 * moment the state lands: this is the real-time window on the work, not a
 * snapshot taken when it opened. Its ONLY closer is the user (swipe, scrim,
 * or the toggle that opened it), because [planPanelOpen] is a user toggle by
 * contract: plan updates, chat switches and the turn ending must never write
 * it. A chat switch that lands an empty plan therefore does not close the
 * sheet — it shows the empty-state line below until the user dismisses it.
 *
 * Shown from [AgentPanelHeader], which both hosts render, so one site covers
 * the floating panel and the full-screen agent alike.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanPanelSheet(agent: BrowserAgentController, onDismiss: () -> Unit) {
    // Read per recomposition, deliberately NOT remembered: a remembered list
    // would freeze the very thing this sheet exists to show live.
    val plan = agent.plan
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                // The plan is capped at 24 steps, but a short landscape window
                // shows only a handful of rows: like the model picker's body,
                // this one scrolls so step 1 stays reachable.
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Checklist,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Plan", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(4.dp))
            if (plan.isEmpty()) {
                Text(
                    "No active plan",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { contentDescription = "agent_plan" }
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "The agent keeps its own step list with the `plan` tool. Ask it to plan a " +
                        "task first — or switch the chat to Plan mode — and every revision it " +
                        "sends appears here while it works.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "${plan.count { it.status == AgentPlan.Status.DONE }} of ${plan.size} steps done",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                plan.forEach { step -> PlanStepRow(step) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ------------------------------------------------------------------- model picker

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelPickerSheet(agent: BrowserAgentController, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(agent.activeProvider) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf("") }
    // Reset with the provider: page 4 of one provider's list says nothing
    // about the next provider's.
    var modelPage by remember(selected?.id) { mutableStateOf(0) }

    LaunchedEffect(selected) {
        val provider = selected ?: return@LaunchedEffect
        loading = true
        error = null
        try {
            models = agent.modelsFor(provider)
        } catch (t: Throwable) {
            models = emptyList()
            error = t.message ?: "could not fetch models"
        } finally {
            loading = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                // The sheet body must scroll: with several providers and long
                // model lists (100+ chips) the manual-entry row would otherwise
                // be unreachable below the fold.
                .verticalScroll(rememberScrollState())
        ) {
            Text("Select model", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Providers are configured in Agent settings; model lists come from the provider's /models endpoint.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            if (agent.providers.isEmpty()) {
                Text("No providers configured yet.")
            }
            agent.providers.forEach { provider ->
                ProviderPickRow(
                    provider = provider,
                    selected = selected?.id == provider.id,
                    isDefault = agent.settings.defaultProviderId == provider.id,
                    onClick = { selected = provider }
                )
            }

            if (selected != null) {
                Spacer(Modifier.height(12.dp))
                FilterChip(
                    selected = agent.chatModelAuto && agent.activeProvider?.id == selected!!.id,
                    onClick = {
                        agent.setAutoModel(selected!!)
                        onDismiss()
                    },
                    label = { Text("Auto — try each model until one answers") },
                    modifier = Modifier.semantics { contentDescription = "agent_model_auto" }
                )
            }

            Spacer(Modifier.height(12.dp))
            when {
                selected == null -> Unit
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Fetching models from ${selected!!.name}…",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                else -> {
                    error?.let {
                        Text(
                            "Could not fetch the model list ($it). Type a model id manually:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (models.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Paging.slice(models, modelPage).forEach { model ->
                                FilterChip(
                                    selected = model == agent.activeModel,
                                    onClick = {
                                        agent.setDefault(selected!!, model)
                                        onDismiss()
                                    },
                                    label = { Text(model, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                )
                            }
                        }
                        PagingFooter(
                            page = modelPage,
                            total = models.size,
                            onPage = { modelPage = it },
                            semanticsPrefix = "agent_model_page"
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = manual,
                            onValueChange = { manual = it },
                            label = { Text("Model id (manual)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                if (manual.isNotBlank() && selected != null) {
                                    agent.setDefault(selected!!, manual.trim())
                                    onDismiss()
                                }
                            },
                            enabled = manual.isNotBlank()
                        ) { Text("Use") }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ------------------------------------------------------------------- surface picker

/**
 * What a chat turn runs on. Two options, not the AI Task editor's three: a
 * chat has no "leave the tab open when it ends" — it runs on a tab the user
 * already had, or on a page of its own that goes away with the turn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatSurfaceSheet(agent: BrowserAgentController, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("Where the chat runs", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                // NOT "in this profile": the choice is stored in the app-wide
                // agent settings blob, so it is the same answer in every
                // profile. The paragraph below is the per-profile half — the
                // page a headless turn runs on is this profile's own.
                "Applies to every turn from now on, in every profile.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !agent.chatHeadless,
                    onClick = {
                        agent.setChatSurface(false)
                        onDismiss()
                    },
                    label = { Text("Headed browser") },
                    modifier = Modifier.semantics { contentDescription = "agent_surface_headed" }
                )
                FilterChip(
                    selected = agent.chatHeadless,
                    onClick = {
                        agent.setChatSurface(true)
                        onDismiss()
                    },
                    label = { Text("Headless") },
                    modifier = Modifier.semantics { contentDescription = "agent_surface_headless" }
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (agent.chatHeadless) {
                    "Runs on a hidden page in this profile — sites you are signed into stay " +
                        "signed in — starting at the address you are on, and nothing appears on " +
                        "screen while it works. It cannot open or switch tabs, and a wallet " +
                        "request cannot be answered from a page you cannot see."
                } else {
                    "Runs on the tab you are looking at, so every click and every line typed " +
                        "happens where you can watch it, and the agent can open tabs of its own."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * One provider row of the "Select model" sheet — a deliberate 3-LINE card so
 * nothing ever overlaps or truncates into an unreadable "url · model" mash:
 *
 *   Line 1 — provider NAME (bold, ellipsized) + "default" tag when applicable
 *   Line 2 — the MODEL (primary color — it is what the user is choosing)
 *   Line 3 — the BASE URL (muted, small, ellipsized)
 *
 * The model line used to sit BESIDE the column as a trailing Text; on long
 * URLs/model ids the two cramped each other into overlapping ellipses.
 */
@Composable
private fun ProviderPickRow(
    provider: AgentProviderEntity,
    selected: Boolean,
    isDefault: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        } else {
            Spacer(Modifier.width(24.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    provider.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isDefault) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "default",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                provider.defaultModel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                provider.baseUrl,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ------------------------------------------------------------------- markdown-lite

/**
 * Lightweight markdown rendering for agent answers: **bold**, `code`,
 * ### headings and "- " bullets. Enough for chat replies without a
 * full markdown dependency.
 */
@Composable
fun MarkdownText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: androidx.compose.ui.graphics.Color,
    // While the row this text belongs to is still streaming, the re-parse is
    // throttled (see the keys below); the moment the row completes, the key
    // becomes the text itself again and the render is exact — the finished
    // answer is byte-identical to a render of the whole string.
    streaming: Boolean = false
) {
    // Theme-aware inline-code background (was a hardcoded translucent gray
    // that clashed with per-profile themes). LocalRoomExtras carries a dark
    // fallback default, so this never crashes outside the browser theme.
    val extras = LocalRoomExtras.current
    val codeBackground = extras.surfaceAlt.copy(alpha = 0.35f)
    // F-02: re-parsing per TOKEN re-parsed the whole accumulated answer for
    // every delta (and the parse itself was quadratic — see renderMarkdown).
    // Two keys now bound the cadence while streaming, and ONLY while
    // streaming: the heartbeat (≤ STREAM_REFRESH_MS after real growth — the
    // tick parks after STREAM_TICK_IDLE_ROUNDS ticks with none, so a row a
    // cancelled turn left "streaming" forever cannot wake the CPU forever)
    // and a coarse length bucket that backstops it (a stream whose ticker
    // parked mid-stall still re-parses every STREAM_REPARSE_CHARS of growth,
    // so the text can never freeze for the rest of the answer). A completed
    // row keys on the text itself, exactly as before: same AnnotatedString,
    // byte for byte.
    val refresh = rememberStreamTick(streaming) { text.length }
    val annotated = remember(
        codeBackground,
        if (streaming) text.length / STREAM_REPARSE_CHARS else text,
        if (streaming) refresh.value else -1
    ) { renderMarkdown(text, codeBackground) }
    androidx.compose.material3.Text(
        text = annotated,
        style = style,
        color = color,
        modifier = Modifier.fillMaxWidth()
    )
}

internal fun renderMarkdown(text: String, codeBackground: Color = Color(0x14808080)): AnnotatedString = buildAnnotatedString {
    val codeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = codeBackground
    )
    // text.lines() splits the WHOLE document; the loop below used to call it
    // once for the iteration and AGAIN inside every iteration for lastIndex —
    // a fresh List<String> of the entire answer allocated per line, making one
    // render O(lines²). Hoisted once, the loop and the lastIndex read share a
    // single list, and the output is byte-for-byte what the double call
    // produced (the two lists were always equal).
    val lines = text.lines()
    lines.forEachIndexed { index, rawLine ->
        val line = rawLine.trimStart()
        when {
            line.startsWith("### ") -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(line.removePrefix("### ").trim())
                pop()
            }
            line.startsWith("## ") || line.startsWith("# ") -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(line.trimStart('#').trim())
                pop()
            }
            line.startsWith("- ") || line.startsWith("* ") -> {
                append("• ")
                appendInlineStyles(line.substring(2), codeStyle)
            }
            else -> appendInlineStyles(line, codeStyle)
        }
        if (index < lines.lastIndex) append('\n')
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInlineStyles(
    line: String,
    codeStyle: SpanStyle
) {
    var i = 0
    while (i < line.length) {
        val boldStart = line.indexOf("**", i)
        val codeStart = line.indexOf('`', i)
        when {
            boldStart >= 0 && (codeStart < 0 || boldStart < codeStart) -> {
                append(line.substring(i, boldStart))
                val end = line.indexOf("**", boldStart + 2)
                if (end < 0) {
                    append(line.substring(boldStart))
                    return
                }
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(line.substring(boldStart + 2, end))
                pop()
                i = end + 2
            }
            codeStart >= 0 -> {
                append(line.substring(i, codeStart))
                val end = line.indexOf('`', codeStart + 1)
                if (end < 0) {
                    append(line.substring(codeStart))
                    return
                }
                pushStyle(codeStyle)
                append(line.substring(codeStart + 1, end))
                pop()
                i = end + 1
            }
            else -> {
                append(line.substring(i))
                return
            }
        }
    }
}
