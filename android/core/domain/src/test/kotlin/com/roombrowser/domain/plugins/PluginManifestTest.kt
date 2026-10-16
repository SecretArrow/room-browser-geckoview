package com.roombrowser.domain.plugins

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/**
 * The tolerant parse: what a VALID manifest reads back as, and that a broken
 * one answers null instead of throwing. Strictness is the validator's job;
 * this only has to be honest about what it can read.
 */
class PluginManifestTest {

    @Test
    fun `the design's own example parses field for field`() {
        val manifest = PluginManifest.parse(VALID)!!
        assertThat(manifest.id).isEqualTo("com.example.hello")
        assertThat(manifest.name).isEqualTo("Hello World")
        assertThat(manifest.version).isEqualTo("1.0.0")
        assertThat(manifest.description).isEqualTo("A tiny example plugin.")
        assertThat(manifest.author?.name).isEqualTo("Jane Doe")
        assertThat(manifest.author?.email).isEqualTo("jane@example.com")
        assertThat(manifest.author?.url).isEqualTo("https://example.com")
        assertThat(manifest.license).isEqualTo("MIT")
        assertThat(manifest.api).isEqualTo(1)
        assertThat(manifest.entry).isEqualTo("main.js")
        assertThat(manifest.permissions).containsExactly("storage")
        assertThat(manifest.configSchema).hasSize(1)
        assertThat(manifest.configSchema[0].key).isEqualTo("greeting")
        assertThat(manifest.configSchema[0].type).isEqualTo("string")
        assertThat(manifest.configSchema[0].default.toString()).isEqualTo("\"Hello\"")
        assertThat(manifest.commands).hasSize(1)
        assertThat(manifest.commands[0].name).isEqualTo("greet")
        assertThat(manifest.hooks).containsExactly("message")
    }

    @Test
    fun `a network manifest reads its allowlist`() {
        val manifest = PluginManifest.parse(
            """
            {"id":"a.b","name":"N","version":"1.0.0","api":1,"entry":"m.js",
             "permissions":["network"],
             "network":{"allow":["api.example.com","*.example.com"]}}
            """.trimIndent()
        )!!
        assertThat(manifest.networkAllow)
            .containsExactly("api.example.com", "*.example.com")
            .inOrder()
    }

    @Test
    fun `the directory name is the id with dots exchanged for underscores`() {
        assertThat(PluginManifest.parse(VALID)!!.dirName).isEqualTo("com_example_hello")
    }

    @Test
    fun `a broken body answers null rather than throwing`() {
        assertThat(PluginManifest.parse("not json at all")).isNull()
        assertThat(PluginManifest.parse("[]")).isNull()
        assertThat(PluginManifest.parse("\"a string\"")).isNull()
        assertThat(PluginManifest.parse("")).isNull()
    }

    @Test
    fun `missing optional pieces read as their defaults`() {
        val manifest = PluginManifest.parse(
            """{"id":"a.b","name":"N","version":"1.0.0","api":1,"entry":"m.js"}"""
        )!!
        assertThat(manifest.description).isEmpty()
        assertThat(manifest.author).isNull()
        assertThat(manifest.license).isEmpty()
        assertThat(manifest.permissions).isEmpty()
        assertThat(manifest.networkAllow).isEmpty()
        assertThat(manifest.configSchema).isEmpty()
        assertThat(manifest.commands).isEmpty()
        assertThat(manifest.hooks).isEmpty()
    }

    @Test
    fun `an absent config default is the type's zero value`() {
        val manifest = PluginManifest.parse(
            """
            {"id":"a.b","name":"N","version":"1.0.0","api":1,"entry":"m.js",
             "config":{"schema":[
               {"key":"s","type":"string"},
               {"key":"n","type":"number"},
               {"key":"b","type":"boolean"}]}}
            """.trimIndent()
        )!!
        val defaults = manifest.configSchema.associate { it.key to it.default.toString() }
        assertThat(defaults["s"]).isEqualTo("\"\"")
        assertThat(defaults["n"]).isEqualTo("0")
        assertThat(defaults["b"]).isEqualTo("false")
    }

    @Test
    fun `a schema and its json round-trip through the piece helpers`() {
        val manifest = PluginManifest.parse(VALID)!!
        val schemaJson = JsonObject(mapOf("schema" to JsonArray(manifest.configSchema.map { it.toJson() })))
        assertThat(PluginManifest.parseConfigSchema(schemaJson)).isEqualTo(manifest.configSchema)
        val commandsJson = JsonArray(manifest.commands.map { it.toJson() })
        assertThat(PluginManifest.parseCommands(commandsJson)).isEqualTo(manifest.commands)
    }

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
