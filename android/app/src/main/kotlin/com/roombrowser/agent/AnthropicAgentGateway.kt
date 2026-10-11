package com.roombrowser.agent

import com.roombrowser.domain.agent.AgentClientIdentity
import com.roombrowser.domain.agent.AgentGateway
import com.roombrowser.domain.agent.AgentHttpException
import com.roombrowser.domain.agent.AgentJson
import com.roombrowser.domain.agent.AnthropicMessages
import com.roombrowser.domain.agent.ChatMessage
import com.roombrowser.domain.agent.ChatRequest
import com.roombrowser.domain.agent.SseParser
import com.roombrowser.domain.agent.StreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
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
 * Anthropic Messages transport (`POST {base}/messages`) for providers that
 * speak the `@ai-sdk/anthropic` shape — AgentRouter among them.
 *
 * **Try both at runtime.** Some aggregators advertise the Anthropic SDK but
 * actually serve the OpenAI `/chat/completions` shape (or expose both). So the
 * FIRST turn probes: it sends the Anthropic-shaped request and, ONLY on a
 * signal that the endpoint is not Anthropic-shaped, falls back to the OpenAI
 * transport against the same base URL. The two fallback signals are:
 *
 *  - `404`/`405` from `POST /messages` — the route does not exist here;
 *  - a `200` whose body is a non-streaming OpenAI `choices` object.
 *
 * Both are detected from the HTTP status / body BEFORE a single token is
 * streamed to the caller, so switching transports can never double-emit. The
 * resolved transport is then cached ([resolved]), so later turns pay no probe
 * cost. A genuine provider error (401, a real Anthropic `4xx`, a mid-stream
 * error frame) is surfaced as [AgentHttpException] and never triggers the
 * fallback.
 *
 * All wire-shape translation lives in the pure, unit-tested [AnthropicMessages];
 * this class is only the OkHttp plumbing around it, mirroring
 * [OkHttpAgentGateway] (same timeouts, same cancellation semantics).
 */
class AnthropicAgentGateway(
    private val client: OkHttpClient,
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

    /** The OpenAI-shaped transport used once the probe says this provider is
     *  not really Anthropic. Built from the SAME client (it applies its own
     *  LLM timeouts) against the same base URL, so a fallback turn is exactly
     *  the OpenAI path with nothing special about it. */
    private val openAi: OkHttpAgentGateway by lazy { OkHttpAgentGateway(client, base, apiKey) }

    /** null until the first turn resolves it; then pinned for the session. */
    @Volatile private var resolved: Transport? = null

    init {
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "Provider base URL must start with http:// or https://"
        }
    }

    private enum class Transport { ANTHROPIC, OPENAI }

    /** Thrown INTERNALLY, always before any event is emitted, when the probe
     *  finds the endpoint is not Anthropic-shaped. Never escapes this class. */
    private class ShapeMismatch(message: String) : Exception(message)

    // ------------------------------------------------------------------ chat

    override suspend fun chat(
        request: ChatRequest,
        events: suspend (StreamEvent) -> Unit
    ): ChatMessage = when (resolved) {
        Transport.OPENAI -> openAi.chat(request, events)
        Transport.ANTHROPIC -> anthropicChat(request, events)
        null -> try {
            val message = anthropicChat(request, events)
            resolved = Transport.ANTHROPIC
            message
        } catch (mismatch: ShapeMismatch) {
            // The probe proved this base speaks OpenAI, not Anthropic — and it
            // did so before a single token reached [events], so switching now
            // cannot double-emit. Remember it and never probe again.
            resolved = Transport.OPENAI
            openAi.chat(request, events)
        }
    }

    private suspend fun anthropicChat(
        request: ChatRequest,
        events: suspend (StreamEvent) -> Unit
    ): ChatMessage {
        val body = AnthropicMessages.buildRequest(request)
        val builder = Request.Builder()
            .url(AnthropicMessages.endpoint(base))
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "text/event-stream")
            // Not the app's own name for a provider that refuses it: see
            // AgentClientIdentity — this base URL is read for the answer.
            .header("User-Agent", AgentClientIdentity.userAgent(base))
            .header("anthropic-version", AnthropicMessages.VERSION)
        if (apiKey.isNotBlank()) {
            // Anthropic authenticates with x-api-key; OpenAI-shaped aggregators
            // read Authorization. Sending both lets EITHER accept the request —
            // each ignores the header it does not use.
            builder.header("x-api-key", apiKey)
            builder.header("Authorization", "Bearer $apiKey")
        }

        val response = execute(builder.build())
        try {
            // 404/405 on /messages: the route does not exist here → this is an
            // OpenAI-only provider. No body read yet, no event emitted.
            if (response.code == 404 || response.code == 405) {
                throw ShapeMismatch("POST /messages returned ${response.code}")
            }
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
        val sse = SseParser()
        val decoder = AnthropicMessages.StreamDecoder()
        var reader: BufferedReader? = null
        try {
            reader = body.byteStream().bufferedReader(Charsets.UTF_8)
            while (true) {
                if (!currentCoroutineContext().isActive) throw CancellationException("agent stream cancelled")
                val line = reader.readLine() ?: break
                val payload = sse.feed(line) ?: continue
                if (payload == "[DONE]") break // not sent by Anthropic; harmless if it is
                decoder.feed(payload).forEach { events(it) }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            throw AgentHttpException(-1, "stream interrupted: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            runCatching { reader?.close() }
        }
        // A mid-stream `error` frame (e.g. overloaded_error) is a real failure,
        // not a shape mismatch: surface it so the UI shows why the turn ended.
        decoder.errorMessage?.let { throw AgentHttpException(-1, it) }
        decoder.finish()
    }

    private suspend fun parseNonStream(
        body: ResponseBody,
        events: suspend (StreamEvent) -> Unit
    ): ChatMessage = withContext(Dispatchers.IO) {
        val text = body.string()
        // A non-streaming 200 that is actually an OpenAI `choices` body means
        // the provider ignored the Anthropic shape — fall back. Nothing has
        // been emitted yet, so this is still safe.
        if (looksOpenAiShaped(text)) throw ShapeMismatch("200 body is OpenAI-shaped")
        val message = AnthropicMessages.parseResponse(text)
        message.content?.takeIf { it.isNotEmpty() }?.let { events(StreamEvent.Text(it)) }
        message
    }

    /** True when [body] is an OpenAI chat object (`choices`) rather than an
     *  Anthropic message (`content` blocks). Garbage → false (let the Anthropic
     *  parser degrade to an empty message instead of re-probing pointlessly). */
    private fun looksOpenAiShaped(body: String): Boolean = runCatching {
        val obj = AgentJson.parseToJsonElement(body.trim()).jsonObject
        obj.containsKey("choices") && !obj.containsKey("content")
    }.getOrDefault(false)

    // ---------------------------------------------------------------- models

    /**
     * Model discovery uses the OpenAI `GET /models` convention, which the
     * Anthropic-speaking aggregators (AgentRouter) expose the same way — so
     * this delegates to the OpenAI transport. The Anthropic presets also ship
     * their known models as pick-list chips, and any model id can be typed, so
     * a provider without a `/models` route is never a dead end.
     */
    override suspend fun listModels(): List<String> = openAi.listModels()

    // ----------------------------------------------------------------- utils

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
}
