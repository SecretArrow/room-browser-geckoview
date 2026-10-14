package com.roombrowser.node

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
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
    }
}
