package com.roombrowser.agent

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.agent.AgentUsage
import com.roombrowser.domain.agent.AgentVisionRequest
import com.roombrowser.domain.agent.ChatMessage
import com.roombrowser.domain.agent.ChatRequest
import com.roombrowser.domain.agent.TurnUsage
import org.junit.Test

/**
 * The OpenAI-side token-usage parsing, pinned as pure JVM tests: what a
 * provider REPORTS is read exactly, what it omits stays null — no count is
 * ever derived, rounded or estimated. The request side is pinned too: a
 * streaming body must ask for usage (stream_options.include_usage), a
 * non-streaming body must not send the field.
 */
class AgentUsageParseTest {

    @Test
    fun `the final chunk's usage object is read exactly`() {
        val payload =
            """{"id":"1","object":"chat.completion.chunk","choices":[],"usage":""" +
                """{"prompt_tokens":120,"completion_tokens":45,"total_tokens":165}}"""

        assertThat(AgentUsage.fromOpenAiChunk(payload)).isEqualTo(TurnUsage(120, 45))
    }

    @Test
    fun `a token chunk without usage yields nothing`() {
        // Every ordinary token chunk — the contains() probe must reject it
        // without a parse, and the parser must answer null regardless.
        val payload = """{"choices":[{"delta":{"content":"Hel"}}]}"""
        assertThat(AgentUsage.fromOpenAiChunk(payload)).isNull()
    }

    @Test
    fun `a non-stream body's usage is read exactly`() {
        val body =
            """{"id":"1","choices":[{"message":{"role":"assistant","content":"hi"}}],""" +
                """"usage":{"prompt_tokens":10,"completion_tokens":3}}"""

        assertThat(AgentUsage.fromOpenAiResponse(body)).isEqualTo(TurnUsage(10, 3))
    }

    @Test
    fun `a usage object without both counts is not invented`() {
        // Present but empty (and one-sided) reports: the missing half stays
        // null, an all-null pair stays null — the shape of "unknown".
        assertThat(AgentUsage.fromOpenAiChunk("""{"choices":[],"usage":{}}""")).isNull()
        assertThat(
            AgentUsage.fromOpenAiResponse(
                """{"choices":[],"usage":{"completion_tokens":7}}"""
            )
        ).isEqualTo(TurnUsage(null, 7))
    }

    @Test
    fun `a garbage payload degrades to null rather than throwing`() {
        assertThat(AgentUsage.fromOpenAiChunk("""{"usage": "not an object""")).isNull()
        assertThat(AgentUsage.fromOpenAiChunk("not json at all")).isNull()
        assertThat(AgentUsage.fromOpenAiResponse("")).isNull()
    }

    @Test
    fun `a streaming body asks the provider for usage`() {
        val body = AgentVisionRequest.openAiBody(
            ChatRequest(
                model = "m",
                messages = listOf(ChatMessage(role = "user", content = "hi")),
                stream = true
            )
        )

        assertThat(body).contains("\"stream_options\":{\"include_usage\":true}")
    }

    @Test
    fun `a non-streaming body stays without stream_options`() {
        val body = AgentVisionRequest.openAiBody(
            ChatRequest(
                model = "m",
                messages = listOf(ChatMessage(role = "user", content = "hi")),
                stream = false
            )
        )

        assertThat(body).doesNotContain("stream_options")
    }

    @Test
    fun `an image-bearing streaming body still asks for usage`() {
        // The images patch and the usage patch must compose: the usage key is
        // added before the messages are rewritten.
        val body = AgentVisionRequest.openAiBody(
            ChatRequest(
                model = "m",
                messages = listOf(
                    ChatMessage(role = "user", content = "look", images = emptyList())
                ),
                stream = true
            )
        )

        assertThat(body).contains("\"stream_options\":{\"include_usage\":true}")
        assertThat(body).doesNotContain("\"images\"")
    }
}
