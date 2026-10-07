package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChatOnlyPolicyTest {

    @Test
    fun `every refused name is in the catalogue`() {
        val catalogue = AgentTools.toolDefs().map { it.function.name }.toSet()
        assertThat(ChatOnlyPolicy.REFUSED).isNotEmpty()
        assertThat(catalogue).containsAtLeastElementsIn(ChatOnlyPolicy.REFUSED)
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
            assertThat(ChatOnlyPolicy.refusal(name, null)).isNull()
        }
    }

    @Test
    fun `everything that can submit is refused, and the refusal names it`() {
        ChatOnlyPolicy.REFUSED.forEach { name ->
            val refusal = ChatOnlyPolicy.refusal(name, null)
            assertThat(refusal).isNotNull()
            assertThat(refusal).contains(name)
        }
    }

    @Test
    fun `run_js is refused although the confirm gate leaves it unasked`() {
        assertThat(AgentTools.INTERACTIVE_TOOLS).doesNotContain(AgentTools.RUN_JS)
        assertThat(ChatOnlyPolicy.refusal(AgentTools.RUN_JS, null)).isNotNull()
    }

    @Test
    fun `app_2fa is refused only when it types into the page`() {
        assertThat(ChatOnlyPolicy.refusal(AgentTools.APP_2FA, "fill")).isNotNull()
        listOf("list", "code", "copy").forEach { action ->
            assertThat(ChatOnlyPolicy.refusal(AgentTools.APP_2FA, action)).isNull()
        }
    }

    @Test
    fun `an unknown name is left to the executor`() {
        // A typo must not be answered with a mode refusal: that would tell the
        // model the tool exists and is merely switched off here.
        assertThat(ChatOnlyPolicy.refusal("not_a_tool", null)).isNull()
    }
}
