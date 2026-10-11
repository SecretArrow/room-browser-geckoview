package com.roombrowser.domain.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The browsing agent's tool catalogue — the actions it can take on the
 * live WebView. Element interaction uses numbered references ([ref]) that
 * the page snapshot assigns to every visible interactive element.
 */
object AgentTools {

    const val NAVIGATE = "navigate"
    const val SEARCH_WEB = "search_web"
    const val READ_PAGE = "read_page"
    const val CLICK = "click"
    const val FILL_INPUT = "fill_input"
    const val PRESS_ENTER = "press_enter"
    const val SCROLL = "scroll"
    const val GO_BACK = "go_back"
    const val OPEN_NEW_TAB = "open_new_tab"
    const val LIST_TABS = "list_tabs"
    const val SWITCH_TAB = "switch_tab"
    const val CLOSE_TAB = "close_tab"

    // ---- social automation (auto reply / like / repost / post) ----
    const val AUTO_LIKE = "auto_like"
    const val AUTO_REPOST = "auto_repost"
    const val AUTO_REPLY = "auto_reply"
    const val AUTO_POST = "auto_post"
    const val WAIT = "wait"

    // ---- wallet (dApp request queue + active network) ----
    const val WALLET_STATE = "wallet_state"
    const val WALLET_REQUESTS = "wallet_requests"
    const val WALLET_APPROVE = "wallet_approve"
    const val WALLET_REJECT = "wallet_reject"
    const val WALLET_SWITCH_NETWORK = "wallet_switch_network"

    // ---- direct page control ----
    const val RUN_JS = "run_js"
    const val SELECT_OPTION = "select_option"
    const val PRESS_KEYS = "press_keys"
    const val WAIT_FOR = "wait_for"

    // ---- the app's own components ----
    const val APP_OPEN = "app_open"
    const val APP_TABS = "app_tabs"
    const val APP_DATA = "app_data"
    const val APP_SETTINGS = "app_settings"
    const val APP_SHIELDS = "app_shields"
    const val APP_SITE_PERMISSION = "app_site_permission"
    const val APP_PAGE = "app_page"

    // ---- the profile's own secrets and notes ----
    const val APP_2FA = "app_2fa"
    const val APP_NOTES = "app_notes"

    // ---- page history controls the catalogue was missing ----
    const val RELOAD = "reload"
    const val FORWARD = "forward"

    // ---- Developer Tools (the agent reading and driving the real buffers) ----
    const val DEVTOOLS_RELOAD = "devtools_reload"
    const val DEVTOOLS_CONSOLE = "devtools_console"
    const val DEVTOOLS_NETWORK = "devtools_network"
    const val DEVTOOLS_INSPECT = "devtools_inspect"
    const val DEVTOOLS_AUDIT = "devtools_audit"
    const val DEVTOOLS_DIAGNOSE = "devtools_diagnose"
    const val DEVTOOLS_REPORT = "devtools_report"
    const val DEVTOOLS_PATCH = "devtools_patch"

    private const val OBJ = """{"type":"object"}"""

