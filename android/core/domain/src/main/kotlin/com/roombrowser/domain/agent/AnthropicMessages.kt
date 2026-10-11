package com.roombrowser.domain.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Translation between the app's OpenAI-shaped agent conversation model
 * ([ChatRequest]/[ChatMessage]/[ToolCall]) and the Anthropic Messages API
 * (`POST {base}/messages`), which several aggregators — AgentRouter among
 * them — speak through the `@ai-sdk/anthropic` SDK.
 *
 * The two APIs differ in more than the path, and every one of these has to be
 * handled or the request is rejected outright:
 *
 *  - the system prompt is a TOP-LEVEL `system` field, not a message;
 *  - `max_tokens` is REQUIRED;
 *  - `tools` entries carry `input_schema`, not `function.parameters`, and
 *    have no `type: "function"` wrapper;
 *  - a tool result is a `tool_result` block inside a USER message, not a
 *    `role: "tool"` message. Every result answering one assistant turn must
 *    be merged into that single user message: Anthropic rejects two
 *    consecutive user messages, so one `tool_result` block per message is
 *    only ever correct when the model asked for exactly one tool;
 *  - streaming is typed events (`content_block_delta` with `text_delta` /
 *    `input_json_delta` / `thinking_delta`) rather than OpenAI's `choices[].delta`.
 *
 * Everything here is pure — no Android, no HTTP — so the whole translation is
 * covered by the core unit tests rather than only by an emulator run.
 */
object AnthropicMessages {

    /** The `anthropic-version` header value. Pinned deliberately: the API
     *  requires an explicit date and its shape is version-dependent. */
    const val VERSION: String = "2023-06-01"

    /**
     * Anthropic requires `max_tokens` and it is a hard ceiling on the reply,
     * not a target. Tool calls are small (a name plus a JSON argument object)
     * but a final answer can be long, so this is generous — a truncated
     * answer would otherwise look like the model stopping mid-sentence.
     */
    const val DEFAULT_MAX_TOKENS: Int = 4096

    fun endpoint(baseUrl: String): String = "${baseUrl.trim().trimEnd('/')}/messages"

    // ------------------------------------------------------------ request

    /** Builds the `POST /messages` body for [request]. */
    fun buildRequest(request: ChatRequest, maxTokens: Int = DEFAULT_MAX_TOKENS): String {
        val system = request.messages
            .filter { it.role == "system" }
            .mapNotNull { it.content?.takeIf { c -> c.isNotBlank() } }
            .joinToString("\n\n")

        val body = buildJsonObject {
            put("model", request.model)
            put("max_tokens", maxTokens)
            // Explicit `stream`, so the caller's choice is never inferred.
            put("stream", request.stream)
            if (system.isNotEmpty()) put("system", system)
            request.temperature?.let { put("temperature", it) }
            put("messages", conversation(request.messages))
            request.tools?.takeIf { it.isNotEmpty() }?.let { tools ->
                putJsonArray("tools") { tools.forEach { add(tool(it)) } }
            }
        }
        return body.toString()
    }

