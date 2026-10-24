package com.roombrowser.domain.agent

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The token counts a provider REPORTED on the wire, read out of the raw
 * payloads each gateway already receives.
 *
 * Everything here is pure (string in, [TurnUsage] out) so the parsing is
 * pinned by JVM unit tests rather than by live servers, and everything is
 * best-effort by contract: a payload without a usage object yields null, a
 * present-but-empty usage object yields null, and nothing here ever derives
 * a count that was not on the wire — an unknown cost stays unknown.
 */
object AgentUsage {

    /**
     * OpenAI streaming: the `usage` object of one SSE `data:` payload. With
     * `stream_options.include_usage` set on the request, the FINAL chunk of a
     * completion carries it (and its `choices` are empty); earlier chunks
     * never do, so a caller may probe every chunk — the cheap `contains`
     * inside keeps that free for the token chunks.
     */
    fun fromOpenAiChunk(payload: String): TurnUsage? {
        if (!payload.contains("\"usage\"")) return null
        return runCatching {
            openAiUsage(AgentJson.parseToJsonElement(payload).jsonObject["usage"])
        }.getOrNull()
    }

    /** OpenAI non-streaming: the top-level `usage` object of a completion body. */
    fun fromOpenAiResponse(body: String): TurnUsage? {
        if (!body.contains("\"usage\"")) return null
        return runCatching {
            openAiUsage(AgentJson.parseToJsonElement(body.trim()).jsonObject["usage"])
        }.getOrNull()
    }

    /**
     * The turn's usage assembled from the places a provider reports it
     * (Anthropic: `message_start` carries the prompt count, the final
     * `message_delta` the completion count). Null only when NEITHER side was
     * seen — a one-sided report is kept, because half the truth still beats
     * none, and an estimate is never filled in for the missing half.
     */
    fun of(promptTokens: Int?, completionTokens: Int?): TurnUsage? =
        if (promptTokens == null && completionTokens == null) null
        else TurnUsage(promptTokens, completionTokens)

    /** An OpenAI wire usage object → [TurnUsage]; absent or fieldless → null. */
    private fun openAiUsage(element: JsonElement?): TurnUsage? {
        val usage = element as? JsonObject ?: return null
        return of(usage.int("prompt_tokens"), usage.int("completion_tokens"))
    }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull
}
