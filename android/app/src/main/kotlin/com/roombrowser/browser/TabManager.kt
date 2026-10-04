package com.roombrowser.browser

import android.graphics.Bitmap
import com.roombrowser.data.db.TabEntity
import com.roombrowser.engine.EngineSession
import com.roombrowser.engine.EngineState
import java.util.concurrent.ConcurrentHashMap

/** In-memory tab model bound to an engine session slot. */
data class TabSession(
    val entity: TabEntity,
    val engine: EngineSession?,
    val thumbnail: Bitmap?,
    val desktopMode: Boolean = false,
    /** Monotonic recency stamp — drives LRU eviction of live engines. */
    val lastUsedAt: Long = 0L
) {
    val id: String get() = entity.id
    val url: String get() = entity.url
    val title: String get() = entity.title.ifBlank { entity.url }
}

/**
 * Tab session manager for ONE profile (the profile bound to this process).
 * Lazy restoration: only the active tab owns a live engine; background
 * tabs hold persisted state (URL/title), optional thumbnails and the
 * back/forward state captured when their engine was evicted or closed.
 *
 * Every open tab has a SESSION by construction (see [ensureSession]) — a
 * tab whose live engine is not tracked here is invisible to the LRU
 * budget, never gets destroyed on close, and loses its page on reselect.
 */
class TabManager {

    private val sessions = linkedMapOf<String, TabSession>()
    private val thumbnails = HashMap<String, Bitmap>()

    /**
     * Back/forward state saved right before an engine is destroyed
     * (LRU eviction or tab close). Keyed by tab id, so a state can never
     * be restored into a DIFFERENT tab; survives [remove] so a closed tab
     * that is reopened gets its history back; dies with [clear] (profile
     * switch — another profile's tabs must never receive this history).
     *
     * The value is the facade's opaque [EngineState]: this class stores it
     * and hands it back and never looks inside, because whatever the engine
     * serialised — a WebView `Bundle` in one edition, a GeckoView
     * `SessionState` in the other — is an engine type that may not appear
     * on this side of the facade.
     */
    private val savedStates = HashMap<String, EngineState>()

    /**
     * Reverse index over live engines: engine -> (owning tab id, that tab's
     * last known page URL).
     *
     * WHY IT EXISTS: engine callbacks carry the session that fired, and both
     * questions asked of them — "which tab owns this engine?" and "which page
     * is THIS engine showing?" — are answered from the firing session.
     * [sessions] cannot answer either from a background thread.
     *
     * WHY CONCURRENT: [pageUrlFor] is read by the engine's resource-request
     * callback, which runs on an engine BACKGROUND thread for every
     * sub-resource of every session; [sessions] is main-thread state and
     * iterating it from there could see a resize mid-flight.
     *
     * WHY THE KEYS ARE SOUND: [EngineSession] implementations are required
     * not to override equals or hashCode (the facade states it as a contract,
     * see the interface's KDoc), so the keys are engine IDENTITY — exactly the
     * `session.engine === engine` semantics the old loops had. That was a
     * property a `WebView` had by inheriting identity equality, and it is the
     * one property this map cannot do without: a value-equal session would
     * make two live tabs collide here.
     *
     * Kept in lockstep with [sessions] through the single [store] funnel. A
     * STALE entry is worse than a missing one: a missing entry falls back to
     * the pre-fix behaviour, a stale one would route an engine's callback to
     * a tab that no longer owns it.
     */
    private val engineIndex = ConcurrentHashMap<EngineSession, EngineEntry>()

    /** Immutable index value, replaced wholesale and never mutated, so a
     *  reader can never see a tab id and a URL from different tabs. */
    private data class EngineEntry(val id: String, val url: String)

    /**
     * THE only place a session's engine reference is written: updates
     * [sessions] and [engineIndex] together, so the reverse index can never
     * disagree with the map it indexes. Main thread only.
     */
    private fun store(session: TabSession): TabSession {
        val previous = sessions[session.id]?.engine
        val engine = session.engine
        if (previous != null && previous !== engine) engineIndex.remove(previous)
        sessions[session.id] = session
        if (engine != null) engineIndex[engine] = EngineEntry(session.id, session.url)
        return session
    }

    fun restore(entities: List<TabEntity>) {
        entities.forEach { store(TabSession(it, null, thumbnails[it.id])) }
    }

    fun add(entity: TabEntity, engine: EngineSession?): TabSession =
        store(TabSession(entity, engine, thumbnails[entity.id]))

    /**
     * The session for this tab, created on demand and refreshed from the
     * latest entity — keeps any live engine/thumbnail the session already
     * holds. Called on every selectTab so "open tab ⇒ session" always holds.
     */
    fun ensureSession(entity: TabEntity): TabSession {
        val existing = sessions[entity.id]
        if (existing != null) return store(existing.copy(entity = entity))
        return store(TabSession(entity, null, thumbnails[entity.id]))
    }

    fun get(id: String): TabSession? = sessions[id]

