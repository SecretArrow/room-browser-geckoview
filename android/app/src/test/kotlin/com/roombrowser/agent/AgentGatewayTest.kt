package com.roombrowser.agent

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.agent.AgentHttpException
import com.roombrowser.domain.agent.AgentJson
import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.domain.agent.ChatMessage
import com.roombrowser.domain.agent.ChatRequest
import com.roombrowser.domain.agent.StreamEvent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for the OpenAI-compatible gateway against a real local HTTP
 * server (MockWebServer): SSE streaming with tool-call deltas, non-stream
 * fallback, /models discovery shapes and error mapping.
 */
class AgentGatewayTest {

    private lateinit var server: MockWebServer
    private lateinit var gateway: OkHttpAgentGateway

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        gateway = OkHttpAgentGateway(
            client = OkHttpClient(),
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            apiKey = "test-key"
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sseBody(vararg events: String): String =
        events.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

    @Test
    fun `streams text tokens and assembles the final message`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    sseBody(
                        """{"choices":[{"delta":{"role":"assistant"}}]}""",
                        """{"choices":[{"delta":{"content":"Hel"}}]}""",
                        """{"choices":[{"delta":{"content":"lo!"}}]}"""
                    )
                )
        )
        val texts = mutableListOf<String>()
        val message = gateway.chat(request(), events = { if (it is StreamEvent.Text) texts.add(it.text) })

        assertThat(texts).containsExactly("Hel", "lo!").inOrder()
        assertThat(message.role).isEqualTo("assistant")
        assertThat(message.content).isEqualTo("Hello!")
        assertThat(message.toolCalls).isNull()

        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/v1/chat/completions")
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer test-key")
        val sent = recorded.body.readUtf8()
        assertThat(sent).contains("\"stream\":true")
        assertThat(sent).contains("\"tools\":[")
    }

    @Test
    fun `accumulates tool call deltas across chunks`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    sseBody(
                        """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"navigate","arguments":"{\"u"}}]}}]}""",
                        """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"rl\":\"https://x.dev\"}"}}]}}]}""",
                        """{"choices":[{"delta":{"content":"Opening it."}}]}"""
                    )
                )
        )
        val message = gateway.chat(request(), events = { })

        assertThat(message.content).isEqualTo("Opening it.")
        val call = message.toolCalls!!.single()
        assertThat(call.id).isEqualTo("call_1")
        assertThat(call.function.name).isEqualTo("navigate")
        assertThat(call.function.arguments).isEqualTo("""{"url":"https://x.dev"}""")
    }

    @Test
    fun `reasoning content surfaces as thinking events`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    sseBody(
                        """{"choices":[{"delta":{"reasoning_content":"hmm..."}}]}""",
                        """{"choices":[{"delta":{"content":"Answer"}}]}"""
                    )
                )
        )
        val thinking = mutableListOf<String>()
        val message = gateway.chat(request(), events = { if (it is StreamEvent.Thinking) thinking.add(it.text) })

        assertThat(thinking).containsExactly("hmm...")
        assertThat(message.content).isEqualTo("Answer")
    }

    @Test
    fun `non-stream json response is accepted as fallback`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":"1","choices":[{"message":{"role":"assistant","content":"plain answer"}}]}""")
        )
        val texts = mutableListOf<String>()
        val message = gateway.chat(request(), events = { if (it is StreamEvent.Text) texts.add(it.text) })

        assertThat(message.content).isEqualTo("plain answer")
        assertThat(texts).containsExactly("plain answer")
    }

    @Test
    fun `http error maps to agent exception with body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad key"}"""))
        val thrown = assertThrows(AgentHttpException::class.java) {
            kotlinx.coroutines.runBlocking {
                gateway.chat(request(), events = { })
            }
        }
        assertThat(thrown.code).isEqualTo(401)
        assertThat(thrown.body).contains("bad key")
    }

    @Test
    fun `models endpoint parses openai shape and sends auth header`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"object":"list","data":[{"id":"glm-4.6"},{"id":"glm-4-flash"}]}""")
        )
        val models = gateway.listModels()

        assertThat(models).containsExactly("glm-4-flash", "glm-4.6").inOrder()
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/v1/models")
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer test-key")
    }

    @Test
    fun `models endpoint accepts bare arrays and string ids`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""["b-model","a-model"]""")
        )
        val noAuth = OkHttpAgentGateway(OkHttpClient(), server.url("/v1").toString().trimEnd('/'), apiKey = "")
        assertThat(noAuth.listModels()).containsExactly("a-model", "b-model").inOrder()
        // No key configured → no Authorization header sent.
        assertThat(server.takeRequest().getHeader("Authorization")).isNull()
    }

    @Test
    fun `empty models list raises a descriptive error`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data":[]}""")
        )
        val thrown = assertThrows(AgentHttpException::class.java) {
            kotlinx.coroutines.runBlocking { gateway.listModels() }
        }
        assertThat(thrown.message).contains("manually")
    }

    @Test
    fun `request wire format contains the tool catalogue`() {
        val request = ChatRequest(
            model = "glm-4.6",
            messages = listOf(ChatMessage(role = "user", content = "open x.dev")),
            stream = true,
            tools = AgentTools.toolDefs(),
            temperature = 0.2
        )
        val json = AgentJson.encodeToString(ChatRequest.serializer(), request)
        assertThat(json).contains("\"function\":{\"name\":\"navigate\"")
        assertThat(json).contains("\"name\":\"read_page\"")
        assertThat(json).contains("\"name\":\"fill_input\"")
    }

    @Test
    fun `final usage chunk surfaces as a usage event`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    sseBody(
                        """{"choices":[{"delta":{"content":"Hi"}}]}""",
                        """{"choices":[],"usage":{"prompt_tokens":118,"completion_tokens":27}}"""
                    )
                )
        )
        val usage = mutableListOf<StreamEvent.Usage>()
        gateway.chat(request(), events = { if (it is StreamEvent.Usage) usage.add(it) })

        assertThat(usage).containsExactly(StreamEvent.Usage(118, 27))
        // The counts only exist because the request ASKED for them: the body
        // must carry stream_options.include_usage.
        assertThat(server.takeRequest().body.readUtf8())
            .contains("\"stream_options\":{\"include_usage\":true}")
    }

    @Test
    fun `non-stream body's usage surfaces as a usage event`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"choices":[{"message":{"role":"assistant","content":"plain answer"}}],""" +
                        """"usage":{"prompt_tokens":10,"completion_tokens":3}}"""
                )
        )
        val usage = mutableListOf<StreamEvent.Usage>()
        gateway.chat(request(), events = { if (it is StreamEvent.Usage) usage.add(it) })

        assertThat(usage).containsExactly(StreamEvent.Usage(10, 3))
    }

    private fun request(): ChatRequest = ChatRequest(
        model = "test-model",
        messages = listOf(ChatMessage(role = "user", content = "hi")),
        stream = true,
        tools = AgentTools.toolDefs(),
        temperature = 0.2
    )
}
