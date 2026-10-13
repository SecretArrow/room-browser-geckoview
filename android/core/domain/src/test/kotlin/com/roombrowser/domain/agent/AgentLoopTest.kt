package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Fake provider transport with a scripted sequence of assistant turns. */
private class FakeGateway(
    private val turns: List<ChatMessage>,
    private val onChat: (ChatRequest) -> Unit = {}
) : AgentGateway {
    var calls = 0
    val requests = mutableListOf<ChatRequest>()

    override suspend fun chat(request: ChatRequest, events: suspend (StreamEvent) -> Unit): ChatMessage {
        calls++
        requests.add(request)
        onChat(request)
        val message = turns.getOrElse(calls - 1) { turns.last() }
        message.content?.let { events(StreamEvent.Text(it)) }
        return message
    }

    override suspend fun listModels(): List<String> = listOf("fake-model")
}

private class FakeExecutor(private val results: Map<String, ToolResult> = emptyMap()) : ToolExecutor {
    val executed = mutableListOf<Pair<String, String>>()
    override suspend fun execute(name: String, argsJson: String): ToolResult {
        executed.add(name to argsJson)
        return results[name] ?: ToolResult(true, "ok:$name")
    }

    fun toolCall(id: String, name: String, args: String): ChatMessage =
        ChatMessage(
            role = "assistant",
            toolCalls = listOf(ToolCall(id = id, function = FunctionCall(name, args)))
        )
}

class AgentLoopTest {

    private fun config(maxSteps: Int = 8) = AgentConfig(
        model = "test-model",
        maxSteps = maxSteps,
        temperature = 0.0,
        systemPrompt = "sys"
    )

    @Test
    fun `direct answer without tool calls`() = runTest {
        val gateway = FakeGateway(listOf(ChatMessage(role = "assistant", content = "Hello!")))
        val loop = AgentLoop(gateway, FakeExecutor(), config())
        val history = mutableListOf(
            ChatMessage(role = "system", content = "sys"),
            ChatMessage(role = "user", content = "hi")
        )
        val events = mutableListOf<AgentEvent>()
        loop.runTurn(history) { events.add(it) }

        assertThat(gateway.calls).isEqualTo(1)
        assertThat(events.filterIsInstance<AgentEvent.AssistantText>().map { it.text }.joinToString(""))
            .isEqualTo("Hello!")
        assertThat(events.last()).isInstanceOf(AgentEvent.FinalAnswer::class.java)
        assertThat((events.last() as AgentEvent.FinalAnswer).text).isEqualTo("Hello!")
        assertThat(history).hasSize(3) // system + user + assistant
    }

    @Test
    fun `tool call is executed and result fed back`() = runTest {
        val navigateTurn = ChatMessage(
            role = "assistant",
            toolCalls = listOf(
                ToolCall(id = "call_1", function = FunctionCall(AgentTools.NAVIGATE, """{"url":"https://example.com"}"""))
            )
        )
        val finalTurn = ChatMessage(role = "assistant", content = "Done, page opened.")
        val executor = FakeExecutor()
        val gateway = FakeGateway(listOf(navigateTurn, finalTurn))
        val loop = AgentLoop(gateway, executor, config())

        val history = mutableListOf(
            ChatMessage(role = "system", content = "sys"),
            ChatMessage(role = "user", content = "open example.com")
        )
        val events = mutableListOf<AgentEvent>()
        loop.runTurn(history) { events.add(it) }

        assertThat(executor.executed).containsExactly(
            AgentTools.NAVIGATE to """{"url":"https://example.com"}"""
        )
        assertThat(gateway.calls).isEqualTo(2)

        // Second request must contain the assistant tool_call message + tool result
        val second = gateway.requests[1]
        val roles = second.messages.map { it.role }
        assertThat(roles).containsAtLeast("system", "user", "assistant", "tool")
        val toolMsg = second.messages.first { it.role == "tool" }
        assertThat(toolMsg.toolCallId).isEqualTo("call_1")
        assertThat(toolMsg.content).isEqualTo("ok:navigate")

        assertThat(events.filterIsInstance<AgentEvent.ToolStarted>().map { it.name })
            .containsExactly(AgentTools.NAVIGATE)
        assertThat(events.filterIsInstance<AgentEvent.ToolFinished>().map { it.ok }).containsExactly(true)
        assertThat(events.last()).isInstanceOf(AgentEvent.FinalAnswer::class.java)
    }

