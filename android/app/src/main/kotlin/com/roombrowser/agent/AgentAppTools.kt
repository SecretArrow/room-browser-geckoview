package com.roombrowser.agent

import android.os.SystemClock
import com.roombrowser.browser.BrowserViewModel
import com.roombrowser.data.db.DownloadEntity
import com.roombrowser.data.db.HistoryEntity
import com.roombrowser.data.db.TabEntity
import com.roombrowser.data.repo.DownloadStatus
import com.roombrowser.data.repo.PermissionKind
import com.roombrowser.domain.agent.ActionVerdict
import com.roombrowser.domain.agent.AgentAppActions
import com.roombrowser.domain.agent.AgentJson
import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.domain.agent.ToolResult
import com.roombrowser.domain.engine.UrlIntelligence
import com.roombrowser.domain.model.PermissionDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The agent's app-control tools: the browser's own screens, tabs, saved data,
 * settings, shields and per-site permissions — the [AgentAppActions]
 * vocabulary given effect against [BrowserViewModel].
 *
 * TWO GATES, and the difference between them matters. An action that destroys
 * something the user cannot get back goes through [destructiveGate], which
 * asks a person EVERY time: nothing in settings switches it off, and YOLO —
 * which is a statement about not wanting to be asked — does not reach it. Every
 * other change goes through [confirmGate], the ordinary chain behind the
 * Confirm actions switch ([AgentAppActions.isWrite] keeps pure reads out of
 * both). A refused or unanswered prompt denies; it never proceeds.
 *
 * Everything here reaches the app through the ViewModel's public surface,
 * exactly as the screens do, so an app action cannot do anything the user's own
 * screens cannot. It also means these tools act on the tab on screen for
 * anything page-shaped (the find bar, shields, reader mode): the ViewModel's
 * page state describes the visible tab, and the executor holds those tools
 * until this chat's tab is the visible one.
 */