    /**
     * Maps the conversation to Anthropic's `messages` array.
     *
     * Consecutive `role: "tool"` messages are collapsed into ONE user message
     * carrying a `tool_result` block each — the reason is in the class comment.
     */
    private fun conversation(messages: List<ChatMessage>): JsonArray = buildJsonArray {
        var i = 0
        while (i < messages.size) {
            val m = messages[i]
            when (m.role) {
                "system" -> i++   // lifted to the top-level field by buildRequest

                "tool" -> {
                    // Collect the whole run of results answering one turn into
                    // ONE user message carrying a tool_result block each.
                    val blocks = buildJsonArray {
                        while (i < messages.size && messages[i].role == "tool") {
                            val t = messages[i]
                            val id = t.toolCallId
                            if (!id.isNullOrBlank()) {
                                addJsonObject {
                                    put("type", "tool_result")
                                    put("tool_use_id", id)
                                    put("content", t.content.orEmpty())
                                }
                            }
                            i++
                        }
                    }
                    if (blocks.isNotEmpty()) {
                        // A screenshot a tool captured arrives as a user message
                        // of its own, right after the results it belongs to.
                        // Anthropic rejects two consecutive user messages, so
                        // it is folded into THIS one, beside the tool_result
                        // blocks it answers.
                        val attached = buildJsonArray {
                            while (i < messages.size && messages[i].role == "user" &&
                                !messages[i].images.isNullOrEmpty()
                            ) {
                                val u = messages[i]
                                u.content?.takeIf { it.isNotBlank() }?.let { text ->
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", text)
                                    }
                                }
                                u.images.orEmpty().forEach { add(imageBlock(it)) }
                                i++
                            }
                        }
                        addJsonObject {
                            put("role", "user")
                            put("content", if (attached.isEmpty()) blocks else JsonArray(blocks + attached))
                        }
                    }
                }

                else -> {
                    addJsonObject {
                        put("role", m.role)
                        put("content", contentOf(m))
                    }
                    i++
                }
            }
        }
    }

    /**
     * A message's `content`: a plain string when it carries neither tool calls
     * nor images (which is what Anthropic expects and what reads best),
     * otherwise the block array carrying the assistant's text, its images and
     * its `tool_use` blocks.
     *
     * An assistant that only called tools has no text at all, and an empty
     * string block is rejected — so the text block is omitted entirely.
     */
    private fun contentOf(m: ChatMessage): kotlinx.serialization.json.JsonElement {
        val calls = m.toolCalls.orEmpty()
        val images = m.images.orEmpty()
        if (calls.isEmpty() && images.isEmpty()) return JsonPrimitive(m.content.orEmpty())

        return buildJsonArray {
            m.content?.takeIf { it.isNotBlank() }?.let { text ->
                addJsonObject {
                    put("type", "text")
                    put("text", text)
                }
            }
            images.forEach { add(imageBlock(it)) }
            calls.forEach { call ->
                addJsonObject {
                    put("type", "tool_use")
                    put("id", call.id)
                    put("name", call.function.name)
                    // `input` is an OBJECT here; OpenAI carries the same thing
                    // as a JSON-encoded string, so a malformed one degrades to
                    // `{}` rather than failing the whole request.
                    put("input", parseObjectOrEmpty(call.function.arguments))
                }
            }
        }
    }

    private fun imageBlock(image: AgentImage): JsonObject = buildJsonObject {
        put("type", "image")
        putJsonObject("source") {
            put("type", "base64")
            put("media_type", image.mediaType)
            put("data", image.dataBase64)
        }
    }

    private fun tool(def: ToolDef): JsonObject = buildJsonObject {
        put("name", def.function.name)
        put("description", def.function.description)
        put("input_schema", def.function.parameters)
    }

    private fun parseObjectOrEmpty(json: String): JsonObject =
        runCatching { AgentJson.parseToJsonElement(json).jsonObject }.getOrElse { JsonObject(emptyMap()) }

    // ----------------------------------------------------------- response

    /** Parses a NON-streaming `POST /messages` response into the app's model. */
    fun parseResponse(body: String): ChatMessage {
        val root = runCatching { AgentJson.parseToJsonElement(body.trim()).jsonObject }
            .getOrElse { return ChatMessage(role = "assistant") }   // null content, like an empty parsed message
        return messageOf(root["content"] as? JsonArray)
    }

    private fun messageOf(blocks: JsonArray?): ChatMessage {
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        blocks?.forEach { block ->
            val obj = block as? JsonObject ?: return@forEach
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> obj["text"]?.jsonPrimitive?.contentOrNull?.let { text.append(it) }
                "thinking" -> obj["thinking"]?.jsonPrimitive?.contentOrNull?.let { text.append(it) }
                "tool_use" -> {
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (name.isNotBlank()) {
                        calls.add(
                            ToolCall(
                                id = id.ifBlank { "call_${calls.size}" },
                                function = FunctionCall(
                                    name = name,
                                    arguments = obj["input"]?.toString() ?: "{}"
                                )
                            )
                        )
                    }
                }
            }
        }
        return ChatMessage(
            role = "assistant",
            content = text.toString().takeIf { it.isNotEmpty() },
            toolCalls = calls.takeIf { it.isNotEmpty() }
        )
    }

    // ------------------------------------------------------------- stream

    /**
     * Folds one provider's SSE stream into the app's [ChatMessage].
     *
     * Stateful by design: Anthropic announces a tool call in
     * `content_block_start` and then dribbles its arguments out as
     * `input_json_delta` fragments keyed by block index, so the index → call
     * mapping has to survive across [feed] calls.
     */
    class StreamDecoder {

        private val text = StringBuilder()
        private val thinking = StringBuilder()
        private val tools = sortedMapOf<Int, ToolAccumulator>()

        /** Set when the stream reports `error`; the gateway surfaces it. */
        var errorMessage: String? = null
            private set

        /** `tool_use`, `max_tokens`, `end_turn`… — kept for diagnostics. */
        var stopReason: String? = null
            private set

        /**
         * Consumes one SSE `data:` payload and returns the events it produced
         * (empty for payloads that carry no user-visible text, which is most
         * of them). An unparseable payload yields nothing rather than
         * throwing: one bad frame must not kill a whole answer.
         */
        fun feed(payload: String): List<StreamEvent> {
            val obj = runCatching { AgentJson.parseToJsonElement(payload).jsonObject }.getOrNull()
                ?: return emptyList()
            return when (val type = obj["type"]?.jsonPrimitive?.contentOrNull) {
                "content_block_start" -> {
                    val index = obj["index"]?.jsonPrimitive?.intOrNull ?: 0
                    val block = obj["content_block"] as? JsonObject
                    if (block != null && block.string("type") == "tool_use") {
                        val acc = tools.getOrPut(index) { ToolAccumulator() }
                        block.string("id")?.let { acc.id = it }
                        block.string("name")?.let { acc.name.append(it) }
                    }
                    emptyList()
                }

                "content_block_delta" -> {
                    val index = obj["index"]?.jsonPrimitive?.intOrNull ?: 0
                    val delta = obj["delta"] as? JsonObject ?: return emptyList()
                    when (delta.string("type")) {
                        "text_delta" -> delta.string("text")?.takeIf { it.isNotEmpty() }?.let {
                            text.append(it)
                            return listOf(StreamEvent.Text(it))
                        }
                        "thinking_delta" -> delta.string("thinking")?.takeIf { it.isNotEmpty() }?.let {
                            thinking.append(it)
                            return listOf(StreamEvent.Thinking(it))
                        }
                        "input_json_delta" -> {
                            delta.string("partial_json")?.let { part ->
                                tools.getOrPut(index) { ToolAccumulator() }.arguments.append(part)
                            }
                        }
                    }
                    emptyList()
                }

                "message_delta" -> {
                    (obj["delta"] as? JsonObject)?.string("stop_reason")?.let { stopReason = it }
                    emptyList()
                }

                "error" -> {
                    val err = obj["error"] as? JsonObject
                    errorMessage = err?.string("message")
                        ?: obj.string("message")
                        ?: "the provider reported an error"
                    emptyList()
                }

                else -> emptyList()   // message_start / content_block_stop / ping
            }
        }

        /** The assistant message the accumulated stream amounts to. */
        fun finish(): ChatMessage {
            val calls = tools.values
                .filter { it.name.isNotEmpty() }
                .mapIndexed { i, acc ->
                    ToolCall(
                        id = acc.id.ifBlank { "call_$i" },
                        function = FunctionCall(
                            name = acc.name.toString(),
                            arguments = acc.arguments.toString().ifBlank { "{}" }
                        )
                    )
                }
            return ChatMessage(
                role = "assistant",
                content = text.toString().takeIf { it.isNotEmpty() },
                toolCalls = calls.takeIf { it.isNotEmpty() }
            )
        }

        /** True when the model produced only reasoning and no answer text. */
        fun thinkingOnly(): Boolean = text.isEmpty() && thinking.isNotEmpty()
    }

    private class ToolAccumulator {
        var id: String = ""
        val name = StringBuilder()
        val arguments = StringBuilder()
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