    private val SCHEMA_NAVIGATE = """{"type":"object","properties":{"url":{"type":"string","description":"Full URL, e.g. https://example.com/path"}},"required":["url"]}"""
    private val SCHEMA_SEARCH = """{"type":"object","properties":{"query":{"type":"string","description":"Search query text"}},"required":["query"]}"""
    private val SCHEMA_NO_PARAMS = OBJ
    private val SCHEMA_REF = """{"type":"object","properties":{"ref":{"type":"integer","description":"Element reference number from read_page"}},"required":["ref"]}"""
    private val SCHEMA_FILL = """{"type":"object","properties":{"ref":{"type":"integer","description":"Input element reference number from read_page"},"text":{"type":"string","description":"Text to type"}},"required":["ref","text"]}"""
    private val SCHEMA_ENTER = """{"type":"object","properties":{"ref":{"type":"integer","description":"Optional element reference to focus before pressing Enter"}}}"""
    private val SCHEMA_SCROLL = """{"type":"object","properties":{"direction":{"type":"string","enum":["up","down"]},"amount":{"type":"integer","description":"Optional percentage of viewport height, default 80"}},"required":["direction"]}"""
    private val SCHEMA_NEW_TAB = """{"type":"object","properties":{"url":{"type":"string","description":"Optional URL to open, defaults to the start page"}}}"""
    private val SCHEMA_TAB_INDEX = """{"type":"object","properties":{"index":{"type":"integer","description":"Tab index from list_tabs"}},"required":["index"]}"""
    private val SCHEMA_TEXT = """{"type":"object","properties":{"text":{"type":"string","description":"Text to send"}},"required":["text"]}"""
    private val SCHEMA_WAIT = """{"type":"object","properties":{"ms":{"type":"integer","description":"Milliseconds to wait, 200-20000, default 1500"}}}"""
    private val SCHEMA_REQUEST_ID = """{"type":"object","properties":{"request_id":{"type":"string","description":"Request id from wallet_requests"}},"required":["request_id"]}"""
    private val SCHEMA_NETWORK_ID = """{"type":"object","properties":{"network_id":{"type":"string","description":"Network id from wallet_state, e.g. EVM:137. The wallet must already know it."}},"required":["network_id"]}"""
    private val SCHEMA_RUN_JS = """{"type":"object","properties":{"script":{"type":"string","description":"JavaScript to run in the page. Synchronous only: the value of the last expression comes back as text, and a returned Promise is not awaited."}},"required":["script"]}"""
    private val SCHEMA_SELECT_OPTION = """{"type":"object","properties":{"ref":{"type":"integer","description":"<select> element reference number from read_page"},"value":{"type":"string","description":"The option's value, or its visible text"}},"required":["ref","value"]}"""
    private val SCHEMA_PRESS_KEYS = """{"type":"object","properties":{"keys":{"type":"string","description":"Key chord: Enter, Escape, Tab, ArrowDown, PageDown, F1-F12, or a combination like Control+a"},"ref":{"type":"integer","description":"Optional element reference to focus first; defaults to whatever is focused"}},"required":["keys"]}"""
    private val SCHEMA_WAIT_FOR = """{"type":"object","properties":{"text":{"type":"string","description":"Text to wait for in the visible page"},"timeout_ms":{"type":"integer","description":"How long to wait, 500-30000, default 10000"}},"required":["text"]}"""

    private val SCHEMA_DEVTOOLS_AUDIT = OBJ
    private val SCHEMA_DEVTOOLS_CONSOLE = """{"type":"object","properties":{"action":{"type":"string","enum":["read","clear","preserve"],"description":"read lists the buffered entries; clear empties the buffer; preserve sets whether the buffer keeps its entries across navigations (needs 'enabled')"},"enabled":{"type":"boolean","description":"For action=preserve: true to keep entries across navigations, false to drop them on each load"},"level":{"type":"string","description":"read filter: only entries at this level, e.g. error, warning, log"},"contains":{"type":"string","description":"read filter: only entries whose text contains this"}}}"""
    private val SCHEMA_DEVTOOLS_NETWORK = """{"type":"object","properties":{"action":{"type":"string","enum":["read","clear"],"description":"read lists the buffered request rows; clear empties the buffer"},"failed_only":{"type":"boolean","description":"read filter: only rows that failed (error status or no status at all)"},"contains":{"type":"string","description":"read filter: only rows whose URL contains this"}}}"""
    private val SCHEMA_DEVTOOLS_INSPECT = """{"type":"object","properties":{"ref":{"type":"integer","description":"Element reference number from read_page"},"selector":{"type":"string","description":"CSS selector, for an element the snapshot did not number"}}}"""
    private val SCHEMA_DEVTOOLS_DIAGNOSE = """{"type":"object","properties":{"focus":{"type":"string","description":"Optional text to correlate on, e.g. a button label or an error the user saw"}}}"""
    private val SCHEMA_DEVTOOLS_PATCH = """{"type":"object","properties":{"script":{"type":"string","description":"JavaScript that applies the fix to the live page"},"reason":{"type":"string","description":"What the patch changes, shown to the user who has to approve it"}},"required":["script"]}"""

    /** An enum built from the object that defines it, so the two cannot drift. */
    private fun enumOf(values: Collection<String>): String =
        values.joinToString("\",\"", prefix = "[\"", postfix = "\"]")

