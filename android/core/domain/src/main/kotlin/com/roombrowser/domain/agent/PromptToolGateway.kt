package com.roombrowser.domain.agent

import java.util.concurrent.atomic.AtomicInteger

/**
 * How a provider is asked to call tools.
 *
 * Native tool calling is the OpenAI/Anthropic `tools` array, and it is what a
 * capable model wants: the provider constrains the reply and the call arrives
 * structured. But it is not universal — some gateways reject the field
 * outright ("does not support tools"), some silently drop it and answer in
 * prose, and a small or heavily-quantised model may hold the schema worse than
 * it holds a sentence. In every one of those cases the agent used to be stuck:
 * `tools` was the only channel, so a provider that refused it could describe an
 * action but never take one.
 *
 * So there are two channels and a way to pick between them:
 *
 *  - [NATIVE] — send `tools`, read `tool_calls`. Streaming is preserved.
 *  - [TEXT] — send no `tools` at all; the catalogue and the calls travel as
 *    text under [LocalToolProtocol]'s contract.
 *  - [AUTO] (the default) — try native, and fall back to text the moment the
 *    provider says it cannot take tools.
 *
 * The stored spelling is the enum name, so a new constant needs no migration.
 */
enum class ToolMode {
    AUTO,
    NATIVE,
    TEXT;

    /** The wire form, for display beside the provider in the editor. */
    val label: String
        get() = when (this) {
            AUTO -> "Auto"
            NATIVE -> "Native"
            TEXT -> "Text"
        }

    companion object {
        /** What an unset or unrecognised stored value means. */
        val DEFAULT: ToolMode = AUTO

        /** A stored `tool_mode` value, tolerating null and older spellings. */
        fun fromStored(value: String?): ToolMode =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * Teaches tool calling to a gateway that does not have it.
 *
 * Wraps any [AgentGateway] and, per [ToolMode], either gets out of the way or
 * re-expresses the turn in text — the conversation is rewritten by
 * [LocalToolProtocol.messagesFor] (contract and catalogue appended to the
 * system message, `tool` results relabelled, assistant calls re-rendered), the
 * `tools` array is dropped, and the reply is read back with
 * [LocalToolProtocol.parseReply]. A reply that asks for a tool becomes a
 * [ChatMessage] with `toolCalls`, exactly as a native one would, so the agent
 * loop cannot tell the difference and needs no change.
 *
 * This is what makes "every provider can call tools" true rather than
 * aspirational: the identity of the provider stops mattering, because the
 * contract is carried by the conversation instead of by the wire.
 *
 * TEXT is not free, and the trade is deliberate:
 *
 *  - the delegate's answer is BUFFERED rather than streamed, because a call is
 *    only recognisable once the whole object has arrived. In TEXT mode the
 *    chat bubble fills in at the end of the turn instead of token by token;
 *  - the model must follow the contract by instruction. A model that ignores
 *    it produces prose, which is read as an answer — the same outcome as a
 *    native model that ignores `tools`. AUTO cannot rescue that case either;
 *    it is what TEXT mode is for when a model merely writes the call badly.
 */
class PromptToolGateway(
    private val delegate: AgentGateway,
    private val mode: ToolMode = ToolMode.DEFAULT
) : AgentGateway {

    /**
     * Whether turns are rewritten. Starts at the configured mode and, under
     * [ToolMode.AUTO], flips to true the first time the provider refuses
     * tools — so the cost is one rejected request per gateway, not per turn.
     */
    @Volatile
    private var textOnly: Boolean = mode == ToolMode.TEXT

    /** Source of the tool-call ids handed to the agent loop (see [nextCallId]). */
    private val callIds = AtomicInteger(0)

    override suspend fun chat(
        request: ChatRequest,
        events: suspend (StreamEvent) -> Unit
    ): ChatMessage {
        if (textOnly) return textTurn(request, events)
        // No catalogue to offer (the loop drops it on the last step to force a
        // plain answer) — nothing to translate, so leave the turn alone.
        if (request.tools.isNullOrEmpty()) return delegate.chat(request, events)
        return try {
            // The caller's [events] are passed STRAIGHT through on this path —
            // native tool calling is the common case and buffering it would
            // cost the token-by-token bubble for nothing. That is safe against
            // the retry below: a provider that refuses the `tools` field
            // refuses it when it validates the request, before it has emitted
            // a single token, so the retry cannot repeat text the user has
            // already seen.
            delegate.chat(request, events)
        } catch (error: AgentHttpException) {
            if (mode != ToolMode.AUTO || !rejectsTools(error)) throw error
            textOnly = true
            textTurn(request, events)
        }
    }

    /**
     * One turn under the text contract: rewrite, ask without `tools`, read the
     * reply back as either a call or an answer.
     */
    private suspend fun textTurn(
        request: ChatRequest,
        events: suspend (StreamEvent) -> Unit
    ): ChatMessage {
        val rewritten = request.copy(
            messages = LocalToolProtocol.messagesFor(request.messages, request.tools),
            tools = null
        )

        // The delegate's own text is swallowed here and re-emitted below: until
        // the turn is complete there is no way to know whether it is prose
        // worth showing or the JSON of a call, and a call must never surface as
        // an answer.
        val streamed = StringBuilder()
        val message = delegate.chat(rewritten) { event ->
            // Usage is neither prose nor a call — it is the provider's own
            // accounting, and swallowing it here would make every text-mode
            // provider (one that refuses the `tools` field) look like it
            // reported nothing. Thinking stays swallowed with the text: this
            // mode has always declined to show it.
            when (event) {
                is StreamEvent.Usage -> events(event)
                else -> if (event is StreamEvent.Text) streamed.append(event.text)
            }
        }
        val answer = message.content ?: streamed.toString()

        return when (val reply = LocalToolProtocol.parseReply(answer)) {
            is LocalToolProtocol.Reply.Call -> ChatMessage(
                role = "assistant",
                content = null,
                toolCalls = listOf(
                    ToolCall(
                        id = nextCallId(),
                        function = FunctionCall(reply.name, reply.argumentsJson)
                    )
                )
            )

            is LocalToolProtocol.Reply.Text -> {
                if (reply.content.isNotEmpty()) events(StreamEvent.Text(reply.content))
                ChatMessage(
                    role = "assistant",
                    content = reply.content.takeIf { it.isNotEmpty() }
                )
            }
        }
    }

    /**
     * Whether [error] says the provider will not take a `tools` array.
     *
     * Deliberately narrow: only a client error (4xx — a 5xx or a timeout is the
     * provider's problem, not a verdict on tools) whose text names tools or
     * functions. A gateway that refuses the field says so; one that merely
     * failed says something else, and re-sending the turn in text would turn a
     * transient fault into a silent downgrade.
     */
    private fun rejectsTools(error: AgentHttpException): Boolean =
        error.code in 400..499 &&
            TOOL_REJECTION_MARKERS.any { error.body.contains(it, ignoreCase = true) }

    /**
     * The engine invents no ids, so this does. The agent loop echoes it back on
     * the tool result (`tool_call_id`), and the tools of one reply must not
     * collide — a model can ask for the same tool twice in a turn.
     */
    private fun nextCallId(): String = "text-${callIds.incrementAndGet()}"

    override suspend fun listModels(): List<String> = delegate.listModels()

    private companion object {
        val TOOL_REJECTION_MARKERS = listOf("tool", "function")
    }
}
