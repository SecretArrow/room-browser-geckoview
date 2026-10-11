package com.roombrowser.agent

import com.roombrowser.domain.agent.AgentClientIdentity
import com.roombrowser.domain.agent.AgentGateway
import com.roombrowser.domain.agent.AgentHttpException
import com.roombrowser.domain.agent.AgentJson
import com.roombrowser.domain.agent.ChatMessage
import com.roombrowser.domain.agent.ChatRequest
import com.roombrowser.domain.agent.FunctionCall
import com.roombrowser.domain.agent.LocalAiTuning
import com.roombrowser.domain.agent.OllamaChatFunctionCall
import com.roombrowser.domain.agent.OllamaChatMessage
import com.roombrowser.domain.agent.OllamaChatToolCall
import com.roombrowser.domain.agent.OllamaTagsParser
import com.roombrowser.domain.agent.StreamEvent
import com.roombrowser.domain.agent.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.BufferedReader
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ---------------------------------------------------------------------------
// Private request DTOs. ChatRequest is NEVER sent raw: Ollama's native /api/chat
// differs from the OpenAI shape in exactly two places that matter —
//  1. `tool_calls` arguments travel as a real JSON OBJECT, not a STRING
//     (our FunctionCall.arguments is a string, so it is re-parsed here);
//  2. generation options (num_ctx / num_gpu / num_thread) and keep_alive are
//     top-level request fields driven by the Local AI tuning screen.
// The message + tool-call shapes live in OllamaDtos.kt ([OllamaChatMessage])
// so the images mapping is JVM-tested; the tool CATALOGUE schema matches OpenAI's tools shape 1:1, so
// it is re-mapped through dedicated wire types.
// ---------------------------------------------------------------------------

@Serializable
private data class WireToolDef(
    val type: String = "function",
    val function: WireToolFunction
)