    private val SCHEMA_APP_OPEN = """{"type":"object","properties":{"screen":{"type":"string","enum":${enumOf(AgentAppActions.SCREENS)},"description":"Screen to open"}},"required":["screen"]}"""
    private val SCHEMA_APP_TABS = """{"type":"object","properties":{"action":{"type":"string","enum":${enumOf(AgentAppActions.TAB_ACTIONS)},"description":"list shows the tabs; the rest act on the tab at 'index' (or on this chat's tab when omitted)"},"index":{"type":"integer","description":"Tab index from action=list"},"position":{"type":"integer","description":"New position, for action=move"},"name":{"type":"string","description":"Group name for action=group"}}}"""
    private val SCHEMA_APP_DATA = """{"type":"object","properties":{"kind":{"type":"string","enum":${enumOf(AgentAppActions.DATA_KINDS)},"description":"Which saved list to work on"},"action":{"type":"string","enum":["list","add","remove","clear","search","pause","resume","cancel","retry","open"],"description":"What to do; the allowed actions depend on kind"},"id":{"type":"string","description":"Row id from action=list, for remove/pause/resume/cancel/retry/open"},"title":{"type":"string","description":"Title, for bookmarks action=add"},"url":{"type":"string","description":"URL, for bookmarks action=add"},"query":{"type":"string","description":"Search text, for history action=search"}},"required":["kind","action"]}"""
    private val SCHEMA_APP_SETTINGS = """{"type":"object","properties":{"action":{"type":"string","enum":["get","set"]},"key":{"type":"string","enum":${enumOf(AgentAppActions.PROFILE_SETTINGS.keys)},"description":"Setting name; omit for action=get to read them all"},"value":{"type":"string","description":"New value for action=set: true/false for a switch, or the text itself"}},"required":["action"]}"""
    private val SCHEMA_APP_SHIELDS = """{"type":"object","properties":{"action":{"type":"string","enum":${enumOf(AgentAppActions.SHIELD_ACTIONS)},"description":"read reports the current site; toggle turns tracking protection off/on for it; clear_site_data removes the stored site data"},"enabled":{"type":"boolean","description":"For action=toggle: true to keep protection on, false to switch it off for this site"}},"required":["action"]}"""
    private val SCHEMA_APP_SITE_PERMISSION = """{"type":"object","properties":{"action":{"type":"string","enum":${enumOf(AgentAppActions.PERMISSION_ACTIONS)}},"permission":{"type":"string","enum":${enumOf(AgentAppActions.PERMISSIONS)}},"decision":{"type":"string","enum":${enumOf(AgentAppActions.PERMISSION_DECISIONS)},"description":"For action=set"}},"required":["action"]}"""
    private val SCHEMA_APP_PAGE = """{"type":"object","properties":{"action":{"type":"string","enum":${enumOf(AgentAppActions.PAGE_ACTIONS)},"description":"find/find_next/find_previous/clear_find work on text; reader_on/reader_off, desktop_on/desktop_off and bookmark_add/bookmark_remove act on the open page"},"query":{"type":"string","description":"For the find actions: the text to find. find_next and find_previous need it repeated — the browser keeps no last query of its own"}},"required":["action"]}"""
    private val SCHEMA_APP_2FA = """{"type":"object","properties":{"action":{"type":"string","enum":${enumOf(AgentAppActions.TOTP_ACTIONS)},"description":"list names the profile's authenticator accounts; code gives the current code for one; copy puts it on the clipboard; fill types it into a field on the page"},"id":{"type":"string","description":"Account id from action=list"},"ref":{"type":"integer","description":"For action=fill: the input to type the code into, from read_page. Required for fill; there is no fallback to the focused element"}},"required":["action"]}"""
    private val SCHEMA_APP_NOTES = """{"type":"object","properties":{"action":{"type":"string","enum":${enumOf(AgentAppActions.NOTE_ACTIONS)}},"id":{"type":"string","description":"Note id from action=list, for get/update/delete"},"title":{"type":"string","description":"Note title, for add/update"},"body":{"type":"string","description":"Note text, for add/update"}},"required":["action"]}"""

