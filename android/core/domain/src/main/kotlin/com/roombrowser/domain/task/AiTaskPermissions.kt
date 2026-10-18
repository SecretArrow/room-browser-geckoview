package com.roombrowser.domain.task

import com.roombrowser.domain.agent.AgentTools
import kotlinx.serialization.Serializable

/**
 * What one scheduled AI task is allowed to do on the page, chosen when the
 * task is created. The four page groups mirror the tool catalogue's risk tiers,
 * so a task that only reads can never be talked into clicking or posting by the
 * page it is reading.
 *
 * The default is read + navigate + interact, and posting OFF: the ordinary
 * "go there and tell me" task needs the first three, while anything that
 * publishes on the user's behalf is opt-in per task.
 *
 * Wallet tools are the fifth group and have no toggle at all: approving a dApp
 * request signs or broadcasts, and the confirmation it needs can only come from
 * a person, so no task grant reaches them (see [ToolGroup.WALLET]).
 *
 * App control is the sixth, and is denied for the same reason: it changes the
 * browser itself — its settings, its saved per-site permissions, what it has
 * kept — and some of it cannot be undone (see [ToolGroup.APP]).
 *
 * The embedded Node runtime is the seventh and is denied outright as well: a
 * Node program reaches the network and this profile's files with no page in
 * front of it, so an unattended run has nothing to gain from it and a great
 * deal to lose (see [ToolGroup.NODE]).
 *
 * The authenticator codes and the notes are the last two, and they differ from
 * every group above in one way: [allows] refuses them here, because a task must
 * never grant itself a live code, but the RUNNER may override that refusal when
 * the user has switched the behaviour on in settings. That setting being OFF is
 * the whole of the protection, so the groups stay denied in this class and the
 * gate consults the setting — this decision stays pure and testable.
 *
 * An unknown tool name is denied, not allowed — a tool added to the catalogue
 * later must be granted explicitly rather than inheriting a blanket yes.
 */
