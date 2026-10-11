package com.roombrowser.agent

import com.roombrowser.browser.BrowserControlApi
import com.roombrowser.devtools.AuditVerdict
import com.roombrowser.devtools.ConsoleEntry
import com.roombrowser.devtools.DeveloperToolsAuditText
import com.roombrowser.devtools.DeveloperToolsElementsText
import com.roombrowser.devtools.DeveloperToolsSecurityText
import com.roombrowser.devtools.DeveloperToolsStorageText
import com.roombrowser.devtools.ElementChildren
import com.roombrowser.devtools.ElementNode
import com.roombrowser.devtools.InspectorSession
import com.roombrowser.devtools.NetworkEntry
import com.roombrowser.domain.agent.ActionVerdict
import com.roombrowser.domain.agent.AgentJson
import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.domain.agent.ConsoleLine
import com.roombrowser.domain.agent.DiagReportExport
import com.roombrowser.domain.agent.DiagnosisCorrelator
import com.roombrowser.domain.agent.DiagnosisFinding
import com.roombrowser.domain.agent.DiagnosisInput
import com.roombrowser.domain.agent.DiagnosisLabel
import com.roombrowser.domain.agent.DomEvidence
import com.roombrowser.domain.agent.NetworkRow
import com.roombrowser.domain.agent.PageSnapshotDto
import com.roombrowser.domain.agent.SecretRedaction
import com.roombrowser.domain.agent.ToolResult
import com.roombrowser.domain.agent.UntrustedContent
import com.roombrowser.engine.EngineSession
import com.roombrowser.engine.devtools.DevToolsCapability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.coroutines.resume

/**
 * The agent's Developer Tools tools — the real Console and Network buffers, the
 * Elements panel's tree, the Application and Security panels' probes, the Audit
 * panel's own checklist, correlation, the report export and a gated page patch.
 *
 * WHAT THIS IS NOT: a second, agent-shaped inspector. Every read goes through
 * [BrowserControlApi.inspectorFor], which returns the same [InspectorSession]
 * the panels show, so what the agent reads is what the user sees, and a panel
 * the engine cannot serve says so here exactly as it is hidden there. An empty
 * list is never dressed up as "nothing was there" — an absent capability is
 * reported as absent, with the engine's own reason.
 *
 * The one tool that changes anything is [AgentTools.DEVTOOLS_PATCH]: applying a
 * candidate fix runs script in a live page, so it goes through the destructive
 * gate every single time (nothing in settings switches that off) and is refused
 * outright in Plan mode and in unattended runs.
 */