    /**
     * Tool names whose execution may require user confirmation.
     *
     * `run_js` is deliberately NOT here: it has a gate of its own, the
     * per-profile "Agent console execution requires approval" setting, which is
     * ON by default — see
     * [com.roombrowser.domain.model.ProfileSettings.agentConsoleApprovalRequired]. The
     * Confirm actions switch and YOLO do not reach it either way. `wait_for`
     * only reads, like `wait`. `wallet_reject` is absent because rejecting is
     * the safe direction and must never be gated.
     *
     * The app tools are here because they change the browser itself — a
     * setting, a saved permission, a bookmark — which is exactly what the
     * Confirm actions switch is for. The actions among them that cannot be
     * undone ask EVERY time regardless of this set: see
     * [AgentAppActions.isDestructive]. `devtools_patch` is here for the same
     * reason, and additionally asks through the destructive gate every time.
     */
    val INTERACTIVE_TOOLS = setOf(
        CLICK, FILL_INPUT, PRESS_ENTER, SELECT_OPTION, PRESS_KEYS,
        AUTO_LIKE, AUTO_REPOST, AUTO_REPLY, AUTO_POST,
        WALLET_APPROVE, WALLET_SWITCH_NETWORK,
        APP_OPEN, APP_TABS, APP_DATA, APP_SETTINGS, APP_SHIELDS,
        APP_SITE_PERMISSION, APP_PAGE,
        APP_2FA, APP_NOTES,
        DEVTOOLS_PATCH
    )

    /**
     * The Developer Tools tools as one set.
     *
     * Being here is not what gates them: each one names the permission it needs
     * (see [AgentPermissions]), and a tool whose permission the profile withholds
     * is refused. It is the set the unattended paths use to refuse the whole
     * group — a scheduled or headless run has no Console panel to drive and no
     * user to confirm a page patch, so none of these has an honest meaning there.
     */
    val DEVTOOLS_TOOLS = setOf(
        DEVTOOLS_RELOAD, DEVTOOLS_CONSOLE, DEVTOOLS_NETWORK,
        DEVTOOLS_INSPECT, DEVTOOLS_AUDIT, DEVTOOLS_DIAGNOSE,
        DEVTOOLS_REPORT, DEVTOOLS_PATCH
    )

    /**
     * The wallet tools as one set. Every one of them answers a dApp request or
     * moves the active network, so none has a meaning without the user watching
     * the sheet — a scheduled run refuses the whole group.
     */
    val WALLET_TOOLS = setOf(
        WALLET_STATE, WALLET_REQUESTS, WALLET_APPROVE, WALLET_REJECT, WALLET_SWITCH_NETWORK
    )

