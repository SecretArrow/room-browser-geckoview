package com.roombrowser.devtools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.browser.BrowserViewModel
import com.roombrowser.browser.engine.ProfileEngine
import com.roombrowser.engine.devtools.DeveloperToolsCapabilities
import com.roombrowser.engine.devtools.DevToolsCapability
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomBottomSheetShape
import com.roombrowser.ui.common.RoomCopyButton
import com.roombrowser.ui.common.SectionHeader
import com.roombrowser.ui.common.StatTile

/**
 * The Developer Tools surface, mounted over the live page.
 *
 * It follows [com.roombrowser.agent.ui.AgentPanelHost] exactly — a
 * bottom-anchored `Surface` sized from the window it is given — because this
 * app has no split-pane and no navigation graph to dock into, and because the
 * panel compositing above the engine view is also what keeps it out of
 * `capturePixels`.
 *
 * A short window (landscape, an emulator) gets almost the whole slot: 72% of a
 * ~320dp window strands every control the panel has.
 */
@Composable
fun DeveloperToolsHost(
    viewModel: BrowserViewModel,
    manager: DeveloperToolsManager
) {
    // The safety net for teardown: a tab id that disappears without any of the
    // three named paths firing still loses its inspector here.
    val openTabIds = viewModel.tabs.map { it.id }.toSet()
    LaunchedEffect(openTabIds) { manager.retainTabs(openTabIds) }

    // Switching tabs retargets the panel instead of closing it.
    val activeTabId = viewModel.activeTabId
    LaunchedEffect(activeTabId) { manager.focus(activeTabId) }

    // Developer Tools is not offered on a private tab, and a panel left open
    // while one is opened must not keep reading it.
    val privateTab = viewModel.pageState.isPrivate
    LaunchedEffect(privateTab) { if (privateTab) manager.close() }

    val tabId = manager.tabId
    if (!manager.isOpen || tabId == null || privateTab) return

    val engineSession = viewModel.activeSession

    var inspector by remember(tabId) { mutableStateOf<InspectorSession?>(null) }
    var overview by remember(tabId) { mutableStateOf<PageOverview?>(null) }
    var answered by remember(tabId) { mutableStateOf<Boolean?>(null) }
    var refreshKey by remember(tabId) { mutableIntStateOf(0) }

    LaunchedEffect(tabId, engineSession, refreshKey) {
        // `manager.tabId` is corrected by the focus effect above, which runs
        // after this composition, so the frame that straddles a tab switch
        // holds the OLD tab with the NEW session. Attaching there would key an
        // inspector to the wrong tab and then build a second one for the right
        // one; skip it, and re-run once focus lands.
        if (tabId != viewModel.activeTabId) return@LaunchedEffect
        val attached = manager.attach(tabId, engineSession)
        inspector = attached
        if (attached == null) {
            overview = null
            answered = null
            return@LaunchedEffect
        }
        val result = attached.pageOverview()
        overview = result
        answered = result != null
    }

    val context = LocalContext.current
    val engineName = remember { runCatching { ProfileEngine.engineName(context) }.getOrNull().orEmpty() }
    val capabilities = remember {
        runCatching { ProfileEngine.devToolsCapabilities(context) }
            .getOrDefault(DeveloperToolsCapabilities.NONE)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heightModifier = when (manager.dock) {
            DevToolsDock.FULLSCREEN -> Modifier.fillMaxSize()
            DevToolsDock.MINIMIZED -> Modifier.wrapContentHeight()
            else -> Modifier.fillMaxHeight(if (maxHeight < 480.dp) 0.94f else 0.72f)
        }
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .then(heightModifier),
            shape = RoomBottomSheetShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 16.dp
        ) {
            // fillMaxWidth, NOT fillMaxSize: MINIMIZED is wrapContentHeight, and
            // a fillMaxSize child reports the whole window back to it, making
            // "minimize" the tallest of the three docks. The docked and
            // fullscreen docks set a tight height the weighted body still fills.
            Column(Modifier.fillMaxWidth()) {
                DeveloperToolsHeader(
                    engineName = engineName,
                    dock = manager.dock,
                    onToggleMinimize = { manager.toggleMinimize() },
                    onToggleFullscreen = { manager.toggleFullscreen() },
                    onClose = { manager.close() }
                )
                if (manager.dock != DevToolsDock.MINIMIZED) {
                    // The strip is outside the scroll on purpose: it is how a
                    // reader leaves a panel, so it must not be something they
                    // have to scroll back to the top to find.
                    val entries = remember(capabilities) { availablePanels(capabilities) }
                    LaunchedEffect(entries) {
                        // A panel the engine cannot serve stays selected across
                        // an engine change unless it is corrected here, and a
                        // selected id with no entry under it is a blank body.
                        if (entries.none { it.id == manager.panel }) {
                            entries.firstOrNull()?.let { manager.selectPanel(it.id) }
                        }
                    }
                    val current = entries.firstOrNull { it.id == manager.panel }
                    DevToolsPanelStrip(entries, current?.id) { manager.selectPanel(it) }
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        val scope = DevToolsPanelScope(
                            session = inspector,
                            engineName = engineName,
                            capabilities = capabilities,
                            tabUrl = viewModel.pageState.url,
                            tabTitle = viewModel.pageState.title,
                            overview = overview,
                            answered = answered,
                            hasEngine = inspector != null || engineSession != null,
                            onRefresh = { refreshKey++ }
                        )
                        // One panel at a time, chosen from the strip. Nothing
                        // outside the registry is reachable, so a panel cannot
                        // be shown by a state the capability layer would refuse.
                        current?.content?.invoke(scope)
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

/**
 * The Overview panel: the page as this build can describe it, plus the
 * inventory of what this build can inspect.
 *
 * It is the strip's always-present entry, so it is where a reader lands when a
 * panel they were reading is gone -- and it is therefore also where "why is
 * there no certificate view" is answered, in the same place, without a second
 * screen to find.
 */
@Composable
internal fun OverviewPanel(scope: DevToolsPanelScope) {
    PageFacts(
        overview = scope.overview,
        tabUrl = scope.tabUrl,
        tabTitle = scope.tabTitle,
        answered = scope.answered,
        hasSession = scope.hasEngine,
        onRefresh = scope.onRefresh
    )
    Spacer(Modifier.height(12.dp))
    SectionHeader("What this edition can inspect") {
        // The inventory IS the panel's honesty statement, so a report about
        // "why can this build not show me X" has to be able to carry it
        // verbatim.
        RoomCopyButton(
            capabilitiesText(scope.engineName, scope.capabilities),
            "Capabilities",
            "Copy what this edition can and cannot inspect"
        )
    }
    CapabilityList(scope.capabilities)
}

@Composable
private fun DeveloperToolsHeader(
    engineName: String,
    dock: DevToolsDock,
    onToggleMinimize: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onClose: () -> Unit
) {
    val extras = LocalRoomExtras.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Developer tools",
                style = MaterialTheme.typography.titleMedium,
                color = extras.textPrimary
            )
            if (engineName.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        engineName,
                        style = MaterialTheme.typography.labelSmall,
                        color = extras.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // The engine and its version are the first thing a bug report
                    // needs, so the name itself carries its copy control. It sits
                    // inside the title column rather than beside the three window
                    // controls, which would cost the title 48dp of a 320dp screen.
                    RoomCopyButton(engineName, "Engine", "Copy the engine name and version")
                }
            }
        }
        IconButton(onClick = onToggleMinimize) {
            Icon(
                if (dock == DevToolsDock.MINIMIZED) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (dock == DevToolsDock.MINIMIZED) "Restore" else "Minimize",
                tint = extras.icon
            )
        }
        IconButton(onClick = onToggleFullscreen) {
            Icon(
                if (dock == DevToolsDock.FULLSCREEN) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                contentDescription = if (dock == DevToolsDock.FULLSCREEN) "Dock" else "Full screen",
                tint = extras.icon
            )
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = "Close developer tools", tint = extras.icon)
        }
    }
}