@Serializable
data class AiTaskPermissions(
    val allowReadPage: Boolean = true,
    val allowNavigate: Boolean = true,
    val allowInteract: Boolean = true,
    val allowPost: Boolean = false
) {
    fun groupOf(toolName: String): ToolGroup? {
        // A chat plugin's tools are named per chat (see
        // [AgentTools.PLUGIN_TOOL_PREFIX]) and never appear in the static
        // branches below — membership is by prefix, and they ride the NODE
        // group: a plugin's code runs with the runtime's own reach, and an
        // unattended run must not run it any more than it runs node_run.
        if (toolName.startsWith(AgentTools.PLUGIN_TOOL_PREFIX)) return ToolGroup.NODE
        return when (toolName) {
            AgentTools.READ_PAGE, AgentTools.SEE_PAGE, AgentTools.SCROLL, AgentTools.WAIT,
            AgentTools.WAIT_FOR, AgentTools.COMPUTE_JS, AgentTools.DATA_TRANSFORM,
            // compare_screenshots captures the page and reads its pixels; it starts
            // no navigation and sends the model nothing, so it is a read.
            AgentTools.COMPARE_SCREENSHOTS,
            // site_memory touches no page and starts no navigation: like the two
            // local tools above, it is bookkeeping the agent does on the device, so
            // it rides with the read group. Its own per-profile opt-in is the real
            // gate; this group is only the coarse unattended one.
            AgentTools.SITE_MEMORY,
            // plan is the model's own step list — no page, no profile, nothing
            // stored — so it rides here for the same reason and is never worth
            // refusing: it is how a run reports where it has got to.
            AgentTools.PLAN -> ToolGroup.READ_PAGE
            AgentTools.NAVIGATE, AgentTools.SEARCH_WEB, AgentTools.GO_BACK,
            AgentTools.OPEN_NEW_TAB, AgentTools.LIST_TABS,
            AgentTools.SWITCH_TAB, AgentTools.CLOSE_TAB,
            AgentTools.RELOAD, AgentTools.FORWARD -> ToolGroup.NAVIGATE
            AgentTools.CLICK, AgentTools.FILL_INPUT, AgentTools.PRESS_ENTER,
            AgentTools.SELECT_OPTION, AgentTools.PRESS_KEYS, AgentTools.RUN_JS,
            // A download is the same kind of act as a click — it follows a link —
            // and it is allowed at the same tier. A run has no download engine of
            // its own and refuses it in the executor; this is only the coarse
            // grant, and the run's own gate is the sharper one.
            AgentTools.DOWNLOAD_FILE,
            // Saving a page as a PDF writes a file to the user's Downloads, so it
            // rides here for the same reason and is refused by a run's own gate.
            AgentTools.SAVE_PDF -> ToolGroup.INTERACT
            AgentTools.AUTO_LIKE, AgentTools.AUTO_REPOST,
            AgentTools.AUTO_REPLY, AgentTools.AUTO_POST -> ToolGroup.POST
            in AgentTools.WALLET_TOOLS -> ToolGroup.WALLET
            in AgentTools.DEVTOOLS_TOOLS -> ToolGroup.DEVTOOLS
            // A program with Node's own APIs behind it, and the tool that installs
            // packages for it. A scheduled run is offered the same catalogue, so the
            // refusal has to be a real one rather than a tool that runs because
            // nobody classified it.
            AgentTools.NODE_RUN, AgentTools.NODE_PACKAGES -> ToolGroup.NODE
            AgentTools.APP_OPEN, AgentTools.APP_TABS, AgentTools.APP_DATA,
            AgentTools.APP_SETTINGS, AgentTools.APP_SHIELDS,
            AgentTools.APP_SITE_PERMISSION, AgentTools.APP_PAGE -> ToolGroup.APP
            // The phone's radios, classified with the app tools a scheduled
            // run is refused: a switch nobody is watching could silence the
            // run's own network, and the guided non-root flow needs a person
            // at the screen anyway. The headless executor's separate refusal
            // (no bridge wired) agrees.
            AgentTools.AIRPLANE_MODE -> ToolGroup.APP
            AgentTools.APP_2FA -> ToolGroup.TOTP
            AgentTools.APP_NOTES -> ToolGroup.NOTES
            else -> null
        }
    }

    fun allows(toolName: String): Boolean = when (groupOf(toolName)) {
        ToolGroup.READ_PAGE -> allowReadPage
        ToolGroup.NAVIGATE -> allowNavigate
        ToolGroup.INTERACT -> allowInteract
        ToolGroup.POST -> allowPost
        ToolGroup.WALLET -> false
        // Nobody is watching a scheduled run, and these change the browser
        // itself — including irreversibly. A chat turn is the only route.
        ToolGroup.APP -> false
        // The Developer Tools tools need a live Console and Network panel and a
        // person to confirm a patch; a scheduled run has neither.
        ToolGroup.DEVTOOLS -> false
        // A Node program has the profile's files and the open network behind it
        // and no page in front of it, so there is nothing a scheduled run could
        // want it for that a chat turn could not do with the user watching.
        ToolGroup.NODE -> false
        // The one group a SETTING can hand over: see [ToolGroup.TOTP].
        ToolGroup.TOTP, ToolGroup.NOTES -> false
        null -> false
    }

    /** The refusal a denied tool answers with, or null when it is allowed. */
    fun refusal(toolName: String): String? =
        if (allows(toolName)) null else refusalMessage(toolName, groupOf(toolName))

    companion object {
        val DEFAULT = AiTaskPermissions()
    }
}

enum class ToolGroup(val label: String) {
    READ_PAGE("reading the page"),
    NAVIGATE("navigating"),
    INTERACT("clicking and typing"),
    POST("posting"),

    /** Never granted to a task; see [walletUnattendedRefusal]. */
    WALLET("wallet requests"),

    /** Never granted to a task; see [appUnattendedRefusal]. */
    APP("the app's own screens, settings and data"),

    /** Never granted to a task; see [devtoolsUnattendedRefusal]. */
    DEVTOOLS("the Developer Tools tools"),

    /** Never granted to a task; see [nodeUnattendedRefusal]. */
    NODE("the embedded Node runtime"),

    /** Never granted by a task itself; one setting can hand it over. */
    TOTP("the authenticator codes"),

    /** Never granted by a task itself; one setting can hand it over. */
    NOTES("the profile's notes")
}

/**
 * What the model reads back when it asks for a tool the task does not allow.
 * It has to say the permission is missing, not that the tool is unknown:
 * "unknown tool" sends it looking for another route to the same action, and it
 * retries a refusal that reads like a typo.
 */
internal fun refusalMessage(toolName: String, group: ToolGroup?): String = when (group) {
    null -> "unknown tool: $toolName"
    ToolGroup.WALLET -> walletUnattendedRefusal(toolName)
    ToolGroup.APP -> appUnattendedRefusal(toolName)
    ToolGroup.DEVTOOLS -> devtoolsUnattendedRefusal(toolName)
    // A plugin's tool rides the NODE group but is not a Node script the model
    // wrote, so it is refused in its own words.
    ToolGroup.NODE ->
        if (toolName.startsWith(AgentTools.PLUGIN_TOOL_PREFIX)) {
            pluginUnattendedRefusal(toolName)
        } else {
            nodeUnattendedRefusal(toolName)
        }
    ToolGroup.TOTP, ToolGroup.NOTES -> profileUnattendedRefusal(toolName)
    else -> "tool '$toolName' is switched off for this task: ${group.label} is not permitted"
}

