package com.roombrowser.domain.task

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.agent.AgentAppActions
import com.roombrowser.domain.agent.AgentTools
import org.junit.Test

/**
 * The permission map is the only thing standing between a scheduled task and a
 * tool it was never granted, so every tool the agent can call is pinned here.
 *
 * A tool that maps to NO branch would silently be denied (the `else`), which is
 * the safe direction — but it would also be a tool a user cannot enable by any
 * toggle, so the full [AgentTools] surface is asserted rather than sampled.
 */
class AiTaskPermissionsTest {

    private val all = AiTaskPermissions.DEFAULT

    @Test
    fun defaults_allow_reading_and_navigation_but_never_posting() {
        assertThat(all.allowReadPage).isTrue()
        assertThat(all.allowNavigate).isTrue()
        assertThat(all.allowInteract).isTrue()
        assertThat(all.allowPost).isFalse()
    }

    @Test
    fun read_group_follows_allow_read_page() {
        val denied = all.copy(allowReadPage = false)
        assertThat(denied.allows(AgentTools.READ_PAGE)).isFalse()
        assertThat(denied.allows(AgentTools.SCROLL)).isFalse()
        assertThat(denied.allows(AgentTools.WAIT)).isFalse()
        assertThat(all.allows(AgentTools.READ_PAGE)).isTrue()
        assertThat(all.allows(AgentTools.SCROLL)).isTrue()
        assertThat(all.allows(AgentTools.WAIT)).isTrue()
    }

    @Test
    fun navigation_group_follows_allow_navigate() {
        val denied = all.copy(allowNavigate = false)
        listOf(
            AgentTools.NAVIGATE, AgentTools.SEARCH_WEB, AgentTools.GO_BACK,
            AgentTools.OPEN_NEW_TAB, AgentTools.LIST_TABS,
            AgentTools.SWITCH_TAB, AgentTools.CLOSE_TAB
        ).forEach { tool ->
            assertThat(denied.allows(tool)).isFalse()
            assertThat(all.allows(tool)).isTrue()
        }
    }

    @Test
    fun interact_group_follows_allow_interact() {
        val denied = all.copy(allowInteract = false)
        listOf(AgentTools.CLICK, AgentTools.FILL_INPUT, AgentTools.PRESS_ENTER).forEach { tool ->
            assertThat(denied.allows(tool)).isFalse()
            assertThat(all.allows(tool)).isTrue()
        }
    }

    @Test
    fun post_group_is_denied_until_allow_post_is_explicitly_granted() {
        val granted = all.copy(allowPost = true)
        listOf(
            AgentTools.AUTO_LIKE, AgentTools.AUTO_REPOST,
            AgentTools.AUTO_REPLY, AgentTools.AUTO_POST
        ).forEach { tool ->
            assertThat(all.allows(tool)).isFalse()
            assertThat(granted.allows(tool)).isTrue()
        }
    }

    @Test
    fun reading_does_not_imply_posting() {
        val readOnly = AiTaskPermissions(
            allowReadPage = true,
            allowNavigate = false,
            allowInteract = false,
            allowPost = false
        )
        assertThat(readOnly.allows(AgentTools.AUTO_POST)).isFalse()
        assertThat(readOnly.allows(AgentTools.READ_PAGE)).isTrue()
    }

    @Test
    fun an_unknown_tool_is_denied_even_with_every_grant_on() {
        val everything = AiTaskPermissions(
            allowReadPage = true,
            allowNavigate = true,
            allowInteract = true,
            allowPost = true
        )
        assertThat(everything.allows("run_shell")).isFalse()
        assertThat(everything.allows("")).isFalse()
    }

    @Test
    fun every_catalogue_tool_belongs_to_exactly_one_group() {
        val catalogue = AgentTools.toolDefs().map { it.function.name }
        listOf(
            ToolGroup.READ_PAGE, ToolGroup.NAVIGATE, ToolGroup.INTERACT,
            ToolGroup.POST, ToolGroup.WALLET, ToolGroup.APP,
            ToolGroup.TOTP, ToolGroup.NOTES
        ).forEach { group ->
            assertThat(catalogue.filter { all.groupOf(it) == group }).isNotEmpty()
        }
        assertThat(catalogue.filter { all.groupOf(it) == null }).isEmpty()
    }

