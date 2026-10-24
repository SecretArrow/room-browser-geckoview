package com.roombrowser.domain.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * OpenAI-compatible chat-completions DTOs used by the browsing agent.
 *
 * The wire format follows the de-facto standard shared by OpenAI, Z.ai,
 * OpenRouter, Groq, DeepSeek, Mistral, Together, Ollama (/v1) and LM Studio:
 *
 *   POST {baseUrl}/chat/completions  {model, messages, tools, stream, temperature}
 *   GET  {baseUrl}/models            {"data":[{"id":"..."}]} (or a bare array)
 *
 * One shared [Json] instance is used for both directions:
 *  - encodeDefaults=true  → `stream:true` / `type:"function"` always sent
 *  - explicitNulls=false  → null content / absent tools are omitted
 */
val AgentJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
    val tools: List<ToolDef>? = null,
    val temperature: Double? = null
)

@Serializable
data class ToolDef(
    val type: String = "function",
    val function: ToolFunction
)

@Serializable
data class ToolFunction(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    /**
     * Screenshots for this message. A separate field, not a content-parts
     * union, so a provider that cannot see them is a per-gateway answer rather
     * than a serialization error. Null (the default) is omitted by [AgentJson].
     */
    val images: List<AgentImage>? = null,
    /**
     * What the provider said this message cost, when it said anything. Set by
     * the gateway that read the wire; null (the default) is what every gateway
     * that does not parse usage answers, and what the loop STRIPS before the
     * message re-enters a request history — a usage block is a response
     * concept, and echoing it back risks a strict provider refusing the
     * request over an argument it never asked for. The caller that wants the
     * number reads it before the loop files the message away.
     */
    val usage: TokenUsage? = null
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall
)

@Serializable
data class FunctionCall(val name: String, val arguments: String)

// ---------- Non-streaming response ----------

@Serializable
data class ChatResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<RespChoice> = emptyList(),
    /** `usage` on the non-streaming body; absent on providers that omit it. */
    val usage: StreamUsage? = null
) {
    val firstMessage: ChatMessage?
        get() = choices.firstOrNull()?.message
}

@Serializable
data class RespChoice(
    val index: Int = 0,
    val message: ChatMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

// ---------- Streaming chunks (SSE `data:` payloads) ----------

@Serializable
data class StreamChunk(
    val id: String? = null,
    val model: String? = null,
    val choices: List<StreamChoice> = emptyList(),
    /**
     * `usage` on a streaming chunk. The OpenAI-compatible shape only carries
     * it when the client asks for `stream_options.include_usage` and not every
     * provider honours even that, so this is OPTIONAL on purpose: absent is
     * the normal case, and the estimate fallback lives in the loop, not here.
     */
    val usage: StreamUsage? = null
)

@Serializable
data class StreamChoice(
    val index: Int = 0,
    val delta: Delta? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class Delta(
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<DeltaToolCall>? = null
)

@Serializable
data class DeltaToolCall(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: FnDelta? = null
)

@Serializable
data class FnDelta(
    val name: String? = null,
    val arguments: String? = null
)

/**
 * Provider HTTP failure. [body] is the provider's raw reply (kept whole for
 * callers that want it), while the EXCEPTION MESSAGE — which is what the UI
 * renders — carries only the human sentence from it, via [ProviderErrorText].
 * A refusal otherwise reaches the user as a wall of JSON whose one useful
 * sentence ("unauthorized client detected, …") is buried in the middle.
 */
class AgentHttpException(val code: Int, val body: String) :
    Exception("HTTP $code${suffix(body)}") {
    private companion object {
        /** ": sentence" when the body says something, "" when it says nothing. */
        fun suffix(body: String): String {
            val text = ProviderErrorText.extract(body)
            return if (text.isBlank()) "" else ": $text"
        }
    }
}

/**
 * The human sentence inside a provider's error body.
 *
 * Every gateway used to hand the raw body to [AgentHttpException], so a
 * refusal surfaced in the UI as a wall of JSON — for example
 * `{"error":{"message":"unauthorized client detected, contact support …"},
 * "message":"UNAUTHENTICATED","success":false}` where the only part worth
 * reading is one sentence. Providers are also not uniform about where that
 * sentence lives, hence the several keys.
 *
 * Falls back to the trimmed body when nothing recognisable is found: a
 * confusing-but-complete message beats a swallowed error.
 */
object ProviderErrorText {

    /** Longest sentence echoed; a provider cannot flood the UI with a novel. */
    const val MAX_CHARS: Int = 300

    fun extract(body: String, maxChars: Int = MAX_CHARS): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return ""
        val sentence = runCatching { fromJson(trimmed) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: trimmed
        return if (sentence.length <= maxChars) sentence else sentence.take(maxChars) + "…"
    }

    /** `error.message` → `error` (a bare string) → `message` → `detail`. */
    private fun fromJson(body: String): String? {
        val root = AgentJson.parseToJsonElement(body)
        val obj = root as? JsonObject ?: return null
        val error = obj["error"]
        (error as? JsonObject)?.string("message")?.let { return it }
        (error as? JsonPrimitive)?.contentOrNull?.let { return it }
        obj.string("message")?.let { return it }
        obj.string("detail")?.let { return it }
        return null
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}

/**
 * Parses `/models` responses leniently. Accepted shapes:
 *  {"object":"list","data":[{"id":"m1"},...]}   (OpenAI-compatible)
 *  {"data":["m1","m2"]}                          (string ids)
 *  ["m1","m2"]                                    (bare array)
 *  {"models":[{"id":"m1"}]}                       (alternate key)
 */
object ModelListParser {

    fun parse(body: String): List<String> = runCatching {
        val element = AgentJson.parseToJsonElement(body.trim())
        extractModelIds(element)
    }.getOrDefault(emptyList())

    private fun extractModelIds(element: JsonElement): List<String> {
        val candidates: List<JsonElement> = when (element) {
            is JsonArray -> element.jsonArray.toList()
            is JsonObject -> {
                val arr = element.jsonObject["data"] as? JsonArray
                    ?: element.jsonObject["models"] as? JsonArray
                arr?.toList() ?: emptyList()
            }
            else -> emptyList()
        }
        return candidates.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull?.takeIf { it.isNotBlank() }
                is JsonObject -> {
                    val id = (item["id"] ?: item["name"]) as? JsonPrimitive
                    id?.contentOrNull?.takeIf { it.isNotBlank() }
                }
                else -> null
            }
        }.distinct().sorted()
    }
}
