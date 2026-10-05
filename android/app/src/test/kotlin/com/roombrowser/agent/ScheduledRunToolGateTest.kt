package com.roombrowser.agent

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.domain.agent.ToolExecutor
import com.roombrowser.domain.agent.ToolResult
import com.roombrowser.domain.task.AiTaskPermissions
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The only thing standing between a task and the chat's executor, which has no
 * permission model of its own. A hole here is a task reaching the app's
 * settings or the wallet because it happens to be running in a visible tab, so
 * every class of refusal is asserted against a delegate that records what it
 * was asked to do.
 */
class ScheduledRunToolGateTest {

    private class Recording : ToolExecutor {
        val calls = mutableListOf<String>()
        override suspend fun execute(name: String, argsJson: String): ToolResult {
            calls += name
            return ToolResult(true, "ran $name")
        }
    }

    private fun gate(
        delegate: ToolExecutor,
        permissions: AiTaskPermissions = AiTaskPermissions(),
        confirmActions: Boolean = true
    ) = ScheduledRunToolGate(delegate, permissions, confirmActions)

    @Test
    fun an_app_control_tool_never_reaches_the_delegate() = runBlocking<Unit> {
        val delegate = Recording()
        val result = gate(delegate).execute(AgentTools.APP_SETTINGS, "{}")
        assertThat(result.ok).isFalse()
        assertThat(delegate.calls).isEmpty()
    }

    @Test
    fun a_wallet_tool_never_reaches_the_delegate() = runBlocking<Unit> {
        val delegate = Recording()
        val result = gate(delegate).execute(AgentTools.WALLET_APPROVE, "{}")
        assertThat(result.ok).isFalse()
        assertThat(delegate.calls).isEmpty()
    }

    @Test
    fun a_tab_tool_is_refused_even_when_navigating_is_allowed() = runBlocking<Unit> {
        val delegate = Recording()
        val permissions = AiTaskPermissions(allowNavigate = true)
        val result = gate(delegate, permissions).execute(AgentTools.CLOSE_TAB, "{}")
        assertThat(result.ok).isFalse()
        assertThat(delegate.calls).isEmpty()
    }

    @Test
    fun a_tool_outside_the_tasks_grants_is_refused() = runBlocking<Unit> {
        val delegate = Recording()
        val permissions = AiTaskPermissions(allowInteract = false)
        val result = gate(delegate, permissions).execute(AgentTools.CLICK, """{"ref":1}""")
        assertThat(result.ok).isFalse()
        assertThat(delegate.calls).isEmpty()
    }

    @Test
    fun posting_stays_off_unless_the_task_was_saved_with_it() = runBlocking<Unit> {
        val delegate = Recording()
        val result = gate(delegate).execute(AgentTools.AUTO_POST, """{"text":"hi"}""")
        assertThat(result.ok).isFalse()
        assertThat(delegate.calls).isEmpty()
    }

    @Test
    fun confirm_actions_refuses_the_interactive_tools_it_would_have_to_ask_about() = runBlocking<Unit> {
        val delegate = Recording()
        val result = gate(delegate, confirmActions = true).execute(AgentTools.CLICK, """{"ref":1}""")
        assertThat(result.ok).isFalse()
        assertThat(delegate.calls).isEmpty()
    }

    @Test
    fun the_same_tool_runs_when_the_task_allows_it_and_nothing_has_to_be_asked() = runBlocking<Unit> {
        val delegate = Recording()
        val result = gate(delegate, confirmActions = false).execute(AgentTools.CLICK, """{"ref":1}""")
        assertThat(result.ok).isTrue()
        assertThat(delegate.calls).containsExactly(AgentTools.CLICK)
    }

    @Test
    fun navigating_reaches_the_delegate_when_the_task_allows_it() = runBlocking<Unit> {
        val delegate = Recording()
        val result = gate(delegate).execute(AgentTools.NAVIGATE, """{"url":"https://example.com"}""")
        assertThat(result.ok).isTrue()
        assertThat(result.output).isEqualTo("ran ${AgentTools.NAVIGATE}")
        assertThat(delegate.calls).containsExactly(AgentTools.NAVIGATE)
    }
}