    @Test
    fun wallet_tools_are_denied_however_the_task_is_granted() {
        val everything = AiTaskPermissions(
            allowReadPage = true,
            allowNavigate = true,
            allowInteract = true,
            allowPost = true
        )
        AgentTools.WALLET_TOOLS.forEach { tool ->
            assertThat(all.groupOf(tool)).isEqualTo(ToolGroup.WALLET)
            assertThat(all.allows(tool)).isFalse()
            assertThat(everything.allows(tool)).isFalse()
        }
        // The refusal has to name the route that DOES work, or the model retries.
        val refusal = everything.refusal(AgentTools.WALLET_APPROVE)
        assertThat(refusal).isNotNull()
        assertThat(refusal).contains(AgentTools.WALLET_APPROVE)
        assertThat(refusal).doesNotContain("unknown tool")
    }

    @Test
    fun app_tools_are_denied_however_the_task_is_granted() {
        val everything = AiTaskPermissions(
            allowReadPage = true,
            allowNavigate = true,
            allowInteract = true,
            allowPost = true
        )
        val appTools = AgentTools.toolDefs().map { it.function.name }
            .filter { it.startsWith("app_") && it !in AgentAppActions.PROFILE_TOOLS }
        assertThat(appTools).isNotEmpty()
        appTools.forEach { tool ->
            assertThat(all.groupOf(tool)).isEqualTo(ToolGroup.APP)
            assertThat(all.allows(tool)).isFalse()
            assertThat(everything.allows(tool)).isFalse()
            // The refusal has to name the route that DOES work, or the model
            // keeps trying the same call in an unattended run.
            val refusal = everything.refusal(tool)
            assertThat(refusal).isNotNull()
            assertThat(refusal).contains(tool)
            assertThat(refusal).doesNotContain("unknown tool")
        }
    }

    /**
     * The 2FA and Notes tools are the one pair a SETTING can hand over, so the
     * property this test pins is the direction of the default: a task grants
     * itself nothing, however it is configured, and the refusal points at the
     * setting instead of reading like a dead end.
     */
    @Test
    fun the_profile_tools_are_denied_by_a_task_and_their_refusal_names_the_setting() {
        val everything = AiTaskPermissions(
            allowReadPage = true,
            allowNavigate = true,
            allowInteract = true,
            allowPost = true
        )
        assertThat(AgentAppActions.PROFILE_TOOLS)
            .containsExactly(AgentTools.APP_2FA, AgentTools.APP_NOTES)
        AgentAppActions.PROFILE_TOOLS.forEach { tool ->
            assertThat(all.groupOf(tool)).isAnyOf(ToolGroup.TOTP, ToolGroup.NOTES)
            assertThat(all.allows(tool)).isFalse()
            assertThat(everything.allows(tool)).isFalse()
            val refusal = everything.refusal(tool)
            assertThat(refusal).isNotNull()
            assertThat(refusal).contains(tool)
            assertThat(refusal).contains("Allow scheduled AI tasks to use 2FA and Notes")
            assertThat(refusal).doesNotContain("unknown tool")
        }
    }

    @Test
    fun a_permitted_tool_has_no_refusal() {
        assertThat(all.refusal(AgentTools.READ_PAGE)).isNull()
        val granted = all.copy(allowPost = true)
        assertThat(granted.refusal(AgentTools.AUTO_POST)).isNull()
    }

    @Test
    fun a_refusal_names_the_group_that_is_switched_off() {
        val noInteract = all.copy(allowInteract = false)
        val message = noInteract.refusal(AgentTools.CLICK)
        assertThat(message).isNotNull()
        assertThat(message).contains(AgentTools.CLICK)
        assertThat(message).contains(ToolGroup.INTERACT.label)
        // Honest about WHY, so the model stops rather than retrying the same call.
        assertThat(message).doesNotContain("unknown tool")
    }

    @Test
    fun a_refusal_for_an_unknown_tool_says_unknown_rather_than_forbidden() {
        val message = all.refusal("run_shell")
        assertThat(message).isEqualTo("unknown tool: run_shell")
    }
}
