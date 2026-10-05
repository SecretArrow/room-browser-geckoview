package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AgentDtosTest {

    // ---------- ModelListParser ----------

    @Test
    fun `openai style models list`() {
        val body = """{"object":"list","data":[{"id":"glm-4.6"},{"id":"glm-4-flash"}]}"""
        assertThat(ModelListParser.parse(body)).containsExactly("glm-4-flash", "glm-4.6").inOrder()
    }

    @Test
    fun `bare array of strings`() {
        assertThat(ModelListParser.parse("""["b-model","a-model"]"""))
            .containsExactly("a-model", "b-model").inOrder() // sorted + distinct
    }

    @Test
    fun `string ids inside data`() {
        assertThat(ModelListParser.parse("""{"data":["m1","m2","m1"]}"""))
            .containsExactly("m1", "m2")
    }

    @Test
    fun `alternate models key`() {
        assertThat(ModelListParser.parse("""{"models":[{"id":"local"}]}"""))
            .containsExactly("local")
    }

    @Test
    fun `garbage body yields empty list`() {
        assertThat(ModelListParser.parse("<html>blocked</html>")).isEmpty()
        assertThat(ModelListParser.parse("")).isEmpty()
    }

    // ---------- Request wire format ----------

    @Test
    fun `chat request serializes to the openai wire format`() {
        val request = ChatRequest(
            model = "glm-4.6",
            messages = listOf(
                ChatMessage(role = "system", content = "sys"),
                ChatMessage(role = "user", content = "hi")
            ),
            stream = true,
            tools = AgentTools.toolDefs(),
            temperature = 0.2
        )
        val json = AgentJson.encodeToString(ChatRequest.serializer(), request)
        assertThat(json).contains("\"model\":\"glm-4.6\"")
        assertThat(json).contains("\"stream\":true")
        assertThat(json).contains("\"tools\":[")
        assertThat(json).contains("\"type\":\"function\"")
        assertThat(json).contains("\"temperature\":0.2")
        assertThat(json).contains("\"messages\":[{\"role\":\"system\",\"content\":\"sys\"}")
    }

    @Test
    fun `null content and absent tools are omitted`() {
        val assistant = ChatMessage(
            role = "assistant",
            content = null,
            toolCalls = listOf(ToolCall(id = "c1", function = FunctionCall("navigate", "{}")))
        )
        val json = AgentJson.encodeToString(ChatMessage.serializer(), assistant)
        assertThat(json).doesNotContain("\"content\"")
        assertThat(json).contains("\"tool_calls\"")
        assertThat(json).contains("\"id\":\"c1\"")
        assertThat(json).contains("\"arguments\":\"{}\"")
    }

    @Test
    fun `tool message carries tool_call_id`() {
        val msg = ChatMessage(role = "tool", content = "ok", toolCallId = "c9")
        val json = AgentJson.encodeToString(ChatMessage.serializer(), msg)
        assertThat(json).contains("\"tool_call_id\":\"c9\"")
    }

    // ---------- Response / chunk decoding ----------

    @Test
    fun `non streaming response decodes with unknown fields ignored`() {
        val body = """
            {"id":"x","object":"chat.completion","created":1,"model":"m",
             "usage":{"prompt_tokens":1},
             "choices":[{"index":0,"finish_reason":"stop",
               "message":{"role":"assistant","content":"answer","refusal":null}}]}
        """.trimIndent()
        val parsed = AgentJson.decodeFromString(ChatResponse.serializer(), body)
        assertThat(parsed.firstMessage!!.content).isEqualTo("answer")
    }

    @Test
    fun `stream chunk with tool call delta decodes`() {
        val body = """
            {"choices":[{"index":0,"delta":{"tool_calls":[
                {"index":0,"id":"call_1","type":"function",
                 "function":{"name":"navigate","arguments":"{\"url\":"}}]}}]}
        """.trimIndent()
        val chunk = AgentJson.decodeFromString(StreamChunk.serializer(), body)
        val dtc = chunk.choices[0].delta!!.toolCalls!![0]
        assertThat(dtc.id).isEqualTo("call_1")
        assertThat(dtc.function!!.name).isEqualTo("navigate")
        assertThat(dtc.function!!.arguments).isEqualTo("{\"url\":")
    }

    @Test
    fun `reasoning content delta decodes`() {
        val body = """{"choices":[{"delta":{"reasoning_content":"thinking..."}}]}"""
        val chunk = AgentJson.decodeFromString(StreamChunk.serializer(), body)
        assertThat(chunk.choices[0].delta!!.reasoningContent).isEqualTo("thinking...")
    }

    // ---------- Tool catalogue / snapshot formatting ----------

    @Test
    fun `tool defs cover the interactive catalogue`() {
        val names = AgentTools.toolDefs().map { it.function.name }
        assertThat(names).containsAtLeast(
            AgentTools.NAVIGATE, AgentTools.READ_PAGE, AgentTools.CLICK,
            AgentTools.FILL_INPUT, AgentTools.PRESS_ENTER, AgentTools.SCROLL
        )
        AgentTools.toolDefs().forEach { def ->
            assertThat(def.function.parameters.containsKey("type")).isTrue()
        }
    }

    @Test
    fun `tool defs cover the social automation catalogue`() {
        val defs = AgentTools.toolDefs().associate { it.function.name to it.function }
        assertThat(defs.keys).containsAtLeast(
            AgentTools.AUTO_LIKE, AgentTools.AUTO_REPOST, AgentTools.AUTO_REPLY,
            AgentTools.AUTO_POST, AgentTools.WAIT
        )
        // automation tools with parameters must declare a required text arg
        listOf(AgentTools.AUTO_REPLY, AgentTools.AUTO_POST).forEach { name ->
            val params = defs.getValue(name).parameters
            val required = params["required"]?.toString() ?: ""
            assertThat(required).contains("text")
        }
        // every automation tool needs a non-blank description
        defs.values.forEach { fn ->
            assertThat(fn.description.isNotBlank()).isTrue()
        }
        // automation tools participate in the confirmation gate
        assertThat(AgentTools.INTERACTIVE_TOOLS).containsAtLeast(
            AgentTools.AUTO_LIKE, AgentTools.AUTO_REPOST, AgentTools.AUTO_REPLY, AgentTools.AUTO_POST
        )
    }

    @Test
    fun `snapshot formatting truncates text and lists elements`() {
        val snapshot = PageSnapshotDto(
            url = "https://example.com",
            title = "Example",
            text = "a".repeat(20_000),
            scrollY = 10,
            maxScrollY = 5000,
            elements = listOf(
                SnapElement(ref = 1, tag = "a", label = "More information", viewport = true, href = "/more"),
                SnapElement(ref = 2, tag = "input", label = "Search", viewport = true, type = "search")
            )
        )
        val formatted = PageSnapshotFormatter.format(snapshot)
        assertThat(formatted).contains("URL: https://example.com")
        assertThat(formatted).contains("SCROLL: 10/5000")
        assertThat(formatted).contains("…[middle omitted]…")
        assertThat(formatted).contains("[1] <a> \"More information\" -> /more  (in viewport)")
        assertThat(formatted).contains("[2] <input type=search> \"Search\"")
    }

    @Test
    fun `describeTool builds labels leniently`() {
        assertThat(AgentTools.describeTool(AgentTools.NAVIGATE, """{"url":"https://x.dev"}"""))
            .isEqualTo("Open https://x.dev")
        assertThat(AgentTools.describeTool(AgentTools.CLICK, """{"ref":12}"""))
            .isEqualTo("Click [12]")
        assertThat(AgentTools.describeTool(AgentTools.FILL_INPUT, "not json"))
            .isEqualTo("Type into [?]")
        assertThat(AgentTools.describeTool(AgentTools.READ_PAGE, null))
            .isEqualTo("Read current page")
        assertThat(AgentTools.describeTool(AgentTools.AUTO_LIKE, "{}"))
            .isEqualTo("Like visible posts")
        assertThat(AgentTools.describeTool(AgentTools.AUTO_REPOST, null))
            .isEqualTo("Repost visible posts")
        assertThat(AgentTools.describeTool(AgentTools.AUTO_REPLY, """{"text":"thanks!"}"""))
            .isEqualTo("Reply \"thanks!\"")
        assertThat(AgentTools.describeTool(AgentTools.AUTO_POST, "not json"))
            .isEqualTo("Post \"\"")
        assertThat(AgentTools.describeTool(AgentTools.WAIT, """{"ms":2000}"""))
            .isEqualTo("Wait 2s")
        assertThat(AgentTools.describeTool(AgentTools.WAIT, null))
            .isEqualTo("Wait 1.5s")
    }

    @Test
    fun `durations read as seconds, and only keep a decimal when they need one`() {
        assertThat(formatDurationMs(2000)).isEqualTo("2s")
        assertThat(formatDurationMs(20_000)).isEqualTo("20s")
        assertThat(formatDurationMs(1000)).isEqualTo("1s")
        // A sub-second wait must not round down to "0s" — that reads as no
        // wait at all, right where the user is deciding whether to allow one.
        assertThat(formatDurationMs(200)).isEqualTo("0.2s")
        assertThat(formatDurationMs(1500)).isEqualTo("1.5s")
    }

    @Test
    fun `prompt teaches the automation tools`() {
        assertThat(AgentPrompts.DEFAULT).contains("auto_like")
        assertThat(AgentPrompts.DEFAULT).contains("auto_repost")
        assertThat(AgentPrompts.DEFAULT).contains("auto_reply")
        assertThat(AgentPrompts.DEFAULT).contains("auto_post")
    }

    @Test
    fun `prompt teaches the direct page and app tools`() {
        // A tool the model is never told about is a tool it does not reach for,
        // so every one of these is named in the prompt.
        val prompt = AgentPrompts.DEFAULT
        listOf(
            AgentTools.RUN_JS, AgentTools.SELECT_OPTION, AgentTools.PRESS_KEYS, AgentTools.WAIT_FOR,
            AgentTools.APP_OPEN, AgentTools.APP_TABS, AgentTools.APP_DATA,
            AgentTools.APP_SETTINGS, AgentTools.APP_SHIELDS,
            AgentTools.APP_SITE_PERMISSION, AgentTools.APP_PAGE
        ).forEach { name ->
            assertThat(prompt).contains(name)
        }
    }

    @Test
    fun `prompt renders placeholders`() {
        val rendered = AgentPrompts.render(java.time.LocalDate.of(2026, 9, 27), "Google")
        assertThat(rendered).contains("2026-09-27")
        assertThat(rendered).contains("Google")
        assertThat(rendered).doesNotContain("{DATE}")
        assertThat(rendered).doesNotContain("{ENGINE}")
    }

    // ---------- ProviderErrorText ----------

    @Test
    fun `an anthropic style error body yields just its sentence`() {
        // The real AgentRouter refusal, verbatim — the envelope around the one
        // useful sentence is exactly what the UI must not show.
        val body = """{"error":{"message":"unauthorized client detected, contact """ +
            """support for assistance at https://discord.gg/HgekCyHJqB"},""" +
            """"message":"UNAUTHENTICATED","success":false,"type":"unauthorized_client_error"}"""
        assertThat(ProviderErrorText.extract(body))
            .isEqualTo(
                "unauthorized client detected, contact support for assistance " +
                    "at https://discord.gg/HgekCyHJqB"
            )
    }

    @Test
    fun `an openai style error body yields its message`() {
        assertThat(
            ProviderErrorText.extract("""{"error":{"message":"bad key","type":"invalid_request_error"}}""")
        ).isEqualTo("bad key")
    }

    @Test
    fun `a bare message field is accepted`() {
        assertThat(ProviderErrorText.extract("""{"message":"rate limited"}""")).isEqualTo("rate limited")
        assertThat(ProviderErrorText.extract("""{"detail":"not found"}""")).isEqualTo("not found")
    }

    @Test
    fun `an error that is a plain string is accepted`() {
        assertThat(ProviderErrorText.extract("""{"error":"boom"}""")).isEqualTo("boom")
    }

    @Test
    fun `a non-json body passes through trimmed rather than vanishing`() {
        // An honest ugly message beats a swallowed error.
        assertThat(ProviderErrorText.extract("  <html>502 Bad Gateway</html>  "))
            .isEqualTo("<html>502 Bad Gateway</html>")
    }

    @Test
    fun `an unrecognised json shape falls back to the whole body`() {
        assertThat(ProviderErrorText.extract("""{"weird":1}""")).isEqualTo("""{"weird":1}""")
    }

    @Test
    fun `an empty body stays empty`() {
        assertThat(ProviderErrorText.extract("")).isEmpty()
        assertThat(ProviderErrorText.extract("   ")).isEmpty()
    }

    @Test
    fun `a long body is capped`() {
        val long = "x".repeat(5000)
        val out = ProviderErrorText.extract(long)
        assertThat(out.length).isEqualTo(ProviderErrorText.MAX_CHARS + 1) // + the ellipsis
        assertThat(out).endsWith("…")
    }

    @Test
    fun `the exception message is the sentence while the raw body is kept`() {
        val raw = """{"error":{"message":"unauthorized client detected"}}"""
        val e = AgentHttpException(401, raw)
        assertThat(e.message).isEqualTo("HTTP 401: unauthorized client detected")
        assertThat(e.body).isEqualTo(raw)   // raw body untouched for callers that want it
        assertThat(e.code).isEqualTo(401)
    }

    @Test
    fun `an exception with no body carries only the status`() {
        assertThat(AgentHttpException(500, "").message).isEqualTo("HTTP 500")
    }
}
