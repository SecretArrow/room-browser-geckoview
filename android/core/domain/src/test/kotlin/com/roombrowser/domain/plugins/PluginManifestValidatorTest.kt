package com.roombrowser.domain.plugins

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.agent.AgentJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * The manifest contract, rule by rule: every §3.2 limit is held by a test that
 * breaks exactly one thing, so a refusal message that stops matching its rule
 * is caught here rather than at a user's install.
 *
 * Every test ends on a VOID-returning Truth call: a Kotlin function whose last
 * expression has a value compiles to a non-void method, and JUnit4 then runs
 * none of the class.
 */
class PluginManifestValidatorTest {

    @Test
    fun `the design's own example manifest is valid`() {
        assertThat(PluginManifestValidator.validate(valid())).isEmpty()
    }

    @Test
    fun `the minimum manifest is valid`() {
        val minimal = JsonObject(
            mapOf(
                "id" to JsonPrimitive("a.b"),
                "name" to JsonPrimitive("X"),
                "version" to JsonPrimitive("0.0.1"),
                "api" to JsonPrimitive(1),
                "entry" to JsonPrimitive("main.js")
            )
        )
        assertThat(PluginManifestValidator.validate(minimal)).isEmpty()
    }

    @Test
    fun `an id needs a dot and the lowercase grammar`() {
        listOf("", "nodot", "No.Dot", "a.", ".a", "-a.b", "a..b").forEach { bad ->
            assertThat(PluginManifestValidator.validate(valid("id" to JsonPrimitive(bad))))
                .isNotEmpty()
        }
        assertThat(PluginManifestValidator.validate(valid("id" to JsonPrimitive("a.b.c.d.e.f"))))
            .isNotEmpty()
    }

    @Test
    fun `an api other than one is refused by name`() {
        val errors = PluginManifestValidator.validate(valid("api" to JsonPrimitive(2)))
        assertThat(errors).hasSize(1)
        assertThat(errors[0]).contains("API version 1")
    }

    @Test
    fun `a missing api is not zero by accident`() {
        assertThat(PluginManifestValidator.validate(without("api"))).isNotEmpty()
    }

    @Test
    fun `unknown top-level fields are refused`() {
        val errors = PluginManifestValidator.validate(valid("future" to JsonPrimitive(true)))
        assertThat(errors).hasSize(1)
        assertThat(errors[0]).contains("unknown field \"future\"")
    }

    @Test
    fun `the entry must be a js path inside the package`() {
        listOf("main.txt", "/abs/main.js", "a/../main.js", "..", "back\\slash.js").forEach { bad ->
            assertThat(PluginManifestValidator.validate(valid("entry" to JsonPrimitive(bad))))
                .isNotEmpty()
        }
        assertThat(
            PluginManifestValidator.validate(valid("entry" to JsonPrimitive("src/deep/main.js")))
        ).isEmpty()
    }

    @Test
    fun `permissions must be known and unique`() {
        assertThat(
            PluginManifestValidator.validate(
                valid("permissions" to jsonArray("storage", "storage"))
            )
        ).isNotEmpty()
        assertThat(
            PluginManifestValidator.validate(
                valid("permissions" to jsonArray("filesystem"))
            )
        ).isNotEmpty()
        // The whole vocabulary at once is fine, and is also the cap.
        assertThat(
            PluginManifestValidator.validate(
                valid(
                    "permissions" to jsonArray(
                        "storage", "network", "agent.tools", "chat.context", "agent.generate"
                    ),
                    "network" to networkAllow("api.example.com")
                )
            )
        ).isEmpty()
    }

    @Test
    fun `agent generate is a permission and five of them are the cap`() {
        // The new permission declares on its own, like every other one.
        assertThat(
            PluginManifestValidator.validate(valid("permissions" to jsonArray("agent.generate")))
        ).isEmpty()

        // Six entries are over the cap even when every one is known — the
        // count is its own rule, not a side effect of the vocabulary.
        val errors = PluginManifestValidator.validate(
            valid(
                "permissions" to jsonArray(
                    "storage", "network", "agent.tools", "chat.context", "agent.generate", "storage"
                ),
                "network" to networkAllow("api.example.com")
            )
        )
        assertThat(errors).isNotEmpty()
        assertThat(errors.any { it.contains("at most 5") }).isTrue()
    }

    @Test
    fun `files is refused by name as a module-json-only permission`() {
        // A plugin.json author copying a module's manifest learns the real
        // fix — the name exists, just not in THIS format — rather than a
        // generic unknown-permission message.
        val errors = PluginManifestValidator.validate(
            valid("permissions" to jsonArray("storage", "files"))
        )
        assertThat(errors).containsExactly("files is a module.json-only permission")
        // The vocabulary a plugin.json may declare still does not carry it.
        assertThat(PluginPermissions.PERMISSIONS).doesNotContain("files")
        assertThat(PluginPermissions.MODULE_PERMISSIONS).contains("files")
    }