/**
 * The refusal for the profile's own content — the authenticator codes and the
 * notes. Unlike the wallet and the browser's screens, a setting CAN hand these
 * over, so the refusal names it: a refusal that reads like a dead end sends the
 * model looking for another route to the same thing.
 */
fun profileUnattendedRefusal(toolName: String): String =
    "tool '$toolName' works only in a chat by default. To let a scheduled run use the " +
        "authenticator codes and the notes, turn on \"Allow scheduled AI tasks to use 2FA and " +
        "Notes\" in AI Agent settings — and read what that setting costs before you do."

/**
 * The refusal for app control in a scheduled run: these actions change the
 * browser itself — its settings, its per-site permissions, what it has saved —
 * and some of them cannot be undone. The confirmation they need can only come
 * from a person, so a chat turn is the only route.
 */
fun appUnattendedRefusal(toolName: String): String =
    "tool '$toolName' changes the app itself and works only in a chat, where you can be asked " +
        "first. Run this as a chat, or do it from the app's own screens."

/**
 * The refusal for the Developer Tools tools in a scheduled run: they read the
 * live Console and Network panels, which exist only while the user has those
 * panels open, and one of them — applying a patch — is a write to a live page
 * that must be confirmed. A scheduled run has neither the panels nor the person.
 */
fun devtoolsUnattendedRefusal(toolName: String): String =
    "tool '$toolName' works only in a chat: it reads the live Developer Tools panels, which are " +
        "there only while the user has them open, and a page patch needs their confirmation. " +
        "Run this as a chat."

/**
 * The AI Agent settings row's title, verbatim. [unattendedRefusal] names the
 * row the reader must flip, so the two must spell it the same way — the
 * settings screen renders this constant as its title and the refusal quotes
 * it, which is the compile-time link that keeps a future rewording of one
 * from silently orphaning the other (the wallet refusal below gets the same
 * protection from a test that pins its literal; this one is fixed by
 * construction instead).
 */
const val CONFIRM_ACTIONS_SETTING_TITLE = "Confirm actions"

/**
 * The refusal for an action the task DOES allow but an unattended run must not
 * take: the confirmation the user asked for can only come from a person, and a
 * scheduled run has nobody to ask. It names the setting that would allow it,
 * so the refusal is actionable rather than a dead end.
 */
fun unattendedRefusal(toolName: String): String =
    "tool '$toolName' would ask you to confirm it first, and a scheduled run has nobody to ask. " +
        "Turn off \"$CONFIRM_ACTIONS_SETTING_TITLE\" in AI Agent settings, or run this in a chat."

/**
 * The refusal for the wallet tools, which no setting can turn on: the promise
 * that a task cannot reach the wallet is what makes "the model cannot approve a
 * transaction on its own" true. The model has to be told the route that works,
 * or it retries the same call until the step budget is gone.
 */
fun walletUnattendedRefusal(toolName: String): String =
    "tool '$toolName' works only in a chat, never in a scheduled run: a wallet request is " +
        "approved by you, in person. Ask the user to approve it in the wallet sheet."

/**
 * The refusal for the embedded Node runtime in a scheduled run. A Node program
 * has this profile's files and the open network behind it and no page in front
 * of it, and the packages it can require are installed the same way, so a run
 * nobody is watching is the wrong place for either: there is nothing they could
 * do there that a chat could not do with the user present.
 */
fun nodeUnattendedRefusal(toolName: String): String =
    "tool '$toolName' works only in a chat: it runs a program with Node's own APIs, or installs " +
        "a package for one, and both can reach the network and write to this profile's files. A " +
        "scheduled run has nobody to ask, so run it as a chat."

/**
 * The refusal for a chat plugin's tool in an unattended run. The permission
 * map already files these names under the NODE group — the plugin's code runs
 * with that runtime's reach — and this is the sentence that says what the
 * name actually is: code a person consented to for ONE chat, which an
 * unattended run is twice the wrong place for.
 */
fun pluginUnattendedRefusal(toolName: String): String =
    "tool '$toolName' works only in a chat: it runs a chat plugin's code, enabled for that chat " +
        "by you and running with the embedded Node runtime's reach. A scheduled run has nobody " +
        "to ask, so run it as a chat."
