package com.roombrowser.agent

import com.roombrowser.domain.agent.AgentClientIdentity
import com.roombrowser.domain.agent.AgentGateway
import com.roombrowser.domain.agent.AgentHttpException
import com.roombrowser.domain.agent.AgentJson
import com.roombrowser.domain.agent.AgentVisionRequest
import com.roombrowser.domain.agent.ChatMessage
import com.roombrowser.domain.agent.ChatRequest
import com.roombrowser.domain.agent.ChatResponse
import com.roombrowser.domain.agent.FunctionCall
import com.roombrowser.domain.agent.ModelListParser
import com.roombrowser.domain.agent.SseParser
import com.roombrowser.domain.agent.StreamChunk
import com.roombrowser.domain.agent.StreamEvent
import com.roombrowser.domain.agent.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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

/**
 * OpenAI-compatible HTTP transport for the browsing agent (chat
 * completions with SSE streaming + /models discovery).
 *
 * Any provider exposing {baseUrl}/chat/completions and {baseUrl}/models
 * works: Z.ai, OpenAI, OpenRouter, Groq, DeepSeek, Mistral, Together,
 * Ollama, LM Studio, custom gateways.
 *
 * The HTTP client is derived from the browser's profile-aware client
 * (honors DoH configuration) with LLM-appropriate timeouts: long read
 * timeout for slow generations, no whole-call timeout for streams.
 */
class OkHttpAgentGateway(
    client: OkHttpClient,
    baseUrl: String,
    private val apiKey: String
) : AgentGateway {

    private val callFactory: OkHttpClient = client.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val base: String = baseUrl.trim().trimEnd('/')

    init {
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "Provider base URL must start with http:// or https://"
        }
    }

    // ------------------------------------------------------------------ chat

    override suspend fun chat(
        request: ChatRequest,
        events: suspend (StreamEvent) -> Unit
    ): ChatMessage {
        val body = AgentVisionRequest.openAiBody(request)
        val builder = Request.Builder()
            .url("$base/chat/completions")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "text/event-stream")
            // A provider that refuses non-CLI clients must be told so here as
            // well: the Anthropic gateway falls back to THIS transport against
            // the same base URL, and a request that keeps the app's own name
            // would be turned away on the same gate. See AgentClientIdentity.
            .header("User-Agent", AgentClientIdentity.userAgent(base))
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")

        val response = execute(builder.build())
        try {
            if (!response.isSuccessful) {
                throw AgentHttpException(response.code, errorBody(response))
            }
            val contentType = response.header("Content-Type") ?: ""
            val responseBody = response.body ?: throw AgentHttpException(response.code, "empty body")
            return if (contentType.contains("text/event-stream", ignoreCase = true)) {
                parseStream(responseBody, events)
            } else {
                parseNonStream(responseBody, events)
            }
        } finally {
            runCatching { response.close() }
        }
    }

    private suspend fun parseStream(
        body: ResponseBody,
        events: suspend (StreamEvent) -> Unit
    ): ChatMessage = withContext(Dispatchers.IO) {
        val parser = SseParser()
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val tools = sortedMapOf<Int, ToolAccumulator>()

        var reader: BufferedReader? = null
        try {
            reader = body.byteStream().bufferedReader(Charsets.UTF_8)
            while (true) {
                if (!currentCoroutineContext().isActive) throw CancellationException("agent stream cancelled")
                val line = reader.readLine() ?: break
                val payload = parser.feed(line) ?: continue
                if (payload == "[DONE]") break
                val chunk = runCatching {
                    AgentJson.decodeFromString(StreamChunk.serializer(), payload)
                }.getOrNull() ?: continue
                chunk.choices.forEach { choice ->
                    val delta = choice.delta ?: return@forEach
                    delta.content?.takeIf { it.isNotEmpty() }?.let {
                        content.append(it)
                        events(StreamEvent.Text(it))
                    }
                    delta.reasoningContent?.takeIf { it.isNotEmpty() }?.let {
                        reasoning.append(it)
                        events(StreamEvent.Thinking(it))
                    }
                    delta.toolCalls?.forEach { dtc ->
                        val acc = tools.getOrPut(dtc.index) { ToolAccumulator() }
                        dtc.id?.takeIf { it.isNotBlank() }?.let { acc.id = it }
                        dtc.function?.name?.takeIf { it.isNotBlank() }?.let { name ->
                            if (acc.name.isEmpty() || !acc.name.endsWith(name)) acc.name.append(name)
                        }
                        dtc.function?.arguments?.let { acc.arguments.append(it) }
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            throw AgentHttpException(-1, "stream interrupted: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            runCatching { reader?.close() }
        }

        val toolCalls = tools.values
            .filter { it.name.isNotEmpty() }
            .mapIndexed { i, acc ->
                ToolCall(
                    id = acc.id.takeIf { it.isNotBlank() } ?: "call_$i",
                    function = FunctionCall(acc.name.toString(), acc.arguments.toString().ifBlank { "{}" })
                )
            }
        ChatMessage(
            role = "assistant",
            content = content.toString().takeIf { it.isNotEmpty() },
            toolCalls = toolCalls.takeIf { it.isNotEmpty() }
        )
    }

    private suspend fun parseNonStream(
        body: ResponseBody,
        events: suspend (StreamEvent) -> Unit
    ): ChatMessage = withContext(Dispatchers.IO) {
        val text = body.string()
        val message = runCatching {
            AgentJson.decodeFromString(ChatResponse.serializer(), text).firstMessage
        }.getOrNull() ?: ChatMessage(role = "assistant", content = "")
        message.content?.takeIf { it.isNotEmpty() }?.let { events(StreamEvent.Text(it)) }
        message
    }

    private class ToolAccumulator {
        var id: String = ""
        val name = StringBuilder()
        val arguments = StringBuilder()
    }

    // ---------------------------------------------------------------- models

    override suspend fun listModels(): List<String> {
        val builder = Request.Builder()
            .url("$base/models")
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", AgentClientIdentity.userAgent(base))
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        val response = execute(builder.build())
        try {
            val text = withContext(Dispatchers.IO) { response.body?.string() ?: "" }
            if (!response.isSuccessful) throw AgentHttpException(response.code, text.take(500))
            val models = ModelListParser.parse(text)
            if (models.isEmpty()) {
                throw AgentHttpException(response.code, "the provider returned no model ids — enter the model id manually")
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
                            continuation.resumeWithException(CancellationException("agent request cancelled"))
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
        /** Normalizes user-entered base URLs (trims spaces + trailing slash). */
        fun normalizeBaseUrl(input: String): String =
            input.trim().trimEnd('/')
    }
}