@Composable
internal fun CapabilityList(capabilities: DeveloperToolsCapabilities) {
    val extras = LocalRoomExtras.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            "Only what this edition can actually serve is listed. A capability that is " +
                "not here is one this build cannot offer — not one that is merely empty.",
            style = MaterialTheme.typography.labelSmall,
            color = extras.textSecondary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        DevToolsCapability.entries.forEach { capability ->
            val present = capabilities.has(capability)
            if (!present) return@forEach
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape((extras.radius * 0.7f).dp))
                    .background(extras.surfaceAlt.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        capabilityLabel(capability),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = extras.textPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("available", style = MaterialTheme.typography.labelSmall, color = extras.primary)
                }
                Text(
                    capabilityDetail(capability),
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.textSecondary
                )
            }
        }
        if (capabilities.capabilities.isEmpty()) {
            Text(
                "This edition reports no inspector capability at all, so every panel is absent " +
                    "rather than empty.",
                style = MaterialTheme.typography.bodySmall,
                color = extras.textSecondary
            )
        }
        // "Cannot, and here is why" is a different answer from "not yet", and
        // the capability layer keeps them apart by carrying a reason only for
        // the first -- so the two are listed apart rather than merged into one
        // list of greyed-out rows.
        val absent = DevToolsCapability.entries.filterNot { capabilities.has(it) }
        AbsentCapabilityGroup(
            title = "Not possible on this engine",
            rows = absent.filter { capabilities.noteFor(it) != null },
            capabilities = capabilities
        )
        AbsentCapabilityGroup(
            title = "Not built yet",
            rows = absent.filter { capabilities.noteFor(it) == null },
            capabilities = capabilities
        )
    }
}

