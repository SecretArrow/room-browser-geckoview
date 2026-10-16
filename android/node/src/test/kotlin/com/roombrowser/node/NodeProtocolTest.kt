package com.roombrowser.node

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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

    // ---------------------------------------------------------- the module half

    @Test
    fun a_zagents_request_carries_every_module_field_the_host_checks() {
        val sent = Json.parseToJsonElement(
            NodeProtocol.pluginRequest(7, "tok", zagentsCall())
        ).jsonObject
        assertThat(sent["format"].toString()).isEqualTo("\"zagents\"")
        assertThat(sent["prompt"].toString()).isEqualTo("\"setup a@b.com pw123456\"")
        assertThat(sent["timeoutSeconds"].toString()).isEqualTo("120")
        assertThat(sent["inputsValues"].toString()).isEqualTo("""{"token":"abc123"}""")
        assertThat(sent["stateRoot"].toString())
            .isEqualTo("\"/data/plugin-data/42/zagents_onhomeroom-daily/state\"")
        assertThat(sent["title"].toString()).isEqualTo("\"OnHomeroom Daily\"")
        assertThat(sent["zagentsVersion"].toString()).isEqualTo("\"1.1.0\"")
        assertThat(sent["zagentsName"].toString()).isEqualTo("\"onhomeroom-daily\"")
        // The module's own permission vocabulary rides the manifest verbatim —
        // the ZAgents names, not the Room ones the row maps them onto.
        assertThat(sent["manifest"].toString()).isEqualTo(
            """{"id":"zagents.onhomeroom-daily","name":"OnHomeroom Daily",""" +
                """"version":"0.0.0","permissions":["storage","network","agent"],""" +
                """"networkAllow":["*.onhomeroom.com"]}"""
        )
    }

    @Test
    fun a_plugin_request_sends_none_of_the_module_fields() {
        // The plugin.json spelling the wire has always spoken: every module
        // field is absent, which the host reads as the plugin SDK and its own
        // defaults — never as an empty value.
        val sent = Json.parseToJsonElement(
            NodeProtocol.pluginRequest(7, "tok", pluginCall(userAgent = "UA/1"))
        ).jsonObject
        for (field in listOf(
            "format", "prompt", "timeoutSeconds", "inputsValues", "stateRoot",
            "title", "zagentsVersion", "zagentsName"
        )) {
            assertWithMessage("$field should be absent").that(sent.containsKey(field)).isFalse()
        }
    }

    @Test
    fun blank_or_missing_module_fields_are_left_out_rather_than_sent_empty() {
        // Same rule as userAgent: a blank module string is not a module
        // string, and a null is an absence — the host must never mistake
        // either for an empty prompt, an empty title or an empty form.
        val sent = Json.parseToJsonElement(
            NodeProtocol.pluginRequest(
                7, "tok",
                zagentsCall(
                    format = "   ",
                    prompt = "",
                    timeoutSeconds = null,
                    inputsValues = null,
                    stateRoot = "  ",
                    title = "",
                    zagentsVersion = "",
                    zagentsName = ""
                )
            )
        ).jsonObject
        for (field in listOf(
            "format", "prompt", "timeoutSeconds", "inputsValues", "stateRoot",
            "title", "zagentsVersion", "zagentsName"
        )) {
            assertWithMessage("$field should be absent").that(sent.containsKey(field)).isFalse()
        }
    }

    @Test
    fun the_module_format_constant_is_the_zagents_spelling() {
        assertThat(PluginCallRequest.FORMAT_ZAGENTS).isEqualTo("zagents")
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

    // -------------------------------------------------------- reverse requests

    @Test
    fun a_reverse_request_line_is_told_apart_from_a_plugin_reply() {
        // Hand-written in the shape bridge.js writes: a STRING id ("gen-" +
        // counter, unique per connection), the cmd member that marks a
        // reverse request, the run's token, and the §4 object under
        // `request`. The plugin reply below carries no cmd — that absence is
        // the whole discrimination, and it is what the read loop keys on.
        val reverse = NodeProtocol.parse(REVERSE_REQUEST)
        assertThat(NodeProtocol.isReverse(reverse)).isTrue()
        assertThat(NodeProtocol.isReverse(NodeProtocol.parse(PLUGIN_REPLY))).isFalse()
        assertThat(NodeProtocol.REVERSE_GENERATE).isEqualTo("agent_generate")
    }

    @Test
    fun a_user_input_reverse_request_is_told_apart_and_answered_by_its_handler() {
        // The form channel's own line, hand-written in the shape bridge.js
        // writes for `user_input`: the same string-id + cmd + token shape,
        // the form request under `request` — one family, two commands.
        val reverse = NodeProtocol.parse(USER_INPUT_REQUEST)
        assertThat(NodeProtocol.isReverse(reverse)).isTrue()
        assertThat(NodeProtocol.isReverse(NodeProtocol.parse(PLUGIN_REPLY))).isFalse()
        assertThat(NodeProtocol.REVERSE_USER_INPUT).isEqualTo("user_input")
        val handlers = ReverseHandlers().register(NodeProtocol.REVERSE_USER_INPUT) { line ->
            // The handler reads the form out of the whole reverse line.
            assertThat((line["request"] as? JsonObject)?.get("title")?.toString())
                .isEqualTo("\"Your account\"")
            buildJsonObject {
                put("values", buildJsonObject { put("email", "a@b.com") })
            }
        }
        assertThat(NodeProtocol.reverseAnswer(reverse, TOKEN, handlers)).isEqualTo(
            """{"id":"form-1","ok":true,"result":{"values":{"email":"a@b.com"}}}"""
        )
    }

    @Test
    fun the_registry_answers_each_command_with_its_own_handler() {
        val handlers = ReverseHandlers()
            .register(NodeProtocol.REVERSE_GENERATE) { buildJsonObject { put("text", "hi") } }
            .register(NodeProtocol.REVERSE_USER_INPUT) { buildJsonObject { put("values", JsonObject(emptyMap())) } }
        assertThat(NodeProtocol.reverseAnswer(NodeProtocol.parse(REVERSE_REQUEST), TOKEN, handlers))
            .isEqualTo("""{"id":"gen-1","ok":true,"result":{"text":"hi"}}""")
        assertThat(NodeProtocol.reverseAnswer(NodeProtocol.parse(USER_INPUT_REQUEST), TOKEN, handlers))
            .isEqualTo("""{"id":"form-1","ok":true,"result":{"values":{}}}""")
    }

    @Test
    fun a_user_input_request_nobody_registered_to_answer_gets_the_no_ui_sentence() {
        // Probes and legacy call sites wire no dialog; the refusal is the
        // same sentence the runtime form itself uses, and the call carries
        // on instead of waiting out the runtime's own 300 s.
        val answer = NodeProtocol.reverseAnswer(
            NodeProtocol.parse(USER_INPUT_REQUEST),
            TOKEN,
            ReverseHandlers()
        )
        assertThat(answer).isEqualTo(
            """{"id":"form-1","ok":false,"error":"No interactive UI is available for this call"}"""
        )
    }

    @Test
    fun the_generation_channel_keeps_its_own_default_words() {
        // agent_generate exactly as before the registry existed: nobody
        // registered means not available, its own words, its own id.
        val answer = NodeProtocol.reverseAnswer(NodeProtocol.parse(REVERSE_REQUEST), TOKEN, ReverseHandlers())
        assertThat(answer).isEqualTo(
            """{"id":"gen-1","ok":false,"error":"agent generation is not available"}"""
        )
    }

    @Test
    fun a_reverse_request_without_this_run_s_token_is_refused() {
        // Whatever the command, the token rule comes first — a line that does
        // not carry this run's token is nobody's to answer.
        val handlers = ReverseHandlers()
            .register(NodeProtocol.REVERSE_USER_INPUT) { JsonObject(emptyMap()) }
        val answer = NodeProtocol.reverseAnswer(
            NodeProtocol.parse(USER_INPUT_REQUEST),
            "not this run's token",
            handlers
        )
        assertThat(answer).isEqualTo(
            """{"id":"form-1","ok":false,"error":"the reverse request did not carry this run's token"}"""
        )
    }

    @Test
    fun a_handler_that_threw_is_answered_with_its_own_words() {
        // A dismissed dialog, a timed-out generation: the handler's outcome
        // is the request's honest answer, and the read keeps waiting for the
        // plugin reply either way.
        val handlers = ReverseHandlers()
            .register(NodeProtocol.REVERSE_USER_INPUT) { throw IllegalStateException("Form was cancelled") }
        assertThat(NodeProtocol.reverseAnswer(NodeProtocol.parse(USER_INPUT_REQUEST), TOKEN, handlers)).isEqualTo(
            """{"id":"form-1","ok":false,"error":"Form was cancelled"}"""
        )
    }

    @Test
    fun an_unknown_reverse_command_is_refused_in_words() {
        val answer = NodeProtocol.reverseAnswer(
            NodeProtocol.parse(UNKNOWN_REVERSE_REQUEST),
            TOKEN,
            ReverseHandlers()
        )
        assertThat(answer).isEqualTo(
            """{"id":"zz-1","ok":false,"error":"unknown reverse command: something_else"}"""
        )
    }

    @Test
    fun the_form_wait_is_the_protocol_three_hundred_seconds() {
        // The wire's own number, the one bridge.js arms for `user_input`: a
        // person reading a dialog is the one wait allowed to take minutes,
        // and the module's budget is paused for every second of it.
        assertThat(NodeProtocol.REVERSE_USER_INPUT_TIMEOUT_MS).isEqualTo(300_000L)
    }

    @Test
    fun a_form_reply_and_its_errors_carry_the_form_line_own_id() {
        assertThat(
            NodeProtocol.reverseReply(
                "form-3",
                buildJsonObject { put("values", buildJsonObject { put("email", "a@b.com") }) }
            )
        ).isEqualTo("""{"id":"form-3","ok":true,"result":{"values":{"email":"a@b.com"}}}""")
        assertThat(NodeProtocol.reverseError("form-4", "Form was timed out"))
            .isEqualTo("""{"id":"form-4","ok":false,"error":"Form was timed out"}""")
    }

    @Test
    fun the_reverse_reply_envelope_carries_the_reverse_line_own_id() {
        assertThat(
            NodeProtocol.reverseReply("gen-1", buildJsonObject { put("text", "hi there") })
        ).isEqualTo("""{"id":"gen-1","ok":true,"result":{"text":"hi there"}}""")
    }

    @Test
    fun the_reverse_error_envelope_carries_the_reverse_line_own_id_and_the_words() {
        assertThat(NodeProtocol.reverseError("gen-2", "agent generation is not available"))
            .isEqualTo("""{"id":"gen-2","ok":false,"error":"agent generation is not available"}""")
    }

    @Test
    fun a_generate_request_is_read_out_of_the_reverse_line_or_handed_bare() {
        // `of` accepts the whole reverse line or the bare §4 object, and the
        // two must agree — the read loop hands lines, a caller that already
        // peeled the object hands the inner one.
        val fromLine = PluginGenerateRequest.of(NodeProtocol.parse(REVERSE_REQUEST))
        val bare = PluginGenerateRequest.of(NodeProtocol.parse(GENERATE_REQUEST))
        assertThat(fromLine).isEqualTo(bare)
        assertThat(bare.purpose).isEqualTo("chat-reply")
        assertThat(bare.instruction).isEqualTo("Reply to the last message in the general channel")
        assertThat(bare.context).isEqualTo("alice: anyone tried the new puzzle app")
        assertThat(bare.hint).isEqualTo("keep it casual")
        assertThat(bare.style.language).isEqualTo("en")
        assertThat(bare.style.maxWords).isEqualTo(24)
        assertThat(bare.style.oneSentence).isTrue()
        assertThat(bare.style.lowercase).isFalse()
        assertThat(bare.style.forbiddenChars).isEqualTo("!:—–?;")
        assertThat(bare.style.tone).isEqualTo("casual")
        assertThat(bare.avoid).containsExactly("i keep coming back to the puzzle app")
    }

    @Test
    fun a_generate_request_clamps_every_field_to_its_own_cap() {
        val flood = "x".repeat(9_999)
        val request = PluginGenerateRequest.of(
            JsonObject(
                mapOf(
                    "purpose" to JsonPrimitive(flood),
                    "instruction" to JsonPrimitive(flood),
                    "context" to JsonPrimitive(flood),
                    "hint" to JsonPrimitive(flood),
                    "style" to JsonObject(
                        mapOf(
                            "language" to JsonPrimitive(flood),
                            "maxWords" to JsonPrimitive(5_000),
                            "forbiddenChars" to JsonPrimitive(flood),
                            "tone" to JsonPrimitive(flood)
                        )
                    ),
                    "avoid" to JsonArray((1..60).map { JsonPrimitive(flood) })
                )
            )
        )
        assertThat(request.purpose).isEqualTo("x".repeat(64))
        assertThat(request.instruction).isEqualTo("x".repeat(500))
        assertThat(request.context).isEqualTo("x".repeat(4_000))
        assertThat(request.hint).isEqualTo("x".repeat(200))
        assertThat(request.style.language).isEqualTo("x".repeat(24))
        assertThat(request.style.maxWords).isEqualTo(200)
        assertThat(request.style.forbiddenChars).isEqualTo("x".repeat(64))
        assertThat(request.style.tone).isEqualTo("x".repeat(64))
        // The avoid list keeps fifty entries of two hundred — the count and
        // the length are clamped independently, exactly as §4 says.
        assertThat(request.avoid).hasSize(50)
        assertThat(request.avoid.all { it.length == 200 }).isTrue()
    }

    @Test
    fun a_missing_purpose_or_instruction_is_refused_with_clear_words() {
        val noPurpose = assertThrows(IllegalArgumentException::class.java) {
            PluginGenerateRequest.of(JsonObject(mapOf("instruction" to JsonPrimitive("i"))))
        }
        assertThat(noPurpose.message).isEqualTo("the generate request carried no purpose")
        val noInstruction = assertThrows(IllegalArgumentException::class.java) {
            PluginGenerateRequest.of(JsonObject(mapOf("purpose" to JsonPrimitive("p"))))
        }
        assertThat(noInstruction.message).isEqualTo("the generate request carried no instruction")
        // Blank is missing: the words are trimmed before they are judged.
        assertThrows(IllegalArgumentException::class.java) {
            PluginGenerateRequest.of(
                JsonObject(
                    mapOf(
                        "purpose" to JsonPrimitive("   "),
                        "instruction" to JsonPrimitive("i")
                    )
                )
            )
        }
    }

    @Test
    fun the_clamped_request_re_emits_only_the_members_that_carry_a_value() {
        // toJson is the prompt renderer's input, so it carries the CLAMPED
        // truth and omits what says nothing — an unset style member never
        // reaches the prompt as a surprise.
        val request = PluginGenerateRequest.of(NodeProtocol.parse(GENERATE_REQUEST))
        assertThat(request.toJson().toString()).isEqualTo(
            """{"purpose":"chat-reply","instruction":"Reply to the last message in the general """ +
                """channel","context":"alice: anyone tried the new puzzle app",""" +
                """"hint":"keep it casual","style":{"language":"en","maxWords":24,""" +
                """"oneSentence":true,"forbiddenChars":"!:—–?;","tone":"casual"},""" +
                """"avoid":["i keep coming back to the puzzle app"]}"""
        )
        val sparse = PluginGenerateRequest.of(
            JsonObject(
                mapOf(
                    "purpose" to JsonPrimitive("p"),
                    "instruction" to JsonPrimitive("i")
                )
            )
        )
        assertThat(sparse.toJson().toString()).isEqualTo("""{"purpose":"p","instruction":"i"}""")
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

    /** A module.json row's call: the module half of the request, filled in. */
    private fun zagentsCall(
        format: String? = PluginCallRequest.FORMAT_ZAGENTS,
        prompt: String? = "setup a@b.com pw123456",
        timeoutSeconds: Int? = 120,
        inputsValues: JsonObject? = JsonObject(mapOf("token" to JsonPrimitive("abc123"))),
        stateRoot: String? = "/data/plugin-data/42/zagents_onhomeroom-daily/state",
        title: String? = "OnHomeroom Daily",
        zagentsVersion: String? = "1.1.0",
        zagentsName: String? = "onhomeroom-daily"
    ): PluginCallRequest = PluginCallRequest(
        pluginRoot = "/data/plugins/zagents_onhomeroom-daily",
        dataRoot = "/data/plugin-data/42/zagents_onhomeroom-daily",
        entry = "main.js",
        manifest = PluginWireManifest(
            id = "zagents.onhomeroom-daily",
            name = "OnHomeroom Daily",
            version = "0.0.0",
            permissions = listOf("storage", "network", "agent"),
            networkAllow = listOf("*.onhomeroom.com")
        ),
        event = PluginCallRequest.EVENT_ACTIVATE,
        timeoutMs = 120_000,
        userAgent = "UA/1",
        format = format,
        prompt = prompt,
        timeoutSeconds = timeoutSeconds,
        inputsValues = inputsValues,
        stateRoot = stateRoot,
        title = title,
        zagentsVersion = zagentsVersion,
        zagentsName = zagentsName,
        history = null
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

        // The reverse request of design §3, whole: a string id, the one
        // reverse command, the run's 48-hex token, and the §4 object under
        // `request` — hand-written so these pin the wire format rather than
        // the encoder that will produce it.
        const val REVERSE_REQUEST =
            """{"id":"gen-1","cmd":"agent_generate",""" +
                """"token":"0123456789abcdef0123456789abcdef0123456789abcdef",""" +
                """"request":{"purpose":"chat-reply",""" +
                """"instruction":"Reply to the last message in the general channel",""" +
                """"context":"alice: anyone tried the new puzzle app","hint":"keep it casual",""" +
                """"style":{"language":"en","maxWords":24,"oneSentence":true,""" +
                """"forbiddenChars":"!:—–?;","tone":"casual"},""" +
                """"avoid":["i keep coming back to the puzzle app"]}}"""

        // The same §4 object, bare — what `of` also accepts.
        const val GENERATE_REQUEST =
            """{"purpose":"chat-reply",""" +
                """"instruction":"Reply to the last message in the general channel",""" +
                """"context":"alice: anyone tried the new puzzle app","hint":"keep it casual",""" +
                """"style":{"language":"en","maxWords":24,"oneSentence":true,""" +
                """"forbiddenChars":"!:—–?;","tone":"casual"},""" +
                """"avoid":["i keep coming back to the puzzle app"]}"""

        // The run's token the reverse requests carry: hand-written so these
        // pin the wire format rather than the encoder that produced it.
        const val TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef"

        // The reverse request of the form channel, whole: the same line shape
        // with its own id prefix and command, the §3.1 form under `request`.
        const val USER_INPUT_REQUEST =
            """{"id":"form-1","cmd":"user_input",""" +
                """"token":"$TOKEN",""" +
                """"request":{"title":"Your account",""" +
                """"description":"This module needs it for this chat",""" +
                """"fields":[{"key":"email","type":"string","title":"Email","required":true}]}}"""

        // A command this build does not know: refused in words, id kept.
        const val UNKNOWN_REVERSE_REQUEST =
            """{"id":"zz-1","cmd":"something_else","token":"$TOKEN","request":{}}"""

        // The history the chat.context grant buys a plugin: user and
        // assistant turns, oldest first.
        val HISTORY = listOf(
            PluginChatEntry("user", "hi"),
            PluginChatEntry("assistant", "hello")
        )
    }
}