class AgentAppTools(
    private val vm: BrowserViewModel,
    /** The tab this chat is bound to, for "this chat's tab" defaults. */
    private val boundTabId: () -> String?,
    private val onStatus: (String) -> Unit = {},
    private val confirmGate: suspend (name: String, label: String) -> ActionVerdict,
    private val destructiveGate: suspend (name: String, label: String) -> ActionVerdict,
    /**
     * The profile's own notes and authenticator accounts. Null means this turn
     * cannot reach them, and `app_2fa`/`app_notes` then refuse rather than
     * reaching a store that is not wired.
     */
    private val profileData: AgentProfileData? = null,
    /** Whether the digits of a generated code may be returned to the model. */
    private val otpDigitsAllowed: suspend () -> Boolean = { false },
    /** Types text into a page field, for `app_2fa action=fill`. */
    private val fillField: (suspend (ref: Int, text: String) -> ToolResult)? = null
) {

    private val profileTools: AgentProfileTools? = profileData?.let {
        AgentProfileTools(data = it, otpDigitsAllowed = otpDigitsAllowed, fillField = fillField)
    }

    /** Null when [name] is not an app tool — the caller's other tools run. */
    suspend fun execute(name: String, argsJson: String): ToolResult? {
        if (name !in AgentAppActions.TOOLS) return null
        val args = parseArgs(argsJson)
        return try {
            val action = strArg(args, "action")
            guard(name, action, AgentTools.describeTool(name, argsJson))?.let { return it }
            dispatch(name, args, argsJson)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            ToolResult(false, t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * The gate for one action, or null to run. Destruction is checked first and
     * on its own: it must be impossible to reach a destructive action's body
     * through a path that skipped it.
     */
    private suspend fun guard(name: String, action: String?, label: String): ToolResult? {
        if (AgentAppActions.isDestructive(name, action)) {
            return when (val verdict = destructiveGate(name, label)) {
                is ActionVerdict.Allow -> null
                is ActionVerdict.Deny -> ToolResult(false, verdict.reason)
                is ActionVerdict.Ask -> ToolResult(false, "the user did not confirm this action")
            }
        }
        if (!AgentAppActions.isWrite(name, action)) return null
        return when (val verdict = confirmGate(name, label)) {
            is ActionVerdict.Allow -> null
            is ActionVerdict.Deny -> ToolResult(false, verdict.reason)
            is ActionVerdict.Ask -> ToolResult(false, "the user did not confirm this action")
        }
    }

    private suspend fun dispatch(name: String, args: JsonObject, argsJson: String): ToolResult = when (name) {
        AgentTools.APP_OPEN -> appOpen(strArg(args, "screen"))
        AgentTools.APP_TABS -> appTabs(strArg(args, "action") ?: "list", args)
        AgentTools.APP_DATA -> appData(strArg(args, "kind"), strArg(args, "action") ?: "list", args)
        AgentTools.APP_SETTINGS -> appSettings(strArg(args, "action"), args)
        AgentTools.APP_SHIELDS -> appShields(strArg(args, "action"), args)
        AgentTools.APP_SITE_PERMISSION -> appSitePermission(strArg(args, "action"), args)
        AgentTools.APP_2FA, AgentTools.APP_NOTES -> profileTools
            ?.execute(name, argsJson)
            ?: ToolResult(false, profileToolsUnavailable(name))
        else -> appPage(strArg(args, "action"), args)
    }

    private fun profileToolsUnavailable(name: String): String =
        "tool '$name' reaches this profile's own notes and authenticator accounts, and this turn " +
            "has no connection to them. Run it in the browser's own agent chat."

    // ------------------------------------------------------------- screens

    private fun appOpen(screen: String?): ToolResult {
        if (screen == null) return ToolResult(false, "missing 'screen' argument")
        if (screen !in AgentAppActions.SCREENS) {
            return ToolResult(
                false,
                "there is no '$screen' screen. The screens are: " +
                    AgentAppActions.SCREENS.sorted().joinToString(", ")
            )
        }
        vm.openScreen(screen)?.let { return ToolResult(false, it) }
        return ToolResult(true, "Opened the '$screen' screen. This chat's tab keeps its page.")
    }

    // ---------------------------------------------------------------- tabs

    private suspend fun appTabs(action: String, args: JsonObject): ToolResult {
        if (action !in AgentAppActions.TAB_ACTIONS) {
            return ToolResult(
                false,
                "unknown tabs action '$action'. The actions are: " +
                    AgentAppActions.TAB_ACTIONS.sorted().joinToString(", ")
            )
        }
        if (action == "list") return ToolResult(true, describeTabs())

        val tab = when (action) {
            // These are about the set of tabs, not one of them.
            "reopen", "close_others", "private" -> null
            else -> resolveTab(intArg(args, "index"))
        }
        if (tab == null && action !in setOf("reopen", "close_others", "private")) {
            return ToolResult(false, indexRefusal(intArg(args, "index")))
        }

        return when (action) {
            "reopen" -> {
                vm.reopenClosedTab()
                ToolResult(true, "Reopened the most recently closed tab.")
            }
            "private" -> {
                vm.startPrivateTab()
                ToolResult(true, "Opened a private tab.")
            }
            "close_others" -> {
                // This spares the tab ON SCREEN, so make this chat's tab the
                // one on screen first — otherwise it would keep whichever tab
                // the user happens to be looking at and close this chat's.
                val spare = resolveTab(null)
                if (spare != null) vm.selectTab(spare.id)
                val closed = vm.tabs.size - 1
                vm.closeOtherTabs()
                if (spare == null) {
                    ToolResult(true, "Closed the other $closed tab(s).")
                } else {
                    ToolResult(true, "Closed the other $closed tab(s), keeping \"${tabLabel(spare)}\".")
                }
            }
            "pin", "unpin" -> {
                val wanted = action == "pin"
                val target = requireNotNull(tab)
                if (target.isPinned == wanted) {
                    return ToolResult(true, "That tab is already ${if (wanted) "pinned" else "not pinned"}.")
                }
                vm.pinTab(target.id)
                ToolResult(true, "${if (wanted) "Pinned" else "Unpinned"} \"${tabLabel(target)}\".")
            }
            "duplicate" -> {
                val target = requireNotNull(tab)
                vm.selectTab(target.id)
                vm.duplicateTab()
                ToolResult(true, "Duplicated \"${tabLabel(target)}\"; the copy is now the open tab.")
            }
            "move" -> {
                val target = requireNotNull(tab)
                val position = intArg(args, "position")
                    ?: return ToolResult(false, "action=move needs 'position' — the new place in the tab list")
                if (position !in 0 until vm.tabs.size) {
                    return ToolResult(false, "position $position is outside the ${vm.tabs.size} open tab(s)")
                }
                vm.moveTab(target.id, position)
                ToolResult(true, "Moved \"${tabLabel(target)}\" to position $position.")
            }
            "group" -> {
                val target = requireNotNull(tab)
                val name = strArg(args, "name")
                    ?: return ToolResult(false, "action=group needs 'name' — the group to put the tab in")
                vm.groupTab(target.id, name)
                ToolResult(true, "Put \"${tabLabel(target)}\" in the group \"$name\".")
            }
            else -> {
                val target = requireNotNull(tab)
                vm.groupTab(target.id, null)
                ToolResult(true, "Took \"${tabLabel(target)}\" out of its group.")
            }
        }
    }

    private fun orderedTabs(): List<TabEntity> = vm.tabs.sortedBy { it.position }

    /**
     * The tab an action works on: the one at [index] in [describeTabs], or —
     * with no index — this chat's tab, falling back to the tab on screen.
     */
    private fun resolveTab(index: Int?): TabEntity? = if (index == null) {
        orderedTabs().firstOrNull { it.id == boundTabId() }
            ?: orderedTabs().firstOrNull { it.id == vm.activeTabId }
    } else {
        orderedTabs().getOrNull(index)
    }

    private fun indexRefusal(index: Int?): String = if (index == null) {
        "there is no tab for this action to work on"
    } else {
        "tab index $index does not exist — there are ${vm.tabs.size} open tab(s); call app_tabs list first"
    }

    private fun tabLabel(tab: TabEntity): String = tab.title.ifBlank { tab.url }.take(60)

    private fun describeTabs(): String {
        val tabs = orderedTabs()
        if (tabs.isEmpty()) return "No tabs are open."
        val bound = boundTabId()
        return tabs.mapIndexed { index, tab ->
            buildString {
                append(index).append(": ")
                if (tab.isPinned) append("[pinned] ")
                if (tab.isPrivate) append("[private] ")
                append('"').append(tabLabel(tab)).append('"')
                tab.groupName?.let { append(" group=").append(it) }
                append(" — ").append(tab.url.take(80))
                if (tab.id == bound) append("  (this chat's tab)")
                if (tab.id == vm.activeTabId) append("  (on screen)")
            }
        }.joinToString("\n")
    }

    // ------------------------------------------------------- saved data

    private suspend fun appData(kind: String?, action: String, args: JsonObject): ToolResult {
        if (kind == null || kind !in AgentAppActions.DATA_KINDS) {
            return ToolResult(
                false,
                "'kind' must be one of: " + AgentAppActions.DATA_KINDS.sorted().joinToString(", ")
            )
        }
        val allowed = AgentAppActions.DATA_ACTIONS.getValue(kind)
        if (action !in allowed) {
            return ToolResult(
                false,
                "'$action' is not an action on $kind. $kind takes: " + allowed.sorted().joinToString(", ")
            )
        }
        return when (kind) {
            "bookmarks" -> when (action) {
                "list" -> ToolResult(true, listBookmarks())
                "add" -> addBookmark(args)
                else -> removeBookmark(intArg(args, "id"))
            }
            "history" -> when (action) {
                "list" -> ToolResult(true, listHistory(vm.recentHistory.take(HISTORY_LIMIT)))
                "search" -> searchHistory(strArg(args, "query"))
                "remove" -> removeHistory(intArg(args, "id"))
                else -> {
                    val count = vm.recentHistory.size
                    vm.clearHistory(0L)
                    ToolResult(true, "Cleared the whole browsing history for this profile ($count entries).")
                }
            }
            else -> downloadAction(action, args)
        }
    }

    private fun listBookmarks(): String {
        val rows = vm.bookmarks.take(LIST_LIMIT)
        if (rows.isEmpty()) return "No bookmarks are saved in this profile."
        return "Bookmarks (id  title — url):\n" + rows.joinToString("\n") {
            "${it.id}  ${it.title.ifBlank { it.url }.take(60)} — ${it.url.take(80)}"
        }
    }

    private suspend fun addBookmark(args: JsonObject): ToolResult {
        val url = strArg(args, "url") ?: return ToolResult(false, "action=add needs 'url'")
        val id = vm.addBookmarkFor(url, strArg(args, "title").orEmpty())
        return if (id == null) {
            ToolResult(true, "$url is already bookmarked.")
        } else {
            ToolResult(true, "Bookmarked $url (id $id).")
        }
    }

    private suspend fun removeBookmark(id: Int?): ToolResult {
        val rowId = id?.toLong() ?: return ToolResult(false, "action=remove needs 'id' from app_data list")
        val row = vm.bookmarks.firstOrNull { it.id == rowId }
            ?: return ToolResult(false, "no bookmark with id $rowId — call app_data list first")
        vm.deleteBookmark(rowId)
        return ToolResult(true, "Removed the bookmark \"${row.title.ifBlank { row.url }}\".")
    }

    private fun listHistory(rows: List<HistoryEntity>): String {
        if (rows.isEmpty()) return "No history entries in this profile."
        return "History, newest first (id  title — url):\n" + rows.joinToString("\n") {
            "${it.id}  ${it.title.ifBlank { it.url }.take(60)} — ${it.url.take(80)}"
        }
    }

    private fun searchHistory(query: String?): ToolResult {
        val text = query?.takeIf { it.isNotBlank() }
            ?: return ToolResult(false, "action=search needs 'query'")
        val hits = vm.recentHistory.filter {
            it.url.contains(text, ignoreCase = true) || it.title.contains(text, ignoreCase = true)
        }
        if (hits.isEmpty()) return ToolResult(true, "Nothing in this profile's history matches \"$text\".")
        return ToolResult(
            true,
            "${hits.size} history entr(y/ies) matching \"$text\":\n" + listHistory(hits.take(LIST_LIMIT))
        )
    }

    private suspend fun removeHistory(id: Int?): ToolResult {
        val rowId = id?.toLong() ?: return ToolResult(false, "action=remove needs 'id' from app_data list")
        val row = vm.recentHistory.firstOrNull { it.id == rowId }
            ?: return ToolResult(false, "no history entry with id $rowId — call app_data list first")
        vm.deleteHistoryItem(rowId)
        return ToolResult(true, "Removed \"${row.title.ifBlank { row.url }}\" from the history.")
    }

    private fun downloadAction(action: String, args: JsonObject): ToolResult {
        if (action == "list") {
            val rows = vm.downloads.take(LIST_LIMIT)
            if (rows.isEmpty()) return ToolResult(true, "No downloads in this profile.")
            return ToolResult(
                true,
                "Downloads (id  name  status):\n" + rows.joinToString("\n") { d ->
                    val percent = if (d.totalBytes > 0) {
                        (d.downloadedBytes * 100 / d.totalBytes).toString() + "%"
                    } else {
                        "?"
                    }
                    "${d.id}  ${d.fileName.take(60)}  ${downloadStatus(d)} $percent"
                }
            )
        }
        val id = intArg(args, "id")?.toLong()
            ?: return ToolResult(false, "action=$action needs 'id' from app_data list")
        val download = vm.downloads.firstOrNull { it.id == id }
            ?: return ToolResult(false, "no download with id $id — call app_data list first")
        val status = runCatching { DownloadStatus.valueOf(download.status) }.getOrNull()
        return when (action) {
            "pause" -> {
                if (status != DownloadStatus.RUNNING && status != DownloadStatus.QUEUED) {
                    return ToolResult(false, "\"${download.fileName}\" is ${downloadStatus(download)}, not running — nothing to pause")
                }
                vm.pauseDownload(id)
                ToolResult(true, "Paused \"${download.fileName}\".")
            }
            "resume" -> {
                if (status != DownloadStatus.PAUSED) {
                    return ToolResult(false, "\"${download.fileName}\" is ${downloadStatus(download)}, not paused — nothing to resume")
                }
                vm.resumeDownload(id)
                ToolResult(true, "Resumed \"${download.fileName}\".")
            }
            "cancel" -> {
                if (status == DownloadStatus.COMPLETED || status == DownloadStatus.CANCELLED) {
                    return ToolResult(false, "\"${download.fileName}\" is already ${downloadStatus(download)}")
                }
                vm.cancelDownload(id)
                ToolResult(true, "Cancelled \"${download.fileName}\".")
            }
            "retry" -> {
                if (status != DownloadStatus.FAILED && status != DownloadStatus.CANCELLED) {
                    return ToolResult(false, "\"${download.fileName}\" is ${downloadStatus(download)} — retry is for a failed or cancelled download")
                }
                vm.retryDownload(id)
                ToolResult(true, "Retrying \"${download.fileName}\".")
            }
            "open" -> {
                if (status != DownloadStatus.COMPLETED) {
                    return ToolResult(false, "\"${download.fileName}\" is ${downloadStatus(download)} — only a finished download can be opened")
                }
                vm.openDownload(id)
                ToolResult(true, "Opening \"${download.fileName}\" in whatever app handles it.")
            }
            else -> {
                vm.deleteDownload(id)
                ToolResult(true, "Removed \"${download.fileName}\" from the download list.")
            }
        }
    }

    private fun downloadStatus(download: DownloadEntity): String =
        runCatching { DownloadStatus.valueOf(download.status).name.lowercase() }.getOrDefault(download.status)

    // ----------------------------------------------------------- settings

    private suspend fun appSettings(action: String?, args: JsonObject): ToolResult = when (action) {
        "get" -> {
            val key = strArg(args, "key")
            if (key == null) {
                ToolResult(true, "This profile's writable settings:\n" + AgentAppActions.describeSettings(vm.profileSettings()))
            } else {
                AgentAppActions.readSetting(vm.profileSettings(), key)
                    ?.let { ToolResult(true, "$key = $it") }
                    ?: ToolResult(false, unknownSetting(key))
            }
        }
        "set" -> {
            val key = strArg(args, "key") ?: return ToolResult(false, "action=set needs 'key'")
            val value = strArg(args, "value") ?: return ToolResult(false, "action=set needs 'value'")
            when (val write = AgentAppActions.applySetting(vm.profileSettings(), key, value)) {
                is AgentAppActions.SettingWrite.Refused -> ToolResult(false, write.reason)
                is AgentAppActions.SettingWrite.Applied -> {
                    vm.updateSettings(write.settings)
                    val now = AgentAppActions.readSetting(write.settings, key) ?: value
                    ToolResult(true, "$key is now $now for this profile.")
                }
            }
        }
        else -> ToolResult(false, "action must be get or set, not '${action ?: ""}'")
    }

    private fun unknownSetting(key: String): String =
        "'$key' is not a setting the agent may change. It can read and write: " +
            AgentAppActions.PROFILE_SETTINGS.keys.sorted().joinToString(", ") +
            ". Everything else (theme, tab layout, the device, the UA mode) lives in the settings screen."

    // ------------------------------------------------------------ shields

    private suspend fun appShields(action: String?, args: JsonObject): ToolResult {
        if (action == null || action !in AgentAppActions.SHIELD_ACTIONS) {
            return ToolResult(
                false,
                "action must be one of: " + AgentAppActions.SHIELD_ACTIONS.sorted().joinToString(", ")
            )
        }
        val host = currentHost() ?: return ToolResult(false, noSite)
        return when (action) {
            "read" -> {
                val shields = vm.shieldsState
                ToolResult(
                    true,
                    "Shields for $host: tracking protection is " +
                        "${if (shields.shieldsDisabled) "OFF" else "on"} for this site. " +
                        "Blocked so far: ${shields.adsBlocked} ads, ${shields.trackersBlocked} trackers, " +
                        "${shields.httpsUpgrades} insecure requests upgraded."
                )
            }
            "toggle" -> {
                val enabled = boolArg(args, "enabled")
                    ?: return ToolResult(false, "action=toggle needs 'enabled': true keeps protection on, false switches it off for this site")
                vm.toggleShieldsForSite(disabled = !enabled)
                ToolResult(true, "Tracking protection is now ${if (enabled) "on" else "off"} for $host.")
            }
            else -> {
                vm.clearSiteDataForCurrentSite()
                ToolResult(
                    true,
                    "Cleared the stored site data for $host. Honest scope: the engine keeps ONE data " +
                        "directory per profile, not one per host, so this cleared the profile's stored " +
                        "site data as a whole — sign-ins on other sites in this profile are gone too."
                )
            }
        }
    }

    // -------------------------------------------------- site permissions

    private suspend fun appSitePermission(action: String?, args: JsonObject): ToolResult {
        if (action == null || action !in AgentAppActions.PERMISSION_ACTIONS) {
            return ToolResult(
                false,
                "action must be one of: " + AgentAppActions.PERMISSION_ACTIONS.sorted().joinToString(", ")
            )
        }
        val host = currentHost() ?: return ToolResult(false, noSite)
        return when (action) {
            "list" -> {
                val saved = vm.sitePermissionsForCurrentHost()
                    .associate { it.permission to it.decision.lowercase() }
                val lines = AgentAppActions.PERMISSIONS.sorted().joinToString("\n") { name ->
                    "$name = " + (saved[name.uppercase()] ?: "not decided (the site is asked each time)")
                }
                ToolResult(true, "Saved permissions for $host:\n$lines")
            }
            else -> {
                val name = strArg(args, "permission")?.lowercase()
                    ?: return ToolResult(false, "action=set needs 'permission'")
                if (name !in AgentAppActions.PERMISSIONS) {
                    return ToolResult(
                        false,
                        "'$name' is not a permission this browser saves. It saves: " +
                            AgentAppActions.PERMISSIONS.sorted().joinToString(", ")
                    )
                }
                val decision = strArg(args, "decision")?.lowercase()
                    ?: return ToolResult(false, "action=set needs 'decision'")
                if (decision !in AgentAppActions.PERMISSION_DECISIONS) {
                    return ToolResult(
                        false,
                        "'$decision' is not a decision. Use one of: " +
                            AgentAppActions.PERMISSION_DECISIONS.sorted().joinToString(", ")
                    )
                }
                vm.setPermission(
                    PermissionKind.valueOf(name.uppercase()),
                    PermissionDecision.valueOf(decision.uppercase())
                )
                ToolResult(true, "$name is now '$decision' for $host.")
            }
        }
    }

    private fun currentHost(): String? =
        vm.shieldsState.host.ifBlank { UrlIntelligence.hostOf(vm.pageState.url).orEmpty() }
            .takeIf { it.isNotBlank() }

    // ---------------------------------------------------------- the page

    private suspend fun appPage(action: String?, args: JsonObject): ToolResult {
        if (action == null || action !in AgentAppActions.PAGE_ACTIONS) {
            return ToolResult(
                false,
                "action must be one of: " + AgentAppActions.PAGE_ACTIONS.sorted().joinToString(", ")
            )
        }
        return when (action) {
            "find", "find_next", "find_previous" -> {
                val query = strArg(args, "query")
                    ?: return ToolResult(false, "the find actions need 'query' — including find_next and find_previous, which repeat it because the browser keeps no last query of its own")
                when (action) {
                    "find" -> vm.findInPage(query)
                    "find_next" -> vm.findInPageNavigate(true, query)
                    else -> vm.findInPageNavigate(false, query)
                }
                ToolResult(true, "Highlighting matches for \"$query\" in the page.")
            }
            "clear_find" -> {
                vm.clearFindInPage()
                ToolResult(true, "Cleared the find highlights.")
            }
            "reader_on" -> readerOn()
            "reader_off" -> {
                vm.exitReaderMode()
                ToolResult(true, "Reader mode closed; the page is shown as the site wrote it.")
            }
            "desktop_on", "desktop_off" -> {
                val wanted = action == "desktop_on"
                if (vm.pageState.desktopMode == wanted) {
                    return ToolResult(true, "Desktop mode is already ${if (wanted) "on" else "off"}.")
                }
                vm.toggleDesktopMode()
                ToolResult(true, "Desktop mode is now ${if (wanted) "on" else "off"}; the page was reloaded.")
            }
            else -> {
                val wanted = action == "bookmark_add"
                // Reported as the state the page is IN afterwards, not as the
                // change that was asked for: asking to add one that was
                // already there is not an add, and saying it was would be a
                // claim the model builds on.
                val bookmarked = vm.setBookmarkForCurrentPage(wanted)
                    ?: return ToolResult(false, "the start page cannot be bookmarked — open a real page first")
                return ToolResult(
                    true,
                    if (bookmarked) "This page is bookmarked." else "This page is not bookmarked."
                )
            }
        }
    }

    /**
     * Reader mode simplifies the page in the WebView and calls back, so the
     * answer is awaited rather than assumed: reporting "reader mode on" for a
     * page that could not be simplified is a claim the model will build on.
     */
    private suspend fun readerOn(): ToolResult {
        if (vm.readerContent != null) return ToolResult(true, "Reader mode is already showing.")
        onStatus("Simplifying the page…")
        vm.enterReaderMode()
        val deadline = SystemClock.elapsedRealtime() + READER_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            vm.readerContent?.let {
                return ToolResult(true, "Reader mode is showing \"${it.title}\".")
            }
            delay(POLL_MS)
        }
        return ToolResult(
            false,
            "this page could not be simplified into reader mode — it may be a feed, a video page, " +
                "or mostly interactive. It is unchanged; read_page still works."
        )
    }

    // ------------------------------------------------------------- helpers

    private fun parseArgs(argsJson: String): JsonObject =
        runCatching { AgentJson.parseToJsonElement(argsJson) as? JsonObject }
            .getOrNull() ?: JsonObject(emptyMap())

    private fun prim(args: JsonObject, key: String): JsonPrimitive? = args[key] as? JsonPrimitive

    private fun strArg(args: JsonObject, key: String): String? =
        prim(args, key)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun intArg(args: JsonObject, key: String): Int? = prim(args, key)?.intOrNull

    private fun boolArg(args: JsonObject, key: String): Boolean? = prim(args, key)?.booleanOrNull

    private companion object {
        const val LIST_LIMIT = 100
        const val HISTORY_LIMIT = 50
        const val READER_TIMEOUT_MS = 4_000L
        const val POLL_MS = 100L
        const val noSite =
            "no site is open in this chat's tab — navigate to one first, or this needs the " +
                "tab on screen (this chat's own tab)."
    }
}
