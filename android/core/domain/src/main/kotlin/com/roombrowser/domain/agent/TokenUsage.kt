package com.roombrowser.domain.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * Token accounting for one turn, one chat or one day: how much the model read
 * and how much it wrote.
 *
 * A value is REAL when the provider reported it in the response's `usage`
 * block (OpenAI-compatible final chunks and non-streaming bodies, Anthropic
 * `message_start`/`message_delta`), and ESTIMATED when it did not — see
 * [TokenUsageEstimate]. The two are not distinguished once summed: the panel
 * shows one pair of numbers per chat, and the honest sentence about where the
 * numbers came from lives in the UI that renders them, not in the type.
 */
@Serializable
data class TokenUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0
) {
    val isZero: Boolean get() = inputTokens <= 0L && outputTokens <= 0L

    operator fun plus(other: TokenUsage): TokenUsage = TokenUsage(
        inputTokens = inputTokens + other.inputTokens,
        outputTokens = outputTokens + other.outputTokens
    )

    /** One line for an export or a dialog: "1,234 in · 5,678 out". */
    fun render(): String =
        "${count(inputTokens)} in · ${count(outputTokens)} out"

    companion object {
        val ZERO = TokenUsage()

        /** Grouped, locale-fixed: an export reads the same on every device. */
        fun count(n: Long): String = String.format(Locale.US, "%,d", n)
    }
}

/**
 * The `usage` object exactly as the WIRE carries it, kept apart from
 * [TokenUsage] on purpose: the wire spells the fields `prompt_tokens`/
 * `completion_tokens`, and those snake_case names must never leak into a
 * request body — the loop strips a returned message's usage before the
 * message re-enters the history it will be sent back inside (see
 * [AgentLoop.runTurn]), but the DTO boundary is the first wall.
 */
@Serializable
data class StreamUsage(
    @SerialName("prompt_tokens") val promptTokens: Long = 0,
    @SerialName("completion_tokens") val completionTokens: Long = 0
) {
    fun toTokenUsage(): TokenUsage = TokenUsage(promptTokens, completionTokens)
}

/**
 * The deterministic fallback for providers that send no `usage`.
 *
 * WHY chars/4: it is the folklore constant for English prose (a token is
 * ~4 characters of it), and the point here is a stable, dependency-free
 * number the user can reason about — not accuracy for code, CJK or
 * tokenizer-specific merges. It is computed from the characters the request
 * and the reply actually carried, so the SAME turn always yields the SAME
 * estimate, on every device and every re-run; a number that moves under a
 * replay would be worse than a slightly wrong one.
 *
 * Integer division, no rounding: an empty side estimates as 0, never 1.
 */
object TokenUsageEstimate {

    /** Characters per token for prose, the heuristic's one constant. */
    const val CHARS_PER_TOKEN = 4

    fun fromChars(inputChars: Long, outputChars: Long): TokenUsage = TokenUsage(
        inputTokens = inputChars / CHARS_PER_TOKEN,
        outputTokens = outputChars / CHARS_PER_TOKEN
    )
}
