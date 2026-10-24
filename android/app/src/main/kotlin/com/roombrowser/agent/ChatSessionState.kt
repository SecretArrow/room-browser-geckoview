package com.roombrowser.agent

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.roombrowser.domain.agent.AgentPlan
import com.roombrowser.domain.agent.UntrustedContent

/**
 * EVERY piece of chat UI state one conversation owns, held together so no two
 * chats can ever share it.
 *
 * WHY THIS EXISTS. The controller used to keep one `entries` list, one
 * `running`, one `statusLine`, one `approval`, one `plan` and one
 * `retryable` for the WHOLE profile, and a chat switch re-pointed them all —
 * so a turn had to refuse every rebind while it ran (freezing the panel on
 * its chat) and could still splice another chat's rows into its list when a
 * rebind raced it. A state per session dissolves that whole class of bug:
 * the turn writes into the state it captured at its start, the panel renders
 * the state of the session it is showing, and the two need never be the same
 * chat.
 *
 * THREADING: [entries] is a [SnapshotStateList] so an append or an in-place
 * update invalidates ONLY the rows that changed (the controller used to pay a
 * whole-list copy per streamed token). All other fields are ordinary Compose
 * state. Writes arrive from the turn's own event sequence — which the gateway
 * runs on IO — and from main-coroutine loads; every field here is a snapshot-
 * backed type that tolerates both, which is exactly why they were chosen.
 *
 * LIFETIME: created on demand by [BrowserAgentController.stateFor], kept in an
 * access-ordered map with a small cap so a profile with hundreds of chats
 * cannot accumulate hundreds of loaded windows (see the controller's
 * [BrowserAgentController.sessionStates]). A running or on-screen session is
 * never evicted.
 */
class ChatSessionState(val sessionId: Long?) {

    /**
     * The transcript, oldest first. Granular by design: the streaming path
     * copy()-replaces ONE element (see [upsertStreaming]) and appends land at
     * the end, so Compose re-reads a row, not a list.
     */
    val entries: SnapshotStateList<AgentEntry> = mutableStateListOf()

    /** Whether a turn is running IN THIS CHAT. At most one turn runs app-wide. */
    var running: Boolean by mutableStateOf(false)
        internal set

    /** The turn's progress line, cleared when the turn ends or pauses out. */
    var statusLine: String? by mutableStateOf(null)
        internal set

    /**
     * The pending Allow/Deny prompt, if any. It lives IN the chat it was
     * raised for — the panel shows whichever chat it is on, and the controller
     * surfaces a RUNNING turn's prompt even from another chat (there is at
     * most one turn, so there is at most one prompt to find).
     */
    var approval: AgentApproval? by mutableStateOf(null)
        internal set

    /**
     * The step list this chat last wrote with `plan`, empty until it does.
     * Owned by the chat, not the controller: a new chat, another chat and
     * another tab all start empty, and coming back to this chat finds the
     * list exactly where the model left it.
     */
    var plan: List<AgentPlan.Item> by mutableStateOf(emptyList())
        internal set

    /**
     * Whether the DB holds transcript rows older than [entries] shows — the
     * "load older" affordance's switch. True only once a window was loaded.
     */
    var hasOlderEntries: Boolean by mutableStateOf(false)
        internal set

    /**
     * The last turn that did not finish HERE, kept so it can be sent again.
     * Per chat on purpose: a failed turn belongs to the chat it was asked in.
     */
    var retryable: RetryableTurn? by mutableStateOf(null)
        internal set

    /**
     * The index of the open streaming bubble, or null when none is open. It
     * lives here rather than in a turn-local var so the turn's `finally` drain
     * reads exactly what the listener last wrote, wherever the panel has
     * drifted to meanwhile.
     */
    var streamingIndex: Int? by mutableStateOf(null)
        internal set

    /**
     * The id of the oldest ROW [entries] was built from — the keyset cursor
     * "load older" pages below. Tracked from the ROWS and not the entries so
     * a row whose entry was dropped (an unparseable plugin card) is still
     * paged past, or the next window would re-read it, drop it again, and
     * offer "load older" forever.
     */
    var oldestLoadedRowId: Long? = null

    /**
     * How many persisted rows the chat had when [entries] was last loaded —
     * the transcript cache's freshness test. A chat whose row count still
     * matches re-renders from memory on every revisit with no query at all;
     * a turn that landed rows while the chat was away moves the count, and
     * the next look reloads the window.
     */
    var persistedRowCount: Int = 0

