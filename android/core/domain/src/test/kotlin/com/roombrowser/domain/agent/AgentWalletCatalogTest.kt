package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The wallet half of the tool catalogue, held to the rules the model (and the
 * confirmation UI) rely on: the tools exist with the right schemas, approval
 * and network switching are interactive, and rejecting deliberately is not.
 */
class AgentWalletCatalogTest {

    @Test
    fun `the wallet tools are all in the catalogue`() {
        val names = AgentTools.toolDefs().map { it.function.name }

        assertThat(names).containsAtLeast(
            AgentTools.WALLET_STATE,
            AgentTools.WALLET_REQUESTS,
            AgentTools.WALLET_APPROVE,
            AgentTools.WALLET_REJECT,
            AgentTools.WALLET_SWITCH_NETWORK
        )
        // Last on purpose: containsAtLeast returns a value, and JUnit4 would
        // then refuse the whole class.
        assertThat(names).contains(AgentTools.WALLET_APPROVE)
    }

    @Test
    fun `approve and switch take an id, read and reject tools need none`() {
        val defs = AgentTools.toolDefs().associate { it.function.name to it.function }

        assertThat(defs.getValue(AgentTools.WALLET_APPROVE).parameters["required"]?.toString())
            .contains("request_id")
        assertThat(defs.getValue(AgentTools.WALLET_REJECT).parameters["required"]?.toString())
            .contains("request_id")
        assertThat(defs.getValue(AgentTools.WALLET_SWITCH_NETWORK).parameters["required"]?.toString())
            .contains("network_id")
        // Read-only tools must not demand an argument the model cannot guess.
        assertThat(defs.getValue(AgentTools.WALLET_STATE).parameters.containsKey("required"))
            .isFalse()
        assertThat(defs.getValue(AgentTools.WALLET_REQUESTS).parameters.containsKey("required"))
            .isFalse()
    }

    @Test
    fun `approval and switching are interactive, rejecting is not`() {
        assertThat(AgentTools.INTERACTIVE_TOOLS).containsAtLeast(
            AgentTools.WALLET_APPROVE,
            AgentTools.WALLET_SWITCH_NETWORK
        )
        // The safe direction must never be behind a gate.
        assertThat(AgentTools.INTERACTIVE_TOOLS).containsNoneOf(
            AgentTools.WALLET_REJECT,
            AgentTools.WALLET_REQUESTS,
            AgentTools.WALLET_STATE
        )
    }

    @Test
    fun `descriptions state the irreversible actions and the safe one`() {
        val defs = AgentTools.toolDefs().associate { it.function.name to it.function.description }

        // The model is told approval is irreversible and is pointed at reject.
        assertThat(defs.getValue(AgentTools.WALLET_APPROVE)).contains("IRREVERSIBLE")
        assertThat(defs.getValue(AgentTools.WALLET_APPROVE)).contains(AgentTools.WALLET_REJECT)
        // ...and that it must list before it approves, and discard afterwards.
        assertThat(defs.getValue(AgentTools.WALLET_APPROVE)).contains(AgentTools.WALLET_REQUESTS)
        assertThat(defs.getValue(AgentTools.WALLET_REJECT)).contains("always allowed")
        // Switching is told it cannot invent a network.
        assertThat(defs.getValue(AgentTools.WALLET_SWITCH_NETWORK)).contains("cannot add or invent")
    }

    @Test
    fun `describeTool names the wallet tools`() {
        assertThat(AgentTools.describeTool(AgentTools.WALLET_STATE, null))
            .isEqualTo("Read wallet state")
        assertThat(AgentTools.describeTool(AgentTools.WALLET_REQUESTS, "{}"))
            .isEqualTo("List pending wallet requests")
        assertThat(
            AgentTools.describeTool(
                AgentTools.WALLET_APPROVE,
                """{"request_id":"12345678-90ab-cdef"}"""
            )
        ).isEqualTo("Approve wallet request 12345678")
        assertThat(AgentTools.describeTool(AgentTools.WALLET_REJECT, "not json"))
            .isEqualTo("Reject wallet request ?")
        assertThat(
            AgentTools.describeTool(AgentTools.WALLET_SWITCH_NETWORK, """{"network_id":"EVM:137"}""")
        ).isEqualTo("Switch network to EVM:137")
    }

    @Test
    fun `the system prompt points the model at the wallet tools`() {
        assertThat(AgentPrompts.DEFAULT).contains(AgentTools.WALLET_REQUESTS)
        assertThat(AgentPrompts.DEFAULT).contains(AgentTools.WALLET_REJECT)
        assertThat(AgentPrompts.DEFAULT).contains(AgentTools.WALLET_SWITCH_NETWORK)
    }
}