    /** OpenAI `tools` array for the chat request. */
    fun toolDefs(): List<ToolDef> = listOf(
        def(NAVIGATE, "Navigate the current tab to a URL. Use complete URLs (https://...).", SCHEMA_NAVIGATE),
        def(SEARCH_WEB, "Search the web with the browser's search engine and show results.", SCHEMA_SEARCH),
        def(READ_PAGE, "Read the current page: URL, title, visible text and all interactive elements with [ref] numbers. Always call this after navigating or before interacting with the page.", SCHEMA_NO_PARAMS),
        def(CLICK, "Click an interactive element identified by its [ref] number from read_page.", SCHEMA_REF),
        def(FILL_INPUT, "Type text into an input/textarea field identified by its [ref] number from read_page.", SCHEMA_FILL),
        def(PRESS_ENTER, "Press Enter (submit the focused form or the given [ref] element).", SCHEMA_ENTER),
        def(SCROLL, "Scroll the page up or down.", SCHEMA_SCROLL),
        def(GO_BACK, "Go back one step in the browsing history.", SCHEMA_NO_PARAMS),
        def(OPEN_NEW_TAB, "Open a new tab and optionally navigate it to a URL.", SCHEMA_NEW_TAB),
        def(LIST_TABS, "List the open tabs with their indices.", SCHEMA_NO_PARAMS),
        def(SWITCH_TAB, "Switch to the tab with the given index (see list_tabs).", SCHEMA_TAB_INDEX),
        def(CLOSE_TAB, "Close the current tab.", SCHEMA_NO_PARAMS),
        def(AUTO_LIKE, "Like/upvote the posts currently visible on the page (works on social feeds: X, Facebook, Reddit, etc.). Likes up to 20 visible items. Scroll first, then call again to continue down the feed.", SCHEMA_NO_PARAMS),
        def(AUTO_REPOST, "Repost/retweet/reblog/share the posts currently visible on the page. Reposts up to 15 visible items. Scroll first, then call again to continue.", SCHEMA_NO_PARAMS),
        def(AUTO_REPLY, "Reply to the open post/thread: types the given text into the visible reply box and submits it. Returns immediately; call wait then read_page to verify.", SCHEMA_TEXT),
        def(AUTO_POST, "Create a new post/status/tweet with the given text: opens the composer, types, and submits. Call wait then read_page to verify.", SCHEMA_TEXT),
        def(WAIT, "Wait for a page update (post-submit animations, infinite scroll loading) before reading again.", SCHEMA_WAIT),
        def(
            WALLET_STATE,
            "Read the wallet's current state: whether it is locked, its accounts (chain, label, address), the active network for each chain, and the networks it already knows. Use it to find a network_id for wallet_switch_network. Addresses are public; never ask the user for a key or phrase.",
            SCHEMA_NO_PARAMS
        ),
        def(
            WALLET_REQUESTS,
            "List the dApp requests waiting for the user's decision, with the host, the method and the transaction or message being requested. ALWAYS call this before wallet_approve: approving a request that was not listed here is refused. Listing changes nothing and needs no confirmation.",
            SCHEMA_NO_PARAMS
        ),
        def(
            WALLET_APPROVE,
            "Approve one pending dApp request by its request_id from wallet_requests. This is IRREVERSIBLE: approving a transaction broadcasts it and moves funds, and approving a signature lets the dApp use a signature that cannot be recalled. The user is shown the full request and must confirm, so expect a pause. Never approve anything the user did not ask for — when the request is unclear or unwanted, use wallet_reject instead.",
            SCHEMA_REQUEST_ID
        ),
        def(
            WALLET_REJECT,
            "Reject one pending dApp request by its request_id. Rejecting is always allowed and never asks for confirmation — it is the safe answer when the request is unclear, unexpected, or was not asked for. The page receives a user-rejected error and nothing is signed or sent.",
            SCHEMA_REQUEST_ID
        ),
        def(
            WALLET_SWITCH_NETWORK,
            "Make an already-known network the wallet's active network for its chain, using a network_id from wallet_state. It cannot add or invent a network: the id must already be in the wallet and enabled. This changes which network every connected dApp sees (chainChanged) and which network later transactions target, and the user is asked to confirm. It moves no funds.",
            SCHEMA_NETWORK_ID
        ),
        def(RUN_JS, "Run JavaScript in the current page and get the value of the last expression back as text. Use it for anything the other tools do not cover: reading attributes, hovering, scrolling an element into view, localStorage. Synchronous only — a returned Promise is not awaited, so read its eventual effect with wait_for or read_page instead.", SCHEMA_RUN_JS),
        def(SELECT_OPTION, "Choose an option in a dropdown (<select>) by its value or by its visible text.", SCHEMA_SELECT_OPTION),
        def(PRESS_KEYS, "Send a key chord to the focused element (or to one by [ref]): Enter, Escape to close a dialog, Tab, ArrowDown, PageDown, F1-F12, or a combination like Control+a.", SCHEMA_PRESS_KEYS),
        def(WAIT_FOR, "Wait until the given text appears in the visible page. Cheaper than polling with read_page when a page loads its content late.", SCHEMA_WAIT_FOR),
        def(APP_OPEN, "Open one of the browser's own screens, by name: settings, history, bookmarks, downloads, tabs, privacy, wallet, theme, ai_tasks and the rest. The page stays loaded and the page tools keep working afterwards.", SCHEMA_APP_OPEN),
        def(APP_TABS, "Work with the browser's tabs: list them, pin or unpin, duplicate, reopen the last closed one, put one in a named group, move it, open a private tab, or close every other tab.", SCHEMA_APP_TABS),
        def(APP_DATA, "Read or change what the browser has saved: bookmarks, history and downloads. Ids come from action=list, not from the page.", SCHEMA_APP_DATA),
        def(APP_SETTINGS, "Read or change this profile's browsing settings by name (JavaScript, ad and tracker blocking, HTTPS upgrade, search engine, and the other names in the schema). Everything the tool can write is listed in its 'key' values.", SCHEMA_APP_SETTINGS),
        def(APP_SHIELDS, "Report tracking protection for the site in this chat's tab and turn it off or on for that site, or clear the browser's stored site data.", SCHEMA_APP_SHIELDS),
        def(APP_SITE_PERMISSION, "List or change the permissions saved for the site in this chat's tab: camera, microphone, location, notifications and the rest.", SCHEMA_APP_SITE_PERMISSION),
        def(APP_PAGE, "Act on the open page from the browser side: find text in it, step through the matches, toggle reader mode or desktop mode, bookmark it, or open Developer Tools for this chat's tab.", SCHEMA_APP_PAGE),
        def(
            APP_2FA,
            "The profile's authenticator (TOTP) accounts. action=list names them — issuer, account and id, never the setup key. action=code gives the code that is valid right now for one of them, action=copy puts it on the clipboard and action=fill types it into the login form. Codes are generated on the device; there is no way to read a setup key out of this tool, and the person cannot be asked to hand one over. Ask for a code only when a form on the page actually needs one.",
            SCHEMA_APP_2FA
        ),
        def(
            APP_NOTES,
            "The notes saved in this profile. action=list shows them with their ids, action=get reads one in full, and add/update/delete change them. Notes belong to the profile, not to the page.",
            SCHEMA_APP_NOTES
        ),
        def(RELOAD, "Reload the page in this chat's tab. The Developer Tools console and network buffers keep working across the reload.", SCHEMA_NO_PARAMS),
        def(FORWARD, "Go forward one step in this chat's tab's history, undoing a back.", SCHEMA_NO_PARAMS),
        def(
            DEVTOOLS_RELOAD,
            "Reload the page specifically to watch what it does on a fresh load, then read the console. Use it when a bug only shows up on load, or after a change you just made. A plain reload is `reload`; this one announces itself as a diagnostic step.",
            SCHEMA_NO_PARAMS
        ),
        def(
            DEVTOOLS_CONSOLE,
            "Read the page's Developer Tools console — the real buffer the Console panel shows, including errors and warnings the page logged. action=read lists entries (filter with level and contains), action=clear empties the buffer, action=preserve decides whether entries survive a navigation. Reading is how you find out WHY something broke; the browser will never guess and never answer with an empty list when the panel is unavailable, it says so instead.",
            SCHEMA_DEVTOOLS_CONSOLE
        ),
        def(
            DEVTOOLS_NETWORK,
            "Read the page's Developer Tools network log — the real buffer the Network panel shows, one row per request with its status, method, URL and timing. action=read lists rows (use failed_only or contains to narrow), action=clear empties it. Use it to find a request that failed or a resource that never loaded.",
            SCHEMA_DEVTOOLS_NETWORK
        ),
        def(
            DEVTOOLS_INSPECT,
            "Inspect one element on the page: its tag, attributes, computed box and the text around it, by the [ref] number from read_page or by a CSS selector. Use it after a console error or a failed request to find the exact node involved.",
            SCHEMA_DEVTOOLS_INSPECT
        ),
        def(
            DEVTOOLS_AUDIT,
            "Run a fixed diagnostic checklist over the open page — document readiness, console errors, failed requests, broken resources, mixed content, and storage/cookie availability — and report what it found with each finding labelled OBSERVED FACT, HYPOTHESIS or RECOMMENDATION. It changes nothing on the page.",
            SCHEMA_DEVTOOLS_AUDIT
        ),
        def(
            DEVTOOLS_DIAGNOSE,
            "Correlate a symptom you or the user noticed with the console and network buffers: give it the text (a message, a button label, a URL) and it returns the console error and the request that go with it, plus the element that likely triggered it. Read-only; it is the deepest single-step diagnosis available.",
            SCHEMA_DEVTOOLS_DIAGNOSE
        ),
        def(
            DEVTOOLS_REPORT,
            "Produce a diagnostic report for the user to share with a site's developer or keep: the engine, the app and Android versions, the profile's label (never its id), the active URL, and the redacted console and network buffers with correlated findings. Nothing secret goes into it — headers, tokens and URLs are redacted before they leave the device. Say where you saved it.",
            SCHEMA_NO_PARAMS
        ),
        def(
            DEVTOOLS_PATCH,
            "Apply a candidate fix to the live page, in the page's own session, by running JavaScript. THIS IS A WRITE TO A LIVE PAGE and is always confirmed by the user, every time, and refused in plan mode and in any unattended run — it is grouped with the destructive actions for that reason. Use it only when the user asked you to fix the page, and explain in 'reason' what the patch changes so the person approving it knows what they are agreeing to.",
            SCHEMA_DEVTOOLS_PATCH
        )
    )

