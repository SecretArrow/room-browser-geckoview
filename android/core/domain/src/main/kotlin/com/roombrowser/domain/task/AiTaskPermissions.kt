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
    fun groupOf(toolName: String): ToolGroup? = when (toolName) {
        AgentTools.READ_PAGE, AgentTools.SCROLL, AgentTools.WAIT -> ToolGroup.READ_PAGE
        AgentTools.NAVIGATE, AgentTools.SEARCH_WEB, AgentTools.GO_BACK,
        AgentTools.OPEN_NEW_TAB, AgentTools.LIST_TABS,
        AgentTools.SWITCH_TAB, AgentTools.CLOSE_TAB -> ToolGroup.NAVIGATE
        AgentTools.CLICK, AgentTools.FILL_INPUT, AgentTools.PRESS_ENTER -> ToolGroup.INTERACT
        AgentTools.AUTO_LIKE, AgentTools.AUTO_REPOST,
        AgentTools.AUTO_REPLY, AgentTools.AUTO_POST -> ToolGroup.POST
        in AgentTools.WALLET_TOOLS -> ToolGroup.WALLET
        else -> null
    }

    fun allows(toolName: String): Boolean = when (groupOf(toolName)) {
        ToolGroup.READ_PAGE -> allowReadPage
        ToolGroup.NAVIGATE -> allowNavigate
        ToolGroup.INTERACT -> allowInteract
        ToolGroup.POST -> allowPost
        ToolGroup.WALLET -> false
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
    WALLET("wallet requests")
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
    else -> "tool '$toolName' is switched off for this task: ${group.label} is not permitted"
}

/**
 * The refusal for an action the task DOES allow but an unattended run must not
 * take: the confirmation the user asked for can only come from a person, and a
 * scheduled run has nobody to ask. It names the setting that would allow it,
 * so the refusal is actionable rather than a dead end.
 */
fun unattendedRefusal(toolName: String): String =
    "tool '$toolName' would ask you to confirm it first, and a scheduled run has nobody to ask. " +
        "Turn off \"Confirm actions before running\" in AI Agent settings, or run this in a chat."

/**
 * The refusal for the wallet tools, which no setting can turn on: the promise
 * that a task cannot reach the wallet is what makes "the model cannot approve a
 * transaction on its own" true. The model has to be told the route that works,
 * or it retries the same call until the step budget is gone.
 */
fun walletUnattendedRefusal(toolName: String): String =
    "tool '$toolName' works only in a chat, never in a scheduled run: a wallet request is " +
        "approved by you, in person. Ask the user to approve it in the wallet sheet."
