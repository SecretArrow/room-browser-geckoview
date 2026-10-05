package com.roombrowser.agent

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.agent.AgentTools
import org.junit.Test

/**
 * Holds the whole tool catalogue to the two sets [HeadlessToolExecutor] knows
 * about.
 *
 * A scheduled run is offered the same tool list as a chat turn, so a tool added
 * to [AgentTools.toolDefs] and classified nowhere would be offered to the model
 * and then refused as "unknown" — the model retries it, the run burns its step
 * budget, and nothing on screen explains why. Neither mistake is reachable from
 * a test that drives a real engine session, so the classification is a value a
 * test can hold against the catalogue, and this is that test.
 *
 * Every test ends on a VOID-returning Truth call: a Kotlin function whose last
 * expression has a value compiles to a non-void method, and JUnit4 then refuses
 * the whole class instead of running the rest of it. `containsExactly…` and
 * `containsAtLeast…` return `Ordered`, so they are never the last line.
 */
class HeadlessToolCatalogTest {

    @Test
    fun `every catalogue tool is either supported or a tab tool`() {
        val everyTool = AgentTools.toolDefs().map { it.function.name }
        val classified = HeadlessToolExecutor.SUPPORTED_TOOLS + HeadlessToolExecutor.TAB_TOOLS

        assertThat(classified).containsExactlyElementsIn(everyTool)
        assertThat(everyTool).containsNoDuplicates()
    }

    @Test
    fun `no tool is on both sides`() {
        val overlap = HeadlessToolExecutor.SUPPORTED_TOOLS
            .intersect(HeadlessToolExecutor.TAB_TOOLS)

        assertThat(overlap).isEmpty()
    }

    @Test
    fun `the tab tools are exactly the four a single page cannot use`() {
        assertThat(HeadlessToolExecutor.TAB_TOOLS).containsExactly(
            AgentTools.OPEN_NEW_TAB,
            AgentTools.LIST_TABS,
            AgentTools.SWITCH_TAB,
            AgentTools.CLOSE_TAB
        )
        assertThat(HeadlessToolExecutor.SUPPORTED_TOOLS)
            .containsNoneIn(HeadlessToolExecutor.TAB_TOOLS)
    }

    @Test
    fun `every tool that would have to ask the user is supported`() {
        // confirmActions turns these into a refusal, so a missing one would go
        // unrefused in an unattended run — the exact action the switch exists
        // to hold back.
        assertThat(HeadlessToolExecutor.SUPPORTED_TOOLS)
            .containsAtLeastElementsIn(AgentTools.INTERACTIVE_TOOLS)
        assertThat(AgentTools.INTERACTIVE_TOOLS).isNotEmpty()
    }
}
