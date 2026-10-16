package com.roombrowser.node

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertThrows
import org.junit.Test

class NodeProtocolTest {

    @Test
    fun request_carries_every_field_the_runtime_checks() {
        val sent = Json.parseToJsonElement(
            NodeProtocol.request(7, "status", "tok", "UA/1")
        ).jsonObject
        assertThat(sent["id"].toString()).isEqualTo("7")
        assertThat(sent["cmd"].toString()).isEqualTo("\"status\"")
        assertThat(sent["token"].toString()).isEqualTo("\"tok\"")
        assertThat(sent["userAgent"].toString()).isEqualTo("\"UA/1\"")
    }

    @Test
    fun a_missing_identity_is_left_out_rather_than_sent_blank() {
        val absent = Json.parseToJsonElement(
            NodeProtocol.request(1, "status", "tok", null)
        ).jsonObject
        assertThat(absent.containsKey("userAgent")).isFalse()
        val blank = Json.parseToJsonElement(
            NodeProtocol.request(1, "status", "tok", "   ")
        ).jsonObject
        assertThat(blank.containsKey("userAgent")).isFalse()
    }

    @Test
    fun a_reply_is_read_back_as_its_result() {
        val result = NodeProtocol.result("""{"id":3,"ok":true,"result":{"node":"v18.20.4"}}""", 3)
        assertThat(result["node"].toString()).isEqualTo("\"v18.20.4\"")
    }

    @Test
    fun the_runtimes_own_refusal_is_raised_with_its_words() {
        val thrown = assertThrows(IllegalStateException::class.java) {
            NodeProtocol.result("""{"id":3,"ok":false,"error":"no such command: nope"}""", 3)
        }
        assertThat(thrown.message).isEqualTo("no such command: nope")
    }

