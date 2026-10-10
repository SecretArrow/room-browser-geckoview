package com.roombrowser.devtools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.roombrowser.engine.devtools.DeveloperToolsCapabilities
import com.roombrowser.engine.devtools.DevToolsPanelId
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomCopyButton
import com.roombrowser.ui.common.SectionHeader

/**
 * What one panel is handed when it composes.
 *
 * The session is nullable because a tab can be open with no live engine yet --
 * the start page, or an engine being rebuilt under a live tab. A panel that
 * needs an engine says so itself rather than being left uncomposed, so the
 * reader is told why the panel is empty instead of being shown nothing.
 *
 * The page overview and the refresh callback are here because reading the page
 * is one pull owned by the host -- two panels that each pulled it would double
 * the cost of opening the surface and could disagree about the same page.
 */
internal class DevToolsPanelScope(
    val session: InspectorSession?,
    val engineName: String,
    val capabilities: DeveloperToolsCapabilities,
    val tabUrl: String,
    val tabTitle: String,
    val overview: PageOverview?,
    val answered: Boolean?,
    val hasEngine: Boolean,
    val onRefresh: () -> Unit
)

/**
 * One entry in the panel strip.
 *
 * [isAvailable] is the entry's own question -- "can this engine serve THIS
 * panel" -- and for every panel that is the capability layer's
 * `DeveloperToolsCapabilities.serves`, so the decision about what an engine can
 * back lives in one place and is testable without Compose. It is a predicate
 * rather than a set of required capabilities because two entries are satisfied
 * by either of a pair.
 *
 * THIS PREDICATE IS THE WHOLE MECHANISM BEHIND "hide what this edition cannot
 * do". A panel whose predicate is false is not composed at all: not composed
 * empty, not composed disabled. Its absence from the strip is the answer.
 */
internal class DevToolsPanelEntry(
    val id: DevToolsPanelId,
    val isAvailable: (DeveloperToolsCapabilities) -> Boolean,
    val content: @Composable (DevToolsPanelScope) -> Unit
)

/**
 * The panels this build ships, in strip order.
 *
 * This list is the panels that have been BUILT, which is shorter than the panels
 * this engine could serve -- see `servedPanels()`. Nothing outside this list is
 * reachable, so adding a panel is one entry plus its content.
 *
 * The order is not the enum's: Overview first (it is where a reader lands) and
 * then the built panels, with the ones a reader opens most often earliest.
 */
internal fun devToolsPanelEntries(): List<DevToolsPanelEntry> = listOf(
    DevToolsPanelEntry(DevToolsPanelId.OVERVIEW, { it.serves(DevToolsPanelId.OVERVIEW) }, { OverviewPanel(it) }),
    DevToolsPanelEntry(DevToolsPanelId.APPLICATION, { it.serves(DevToolsPanelId.APPLICATION) }, { ApplicationPanel(it) }),
    DevToolsPanelEntry(DevToolsPanelId.SECURITY, { it.serves(DevToolsPanelId.SECURITY) }, { SecurityPanel(it) }),
    DevToolsPanelEntry(
        DevToolsPanelId.AUDIT,
        { it.serves(DevToolsPanelId.AUDIT) },
        { scope ->
            if (scope.session != null) {
                AuditPanel(scope)
            } else {
                NeedsEngine("The audit needs a live page to read.")
            }
        }
    ),
    DevToolsPanelEntry(
        DevToolsPanelId.CONSOLE,
        { it.serves(DevToolsPanelId.CONSOLE) },
        { scope -> scope.session?.let { ConsolePanel(it) } ?: NeedsEngine("The console needs a live page to read.") }
    ),
    DevToolsPanelEntry(
        DevToolsPanelId.NETWORK,
        { it.serves(DevToolsPanelId.NETWORK) },
        { scope -> scope.session?.let { NetworkPanel(it) } ?: NeedsEngine("The network feed needs a live page to read.") }
    )
)

/**
 * The entries this engine can serve.
 *
 * Also the answer to "which panel is selected when the one selected is not in
 * the list" -- the strip clamps to the first entry here.
 */
internal fun availablePanels(capabilities: DeveloperToolsCapabilities): List<DevToolsPanelEntry> =
    devToolsPanelEntries().filter { it.isAvailable(capabilities) }

/**
 * The strip: one chip per panel this build serves, and nothing else.
 *
 * It is drawn from [availablePanels], so a panel the engine cannot serve is
 * absent rather than greyed out. A disabled chip would read as a promise that
 * some later release turns it on, and for the panels an INSTALLED engine decides
 * -- which is most of them -- that promise is not ours to make.
 */
@Composable
internal fun DevToolsPanelStrip(
    entries: List<DevToolsPanelEntry>,
    selected: DevToolsPanelId?,
    onSelect: (DevToolsPanelId) -> Unit
) {
    val extras = LocalRoomExtras.current
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        entries.forEach { entry ->
            val isSelected = entry.id == selected
            Text(
                entry.id.title,
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) extras.onButton else extras.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) extras.primary else extras.surfaceAlt)
                    .clickable { onSelect(entry.id) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    // The chips carry the same words as the section headers
                    // below them, so "Console" alone names two different nodes.
                    // This is what tells them apart -- for a screen reader and
                    // for the e2e suite alike.
                    .semantics {
                        contentDescription = panelChipDescription(entry.id)
                        this.selected = isSelected
                    }
            )
        }
    }
}

/**
 * What a strip chip announces itself as.
 *
 * One function rather than a string at each call site, because the e2e suite
 * selects panels by this spelling and a test that builds its own would pass
 * while selecting nothing.
 */
internal fun panelChipDescription(id: DevToolsPanelId): String = "${id.title} panel"

/**
 * What a panel shows when the tab has no live engine.
 *
 * It is a sentence rather than an absence: a selected chip with nothing under it
 * reads as a broken panel, and the reason -- the engine is still starting, or
 * this is the start page -- is one this panel knows.
 */
@Composable
internal fun NeedsEngine(what: String) {
    val extras = LocalRoomExtras.current
    Text(
        what,
        style = MaterialTheme.typography.bodySmall,
        color = extras.textSecondary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    )
}

/**
 * One report section: its header, a copy control, and the text both draw.
 *
 * [body] is written once and handed to both, so what is on screen and what a
 * copy carries cannot drift apart.
 */
@Composable
internal fun Block(body: String, title: String) {
    val extras = LocalRoomExtras.current
    SectionHeader(title) {
        RoomCopyButton(body, title, "Copy the $title section")
    }
    Text(
        body,
        style = MaterialTheme.typography.bodySmall,
        color = extras.textPrimary,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 6.dp)
    )
}
