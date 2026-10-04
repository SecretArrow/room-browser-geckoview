package com.roombrowser.domain.task

import com.roombrowser.domain.agent.AgentTools
import kotlinx.serialization.Serializable

/**
 * What one scheduled AI task is allowed to do on the page, chosen when the
 * task is created. The four groups mirror the tool catalogue's risk tiers, so
 * a task that only reads can never be talked into clicking or posting by the
 * page it is reading.
 *
 * The default is read + navigate + interact, and posting OFF: the ordinary
 * "go there and tell me" task needs the first three, while anything that
 * publishes on the user's behalf is opt-in per task.
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
    fun allows(toolName: String): Boolean = when (toolName) {
        AgentTools.READ_PAGE, AgentTools.SCROLL, AgentTools.WAIT -> allowReadPage
        AgentTools.NAVIGATE, AgentTools.SEARCH_WEB, AgentTools.GO_BACK,
        AgentTools.OPEN_NEW_TAB, AgentTools.LIST_TABS,
        AgentTools.SWITCH_TAB, AgentTools.CLOSE_TAB -> allowNavigate
        AgentTools.CLICK, AgentTools.FILL_INPUT, AgentTools.PRESS_ENTER -> allowInteract
        AgentTools.AUTO_LIKE, AgentTools.AUTO_REPOST,
        AgentTools.AUTO_REPLY, AgentTools.AUTO_POST -> allowPost
        else -> false
    }

    companion object {
        val DEFAULT = AiTaskPermissions()
    }
}