@Serializable
private data class WireToolFunction(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

@Serializable
private data class WireChatRequest(
    val model: String,
    val messages: List<OllamaChatMessage>,
    val stream: Boolean = true,
    val tools: List<WireToolDef>? = null,
    val options: JsonObject? = null,
    @SerialName("keep_alive") val keepAlive: String? = null
)

/**
 * Agent transport for a REAL Ollama server (protocol "OLLAMA") via the
 * native API — base like `http://localhost:11434`, NO `/v1` suffix.
 *
 * Why a dedicated gateway instead of Ollama's OpenAI-compat layer:
 *  - `/api/chat` exposes `options` (num_ctx, num_gpu, num_thread) and
 *    `keep_alive`, which is how the Local AI tuning screen actually reaches
 *    the model. The OpenAI-compat shim ignores them — GPU tuning would be
 *    decorative, not real.
 *  - `/api/tags` returns Ollama's own model shape (`llama3.2:1b` tags),
 *    which is what the Local AI manager installs and references.
 *
 * Responses are NDJSON (one JSON object per line). Streaming is requested
 * always; a server that ignores `stream` and answers one JSON object is
 * handled by the non-stream fallback (Content-Type sniff, like
 * OkHttpAgentGateway). Read timeout is 240s — quantized local models on a
 * phone are SLOW, and one NDJSON line can legally take minutes to appear.
 */
class OllamaAgentGateway(
    client: OkHttpClient,
    baseUrl: String,
    private val apiKey: String,
    private val tuning: LocalAiTuning? = null
) : AgentGateway {

    private val callFactory: OkHttpClient = client.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS) // local models are slow
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val base: String = baseUrl.trim().trimEnd('/')

    init {
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "Ollama base URL must start with http:// or https://"
        }
    }

    // ------------------------------------------------------------------ chat

    override suspend fun chat(
        request: ChatRequest,
        events: suspend (StreamEvent) -> Unit
    ): ChatMessage {
        val body = AgentJson.encodeToString(WireChatRequest.serializer(), buildWireRequest(request))
        val builder = Request.Builder()
            .url("$base/api/chat")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "application/x-ndjson")
            .header("User-Agent", AgentClientIdentity.userAgent(base))
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")

        val response = execute(builder.build())
        try {
            if (!response.isSuccessful) {
                throw AgentHttpException(response.code, errorBody(response))
            }
            val contentType = response.header("Content-Type") ?: ""
            val responseBody = response.body ?: throw AgentHttpException(response.code, "empty body")
            return if (contentType.contains("x-ndjson", ignoreCase = true) ||
                contentType.contains("event-stream", ignoreCase = true)
            ) {
                parseStream(responseBody, events)
            } else {
                parseNonStream(responseBody, events)
            }
        } finally {
            runCatching { response.close() }
        }
    }

    /** Maps the loop's request into Ollama's wire shape (see wire DTOs above). */
    private fun buildWireRequest(request: ChatRequest): WireChatRequest {
        val clamped = tuning?.clampToSanity()
        val options = LinkedHashMap<String, JsonElement>()
        if (clamped != null) {
            // num_ctx always: without it Ollama defaults to the model's own
            // (often tiny) context and silently truncates the tool catalogue.
            options["num_ctx"] = JsonPrimitive(clamped.contextWindow)
            // num_gpu / num_thread only when the user set them — 0 is a legal
            // "CPU only" value, so absence ≠ null must stay distinguishable.
            clamped.gpuLayers?.let { options["num_gpu"] = JsonPrimitive(it) }
            clamped.cpuThreads?.let { options["num_thread"] = JsonPrimitive(it) }
        }
        request.temperature?.let { options["temperature"] = JsonPrimitive(it) }

        return WireChatRequest(
            model = request.model,
            messages = request.messages.map(::wireMessage),
            stream = true, // the app always asks to stream; see chat() fallback
            tools = request.tools?.map { def ->
                WireToolDef(
                    function = WireToolFunction(
                        name = def.function.name,
                        description = def.function.description,
                        parameters = def.function.parameters
                    )
                )
            },
            // Empty options (no tuning + no temperature) are omitted entirely —
            // a bare request keeps Ollama's own defaults untouched.
            options = options.takeIf { it.isNotEmpty() }?.let { JsonObject(it) },
            keepAlive = clamped?.let { "${it.keepAliveMinutes}m" }
        )
    }

    /**
     * system/user/assistant → {role, content}; assistant tool_calls → wire
     * tool_calls (arguments STRING re-parsed into a real JSON object);
     * tool results → {role:"tool", content} (Ollama's native tool shape).
     */
    private fun wireMessage(message: ChatMessage): OllamaChatMessage {
        val calls = message.toolCalls.orEmpty().mapNotNull { call ->
            val name = call.function.name.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            OllamaChatToolCall(
                function = OllamaChatFunctionCall(
                    name = name,
                    arguments = argsObject(call.function.arguments)
                )
            )
        }
        // Tool results must carry non-null content on the Ollama wire.
        val content = if (message.role == "tool") message.content.orEmpty() else message.content
        return OllamaChatMessage(
            role = message.role,
            content = content,
            toolCalls = calls.takeIf { it.isNotEmpty() },
            // Ollama's native field: raw base64, no `data:` prefix.
            images = message.images?.map { it.dataBase64 }?.takeIf { it.isNotEmpty() }
        )
    }

    /** Parses our arguments STRING back into an object; lenient fallback `{}`. */
    private fun argsObject(arguments: String): JsonObject {
        if (arguments.isBlank()) return JsonObject(emptyMap())
        return runCatching { AgentJson.parseToJsonElement(arguments) }.getOrNull()
            as? JsonObject ?: JsonObject(emptyMap())
    }

    /** Accepts `arguments` either as an object (Ollama) or a JSON string. */
    private fun argsObject(element: JsonElement?): JsonObject = when (element) {
        is JsonObject -> element
        is JsonPrimitive -> runCatching { AgentJson.parseToJsonElement(element.content) }.getOrNull()
            as? JsonObject ?: JsonObject(emptyMap())
        else -> JsonObject(emptyMap())
    }

    private suspend fun parseStream(
        body: ResponseBody,
        events: suspend (StreamEvent) -> Unit
    ): ChatMessage = withContext(Dispatchers.IO) {
        val content = StringBuilder()
        // Ordered list, NOT a name-keyed map: the browsing prompt actively
        // encourages BATCHING independent tool calls, and a batch may contain
        // the SAME function twice with different arguments (two click_element
        // refs, two opens…). A map would merge those into one call with both
        // argument sets — silently losing an action. Exact (name + arguments)
        // duplicates are deduped in [assembleAssistant] instead, which covers
        // servers that re-send a whole call on a later line.
        val toolAcc = mutableListOf<Pair<String, JsonObject>>()

        var reader: BufferedReader? = null
        try {
            reader = body.byteStream().bufferedReader(Charsets.UTF_8)
            while (true) {
                if (!currentCoroutineContext().isActive) throw CancellationException("agent stream cancelled")
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val root = runCatching {
                    AgentJson.parseToJsonElement(line) as? JsonObject
                }.getOrNull() ?: continue
                val message = root["message"] as? JsonObject

                (message?.get("content") as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }
                    ?.let {
                        content.append(it)
                        events(StreamEvent.Text(it))
                    }

                (message?.get("tool_calls") as? JsonArray)?.let { accumulateTools(it, toolAcc) }

                if ((root["done"] as? JsonPrimitive)?.booleanOrNull == true) break
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // call.cancel() surfaces as an IOException, NOT a CancellationException
            // — a cancelled coroutine must stay cancelled (mirrors AgentLoop).
            if (!currentCoroutineContext().isActive) throw CancellationException("ollama stream cancelled")
            throw AgentHttpException(-1, "ollama stream interrupted: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            runCatching { reader?.close() }
        }

        assembleAssistant(content.toString(), toolAcc)
    }

    private suspend fun parseNonStream(
        body: ResponseBody,
        events: suspend (StreamEvent) -> Unit
    ): ChatMessage = withContext(Dispatchers.IO) {
        val text = body.string()
        val root = runCatching { AgentJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
        val message = root?.get("message") as? JsonObject
        val content = (message?.get("content") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val toolAcc = mutableListOf<Pair<String, JsonObject>>()
        (message?.get("tool_calls") as? JsonArray)?.let { accumulateTools(it, toolAcc) }
        content.takeIf { it.isNotEmpty() }?.let { events(StreamEvent.Text(it)) }
        assembleAssistant(content, toolAcc)
    }

    private fun accumulateTools(array: JsonArray, into: MutableList<Pair<String, JsonObject>>) {
        array.forEach { el ->
            val fn = (el as? JsonObject)?.get("function") as? JsonObject ?: return@forEach
            val name = (fn["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@forEach
            into.add(name to argsObject(fn["arguments"]))
        }
    }

    /** Compact re-serialization of the arguments object → our arguments STRING. */
    private fun assembleAssistant(
        content: String,
        tools: List<Pair<String, JsonObject>>
    ): ChatMessage {
        // Exact (name + arguments) dedupe first: some servers re-send an
        // already-emitted call on a later line; identical twins are noise,
        // while two same-name calls with DIFFERENT arguments (a genuine batch)
        // both survive.
        val toolCalls = tools
            .distinctBy { (name, args) -> name + "\u0000" + AgentJson.encodeToString(JsonObject.serializer(), args) }
            .mapIndexed { i, (name, args) ->
                ToolCall(
                    // Ollama sends no call ids — stable per-index ids keep the
                    // loop's tool-result correlation working.
                    id = "call_$i",
                    function = FunctionCall(name, AgentJson.encodeToString(JsonObject.serializer(), args))
                )
            }
        return ChatMessage(
            role = "assistant",
            content = content.takeIf { it.isNotEmpty() },
            toolCalls = toolCalls.takeIf { it.isNotEmpty() }
        )
    }

    // ---------------------------------------------------------------- models

    override suspend fun listModels(): List<String> {
        val builder = Request.Builder()
            .url("$base/api/tags")
            .get()
            .header("Accept", "application/json")
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        val response = execute(builder.build())
        try {
            val text = withContext(Dispatchers.IO) { response.body?.string() ?: "" }
            if (!response.isSuccessful) throw AgentHttpException(response.code, text.take(500))
            val models = OllamaTagsParser.parse(text)
                .map { it.name }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
            if (models.isEmpty()) {
                // Honest message: an reachable-but-empty Ollama is not an error
                // state to retry, it is a "go install something" state.
                throw AgentHttpException(
                    response.code,
                    "the Ollama server returned no models — open Local AI to install one"
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

    /** Enqueues the call and suspends; cancelling the coroutine cancels HTTP. */
    private suspend fun execute(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = callFactory.newCall(request)
            continuation.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    if (continuation.isActive) {
                        if (call.isCanceled()) {
                            continuation.resumeWithException(CancellationException("ollama request cancelled"))
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
}
