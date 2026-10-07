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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.roombrowser.agent.AgentAttachment
import com.roombrowser.agent.AgentEntry
import com.roombrowser.agent.ApprovalAnswer
import com.roombrowser.agent.BrowserAgentController
import com.roombrowser.browser.BrowserViewModel
import com.roombrowser.data.db.AgentProviderEntity
import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.ui.common.LocalRoomExtras
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
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var resumeCount by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeCount++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    androidx.compose.runtime.LaunchedEffect(expanded, resumeCount) {
        if (expanded) agent.refreshProviders()
    }

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
                    // Keyed on the tab so unsent input cannot follow the user
                    // into another tab's conversation: the composer text and
                    // its attached files are remembered across tab switches
                    // otherwise, and the next Send would file them under a
                    // chat they were never written for.
                    key(viewModel.activeTabId) {
                        AgentComposer(
                            agent = agent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .imePadding()
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
                    // clickable BEFORE padding so the vertical padding counts
                    // toward the touch target (taller, ≥32dp effective row).
                    .clickable(enabled = provider != null) { showModelPicker = true }
                    .semantics { contentDescription = "agent_model" }
                    .padding(vertical = 8.dp)
            )
            // Where a turn runs, on the same tappable line pattern as the
            // model above. It has to be visible BEFORE a turn is sent: the two
            // surfaces disagree about what "click that button" means, and only
            // one of them can be watched.
            Text(
                if (agent.chatHeadless) "Headless · hidden page" else "Headed · this tab",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clickable { showSurfacePicker = true }
                    .semantics { contentDescription = "agent_surface" }
                    .padding(vertical = 6.dp)
            )
            // Chat only: a chip rather than a third tappable line, because its
            // STATE is the thing to see — a turn that suddenly refuses to act
            // reads as a broken agent, and the two lines above only say where
            // the turn runs, not what it may do.
            FilterChip(
                selected = agent.chatOnly,
                onClick = { agent.setChatOnly(!agent.chatOnly) },
                label = { Text("Chat only") },
                modifier = Modifier
                    .semantics { contentDescription = "agent_chat_only" }
                    .padding(top = 2.dp)
            )
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
        IconButton(onClick = onNewSession) {
            Icon(Icons.Filled.Add, contentDescription = "New agent chat")
        }
        IconButton(onClick = onOpenSessions) {
            Icon(Icons.Filled.History, contentDescription = "Agent chat history")
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
}

// ------------------------------------------------------------------- conversation

@Composable
private fun AgentConversation(
    agent: BrowserAgentController,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val entries = agent.entries

    // Live auto-scroll: instant while streaming, animated for new entries.
    // The ApprovalCard is composed as the item AFTER the last entry — when it
    // is showing it is the true last item, so scroll past the last entry
    // (index = entries.size) to keep the approval card above the fold.
    LaunchedEffect(entries.size, entries.lastOrNull(), agent.approval) {
        if (entries.isNotEmpty()) {
            val lastItem = if (agent.approval != null) entries.size else entries.size - 1
            listState.scrollToItem(lastItem)
        }
    }

    if (entries.isEmpty()) {
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

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(entries, key = { index, _ -> index }) { _, entry ->
            when (entry) {
                is AgentEntry.User -> UserBubble(entry)
                is AgentEntry.Assistant -> AssistantMessage(entry)
                is AgentEntry.Tool -> ToolCard(entry)
                is AgentEntry.Notice -> NoticeLine(entry)
            }
        }
        agent.approval?.let { approval -> item { ApprovalCard(approval, agent) } }
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
                color = MaterialTheme.colorScheme.onSurface
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
            Spacer(Modifier.height(2.dp))
            Text(
                if (approval.destructive) {
                    "This cannot be undone, and it asks every time — there is no answer here " +
                        "that stops the asking."
                } else {
                    "\"Always allow\" stops the agent asking about anything, until you turn it " +
                        "off in AI Agent settings."
                },
                style = MaterialTheme.typography.labelSmall
            )
        }
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
    var includePage by rememberSaveable { mutableStateOf(agent.settings.includePageContext) }
    // Keyed on the SETTING, not merely remembered: the default context is also
    // editable from AI Agent settings and from the sheet below, and this panel
    // stays composed underneath those. Reading the setting back means an edit
    // made anywhere shows up here instead of the composer going stale.
    var useDefaultContext by remember(agent.settings.useDefaultContext) {
        mutableStateOf(agent.settings.useDefaultContext)
    }
    var defaultContext by remember(agent.settings.defaultContext) {
        mutableStateOf(agent.settings.defaultContext)
    }
    var editingContext by remember { mutableStateOf(false) }
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
            // Row 1 — controls: the two context switches + file upload. The
            // text field lives on its own full-width row below, so it reaches
            // the panel edges ("lebar sampai ke pinggir layar").
            //
            // The chips SCROLL rather than squeeze: two labels plus a 48dp
            // attach button do not fit a 320dp phone at a large font scale,
            // and a wrapped or truncated "Default context" is unreadable in
            // exactly the state the user needs to read it in.
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                    // green and change nothing about the requests. Long press
                    // edits from either state.
                    FilterChip(
                        selected = useDefaultContext,
                        onClick = {
                            if (defaultContext.isBlank()) {
                                editingContext = true
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
                        .clickable { editingContext = true }
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
                            if (input.isNotBlank()) {
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

    if (editingContext) {
        DefaultContextSheet(
            initial = defaultContext,
            onDismiss = { editingContext = false },
            onSave = { text ->
                defaultContext = text
                // Saving a non-blank context switches it ON: writing the text
                // down IS the intent to use it, and leaving the switch off
                // would make "Save" look like it did nothing. Clearing to
                // blank switches it off for the same reason in reverse.
                val enabled = text.isNotBlank()
                useDefaultContext = enabled
                agent.updateSettings { it.copy(defaultContext = text, useDefaultContext = enabled) }
                editingContext = false
            }
        )
    }
}

/**
 * Edit / replace / clear the standing context.
 *
 * A sheet rather than an AlertDialog because the field is a paragraph: a
 * dialog sized to its content on a phone gives a four-line box, and this is
 * the one place in the panel where the user writes prose. [onClear] is a
 * first-class action rather than "save an empty string", so emptying the
 * context is one deliberate tap instead of a select-all and a delete.
 */
@Composable
private fun DefaultContextSheet(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var draft by remember { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text("Default context", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "Sent with every request until you turn it off — the same " +
                    "instruction, not a one-off. Use it for how you want answers " +
                    "(language, format, level of detail) or what you are working on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("e.g. Answer in Indonesian, be concise, and assume I am on Android.") },
                minLines = 4,
                maxLines = 10,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "agent_default_context_field" }
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onSave(draft.trim()) },
                    enabled = draft.isNotBlank(),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "agent_default_context_save" }
                ) { Text("Save and use") }
                OutlinedButton(
                    onClick = { onSave("") },
                    enabled = initial.isNotBlank() || draft.isNotBlank(),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "agent_default_context_clear" }
                ) { Text("Clear") }
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
private fun readAttachmentText(resolver: ContentResolver, uri: Uri): Pair<String, Long>? {
    return runCatching {
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
                            models.forEach { model ->
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
                "Applies to every turn from now on, in this profile.",
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
fun MarkdownText(text: String, style: androidx.compose.ui.text.TextStyle, color: androidx.compose.ui.graphics.Color) {
    // Theme-aware inline-code background (was a hardcoded translucent gray
    // that clashed with per-profile themes). LocalRoomExtras carries a dark
    // fallback default, so this never crashes outside the browser theme.
    val extras = LocalRoomExtras.current
    val codeBackground = extras.surfaceAlt.copy(alpha = 0.35f)
    val annotated = remember(text, codeBackground) { renderMarkdown(text, codeBackground) }
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
    text.lines().forEachIndexed { index, rawLine ->
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
        if (index < text.lines().lastIndex) append('\n')
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
