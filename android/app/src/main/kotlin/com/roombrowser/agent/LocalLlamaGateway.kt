package com.roombrowser.agent

import com.roombrowser.domain.agent.AgentGateway
import com.roombrowser.domain.agent.AgentHttpException
import com.roombrowser.domain.agent.AgentVisionSupport
import com.roombrowser.domain.agent.ChatMessage
import com.roombrowser.domain.agent.ChatRequest
import com.roombrowser.domain.agent.FunctionCall
import com.roombrowser.domain.agent.LocalToolProtocol
import com.roombrowser.domain.agent.StreamEvent
import com.roombrowser.domain.agent.ToolCall
import com.roombrowser.localai.engine.LlamaEngine
import com.roombrowser.localai.engine.LlamaEngineApi
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Gateway that runs the agent turn fully ON-DEVICE through the embedded
 * llama.cpp engine (provider protocol "LOCAL"). No network is involved at
 * all — no server, no API key, nothing leaves the phone.
 *
 * The provider's `defaultModel` (set by the Local AI screen or the provider
 * editor's model chips) carries the ON-DEVICE MODEL ID: the file name of a
 * `.gguf` model in [modelsDirectory] minus the extension. The directory is
 * attached by the [AgentGateways] router (it needs an app Context — the
 * same directory `OnDeviceModelStore.modelsDir` uses, i.e.
 * `{noBackupFilesDir}/on_device_models`); unit tests inject a temp folder
 * and a fake [LlamaEngineApi] instead.
 *
 * CONCURRENCY: BrowserAgentController serializes agent turns through a
 * single `turnJob`, so at most ONE chat() runs at a time against the
 * single native llama.cpp context. Concurrent callers are out of scope by
 * design — the engine holds one loaded model.
 *
 * Cancellation propagates naturally: the engine's generation runs inside a
 * cancellable coroutine and aborts the native decode loop.
 *
 * HONEST limitations surfaced to the user as errors:
 *  - the engine build has no native library → "not available in this build";
 *  - the model id has no `.gguf` file → "not found — download or import it";
 *  - llama.cpp rejects the file (corrupt / not enough RAM) → its message.
 *
 * Events: the whole answer arrives as ONE [StreamEvent.Text] — there is no
 * token streaming from the native loop into the gateway (honest: the chat
 * bubble shows the full answer at once, unlike the SSE gateways). A turn that
 * CALLS A TOOL emits no text at all: the raw call JSON is not an answer, and
 * the loop would show it as one.
 *
 * TOOL CALLING: the engine takes a prompt and returns prose — there is no
 * `tools` array on the wire and no `tool_calls` in the reply — so both travel
 * as text under [LocalToolProtocol]'s contract. That translation is what lets
 * the on-device model actually DRIVE the browser instead of describing what it
 * would do; see that file for the shape and its parsing rules.
 *
 * Screenshots are NOT supported — the engine takes a text prompt only, so
 * [AgentVisionSupport] reports LOCAL as unable and the caller must refuse
 * before a request is built.
 */
class LocalLlamaGateway(
    private val engine: LlamaEngineApi = LlamaEngine
) : AgentGateway {

    /**
     * Directory holding the on-device `.gguf` models. Attached by
     * [AgentGateways] routing (needs an app Context); null in unit tests
     * unless injected — with null, nothing is resolvable.
     */
    var modelsDirectory: File? = null

    /** Source of the tool-call ids handed to the agent loop (see [nextCallId]). */
    private val callIds = AtomicInteger(0)

    // ------------------------------------------------------------------ chat

    override suspend fun chat(
        request: ChatRequest,
        events: suspend (StreamEvent) -> Unit
    ): ChatMessage {
        if (!engine.available) {
            throw AgentHttpException(-1, "on-device engine not available in this build")
        }
        val modelId = request.model.trim()
        val file = fileFor(modelId)
            ?: throw AgentHttpException(
                -1,
                "on-device model '$modelId' not found — download or import it in Local AI"
            )

        // Ensure loaded: skip the (expensive) native load when the engine
        // already holds this model. The engine's own state is only visible
        // on the LlamaEngine object (not part of the frozen LlamaEngineApi
        // interface), so a failed downcast simply means "assume not loaded"
        // — the engine's load() is idempotent anyway.
        if (loadedModelId() != modelId) {
            val loadError = engine.load(modelId, file, contextTokens = 0, threads = DEFAULT_THREADS)
            if (loadError != null) {
                throw AgentHttpException(
                    -1,
                    "on-device model '$modelId' could not be loaded: $loadError"
                )
            }
        }

        // The engine has no `tools` channel — it takes a prompt and returns
        // prose — so the catalogue goes out and the call comes back as TEXT
        // under LocalToolProtocol's contract. Without this the model could
        // describe an action but never take one: the reply carried no
        // toolCalls, and the agent loop stopped at "no calls → final answer"
        // on every step.
        // No usage event here, on purpose: [LlamaEngineApi.chat] returns the
        // reply text only — the engine exposes no token accounting, and a
        // count guessed from the prompt is exactly the estimate this pipeline
        // refuses. A LOCAL turn reports no usage; its row's token columns
        // stay null.
        val answer = engine.chat(LocalToolProtocol.conversationFor(request.messages, request.tools))
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
                // A call is NOT streamed as text: the loop reads streamed text
                // as the answer to show, and the raw JSON is not that.
                if (reply.content.isNotEmpty()) events(StreamEvent.Text(reply.content))
                // Same final-message shape the HTTP gateways return (null
                // content when the model produced nothing).
                ChatMessage(
                    role = "assistant",
                    content = reply.content.takeIf { it.isNotEmpty() }
                )
            }
        }
    }

    /**
     * The engine invents no ids, so this does. The agent loop echoes it back
     * on the tool result (`tool_call_id`), and the tools of one reply must not
     * collide — the model can ask for the same tool twice in a turn.
     */
    private fun nextCallId(): String = "local-${callIds.incrementAndGet()}"

    // ---------------------------------------------------------------- models

    /**
     * Lists the on-device model ids (file names minus `.gguf`) — used by the
     * provider editor's "Fetch models" button via the generic gateway path.
     */
    override suspend fun listModels(): List<String> {
        val dir = modelsDirectory
            ?: throw AgentHttpException(
                -1,
                "on-device models directory not attached — no app context"
            )
        val ids = dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") }
            ?.map { it.name.removeSuffix(".gguf") }
            ?.sorted()
            ?: emptyList()
        if (ids.isEmpty()) {
            throw AgentHttpException(
                -1,
                "no on-device models installed — import or download one in Local AI"
            )
        }
        return ids
    }

    // ---------------------------------------------------------------- utils

    /** Model id → `.gguf` file; null when the id is unsafe or the file is absent. */
    private fun fileFor(id: String): File? {
        // Path-traversal guard: a model id is a plain file name, never a path.
        if (id.isBlank() || id.contains('/') || id.contains('\\') || id.contains("..")) return null
        val dir = modelsDirectory ?: return null
        val candidate = File(dir, "$id.gguf")
        return if (candidate.isFile) candidate else null
    }

    /** Currently loaded model id, when the engine singleton exposes its state. */
    private fun loadedModelId(): String? =
        (engine as? LlamaEngine)?.state?.value?.modelId

    private companion object {
        /** CPU threads for the native decode loop (phone-safe default). */
        const val DEFAULT_THREADS = 4
    }
}
