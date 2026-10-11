package com.roombrowser.domain.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/** Streaming events emitted while the provider is generating a turn. */
sealed interface StreamEvent {
    /** A visible text token of the assistant message. */
    data class Text(val text: String) : StreamEvent

    /** A reasoning/"thinking" token (e.g. GLM `reasoning_content`). */
    data class Thinking(val text: String) : StreamEvent
}

/**
 * Provider transport implemented by the app layer (OkHttp + SSE).
 *
 * [chat] streams the assistant turn and returns the FINAL assembled
 * assistant message (text + tool calls).
 */
interface AgentGateway {
    suspend fun chat(
        request: ChatRequest,
        events: suspend (StreamEvent) -> Unit
    ): ChatMessage

    /** Lists model ids from the provider's /models endpoint. */
    suspend fun listModels(): List<String>
}

/** Executes one browser action; returns a plain-text result for the model. */
interface ToolExecutor {
    suspend fun execute(name: String, argsJson: String): ToolResult
}

data class ToolResult(val ok: Boolean, val output: String) {
    fun asMessageContent(): String = (if (ok) "" else "ERROR: ") + output
}

data class AgentConfig(
    val model: String,
    val maxSteps: Int = 25,
    val temperature: Double = 0.2,
    val systemPrompt: String
)

/** Events surfaced to the chat UI. */
sealed interface AgentEvent {
    data class AssistantText(val text: String) : AgentEvent
    data class AssistantThinking(val text: String) : AgentEvent
    data class ToolStarted(val id: String, val name: String, val argsJson: String) : AgentEvent
    data class ToolFinished(val id: String, val name: String, val ok: Boolean, val summary: String) : AgentEvent
    data class FinalAnswer(val text: String) : AgentEvent
    data class AgentError(val message: String, val fatal: Boolean) : AgentEvent
    data class Notice(val text: String) : AgentEvent
}

/**
 * The agent loop (plan → act → observe → repeat):
 *
 *  1. send conversation + tool catalogue to the provider (streaming)
 *  2. stream text/thinking tokens to the UI while they arrive
 *  3. if the model requested tool calls → execute them, append results,
 *     go to 1 (bounded by [AgentConfig.maxSteps])
 *  4. when the model answers without tool calls → done (FinalAnswer)
 *  5. when the step budget is exhausted → one final request WITHOUT tools
 *     forces a plain answer
 *
 * The loop is pure JVM logic with no Android dependency — fully unit tested
 * with fake gateways/executors.
 */
class AgentLoop(
    private val gateway: AgentGateway,
    private val tools: ToolExecutor,
    private val config: AgentConfig,
    /**
     * Runs before every step and MAY SUSPEND. This is where an attended turn
     * waits: a Pause is honoured here, between steps, and never inside a tool
     * that has already started. Default: no wait.
     */
    private val beforeStep: suspend () -> Unit = {},
    /**
     * Checked between steps. When it turns true the turn ends with a notice
     * instead of continuing — the action budget uses it for a hard stop.
     */
    private val stopRequested: () -> Boolean = { false },
    /** The sentence shown when [stopRequested] ends the turn. */
    private val stopReason: () -> String = { "Stopped." }
) {

    /**
     * Runs one user turn. [history] is MUTATED in place (assistant and tool
     * messages are appended) so the caller can persist or continue it.
     */
    suspend fun runTurn(
        history: MutableList<ChatMessage>,
        listener: suspend (AgentEvent) -> Unit
    ) {
        try {
            var step = 0
            while (step < config.maxSteps) {
                if (!currentCoroutineContext().isActive) return
                beforeStep()
                if (!currentCoroutineContext().isActive) return
                if (stopRequested()) {
                    val reason = stopReason()
                    listener(AgentEvent.Notice(reason))
                    listener(AgentEvent.FinalAnswer(reason))
                    return
                }
                step++

                trimHistory(history)
                val useTools = step < config.maxSteps // last attempt: plain answer
                val request = ChatRequest(
                    model = config.model,
                    messages = history.toList(),
                    stream = true,
                    tools = if (useTools) AgentTools.toolDefs() else null,
                    temperature = config.temperature
                )

                val assistant = gateway.chat(request) { ev ->
                    when (ev) {
                        is StreamEvent.Text -> listener(AgentEvent.AssistantText(ev.text))
                        is StreamEvent.Thinking -> listener(AgentEvent.AssistantThinking(ev.text))
                    }
                }
                history.add(assistant)

                val calls = assistant.toolCalls.orEmpty()
                if (calls.isEmpty()) {
                    listener(AgentEvent.FinalAnswer(assistant.content.orEmpty().ifBlank { "(the model returned an empty answer)" }))
                    return
                }

                for (call in calls) {
                    listener(AgentEvent.ToolStarted(call.id, call.function.name, call.function.arguments))
                    val result = try {
                        tools.execute(call.function.name, call.function.arguments)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        ToolResult(ok = false, output = "tool crashed: ${t.message ?: t.javaClass.simpleName}")
                    }
                    val content = result.asMessageContent().take(MAX_TOOL_RESULT_CHARS)
                    val summary = result.output.replace('\n', ' ').trim().take(SUMMARY_CHARS)
                    listener(AgentEvent.ToolFinished(call.id, call.function.name, result.ok, summary))
                    history.add(
                        ChatMessage(role = "tool", content = content, toolCallId = call.id)
                    )
                }
            }

            listener(AgentEvent.Notice("Step limit reached (${config.maxSteps}) — wrapping up."))
            trimHistory(history)
            val final = gateway.chat(
                ChatRequest(
                    model = config.model,
                    messages = history.toList(),
                    stream = true,
                    temperature = config.temperature
                )
            ) { ev ->
                if (ev is StreamEvent.Text) listener(AgentEvent.AssistantText(ev.text))
            }
            history.add(final)
            listener(AgentEvent.FinalAnswer(final.content.orEmpty().ifBlank { "(stopped at the step limit without a final answer)" }))
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // A cancelled turn (user pressed Stop / call cancelled) must not
            // surface as a fake provider error.
            if (!currentCoroutineContext().isActive) throw CancellationException("agent turn cancelled")
            listener(AgentEvent.AgentError(t.message ?: t.javaClass.simpleName, fatal = true))
        }
    }

    /**
     * Keeps the request payload bounded: system message + the newest turns,
     * oldest non-system messages dropped first.
     */
    private fun trimHistory(history: MutableList<ChatMessage>) {
        if (history.size <= MAX_HISTORY) return
        val system = history.firstOrNull { it.role == "system" }
        val rest = history.filterNot { it.role == "system" }
        val kept = rest.takeLast(MAX_HISTORY - 1)
        history.clear()
        system?.let { history.add(it) }
        history.addAll(kept)
    }

    companion object {
        const val MAX_TOOL_RESULT_CHARS = 6000
        const val SUMMARY_CHARS = 160
        const val MAX_HISTORY = 40
    }
}
