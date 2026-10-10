package com.roombrowser.devtools

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.roombrowser.engine.EngineSession

/** How the Developer Tools surface occupies the window. */
enum class DevToolsDock { CLOSED, DOCKED, FULLSCREEN, MINIMIZED }

/**
 * The Developer Tools surface's state: how it is docked, which tab it is
 * looking at, and the per-tab [InspectorSession] registry.
 *
 * One instance lives on [com.roombrowser.browser.BrowserViewModel], because
 * every teardown path except the user closing the panel is a tab-lifecycle
 * event the view model already owns. Keeping the registry here rather than in
 * a composable is what makes "close the tab ⇒ the inspector is gone" a fact
 * instead of a hope: the panel composable comes and goes with recomposition,
 * the view model does not.
 *
 * Sessions are keyed by tab id AND hold the [EngineSession] they were built
 * on, so a tab that outlives its engine (a profile switch, a crash recovery)
 * gets a fresh inspector instead of a stale handle to a dead one.
 *
 * Not thread-safe and not meant to be: it is touched from the main thread by
 * Compose and by the view model's own tab coroutines.
 */
class DeveloperToolsManager {

    var dock: DevToolsDock by mutableStateOf(DevToolsDock.CLOSED)
        private set

    /** The tab the surface is inspecting; null while closed. */
    var tabId: String? by mutableStateOf(null)
        private set

    private val attachedEngine = mutableMapOf<String, EngineSession>()
    private val sessions = mutableMapOf<String, InspectorSession>()

    val isOpen: Boolean get() = dock != DevToolsDock.CLOSED

    fun open(tabId: String) {
        if (dock == DevToolsDock.CLOSED) dock = DevToolsDock.DOCKED
        this.tabId = tabId
    }

    /**
     * Points the surface at the tab now in front.
     *
     * Switching tabs does NOT close Developer Tools — it retargets it, which
     * is what a user expects of a panel that is describing "this page". An
     * inspector is built for the new tab on the next composition.
     */
    fun focus(tabId: String?) {
        if (!isOpen || tabId == null) return
        this.tabId = tabId
    }

    /** Closes the surface and tears down every inspector it opened. */
    fun close() {
        if (dock == DevToolsDock.CLOSED && sessions.isEmpty()) return
        dock = DevToolsDock.CLOSED
        tabId = null
        sessions.values.forEach { it.close() }
        sessions.clear()
        attachedEngine.clear()
    }

    fun toggleFullscreen() {
        dock = when (dock) {
            DevToolsDock.FULLSCREEN -> DevToolsDock.DOCKED
            DevToolsDock.MINIMIZED -> DevToolsDock.FULLSCREEN
            else -> DevToolsDock.FULLSCREEN
        }
    }

    fun toggleMinimize() {
        dock = if (dock == DevToolsDock.MINIMIZED) DevToolsDock.DOCKED else DevToolsDock.MINIMIZED
    }

    /**
     * The inspector for [tabId], created on first ask and rebuilt if the tab's
     * engine is a different object than the one the inspector was built on.
     * Null when the tab has no live engine yet.
     *
     * A null [session] is NOT the tab closing — it is the start page, or an
     * engine being rebuilt under a live tab — so it must not go through
     * [onTabClosed]: that would clear [tabId] for the very tab the surface is
     * pointed at, and since nothing but [open]/[focus] ever restores it the
     * panel would vanish while still reporting itself open. The tab keeps its
     * (closed) inspector and the surface renders "no engine session yet".
     */
    fun attach(tabId: String, session: EngineSession?): InspectorSession? {
        if (session == null) {
            sessions.remove(tabId)?.close()
            attachedEngine.remove(tabId)
            return null
        }
        val existing = sessions[tabId]
        if (existing != null && attachedEngine[tabId] === session) return existing
        existing?.close()
        return InspectorSession(tabId, session).also {
            sessions[tabId] = it
            attachedEngine[tabId] = session
        }
    }

    fun inspectorFor(tabId: String?): InspectorSession? = tabId?.let { sessions[it] }

    /**
     * A tab the surface may be describing has gone. When it is the tab being
     * described the whole surface closes, because a panel pointed at no tab
     * composes nothing while still reporting itself open.
     */
    fun onTabClosed(tabId: String) {
        if (this.tabId == tabId) {
            close()
            return
        }
        dropInspector(tabId)
    }

    /**
     * Drops every inspector built on [session].
     *
     * The engine can die without its tab closing — LRU eviction, a profile
     * switch, a crash — and the inspector must not outlive the handle it was
     * built on. Identified by session rather than by tab id because this is
     * reached from the engine-teardown funnel, which does not know the id.
     *
     * The surface stays open on purpose: the tab outlives its engine and gets a
     * fresh inspector on the next composition.
     */
    fun detachEngine(session: EngineSession) {
        attachedEngine.filterValues { it === session }.keys.toList().forEach { dropInspector(it) }
    }

    private fun dropInspector(tabId: String) {
        sessions.remove(tabId)?.close()
        attachedEngine.remove(tabId)
    }

    /**
     * The safety net: drops every inspector whose tab is no longer open.
     *
     * The three named teardown paths (panel close, tab close, engine close)
     * all cover the cases we know about; this covers the one we do not — a tab
     * removed by a path that forgot to tell us. Without it a leaked inspector
     * holds a subscription on a dead session forever.
     */
    fun retainTabs(openTabIds: Set<String>) {
        sessions.keys.filterNot { it in openTabIds }.forEach { onTabClosed(it) }
        val current = tabId
        if (isOpen && (current == null || current !in openTabIds)) close()
    }
}
