package com.roombrowser.agent

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.agent.AgentHttpException
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
 * JVM tests for the Anthropic Messages gateway against a real local HTTP
 * server (MockWebServer). The pure wire translation is covered exhaustively by
 * AnthropicMessagesTest in :core:domain — here we test the HTTP plumbing and,
 * above all, the "try both at runtime" probe: a 404 or an OpenAI-shaped 200 on
 * `POST /messages` must transparently fall back to `/chat/completions` without
 * ever double-emitting, and the resolved transport must then be cached; a real
 * error (401, a mid-stream error frame) must surface, not fall back.
 */
class AnthropicAgentGatewayTest {

    private lateinit var server: MockWebServer
    private lateinit var gateway: AnthropicAgentGateway

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        gateway = AnthropicAgentGateway(
            client = OkHttpClient(),
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            apiKey = "test-key"
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Anthropic SSE: one `data:` line per event, blank-line terminated, and
     *  crucially NO `[DONE]` sentinel (Anthropic ends with `message_stop`). */
    private fun anthropicSse(vararg events: String): String =
        events.joinToString("") { "data: $it\n\n" }

    /** OpenAI SSE, as the fallback endpoint would answer. */
    private fun openAiSse(vararg events: String): String =
        events.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

    // ---------------------------------------------------------- anthropic path

    @Test
    fun `streams anthropic text deltas and sends the anthropic headers`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    anthropicSse(
                        """{"type":"message_start","message":{"id":"m1"}}""",
                        """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}""",
                        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo!"}}""",
                        """{"type":"content_block_stop","index":0}""",
                        """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
                        """{"type":"message_stop"}"""
                    )
                )
        )
        val texts = mutableListOf<String>()
        val message = gateway.chat(request(), events = { if (it is StreamEvent.Text) texts.add(it.text) })

        assertThat(texts).containsExactly("Hel", "lo!").inOrder()
        assertThat(message.content).isEqualTo("Hello!")
        assertThat(message.toolCalls).isNull()

        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/v1/messages")
        assertThat(recorded.getHeader("anthropic-version")).isEqualTo("2023-06-01")
        assertThat(recorded.getHeader("x-api-key")).isEqualTo("test-key")
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer test-key")
        val sent = recorded.body.readUtf8()
        assertThat(sent).contains("\"max_tokens\":")
        assertThat(sent).contains("\"system\":") // lifted out of messages
        assertThat(sent).contains("\"input_schema\":") // tools use the anthropic shape
    }

    @Test
    fun `reassembles a tool call from input_json_delta fragments`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    anthropicSse(
                        """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"navigate"}}""",
                        """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"url\":"}}""",
                        """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\"https://x.dev\"}"}}""",
                        """{"type":"content_block_stop","index":0}""",
                        """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}"""
                    )
                )
        )
        val message = gateway.chat(request(), events = { })

        val call = message.toolCalls!!.single()
        assertThat(call.id).isEqualTo("toolu_1")
        assertThat(call.function.name).isEqualTo("navigate")
        assertThat(call.function.arguments).isEqualTo("""{"url":"https://x.dev"}""")
    }

    @Test
    fun `a mid-stream error frame surfaces as an exception`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(anthropicSse("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""))
        )
        val thrown = assertThrows(AgentHttpException::class.java) {
            kotlinx.coroutines.runBlocking { gateway.chat(request(), events = { }) }
        }
        assertThat(thrown.message).contains("Overloaded")
    }

    @Test
    fun `a real 401 surfaces and does NOT trigger the fallback`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"type":"error","error":{"message":"bad key"}}"""))
        val thrown = assertThrows(AgentHttpException::class.java) {
            kotlinx.coroutines.runBlocking { gateway.chat(request(), events = { }) }
        }
        assertThat(thrown.code).isEqualTo(401)
        assertThat(thrown.body).contains("bad key")
        // No second request was attempted — 401 is a genuine error, not a
        // shape mismatch. (A fallback would have hung on an empty queue.)
        assertThat(server.requestCount).isEqualTo(1)
    }

    // ----------------------------------------------------------- fallback path

    @Test
    fun `a 404 on messages falls back to the openai endpoint without double-emitting`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("no such route"))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    openAiSse(
                        """{"choices":[{"delta":{"content":"Hel"}}]}""",
                        """{"choices":[{"delta":{"content":"lo!"}}]}"""
                    )
                )
        )
        val texts = mutableListOf<String>()
        val message = gateway.chat(request(), events = { if (it is StreamEvent.Text) texts.add(it.text) })

        assertThat(texts).containsExactly("Hel", "lo!").inOrder() // emitted once, from OpenAI only
        assertThat(message.content).isEqualTo("Hello!")
        assertThat(server.takeRequest().path).isEqualTo("/v1/messages")
        assertThat(server.takeRequest().path).isEqualTo("/v1/chat/completions")
    }

    @Test
    fun `an openai-shaped 200 on messages falls back too`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":"1","choices":[{"message":{"role":"assistant","content":"ignored"}}]}""")
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(openAiSse("""{"choices":[{"delta":{"content":"real answer"}}]}"""))
        )
        val message = gateway.chat(request(), events = { })

        assertThat(message.content).isEqualTo("real answer")
        assertThat(server.takeRequest().path).isEqualTo("/v1/messages")
        assertThat(server.takeRequest().path).isEqualTo("/v1/chat/completions")
    }

    @Test
    fun `the resolved transport is cached so later turns skip the probe`() = runTest {
        // Turn 1: probe → 404 → fall back to /chat/completions.
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(openAiSse("""{"choices":[{"delta":{"content":"one"}}]}"""))
        )
        gateway.chat(request(), events = { })
        assertThat(server.takeRequest().path).isEqualTo("/v1/messages")
        assertThat(server.takeRequest().path).isEqualTo("/v1/chat/completions")

        // Turn 2: must go STRAIGHT to /chat/completions — no /messages probe.
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(openAiSse("""{"choices":[{"delta":{"content":"two"}}]}"""))
        )
        val second = gateway.chat(request(), events = { })
        assertThat(second.content).isEqualTo("two")
        assertThat(server.takeRequest().path).isEqualTo("/v1/chat/completions")
    }

    // ---------------------------------------------------------------- models

    @Test
    fun `listModels uses the openai models convention`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"object":"list","data":[{"id":"deepseek-v4-flash"},{"id":"claude-3-5"}]}""")
        )
        val models = gateway.listModels()

        assertThat(models).containsExactly("claude-3-5", "deepseek-v4-flash").inOrder()
        assertThat(server.takeRequest().path).isEqualTo("/v1/models")
    }

    private fun request(): ChatRequest = ChatRequest(
        model = "deepseek-v4-flash",
        messages = listOf(
            ChatMessage(role = "system", content = "You are a browsing agent."),
            ChatMessage(role = "user", content = "open x.dev")
        ),
        stream = true,
        tools = AgentTools.toolDefs(),
        temperature = 0.2
    )
}
