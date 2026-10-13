package com.roombrowser.browser

import com.roombrowser.devtools.InspectorSession
import com.roombrowser.domain.agent.DiagReport
import com.roombrowser.domain.agent.DomEvidence
import com.roombrowser.domain.model.Profile
import com.roombrowser.engine.EngineSession
import com.roombrowser.engine.devtools.DeveloperToolsCapabilities

/**
 * The browser operations the agent is allowed to name, as ONE interface.
 *
 * WHY it exists: the executors used to reach [BrowserViewModel] and the
 * Developer Tools manager directly, so "what the agent can make this browser
 * do" was spread across whichever private helpers happened to call the view
 * model. Here it is a single surface an implementation satisfies, which is
 * what makes it possible to hold the agent's reach still while the app under
 * it changes — and what makes the reach reviewable in one place.
 *
 * It is deliberately NARROW and browser-shaped. Nothing here guesses at a
 * coordinate, dispatches a synthetic tap, or reaches a screen the user's own
 * controls cannot: every method is something the app already does on the user's
 * behalf.
 *
 * It is not an engine facade. [EngineSession] and [DeveloperToolsCapabilities]
 * are the app-side facade types both editions already share, named here because
 * the Developer Tools surface is built on them.
 */
interface BrowserControlApi {

    /** The engine's name and version, for a diagnostic report. */
    val engineName: String
    val engineVersion: String

    // ---- navigation and tabs ----

    fun navigate(url: String)

    /** Opens a tab and returns its id, or null when no tab could be opened. */
    suspend fun openTab(url: String?): String?

    fun closeTab(tabId: String?)

    fun switchTab(tabId: String)

    fun reload(tabId: String?)

    fun goForward(tabId: String?)

    // ---- the page the user is looking at ----

    fun find(query: String, forward: Boolean)

    fun clearFind()

    fun setDesktopMode(enabled: Boolean)

    /** Opens the Developer Tools surface, pointed at [tabId] (or the tab on screen). */
    fun openDevToolsPanel(tabId: String?)

    // ---- files ----

    /**
     * Queue a download of [url] through the profile's own download engine, the
     * same one a link the user taps goes through, and return the file name it
     * will be saved as — or null when this profile has no engine yet, so a
     * caller never reports a file that was not queued.
     *
     * [name] is a suggestion: the engine still de-duplicates it and still takes
     * the server's Content-Disposition when the URL itself names nothing. The
     * transfer runs in the background and reports through the downloads list,
     * so this returning is not the file arriving.
     */
    fun startDownload(url: String, name: String?): String?

    // ---- tab identity ----

    fun activeTabId(): String?

    fun tabIds(): List<String>

    fun tabTitle(tabId: String): String?

    fun tabUrl(tabId: String): String?

    fun tabSession(tabId: String): EngineSession?

    // ---- Developer Tools ----

    /**
     * The live inspector for [tabId], attaching one and starting console and
     * network capture the first time it is asked. Null when the tab has no
     * engine session to attach to.
     *
     * The buffers are the REAL ones the panels show — this does not sniff
     * anything of its own, so what the agent reads is what the user sees.
     */
    fun inspectorFor(tabId: String): InspectorSession?

    /** What the engine behind [tabId] can actually serve, honest reasons included. */
    fun capabilitiesFor(tabId: String): DeveloperToolsCapabilities

    /**
     * Builds a diagnostic report from this tab: engine and app identity, the
     * profile's label, the active URL, the redacted console and network
     * buffers, [dom] evidence and the correlated findings.
     *
     * The caller supplies the [dom] evidence (it owns the page snapshot) and
     * the optional [focus] the user pointed at; everything else is read here.
     */
    suspend fun captureDiagnostics(
        tabId: String?,
        dom: List<DomEvidence> = emptyList(),
        focus: String = ""
    ): DiagReport

    /** The profile this browser is bound to, for the report's label. */
    fun profile(): Profile

    /**
     * Writes a diagnostic report to a file this profile owns and returns its
     * absolute path, or null when there is nowhere to write. The file stays
     * inside the app's own directory — it is not placed in Downloads, so the
     * tool's answer says where it actually is.
     */
    fun saveDiagnosticReport(text: String, fileName: String): String?
}
