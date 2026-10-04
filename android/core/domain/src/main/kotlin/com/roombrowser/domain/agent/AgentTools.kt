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

    /** Tool names whose execution may require user confirmation. */
    val INTERACTIVE_TOOLS = setOf(
        CLICK, FILL_INPUT, PRESS_ENTER,
        AUTO_LIKE, AUTO_REPOST, AUTO_REPLY, AUTO_POST,
        // Reject is deliberately absent: the safe direction must never be gated.
        WALLET_APPROVE, WALLET_SWITCH_NETWORK
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

    fun format(s: PageSnapshotDto): String = buildString {
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
