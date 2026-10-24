package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * The exact wire shape each provider receives when a message carries an
 * image. These are literal-string comparisons on purpose: a regression in a
 * request body is caught here instead of only against a live provider.
 */
class AgentVisionWireTest {

    private val image = AgentImage(
        dataBase64 = "QUJD",
        mediaType = "image/png",
        width = 640,
        height = 480
    )

    // -------------------------------------------------------- [OI]-compatible

    @Test
    fun `openai image message becomes a text plus image_url parts array`() {
        val request = ChatRequest(
            model = "glm-4.6",
            messages = listOf(
                ChatMessage(role = "user", content = "what is this?", images = listOf(image))
            )
        )
        assertThat(AgentVisionRequest.openAiBody(request)).isEqualTo(
            """{"model":"glm-4.6","messages":[{"role":"user","content":""" +
                """[{"type":"text","text":"what is this?"},""" +
                """{"type":"image_url","image_url":{"url":"data:image/png;base64,QUJD"}}]}],""" +
                """"stream":true,"stream_options":{"include_usage":true}}"""
        )
    }

    @Test
    fun `an openai image only message carries no empty text part`() {
        val request = ChatRequest(
            model = "m",
            messages = listOf(ChatMessage(role = "user", images = listOf(image)))
        )
        val messages = AgentJson.parseToJsonElement(AgentVisionRequest.openAiBody(request))
            .jsonObject["messages"]!!.jsonArray
        val content = messages[0].jsonObject["content"]!!.jsonArray
        assertThat(content).hasSize(1)
        assertThat(content[0].jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("image_url")
    }

    // ------------------------------------------------------------ compatibility

    @Test
    fun `a message with no images serializes exactly as it does today`() {
        val message = ChatMessage(role = "user", content = "hi")
        val plain = AgentJson.encodeToString(ChatMessage.serializer(), message)
        assertThat(plain).isEqualTo("""{"role":"user","content":"hi"}""")
        // No stray "images" key, and the request body is unchanged too.
        assertThat(plain).doesNotContain("images")
        assertThat(
            AgentVisionRequest.openAiBody(ChatRequest(model = "m", messages = listOf(message)))
        ).isEqualTo(
            """{"model":"m","messages":[{"role":"user","content":"hi"}],"stream":true,""" +
                """"stream_options":{"include_usage":true}}"""
        )
    }

    @Test
    fun `an empty images list does not leak a stray images key`() {
        val request = ChatRequest(
            model = "m",
            messages = listOf(ChatMessage(role = "user", content = "hi", images = emptyList()))
        )
        assertThat(AgentVisionRequest.openAiBody(request))
            .isEqualTo(
            """{"model":"m","messages":[{"role":"user","content":"hi"}],"stream":true,""" +
                """"stream_options":{"include_usage":true}}"""
        )
    }

    // ---------------------------------------------------------------- Anthropic

    @Test
    fun `anthropic image message becomes an image block in the content array`() {
        val request = ChatRequest(
            model = "m",
            messages = listOf(
                ChatMessage(role = "user", content = "look", images = listOf(image))
            )
        )
        assertThat(AnthropicMessages.buildRequest(request)).isEqualTo(
            """{"model":"m","max_tokens":4096,"stream":true,"messages":""" +
                """[{"role":"user","content":[{"type":"text","text":"look"},""" +
                """{"type":"image","source":{"type":"base64","media_type":"image/png",""" +
                """"data":"QUJD"}}]}]}"""
        )
    }

    @Test
    fun `anthropic text only message keeps its plain string content`() {
        val request = ChatRequest(
            model = "m",
            messages = listOf(ChatMessage(role = "user", content = "hi"))
        )
        val messages = AgentJson.parseToJsonElement(AnthropicMessages.buildRequest(request))
            .jsonObject["messages"]!!.jsonArray
        assertThat(messages[0].jsonObject["content"]!!.jsonPrimitive.content).isEqualTo("hi")
    }

    // ------------------------------------------------------------------- Ollama

    @Test
    fun `ollama image message carries a raw base64 images array`() {
        val message = OllamaChatMessage(role = "user", content = "look", images = listOf("QUJD"))
        assertThat(AgentJson.encodeToString(OllamaChatMessage.serializer(), message))
            .isEqualTo("""{"role":"user","content":"look","images":["QUJD"]}""")
    }

    @Test
    fun `ollama message without images serializes as before`() {
        assertThat(
            AgentJson.encodeToString(
                OllamaChatMessage.serializer(),
                OllamaChatMessage(role = "tool", content = "ok")
            )
        ).isEqualTo("""{"role":"tool","content":"ok"}""")
    }
}
