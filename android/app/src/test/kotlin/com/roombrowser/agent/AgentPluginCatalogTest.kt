package com.roombrowser.agent

import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.db.ChatPluginEntity
import com.roombrowser.data.db.PluginEntity
import com.roombrowser.data.repo.toManifest
import com.roombrowser.domain.plugins.PluginFormat
import com.roombrowser.domain.plugins.PluginSystemPrompt
import org.junit.Test

/**
 * The catalogue's pure half: the system-prompt addition a chat's enabled
 * plugins contribute to the agent turn — the one seam the model's picture of
 * "what else runs in this conversation" flows through.
 *
 * The blocks themselves are [PluginSystemPrompt]'s (pinned by the domain
 * tests); what matters HERE is the catalogue's join: one block per ENABLED
 * binding, in the order the chat enabled them, joined by a blank line, the
 * version spelled VERBATIM (a module's own `zagents_version`, not the
 * semver-coerced column) — and a chat with no plugins adds NOTHING, so its
 * prompt stays byte-identical to the pre-plugin one.
 *
 * Every test ends on a VOID-returning Truth call: a Kotlin function whose
 * last expression has a value compiles to a non-void method, and JUnit4 then
 * refuses the whole class.
 */
class AgentPluginCatalogTest {

    private fun plugin(
        id: String = "com.example.hello",
        name: String = "Hello World",
        version: String = "1.0.0",
        description: String = "Says hello.",
        format: String = PluginFormat.PLUGIN,
        zagentsVersion: String? = null
    ) = PluginEntity(
        id = id,
        name = name,
        version = version,
        description = description,
        authorName = "",
        license = "",
        entry = "main.js",
        apiVersion = 1,
        permissionsJson = """["storage"]""",
        networkAllowJson = "[]",
        configSchemaJson = "[]",
        commandsJson = "[]",
        hooksJson = "[]",
        dirName = id.replace('.', '_'),
        installedAt = 1L,
        updatedAt = 1L,
        format = format,
        zagentsVersion = zagentsVersion
    )

    /** One enabled binding of [plugin], the sheet's own order carrier. */
    private fun sessionPlugin(plugin: PluginEntity) = SessionPlugin(
        plugin = plugin,
        binding = ChatPluginEntity(
            id = 1L,
            sessionId = 7L,
            pluginId = plugin.id,
            enabled = true,
            grantedPermissionsJson = """["storage"]""",
            configJson = "",
            createdAt = 1L,
            updatedAt = 1L
        ),
        manifest = plugin.toManifest(),
        tools = emptyList(),
        commands = emptyList(),
        hooks = emptyList(),
        probeOk = true,
        probeError = null
    )

    @Test
    fun `a chat with no plugins adds nothing to the prompt`() {
        assertThat(AgentPluginCatalog.systemPromptAddition(emptyList())).isEmpty()
    }

    @Test
    fun `one plugin is one block after the prompt's own text`() {
        val addition = AgentPluginCatalog.systemPromptAddition(
            listOf(sessionPlugin(plugin()))
        )
        assertThat(addition).isEqualTo(
            "# Active plugin: Hello World v1.0.0\nSays hello."
        )
    }

    @Test
    fun `a module introduces itself in the module wording and its own version spelling`() {
        // The version is the VERBATIM `zagents_version` — "1.2-beta" is not a
        // semver, and the semver column's coerced "0.0.0" is not what the user
        // or the module's own About box says.
        val addition = AgentPluginCatalog.systemPromptAddition(
            listOf(
                sessionPlugin(
                    plugin(
                        id = "zagents.greeter",
                        name = "Greeter Module",
                        version = "0.0.0",
                        description = "Greets.",
                        format = PluginFormat.ZAGENTS,
                        zagentsVersion = "1.2-beta"
                    )
                )
            )
        )
        assertThat(addition).isEqualTo(
            "# Active module: Greeter Module v1.2-beta\nGreets."
        )
    }

    @Test
    fun `blocks join by a blank line in the order the chat enabled them`() {
        val addition = AgentPluginCatalog.systemPromptAddition(
            listOf(
                sessionPlugin(plugin()),
                sessionPlugin(
                    plugin(
                        id = "zagents.greeter",
                        name = "Greeter Module",
                        version = "0.0.0",
                        description = "Greets.",
                        format = PluginFormat.ZAGENTS,
                        zagentsVersion = "1.2-beta"
                    )
                )
            )
        )
        assertThat(addition).isEqualTo(
            "# Active plugin: Hello World v1.0.0\nSays hello.\n\n" +
                "# Active module: Greeter Module v1.2-beta\nGreets."
        )
    }

    @Test
    fun `a module with no zagents version falls back to the semver column`() {
        val addition = AgentPluginCatalog.systemPromptAddition(
            listOf(
                sessionPlugin(
                    plugin(
                        id = "zagents.greeter",
                        name = "Greeter Module",
                        version = "1.0.0",
                        description = "Greets.",
                        format = PluginFormat.ZAGENTS,
                        zagentsVersion = null
                    )
                )
            )
        )
        assertThat(addition).contains("# Active module: Greeter Module v1.0.0\n")
    }

    @Test
    fun `an empty description gets each format's own default sentence`() {
        val plugin = AgentPluginCatalog.systemPromptAddition(
            listOf(sessionPlugin(plugin(description = "")))
        )
        assertThat(plugin).contains(PluginSystemPrompt.PLUGIN_DEFAULT_DESCRIPTION)
        val module = AgentPluginCatalog.systemPromptAddition(
            listOf(
                sessionPlugin(
                    plugin(
                        id = "zagents.greeter",
                        name = "Greeter Module",
                        version = "0.0.0",
                        description = "",
                        format = PluginFormat.ZAGENTS,
                        zagentsVersion = "1.2-beta"
                    )
                )
            )
        )
        // The module's default says the entry point already executed — the
        // fact that keeps the model from "helpfully" trying to invoke it.
        assertThat(module).contains(PluginSystemPrompt.MODULE_DEFAULT_DESCRIPTION)
        assertThat(module).doesNotContain(PluginSystemPrompt.PLUGIN_DEFAULT_DESCRIPTION)
    }
}
