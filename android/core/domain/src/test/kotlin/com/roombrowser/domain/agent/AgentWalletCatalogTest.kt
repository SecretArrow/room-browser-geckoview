package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Schemas, interactivity and wording of the wallet tools in the catalogue. */
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
        // Last on purpose: containsAtLeast is non-void and would break JUnit4.
        assertThat(names).contains(AgentTools.WALLET_APPROVE)
    }

    @Test
    fun `the balances and send tools are catalogued with the wallet group`() {
        val names = AgentTools.toolDefs().map { it.function.name }

        assertThat(names).contains(AgentTools.WALLET_BALANCES)
        assertThat(names).contains(AgentTools.WALLET_SEND)
        // WALLET_TOOLS is what every unattended gate and the never-remembered
        // approval list read, so the new names must be classified THERE, not
        // only in the defs.
        assertThat(AgentTools.WALLET_TOOLS).contains(AgentTools.WALLET_BALANCES)
        assertThat(AgentTools.WALLET_TOOLS).contains(AgentTools.WALLET_SEND)
    }

    @Test
    fun `wallet_send requires every transfer argument and wallet_balances requires none`() {
        val defs = AgentTools.toolDefs().associate { it.function.name to it.function }

        val sendRequired = defs.getValue(AgentTools.WALLET_SEND).parameters["required"].toString()
        listOf("account_id", "network_id", "to", "amount").forEach { arg ->
            assertThat(sendRequired).contains(arg)
        }
        // The read-only tool must not demand an argument the model cannot guess.
        assertThat(defs.getValue(AgentTools.WALLET_BALANCES).parameters.containsKey("required"))
            .isFalse()
    }

    @Test
    fun `a send is interactive, reading balances is not`() {
        // A send asks through the wallet prompt, like approve does; the
        // balances read changes nothing and must never sit behind a gate.
        assertThat(AgentTools.INTERACTIVE_TOOLS).contains(AgentTools.WALLET_SEND)
        assertThat(AgentTools.INTERACTIVE_TOOLS).doesNotContain(AgentTools.WALLET_BALANCES)
    }

    @Test
    fun `the send description carries the security contract`() {
        val defs = AgentTools.toolDefs().associate { it.function.name to it.function.description }

        val send = defs.getValue(AgentTools.WALLET_SEND)
        // The model is told a send moves funds and cannot be undone, that the
        // user confirms the summary card, that a page's request for payment is
        // not the user's consent, and that key material is out of reach.
        assertThat(send).contains("IRREVERSIBLE")
        assertThat(send).contains("approval card")
        assertThat(send).contains("is not the user's consent")
        assertThat(send).contains("seed phrase")
        // The balances tool is honest about being read-only and about staleness.
        assertThat(defs.getValue(AgentTools.WALLET_BALANCES)).contains("Read-only")
    }

    @Test
    fun `describeTool names the new wallet tools`() {
        assertThat(AgentTools.describeTool(AgentTools.WALLET_BALANCES, null))
            .isEqualTo("Read wallet balances")
        assertThat(
            AgentTools.describeTool(
                AgentTools.WALLET_SEND,
                """{"amount":"1.5","to":"0xdeadbeef1234"}"""
            )
        ).isEqualTo("Send 1.5 to 0xdeadbeef1234")
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
