package com.roombrowser.agent

import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.db.AgentProviderEntity
import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.domain.agent.ChatMessage
import com.roombrowser.domain.agent.ChatRequest
import com.roombrowser.domain.agent.LocalAiTuning
import com.roombrowser.domain.agent.OllamaPullEvent
import com.roombrowser.domain.agent.StreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * JVM tests for the Local AI (Ollama) engine against a real local HTTP
 * server (MockWebServer): native /api/chat NDJSON streaming + tool calls,
 * tuning options on the wire, /api/tags model discovery, and the management
 * client (version / pull / delete) including the PAUSE→RESUME cancellation
 * contract LocalAiController is built on.
 *
 * LocalAiController itself is deliberately NOT unit-tested here: it needs an
 * Android Application + the Room object graph, so its behavior is covered by
 * the instrumentation/e2e tests (see the Local AI UI work stream).
 */
class OllamaLocalTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        // Resilient on purpose: the pull-cancellation test leaves MockWebServer's
        // throttled writer sleeping on a socket the client already abandoned via
        // call.cancel() — server.shutdown() can then
        // surface the forced interrupt as an IOException. That is harness
        // noise from the intentional stall, not a contract failure (the same
        // pattern the e2e tests use for their fake servers).
        runCatching { server.shutdown() }
    }

    /** Base URL exactly as a user would store it (trailing slash, normalized inside). */
    private val base: String
        get() = server.url("/").toString()

    private fun ndjson(vararg lines: String): String =
        lines.joinToString("\n") { it } + "\n"

    private fun chatDone(content: String): String =
        ndjson("""{"model":"llama3.2:1b","message":{"role":"assistant","content":"$content"},"done":true}""")

    private fun request(temperature: Double? = null): ChatRequest = ChatRequest(
        model = "llama3.2:1b",
        messages = listOf(
            ChatMessage(role = "system", content = "You browse the web."),
            ChatMessage(role = "user", content = "hi")
        ),
        stream = true,
        tools = AgentTools.toolDefs(),
        temperature = temperature
    )

    // ------------------------------------------------------------- version

    @Test
    fun `version parses the version field`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"version":"0.5.7"}""")
        )
        val client = OllamaClient(OkHttpClient(), base)

        assertThat(client.version()).isEqualTo("0.5.7")
        assertThat(server.takeRequest().path).isEqualTo("/api/version")
    }

    // ------------------------------------------------------------- models

    @Test
    fun `gateway listModels hits api tags and returns sorted model names`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"models":[
                      {"name":"qwen2.5:0.5b","model":"qwen2.5:0.5b","modified_at":"2024-11-13T15:04:44.857166Z","size":392784621,"digest":"sha256:a8b5f3f2b04b8d0e1d","details":{"parent_model":"","format":"gguf","family":"qwen2","families":["qwen2"],"parameter_size":"0.5B","quantization_level":"Q4_K_M"}},
                      {"name":"llama3.2:1b","model":"llama3.2:1b","modified_at":"2024-09-26T17:01:21.809002Z","size":1621136208,"digest":"sha256:c4879b32b0a2a4d1","details":{"parent_model":"","format":"gguf","family":"llama","families":["llama"],"parameter_size":"1.2B","quantization_level":"Q4_K_M"}}
                    ]}
                    """.trimIndent()
                )
        )
        val gateway = AgentGateways.forProvider(
            OkHttpClient(),
            base,
            apiKey = "",
            protocol = AgentProviderEntity.PROTOCOL_OLLAMA
        )

        assertThat(gateway.listModels()).containsExactly("llama3.2:1b", "qwen2.5:0.5b").inOrder()
        assertThat(server.takeRequest().path).isEqualTo("/api/tags")
    }

    // ------------------------------------------------------------- chat

    @Test
    fun `chat streams ndjson content deltas and assembles the message`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/x-ndjson")
                .setBody(
                    ndjson(
                        """{"model":"llama3.2:1b","message":{"role":"assistant","content":"Hel"},"done":false}""",
                        """{"model":"llama3.2:1b","message":{"role":"assistant","content":"lo!"},"done":false}""",
                        """{"model":"llama3.2:1b","message":{"role":"assistant","content":""},"done":true}"""
                    )
                )
        )
        val gateway = OllamaAgentGateway(OkHttpClient(), base, apiKey = "")
        val texts = mutableListOf<String>()

        val message = gateway.chat(request(), events = { if (it is StreamEvent.Text) texts.add(it.text) })

        assertThat(texts).containsExactly("Hel", "lo!").inOrder()
        assertThat(message.role).isEqualTo("assistant")
        assertThat(message.content).isEqualTo("Hello!")
        assertThat(message.toolCalls).isNull()

        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/api/chat")
        assertThat(recorded.getHeader("Accept")).contains("x-ndjson")
        val sent = recorded.body.readUtf8()
        assertThat(sent).contains("\"stream\":true")
        assertThat(sent).contains("\"tools\":[")
        // The tool catalogue keeps Ollama's expected OpenAI-style shape.
        assertThat(sent).contains("\"type\":\"function\"")
    }

    @Test
    fun `chat parses tool calls with object arguments`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/x-ndjson")
                .setBody(
                    ndjson(
                        """{"model":"llama3.2:1b","message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"click_element","arguments":{"ref":"7"}}}]},"done":true}"""
                    )
                )
        )
        val gateway = OllamaAgentGateway(OkHttpClient(), base, apiKey = "")

        val message = gateway.chat(request(), events = { })

        val call = message.toolCalls!!.single()
        assertThat(call.id).isEqualTo("call_0")
        assertThat(call.function.name).isEqualTo("click_element")
        // Ollama's arguments OBJECT re-serialized as our compact arguments STRING.
        assertThat(call.function.arguments).isEqualTo("""{"ref":"7"}""")
    }

    @Test
    fun `chat keeps batched same-name tool calls as separate calls`() = runTest {
        // The browsing prompt ENCOURAGES batching independent actions; a batch
        // can legitimately call the same function twice with different refs.
        // A name-keyed accumulator would merge them (losing an action) — this
        // test pins the ordered-list behavior.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/x-ndjson")
                .setBody(
                    ndjson(
                        """{"model":"llama3.2:1b","message":{"role":"assistant","content":"","tool_calls":[""" +
                            """{"function":{"name":"click_element","arguments":{"ref":"7"}}},""" +
                            """{"function":{"name":"click_element","arguments":{"ref":"12"}}},""" +
                            """{"function":{"name":"open_url","arguments":{"url":"https://example.com"}}}""" +
                            """]},"done":true}"""
                    )
                )
        )
        val gateway = OllamaAgentGateway(OkHttpClient(), base, apiKey = "")

        val message = gateway.chat(request(), events = { })

        val calls = message.toolCalls!!
        assertThat(calls).hasSize(3)
        assertThat(calls[0].function.name).isEqualTo("click_element")
        assertThat(calls[0].function.arguments).isEqualTo("""{"ref":"7"}""")
        assertThat(calls[1].function.name).isEqualTo("click_element")
        assertThat(calls[1].function.arguments).isEqualTo("""{"ref":"12"}""")
        assertThat(calls[2].function.name).isEqualTo("open_url")
        // Re-sent identical calls (server quirk) are deduped, different ones kept.
        assertThat(calls.map { it.id }).containsNoDuplicates()
    }

    @Test
    fun `tuning options and keep_alive are sent on the wire`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/x-ndjson")
                .setBody(chatDone("ok"))
        )
        val tuned = OllamaAgentGateway(
            OkHttpClient(), base, apiKey = "",
            tuning = LocalAiTuning(gpuLayers = 12, contextWindow = 4096, keepAliveMinutes = 10)
        )
        tuned.chat(request(), events = { })

        val sent = server.takeRequest().body.readUtf8()
        assertThat(sent).contains("\"num_gpu\":12")
        assertThat(sent).contains("\"num_ctx\":4096")
        assertThat(sent).contains("\"keep_alive\":\"10m\"")
        assertThat(sent).contains("\"stream\":true")

        // Without tuning: no hardware options, no keep_alive, no options at all.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/x-ndjson")
                .setBody(chatDone("ok"))
        )
        val plain = OllamaAgentGateway(OkHttpClient(), base, apiKey = "")
        plain.chat(request(), events = { })

        val sentPlain = server.takeRequest().body.readUtf8()
        assertThat(sentPlain).doesNotContain("num_gpu")
        assertThat(sentPlain).doesNotContain("num_ctx")
        assertThat(sentPlain).doesNotContain("keep_alive")
        assertThat(sentPlain).doesNotContain("\"options\"")
    }

    @Test
    fun `chat temperature passthrough lands in options`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/x-ndjson")
                .setBody(chatDone("ok"))
        )
        val gateway = OllamaAgentGateway(OkHttpClient(), base, apiKey = "")

        gateway.chat(request(temperature = 0.2), events = { })

        assertThat(server.takeRequest().body.readUtf8()).contains("\"temperature\":0.2")
    }

    @Test
    fun `non-stream json response is accepted as fallback`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"model":"llama3.2:1b","message":{"role":"assistant","content":"plain answer"},"done":true}""")
        )
        val gateway = OllamaAgentGateway(OkHttpClient(), base, apiKey = "")
        val texts = mutableListOf<String>()

        val message = gateway.chat(request(), events = { if (it is StreamEvent.Text) texts.add(it.text) })

        assertThat(message.content).isEqualTo("plain answer")
        assertThat(texts).containsExactly("plain answer")
    }

    // ------------------------------------------------------------- pull

    private val pullBody: String = ndjson(
        """{"status":"pulling manifest"}""",
        """{"status":"downloading","digest":"sha256:abcdef1234","completed":100,"total":200}""",
        """{"status":"verifying sha256 digest"}""",
        """{"status":"success"}"""
    )

    @Test
    fun `pull streams ndjson progress events and returns on success`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/x-ndjson")
                .setBody(pullBody)
        )
        val client = OllamaClient(OkHttpClient(), base)
        val events = mutableListOf<OllamaPullEvent>()

        client.pull("llama3.2:1b") { event -> events.add(event) }

        assertThat(events).hasSize(4)
        assertThat(events.first().isDownloading).isFalse()
        assertThat(events[1].isDownloading).isTrue()
        assertThat(events.last().isTerminal).isTrue()

        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/api/pull")
        val sent = recorded.body.readUtf8()
        assertThat(sent).contains("\"model\":\"llama3.2:1b\"")
        assertThat(sent).contains("\"stream\":true")
    }

    @Test
    fun `pull cancellation surfaces as CancellationException and a second pull resumes`() = runBlocking {
        // Response 1: throttled so the stream stalls right after the first
        // line — the window in which the user presses Pause.
        //
        // The two bounds this assertion must keep apart:
        //   WORKING watcher → call.cancel() aborts the blocked read in
        //                     milliseconds (one dispatched continuation).
        //   BROKEN watcher  → the read only frees when the next chunk lands
        //                     or the read times out, whichever comes first.
        // Both halves of that pair are now pinned by this test rather than
        // inherited from app code. The chunk period is 600 s and the client
        // below is built with a 1200 s read timeout, so the chunk is the ONLY
        // way a broken watcher can escape — and the 120 s window is a 5x margin
        // under it.
        //
        // The window has now flaked three times, each time with this exact
        // signature (TimeoutCancellation at the await below) on app code that
        // was byte-identical to a green run of the same commit in the sibling
        // repo. Each widening pulls the chunk period and the read timeout out
        // with it rather than narrowing the margin: a starved runner DELAYS the
        // abort, it does not reorder it, so tolerance bought at constant margin
        // costs no discrimination. The margin is in fact wider than it has ever
        // been.
        // Response 2: the re-issued (resumed) pull, served completely.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/x-ndjson")
                .setBody(pullBody)
                .throttleBody(48, 600, TimeUnit.SECONDS)
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/x-ndjson")
                .setBody(pullBody)
        )

        val client = OllamaClient(OkHttpClient(), base, readTimeoutSeconds = 1200)
        val firstEvent = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val pullJob = scope.async {
            client.pull("llama3.2:1b") { firstEvent.complete(Unit) }
        }

        // Wait for the first NDJSON line, then PAUSE (cancel the job).
        // (First throttle chunk is written immediately, so 60 s is generous
        // headroom for thread scheduling on a starved runner.)
        withTimeout(60_000) { firstEvent.await() }
        pullJob.cancel()
        // The cancelled pull must return PROMPTLY (the blocked read is aborted
        // via call.cancel()) and surface as a CancellationException — that is
        // the exact contract LocalAiController.pause() relies on. 120 s is 5x
        // under the 600 s a broken watcher would need (see the bounds above).
        val surfaced = withTimeout(120_000) { runCatching { pullJob.await() }.exceptionOrNull() }
        assertThat(surfaced).isInstanceOf(CancellationException::class.java)
        assertThat(server.takeRequest().path).isEqualTo("/api/pull")
        scope.cancel()

        // RESUME: a fresh pull on the same client/server completes normally —
        // Ollama skips its already-completed blobs server-side.
        val resumed = mutableListOf<OllamaPullEvent>()
        client.pull("llama3.2:1b") { event -> resumed.add(event) }

        assertThat(resumed).hasSize(4)
        assertThat(resumed.last().isTerminal).isTrue()
        val second = server.takeRequest()
        assertThat(second.path).isEqualTo("/api/pull")
        assertThat(second.body.readUtf8()).contains("\"model\":\"llama3.2:1b\"")
    }

    // ------------------------------------------------------------- delete

    @Test
    fun `delete issues DELETE to api delete with the model body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val client = OllamaClient(OkHttpClient(), base)

        client.delete("llama3.2:1b")

        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("DELETE")
        assertThat(recorded.path).isEqualTo("/api/delete")
        assertThat(recorded.body.readUtf8()).contains("\"model\":\"llama3.2:1b\"")
    }
}