@Composable
internal fun AbsentCapabilityGroup(
    title: String,
    rows: List<DevToolsCapability>,
    capabilities: DeveloperToolsCapabilities
) {
    if (rows.isEmpty()) return
    val extras = LocalRoomExtras.current
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = extras.textSecondary,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
    )
    rows.forEach { capability ->
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(
                capabilityLabel(capability),
                style = MaterialTheme.typography.bodySmall,
                color = extras.textSecondary
            )
            Text(
                // No reason recorded means "not yet", and the capability's own
                // description then says what is being given up.
                capabilities.noteFor(capability) ?: capabilityDetail(capability),
                style = MaterialTheme.typography.labelSmall,
                color = extras.textSecondary.copy(alpha = 0.75f)
            )
        }
    }
}

@Composable
internal fun PageFacts(
    overview: PageOverview?,
    tabUrl: String,
    tabTitle: String,
    answered: Boolean?,
    hasSession: Boolean,
    onRefresh: () -> Unit
) {
    val extras = LocalRoomExtras.current
    val shownTitle = overview?.title?.takeIf { it.isNotBlank() } ?: tabTitle.ifBlank { "No title" }
    val shownUrl = overview?.url ?: tabUrl
    val wholeBlock = remember(overview, shownUrl, shownTitle) {
        pageFactsText(overview, shownUrl, shownTitle)
    }
    SectionHeader("Current page") {
        RoomCopyButton(wholeBlock, "Current page", "Copy everything this panel is showing")
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    shownTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = extras.textPrimary
                )
                Text(
                    shownUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.textSecondary
                )
            }
            RoomCopyButton(shownTitle, "Title", "Copy the page title")
            RoomCopyButton(shownUrl, "URL", "Copy the page URL")
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "Re-read the page", tint = extras.icon)
            }
        }

        when {
            !hasSession -> Text(
                "No live engine session for this tab yet — nothing to read.",
                style = MaterialTheme.typography.bodySmall,
                color = extras.textSecondary
            )
            answered == null -> Text(
                "Reading the page…",
                style = MaterialTheme.typography.bodySmall,
                color = extras.textSecondary
            )
            answered == false -> Text(
                // No cause is named: this branch covers a timeout, an undecodable
                // reply and a null probe alike, and the clipboard says the same.
                "${noReadingLine()} Either the reply is still coming or it was not " +
                    "something this panel can read — the page itself is untouched. " +
                    "Tap refresh.",
                style = MaterialTheme.typography.bodySmall,
                color = extras.textSecondary
            )
            overview != null -> OverviewGrid(overview)
        }
    }
}

