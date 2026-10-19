package com.roombrowser.devtools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomCopyButton
import com.roombrowser.ui.common.SectionHeader

/**
 * How many rows a panel draws at once.
 *
 * The feed keeps [InspectorSession.CONSOLE_CAP]; this is what a 320dp emulator
 * can put on screen without the whole panel going to jelly. The caption always
 * says which of the two numbers the user is looking at.
 */
private const val RENDER_MAX = 200

private val LEVELS = listOf("error", "warn", "log")

@Composable
internal fun ConsolePanel(session: InspectorSession) {
    val extras = LocalRoomExtras.current

    // Reading the revision is what subscribes this panel to the feed: the sink
    // bumps it on the main thread, so there is no timer and no polling.
    val revision = session.consoleRevision
    val entries = remember(session, revision) { session.console.snapshot() }
    var level by remember(session) { mutableStateOf<String?>(null) }

    DisposableEffect(session) {
        session.startConsole()
        onDispose { session.stopConsole() }
    }

    // One pass derives both lists the panel reads: the filtered feed (the copy
    // control reports the WHOLE of it, not the window) and the RENDER_MAX-row
    // window it draws, keyed for Compose. The cache hands back the SAME
    // instances whenever the derivation's content did not change, so a feed
    // event the active filter excludes costs the snapshot pass and nothing
    // downstream — no new window, no rebuilt report, no row recomposed.
    val feed = remember(session) { FeedViewCache<ConsoleEntry>() }
        .derive(entries, session.console.dropped, RENDER_MAX, levelMatches(level))
    val matching = feed.matching
    val shown = feed.rows
    val keys = feed.keys
    val caption = feedCaption(
        shown = shown.size,
        kept = entries.size,
        cap = session.console.capacity,
        dropped = session.console.dropped
    )
    // The whole-feed report walks every matching entry (up to the ring's cap)
    // to build one pasteable block, and the panel recomposes on every feed
    // event — so it is built only when the feed it reports actually changed.
    // The cache's instance stability is what makes that key hit rather than
    // churn: an unchanged `matching` is the same list object, not a new equal
    // one.
    val report = remember(matching, level, session.console.capacity, session.console.dropped) {
        consoleReport(matching, level, session.console.capacity, session.console.dropped)
    }

    SectionHeader("Console") {
        // The whole feed, not the [RENDER_MAX] rows on screen: the ring is the
        // bound worth reporting, and a paste that quietly held only the newest
        // 200 of 2000 would read as a quiet page. The header line carries the
        // count, the filter and any loss, so the text states its own bound.
        RoomCopyButton(
            report,
            "Console",
            "Copy these console entries"
        )
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = extras.textSecondary,
            modifier = Modifier.weight(1f)
        )
        RoomCopyButton(caption, "Console caption", "Copy the console counts")
        IconButton(onClick = { session.clearConsole() }) {
            Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear the console feed", tint = extras.icon)
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        LevelChooser(level) { level = it }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (entries.isEmpty()) {
            Text(
                "Nothing logged yet. This feed keeps the page's own console output and, " +
                    "where the engine offers it, the engine's own messages.",
                style = MaterialTheme.typography.bodySmall,
                color = extras.textSecondary
            )
        }
        // WHY KEYS AND NOT A LAZY LIST: these rows sit inside the dock's outer
        // vertical scroll, which hands them unbounded height — a LazyColumn
        // there is measured against infinity and crashes, and giving it a
        // bounded height would nest one scroll inside another and change which
        // rows are reachable. Keying the rows buys the half virtualization
        // would have: when the window slides (the ring drops an old entry and a
        // new one arrives), a survivor keeps its key and its composition is
        // REUSED rather than recomposed, so one console message costs one row,
        // not RENDER_MAX.
        for (i in shown.indices) {
            key(keys[i]) { ConsoleRow(shown[i]) }
        }
    }
}

