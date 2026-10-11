package com.roombrowser.domain.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Anthropic Messages translation is the part of the AgentRouter support
 * that cannot be exercised by an emulator run without a live provider, so it
 * is covered here instead: the request shape, the tool-result merging rule
 * Anthropic enforces, the non-streaming response, and the streaming decoder.
 */
class AnthropicMessagesTest {

    private fun body(request: ChatRequest): JsonObject =
        AgentJson.parseToJsonElement(AnthropicMessages.buildRequest(request)).jsonObject

    private fun def(name: String, description: String = "does a thing") = ToolDef(
        function = ToolFunction(
            name = name,
            description = description,
            parameters = AgentJson.parseToJsonElement(
                """{"type":"object","properties":{"url":{"type":"string"}}}"""
            ).jsonObject
        )
    )

    // ------------------------------------------------------------- endpoint

    @Test
    fun `endpoint appends messages and normalises a trailing slash`() {
        assertEquals(
            "https://agentrouter.org/v1/messages",
            AnthropicMessages.endpoint("https://agentrouter.org/v1")
        )
        assertEquals(
            "https://agentrouter.org/v1/messages",
            AnthropicMessages.endpoint("https://agentrouter.org/v1/")
        )
    }

    // -------------------------------------------------------------- request

    @Test
    fun `system prompt is lifted out of messages into the top-level field`() {
        val b = body(
            ChatRequest(
                model = "deepseek-v4-flash",
                messages = listOf(
                    ChatMessage(role = "system", content = "You are a browsing agent."),
                    ChatMessage(role = "user", content = "open example.com")
                )
            )
        )
        assertEquals("You are a browsing agent.", b["system"]?.jsonPrimitive?.content)
        val messages = b["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun `max_tokens is always present because the API requires it`() {
        val b = body(ChatRequest(model = "m", messages = listOf(ChatMessage(role = "user", content = "hi"))))
        assertEquals(AnthropicMessages.DEFAULT_MAX_TOKENS, b["max_tokens"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `tools use input_schema with no function wrapper`() {
        val b = body(
            ChatRequest(
                model = "m",
                messages = listOf(ChatMessage(role = "user", content = "hi")),
                tools = listOf(def("navigate"))
            )
        )
        val tool = b["tools"]!!.jsonArray[0].jsonObject
        assertEquals("navigate", tool["name"]?.jsonPrimitive?.content)
        assertNotNull(tool["input_schema"])
        assertNull("Anthropic has no type:function wrapper", tool["type"])
        assertNull(tool["function"])
    }

    @Test
    fun `an assistant tool call becomes an assistant message with tool_use blocks`() {
        val b = body(
            ChatRequest(
                model = "m",
                messages = listOf(
                    ChatMessage(
                        role = "assistant",
                        content = "Opening it now.",
                        toolCalls = listOf(
                            ToolCall(
                                id = "toolu_1",
                                function = FunctionCall("navigate", """{"url":"https://example.com"}""")
                            )
                        )
                    )
                )
            )
        )
        val blocks = b["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("text", blocks[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("tool_use", blocks[1].jsonObject["type"]?.jsonPrimitive?.content)
        // `input` is an OBJECT here — OpenAI carries the same value as a string.
        assertEquals(
            "https://example.com",
            blocks[1].jsonObject["input"]!!.jsonObject["url"]?.jsonPrimitive?.content
        )
    }

    @Test
    fun `an assistant that only called tools carries no empty text block`() {
        val b = body(
            ChatRequest(
                model = "m",
                messages = listOf(
                    ChatMessage(
                        role = "assistant",
                        content = null,
                        toolCalls = listOf(ToolCall(id = "t1", function = FunctionCall("read_page", "{}")))
                    )
                )
            )
        )
        val blocks = b["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(1, blocks.size)
        assertEquals("tool_use", blocks[0].jsonObject["type"]?.jsonPrimitive?.content)
    }

    /**
     * The rule that makes a naive port fail: Anthropic rejects two consecutive
     * user messages, so N tool results answering one assistant turn must
     * arrive as N blocks of ONE user message.
     */
    @Test
    fun `consecutive tool results merge into a single user message`() {
        val b = body(
            ChatRequest(
                model = "m",
                messages = listOf(
                    ChatMessage(role = "user", content = "do two things"),
                    ChatMessage(
                        role = "assistant",
                        toolCalls = listOf(
                            ToolCall(id = "t1", function = FunctionCall("a", "{}")),
                            ToolCall(id = "t2", function = FunctionCall("b", "{}"))
                        )
                    ),
                    ChatMessage(role = "tool", content = "first result", toolCallId = "t1"),
                    ChatMessage(role = "tool", content = "second result", toolCallId = "t2")
                )
            )
        )
        val messages = b["messages"]!!.jsonArray
        assertEquals("the two results must not become two user messages", 3, messages.size)
        val results = messages[2].jsonObject["content"]!!.jsonArray
        assertEquals("user", messages[2].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals(2, results.size)
        assertEquals("tool_result", results[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("t1", results[0].jsonObject["tool_use_id"]?.jsonPrimitive?.content)
        assertEquals("first result", results[0].jsonObject["content"]?.jsonPrimitive?.content)
        assertEquals("t2", results[1].jsonObject["tool_use_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a malformed tool argument string degrades to an empty input object`() {
        val b = body(
            ChatRequest(
                model = "m",
                messages = listOf(
                    ChatMessage(
                        role = "assistant",
                        toolCalls = listOf(ToolCall(id = "t1", function = FunctionCall("a", "not json")))
                    )
                )
            )
        )
        val block = b["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals(0, block["input"]!!.jsonObject.size)
    }

    // ------------------------------------------------------------- response

    @Test
    fun `non-streaming text and tool_use blocks are both parsed`() {
        val message = AnthropicMessages.parseResponse(
            """
            {"id":"msg_1","role":"assistant","content":[
              {"type":"text","text":"Let me look."},
              {"type":"tool_use","id":"toolu_9","name":"navigate","input":{"url":"https://example.com"}}
            ],"stop_reason":"tool_use"}
            """.trimIndent()
        )
        assertEquals("assistant", message.role)
        assertEquals("Let me look.", message.content)
        assertEquals(1, message.toolCalls!!.size)
        assertEquals("toolu_9", message.toolCalls!![0].id)
        assertEquals("navigate", message.toolCalls!![0].function.name)
        assertEquals("""{"url":"https://example.com"}""", message.toolCalls!![0].function.arguments)
    }

    @Test
    fun `a garbage response body yields an empty assistant message not a crash`() {
        val message = AnthropicMessages.parseResponse("<html>502 Bad Gateway</html>")
        assertEquals("assistant", message.role)
        assertNull(message.content)
        assertNull(message.toolCalls)
    }

    // --------------------------------------------------------------- stream

    @Test
    fun `streamed text deltas accumulate into the answer`() {
        val d = AnthropicMessages.StreamDecoder()
        val seen = mutableListOf<String>()
        d.feed("""{"type":"message_start","message":{"id":"m1"}}""").forEach { }
        seen += d.feed("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""")
            .filterIsInstance<StreamEvent.Text>().map { it.text }
        seen += d.feed("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}""")
            .filterIsInstance<StreamEvent.Text>().map { it.text }
        seen += d.feed("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}""")
            .filterIsInstance<StreamEvent.Text>().map { it.text }
        assertEquals(listOf("Hel", "lo"), seen)
        assertEquals("Hello", d.finish().content)
        assertNull(d.finish().toolCalls)
    }

    @Test
    fun `thinking deltas surface as Thinking and stay out of the answer`() {
        val d = AnthropicMessages.StreamDecoder()
        val ev = d.feed("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hmm"}}""")
        assertEquals(listOf(StreamEvent.Thinking("hmm")), ev)
        assertEquals("", d.finish().content.orEmpty())
        assertTrue(d.thinkingOnly())
    }

    /**
     * Arguments arrive as JSON *fragments*; reassembling them by block index
     * is the whole reason the decoder is stateful.
     */
    @Test
    fun `tool arguments split across deltas are reassembled by block index`() {
        val d = AnthropicMessages.StreamDecoder()
        d.feed("""{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_2","name":"navigate"}}""")
        d.feed("""{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"url\":"}}""")
        d.feed("""{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"https://example.com\"}"}}""")
        d.feed("""{"type":"content_block_stop","index":1}""")
        d.feed("""{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""")

        val msg = d.finish()
        assertEquals(1, msg.toolCalls!!.size)
        assertEquals("toolu_2", msg.toolCalls!![0].id)
        assertEquals("navigate", msg.toolCalls!![0].function.name)
        assertEquals("""{"url":"https://example.com"}""", msg.toolCalls!![0].function.arguments)
        assertEquals("tool_use", d.stopReason)
    }

    @Test
    fun `two parallel tool calls keep their own indices`() {
        val d = AnthropicMessages.StreamDecoder()
        d.feed("""{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t0","name":"a"}}""")
        d.feed("""{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"t1","name":"b"}}""")
        d.feed("""{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"x\":1}"}}""")
        val calls = d.finish().toolCalls!!
        assertEquals(2, calls.size)
        assertEquals("a", calls[0].function.name)
        assertEquals("{}", calls[0].function.arguments)
        assertEquals("b", calls[1].function.name)
        assertEquals("""{"x":1}""", calls[1].function.arguments)
    }

    @Test
    fun `an error frame is recorded rather than thrown`() {
        val d = AnthropicMessages.StreamDecoder()
        val ev = d.feed("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
        assertTrue(ev.isEmpty())
        assertEquals("Overloaded", d.errorMessage)
    }

    @Test
    fun `an unparseable frame is skipped without killing the stream`() {
        val d = AnthropicMessages.StreamDecoder()
        assertTrue(d.feed("not json at all").isEmpty())
        d.feed("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"still here"}}""")
        assertEquals("still here", d.finish().content)
    }

    @Test
    fun `a tool_use block with a blank name is dropped`() {
        val d = AnthropicMessages.StreamDecoder()
        d.feed("""{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t0","name":""}}""")
        assertNull(d.finish().toolCalls)
    }

    @Test
    fun `the request round-trips through the encoder for the real preset model`() {
        // The exact shape the AgentRouter preset ships, so a regression in the
        // encoder is caught here rather than against a live endpoint.
        val b = body(
            ChatRequest(
                model = "deepseek-v4-flash",
                messages = listOf(
                    ChatMessage(role = "system", content = "sys"),
                    ChatMessage(role = "user", content = "hello")
                ),
                tools = listOf(def("navigate")),
                temperature = 0.2
            )
        )
        assertEquals("deepseek-v4-flash", b["model"]?.jsonPrimitive?.content)
        assertTrue(b["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(0.2, b["temperature"]!!.jsonPrimitive.content.toDouble(), 0.0001)
        assertFalse(b["messages"]!!.jsonArray.isEmpty())
        assertTrue(b["messages"]!! is JsonArray)
    }
}