    private fun def(name: String, description: String, schema: String): ToolDef =
        ToolDef(
            function = ToolFunction(
                name = name,
                description = description,
                parameters = AgentJson.parseToJsonElement(schema).jsonObject
            )
        )

    /**
     * Human-readable one-line label for a tool invocation, used by the chat
     * UI cards. Parses the arguments leniently — never throws.
     */
    fun describeTool(name: String, argsJson: String?): String = try {
        val args: JsonObject = runCatching {
            when {
                argsJson.isNullOrBlank() -> JsonObject(emptyMap())
                else -> AgentJson.parseToJsonElement(argsJson).let { it as? JsonObject }
                    ?: JsonObject(emptyMap())
            }
        }.getOrDefault(JsonObject(emptyMap()))
        fun str(key: String): String? =
            (args[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        fun int(key: String): Int? =
            (args[key] as? JsonPrimitive)?.intOrNull
        // A UUID, shortened for the label; the model still passes the whole id.
        fun shortId(id: String?): String = id?.take(8) ?: "?"

        when (name) {
            NAVIGATE -> "Open ${str("url") ?: "page"}"
            SEARCH_WEB -> "Search \"${str("query") ?: ""}\""
            READ_PAGE -> "Read current page"
            CLICK -> "Click [${int("ref") ?: "?"}]"
            FILL_INPUT -> "Type into [${int("ref") ?: "?"}]"
            PRESS_ENTER -> "Press Enter"
            SCROLL -> "Scroll ${str("direction") ?: "down"}"
            GO_BACK -> "Go back"
            OPEN_NEW_TAB -> "New tab${str("url")?.let { ": $it" } ?: ""}"
            LIST_TABS -> "List tabs"
            SWITCH_TAB -> "Switch to tab [${int("index") ?: "?"}]"
            CLOSE_TAB -> "Close current tab"
            AUTO_LIKE -> "Like visible posts"
            AUTO_REPOST -> "Repost visible posts"
            AUTO_REPLY -> "Reply \"${(str("text") ?: "").take(30)}\""
            AUTO_POST -> "Post \"${(str("text") ?: "").take(30)}\""
            WAIT -> "Wait ${formatDurationMs(int("ms") ?: 1500)}"
            WALLET_STATE -> "Read wallet state"
            WALLET_REQUESTS -> "List pending wallet requests"
            WALLET_APPROVE -> "Approve wallet request ${shortId(str("request_id"))}"
            WALLET_REJECT -> "Reject wallet request ${shortId(str("request_id"))}"
            WALLET_SWITCH_NETWORK -> "Switch network to ${str("network_id") ?: "?"}"
            RUN_JS -> "Run JS: " + (str("script") ?: "").lineSequence().firstOrNull().orEmpty().take(40)
            SELECT_OPTION -> "Select \"${(str("value") ?: "").take(30)}\" in [${int("ref") ?: "?"}]"
            PRESS_KEYS -> "Press ${str("keys") ?: "?"}"
            WAIT_FOR -> "Wait for \"${(str("text") ?: "").take(30)}\""
            APP_OPEN -> "Open ${str("screen") ?: "?"} screen"
            APP_TABS -> "Tabs: ${str("action") ?: "list"}${int("index")?.let { " [$it]" } ?: ""}"
            APP_DATA -> "${str("kind") ?: "data"}: ${str("action") ?: "list"}"
            APP_SETTINGS -> "Settings: ${str("action") ?: "get"}${str("key")?.let { " $it" } ?: ""}"
            APP_SHIELDS -> "Shields: ${str("action") ?: "read"}"
            APP_SITE_PERMISSION -> "Site permission: ${str("action") ?: "list"}"
            APP_PAGE -> "Page: ${str("action") ?: "?"}"
            APP_2FA -> "2FA: ${str("action") ?: "list"}"
            APP_NOTES -> "Notes: ${str("action") ?: "list"}"
            RELOAD -> "Reload page"
            FORWARD -> "Go forward"
            DEVTOOLS_RELOAD -> "Reload and watch the console"
            DEVTOOLS_CONSOLE -> "Console: ${str("action") ?: "read"}${str("level")?.let { " ($it)" } ?: ""}"
            DEVTOOLS_NETWORK -> "Network: ${str("action") ?: "read"}${if (str("failed_only").toBoolean()) " (failed)" else ""}"
            DEVTOOLS_INSPECT -> "Inspect " + (int("ref")?.let { "[$it]" } ?: str("selector") ?: "element")
            DEVTOOLS_AUDIT -> "Audit the page"
            DEVTOOLS_DIAGNOSE -> "Diagnose" + (str("focus")?.let { ": \"${it.take(30)}\"" } ?: "")
            DEVTOOLS_REPORT -> "Export diagnostic report"
            DEVTOOLS_PATCH -> "Apply a page patch"
            else -> name
        }
    } catch (_: Exception) {
        name
    }
}

/**
 * A millisecond count the way a person reads it: seconds, with one decimal
 * only when the value is not whole.
 *
 * WHY: "Wait 2000ms" is a number the reader has to divide in their head;
 * "Wait 2s" is already the answer. Sub-second waits keep the decimal
 * ("0.2s") rather than rounding to "0s", which would read as no wait at all.
 *
 * The wire format is unchanged — the model still asks in `ms`, which is what
 * every provider's tool schema expects. This is display only.
 */
fun formatDurationMs(ms: Int): String {
    val tenths = Math.round(ms / 100.0)
    return if (tenths % 10 == 0L) "${tenths / 10}s" else "${tenths / 10}.${tenths % 10}s"
}

// ---------- Page snapshot (produced by JS injection in the WebView) ----------

@Serializable
data class PageSnapshotDto(
    val url: String = "",
    val title: String = "",
    val text: String = "",
    val scrollY: Int = 0,
    val maxScrollY: Int = 0,
    val elements: List<SnapElement> = emptyList()
)

@Serializable
data class SnapElement(
    val ref: Int = 0,
    val tag: String = "",
    val label: String = "",
    val viewport: Boolean = false,
    val href: String? = null,
    val type: String? = null,
    val checked: Boolean? = null,
    val disabled: Boolean? = null
)

/**
 * Formats a page snapshot into the compact text representation given to
 * the model. Text and elements are capped so a huge page cannot blow up
 * the context window.
 */
object PageSnapshotFormatter {

    const val MAX_TEXT_CHARS = 6000
    const val MAX_ELEMENTS = 120

    /**
     * Every page snapshot the model sees goes through the untrusted fence and
     * the redactor, here rather than at each call site, so a new caller cannot
     * forget either.
     */
    fun format(s: PageSnapshotDto): String =
        UntrustedContent.wrap(SecretRedaction.redact(render(s)), "the page")

    private fun render(s: PageSnapshotDto): String = buildString {
        appendLine("URL: ${s.url}")
        appendLine("TITLE: ${s.title.ifBlank { "(untitled)" }}")
        appendLine("SCROLL: ${s.scrollY}/${s.maxScrollY}")
        appendLine()
        appendLine("PAGE TEXT (truncated):")
        if (s.text.isBlank()) {
            appendLine("(no visible text — the page may still be loading or render via canvas)")
        } else if (s.text.length <= MAX_TEXT_CHARS) {
            appendLine(s.text)
        } else {
            appendLine(s.text.take(MAX_TEXT_CHARS / 2))
            appendLine("…[middle omitted]…")
            appendLine(s.text.takeLast(MAX_TEXT_CHARS / 4))
        }
        appendLine()
        appendLine("INTERACTIVE ELEMENTS (use [ref] numbers):")
        val elements = s.elements.take(MAX_ELEMENTS)
        if (elements.isEmpty()) {
            appendLine("(no interactive elements found)")
        } else {
            elements.forEach { e ->
                append("[${e.ref}] <${e.tag}")
                e.type?.let { append(" type=$it") }
                if (e.disabled == true) append(" disabled")
                append(">")
                if (e.label.isNotBlank()) append(" \"${e.label.take(80)}\"")
                e.href?.let { append(" -> ${it.take(120)}") }
                append(if (e.viewport) "  (in viewport)" else "")
                appendLine()
            }
        }
    }
}