    @Test
    fun `an inputs schema is part of the manifest's own rulebook`() {
        // A schema this build cannot render is refused at install, and the
        // message is the schema validator's own — one rulebook, relayed.
        val errors = PluginManifestValidator.validate(
            valid(
                "inputs" to jsonArray(
                    JsonObject(
                        mapOf(
                            "key" to JsonPrimitive("E"),
                            "type" to JsonPrimitive("string"),
                            "title" to JsonPrimitive("E")
                        )
                    )
                )
            )
        )
        assertThat(errors).hasSize(1)
        assertThat(errors.single()).contains("inputs")

        // And the design's own example field is a valid declaration.
        assertThat(
            PluginManifestValidator.validate(
                valid(
                    "inputs" to jsonArray(
                        JsonObject(
                            mapOf(
                                "key" to JsonPrimitive("email"),
                                "type" to JsonPrimitive("string"),
                                "title" to JsonPrimitive("Email"),
                                "required" to JsonPrimitive(true),
                                "pattern" to JsonPrimitive("^[^@]+@[^@]+$"),
                                "patternError" to JsonPrimitive("Enter a valid email"),
                                "minLength" to JsonPrimitive(3),
                                "maxLength" to JsonPrimitive(200)
                            )
                        )
                    )
                )
            )
        ).isEmpty()
    }

    @Test
    fun `the network allowlist is required with the permission and pointless without it`() {
        // Declaring the permission with no hosts is refused…
        assertThat(
            PluginManifestValidator.validate(valid("permissions" to jsonArray("network")))
        ).isNotEmpty()
        // …an empty list is the same refusal…
        assertThat(
            PluginManifestValidator.validate(
                valid(
                    "permissions" to jsonArray("network"),
                    "network" to networkAllow()
                )
            )
        ).isNotEmpty()
        // …and hosts without the permission are refused too.
        assertThat(
            PluginManifestValidator.validate(
                valid("network" to networkAllow("api.example.com"))
            )
        ).isNotEmpty()
    }

    @Test
    fun `host patterns follow the v1 hostname rules`() {
        listOf(
            "API.example.com",
            "api .example.com",
            "https://api.example.com",
            "api.example.com:8443",
            "localhost",
            "intranet",
            "1.2.3.4",
            "010.1.1.1",
            "-bad.example.com",
            "bad-.example.com",
            "example..com"
        ).forEach { bad ->
            assertThat(PluginManifestValidator.validate(validWithNetwork(bad))).isNotEmpty()
        }
        listOf("api.example.com", "*.example.com", "x-y.example.com", "api.example.co.uk").forEach { good ->
            assertThat(PluginManifestValidator.validate(validWithNetwork(good))).isEmpty()
        }
    }

    @Test
    fun `config schema keys, types and defaults are held to their own shapes`() {
        assertThat(
            PluginManifestValidator.validate(
                valid(
                    "config" to JsonObject(
                        mapOf(
                            "schema" to jsonArray(
                                JsonObject(
                                    mapOf(
                                        "key" to JsonPrimitive("Greeting"),
                                        "type" to JsonPrimitive("string"),
                                        "default" to JsonPrimitive("hi")
                                    )
                                )
                            )
                        )
                    )
                )
            )
        ).isNotEmpty()

        assertThat(
            PluginManifestValidator.validate(
                valid(
                    "config" to JsonObject(
                        mapOf(
                            "schema" to jsonArray(
                                JsonObject(
                                    mapOf(
                                        "key" to JsonPrimitive("greeting"),
                                        "type" to JsonPrimitive("number"),
                                        "default" to JsonPrimitive("hi")
                                    )
                                )
                            )
                        )
                    )
                )
            )
        ).isNotEmpty()

        // A default that matches its type is fine, and a missing one is the
        // zero value — not an error.
        assertThat(
            PluginManifestValidator.validate(
                valid(
                    "config" to JsonObject(
                        mapOf(
                            "schema" to jsonArray(
                                JsonObject(
                                    mapOf(
                                        "key" to JsonPrimitive("greeting"),
                                        "type" to JsonPrimitive("string")
                                    )
                                )
                            )
                        )
                    )
                )
            )
        ).isEmpty()
    }

    @Test
    fun `commands may not be named plugins and must be unique`() {
        assertThat(
            PluginManifestValidator.validate(
                valid(
                    "commands" to jsonArray(
                        command("greet"),
                        command("greet")
                    )
                )
            )
        ).isNotEmpty()
        val reserved = PluginManifestValidator.validate(
            valid("commands" to jsonArray(command("plugins")))
        )
        assertThat(reserved).isNotEmpty()
        assertThat(reserved[0]).contains("reserved")
    }