@Composable
internal fun OverviewGrid(overview: PageOverview) {
    val extras = LocalRoomExtras.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            overview.nodes?.let { StatTile(it.toString(), "nodes", Modifier.weight(1f), copyable = true) }
            overview.scripts?.let { StatTile(it.toString(), "scripts", Modifier.weight(1f), copyable = true) }
            overview.frames?.let { StatTile(it.toString(), "frames", Modifier.weight(1f), copyable = true) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            overview.images?.let { StatTile(it.toString(), "images", Modifier.weight(1f), copyable = true) }
            overview.forms?.let { StatTile(it.toString(), "forms", Modifier.weight(1f), copyable = true) }
            overview.links?.let { StatTile(it.toString(), "links", Modifier.weight(1f), copyable = true) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            overview.viewportWidth?.let { StatTile("$it", "viewport w", Modifier.weight(1f), copyable = true) }
            overview.viewportHeight?.let { StatTile("$it", "viewport h", Modifier.weight(1f), copyable = true) }
            overview.devicePixelRatio?.let {
                StatTile(compactNumber(it), "dpr", Modifier.weight(1f), copyable = true)
            }
        }

        FactRow("Ready state", overview.readyState)
        FactRow("Origin", overview.origin)
        FactRow("Language", overview.lang)
        FactRow("Encoding", overview.charset)
        FactRow("Content type", overview.contentType)
        FactRow("Secure context", overview.isSecureContext?.let { if (it) "yes" else "no" })
        FactRow("Service worker", overview.serviceWorker)
        FactRow("Manifest", overview.manifest)
        FactRow("Notifications", overview.notificationPermission)
        FactRow("Storage", storageRowValue(overview))
        Text(
            "Counts and flags only: this reports which of these the page has, never what " +
                "is inside them.",
            style = MaterialTheme.typography.labelSmall,
            color = extras.textSecondary
        )
    }
}

@Composable
internal fun FactRow(label: String, value: String?) {
    val extras = LocalRoomExtras.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = extras.textSecondary,
            modifier = Modifier.width(110.dp)
        )
        Text(
            value ?: "—",
            style = MaterialTheme.typography.labelSmall,
            color = extras.textPrimary,
            modifier = Modifier.weight(1f)
        )
        // Only a row that HAS a value is copyable: copying the em dash that
        // stands for "the engine did not say" would put nothing useful on the
        // clipboard while looking like it worked.
        if (value != null) {
            RoomCopyButton(value, label, "Copy $label")
        }
    }
}

internal fun capabilityLabel(capability: DevToolsCapability): String = when (capability) {
    DevToolsCapability.PAGE_SCRIPTING -> "Page scripting"
    DevToolsCapability.CONSOLE_CAPTURE -> "Console capture"
    DevToolsCapability.ENGINE_CONSOLE -> "Engine console"
    DevToolsCapability.NETWORK_REQUEST_LINE -> "Network requests"
    DevToolsCapability.NETWORK_RESPONSE_HEADERS -> "Response headers"
    DevToolsCapability.COOKIE_ATTRIBUTES -> "Cookie attributes"
    DevToolsCapability.SECURITY_INFO -> "Connection security"
    DevToolsCapability.SECURITY_CERTIFICATE -> "Certificate view"
    DevToolsCapability.PROTOCOL_SESSION -> "DevTools protocol"
    DevToolsCapability.CPU_PROFILER -> "CPU profiler"
    DevToolsCapability.JS_DEBUGGER -> "JavaScript debugger"
}

internal fun capabilityDetail(capability: DevToolsCapability): String = when (capability) {
    DevToolsCapability.PAGE_SCRIPTING ->
        "Running a script in the live page and reading its result — what every other panel is built on."
    DevToolsCapability.CONSOLE_CAPTURE -> "Messages the page itself logs."
    DevToolsCapability.ENGINE_CONSOLE -> "Messages the browser engine reports about the page."
    DevToolsCapability.NETWORK_REQUEST_LINE -> "Method, URL and request headers of each load."
    DevToolsCapability.NETWORK_RESPONSE_HEADERS -> "Status and response headers, read without breaking streaming."
    DevToolsCapability.COOKIE_ATTRIBUTES -> "HttpOnly / Secure / Expires / SameSite, not just name and value."
    DevToolsCapability.SECURITY_INFO -> "Protocol version, cipher and mixed-content state for this page."
    DevToolsCapability.SECURITY_CERTIFICATE -> "The certificate the connection actually presented."
    DevToolsCapability.PROTOCOL_SESSION -> "The engine's own inspector protocol, if this build can reach it."
    DevToolsCapability.CPU_PROFILER -> "A sampled CPU profile of a page interaction."
    DevToolsCapability.JS_DEBUGGER -> "Breakpoints and stepping through page script."
}
