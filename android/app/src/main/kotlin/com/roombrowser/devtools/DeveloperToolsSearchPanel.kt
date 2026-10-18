package com.roombrowser.devtools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomCopyButton
import com.roombrowser.ui.common.SectionHeader
import kotlinx.coroutines.launch

/**
 * How much of a console text or a network URL a result row draws. A row in a
 * phone-width dock is a pointer to the thing, not the thing itself: the full
 * text is in the Console panel and the full URL in the Network panel, and those
 * are where a reader goes next.
 */
private const val ROW_TEXT_CAP = 160

/**
 * The pinned sentence every truncated category shows, so the cap is never a
 * surprise. It states the bound the probe actually stopped at.
 */
internal const val SEARCH_TRUNCATED_LINE = "First 100 shown — the page holds more."

/**
 * One search over everything this surface can read, from one query field.
 *
 * THE USER PRESSES THE BUTTON. There is no debounce and nothing searches while
 * the user types: a probe on every keystroke would hammer the eval queue for
 * nothing, and the answer to a half-typed query is a wrong answer. The IME's
 * own search key runs the same path as the button.
 *
 * WHAT ONE RUN READS. The page's own globals, storage keys, cookies and element
 * text, all through the one synchronous probe -- plus the console and network
 * buffers this surface already holds, searched app-side where they live. A
 * category with nothing in it draws nothing: "0 results" is not a finding.
 *
 * VALUES STAY BEHIND THE SAME RULE AS EVERYWHERE ELSE. A storage hit names its
 * key and where it matched; the value is read only when the reader taps that
 * one row, and then it lives in a dialog with a copy control rather than in any
 * buffer or report this module can export.
 */
@Composable
internal fun SearchPanel(session: InspectorSession) {
    val extras = LocalRoomExtras.current
    val scope = rememberCoroutineScope()

    var query by remember(session) { mutableStateOf("") }
    var outcome by remember(session) { mutableStateOf<SearchOutcome?>(null) }
    var running by remember(session) { mutableStateOf(false) }
    var revealing by remember(session) { mutableStateOf<StorageHit?>(null) }

    fun runSearch() {
        // Under two characters there is nothing worth a round trip, and a
        // second tap while one search is in flight would race it for the state
        // it is about to write.
        if (query.length < 2 || running) return
        running = true
        val asked = query
        scope.launch {
            val probe = session.searchProbe(asked)
            outcome = SearchOutcome(
                query = asked,
                probe = probe,
                console = consoleHits(session.console.snapshot(), asked),
                network = networkHits(session.network.snapshot(), asked)
            )
            running = false
        }
    }

    SectionHeader("Search")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Find anything on this page") },
            shape = MaterialTheme.shapes.small,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "devtools_search_field" }
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = { runSearch() },
            modifier = Modifier.semantics { contentDescription = "devtools_search_run" }
        ) {
            Text("Search")
        }
    }
    searchState(running, outcome, query)?.let { line ->
        Text(
            line,
            style = MaterialTheme.typography.labelSmall,
            color = extras.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )
    }

    val answer = outcome
    if (answer != null) {
        SearchResults(answer, onReveal = { hit -> revealing = hit })
    }

    revealing?.let { hit ->
        StorageValueDialog(hit, session, onDismiss = { revealing = null })
    }
}

/**
 * What the state line says, so an empty panel is never unexplained.
 *
 * The unanswered sentence and the reading sentence are the Audit panel's own
 * words: one state, one spelling, everywhere it appears. The hint is checked
 * last because it is about the NEXT search, and a reader who just watched one
 * fail should see why before being told to type more.
 */
private fun searchState(running: Boolean, outcome: SearchOutcome?, query: String): String? = when {
    running -> "Reading the page..."
    outcome != null && outcome.probe == null -> "The page did not answer."
    query.length < 2 -> "Type at least 2 characters to search."
    else -> null
}

