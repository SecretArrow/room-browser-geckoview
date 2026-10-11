package com.roombrowser.agent

import com.roombrowser.domain.agent.AgentClientIdentity
import com.roombrowser.domain.agent.AgentGateway
import com.roombrowser.domain.agent.AgentHttpException
import com.roombrowser.domain.agent.AgentJson
import com.roombrowser.domain.agent.AgentVisionSupport
import com.roombrowser.domain.agent.ChatMessage
import com.roombrowser.domain.agent.ChatRequest
import com.roombrowser.domain.agent.ModelListParser
import com.roombrowser.domain.agent.OpenCodeModelsParser
import com.roombrowser.domain.agent.OpenCodeToolCallParser
import com.roombrowser.domain.agent.OpenCodeWire
import com.roombrowser.domain.agent.StreamEvent
import com.roombrowser.domain.agent.ToolDef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Agent transport for an **opencode serve** backend (protocol "OPENCODE").
 *
 * opencode's server API is session-based and NOT OpenAI-compatible, so this
 * gateway bridges both directions while keeping [AgentLoop] untouched:
 *
 *  - Outbound: only the messages not yet sent are posted (delta) as ONE
 *    `POST /session/{id}/message` (system → labelled text, tool results →
 *    "TOOL RESULT" labels). The server-side session keeps the context, so
 *    nothing is ever re-sent.
 *  - Inbound: `GET /session/{id}/message` is polled; newly appearing
 *    assistant text is streamed to the UI in deltas, then parsed with
 *    [OpenCodeToolCallParser] — the model is instructed (first message of
 *    the session) to emit tool calls as JSON blocks because opencode has no
 *    browser tools. Parsed calls become real [ChatMessage.toolCalls].
 *
 * A turn that was trimmed by the loop (history shrank) transparently starts
 * a fresh opencode session and replays the visible history.
 *
 * Screenshots are NOT supported — the server API carries text only, so
 * [AgentVisionSupport] reports OPENCODE as unable and the caller must refuse
 * before a request is built.
 */
