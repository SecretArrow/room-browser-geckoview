package com.roombrowser.agent

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.plugins.PluginManifestValidator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

/**
 * The pure half of `/make_plugins`' state: [PendingPluginCreation.candidate] —
 * the manifest the form's answer becomes — and the read-only helpers the
 * dialog renders. The candidate matrix is the security property in miniature:
 * permissions and hosts come ONLY from the form's arguments, every other
 * draft field rides through verbatim, and the single install validator is
 * the one judge of what a candidate is worth (the dialog's gate and the
 * state's backstop both read it).
 */
class PluginMakerTest {

    // ----------------------------------------------------------- the helpers

    @Test
    fun `the read-only helpers carry the draft's own facts`() {
        val pending = PendingPluginCreation(draft(), ENTRY)
        assertThat(pending.entryPath).isEqualTo("main.js")
        assertThat(pending.displayName).isEqualTo("Made Plugin")
        assertThat(pending.declaredDescription).isEqualTo("Composed draft.")
        assertThat(pending.versionText).isEqualTo("0.1.0")
        assertThat(pending.idText).isEqualTo("com.example.made")
        assertThat(pending.declaredCommands).containsExactly("greet")
        assertThat(pending.declaredPermissions).containsExactly("network", "storage").inOrder()
        assertThat(pending.declaredHosts).containsExactly("api.example.com", "*.example.org").inOrder()
    }

    @Test
    fun `the unchanged draft candidate passes the validator`() {
        val pending = PendingPluginCreation(draft(), ENTRY)
        val candidate = pending.candidate(
            pending.displayName,
            pending.declaredDescription,
            pending.declaredPermissions,
            pending.declaredHosts
        )
        assertThat(PluginManifestValidator.validate(candidate)).isEmpty()
    }

    // ------------------------------------------------------- the rewrite

    @Test
    fun `candidate replaces name description permissions and hosts from the form`() {
        val pending = PendingPluginCreation(draft(), ENTRY)
        val candidate = pending.candidate(
            name = "Reviewed",
            description = "Reviewed and narrowed.",
            permissions = listOf("storage", "network"),
            hosts = listOf("only.example.com")
        )
        assertThat(candidate["name"]!!.jsonPrimitive.content).isEqualTo("Reviewed")
        assertThat(candidate["description"]!!.jsonPrimitive.content).isEqualTo("Reviewed and narrowed.")
        // The canonical order wins whatever order the checkboxes were read in.
        assertThat(
            candidate["permissions"]!!.jsonArray.map { it.jsonPrimitive.content }
        ).containsExactly("storage", "network").inOrder()
        assertThat(
            candidate["network"]!!.jsonObject["allow"]!!.jsonArray.map { it.jsonPrimitive.content }
        ).containsExactly("only.example.com")
        assertThat(PluginManifestValidator.validate(candidate)).isEmpty()
    }

    @Test
    fun `an unchecked network permission drops the network member with it`() {
        val pending = PendingPluginCreation(draft(), ENTRY)
        val candidate = pending.candidate(
            name = "Reviewed",
            description = "Narrowed.",
            permissions = listOf("storage"),
            hosts = pending.declaredHosts
        )
        // The hosts the draft named are NOT installed when the box is
        // unchecked — the allowlist rides the permission, never survives it.
        assertThat(candidate.containsKey("network")).isFalse()
        assertThat(PluginManifestValidator.validate(candidate)).isEmpty()
    }

    @Test
    fun `network checked with a bad host keeps the validator's refusal live`() {
        val pending = PendingPluginCreation(draft(), ENTRY)
        val candidate = pending.candidate(
            name = "Reviewed",
            description = "Narrowed.",
            permissions = listOf("network"),
            hosts = listOf("Not A Host")
        )
        // The rewrite does not paper over a bad answer: the SAME refusal the
        // install door would give is what keeps Allow and Create disabled.
        val errors = PluginManifestValidator.validate(candidate)
        assertThat(errors).isNotEmpty()
        assertThat(errors.any { it.contains("network.allow") }).isTrue()
    }

    @Test
    fun `a blank name keeps the candidate invalid`() {
        val pending = PendingPluginCreation(draft(), ENTRY)
        val candidate = pending.candidate(
            name = "",
            description = "Narrowed.",
            permissions = emptyList(),
            hosts = emptyList()
        )
        assertThat(PluginManifestValidator.validate(candidate)).isNotEmpty()
    }

    @Test
    fun `candidate preserves the draft's other fields verbatim`() {
        val draft = draft()
        val pending = PendingPluginCreation(draft, ENTRY)
        val candidate = pending.candidate("Reviewed", "Narrowed.", emptyList(), emptyList())
        // Identity, authorship, the declared commands, config and hooks all
        // ride through untouched — the form may only answer the questions it
        // was asked, and it was asked four.
        assertThat(candidate["id"]!!.jsonPrimitive.content).isEqualTo("com.example.made")
        assertThat(candidate["version"]!!.jsonPrimitive.content).isEqualTo("0.1.0")
        assertThat(candidate["api"]!!.jsonPrimitive.content).isEqualTo("1")
        assertThat(candidate["entry"]!!.jsonPrimitive.content).isEqualTo("main.js")
        assertThat(candidate["license"]!!.jsonPrimitive.content).isEqualTo("MIT")
        assertThat(candidate["author"]!!.jsonObject["name"]!!.jsonPrimitive.content).isEqualTo("Jane")
        assertThat(candidate["commands"]).isEqualTo(draft["commands"])
        assertThat(candidate["hooks"]).isEqualTo(draft["hooks"])
        assertThat(candidate["config"]).isEqualTo(draft["config"])
        // An empty checkbox list omits the member — "no permissions" is the
        // manifest's own spelling for none.
        assertThat(candidate.containsKey("permissions")).isFalse()
    }

    // ------------------------------------------------------------ fixtures

    private val ENTRY = "exports.activate = async (room) => {};"

    /** A validator-clean draft with every field the rewrite must preserve. */
    private fun draft(): JsonObject = buildJsonObject {
        put("id", "com.example.made")
        put("name", "Made Plugin")
        put("version", "0.1.0")
        put("description", "Composed draft.")
        put("author", buildJsonObject {
            put("name", "Jane")
            put("email", "jane@example.com")
            put("url", "https://example.com")
        })
        put("license", "MIT")
        put("api", 1)
        put("entry", "main.js")
        put("permissions", JsonArray(listOf("network", "storage").map { JsonPrimitive(it) }))
        put("network", buildJsonObject {
            put("allow", JsonArray(listOf("api.example.com", "*.example.org").map { JsonPrimitive(it) }))
        })
        put("config", buildJsonObject {
            put(
                "schema",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("key", "greeting")
                            put("type", "string")
                            put("default", "Hello")
                            put("title", "Greeting word")
                            put("description", "")
                        }
                    )
                )
            )
        })
        put("commands", JsonArray(
            listOf(
                buildJsonObject {
                    put("name", "greet")
                    put("title", "Greet")
                    put("description", "")
                }
            )
        ))
        put("hooks", JsonArray(listOf(JsonPrimitive("message"))))
    }
}