/**
 * Everything one run found, in one fixed order: the page first, then the two
 * buffers this surface already holds.
 *
 * A category with no hits renders nothing at all -- not "0 results", which
 * would read as a finding about the page rather than as the absence of one.
 * When every category is empty the panel says so once, centered, in the
 * reader's own words: the query they typed.
 */
@Composable
private fun SearchResults(answer: SearchOutcome, onReveal: (StorageHit) -> Unit) {
    val extras = LocalRoomExtras.current
    val probe = answer.probe

    if (probe != null) {
        if (probe.variables.isNotEmpty()) {
            SectionHeader("Page variables (${probe.variables.size})")
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                probe.variables.forEach { hit -> VariableRow(hit) }
            }
            if (probe.variablesTruncated) {
                TruncationLine()
            }
        }
        if (probe.storage.isNotEmpty()) {
            SectionHeader("Storage (${probe.storage.size})")
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                probe.storage.forEach { hit -> StorageRow(hit) { onReveal(hit) } }
            }
            if (probe.storageTruncated) {
                TruncationLine()
            }
        }
        if (probe.cookies.isNotEmpty()) {
            SectionHeader("Cookies (${probe.cookies.size})")
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                probe.cookies.forEach { hit -> CookieRow(hit) }
            }
        }
        if (probe.dom.isNotEmpty()) {
            SectionHeader("Page text and elements (${probe.dom.size})")
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                probe.dom.forEach { hit -> DomRow(hit) }
            }
            if (probe.domTruncated) {
                TruncationLine()
            }
        }
    }

    if (answer.console.isNotEmpty()) {
        SectionHeader("Console (${answer.console.size})")
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            answer.console.forEach { entry -> ConsoleRow(entry) }
        }
    }
    if (answer.network.isNotEmpty()) {
        SectionHeader("Network (${answer.network.size})")
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            answer.network.forEach { entry -> NetworkRow(entry) }
        }
    }

    if (probe != null &&
        probe.variables.isEmpty() && probe.storage.isEmpty() &&
        probe.cookies.isEmpty() && probe.dom.isEmpty() &&
        answer.console.isEmpty() && answer.network.isEmpty()
    ) {
        Text(
            "No matches for \"${answer.query}\" on this page.",
            style = MaterialTheme.typography.bodySmall,
            color = extras.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        )
    }
}

/** The one sentence a capped category owes its reader. */
@Composable
private fun TruncationLine() {
    val extras = LocalRoomExtras.current
    Text(
        SEARCH_TRUNCATED_LINE,
        style = MaterialTheme.typography.labelSmall,
        color = extras.textSecondary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
    )
}

/** One page variable: its name in monospace, its type, and a short value when there is one. */
@Composable
private fun VariableRow(hit: VariableHit) {
    val extras = LocalRoomExtras.current
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                hit.name,
                style = MaterialTheme.typography.bodySmall,
                color = extras.textPrimary,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                hit.kind,
                style = MaterialTheme.typography.labelSmall,
                color = extras.textSecondary
            )
        }
        hit.preview?.let { preview ->
            Text(
                preview,
                style = MaterialTheme.typography.labelSmall,
                color = extras.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * One storage hit: the key, the area it lives in, and where it matched.
 *
 * Tapping the row is the ONLY path by which the value reaches the app -- the
 * probe matched it in the page and reported the fact and the length, and the
 * value itself waits behind this tap, exactly as it does in the Application
 * panel.
 */
@Composable
private fun StorageRow(hit: StorageHit, onReveal: () -> Unit) {
    val extras = LocalRoomExtras.current
    // The whole row is the tap target -- the key, the area and the matched-in
    // line are one hit, and a reader aiming at any part of it means the same
    // reveal.
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onReveal)
            .padding(vertical = 3.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                hit.key,
                style = MaterialTheme.typography.bodySmall,
                color = extras.textPrimary,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                hit.area,
                style = MaterialTheme.typography.labelSmall,
                color = extras.textSecondary,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(extras.surfaceAlt)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        if (hit.matchedIn == "value") {
            Text(
                hit.valueLength?.let { length -> "matched in value ($length chars)" } ?: "matched in value",
                style = MaterialTheme.typography.labelSmall,
                color = extras.textSecondary
            )
        }
    }
}

/** One cookie the page can see: its name, and where it matched. The value is never carried. */
@Composable
private fun CookieRow(hit: CookieHit) {
    val extras = LocalRoomExtras.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            hit.name,
            style = MaterialTheme.typography.bodySmall,
            color = extras.textPrimary,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (hit.matchedIn == "value") {
            Text(
                "matched in value",
                style = MaterialTheme.typography.labelSmall,
                color = extras.textSecondary
            )
        }
    }
}

