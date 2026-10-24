package com.roombrowser.agent

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.agent.AgentPermissions
import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.domain.agent.PlanModePolicy
import com.roombrowser.domain.agent.ToolExecutor
import com.roombrowser.domain.agent.ToolResult
import com.roombrowser.domain.task.AiTaskPermissions
import com.roombrowser.domain.task.ToolGroup
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The skills tools' gates, pinned at the layers that own them — the same
 * split AgentWalletSendTest uses, because the chat executor itself needs a
 * live browser (its constructor takes a BrowserViewModel) and cannot exist
 * on this JVM:
 *
 *  - the CONFIRM gate on the chat route is [AgentTools.INTERACTIVE_TOOLS]
 *    membership — that membership is precisely what the executor's shared
 *    confirm chain keys off (it is how node_packages asks, and the skills
 *    branch calls `refuse(...)` before it touches the access), so a denied
 *    answer never reaches [AgentSkillsAccess];
 *  - the PERMISSION gate is [AgentPermissions.refusal], pinned to name the
 *    `agent.skills` axis the settings row flips;
 *  - PLAN mode and the UNATTENDED routes (scheduled runs, headless chat
 *    turns) are refused by the policy objects and the two gates those run
 *    through, and every one of those refusals is pinned here with a
 *    recording delegate that proves the tool never ran.
 *
 * Every test ends on a VOID-returning Truth call; the contains* families
 * return `Ordered` and would make the method non-void, which JUnit4 refuses.
 */
class AgentSkillsGateTest {

    /** Records every tool that reaches it — the proof a gate held. */
    private class Recording : ToolExecutor {
        val calls = mutableListOf<String>()
        override suspend fun execute(name: String, argsJson: String): ToolResult {
            calls += name
            return ToolResult(true, "ran $name")
        }
    }

    private val four = listOf(
        AgentTools.SKILLS_LIST, AgentTools.SKILLS_INSTALL,
        AgentTools.SKILLS_READ, AgentTools.SKILLS_REMOVE
    )

    // ------------------------------------------------------------- the catalogue

    @Test
    fun `the four skills tools are in the catalogue exactly once each`() {
        val defs = AgentTools.toolDefs().map { it.function.name }
        for (name in four) {
            assertThat(defs.count { it == name }).isEqualTo(1)
            assertThat(AgentTools.describeTool(name, "{}")).isNotEqualTo(name)
        }
        assertThat(AgentTools.SKILLS_TOOLS).containsExactlyElementsIn(four)
        assertThat(AgentTools.describeTool(AgentTools.SKILLS_INSTALL, """{"spec":"owner/repo"}"""))
            .contains("owner/repo")
    }

    // ------------------------------------------------------------- the confirm gate

    @Test
    fun `install and remove are interactive, list and read never ask`() {
        // INTERACTIVE_TOOLS membership IS the chat route's confirm gate: the
        // executor asks through confirmGate for exactly the names in this
        // set (YOLO flips it off deliberately — the same answer node_packages
        // and node_run live with). Reads must NOT be in it: asking before a
        // read trains the user to tap through prompts.
        assertThat(AgentTools.INTERACTIVE_TOOLS).contains(AgentTools.SKILLS_INSTALL)
        assertThat(AgentTools.INTERACTIVE_TOOLS).contains(AgentTools.SKILLS_REMOVE)
        assertThat(AgentTools.INTERACTIVE_TOOLS).doesNotContain(AgentTools.SKILLS_LIST)
        assertThat(AgentTools.INTERACTIVE_TOOLS).doesNotContain(AgentTools.SKILLS_READ)
        // Same classification as the npm install it mirrors.
        assertThat(AgentTools.INTERACTIVE_TOOLS).contains(AgentTools.NODE_PACKAGES)
    }

    // ------------------------------------------------------------- the permission gate

    @Test
    fun `a withheld agent skills permission refuses all four tools and names the axis`() {
        for (tool in four) {
            assertThat(AgentPermissions.required(tool)).containsExactly(AgentPermissions.AGENT_SKILLS)
            assertThat(AgentPermissions.granted(tool, setOf(AgentPermissions.AGENT_SKILLS))).isFalse()

            val refusal = AgentPermissions.refusal(tool, setOf(AgentPermissions.AGENT_SKILLS))
            assertThat(refusal).isNotNull()
            assertThat(refusal).contains(tool)
            // The sentence names the axis by its LABEL — the string the
            // settings screen shows next to the switch — so the user can
            // find what to grant; the raw "agent.skills" id is for profiles
            // and manifests, not for people.
            assertThat(refusal).contains(AgentPermissions.label(AgentPermissions.AGENT_SKILLS))

            // Granted: every tool answers normally.
            assertThat(AgentPermissions.refusal(tool, emptySet())).isNull()
        }
    }

