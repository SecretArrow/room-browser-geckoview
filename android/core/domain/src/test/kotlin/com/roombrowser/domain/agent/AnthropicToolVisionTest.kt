package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * A screenshot a tool captured rides in a user turn of its own (see
 * [AgentLoop.runTurn]). Anthropic rejects two consecutive user messages, so it
 * must be folded into the tool_result message it answers rather than sent as a
 * second user message.
 */
class AnthropicToolVisionTest {

    private val image = AgentImage(dataBase64 = "QUJD", mediaType = "image/jpeg", width = 1280, height = 720)

    private fun messagesOf(request: ChatRequest) =
        AgentJson.parseToJsonElement(AnthropicMessages.buildRequest(request))
            .jsonObject["messages"]!!.jsonArray

    @Test
    fun `a screenshot after tool results joins the same user message`() {
        val messages = messagesOf(
            ChatRequest(
                model = "m",
                messages = listOf(
                    ChatMessage(
                        role = "assistant",
                        toolCalls = listOf(ToolCall(id = "t1", function = FunctionCall("see_page", "{}")))
                    ),
                    ChatMessage(role = "tool", content = "Screenshot of the page", toolCallId = "t1"),
                    ChatMessage(
                        role = "user",
                        content = "The page as the last tool captured it.",
                        images = listOf(image)
                    )
                )
            )
        )

        assertThat(messages).hasSize(2)
        assertThat(messages[1].jsonObject["role"]!!.jsonPrimitive.content).isEqualTo("user")
        val content = messages[1].jsonObject["content"]!!.jsonArray
        assertThat(content).hasSize(3)
        assertThat(content[0].jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("tool_result")
        assertThat(content[1].jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("text")
        assertThat(content[2].jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("image")
    }

    @Test
    fun `a tool run with no picture stays two messages and an ordinary reply`() {
        val messages = messagesOf(
            ChatRequest(
                model = "m",
                messages = listOf(
                    ChatMessage(
                        role = "assistant",
                        toolCalls = listOf(ToolCall(id = "t1", function = FunctionCall("read_page", "{}")))
                    ),
                    ChatMessage(role = "tool", content = "text only", toolCallId = "t1"),
                    ChatMessage(role = "user", content = "carry on")
                )
            )
        )

        assertThat(messages).hasSize(3)
        assertThat(messages[2].jsonObject["content"]!!.jsonPrimitive.content).isEqualTo("carry on")
    }
}