    @Test
    fun `commands may not be named make_plugins either - the second reserved word`() {
        // Design §5.1: a manifest declaring a command named make_plugins is
        // refused at install, exactly the sentence the first reserved word
        // earns — one rule, both app-owned words.
        val errors = PluginManifestValidator.validate(
            valid("commands" to jsonArray(command("make_plugins")))
        )
        assertThat(errors).hasSize(1)
        assertThat(errors.single()).isEqualTo("command name \"make_plugins\" is reserved by the app")
        // And the first word's refusal is unchanged by the second arriving.
        val plugins = PluginManifestValidator.validate(
            valid("commands" to jsonArray(command("plugins")))
        )
        assertThat(plugins).hasSize(1)
        assertThat(plugins.single()).isEqualTo("command name \"plugins\" is reserved by the app")
    }

    @Test
    fun `commands may not be named airplane - the third reserved word`() {
        // The /airplane chat command is app-owned first-party surface (a
        // sandboxed module cannot toggle radios, and pretending one could
        // would be a lie), so a manifest declaring the word is refused at
        // install with the same reserved sentence as the first two.
        val errors = PluginManifestValidator.validate(
            valid("commands" to jsonArray(command("airplane")))
        )
        assertThat(errors).hasSize(1)
        assertThat(errors.single()).isEqualTo("command name \"airplane\" is reserved by the app")
        // The reserved set the parser and this validator share is the one
        // spelling of the vocabulary — all three words, no drift.
        assertThat(PluginCommandParser.RESERVED).containsExactly("plugins", "make_plugins", "airplane")
    }

    @Test
    fun `hooks are a subset of message`() {
        assertThat(
            PluginManifestValidator.validate(valid("hooks" to jsonArray("message")))
        ).isEmpty()
        assertThat(
            PluginManifestValidator.validate(valid("hooks" to jsonArray("message", "message")))
        ).isNotEmpty()
        assertThat(
            PluginManifestValidator.validate(valid("hooks" to jsonArray("tick")))
        ).isNotEmpty()
    }

    @Test
    fun `every rule broken at once is reported at once`() {
        val errors = PluginManifestValidator.validate(
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive("Bad"),
                    "name" to JsonPrimitive(""),
                    "version" to JsonPrimitive("one"),
                    "api" to JsonPrimitive(7),
                    "entry" to JsonPrimitive("main.ts"),
                    "permissions" to jsonArray("storage", "storage", "nope"),
                    "hooks" to jsonArray("message", "message"),
                    "extra" to JsonPrimitive(1)
                )
            )
        )
        // One message per broken rule is the point of the validator; a person
        // fixing a manifest should not have to run install once per defect.
        assertThat(errors.size).isAtLeast(8)
    }

    // ------------------------------------------------------------ fixtures

    private fun valid(vararg edits: Pair<String, JsonElement>): JsonObject {
        val base = AgentJson.parseToJsonElement(VALID).jsonObject.toMutableMap()
        edits.forEach { (key, value) -> base[key] = value }
        return JsonObject(base)
    }

    private fun without(key: String): JsonObject {
        val base = AgentJson.parseToJsonElement(VALID).jsonObject.toMutableMap()
        base.remove(key)
        return JsonObject(base)
    }

    private fun validWithNetwork(host: String): JsonObject = valid(
        "permissions" to jsonArray("network"),
        "network" to networkAllow(host)
    )

    private fun networkAllow(vararg hosts: String): JsonObject = JsonObject(
        mapOf("allow" to jsonArray(*hosts))
    )

    private fun command(name: String): JsonObject = JsonObject(
        mapOf(
            "name" to JsonPrimitive(name),
            "title" to JsonPrimitive("T"),
            "description" to JsonPrimitive("D")
        )
    )

    private fun jsonArray(vararg values: String): JsonElement =
        JsonArray(values.map { JsonPrimitive(it) })

    /** The same array for element payloads — schema items, command rows. */
    private fun jsonArray(vararg values: JsonElement): JsonElement =
        JsonArray(values.toList())

    private companion object {
        val VALID = """
            {"id":"com.example.hello","name":"Hello World","version":"1.0.0",
             "description":"A tiny example plugin.",
             "author":{"name":"Jane Doe","email":"jane@example.com","url":"https://example.com"},
             "license":"MIT","api":1,"entry":"main.js",
             "permissions":["storage"],
             "config":{"schema":[{"key":"greeting","type":"string","default":"Hello",
                                  "title":"Greeting word","description":"Word to greet with"}]},
             "commands":[{"name":"greet","title":"Greet","description":"Say hello"}],
             "hooks":["message"]}
        """.trimIndent()
    }
}
