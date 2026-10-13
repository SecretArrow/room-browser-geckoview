package com.roombrowser.browser

import android.content.Context
import com.roombrowser.browser.engine.DownloadEngine
import com.roombrowser.browser.engine.ProfileEngine
import com.roombrowser.data.net.SystemFacts
import com.roombrowser.devtools.DeveloperToolsManager
import com.roombrowser.devtools.InspectorSession
import com.roombrowser.domain.agent.ConsoleLine
import com.roombrowser.domain.agent.DiagReport
import com.roombrowser.domain.agent.DiagnosisCorrelator
import com.roombrowser.domain.agent.DiagnosisInput
import com.roombrowser.domain.agent.DomEvidence
import com.roombrowser.domain.agent.NetworkRow
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.UserAgents
import com.roombrowser.engine.EngineSession
import com.roombrowser.engine.devtools.DeveloperToolsCapabilities
import java.io.File

/**
 * [BrowserControlApi] over the [BrowserViewModel] and the app's own
 * [DeveloperToolsManager].
 *
 * It adds no way to reach the browser: every method is a call the app's own
 * screens already make. It exists so the agent's reach is one reviewable
 * surface rather than a set of private helpers.
 *
 * [inspectorFor] is the one method with a side effect a panel does not have —
 * it starts capture on a session nobody has opened a panel for, because an
 * agent that asked for the console is the open panel as far as the buffers are
 * concerned. Capture is started once per session and never stopped here; the
 * session's own teardown paths close it.
 */
class BrowserControlApiImpl(
    private val vm: BrowserViewModel,
    private val context: Context
) : BrowserControlApi {

    private val devtools: DeveloperToolsManager get() = vm.devtools

    /** Sessions whose capture has already been started, so a second ask does not stack a callback. */
    private val capturing: MutableSet<InspectorSession> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<InspectorSession, Boolean>())

    override val engineName: String
        get() = runCatching { ProfileEngine.engineName(context) }.getOrDefault("unknown engine")

    override val engineVersion: String
        get() = runCatching {
            ProfileEngine.installedEngines(context).firstOrNull { it.isCurrent }?.versionName
        }.getOrNull() ?: "unknown"

    // ---- navigation and tabs ----

    override fun navigate(url: String) {
        vm.loadUrl(url)
    }

    override suspend fun openTab(url: String?): String? {
        vm.openNewTab(url?.takeIf { it.isNotBlank() } ?: "about:home")
        return vm.activeTabId
    }

    override fun closeTab(tabId: String?) {
        val id = tabId ?: vm.activeTabId ?: return
        vm.closeTab(id)
    }

    override fun switchTab(tabId: String) {
        vm.selectTab(tabId)
    }

    override fun reload(tabId: String?) {
        val id = tabId ?: vm.activeTabId
        if (id == null || vm.isActiveTab(id)) {
            vm.reload()
            return
        }
        vm.tabSession(id)?.reload()
    }

    override fun goForward(tabId: String?) {
        val id = tabId ?: vm.activeTabId
        val session = id?.let { vm.tabSession(it) }
        if (session == null) {
            vm.goForward()
            return
        }
        session.goForward()
    }

    // ---- the page the user is looking at ----

    override fun find(query: String, forward: Boolean) {
        if (forward) vm.findInPageNavigate(true, query) else vm.findInPage(query)
    }

    override fun clearFind() {
        vm.clearFindInPage()
    }

    override fun setDesktopMode(enabled: Boolean) {
        if (vm.pageState.desktopMode != enabled) vm.toggleDesktopMode()
    }

    override fun openDevToolsPanel(tabId: String?) {
        val id = tabId ?: vm.activeTabId ?: return
        devtools.open(id)
    }

    // ---- files ----

    override fun startDownload(url: String, name: String?): String? {
        // The profile's own user agent, the same identity the page presents,
        // so a server that serves the file only to a real browser still does.
        val userAgent = UserAgents.effectiveUserAgent(vm.profileSettings())
        val suggested = name?.trim()?.takeIf { it.isNotBlank() }
            ?: DownloadEngine.guessFileName(url, null, "")
        return if (vm.download(url, suggested, "", userAgent)) suggested else null
    }

    // ---- tab identity ----

    override fun activeTabId(): String? = vm.activeTabId

    override fun tabIds(): List<String> = vm.tabs.map { it.id }

    override fun tabTitle(tabId: String): String? = vm.tabSession(tabId)?.title

    override fun tabUrl(tabId: String): String? = vm.tabSession(tabId)?.url

    override fun tabSession(tabId: String): EngineSession? = vm.tabSession(tabId)

    // ---- Developer Tools ----

    override fun inspectorFor(tabId: String): InspectorSession? {
        val session = vm.tabSession(tabId) ?: return null
        val inspector = devtools.attach(tabId, session) ?: return null
        if (capturing.add(inspector)) {
            inspector.startConsole()
            inspector.startNetwork()
        }
        return inspector
    }

    override fun capabilitiesFor(tabId: String): DeveloperToolsCapabilities =
        inspectorFor(tabId)?.capabilities
            ?: runCatching { ProfileEngine.devToolsCapabilities(context) }
                .getOrDefault(DeveloperToolsCapabilities.NONE)

    override suspend fun captureDiagnostics(
        tabId: String?,
        dom: List<DomEvidence>,
        focus: String
    ): DiagReport {
        val id = tabId ?: vm.activeTabId
        val inspector = id?.let { inspectorFor(it) }

        // The page's own performance entries are the history the live signal
        // cannot replay, so the network buffer is topped up before it is read.
        // A tab with no inspector simply has no rows.
        if (inspector != null) {
            val timings = runCatching { inspector.resourceTimings() }.getOrNull()
            if (timings != null) inspector.addResourceTimings(timings)
        }

        val console = inspector?.console?.snapshot().orEmpty().map { ConsoleLine(it.level, it.text) }
        val network = inspector?.network?.snapshot().orEmpty().map {
            NetworkRow(url = it.url, status = it.status ?: 0, method = it.method ?: "GET")
        }
        val findings = DiagnosisCorrelator.correlate(
            DiagnosisInput(console = console, network = network, dom = dom, focus = focus)
        )
        val device = runCatching { SystemFacts.device(context) }.getOrNull()

        return DiagReport(
            engineName = engineName,
            engineVersion = engineVersion,
            androidRelease = device?.androidRelease ?: "unknown",
            apiLevel = device?.apiLevel ?: 0,
            appVersion = device?.appVersion ?: "unknown",
            profile = vm.profile,
            activeUrl = id?.let { tabUrl(it) } ?: vm.pageState.url,
            console = console,
            network = network,
            dom = dom,
            findings = findings,
            generatedAtMillis = System.currentTimeMillis()
        )
    }

    override fun profile(): Profile = vm.profile

    override fun saveDiagnosticReport(text: String, fileName: String): String? = runCatching {
        val dir = context.getExternalFilesDir(null)?.resolve(DIAG_DIR)
            ?: context.filesDir.resolve(DIAG_DIR)
        dir.mkdirs()
        val file = File(dir, fileName)
        file.writeText(text)
        file.absolutePath
    }.getOrNull()

    private companion object {
        const val DIAG_DIR = "room-browser-diagnostics"
    }
}