class AgentDevToolsTools(
    private val control: BrowserControlApi,
    /** The tab this chat is bound to, for "this chat's tab" defaults. */
    private val boundTabId: () -> String?,
    private val onStatus: (String) -> Unit = {},
    /**
     * The gate for actions that cannot be undone. A page patch goes through it
     * unconditionally — the Confirm actions switch and YOLO do not reach it.
     */
    private val destructiveGate: suspend (name: String, label: String) -> ActionVerdict
) {

    /**
     * Whether each tab's console buffer is kept across a `devtools_reload`.
     *
     * The ring itself lives in memory and never clears on its own, so this flag
     * only decides whether [devtoolsReload] empties it first — which is the one
     * navigation this class owns. Stated plainly in the tool's answer, rather
     * than implied to mean every navigation.
     */
    private val keepConsole = mutableMapOf<String, Boolean>()

    /**
     * The element-tree generation last read per tab.
     *
     * A page-side node id is only meaningful inside the generation it was issued
     * for, so a drill-down reuses the generation the tree read handed back rather
     * than starting a new one and silently addressing different nodes.
     */
    private val elementsGen = mutableMapOf<String, Int>()

    /** Null when [name] is not a Developer Tools tool — the caller's other tools run. */
    suspend fun execute(name: String, argsJson: String): ToolResult? {
        if (name !in AgentTools.DEVTOOLS_TOOLS) return null
        val args = parseArgs(argsJson)
        return try {
            when (name) {
                AgentTools.DEVTOOLS_RELOAD -> devtoolsReload()
                AgentTools.DEVTOOLS_CONSOLE -> console(args)
                AgentTools.DEVTOOLS_NETWORK -> network(args)
                AgentTools.DEVTOOLS_INSPECT -> inspect(args)
                AgentTools.DEVTOOLS_ELEMENTS -> elements(args)
                AgentTools.DEVTOOLS_STORAGE -> storage()
                AgentTools.DEVTOOLS_SECURITY -> security()
                AgentTools.DEVTOOLS_AUDIT -> audit()
                AgentTools.DEVTOOLS_DIAGNOSE -> diagnose(args)
                AgentTools.DEVTOOLS_REPORT -> report()
                AgentTools.DEVTOOLS_PATCH -> patch(args)
                else -> null
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            ToolResult(false, t.message ?: t.javaClass.simpleName)
        }
    }

    // --------------------------------------------------------------- console

    private suspend fun console(args: JsonObject): ToolResult {
        val action = strArg(args, "action") ?: "read"
        val tabId = boundTabId() ?: return ToolResult(false, noTab())
        val inspector = control.inspectorFor(tabId)
        val caps = control.capabilitiesFor(tabId)
        val consoleAvailable = caps.has(DevToolsCapability.CONSOLE_CAPTURE) ||
            caps.has(DevToolsCapability.ENGINE_CONSOLE)
        if (!consoleAvailable) {
            return ToolResult(false, capabilityAbsent("the console", caps.noteFor(DevToolsCapability.CONSOLE_CAPTURE)))
        }
        val session = inspector ?: return ToolResult(false, noEngine(tabId))

        return when (action) {
            "clear" -> {
                session.clearConsole()
                ToolResult(true, "Cleared the console buffer for this chat's tab.")
            }
            "preserve" -> {
                val enabled = boolArg(args, "enabled")
                    ?: return ToolResult(false, "action=preserve needs 'enabled': true to keep entries across a reload, false to clear them on the next devtools_reload")
                keepConsole[tabId] = enabled
                ToolResult(
                    true,
                    "Console entries are now ${if (enabled) "KEPT" else "CLEARED"} on the next devtools_reload. " +
                        "Honest scope: this covers the reload this tool drives. A navigation started by the page " +
                        "or by navigate does not clear the buffer in this build."
                )
            }
            "read" -> {
                val level = strArg(args, "level")
                val contains = strArg(args, "contains")
                var rows = session.console.snapshot()
                if (level != null) rows = rows.filter { it.level.equals(level, ignoreCase = true) }
                if (contains != null) rows = rows.filter { it.text.contains(contains, ignoreCase = true) }
                ToolResult(true, renderConsole(rows, session, level, contains))
            }
            else -> ToolResult(false, "action must be read, clear or preserve, not '$action'")
        }
    }

    private fun renderConsole(
        rows: List<ConsoleEntry>,
        session: InspectorSession,
        level: String?,
        contains: String?
    ): String {
        val dropped = session.console.dropped
        val filterNote = buildList {
            level?.let { add("level=$it") }
            contains?.let { add("contains=\"$it\"") }
        }
        val header = buildString {
            append("Console: ${rows.size} entr${if (rows.size == 1) "y" else "ies"}")
            if (filterNote.isNotEmpty()) append(" (${filterNote.joinToString(", ")})")
            if (dropped > 0) append(" — $dropped older entr(y/ies) were dropped from the buffer")
        }
        if (rows.isEmpty()) {
            return header + "\n" +
                "The buffer is empty. Capture is running now, so reload the page and read again — " +
                "a buffer that was just started cannot show what the page logged before it."
        }
        val body = rows.takeLast(MAX_CONSOLE_ROWS).joinToString("\n") { e ->
            "[${e.level}] ${e.text}" + (e.source?.let { "  ($it${e.line?.let { l -> ":$l" } ?: ""})" } ?: "")
        }
        return UntrustedContent.wrap(SecretRedaction.redact("$header\n$body"), "the console")
    }

    // --------------------------------------------------------------- network

    private suspend fun network(args: JsonObject): ToolResult {
        val action = strArg(args, "action") ?: "read"
        val tabId = boundTabId() ?: return ToolResult(false, noTab())
        val inspector = control.inspectorFor(tabId)
        val caps = control.capabilitiesFor(tabId)
        val networkAvailable = caps.has(DevToolsCapability.NETWORK_REQUEST_LINE) ||
            caps.has(DevToolsCapability.PAGE_SCRIPTING)
        if (!networkAvailable) {
            return ToolResult(
                false,
                capabilityAbsent("the network log", caps.noteFor(DevToolsCapability.NETWORK_REQUEST_LINE))
            )
        }
        val session = inspector ?: return ToolResult(false, noEngine(tabId))

        return when (action) {
            "clear" -> {
                session.clearNetwork()
                ToolResult(true, "Cleared the network buffer for this chat's tab.")
            }
            "read" -> {
                // The page's own performance entries are the history the live
                // signal cannot replay, so the buffer is topped up before it is
                // read — the same rows the Network panel shows.
                val timings = runCatching { session.resourceTimings() }.getOrNull()
                if (timings != null) session.addResourceTimings(timings)
                val failedOnly = boolArg(args, "failed_only") == true
                val contains = strArg(args, "contains")
                var rows = session.network.snapshot()
                if (failedOnly) rows = rows.filter { it.status == null || it.status >= 400 }
                if (contains != null) rows = rows.filter { it.url.contains(contains, ignoreCase = true) }
                ToolResult(true, renderNetwork(rows, session, failedOnly, contains))
            }
            else -> ToolResult(false, "action must be read or clear, not '$action'")
        }
    }

    private fun renderNetwork(
        rows: List<NetworkEntry>,
        session: InspectorSession,
        failedOnly: Boolean,
        contains: String?
    ): String {
        val failed = rows.count { it.status == null || it.status >= 400 }
        val header = buildString {
            append("Network: ${rows.size} row(s), $failed failed")
            if (failedOnly) append(" (failed only)")
            contains?.let { append(" (contains=\"$it\")") }
            if (session.network.dropped > 0) append(" — ${session.network.dropped} older row(s) dropped")
        }
        if (rows.isEmpty()) {
            return header + "\n" +
                "No requests in the buffer. Capture is running now, so reload the page and read " +
                "again — requests the page made before capture started are not recoverable here."
        }
        val body = rows.takeLast(MAX_NETWORK_ROWS).joinToString("\n") { e ->
            val status = e.status?.toString() ?: "ERR"
            val time = e.durationMs?.let { " in ${(it.toInt())}ms" } ?: ""
            val head = "$status  ${e.method ?: "GET"} ${e.url}$time" + (e.note?.let { "  [$it]" } ?: "")
            // Header values were already masked when the row entered the buffer
            // (EngineNetworkSignal.toEntry), so this reuses that redaction
            // rather than re-deriving it.
            val headers = e.requestHeaders.map { "    > ${it.name}: ${it.value}" } +
                e.responseHeaders.map { "    < ${it.name}: ${it.value}" }
            (listOf(head) + headers).joinToString("\n")
        }
        return UntrustedContent.wrap(SecretRedaction.redact("$header\n$body"), "the network log")
    }

    // ---------------------------------------------------------------- inspect

    private suspend fun inspect(args: JsonObject): ToolResult {
        val tabId = boundTabId() ?: return ToolResult(false, noTab())
        val session = control.tabSession(tabId) ?: return ToolResult(false, noEngine(tabId))
        val ref = intArg(args, "ref")
        val selector = strArg(args, "selector")
        val query = when {
            ref != null -> "[${PageInjector.REF_ATTR}=\"$ref\"]"
            selector != null -> selector
            else -> return ToolResult(false, "devtools_inspect needs 'ref' (from read_page) or 'selector'")
        }
        val raw = evaluate(session, inspectJs(AgentJson.encodeToString(String.serializer(), query)))
            ?: return ToolResult(false, "the engine did not answer the element probe — the page may be mid-load")
        val text = unquote(raw)
        if (text.contains("\"found\":false")) {
            return ToolResult(false, "no element matched '$query'. Call read_page for fresh [ref] numbers.")
        }
        val label = if (ref != null) "element [$ref]" else "element '$selector'"
        return ToolResult(true, UntrustedContent.wrap(SecretRedaction.redact("$label:\n$text"), "the page"))
    }

    // --------------------------------------------------------------- elements

    private suspend fun elements(args: JsonObject): ToolResult {
        val tabId = boundTabId() ?: return ToolResult(false, noTab())
        val caps = control.capabilitiesFor(tabId)
        if (!caps.has(DevToolsCapability.PAGE_SCRIPTING)) {
            return ToolResult(
                false,
                capabilityAbsent("the element tree", caps.noteFor(DevToolsCapability.PAGE_SCRIPTING))
            )
        }
        val session = control.inspectorFor(tabId) ?: return ToolResult(false, noEngine(tabId))
        val nodeId = intArg(args, "node")
        val from = intArg(args, "from") ?: 0

        if (nodeId == null) {
            // The panel's own read: one generation, the root, then one page of
            // its children. The ids printed are the panel's, not a fresh walk.
            val tree = session.elementsTree()
                ?: return ToolResult(false, "the page did not answer the element read — the page may be mid-load")
            val gen = tree.gen
                ?: return ToolResult(false, "the element read carried no generation, so its node ids cannot be trusted")
            elementsGen[tabId] = gen
            val root = tree.node ?: return ToolResult(false, "the page reported no document element")
            val rootId = root.id ?: return ToolResult(false, "the page's root node had no id")
            val page = session.elementChildren(gen, rootId, 0)
                ?: return ToolResult(false, "the page did not answer the read for the root's children")
            val rows = listOf(root to 0) + page.nodes.orEmpty().map { it to 1 }
            return ToolResult(true, renderElements(gen, rows, childPageNote(page.nodes.orEmpty().size, page)))
        }

        val gen = elementsGen[tabId]
            ?: return ToolResult(
                false,
                "no element tree has been read for this tab yet — call devtools_elements with no 'node' first"
            )
        val page = session.elementChildren(gen, nodeId, from)
            ?: return ToolResult(false, "the page did not answer the element read")
        if (page.stale == true) {
            return ToolResult(
                false,
                "the document was re-read since those node ids were issued, so node $nodeId is no longer the one " +
                    "you read — call devtools_elements with no 'node' for a fresh tree"
            )
        }
        val children = page.nodes.orEmpty()
        val rows = children.map { it to 1 }
        val note = childPageNote(children.size, page)
            ?: if (children.isEmpty()) "node $nodeId has no element children" else null
        return ToolResult(true, renderElements(gen, rows, note))
    }

    private fun childPageNote(shown: Int, page: ElementChildren): String? {
        val total = page.total
        val more = if (total != null && total > shown) "(showing $shown of $total; pass 'from' to page on)" else null
        val capped = if (page.exhausted == true) "the page has more nodes than one read holds, so some are not shown" else null
        return listOfNotNull(more, capped).joinToString(". ").takeIf { it.isNotEmpty() }
    }

    private fun renderElements(gen: Int, rows: List<Pair<ElementNode, Int>>, note: String?): String {
        val text = buildString {
            appendLine("Elements — engine: ${control.engineName} (generation $gen)")
            appendLine(DeveloperToolsElementsText.TREE_CAPTION)
            rows.forEach { (node, depth) ->
                appendLine("${"  ".repeat(depth)}#${node.id} ${DeveloperToolsElementsText.rowText(node)}")
            }
            note?.let { appendLine(it) }
        }.trimEnd()
        return UntrustedContent.wrap(SecretRedaction.redact(text), "the page")
    }

    // --------------------------------------------------------------- storage

    private suspend fun storage(): ToolResult {
        val tabId = boundTabId() ?: return ToolResult(false, noTab())
        val caps = control.capabilitiesFor(tabId)
        if (!caps.has(DevToolsCapability.PAGE_SCRIPTING)) {
            return ToolResult(
                false,
                capabilityAbsent("the page's storage", caps.noteFor(DevToolsCapability.PAGE_SCRIPTING))
            )
        }
        val session = control.inspectorFor(tabId) ?: return ToolResult(false, noEngine(tabId))
        // The Application panel's own probe and its own report, so the two
        // cannot drift -- including the "still working, so this is partial"
        // header, which a hand-assembled copy would lose.
        val probe = session.applicationProbe()
            ?: return ToolResult(false, "the page did not answer the storage probe — the page may be mid-load")
        val frames = session.frames()
        // Cookies are a section of the panel's report but a separate permission
        // here, so the section is dropped rather than filled with a claim.
        val text = DeveloperToolsStorageText.applicationReport(
            probe = probe,
            frames = frames,
            cookies = null,
            engineName = control.engineName,
            includeCookies = false
        )
        return ToolResult(true, UntrustedContent.wrap(SecretRedaction.redact(text), "the page"))
    }

    // -------------------------------------------------------------- security

    private suspend fun security(): ToolResult {
        val tabId = boundTabId() ?: return ToolResult(false, noTab())
        val caps = control.capabilitiesFor(tabId)
        if (!caps.has(DevToolsCapability.SECURITY_INFO)) {
            return ToolResult(
                false,
                capabilityAbsent("the connection's security", caps.noteFor(DevToolsCapability.SECURITY_INFO))
            )
        }
        val session = control.inspectorFor(tabId) ?: return ToolResult(false, noEngine(tabId))
        val info = session.inspector.securityInfo()
        val probe = session.securityProbe()
        if (info == null && probe == null) {
            return ToolResult(false, "neither the engine nor the page reported anything about this connection")
        }
        // The certificate section is told which edition it is on, so a WebView
        // blank reads as "this build cannot read a certificate", never as
        // "this page presented none".
        val text = DeveloperToolsSecurityText.securityReport(
            info,
            probe,
            control.engineName,
            caps.has(DevToolsCapability.SECURITY_CERTIFICATE)
        )
        return ToolResult(true, UntrustedContent.wrap(SecretRedaction.redact(text), "the page"))
    }

    // ------------------------------------------------------------------ audit

    private suspend fun audit(): ToolResult {
        val tabId = boundTabId() ?: return ToolResult(false, noTab())
        val caps = control.capabilitiesFor(tabId)
        val session = control.inspectorFor(tabId) ?: return ToolResult(false, noEngine(tabId))

        // The Audit PANEL's own probe, not a private script, so the agent's
        // answer and the panel's answer are the same read and cannot disagree.
        val probe = session.auditProbe()
            ?: return ToolResult(false, "the engine did not answer the audit probe — the page may be mid-load")

        val findings = mutableListOf<DiagnosisFinding>()
        fun observed(title: String) {
            findings += DiagnosisFinding(DiagnosisLabel.OBSERVED_FACT, title, emptyList())
        }
        fun recommend(title: String) {
            findings += DiagnosisFinding(DiagnosisLabel.RECOMMENDATION, title, emptyList())
        }

        // Each finding is the panel's own line, verdict word and all.
        val rows = DeveloperToolsAuditText.rows(probe)
        rows.forEach { observed(it.line().trim()) }
        val failures = rows.count { it.verdict == AuditVerdict.FAIL }

        // The buffers say what the page's own report cannot: what it logged and
        // what it asked for that failed.
        val consoleErrors = session.console.snapshot().count { it.level.equals("error", true) }
        val failedRequests = session.network.snapshot().count { it.status == null || it.status >= 400 }
        observed("$consoleErrors console error(s) in the buffer")
        observed("$failedRequests failed request(s) in the buffer")
        if (failures > 0) recommend("$failures audit check(s) came back missing; the Audit panel lists the same verdicts.")
        if (consoleErrors > 0) recommend("Read the console with devtools_console and correlate with devtools_diagnose.")
        if (failedRequests > 0) recommend("Read the network log with devtools_network failed_only=true.")

        if (!caps.has(DevToolsCapability.CONSOLE_CAPTURE) && !caps.has(DevToolsCapability.ENGINE_CONSOLE)) {
            recommend(
                "This engine exposes no console capture, so the error count above cannot change here. " +
                    (caps.noteFor(DevToolsCapability.CONSOLE_CAPTURE) ?: "The engine reports no reason.")
            )
        }

        val header = "Page audit — ${control.tabUrl(tabId) ?: "(no url)"}\n${DeveloperToolsAuditText.CAPTION}"
        return ToolResult(true, renderReport(header, findings))
    }

    // --------------------------------------------------------------- diagnose

    private suspend fun diagnose(args: JsonObject): ToolResult {
        val tabId = boundTabId() ?: return ToolResult(false, noTab())
        val focus = strArg(args, "focus").orEmpty()
        val (console, network) = buffers(tabId)
        val dom = domEvidence(tabId)
        val findings = DiagnosisCorrelator.correlate(
            DiagnosisInput(console = console, network = network, dom = dom, focus = focus)
        )
        if (findings.isEmpty()) {
            return ToolResult(
                true,
                "Nothing to correlate: the console and network buffers for this chat's tab are empty. " +
                    "Reproduce the problem, then call devtools_diagnose again."
            )
        }
        val header = "Diagnosis — ${DiagnosisCorrelator.summary(DiagnosisInput(console, network, dom, focus), findings)}"
        return ToolResult(true, renderReport(header, findings))
    }

    // ----------------------------------------------------------------- report

    private suspend fun report(): ToolResult {
        val tabId = boundTabId() ?: return ToolResult(false, noTab())
        val dom = domEvidence(tabId)
        onStatus("Building the diagnostic report…")
        val diag = control.captureDiagnostics(tabId, dom)
        val text = DiagReportExport.format(diag)
        val fileName = DiagReportExport.suggestedFileName(diag.generatedAtMillis)
        val path = control.saveDiagnosticReport(text, fileName)
        return if (path == null) {
            // Nothing was written, so the report is handed over inline rather
            // than claimed to be saved — an export that says "saved" and did
            // not is the failure this tool exists to avoid.
            ToolResult(
                true,
                "I could not write a file here, so here is the report itself. Copy it if you want to keep it:\n\n" + text
            )
        } else {
            ToolResult(
                true,
                "Wrote the diagnostic report to:\n$path\n\n" +
                    "It is in this profile's own app folder, not in Downloads — open it from a file manager " +
                    "under Android/data/<package>/files/room-browser-diagnostics/. " +
                    "Everything in it is redacted: header values and token-bearing URLs are already masked."
            )
        }
    }

    // ------------------------------------------------------------------ patch

    private suspend fun patch(args: JsonObject): ToolResult {
        val script = strArg(args, "script")
            ?: return ToolResult(false, "missing 'script' argument")
        val reason = strArg(args, "reason").orEmpty().ifBlank { "apply a fix to the live page" }
        val label = "run this patch on the live page: " + reason.take(80)
        when (val verdict = destructiveGate(AgentTools.DEVTOOLS_PATCH, label)) {
            is ActionVerdict.Allow -> Unit
            is ActionVerdict.Deny -> return ToolResult(false, verdict.reason)
            is ActionVerdict.Ask -> return ToolResult(false, "the user did not confirm this page patch")
        }
        val tabId = boundTabId() ?: return ToolResult(false, noTab())
        val session = control.tabSession(tabId) ?: return ToolResult(false, noEngine(tabId))
        val raw = evaluate(session, script)
            ?: return ToolResult(false, "the patch did not run — the engine reported an error or the page is mid-load")
        // The script's own return value is page-shaped, so it is fenced and
        // masked like any other page text; the sentence around it is ours.
        val result = UntrustedContent.wrap(SecretRedaction.redact(clip(unquote(raw))), "the page")
        return ToolResult(
            true,
            "Applied the patch to the live page. Result:\n$result\n" +
                "The change is in this page's own session only — it is not saved to the site and is gone on the next reload."
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun devtoolsReload(): ToolResult {
        val tabId = boundTabId() ?: return ToolResult(false, noTab())
        if (keepConsole[tabId] == false) control.inspectorFor(tabId)?.clearConsole()
        control.reload(tabId)
        return ToolResult(
            true,
            "Reloading this chat's tab to watch a fresh load. Call wait or devtools_console read in a moment."
        )
    }

    private suspend fun buffers(tabId: String): Pair<List<ConsoleLine>, List<NetworkRow>> {
        val inspector = control.inspectorFor(tabId)
        val console = inspector?.console?.snapshot().orEmpty().map { ConsoleLine(it.level, it.text) }
        val network = inspector?.network?.snapshot().orEmpty().map {
            NetworkRow(url = it.url, status = it.status ?: 0, method = it.method ?: "GET")
        }
        return console to network
    }

    private suspend fun domEvidence(tabId: String): List<DomEvidence> {
        val session = control.tabSession(tabId) ?: return emptyList()
        val raw = evaluate(session, PageInjector.snapshotJs()) ?: return emptyList()
        val snapshot = runCatching { AgentJson.decodeFromString(PageSnapshotDto.serializer(), raw) }
            .getOrNull() ?: return emptyList()
        return snapshot.elements.map { DomEvidence(it.ref, it.tag, it.label, it.href) }
    }

    private fun renderReport(header: String, findings: List<DiagnosisFinding>): String = buildString {
        appendLine(header)
        DiagnosisLabel.entries.forEach { label ->
            val group = findings.filter { it.label == label }
            if (group.isEmpty()) return@forEach
            appendLine()
            appendLine(labelText(label))
            group.forEach { f ->
                appendLine("  - ${f.title}")
                f.evidence.forEach { appendLine("      ${it}") }
                f.relatedElementRef?.let { appendLine("      element [$it]") }
            }
        }
    }

    private fun labelText(label: DiagnosisLabel): String = when (label) {
        DiagnosisLabel.OBSERVED_FACT -> "OBSERVED FACT"
        DiagnosisLabel.HYPOTHESIS -> "HYPOTHESIS"
        DiagnosisLabel.RECOMMENDATION -> "RECOMMENDATION"
    }

    private fun capabilityAbsent(what: String, note: String?): String =
        "This engine cannot serve $what, so the tool has nothing to read. " +
            (note ?: "The Developer Tools surface hides this panel for the same reason.")

    private fun noTab(): String =
        "this chat has no tab to work on — open a tab and send a new message"

    private fun noEngine(tabId: String): String =
        "this chat's tab has no live engine session yet, so there is nothing to inspect"

    private suspend fun evaluate(session: EngineSession, script: String): String? =
        suspendCancellableCoroutine { continuation ->
            try {
                session.evaluateJs(script) { value ->
                    if (continuation.isActive) continuation.resume(value)
                }
            } catch (t: Throwable) {
                if (continuation.isActive) continuation.resume(null)
            }
        }

    private fun unquote(jsResult: String): String = runCatching {
        if (jsResult.length >= 2 && jsResult.startsWith("\"") && jsResult.endsWith("\"")) {
            AgentJson.decodeFromString(String.serializer(), jsResult)
        } else {
            jsResult
        }
    }.getOrDefault(jsResult)

    private fun clip(text: String): String =
        if (text.length <= MAX_RESULT_CHARS) text else text.take(MAX_RESULT_CHARS) + "…"

    private fun parseArgs(argsJson: String): JsonObject =
        runCatching { AgentJson.parseToJsonElement(argsJson) as? JsonObject }
            .getOrNull() ?: JsonObject(emptyMap())

    private fun strArg(args: JsonObject, key: String): String? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun intArg(args: JsonObject, key: String): Int? =
        (args[key] as? JsonPrimitive)?.intOrNull

    private fun boolArg(args: JsonObject, key: String): Boolean? =
        (args[key] as? JsonPrimitive)?.booleanOrNull

    private companion object {
        const val MAX_CONSOLE_ROWS = 200
        const val MAX_NETWORK_ROWS = 200
        const val MAX_RESULT_CHARS = 6000

        /** The Elements probe: tag, attributes, computed box and the surrounding text. */
        fun inspectJs(jsonSelector: String): String = """
            (function(){
              var sel = $jsonSelector;
              var el = null;
              try { el = document.querySelector(sel); } catch (e) { el = null; }
              if (!el) return JSON.stringify({ found: false, selector: sel });
              var r = el.getBoundingClientRect();
              var attrs = {};
              for (var i = 0; i < el.attributes.length && i < 40; i++) {
                var a = el.attributes[i];
                attrs[a.name] = String(a.value).slice(0, 300);
              }
              var out = {
                found: true,
                tag: el.tagName.toLowerCase(),
                text: (el.innerText || '').slice(0, 400).replace(/\s+/g, ' '),
                box: { x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height) },
                attrs: attrs,
                parent: el.parentElement ? (el.parentElement.tagName.toLowerCase() + (el.parentElement.id ? '#' + el.parentElement.id : '')) : null
              };
              try {
                var cs = window.getComputedStyle(el);
                out.display = cs.display; out.visibility = cs.visibility; out.opacity = cs.opacity;
              } catch (e) {}
              return JSON.stringify(out);
            })()
        """.trimIndent()
    }
}
