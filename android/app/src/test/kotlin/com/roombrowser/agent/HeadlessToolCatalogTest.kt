package com.roombrowser.agent

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.agent.AgentAppActions
import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.domain.plugins.PluginToolNaming
import com.roombrowser.domain.task.AiTaskPermissions
import com.roombrowser.domain.task.ToolGroup
import org.junit.Test

/**
 * Holds the whole tool catalogue to the five sets [HeadlessToolExecutor] knows
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
    fun `every catalogue tool is supported, a tab tool, an app tool or a wallet tool`() {
        val everyTool = AgentTools.toolDefs().map { it.function.name }
        val classified = HeadlessToolExecutor.SUPPORTED_TOOLS +
            HeadlessToolExecutor.TAB_TOOLS +
            HeadlessToolExecutor.DOWNLOAD_TOOLS +
            HeadlessToolExecutor.PDF_TOOLS +
            HeadlessToolExecutor.APP_TOOLS +
            HeadlessToolExecutor.DEVELOPER_TOOLS +
            AgentTools.WALLET_TOOLS

        assertThat(classified).containsExactlyElementsIn(everyTool)
        assertThat(everyTool).containsNoDuplicates()
    }

    @Test
    fun `no tool is on two sides`() {
        val sets = listOf(
            HeadlessToolExecutor.SUPPORTED_TOOLS,
            HeadlessToolExecutor.TAB_TOOLS,
            HeadlessToolExecutor.DOWNLOAD_TOOLS,
            HeadlessToolExecutor.PDF_TOOLS,
            HeadlessToolExecutor.APP_TOOLS,
            HeadlessToolExecutor.DEVELOPER_TOOLS,
            AgentTools.WALLET_TOOLS
        )

        sets.forEachIndexed { i, a ->
            sets.drop(i + 1).forEach { b ->
                assertThat(a.intersect(b)).isEmpty()
            }
        }
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
    fun `the app tools are exactly the vocabulary that drives the browser itself`() {
        assertThat(HeadlessToolExecutor.APP_TOOLS).containsExactlyElementsIn(AgentAppActions.TOOLS)
        assertThat(HeadlessToolExecutor.SUPPORTED_TOOLS)
            .containsNoneIn(HeadlessToolExecutor.APP_TOOLS)
    }

    @Test
    fun `every tool that would have to ask the user is classified`() {
        // confirmActions turns these into a refusal, so a missing one would go
        // unrefused in an unattended run — the exact action the switch exists
        // to hold back. The app and wallet tools answer differently (a run
        // cannot use them at all), which is why the union is the set to check.
        val classified = HeadlessToolExecutor.SUPPORTED_TOOLS +
            HeadlessToolExecutor.DOWNLOAD_TOOLS +
            HeadlessToolExecutor.PDF_TOOLS +
            HeadlessToolExecutor.APP_TOOLS +
            HeadlessToolExecutor.DEVELOPER_TOOLS +
            AgentTools.WALLET_TOOLS
        assertThat(classified).containsAtLeastElementsIn(AgentTools.INTERACTIVE_TOOLS)
        assertThat(AgentTools.INTERACTIVE_TOOLS).isNotEmpty()
    }

    @Test
    fun `the file-writing tools are the ones a run has nowhere to put`() {
        // A run has a hidden page and no download engine, so a file it produced
        // would land in the user's Downloads unwatched. It is refused rather
        // than silently performed — and it must NOT be on the supported list,
        // or the refusal would never be reached.
        assertThat(HeadlessToolExecutor.DOWNLOAD_TOOLS).containsExactly(AgentTools.DOWNLOAD_FILE)
        assertThat(HeadlessToolExecutor.PDF_TOOLS).containsExactly(AgentTools.SAVE_PDF)
        assertThat(HeadlessToolExecutor.SUPPORTED_TOOLS)
            .containsNoneIn(HeadlessToolExecutor.DOWNLOAD_TOOLS + HeadlessToolExecutor.PDF_TOOLS)
        assertThat(HeadlessToolExecutor.TAB_TOOLS)
            .containsNoneIn(HeadlessToolExecutor.DOWNLOAD_TOOLS + HeadlessToolExecutor.PDF_TOOLS)
    }

    @Test
    fun `a plugin tool is classified refused, never unknown`() {
        // Plugin tools join the catalogue per chat, never through
        // [AgentTools.toolDefs], so the static sets above say nothing about
        // them — and a scheduled run that is offered one anyway (a model can
        // ask for a tool it was never given) must meet a REFUSAL that says
        // what it is, because "unknown tool" sends it hunting for a route
        // that does not exist and it retries until the step budget is gone.
        val name = PluginToolNaming.pluginToolName("com.example.hello", "count_words")!!
        assertThat(AgentTools.toolDefs().map { it.function.name }).containsNoneIn(listOf(name))
        assertThat(AiTaskPermissions.DEFAULT.groupOf(name)).isEqualTo(ToolGroup.NODE)
        val refusal = AiTaskPermissions.DEFAULT.refusal(name)
        assertThat(refusal).isNotNull()
        assertThat(refusal).contains("chat plugin")
        assertThat(refusal).doesNotContain("unknown tool")
    }
}