    // ------------------------------------------------------------- plan mode

    @Test
    fun `plan mode refuses install and remove but the reads stay legal`() {
        // A skill install fetches and writes; a removal deletes — both are
        // work a plan promises to propose, not do. The reads are the whole
        // point of the mode: the instructions are what the plan is ABOUT.
        for (tool in listOf(AgentTools.SKILLS_INSTALL, AgentTools.SKILLS_REMOVE)) {
            val refusal = PlanModePolicy.refusal(tool, action = null)
            assertThat(refusal).isNotNull()
            assertThat(refusal).contains(tool)
            assertThat(refusal).contains("Plan mode")
        }
        assertThat(PlanModePolicy.refusal(AgentTools.SKILLS_LIST, action = null)).isNull()
        assertThat(PlanModePolicy.refusal(AgentTools.SKILLS_READ, action = null)).isNull()
    }

    // ------------------------------------------------------------- scheduled runs

    @Test
    fun `a scheduled run refuses all four skills tools and never reaches the delegate`() =
        runBlocking<Unit> {
            val delegate = Recording()
            val gate = ScheduledRunToolGate(delegate, AiTaskPermissions(), confirmActions = false)
            for (tool in four) {
                val result = gate.execute(tool, "{}")
                assertThat(result.ok).isFalse()
                assertThat(result.output).contains(tool)
                // "unknown tool" would send the model hunting for a route.
                assertThat(result.output).doesNotContain("unknown tool")
            }
            assertThat(delegate.calls).isEmpty()

            // And the classification behind it: even a task granted
            // everything reaches no skills tool.
            val everything = AiTaskPermissions(allowReadPage = true, allowNavigate = true, allowInteract = true, allowPost = true)
            for (tool in four) {
                assertThat(everything.groupOf(tool)).isEqualTo(ToolGroup.SKILLS)
                assertThat(everything.allows(tool)).isFalse()
                assertThat(everything.refusal(tool)).contains("scheduled run")
            }
        }

    // ------------------------------------------------------------- headless chat turns

    @Test
    fun `a headless chat turn refuses all four skills tools and never reaches the page`() =
        runBlocking<Unit> {
            val page = Recording()
            val gate = HeadlessChatToolGate(page)
            for (tool in four) {
                val result = gate.execute(tool, "{}")
                assertThat(result.ok).isFalse()
                // The truth for THIS route: it is a chat, just not one whose
                // tab is on screen — the wording says so rather than lying
                // with the scheduled run's "run this as a chat".
                assertThat(result.output).contains("browser tab")
            }
            assertThat(page.calls).isEmpty()
        }

    // ------------------------------------------------------------- the /skills chat command

    @Test
    fun `the chat remove command is inert until its literal confirm word`() {
        // The command's own gate, parsed before anything touches the store:
        // without the trailing `confirm`, parsing yields an UNconfirmed shape
        // and the handler answers with how to confirm — nothing is removed.
        val unconfirmed = SkillChatCommand.parse("remove binance-agentic-wallet")
        assertThat(unconfirmed).isEqualTo(SkillChatCommand.Remove("binance-agentic-wallet", confirmed = false))

        val confirmed = SkillChatCommand.parse("remove binance-agentic-wallet confirm")
        assertThat(confirmed).isEqualTo(SkillChatCommand.Remove("binance-agentic-wallet", confirmed = true))

        // The other shapes, pinned so a rewording cannot silently break them.
        assertThat(SkillChatCommand.parse("")).isEqualTo(SkillChatCommand.List)
        assertThat(SkillChatCommand.parse("add owner/repo/skills/x"))
            .isEqualTo(SkillChatCommand.Add("owner/repo/skills/x"))
        assertThat(SkillChatCommand.parse("disable some-skill"))
            .isEqualTo(SkillChatCommand.SetEnabled("some-skill", enabled = false))
        assertThat(SkillChatCommand.parse("nonsense words"))
            .isEqualTo(SkillChatCommand.Unknown("nonsense words"))
    }
}