@Composable
private fun LevelChooser(level: String?, onLevel: (String?) -> Unit) {
    val extras = LocalRoomExtras.current
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (listOf<String?>(null) + LEVELS).forEach { option ->
            val selected = option == level
            Text(
                option ?: "all",
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) extras.onButton else extras.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) extras.primary else extras.surfaceAlt)
                    .clickable { onLevel(option) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun ConsoleRow(entry: ConsoleEntry) {
    val extras = LocalRoomExtras.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            entry.level,
            style = MaterialTheme.typography.labelSmall,
            color = levelColor(entry.level),
            modifier = Modifier.width(44.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                entry.text,
                style = MaterialTheme.typography.bodySmall,
                color = extras.textPrimary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            val where = listOfNotNull(
                entry.source,
                entry.line?.takeIf { it > 0 }?.toString(),
                if (entry.fromEngine) "engine" else null
            )
            if (where.isNotEmpty()) {
                Text(
                    where.joinToString(":"),
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        // A console entry is the unit people paste into a report, so it is
        // copyable on its own: the feed-wide control beside the header would
        // otherwise make one line cost the whole feed.
        RoomCopyButton(consoleLine(entry), "Console entry", "Copy this console entry")
    }
}

@Composable
private fun levelColor(level: String): Color {
    val extras = LocalRoomExtras.current
    return when (level) {
        "error", "assert" -> MaterialTheme.colorScheme.error
        "warn" -> extras.secondary
        else -> extras.textSecondary
    }
}

/** The console panel's filter as [FeedViewCache.derive] takes it: null keeps every level. */
private fun levelMatches(level: String?): ((ConsoleEntry) -> Boolean)? =
    level?.let { wanted -> { entry: ConsoleEntry -> entry.level == wanted } }

/**
 * The derived view of one bounded feed that a panel draws: every entry the
 * active filter kept (`matching` — the copy control reports the whole filtered
 * feed, not the window), the newest [rows] of it, and one [keys] entry per row
 * for Compose to reuse its composition by.
 *
 * A key is the row's APPEND ORDINAL — the number of entries ever appended
 * before it, which is the ring's `dropped` count plus its index in the
 * snapshot. An ordinal belongs to one append for the ring's whole life, so
 * when the window slides every survivor keeps the key it already had; the
 * entries themselves cannot serve (a page can log the same line twice, and a
 * duplicate key is a crash), and a list position cannot either (it moves under
 * every survivor exactly when the window slides).
 *
 * Top-level and `internal` rather than private to the Console panel because
 * the Network panel derives its rows the same way, and a derivation kept in
 * two places is a derivation that drifts.
 */
internal class FeedView<E>(
    val matching: List<E>,
    val rows: List<E>,
    val keys: List<Int>
)

/**
 * Derives [FeedView] from one snapshot of a bounded feed. `matches` is null
 * when nothing is filtered out, which keeps `matching` as the snapshot itself
 * — no copy of a 2000-entry list per feed event.
 */
internal fun <E> feedView(
    entries: List<E>,
    dropped: Int,
    renderMax: Int,
    matches: ((E) -> Boolean)?
): FeedView<E> {
    if (matches == null) {
        val from = (entries.size - renderMax).coerceAtLeast(0)
        return FeedView(
            matching = entries,
            rows = entries.subList(from, entries.size),
            keys = (from until entries.size).map { dropped + it }
        )
    }
    val kept = ArrayList<E>()
    val ordinals = ArrayList<Int>()
    entries.forEachIndexed { index, entry ->
        if (matches(entry)) {
            kept.add(entry)
            ordinals.add(dropped + index)
        }
    }
    val from = (kept.size - renderMax).coerceAtLeast(0)
    return FeedView(
        matching = kept,
        rows = kept.subList(from, kept.size),
        keys = ordinals.subList(from, ordinals.size)
    )
}

/**
 * The derivation a feed panel keeps between recompositions.
 *
 * The point is not the filter pass (that is microseconds over the ring's cap)
 * but what a re-derive would drag behind it: a new `matching` instance makes
 * every remember keyed on it — the whole-feed report, which walks every
 * matching entry — rebuild, and a positional row window makes Compose
 * recompose every row the slide displaced. So when a re-derivation's content
 * is the same as the last one's, the LAST instances are handed back and none
 * of that happens.
 *
 * The rows and keys are functions of the matching list (and of the append
 * ordinals baked into it), so equal matching means equal rows; the keys a
 * reused view carries were computed under an older `dropped`, but a key only
 * has to be unique within one composition and stable under one entry — both
 * of which an ordinal stays, wherever the count started.
 */
internal class FeedViewCache<E> {
    private var last: FeedView<E>? = null

    fun derive(
        entries: List<E>,
        dropped: Int,
        renderMax: Int,
        matches: ((E) -> Boolean)?
    ): FeedView<E> {
        val candidate = feedView(entries, dropped, renderMax, matches)
        val previous = last
        if (previous != null && previous.matching == candidate.matching) {
            return previous
        }
        last = candidate
        return candidate
    }
}

/** The one caption every bounded feed shows, so the cap and any loss are never a surprise. */
internal fun feedCaption(shown: Int, kept: Int, cap: Int, dropped: Int): String {
    val head = "$shown of $kept · $cap max"
    return if (dropped > 0) "$head · $dropped older dropped" else head
}
