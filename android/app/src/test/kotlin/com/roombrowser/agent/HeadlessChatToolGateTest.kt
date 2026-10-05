package com.roombrowser.agent

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.domain.agent.ToolExecutor
import com.roombrowser.domain.agent.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * What a headless chat may reach. The page tools are the point of the mode, so
 * a hole that lets a wallet signature or a tab operation through would be a
 * hidden page doing something the user cannot see — and the app's own tools
 * must keep working, because they are about the browser rather than the page.
 */
class HeadlessChatToolGateTest {

    private class Recording : ToolExecutor {
        val calls = mutableListOf<String>()
        override suspend fun execute(name: String, argsJson: String): ToolResult {
            calls += name
            return ToolResult(true, "page ran $name")
        }
    }

    @Test
    fun a_wallet_tool_is_refused_without_reaching_the_page() = runBlocking<Unit> {
        val page = Recording()
        val result = HeadlessChatToolGate(page).execute(AgentTools.WALLET_APPROVE, "{}")
        assertThat(result.ok).isFalse()
        assertThat(result.output).contains("hidden page")
        assertThat(page.calls).isEmpty()
    }

    @Test
    fun a_tab_tool_is_refused_without_reaching_the_page() = runBlocking<Unit> {
        val page = Recording()
        val result = HeadlessChatToolGate(page).execute(AgentTools.OPEN_NEW_TAB, """{"url":"https://a.example"}""")
        assertThat(result.ok).isFalse()
        assertThat(result.output).contains("no tabs")
        assertThat(page.calls).isEmpty()
    }

    @Test
    fun an_app_tool_runs_through_the_app_and_never_touches_the_page() = runBlocking<Unit> {
        val page = Recording()
        val asked = mutableListOf<String>()
        val gate = HeadlessChatToolGate(page) { name, _ ->
            asked += name
            ToolResult(true, "app ran $name")
        }
        val result = gate.execute(AgentTools.APP_SETTINGS, "{}")
        assertThat(result.ok).isTrue()
        assertThat(result.output).isEqualTo("app ran ${AgentTools.APP_SETTINGS}")
        assertThat(asked).containsExactly(AgentTools.APP_SETTINGS)
        assertThat(page.calls).isEmpty()
    }

    @Test
    fun a_page_tool_reaches_the_page() = runBlocking<Unit> {
        val page = Recording()
        // The app's tools answer null for a name that is not theirs — the same
        // contract AgentAppTools has.
        val gate = HeadlessChatToolGate(page) { _, _ -> null }
        val result = gate.execute(AgentTools.READ_PAGE, "{}")
        assertThat(result.ok).isTrue()
        assertThat(page.calls).containsExactly(AgentTools.READ_PAGE)
    }

    @Test
    fun a_page_bound_app_tool_is_refused_even_though_the_app_would_answer_it() = runBlocking<Unit> {
        val page = Recording()
        val asked = mutableListOf<String>()
        val gate = HeadlessChatToolGate(page) { name, _ ->
            asked += name
            ToolResult(true, "app ran $name")
        }
        val result = gate.execute(AgentTools.APP_PAGE, """{"action":"find","text":"x"}""")
        assertThat(result.ok).isFalse()
        assertThat(result.output).contains("hidden")
        // The point: the app lambda is never reached, so the find cannot land
        // on the tab the user is looking at.
        assertThat(asked).isEmpty()
        assertThat(page.calls).isEmpty()
    }

    @Test
    fun a_browser_level_app_tool_still_runs() = runBlocking<Unit> {
        val page = Recording()
        val gate = HeadlessChatToolGate(page) { name, _ -> ToolResult(true, "app ran $name") }
        assertThat(gate.execute(AgentTools.APP_DATA, """{"action":"list"}""").ok).isTrue()
        assertThat(page.calls).isEmpty()
    }
}
