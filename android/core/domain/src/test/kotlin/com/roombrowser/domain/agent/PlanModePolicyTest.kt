package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlanModePolicyTest {

    @Test
    fun `every refused name is in the catalogue`() {
        val catalogue = AgentTools.toolDefs().map { it.function.name }.toSet()
        assertThat(PlanModePolicy.REFUSED).isNotEmpty()
        assertThat(catalogue).containsAtLeastElementsIn(PlanModePolicy.REFUSED)
    }

    @Test
    fun `reading and navigating are not refused`() {
        val allowed = listOf(
            AgentTools.NAVIGATE, AgentTools.SEARCH_WEB, AgentTools.READ_PAGE,
            AgentTools.SCROLL, AgentTools.WAIT, AgentTools.WAIT_FOR, AgentTools.GO_BACK,
            AgentTools.LIST_TABS, AgentTools.OPEN_NEW_TAB, AgentTools.SWITCH_TAB,
            AgentTools.CLOSE_TAB, AgentTools.WALLET_STATE, AgentTools.WALLET_REQUESTS,
            AgentTools.WALLET_REJECT, AgentTools.APP_OPEN, AgentTools.APP_DATA,
            AgentTools.APP_SETTINGS, AgentTools.APP_NOTES, AgentTools.APP_TABS
        )
        allowed.forEach { name ->
            assertThat(PlanModePolicy.refusal(name, null)).isNull()
        }
    }

    @Test
    fun `everything that can submit is refused, and the refusal names it`() {
        PlanModePolicy.REFUSED.forEach { name ->
            val refusal = PlanModePolicy.refusal(name, null)
            assertThat(refusal).isNotNull()
            assertThat(refusal).contains(name)
        }
    }

    @Test
    fun `run_js is refused although the confirm gate leaves it unasked`() {
        assertThat(AgentTools.INTERACTIVE_TOOLS).doesNotContain(AgentTools.RUN_JS)
        assertThat(PlanModePolicy.refusal(AgentTools.RUN_JS, null)).isNotNull()
    }

    @Test
    fun `app_2fa is refused only when it types into the page`() {
        assertThat(PlanModePolicy.refusal(AgentTools.APP_2FA, "fill")).isNotNull()
        listOf("list", "code", "copy").forEach { action ->
            assertThat(PlanModePolicy.refusal(AgentTools.APP_2FA, action)).isNull()
        }
    }

    @Test
    fun `changing what a site may do is refused, while reading it is not`() {
        listOf("toggle", "clear_site_data").forEach { action ->
            assertThat(PlanModePolicy.refusal(AgentTools.APP_SHIELDS, action)).isNotNull()
        }
        assertThat(PlanModePolicy.refusal(AgentTools.APP_SITE_PERMISSION, "set")).isNotNull()
        assertThat(PlanModePolicy.refusal(AgentTools.APP_SHIELDS, "read")).isNull()
        assertThat(PlanModePolicy.refusal(AgentTools.APP_SITE_PERMISSION, "list")).isNull()
    }

    @Test
    fun `every refused action is one its own tool offers`() {
        // Refusing an action a tool does not have would quietly refuse nothing,
        // and the action arrives from the model, so the names are the contract.
        assertThat(AgentAppActions.SHIELD_ACTIONS)
            .containsAtLeastElementsIn(listOf("toggle", "clear_site_data"))
        assertThat(AgentAppActions.PERMISSION_ACTIONS).containsAtLeastElementsIn(listOf("set"))
        assertThat(AgentAppActions.TOTP_ACTIONS).containsAtLeastElementsIn(listOf("fill"))
    }

    @Test
    fun `an unknown action is left to the executor`() {
        // The app tools refuse an action outside their own enum, so a null or
        // unparsed one must not be turned into a refusal here — that would tell
        // the model the tool exists and is merely switched off.
        assertThat(PlanModePolicy.refusal(AgentTools.APP_SHIELDS, null)).isNull()
        assertThat(PlanModePolicy.refusal(AgentTools.APP_SHIELDS, "not_an_action")).isNull()
    }

    @Test
    fun `an unknown name is left to the executor`() {
        // A typo must not be answered with a mode refusal: that would tell the
        // model the tool exists and is merely switched off here.
        assertThat(PlanModePolicy.refusal("not_a_tool", null)).isNull()
    }
}
