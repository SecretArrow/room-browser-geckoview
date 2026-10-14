package com.roombrowser.node

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** What the runtime reports about itself. */
@Serializable
data class NodeStatus(
    val node: String,
    val platform: String,
    val arch: String,
    val pid: Int,
    val workDir: String,
    val userAgent: String? = null,
    val userAgentEnforced: Boolean = false
)

/**
 * What one script produced.
 *
 * [ok] is the SCRIPT's outcome, not the runtime's: a script that throws is an
 * answer the model has to read and correct, not a broken runtime, so it arrives
 * with the same envelope as a success and the thrown words in [output].
 */
@Serializable
data class NodeRunResult(
    val ok: Boolean,
    val output: String,
    val ms: Long = 0
)

/**
 * One line of JSON each way. The other end is `assets/node/bridge.js`, and the
 * two spellings are one protocol: `id`, `cmd`, `token`, `userAgent` going out;
 * `id`, `ok`, `result` or `error` coming back.
 */
internal object NodeProtocol {

    private val json = Json { ignoreUnknownKeys = true }

    fun request(
        id: Int,
        cmd: String,
        token: String,
        userAgent: String?,
        script: String? = null,
        timeoutMs: Long? = null
    ): String =
        buildJsonObject {
            put("id", id)
            put("cmd", cmd)
            put("token", token)
            // Blank is left out rather than sent as "": the runtime reads a
            // missing identity as a refusal on every request, never as a
            // fallback to its own.
            if (!userAgent.isNullOrBlank()) put("userAgent", userAgent)
            if (script != null) put("script", script)
            if (timeoutMs != null) put("timeoutMs", timeoutMs)
        }.toString()

    /** The reply's `result`, or the runtime's own error as an exception. */
    fun result(line: String, expectedId: Int): JsonObject {
        val reply = json.parseToJsonElement(line).jsonObject
        val id = reply["id"]?.jsonPrimitive?.intOrNull
        require(id == expectedId) { "the runtime answered request $id, not $expectedId" }
        val ok = reply["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        if (!ok) {
            throw IllegalStateException(
                reply["error"]?.jsonPrimitive?.content ?: "the runtime refused the request"
            )
        }
        return reply["result"]!!.jsonObject
    }

    fun statusOf(result: JsonObject): NodeStatus =
        json.decodeFromJsonElement(NodeStatus.serializer(), result)

    fun runOf(result: JsonObject): NodeRunResult =
        json.decodeFromJsonElement(NodeRunResult.serializer(), result)
}