/** One element hit: `tag#id.class`, and the window around the match in its own text. */
@Composable
private fun DomRow(hit: DomHit) {
    val extras = LocalRoomExtras.current
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            domSelector(hit),
            style = MaterialTheme.typography.bodySmall,
            color = extras.textPrimary,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        hit.snippet?.let { snippet ->
            Text(
                snippet,
                style = MaterialTheme.typography.labelSmall,
                color = extras.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** One console hit: the level, and as much of the text as a row can carry. */
@Composable
private fun ConsoleRow(entry: ConsoleEntry) {
    val extras = LocalRoomExtras.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            entry.level,
            style = MaterialTheme.typography.labelSmall,
            color = extras.textSecondary,
            modifier = Modifier.width(44.dp)
        )
        Text(
            entry.text.take(ROW_TEXT_CAP),
            style = MaterialTheme.typography.bodySmall,
            color = extras.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/** One network hit: the URL, and the status when the engine reported one. */
@Composable
private fun NetworkRow(entry: NetworkEntry) {
    val extras = LocalRoomExtras.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            entry.url.take(ROW_TEXT_CAP),
            style = MaterialTheme.typography.bodySmall,
            color = extras.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            entry.status?.toString() ?: "no status",
            style = MaterialTheme.typography.labelSmall,
            color = extras.textSecondary,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/**
 * The value behind one storage hit, read on demand and shown no wider than the
 * dialog it arrived in.
 *
 * The copy control carries the SAME pair the Application panel's reveal copies
 * -- the key and the value it was hiding, in that object's own words -- so the
 * two spellings of one reveal cannot drift.
 */
@Composable
private fun StorageValueDialog(hit: StorageHit, session: InspectorSession, onDismiss: () -> Unit) {
    val extras = LocalRoomExtras.current
    // Keyed on the hit, so a second row's reveal starts from "not read yet"
    // rather than from the previous row's value.
    var revealed by remember(hit) { mutableStateOf<String?>(null) }
    LaunchedEffect(hit) {
        revealed = session.storageValue(hit.area, hit.key)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(hit.key) },
        text = {
            // Scrollable: a long value in a landscape dialog cannot show itself
            // whole, and an unscrollable dialog would cut the copy target off.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    hit.area,
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.textSecondary
                )
                Text(
                    revealed ?: "(the value could not be read)",
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.textPrimary,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        confirmButton = {
            RoomCopyButton(
                DeveloperToolsStorageText.storageValueText(hit.area, hit.key, revealed),
                "${hit.area} key",
                "Copy this ${hit.area} key"
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/** One class-list separator, so `tag#id.class` reads as the selector it is named after. */
private val WHITESPACE = Regex("\\s+")

/** `tag#id.class` for one element hit, in the selector's own spelling. */
private fun domSelector(hit: DomHit): String = buildString {
    append(hit.tag)
    hit.id?.takeIf { it.isNotBlank() }?.let { append('#').append(it) }
    hit.classes?.takeIf { it.isNotBlank() }?.let { classes ->
        append('.')
        append(classes.trim().split(WHITESPACE).joinToString("."))
    }
}

/** One finished search: the query it answered, the page's answer, and the two buffers searched app-side. */
private data class SearchOutcome(
    val query: String,
    val probe: SearchProbe?,
    val console: List<ConsoleEntry>,
    val network: List<NetworkEntry>
)