    @Test
    fun an_answer_to_a_different_request_is_refused() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            NodeProtocol.result("""{"id":9,"ok":true,"result":{}}""", 3)
        }
        assertThat(thrown.message).contains("9")
    }

    @Test
    fun status_decodes_every_field_the_runtime_reports() {
        val result = NodeProtocol.result(STATUS_WITH_IDENTITY, 1)
        val status = NodeProtocol.statusOf(result)
        assertThat(status.node).isEqualTo("v18.20.4")
        assertThat(status.platform).isEqualTo("linux")
        assertThat(status.arch).isEqualTo("x64")
        assertThat(status.pid).isEqualTo(42)
        assertThat(status.workDir).isEqualTo("/data")
        assertThat(status.userAgent).isEqualTo("UA/1")
        assertThat(status.userAgentEnforced).isTrue()
    }

    @Test
    fun a_runtime_that_reported_no_identity_decodes_as_none() {
        val result = NodeProtocol.result(STATUS_WITHOUT_IDENTITY, 1)
        val status = NodeProtocol.statusOf(result)
        assertThat(status.userAgent).isNull()
        assertThat(status.userAgentEnforced).isTrue()
    }

    @Test
    fun a_run_request_carries_the_script_and_the_budget() {
        val sent = Json.parseToJsonElement(
            NodeProtocol.request(4, "run", "tok", "UA/1", script = "return 1", timeoutMs = 5000)
        ).jsonObject
        assertThat(sent["cmd"].toString()).isEqualTo("\"run\"")
        assertThat(sent["script"].toString()).isEqualTo("\"return 1\"")
        assertThat(sent["timeoutMs"].toString()).isEqualTo("5000")
    }

    @Test
    fun a_request_that_carries_no_script_sends_none() {
        val sent = Json.parseToJsonElement(
            NodeProtocol.request(4, "status", "tok", "UA/1")
        ).jsonObject
        assertThat(sent.containsKey("script")).isFalse()
        assertThat(sent.containsKey("timeoutMs")).isFalse()
    }

    @Test
    fun a_script_that_threw_arrives_inside_a_successful_reply() {
        // The envelope's `ok` is the RUNTIME's outcome, and a throwing script
        // is not a broken runtime — so the caller reads a failure it can act
        // on instead of an exception it would have to word itself.
        val result = NodeProtocol.result(RUN_THAT_THREW, 2)
        val run = NodeProtocol.runOf(result)
        assertThat(run.ok).isFalse()
        assertThat(run.output).contains("boom")
        assertThat(run.ms).isEqualTo(12)
    }

    @Test
    fun a_run_that_returned_a_value_reads_it_back_as_text() {
        val run = NodeProtocol.runOf(NodeProtocol.result(RUN_THAT_RETURNED, 2))
        assertThat(run.ok).isTrue()
        assertThat(run.output).contains("42")
    }

    // ------------------------------------------------------------ plugin calls

    @Test
    fun a_plugin_request_carries_every_field_the_host_checks() {
        val sent = Json.parseToJsonElement(
            NodeProtocol.pluginRequest(7, "tok", pluginCall(userAgent = "UA/1"))
        ).jsonObject
        assertThat(sent["id"].toString()).isEqualTo("7")
        assertThat(sent["cmd"].toString()).isEqualTo("\"plugin\"")
        assertThat(sent["token"].toString()).isEqualTo("\"tok\"")
        assertThat(sent["userAgent"].toString()).isEqualTo("\"UA/1\"")
        assertThat(sent["pluginRoot"].toString()).isEqualTo("\"/data/plugins/com_example_hello\"")
        assertThat(sent["dataRoot"].toString()).isEqualTo("\"/data/plugin-data/42/com_example_hello\"")
        assertThat(sent["entry"].toString()).isEqualTo("\"main.js\"")
        assertThat(sent["event"].toString()).isEqualTo("\"activate\"")
        assertThat(sent["payload"].toString()).isEqualTo("{}")
        assertThat(sent["config"].toString()).isEqualTo("{\"greeting\":\"Hello\"}")
        assertThat(sent["timeoutMs"].toString()).isEqualTo("30000")
        // The manifest slice: exactly §6's five fields, arrays always present.
        assertThat(sent["manifest"].toString()).isEqualTo(
            """{"id":"com.example.hello","name":"Hello World","version":"1.0.0",""" +
                """"permissions":["storage"],"networkAllow":["api.example.com"]}"""
        )
    }

    @Test
    fun a_plugin_request_without_an_identity_sends_none() {
        val absent = Json.parseToJsonElement(
            NodeProtocol.pluginRequest(1, "tok", pluginCall(userAgent = null))
        ).jsonObject
        assertThat(absent.containsKey("userAgent")).isFalse()
        val blank = Json.parseToJsonElement(
            NodeProtocol.pluginRequest(1, "tok", pluginCall(userAgent = "   "))
        ).jsonObject
        assertThat(blank.containsKey("userAgent")).isFalse()
    }

    @Test
    fun a_plugin_request_with_history_carries_it_most_recent_last() {
        val sent = Json.parseToJsonElement(
            NodeProtocol.pluginRequest(7, "tok", pluginCall(userAgent = "UA/1"))
        ).jsonObject
        assertThat(sent["history"].toString())
            .isEqualTo("""[{"role":"user","text":"hi"},{"role":"assistant","text":"hello"}]""")
    }

    @Test
    fun a_plugin_request_without_the_context_grant_leaves_history_out() {
        // Absent (never granted) and empty (granted, nothing said yet) both
        // stay off the wire: the host reads a missing field as "this plugin
        // may not read the chat", which is the promise the grant made.
        val absent = Json.parseToJsonElement(
            NodeProtocol.pluginRequest(1, "tok", pluginCall(userAgent = "UA/1", history = null))
        ).jsonObject
        assertThat(absent.containsKey("history")).isFalse()
        val empty = Json.parseToJsonElement(
            NodeProtocol.pluginRequest(1, "tok", pluginCall(userAgent = "UA/1", history = emptyList()))
        ).jsonObject
        assertThat(empty.containsKey("history")).isFalse()
    }

    @Test
    fun a_plugin_reply_decodes_its_whole_result() {
        val result = NodeProtocol.result(PLUGIN_REPLY, 7)
        val plugin = NodeProtocol.pluginOf(result)
        assertThat(plugin.ok).isTrue()
        assertThat(plugin.output).isEqualTo("done")
        assertThat(plugin.cards).hasSize(1)
        assertThat(plugin.cards[0]["title"].toString()).isEqualTo("\"Hello\"")
        assertThat(plugin.cards[0]["fields"].toString())
            .isEqualTo("""[["k","v"]]""")
        assertThat(plugin.logs).containsExactly("one", "two").inOrder()
        assertThat(plugin.tools).hasSize(1)
        assertThat(plugin.tools[0].name).isEqualTo("count_words")
        assertThat(plugin.tools[0].description).isEqualTo("Count words in a text")
        assertThat(plugin.tools[0].parameters["type"].toString()).isEqualTo("\"object\"")
        assertThat(plugin.commands).containsExactly("greet")
        assertThat(plugin.hooks).containsExactly("message")
        assertThat(plugin.ms).isEqualTo(123)
    }

    @Test
    fun a_plugin_that_failed_arrives_inside_a_successful_envelope() {
        // The envelope's `ok` is the RUNTIME's outcome and the inner `ok` is
        // the PLUGIN's — a throwing handler is an answer the caller reads, not
        // an exception it has to word itself.
        val plugin = NodeProtocol.pluginOf(NodeProtocol.result(PLUGIN_THAT_THREW, 9))
        assertThat(plugin.ok).isFalse()
        assertThat(plugin.output).contains("boom")
        assertThat(plugin.cards).isEmpty()
        assertThat(plugin.tools).isEmpty()
    }

    @Test
    fun a_sparse_plugin_reply_decodes_with_its_defaults() {
        val plugin = NodeProtocol.pluginOf(NodeProtocol.result(PLUGIN_SPARSE, 4))
        assertThat(plugin.ok).isTrue()
        assertThat(plugin.output).isEmpty()
        assertThat(plugin.cards).isEmpty()
        assertThat(plugin.logs).isEmpty()
        assertThat(plugin.tools).isEmpty()
        assertThat(plugin.commands).isEmpty()
        assertThat(plugin.hooks).isEmpty()
        assertThat(plugin.ms).isEqualTo(0)
    }

    private fun pluginCall(
        userAgent: String?,
        history: List<PluginChatEntry>? = HISTORY
    ): PluginCallRequest = PluginCallRequest(
        pluginRoot = "/data/plugins/com_example_hello",
        dataRoot = "/data/plugin-data/42/com_example_hello",
        entry = "main.js",
        manifest = PluginWireManifest(
            id = "com.example.hello",
            name = "Hello World",
            version = "1.0.0",
            permissions = listOf("storage"),
            networkAllow = listOf("api.example.com")
        ),
        event = PluginCallRequest.EVENT_ACTIVATE,
        payload = JsonObject(emptyMap()),
        config = JsonObject(mapOf("greeting" to JsonPrimitive("Hello"))),
        timeoutMs = 30_000,
        userAgent = userAgent,
        history = history
    )

    private companion object {
        // Hand-written, in the shape assets/node/bridge.js actually writes:
        // these pin the wire format rather than the encoder that produced it.
        const val STATUS_WITH_IDENTITY =
            """{"id":1,"ok":true,"result":{"node":"v18.20.4","platform":"linux","arch":"x64","pid":42,"workDir":"/data","userAgent":"UA/1","userAgentEnforced":true}}"""

        const val STATUS_WITHOUT_IDENTITY =
            """{"id":1,"ok":true,"result":{"node":"v18.20.4","platform":"linux","arch":"x64","pid":42,"workDir":"/","userAgent":null,"userAgentEnforced":true}}"""

        const val RUN_THAT_THREW =
            """{"id":2,"ok":true,"result":{"ok":false,"output":"the script threw: boom","ms":12}}"""

        const val RUN_THAT_RETURNED =
            """{"id":2,"ok":true,"result":{"ok":true,"output":"42","ms":3}}"""

        // The plugin reply of design §6, whole.
        const val PLUGIN_REPLY =
            """{"id":7,"ok":true,"result":{"ok":true,"output":"done",""" +
                """"cards":[{"title":"Hello","text":"...","fields":[["k","v"]],""" +
                """"link":{"label":"Open","url":"https://example.com"},"style":"info"}],""" +
                """"logs":["one","two"],""" +
                """"tools":[{"name":"count_words","description":"Count words in a text",""" +
                """"parameters":{"type":"object"}}],""" +
                """"commands":["greet"],"hooks":["message"],"ms":123}}"""

        const val PLUGIN_THAT_THREW =
            """{"id":9,"ok":true,"result":{"ok":false,"output":"the handler threw: boom","ms":5}}"""

        const val PLUGIN_SPARSE =
            """{"id":4,"ok":true,"result":{"ok":true}}"""

        // The history the chat.context grant buys a plugin: user and
        // assistant turns, oldest first.
        val HISTORY = listOf(
            PluginChatEntry("user", "hi"),
            PluginChatEntry("assistant", "hello")
        )
    }
}
