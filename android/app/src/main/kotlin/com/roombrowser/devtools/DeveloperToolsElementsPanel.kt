package com.roombrowser.devtools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomCopyButton
import com.roombrowser.ui.common.SectionHeader
import kotlinx.coroutines.launch

/**
 * The Elements panel: the live document, one level at a time.
 *
 * A PULL PER EXPANSION, NEVER A WALK OF THE WHOLE DOCUMENT. The panel fetches the
 * root, and then a node's children only when that node is opened, so the cost is
 * what the reader expanded rather than what the page contains -- which is the
 * only way this is affordable on a phone holding a 5,000-node DOM.
 *
 * A NODE ID IS ONLY VALID FOR ONE GENERATION. Every tree read rebuilds the
 * page-side registry, so an id from before a refresh addresses nothing, and the
 * detail section says that instead of showing another node's attributes.
 */
@Composable
internal fun ElementsPanel(scope: DevToolsPanelScope) {
    val extras = LocalRoomExtras.current
    val session = scope.session
    val coroutine = rememberCoroutineScope()
    val state = remember(session) { ElementsTreeState() }
    var revision by remember(session) { mutableIntStateOf(0) }
    var reading by remember(session) { mutableStateOf(session != null) }
    var busy by remember(session) { mutableStateOf(false) }
    var refreshKey by remember(session) { mutableIntStateOf(0) }

    LaunchedEffect(session, refreshKey) {
        state.adopt(null)
        revision++
        if (session == null) {
            reading = false
            return@LaunchedEffect
        }
        reading = true
        state.adopt(session.elementsTree())
        revision++
        reading = false
    }

    fun readDetail(nodeId: Int) {
        state.selected = nodeId
        state.detail = null
        revision++
        val attached = session ?: return
        coroutine.launch {
            busy = true
            val gen = state.generation
            val detail = if (gen == null) null else attached.elementDetail(gen, nodeId)
            state.detail = detail
            state.stale = detail?.stale == true
            busy = false
            revision++
        }
    }

    fun toggle(node: ElementNode) {
        val id = node.id ?: return
        val open = id in state.expanded
        if (open) {
            state.expanded -= id
            revision++
            return
        }
        state.expanded += id
        readDetail(id)
        if (state.children.containsKey(id)) return
        val attached = session ?: return
        val gen = state.generation ?: return
        coroutine.launch {
            busy = true
            attached.elementChildren(gen, id, 0)?.let { page ->
                if (page.stale == true) {
                    state.stale = true
                } else {
                    state.addChildren(id, page)
                }
            }
            busy = false
            revision++
        }
    }

    fun showMore(node: ElementNode) {
        val id = node.id ?: return
        val attached = session ?: return
        val gen = state.generation ?: return
        val from = state.nextFrom(id)
        coroutine.launch {
            busy = true
            attached.elementChildren(gen, id, from)?.let { page ->
                if (page.stale == true) {
                    state.stale = true
                } else {
                    state.addChildren(id, page)
                }
            }
            busy = false
            revision++
        }
    }

    // Read on every revision, so a state write from a coroutine is what redraws
    // the panel rather than a recomposition that happens to come along.
    val rows = remember(revision) {
        DeveloperToolsElementsText.visibleRows(state.root, state.children, state.expanded)
    }
    val detail = state.detail
    val shown = state.selected

    SectionHeader("Elements") {
        RoomCopyButton(
            DeveloperToolsElementsText.treeReport(rows, scope.engineName),
            "Elements",
            "Copy the visible element tree"
        )
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            elementsState(session != null, reading, busy, state.root, state.stale),
            style = MaterialTheme.typography.labelSmall,
            color = extras.textSecondary,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = { refreshKey++ }, enabled = session != null) {
            Icon(Icons.Filled.Refresh, contentDescription = "Read the document again", tint = extras.icon)
        }
    }
    Text(
        DeveloperToolsElementsText.TREE_CAPTION,
        style = MaterialTheme.typography.labelSmall,
        color = extras.textSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
    )
    Text(
        DeveloperToolsElementsText.treeSummary(state, rows.size),
        style = MaterialTheme.typography.labelSmall,
        color = extras.textSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
    )

    Column(Modifier.fillMaxWidth()) {
        rows.forEach { row ->
            ElementRow(
                row = row,
                selected = row.node.id != null && row.node.id == shown,
                expanded = row.node.id != null && row.node.id in state.expanded,
                onToggle = { toggle(row.node) }
            )
            // The remainder of a long child list is one explicit tap away, and
            // the line says how much of it is left rather than only offering a
            // button that might do nothing.
            val id = row.node.id
            if (id != null && id in state.expanded) {
                val held = state.children[id].orEmpty().size
                val total = state.totals[id] ?: row.node.children ?: 0
                if (held < total) {
                    ShowMoreRow(id, held, total) { showMore(row.node) }
                }
            }
        }
    }

    if (shown != null) {
        Block(
            DeveloperToolsElementsText.nodeDetailText(detail, scope.engineName),
            "Selected element"
        )
    }
}

/** What the panel is doing, so an empty tree is never unexplained. */
private fun elementsState(
    hasSession: Boolean,
    reading: Boolean,
    busy: Boolean,
    root: ElementNode?,
    stale: Boolean
): String = when {
    !hasSession -> "No engine session for this tab yet."
    reading -> "Reading the document..."
    stale -> "The document changed under this panel; read the tree again."
    root == null -> "The page did not answer, so no tree was read."
    busy -> "Reading a node..."
    else -> "Reading the live document, one level at a time."
}

/** How far a row indents. Clamped, because a 30-deep tree would otherwise push its own name off screen. */
private const val MAX_INDENT_DEPTH = 8

@Composable
private fun ElementRow(
    row: ElementTreeRow,
    selected: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val extras = LocalRoomExtras.current
    val node = row.node
    val hasChildren = (node.children ?: 0) > 0
    val indent = row.depth.coerceAtMost(MAX_INDENT_DEPTH) * 12
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(start = (8 + indent).dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .semantics { contentDescription = "element node ${node.id}" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = if (hasChildren) extras.icon else extras.textSecondary,
            modifier = Modifier.width(20.dp)
        )
        Text(
            DeveloperToolsElementsText.rowText(node),
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) extras.primary else extras.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        RoomCopyButton(
            DeveloperToolsElementsText.rowText(node),
            "Element",
            "Copy this element row"
        )
    }
}

@Composable
private fun ShowMoreRow(nodeId: Int, held: Int, total: Int, onMore: () -> Unit) {
    val extras = LocalRoomExtras.current
    Text(
        "showing $held of $total children -- show more",
        style = MaterialTheme.typography.labelSmall,
        color = extras.primary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onMore() }
            .padding(start = 32.dp, top = 2.dp, bottom = 2.dp)
            .semantics { contentDescription = "show more children of element $nodeId" }
    )
}
