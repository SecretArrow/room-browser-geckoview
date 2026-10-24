package com.roombrowser.agent

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.agent.AgentUsage
import com.roombrowser.domain.agent.AnthropicMessages
import com.roombrowser.domain.agent.TurnUsage
import org.junit.Test

/**
 * The Anthropic-side token-usage assembly, pinned as pure JVM tests:
 * `message_start` carries the prompt count, the final `message_delta` the
 * completion count, and the two combine into the turn's [TurnUsage]. What the
 * provider never sent is never produced — the combined result is null then,
 * not an estimate.
 */
class AnthropicUsageTest {

    @Test
    fun `message_start and message_delta combine into the turn's usage`() {
        val decoder = AnthropicMessages.StreamDecoder()
        decoder.feed(
            """{"type":"message_start","message":{"id":"m","role":"assistant",""" +
                """"usage":{"input_tokens":25,"output_tokens":1}}}"""
        )
        decoder.feed(
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}"""
        )
        decoder.feed(
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":15}}"""
        )

        assertThat(decoder.usage()).isEqualTo(TurnUsage(25, 15))
    }

    @Test
    fun `frames without usage leave the count unknown`() {
        val decoder = AnthropicMessages.StreamDecoder()
        decoder.feed("""{"type":"message_start","message":{"id":"m"}}""")
        decoder.feed("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""")

        assertThat(decoder.usage()).isNull()
    }

    @Test
    fun `a one-sided report is kept rather than discarded`() {
        assertThat(AgentUsage.of(promptTokens = 25, completionTokens = null))
            .isEqualTo(TurnUsage(25, null))
        assertThat(AgentUsage.of(promptTokens = null, completionTokens = 15))
            .isEqualTo(TurnUsage(null, 15))
    }

    @Test
    fun `nothing reported means null, never zero`() {
        assertThat(AgentUsage.of(promptTokens = null, completionTokens = null)).isNull()
    }
}