    @Test
    fun `tool crash becomes an error result not a loop crash`() = runTest {
        val boom = object : ToolExecutor {
            override suspend fun execute(name: String, argsJson: String): ToolResult =
                throw IllegalStateException("webview gone")
        }
        val navigateTurn = ChatMessage(
            role = "assistant",
            toolCalls = listOf(ToolCall(id = "c1", function = FunctionCall(AgentTools.READ_PAGE, "{}")))
        )
        val finalTurn = ChatMessage(role = "assistant", content = "Could not read the page.")
        val gateway = FakeGateway(listOf(navigateTurn, finalTurn))
        val loop = AgentLoop(gateway, boom, config())
        val history = mutableListOf(ChatMessage(role = "user", content = "read"))
        val events = mutableListOf<AgentEvent>()

        loop.runTurn(history) { events.add(it) }

        val finished = events.filterIsInstance<AgentEvent.ToolFinished>()
        assertThat(finished).hasSize(1)
        assertThat(finished[0].ok).isFalse()
        assertThat(finished[0].summary).contains("webview gone")
        // The error content was still delivered to the model as a tool result.
        val toolMsg = gateway.requests[1].messages.first { it.role == "tool" }
        assertThat(toolMsg.content).startsWith("ERROR: tool crashed")
    }

    @Test
    fun `step limit forces a final tool-less answer`() = runTest {
        val loopTurn = ChatMessage(
            role = "assistant",
            toolCalls = listOf(ToolCall(id = "c", function = FunctionCall(AgentTools.READ_PAGE, "{}")))
        )
        val gateway = FakeGateway(
            listOf(loopTurn, loopTurn, ChatMessage(role = "assistant", content = "Final summary."))
        )
        val loop = AgentLoop(gateway, FakeExecutor(), config(maxSteps = 2))
        val history = mutableListOf(ChatMessage(role = "user", content = "go"))
        val events = mutableListOf<AgentEvent>()

        loop.runTurn(history) { events.add(it) }

        // maxSteps=2 → step 1 carried tools; the last in-loop attempt (step 2)
        // and the forced final request had none.
        assertThat(gateway.requests[0].tools).isNotNull()
        assertThat(gateway.requests[1].tools).isNull()
        assertThat(gateway.requests[2].tools).isNull()
        assertThat(events.filterIsInstance<AgentEvent.Notice>()).isNotEmpty()
        assertThat(events.last()).isInstanceOf(AgentEvent.FinalAnswer::class.java)
    }

    @Test
    fun `the step budget is clamped to the guard rails`() {
        assertThat(config().steps).isEqualTo(8)
        // A stored value below the floor or above the ceiling is clamped, not
        // trusted: the ceiling is what stops a typed or migrated number from
        // turning one turn into an unbounded run of provider calls.
        assertThat(config(maxSteps = 0).steps).isEqualTo(AgentConfig.MIN_STEPS)
        assertThat(config(maxSteps = -4).steps).isEqualTo(AgentConfig.MIN_STEPS)
        assertThat(config(maxSteps = AgentConfig.MAX_STEPS + 1).steps)
            .isEqualTo(AgentConfig.MAX_STEPS)
        // The default is the one a fresh profile gets, and it has to survive
        // the clamp or the setting would mean nothing.
        assertThat(AgentConfig(model = "m", systemPrompt = "s").steps)
            .isEqualTo(AgentConfig.DEFAULT_STEPS)
    }

    @Test
    fun `a tool that failed twice is answered with a correction, not run again`() = runTest {
        val executor = FakeExecutor(mapOf(AgentTools.CLICK to ToolResult(false, "no such element")))
        val turns = List(4) { executor.toolCall("c$it", AgentTools.CLICK, """{"ref":3}""") } +
            listOf(ChatMessage(role = "assistant", content = "Giving up."))
        val gateway = FakeGateway(turns)
        val loop = AgentLoop(gateway, executor, config(maxSteps = 5))
        val history = mutableListOf(ChatMessage(role = "user", content = "click it"))
        val events = mutableListOf<AgentEvent>()

        loop.runTurn(history) { events.add(it) }

        // Steps 1 and 2 ran and failed; from then on the call is refused before
        // it reaches the executor, so the page is not touched again.
        assertThat(executor.executed).hasSize(2)
        val summaries = events.filterIsInstance<AgentEvent.ToolFinished>().map { it.summary }
        assertThat(summaries[0]).contains("no such element")
        assertThat(summaries[2]).contains("failed 2 times in a row")
        assertThat(history.last().content).isEqualTo("Giving up.")
    }