    /** Whether a transcript window was ever loaded into this state. */
    var hasLoadedWindow: Boolean = false

    /** Whether [plan] was ever read from the chat's row (vs the default empty). */
    var planLoaded: Boolean = false

    // ------------------------------------------------------------ transcript

    /** Appends one row at the end. */
    internal fun appendEntry(entry: AgentEntry) {
        entries.add(entry)
    }

    /** Prepends a page of older rows ("load older"); answers how many landed. */
    internal fun prependOlder(older: List<AgentEntry>): Int {
        entries.addAll(0, older)
        return older.size
    }

    /** Swaps the whole list — the chat-switch / window-reload shape. */
    internal fun replaceAll(next: List<AgentEntry>) {
        entries.clear()
        entries.addAll(next)
    }

    /**
     * The turn's streamed bubble: finds-or-appends it, then applies one
     * coalesced window of deltas. Answers the (possibly new) index.
     */
    internal fun upsertStreaming(index: Int, text: String, thinking: String): Int {
        val i = if (index in entries.indices) {
            val current = entries[index]
            if (current is AgentEntry.Assistant && current.streaming) index else -1
        } else -1
        val target = if (i >= 0) i else {
            entries.add(AgentEntry.Assistant("", "", streaming = true, at = System.currentTimeMillis()))
            entries.size - 1
        }
        val current = entries[target] as AgentEntry.Assistant
        entries[target] = current.copy(
            text = current.text + text,
            thinking = current.thinking + thinking
        )
        return target
    }

    /**
     * Closes the open streaming bubble: the fence can only come off once the
     * whole answer is in. Returns -1 — "no bubble open" — for the next write.
     */
    internal fun finalizeStreaming(index: Int): Int {
        if (index in entries.indices) {
            val entry = entries[index]
            if (entry is AgentEntry.Assistant && entry.streaming) {
                entries[index] = entry.copy(
                    streaming = false,
                    text = UntrustedContent.stripMarkers(entry.text)
                )
            }
        }
        return -1
    }

    /** Updates the tool card with [callId] in place; a miss is a no-op. */
    internal fun updateTool(callId: String, transform: (AgentEntry.Tool) -> AgentEntry.Tool) {
        val index = entries.indexOfFirst { it is AgentEntry.Tool && it.callId == callId }
        if (index >= 0) {
            val entry = entries[index]
            entries[index] = transform(entry as AgentEntry.Tool)
        }
    }

    /**
     * The model's finished answer, where [AgentTranscript] says it lands: the
     * open bubble is REPLACED (its text was this same answer's deltas), and
     * anything else gets a row of its own. The pure function keeps the WHERE;
     * this applies it in place so the other rows are not re-materialized.
     *
     * SUSPEND only because [AgentTranscript.answer] is (its usage hook is);
     * the transcript work itself is in-memory list shaping.
     */
    internal suspend fun applyAnswer(openIndex: Int, answer: String, at: Long) {
        // Usage is NOT this state's business: the controller's FinalAnswer
        // branch persists the reported counts onto the assistant row directly
        // (repo.setMessageUsage), because the row id exists only there.
        val next = AgentTranscript.answer(entries.toList(), openIndex, answer, at)
        when {
            // The replace branch of AgentTranscript.answer: same size, one row.
            next.size == entries.size && openIndex in entries.indices ->
                entries[openIndex] = next[openIndex]
            next.size == entries.size + 1 -> entries.add(next.last())
            else -> replaceAll(next) // unreachable today; the honest fallback
        }
    }

    // ------------------------------------------------------------ turn state

    /**
     * A turn started against the null-keyed scratch state (the tab had no
     * chat yet) and has now resolved a real session id: everything written so
     * far — the user's bubble, a notice — moves onto the resolved chat's own
     * state, which may ALREADY hold a loaded window those rows belong under.
     */
    internal fun adoptFrom(other: ChatSessionState) {
        if (other === this) return
        entries.addAll(other.entries)
        retryable = other.retryable
        other.entries.clear()
        other.retryable = null
    }

    /** Empties the transcript and the window bookkeeping that paged it in. */
    internal fun clearTranscriptWindow() {
        entries.clear()
        hasOlderEntries = false
        oldestLoadedRowId = null
        hasLoadedWindow = false
        persistedRowCount = 0
    }
}