    fun all(): List<TabSession> = sessions.values.toList()

    fun updateEntity(entity: TabEntity) {
        val existing = sessions[entity.id]
        if (existing != null) {
            store(existing.copy(entity = entity))
        } else {
            store(TabSession(entity, null, thumbnails[entity.id]))
        }
    }

    fun attachEngine(id: String, engine: EngineSession?) {
        sessions[id]?.let {
            store(it.copy(engine = engine, lastUsedAt = android.os.SystemClock.elapsedRealtime()))
        }
    }

    /** Drops the engine reference from whichever session holds [engine]
     *  (per-tab engines are owned by exactly ONE session). */
    fun detachEngine(engine: EngineSession?) {
        if (engine == null) return
        sessions.values.filter { it.engine === engine }
            .forEach { store(it.copy(engine = null)) }
    }

    /** Reverse of [attachEngine]: the tab OWNING [engine] (per-tab engines
     *  belong to exactly ONE session), or null when no session holds it —
     *  a destroyed/never-tracked engine. Used to route an engine's callbacks
     *  to its own tab instead of whichever tab happens to be active.
     *  Thread-safe: reads the concurrent [engineIndex] only. */
    fun idFor(engine: EngineSession?): String? {
        if (engine == null) return null
        return engineIndex[engine]?.id
    }

    /** Page URL of the tab OWNING [engine], or null when no session holds it
     *  (destroyed or mid-teardown engine). Safe from ANY thread: this is the
     *  one lookup the resource-request callback may perform, and it must
     *  never touch [sessions].
     *
     *  A null answer is the caller's cue to fall back — it is NOT a licence
     *  to judge the engine against some other tab's page. */
    fun pageUrlFor(engine: EngineSession?): String? {
        if (engine == null) return null
        return engineIndex[engine]?.url
    }

    /** Records [url] as this tab's current page URL, engine untouched. Called
     *  from the navigation funnel so a BACKGROUND engine's page host — the one
     *  the resource-request callback judges its sub-resources against — is as
     *  fresh as the row being persisted, rather than as stale as the last
     *  switch to that tab. Main thread only; no-op for a tab with no session
     *  (a late callback after the tab closed must not resurrect an index
     *  entry). */
    fun setPageUrl(id: String, url: String) {
        val session = sessions[id] ?: return
        if (session.url == url) return
        store(session.copy(entity = session.entity.copy(url = url)))
    }

    /** Stores the engine's back/forward state under [id] (pre-destroy).
     *  [state] is the facade's opaque token; a session that could not produce
     *  one returns null from `saveState` and the caller simply does not call
     *  this, which is the same "no history to restore" answer the WebView
     *  edition's empty-bundle guard gave. */
    fun saveEngineState(id: String, state: EngineState) {
        savedStates[id] = state
        if (savedStates.size > MAX_SAVED_STATES) {
            savedStates.keys.firstOrNull()?.let { savedStates.remove(it) }
        }
    }

    /** The saved engine state for [id] (null when this tab has none). */
    fun engineState(id: String): EngineState? = savedStates[id]

    fun captureThumbnail(id: String, bitmap: Bitmap?) {
        if (bitmap == null) return
        thumbnails[id] = bitmap
        if (thumbnails.size > MAX_THUMBS) {
            val oldest = thumbnails.keys.firstOrNull()
            oldest?.let { thumbnails.remove(it) }
        }
        sessions[id]?.let { store(it.copy(thumbnail = bitmap)) }
    }

    fun remove(id: String): TabSession? {
        val removed = sessions.remove(id) ?: return null
        removed.engine?.let { engineIndex.remove(it) }
        return removed
    }

    fun setDesktopMode(id: String, enabled: Boolean) {
        sessions[id]?.let { store(it.copy(desktopMode = enabled)) }
    }

    fun privateTabs(): List<TabSession> = sessions.values.filter { it.entity.isPrivate }

    /** Sessions that currently hold a LIVE engine (the active one included). */
    fun liveEngineSessions(): List<TabSession> =
        sessions.values.filter { it.engine != null }

    /**
     * LRU eviction candidates: background sessions (never [keepId]) holding
     * live engines, OLDEST first. The caller destroys as many as needed to
     * stay under the live-engine budget — evicted tabs gracefully fall back
     * to lazy re-creation (entity + thumbnail + saved state survive).
     */
    fun lruVictims(keepId: String?): List<TabSession> =
        sessions.values
            .filter { it.engine != null && it.id != keepId }
            .sortedBy { it.lastUsedAt }

    fun clear() {
        sessions.clear()
        thumbnails.clear()
        // Engine-state tokens die with the profile context: the next
        // profile's tabs must never receive this profile's history.
        savedStates.clear()
        // The engine index is a view of `sessions`; it dies with them.
        engineIndex.clear()
    }

    companion object {
        const val MAX_THUMBS = 8
        const val MAX_SAVED_STATES = 8
    }
}
