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

data class ToolResult(
    val ok: Boolean,
    val output: String,
    /**
     * Pictures this tool captured for the model to look at, empty for every
     * tool that only speaks text. They are handed to the provider as a user
     * turn of their own — see [AgentLoop.runTurn] — because a tool result
     * message carries text only.
     */
    val images: List<AgentImage> = emptyList(),
    /**
     * A step list this tool just set, empty for every tool that is not
     * `plan`. Carried on the result rather than parsed again by the loop, so
     * the list the user is shown is the one the executor already validated.
     */
    val plan: List<AgentPlan.Item> = emptyList()
) {
    fun asMessageContent(): String = (if (ok) "" else "ERROR: ") + output
}

data class AgentConfig(
    val model: String,
    val maxSteps: Int = DEFAULT_STEPS,
    val temperature: Double = 0.2,
    val systemPrompt: String,
    /**
     * The turn may not change anything until it has written a step list with
     * `plan`. An attempt before that is answered with [AgentPlan.NUDGE]
     * instead of being executed, so the model reads it as the result of its
     * own call. Off for a scheduled run, which has no user watching a list.
     */
    val planBeforeActions: Boolean = false,
    /**
     * The turn must END with a step list, because in this mode the list IS the
     * answer. Off everywhere else: a turn that only answers a question needs
     * no list.
     */
    val planIsTheAnswer: Boolean = false
) {
    /**
     * The step budget the loop actually honours. A stored value is clamped
     * rather than trusted: the ceiling is what keeps a typed or migrated
     * number from turning one turn into an unbounded run of provider calls.
     */
    val steps: Int get() = maxSteps.coerceIn(MIN_STEPS, MAX_STEPS)

    companion object {
        const val MIN_STEPS = 1
        const val MAX_STEPS = 200
        const val DEFAULT_STEPS = 75
    }
}

/** Events surfaced to the chat UI. */
sealed interface AgentEvent {
    data class AssistantText(val text: String) : AgentEvent
    data class AssistantThinking(val text: String) : AgentEvent
    data class ToolStarted(val id: String, val name: String, val argsJson: String) : AgentEvent
    data class ToolFinished(val id: String, val name: String, val ok: Boolean, val summary: String) : AgentEvent

    /**
     * The whole step list, as `plan` just set it. Sent on every call rather
     * than as a delta, so a surface that missed one still shows the truth.
     */
    data class PlanUpdated(val items: List<AgentPlan.Item>) : AgentEvent
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
            // One turn's worth: a tool that failed twice in a previous turn is
            // not a loop in this one.
            val failures = AgentFailureWatch()
            // The list this turn is working to, as the last `plan` call set it.
            var currentPlan = emptyList<AgentPlan.Item>()
            var planRefusals = 0
            var planNudges = 0
            while (step < config.steps) {
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
                val useTools = step < config.steps // last attempt: plain answer
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
                    // Where the list IS the answer, an answer without one is not
                    // an answer. One re-ask, and never on the tools-free last
                    // step, where the tool it asks for is not on offer.
                    if (config.planIsTheAnswer && currentPlan.isEmpty() &&
                        useTools && planNudges < MAX_PLAN_NUDGES
                    ) {
                        planNudges++
                        history.add(ChatMessage(role = "user", content = AgentPlan.NUDGE))
                        continue
                    }
                    listener(AgentEvent.FinalAnswer(assistant.content.orEmpty().ifBlank { "(the model returned an empty answer)" }))
                    // After the answer, so it reads as the closing line of the
                    // turn rather than as something said before it.
                    if (config.planBeforeActions && currentPlan.any { it.status != AgentPlan.Status.DONE }) {
                        listener(AgentEvent.Notice(AgentPlan.completionNotice(currentPlan)))
                    }
                    return
                }

                val turnImages = mutableListOf<AgentImage>()
                for (call in calls) {
                    listener(AgentEvent.ToolStarted(call.id, call.function.name, call.function.arguments))
                    // Work may not be carried out before the list that says what
                    // the work is. The refusal is bounded: a model that will not
                    // write a list still gets to finish the user's task.
                    val needsPlan = config.planBeforeActions &&
                        currentPlan.isEmpty() &&
                        call.function.name in PlanModePolicy.REFUSED &&
                        planRefusals < MAX_PLAN_REFUSALS
                    // A repeat of a call that just failed twice is answered with
                    // a correction rather than executed again: the model reads
                    // it as the result of its own call, which is the one place
                    // it is certain to look.
                    val blocked = if (needsPlan) {
                        planRefusals++
                        AgentPlan.NUDGE
                    } else {
                        failures.refusal(call.function.name)
                    }
                    val result = if (blocked != null) {
                        ToolResult(ok = false, output = blocked)
                    } else {
                        val executed = try {
                            tools.execute(call.function.name, call.function.arguments)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            ToolResult(ok = false, output = "tool crashed: ${t.message ?: t.javaClass.simpleName}")
                        }
                        failures.record(call.function.name, executed.ok, executed.output)
                        executed
                    }
                    val content = result.asMessageContent().take(MAX_TOOL_RESULT_CHARS)
                    // The model sees `content`, fence and all; the user sees
                    // this summary, so the fence comes off here.
                    val summary = UntrustedContent.payload(result.output)
                        .replace('\n', ' ').trim().take(SUMMARY_CHARS)
                    // Before the card, so the checklist the step belongs to is
                    // already on screen when the step lands in it.
                    if (result.plan.isNotEmpty()) {
                        currentPlan = result.plan
                        listener(AgentEvent.PlanUpdated(result.plan))
                    }
                    listener(AgentEvent.ToolFinished(call.id, call.function.name, result.ok, summary))
                    history.add(
                        ChatMessage(role = "tool", content = content, toolCallId = call.id)
                    )
                    if (result.ok) turnImages += result.images
                }
                if (turnImages.isNotEmpty()) {
                    // A tool result message carries text only, so a captured
                    // picture rides in a user turn of its own, right after the
                    // results it belongs to.
                    history.add(
                        ChatMessage(
                            role = "user",
                            content = "The page as the last tool captured it.",
                            images = turnImages
                        )
                    )
                }
            }

            listener(AgentEvent.Notice("Step limit reached (${config.steps}) — wrapping up."))
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
            // A turn cut off mid-list is exactly when the count matters most,
            // and it goes under the answer for the same reason as above.
            if (config.planBeforeActions && currentPlan.any { it.status != AgentPlan.Status.DONE }) {
                listener(AgentEvent.Notice(AgentPlan.completionNotice(currentPlan)))
            }
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

        /**
         * How many times a turn may be told to write its step list before the
         * loop stops insisting and lets the work proceed. A model that will not
         * plan must not be able to wedge the task it was given.
         */
        const val MAX_PLAN_REFUSALS = 2
        const val MAX_PLAN_NUDGES = 1
    }
}