    @Test
    fun `provider failure surfaces as fatal agent error`() = runTest {
        val gateway = object : AgentGateway {
            override suspend fun chat(request: ChatRequest, events: suspend (StreamEvent) -> Unit): ChatMessage =
                throw AgentHttpException(401, "invalid api key")

            override suspend fun listModels(): List<String> = emptyList()
        }
        val loop = AgentLoop(gateway, FakeExecutor(), config())
        val history = mutableListOf(ChatMessage(role = "user", content = "hi"))
        val events = mutableListOf<AgentEvent>()
        loop.runTurn(history) { events.add(it) }

        val error = events.filterIsInstance<AgentEvent.AgentError>().single()
        assertThat(error.fatal).isTrue()
        assertThat(error.message).contains("401")
    }

    @Test
    fun `long tool results are truncated for the model`() = runTest {
        val executor = object : ToolExecutor {
            override suspend fun execute(name: String, argsJson: String): ToolResult =
                ToolResult(true, "x".repeat(100_000))
        }
        val toolTurn = ChatMessage(
            role = "assistant",
            toolCalls = listOf(ToolCall(id = "c", function = FunctionCall(AgentTools.READ_PAGE, "{}")))
        )
        val gateway = FakeGateway(listOf(toolTurn, ChatMessage(role = "assistant", content = "ok")))
        val loop = AgentLoop(gateway, executor, config())
        val history = mutableListOf(ChatMessage(role = "user", content = "go"))
        loop.runTurn(history) { }

        val toolMsg = gateway.requests[1].messages.first { it.role == "tool" }
        assertThat(toolMsg.content!!.length).isEqualTo(AgentLoop.MAX_TOOL_RESULT_CHARS)
    }

    @Test
    fun `the user-visible tool summary never carries the fence`() = runTest {
        val page = "Sign in to continue."
        val executor = object : ToolExecutor {
            override suspend fun execute(name: String, argsJson: String): ToolResult =
                ToolResult(true, UntrustedContent.wrap(page))
        }
        val toolTurn = ChatMessage(
            role = "assistant",
            toolCalls = listOf(ToolCall(id = "c", function = FunctionCall(AgentTools.READ_PAGE, "{}")))
        )
        val gateway = FakeGateway(listOf(toolTurn, ChatMessage(role = "assistant", content = "ok")))
        val loop = AgentLoop(gateway, executor, config())
        val events = mutableListOf<AgentEvent>()

        loop.runTurn(mutableListOf(ChatMessage(role = "user", content = "go"))) { events.add(it) }

        assertThat(events.filterIsInstance<AgentEvent.ToolFinished>().single().summary)
            .isEqualTo(page)
        // The model's copy keeps the fence: that half is the defence.
        val toolMsg = gateway.requests[1].messages.first { it.role == "tool" }
        assertThat(toolMsg.content).contains(UntrustedContent.BEGIN)
    }

    @Test
    fun `history trimming keeps the system message and newest turns`() = runTest {
        val loopTurn = ChatMessage(
            role = "assistant",
            toolCalls = listOf(ToolCall(id = "c", function = FunctionCall(AgentTools.READ_PAGE, "{}")))
        )
        val gateway = FakeGateway(listOf(loopTurn, ChatMessage(role = "assistant", content = "done")))
        val loop = AgentLoop(gateway, FakeExecutor(), config())
        val history = mutableListOf<ChatMessage>()
        history.add(ChatMessage(role = "system", content = "sys"))
        repeat(60) { history.add(ChatMessage(role = "user", content = "msg$it")) }

        loop.runTurn(history) { }
        assertThat(history.size).isAtMost(AgentLoop.MAX_HISTORY + 4)
        assertThat(history.first().role).isEqualTo("system")
    }
}