class OpenCodeAgentGateway(
    client: OkHttpClient,
    baseUrl: String,
    private val apiKey: String,
    pollIntervalMs: Long = POLL_INTERVAL_MS,
    pollTotalMs: Long = POLL_TOTAL_MS
) : AgentGateway {

    private val pollInterval = pollIntervalMs
    private val pollTotal = pollTotalMs

    private val callFactory: OkHttpClient = client.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // per-request (polls are fast)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val base: String = baseUrl.trim().trimEnd('/')

    init {
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "OpenCode base URL must start with http:// or https://"
        }
    }

    // Session state (one gateway instance == one agent turn == one session)
    private var sessionId: String? = null
    private var sentCount = 0
    private var introSent = false
    private val consumedIds = mutableSetOf<String>()

    // ------------------------------------------------------------------ chat

    override suspend fun chat(
        request: ChatRequest,
        events: suspend (StreamEvent) -> Unit
    ): ChatMessage = withContext(Dispatchers.IO) {
        val messages = request.messages

        // History was trimmed (or this is a fresh turn) → replay from scratch.
        if (sentCount > messages.size) {
            sessionId = null
            sentCount = 0
            introSent = false
            consumedIds.clear()
        }

        val sid = ensureSession()
        val delta = messages.drop(sentCount)
        val outgoing = buildOutgoing(delta, request.tools)

        val body = OpenCodeWire.messageBody(request.model, outgoing)
        val post = Request.Builder()
            .url("$base/session/$sid/message")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "application/json")
            // A CLI-gated host refuses the app's own name: see AgentClientIdentity.
            .header("User-Agent", AgentClientIdentity.userAgent(base))
        if (apiKey.isNotBlank()) post.header("Authorization", "Bearer $apiKey")

        val response = execute(post.build())
        try {
            if (!response.isSuccessful) throw AgentHttpException(response.code, errorBody(response))
        } finally {
            runCatching { response.close() }
        }

        sentCount = messages.size

        val rawText = awaitAssistant(sid, events)
        val parsed = OpenCodeToolCallParser.parse(rawText, enabled = request.tools != null)
        ChatMessage(
            role = "assistant",
            content = parsed.text.ifBlank { rawText.take(200) }.ifBlank { null },
            toolCalls = parsed.toolCalls.takeIf { it.isNotEmpty() }
        )
    }

    /**
     * Builds the single outgoing text: the text-tool protocol briefing on
     * the first message of a session + the labelled conversation delta.
     */
    private fun buildOutgoing(delta: List<ChatMessage>, tools: List<ToolDef>?): String {
        val sb = StringBuilder()
        if (!introSent) {
            introSent = true
            sb.append(intro(tools))
            sb.append("\n\n---\n\n")
        }
        sb.append(delta.joinToString("\n\n---\n\n") { render(it) })
        return sb.toString().ifBlank { "(continue)" }
    }

    private fun render(message: ChatMessage): String = when (message.role) {
        "system" -> "SYSTEM:\n${message.content.orEmpty()}"
        "assistant" -> "ASSISTANT:\n${message.content.orEmpty()}"
        "tool" -> "TOOL RESULT (${message.toolCallId.orEmpty()}):\n${message.content.orEmpty()}"
        else -> message.content.orEmpty()
    }

    private fun intro(tools: List<ToolDef>?): String {
        val catalogue = if (tools.isNullOrEmpty()) {
            "(No browser tools are available for this reply — answer in plain text.)"
        } else {
            AgentJson.encodeToString(ListSerializer(ToolDef.serializer()), tools)
        }
        return """
            |You are driving an Android web browser (Room Browser) through a TEXT-BASED tool-call protocol:
            |this backend has no native tool calling, so you request actions by replying with ONE fenced JSON block:
            |
            |```json
            |{"tool_calls":[{"function":{"name":"<tool_name>","arguments":{...}}}]}
            |```
            |
            |Rules:
            |1. The available browser tools (name, description, JSON argument schema) are listed below.
            |2. Batch several tool calls in the same block only when they are independent.
            |3. After each block you will receive "TOOL RESULT (...)" messages — read them, then continue.
            |4. When the task is finished (or you only need to talk), reply with plain text and NO JSON block.
            |5. Never fabricate tool results; wait for them.
            |
            |TOOLS:
            |$catalogue
        """.trimMargin()
    }

    // ------------------------------------------------------------------ poll

    private class OpMessage(val id: String?, val role: String, val text: String)

    /**
     * Polls `GET /session/{id}/message` until a NEW assistant message appears
     * (id not yet consumed) and stabilizes; emits text deltas for streaming.
     */
    private suspend fun awaitAssistant(
        sid: String,
        events: suspend (StreamEvent) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + pollTotal
        var streamedId: String? = null
        var streamedLen = 0
        var stablePolls = 0
        var lastText = ""

        while (System.currentTimeMillis() < deadline) {
            if (!currentCoroutineContext().isActive) throw CancellationException("opencode poll cancelled")
            val messages = fetchMessages(sid)
            val target = messages
                .filter { it.role == "assistant" && it.text.isNotBlank() && (it.id == null || it.id !in consumedIds) }
                .lastOrNull()

            if (target != null) {
                if (target.id != null && target.id != streamedId) {
                    // A different (newer) assistant message replaced the old one.
                    streamedId = target.id
                    streamedLen = 0
                }
                if (target.text.length > streamedLen) {
                    val deltaText = target.text.substring(streamedLen)
                    events(StreamEvent.Text(deltaText))
                    streamedLen = target.text.length
                    stablePolls = 0
                }
                lastText = target.text

                // Completion heuristics: a parseable tool block, or the text
                // stopped growing for two consecutive polls.
                val hasToolBlock = target.text.contains("\"tool_calls\"") ||
                    target.text.contains("\"actions\"") ||
                    target.text.contains("\"tool_call\"") ||
                    target.text.contains("```")
                if (hasToolBlock && OpenCodeToolCallParser.parse(target.text, enabled = true).toolCalls.isNotEmpty()) {
                    target.id?.let { consumedIds.add(it) }
                    return@withContext target.text
                }
                stablePolls++
                if (stablePolls >= 2) {
                    target.id?.let { consumedIds.add(it) }
                    return@withContext target.text
                }
            } else {
                stablePolls = 0
            }
            delay(pollInterval)
        }
        // Deadline reached — return whatever streamed (may be blank).
        lastText.ifBlank { throw AgentHttpException(-1, "opencode did not produce an assistant reply within ${pollTotal / 1000}s") }
    }

    private suspend fun fetchMessages(sid: String): List<OpMessage> {
        val builder = Request.Builder()
            .url("$base/session/$sid/message")
            .get()
            .header("Accept", "application/json")
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        val response = execute(builder.build())
        try {
            val text = response.body?.string() ?: ""
            if (!response.isSuccessful) throw AgentHttpException(response.code, text.take(500))
            return parseMessages(text)
        } finally {
            runCatching { response.close() }
        }
    }

    /** Lenient parser: bare array, {items:[...]}, {messages:[...]} or {data:[...]}. */
    private fun parseMessages(body: String): List<OpMessage> = runCatching {
        val root = AgentJson.parseToJsonElement(body.trim())
        val items = when (root) {
            is JsonArray -> root
            is JsonObject -> root["items"] as? JsonArray
                ?: root["messages"] as? JsonArray
                ?: root["data"] as? JsonArray
            else -> null
        } ?: return emptyList()
        items.mapNotNull { el ->
            val wrapper = el as? JsonObject ?: return@mapNotNull null
            val msg = wrapper["message"] as? JsonObject ?: wrapper
            val role = (msg["role"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val id = (msg["id"] as? JsonPrimitive)?.contentOrNull
                ?: (wrapper["id"] as? JsonPrimitive)?.contentOrNull
            val parts = msg["parts"] as? JsonArray
            val text = parts.orEmpty().mapNotNull { p ->
                val po = p as? JsonObject ?: return@mapNotNull null
                if ((po["type"] as? JsonPrimitive)?.contentOrNull == "text") {
                    (po["text"] as? JsonPrimitive)?.contentOrNull
                } else null
            }.joinToString("")
            OpMessage(id, role, text)
        }
    }.getOrDefault(emptyList())

    // ---------------------------------------------------------------- session

    private suspend fun ensureSession(): String {
        sessionId?.let { return it }
        val body = OpenCodeWire.createSessionBody("Room Browser Agent")
        val builder = Request.Builder()
            .url("$base/session")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "application/json")
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        val response = execute(builder.build())
        try {
            val text = response.body?.string() ?: ""
            if (!response.isSuccessful) throw AgentHttpException(response.code, text.take(500))
            val root = runCatching {
                AgentJson.parseToJsonElement(text.trim()) as? JsonObject
            }.getOrNull()
            val id = root?.let { obj ->
                (obj["id"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["info"] as? JsonObject)?.let { (it["id"] as? JsonPrimitive)?.contentOrNull }
            } ?: throw AgentHttpException(-1, "opencode returned a session without an id")
            sessionId = id
            return id
        } finally {
            runCatching { response.close() }
        }
    }

    // ---------------------------------------------------------------- models

    override suspend fun listModels(): List<String> {
        for (endpoint in listOf("/provider", "/config/providers")) {
            val builder = Request.Builder()
                .url("$base$endpoint")
                .get()
                .header("Accept", "application/json")
            if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
            val response = execute(builder.build())
            try {
                val text = response.body?.string() ?: ""
                if (!response.isSuccessful) continue
                val models = OpenCodeModelsParser.parse(text)
                if (models.isNotEmpty()) return models
            } finally {
                runCatching { response.close() }
            }
        }
        // Last resort: some setups proxy an OpenAI-style /models too.
        val builder = Request.Builder()
            .url("$base/models")
            .get()
            .header("Accept", "application/json")
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        val response = execute(builder.build())
        try {
            val text = response.body?.string() ?: ""
            if (!response.isSuccessful) throw AgentHttpException(response.code, text.take(500))
            val models = ModelListParser.parse(text)
            if (models.isEmpty()) {
                throw AgentHttpException(
                    response.code,
                    "opencode returned no models at /provider — is `opencode serve` running and reachable?"
                )
            }
            return models
        } finally {
            runCatching { response.close() }
        }
    }

    // ---------------------------------------------------------------- utils

    private fun errorBody(response: Response): String = runCatching {
        response.body?.string()?.take(500) ?: ""
    }.getOrDefault("")

    private suspend fun execute(request: Request): Response =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val call: Call = callFactory.newCall(request)
            continuation.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    if (continuation.isActive) {
                        if (call.isCanceled()) {
                            continuation.resumeWithException(CancellationException("opencode request cancelled"))
                        } else {
                            continuation.resumeWithException(
                                AgentHttpException(-1, "connection failed: ${e.message ?: e.javaClass.simpleName}")
                            )
                        }
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response)
                    else response.close()
                }
            })
        }

    companion object {
        const val POLL_INTERVAL_MS = 1500L
        const val POLL_TOTAL_MS = 240_000L
    }
}
