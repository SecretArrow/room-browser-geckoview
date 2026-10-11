package com.roombrowser.agent

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.agent.AgentHttpException
import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.domain.agent.ChatMessage
import com.roombrowser.domain.agent.ChatRequest
import com.roombrowser.domain.agent.StreamEvent
import com.roombrowser.localai.engine.LlamaEngineApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * JVM tests for [LocalLlamaGateway] against a fake [LlamaEngineApi]:
 * model resolution from the attached models directory, message mapping,
 * the single honest Text event, and every failure mode surfacing its own
 * message (engine unavailable / model file missing / native load error).
 */
class LocalLlamaGatewayTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = createTempDirectory("localengine").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** Records every call; load/chat answers are injectable. */
    private class FakeEngine(
        override val available: Boolean = true,
        private val loadError: String? = null,
        private val chatAnswer: String = ""
    ) : LlamaEngineApi {
        val loadCalls = mutableListOf<Pair<String, File>>()
        val chatCalls = mutableListOf<List<Pair<String, String>>>()
        var unloadCount = 0

        override fun version(): String? = "b4364"

        override suspend fun load(
            modelId: String,
            file: File,
            contextTokens: Int,
            threads: Int
        ): String? {
            loadCalls.add(modelId to file)
            return loadError
        }

        override fun unload() {
            unloadCount++
        }

        override suspend fun completeRaw(
            prompt: String,
            maxTokens: Int,
            temperature: Float,
            topP: Float
        ): Result<String> = Result.success("raw continuation")

        override suspend fun chat(messages: List<Pair<String, String>>): String {
            chatCalls.add(messages)
            return chatAnswer
        }
    }

    private fun request(model: String = "m1"): ChatRequest = ChatRequest(
        model = model,
        messages = listOf(ChatMessage(role = "user", content = "hi"))
    )

    @Test
    fun `chat resolves the model file and returns the engine answer`() = runTest {
        File(dir, "m1.gguf").writeText("gguf-bytes")
        val engine = FakeEngine(available = true, chatAnswer = "hello on-device")
        val gateway = LocalLlamaGateway(engine).apply { modelsDirectory = dir }
        val events = mutableListOf<StreamEvent>()

        val message = gateway.chat(request(), events = { events.add(it) })

        assertThat(message.role).isEqualTo("assistant")
        assertThat(message.content).isEqualTo("hello on-device")
        // The engine received the mapped role→content pairs, in order.
        assertThat(engine.chatCalls).containsExactly(listOf("user" to "hi"))
        // The model file was resolved and loaded with the phone-safe defaults.
        assertThat(engine.loadCalls.map { it.first }).containsExactly("m1")
        assertThat(engine.loadCalls.single().second.name).isEqualTo("m1.gguf")
        // One honest whole-answer event — no token streaming.
        assertThat(events).containsExactly(StreamEvent.Text("hello on-device"))
    }

    @Test
    fun `missing model file throws an honest error`() = runTest {
        val gateway = LocalLlamaGateway(FakeEngine()).apply { modelsDirectory = dir }

        val thrown = assertThrows(AgentHttpException::class.java) {
            kotlinx.coroutines.runBlocking { gateway.chat(request(), events = { }) }
        }
        assertThat(thrown.message).contains("not found")
        assertThat(thrown.message).contains("m1")
    }

    @Test
    fun `engine unavailable in the build throws an honest error`() = runTest {
        File(dir, "m1.gguf").writeText("gguf-bytes")
        val gateway = LocalLlamaGateway(FakeEngine(available = false)).apply { modelsDirectory = dir }

        val thrown = assertThrows(AgentHttpException::class.java) {
            kotlinx.coroutines.runBlocking { gateway.chat(request(), events = { }) }
        }
        assertThat(thrown.message).contains("not available")
    }

    @Test
    fun `native load error surfaces verbatim`() = runTest {
        File(dir, "m1.gguf").writeText("gguf-bytes")
        val engine = FakeEngine(available = true, loadError = "boom: needs RAM")
        val gateway = LocalLlamaGateway(engine).apply { modelsDirectory = dir }

        val thrown = assertThrows(AgentHttpException::class.java) {
            kotlinx.coroutines.runBlocking { gateway.chat(request(), events = { }) }
        }
        assertThat(thrown.message).contains("boom: needs RAM")
    }

    @Test
    fun `path traversal ids never resolve a file`() = runTest {
        val gateway = LocalLlamaGateway(FakeEngine()).apply { modelsDirectory = dir }

        val thrown = assertThrows(AgentHttpException::class.java) {
            kotlinx.coroutines.runBlocking {
                gateway.chat(request(model = "../secret"), events = { })
            }
        }
        assertThat(thrown.message).contains("not found")
    }

    @Test
    fun `listModels lists gguf ids and fails honestly when empty`() = runTest {
        File(dir, "b-model.gguf").writeText("b")
        File(dir, "a-model.gguf").writeText("a")
        File(dir, "notes.txt").writeText("not a model")
        val gateway = LocalLlamaGateway(FakeEngine()).apply { modelsDirectory = dir }

        assertThat(gateway.listModels()).containsExactly("a-model", "b-model").inOrder()

        val emptyGateway = LocalLlamaGateway(FakeEngine()).apply { modelsDirectory = dir }
        dir.resolve("a-model.gguf").delete()
        dir.resolve("b-model.gguf").delete()
        val thrown = assertThrows(AgentHttpException::class.java) {
            kotlinx.coroutines.runBlocking { emptyGateway.listModels() }
        }
        assertThat(thrown.message).contains("no on-device models")
    }

    // ------------------------------------------------------------- tool calls

    /** A turn the loop offers the catalogue on, as BrowserAgentController does. */
    private fun toolRequest(model: String = "m1"): ChatRequest = ChatRequest(
        model = model,
        messages = listOf(
            ChatMessage(role = "system", content = "You are a browsing agent."),
            ChatMessage(role = "user", content = "like the visible posts")
        ),
        tools = AgentTools.toolDefs()
    )

    @Test
    fun `the catalogue reaches the engine in the system message`() = runTest {
        File(dir, "m1.gguf").writeText("gguf-bytes")
        val engine = FakeEngine(available = true, chatAnswer = "nothing to do")
        val gateway = LocalLlamaGateway(engine).apply { modelsDirectory = dir }

        gateway.chat(toolRequest(), events = { })

        val sent = engine.chatCalls.single()
        assertThat(sent.first().first).isEqualTo("system")
        assertThat(sent.first().second).contains("You are a browsing agent.")
        assertThat(sent.first().second).contains("auto_like")
        // The catalogue is the whole point of the turn: without it the model
        // has no way to name an action at all.
        assertThat(sent.first().second).contains("read_page")
    }

    @Test
    fun `a tool call reply becomes a tool call and is not streamed as text`() = runTest {
        File(dir, "m1.gguf").writeText("gguf-bytes")
        val engine = FakeEngine(
            available = true,
            chatAnswer = """{"tool":"click","args":{"ref":3}}"""
        )
        val gateway = LocalLlamaGateway(engine).apply { modelsDirectory = dir }
        val events = mutableListOf<StreamEvent>()

        val message = gateway.chat(toolRequest(), events = { events.add(it) })

        val call = message.toolCalls?.single()
        assertThat(call).isNotNull()
        assertThat(call!!.function.name).isEqualTo("click")
        assertThat(call.function.arguments).isEqualTo("""{"ref":3}""")
        assertThat(call.id).isNotEmpty()
        assertThat(message.content).isNull()
        // The raw JSON is not the answer: showing it in the bubble would be a
        // lie about what the agent did.
        assertThat(events).isEmpty()
    }

    @Test
    fun `a plain answer still streams when a catalogue was offered`() = runTest {
        File(dir, "m1.gguf").writeText("gguf-bytes")
        val engine = FakeEngine(available = true, chatAnswer = "There are three posts.")
        val gateway = LocalLlamaGateway(engine).apply { modelsDirectory = dir }
        val events = mutableListOf<StreamEvent>()

        val message = gateway.chat(toolRequest(), events = { events.add(it) })

        assertThat(message.toolCalls).isNull()
        assertThat(message.content).isEqualTo("There are three posts.")
        assertThat(events).containsExactly(StreamEvent.Text("There are three posts."))
    }

    /** The loop echoes the id back on each tool result, so they must not collide. */
    @Test
    fun `successive calls are given distinct ids`() = runTest {
        File(dir, "m1.gguf").writeText("gguf-bytes")
        val engine = FakeEngine(
            available = true,
            chatAnswer = """{"tool":"scroll","args":{"direction":"down"}}"""
        )
        val gateway = LocalLlamaGateway(engine).apply { modelsDirectory = dir }

        val first = gateway.chat(toolRequest(), events = { }).toolCalls!!.single().id
        val second = gateway.chat(toolRequest(), events = { }).toolCalls!!.single().id

        assertThat(first).isNotEqualTo(second)
    }
}
