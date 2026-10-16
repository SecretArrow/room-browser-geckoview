package com.roombrowser.domain.plugins

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.agent.AgentJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * The module.json contract, rule by rule: what a lenient read turns a broken
 * manifest into, what the name becomes, what the mapping onto the plugin row
 * vocabulary says, and which of the few hard refusals fire. ZAgents is
 * tolerant by design — the tests here pin the DEFAULTS as firmly as the
 * refusals, because a default that drifts is a compatibility break that no
 * error message will ever announce.
 */
class ZAgentsManifestTest {

    @Test
    fun `a full manifest parses field for field`() {
        val manifest = ZAgentsManifest.parse(MODULE)!!
        assertThat(manifest.name).isEqualTo("onhomeroom-daily")
        assertThat(manifest.version).isEqualTo("1.1.0")
        assertThat(manifest.title).isEqualTo("Daily Digest")
        assertThat(manifest.description).isEqualTo("Composes a daily summary and posts it.")
        assertThat(manifest.entry).isEqualTo("main.js")
        assertThat(manifest.permissions).containsExactly("storage", "network", "chat", "agent").inOrder()
        assertThat(manifest.allowedHosts).containsExactly("api.onhomeroom.com", "*.example.com").inOrder()
        assertThat(manifest.timeoutSeconds).isEqualTo(45)
        assertThat(manifest.inputs.fields).isEmpty()
    }

    @Test
    fun `a broken body answers null rather than throwing`() {
        assertThat(ZAgentsManifest.parse("not json at all")).isNull()
        assertThat(ZAgentsManifest.parse("[]")).isNull()
        assertThat(ZAgentsManifest.parse("\"a string\"")).isNull()
        assertThat(ZAgentsManifest.parse("")).isNull()
    }

    @Test
    fun `the name is sanitized exactly as the zagents registry does it`() {
        assertThat(ZAgentsManifest.sanitizeName("  My Module! ")).isEqualTo("my-module")
        assertThat(ZAgentsManifest.sanitizeName("Cool_Name.Here")).isEqualTo("cool_name.here")
        assertThat(ZAgentsManifest.sanitizeName("a----b")).isEqualTo("a-b")
        assertThat(ZAgentsManifest.sanitizeName("...leading")).isEqualTo("leading")
        assertThat(ZAgentsManifest.sanitizeName("trailing...")).isEqualTo("trailing")
        assertThat(ZAgentsManifest.sanitizeName("-dashes-")).isEqualTo("dashes")
        assertThat(ZAgentsManifest.sanitizeName("a...b")).isEqualTo("a...b")
        assertThat(ZAgentsManifest.sanitizeName("UPPER")).isEqualTo("upper")
        // The cap cuts at 40 without re-stripping what the cut left behind.
        assertThat(ZAgentsManifest.sanitizeName("a".repeat(50))).hasLength(40)
        // Everything that lands empty — or on the two names that could only
        // ever mean a directory — falls back to "module".
        assertThat(ZAgentsManifest.sanitizeName("")).isEqualTo("module")
        assertThat(ZAgentsManifest.sanitizeName("..")).isEqualTo("module")
        assertThat(ZAgentsManifest.sanitizeName("!!!")).isEqualTo("module")
        assertThat(ZAgentsManifest.sanitizeName(".")).isEqualTo("module")
    }

    @Test
    fun `wrong shapes fall back to their defaults, not to errors`() {
        val manifest = ZAgentsManifest.parse(
            """
            {"name":"counter","version":123,"title":["t"],"description":{"d":1},
             "entry":true,"permissions":{"storage":1},"allowedHosts":"api.example.com",
             "timeoutSeconds":"30","inputs":{"key":"email"}}
            """.trimIndent()
        )!!
        assertThat(manifest.version).isEqualTo("0.0.0")
        assertThat(manifest.title).isEmpty()
        assertThat(manifest.description).isEmpty()
        assertThat(manifest.entry).isEmpty()
        assertThat(manifest.permissions).isEmpty()
        assertThat(manifest.allowedHosts).isEmpty()
        assertThat(manifest.timeoutSeconds).isEqualTo(30)
        assertThat(manifest.inputs.fields).isEmpty()
    }

    @Test
    fun `unknown permission names are dropped, never widened`() {
        val manifest = ZAgentsManifest.parse(
            """
            {"name":"m","permissions":["storage","typo","NETWORK","files","chat","agent"]}
            """.trimIndent()
        )!!
        // Uppercase is as unknown as a typo: the whitelist is exact, and the
        // set a module ends up with can only ever be a subset of the five.
        assertThat(manifest.permissions).containsExactly("storage", "files", "chat", "agent").inOrder()
    }

    @Test
    fun `timeout seconds defaults to thirty and clamps into the 1..120 window`() {
        fun timeoutOf(json: String): Int = ZAgentsManifest.parse(json)!!.timeoutSeconds
        assertThat(timeoutOf("""{"name":"m"}""")).isEqualTo(30)
        assertThat(timeoutOf("""{"name":"m","timeoutSeconds":0}""")).isEqualTo(1)
        assertThat(timeoutOf("""{"name":"m","timeoutSeconds":500}""")).isEqualTo(120)
        assertThat(timeoutOf("""{"name":"m","timeoutSeconds":-3}""")).isEqualTo(1)
        // Only a plain int counts — 2.5 and "30" are not ints, and the
        // default is the honest answer to both.
        assertThat(timeoutOf("""{"name":"m","timeoutSeconds":2.5}""")).isEqualTo(30)
        assertThat(timeoutOf("""{"name":"m","timeoutSeconds":"30"}""")).isEqualTo(30)
    }

    @Test
    fun `allowed hosts are trimmed, lowercased and emptied of blanks`() {
        val manifest = ZAgentsManifest.parse(
            """{"name":"m","allowedHosts":["  API.Example.COM ","", "  ","*.Example.com"]}"""
        )!!
        assertThat(manifest.allowedHosts).containsExactly("api.example.com", "*.example.com").inOrder()
    }

    @Test
    fun `the version is kept verbatim and coerced only for the semver column`() {
        fun semverOf(version: String): String =
            ZAgentsManifest.parse("""{"name":"m","version":"$version"}""")!!.semverVersion
        assertThat(semverOf("1.2.3")).isEqualTo("1.2.3")
        assertThat(semverOf("1.2.3-beta.4")).isEqualTo("1.2.3-beta.4")
        assertThat(semverOf("1.2.3.4")).isEqualTo("1.2.3")
        assertThat(semverOf("2024.01.15")).isEqualTo("2024.01.15")
        assertThat(semverOf("v1.2.3")).isEqualTo("0.0.0")
        assertThat(semverOf("2.0")).isEqualTo("0.0.0")
        assertThat(semverOf("beta")).isEqualTo("0.0.0")
        val absent = ZAgentsManifest.parse("""{"name":"m"}""")!!
        assertThat(absent.version).isEqualTo("0.0.0")
        assertThat(absent.semverVersion).isEqualTo("0.0.0")
    }

    @Test
    fun `the mapping onto the plugin row vocabulary`() {
        val manifest = ZAgentsManifest.parse(
            """
            {"name":"daily","title":"Daily Digest","version":"1.2.3.4",
             "permissions":["storage","network","files","chat","agent"],
             "allowedHosts":["api.example.com"]}
            """.trimIndent()
        )!!
        val mapped = manifest.toPluginManifest("main.js")
        assertThat(mapped.id).isEqualTo("zagents.daily")
        assertThat(mapped.name).isEqualTo("Daily Digest")
        assertThat(mapped.version).isEqualTo("1.2.3")
        assertThat(mapped.api).isEqualTo(1)
        assertThat(mapped.entry).isEqualTo("main.js")
        assertThat(mapped.author).isNull()
        assertThat(mapped.license).isEmpty()
        // files maps to storage AND the module-only files name; the duplicate
        // storage from that mapping collapses; order is the declared order.
        assertThat(mapped.permissions)
            .containsExactly("storage", "network", "files", "chat.context", "agent.generate")
            .inOrder()
        assertThat(mapped.networkAllow).containsExactly("api.example.com")
        assertThat(mapped.configSchema).isEmpty()
        assertThat(mapped.commands).isEmpty()
        assertThat(mapped.hooks).isEmpty()
        assertThat(mapped.dirName).isEqualTo("zagents_daily")
    }

    @Test
    fun `a titleless module is known by its machine name`() {
        val manifest = ZAgentsManifest.parse("""{"name":"counter"}""")!!
        assertThat(manifest.displayName).isEqualTo("counter")
        assertThat(manifest.toPluginManifest("main.js").name).isEqualTo("counter")
    }

    @Test
    fun `the entry resolves through package main and then the default`() {
        fun resolvedOf(moduleEntry: String?, packageMain: String?): String {
            val body = if (moduleEntry != null) {
                """{"name":"m","entry":"$moduleEntry"}"""
            } else {
                """{"name":"m"}"""
            }
            return ZAgentsManifest.parse(body)!!.resolveEntry(packageMain)
        }
        assertThat(resolvedOf("lib/start.js", null)).isEqualTo("lib/start.js")
        assertThat(resolvedOf(null, "lib/index.js")).isEqualTo("lib/index.js")
        assertThat(resolvedOf(null, null)).isEqualTo("main.js")
        assertThat(resolvedOf("", "  ")).isEqualTo("main.js")
        // The manifest's own entry wins over the package's main.
        assertThat(resolvedOf("main.js", "lib/index.js")).isEqualTo("main.js")
    }

    @Test
    fun `the description and title are clamped for display sanity`() {
        val manifest = ZAgentsManifest.parse(
            """{"name":"m","title":"${"t".repeat(70)}","description":"${"d".repeat(600)}}"""
        )!!
        assertThat(manifest.title).hasLength(64)
        assertThat(manifest.description).hasLength(500)
    }

    @Test
    fun `validate accepts a clean manifest`() {
        assertThat(ZAgentsManifest.validate(AgentJson.parseToJsonElement(MODULE).jsonObject)).isEmpty()
    }

    @Test
    fun `network without an allowlist is refused with the design's own message`() {
        listOf(
            """{"name":"m","permissions":["network"]}""",
            """{"name":"m","permissions":["network"],"allowedHosts":[]}""",
            """{"name":"m","permissions":["network"],"allowedHosts":["  "]}""",
            """{"name":"m","permissions":["network"],"allowedHosts":"api.example.com"}"""
        ).forEach { body ->
            val errors = ZAgentsManifest.validate(AgentJson.parseToJsonElement(body).jsonObject)
            assertThat(errors).hasSize(1)
            assertThat(errors.single()).isEqualTo(
                "module.json declares the network permission but no allowedHosts allowlist — " +
                    "Room Browser requires an explicit host allowlist; " +
                    "add \"allowedHosts\": [...] to module.json"
            )
        }
    }

    @Test
    fun `an entry that leaves the module is refused by name`() {
        listOf("../evil.js", "/abs.js", "a/../../evil.js", "back\\slash.js").forEach { bad ->
            val errors = ZAgentsManifest.validate(
                AgentJson.parseToJsonElement("""{"name":"m","entry":"$bad"}""").jsonObject
            )
            assertThat(errors).isNotEmpty()
            assertThat(errors.single()).contains("must be a relative path inside the module")
        }
    }

    @Test
    fun `a name whose generated id cannot be stored is refused`() {
        // Five dot-separated segments do not fit the plugin id grammar, and a
        // module whose identity cannot be stored is not one to install.
        val errors = ZAgentsManifest.validate(
            AgentJson.parseToJsonElement("""{"name":"a.b.c.d.e"}""").jsonObject
        )
        assertThat(errors).hasSize(1)
        assertThat(errors.single()).contains("does not fit this app's plugin id grammar")
        // Four segments fit: the prefix plus four is the grammar's own cap.
        assertThat(
            ZAgentsManifest.validate(AgentJson.parseToJsonElement("""{"name":"a.b.c.d"}""").jsonObject)
        ).isEmpty()
    }

    @Test
    fun `inputs schema violations are relayed by the module validator`() {
        val errors = ZAgentsManifest.validate(
            AgentJson.parseToJsonElement(
                """{"name":"m","inputs":[{"key":"E","type":"string","title":"E"}]}"""
            ).jsonObject
        )
        assertThat(errors).isNotEmpty()
        assertThat(errors.single()).contains("inputs")
    }

    // ------------------------------------------------------------ fixtures

    private companion object {
        val MODULE = """
            {"name":"onhomeroom-daily","version":"1.1.0","title":"Daily Digest",
             "description":"Composes a daily summary and posts it.",
             "entry":"main.js",
             "permissions":["storage","network","chat","agent"],
             "allowedHosts":["api.onhomeroom.com","*.example.com"],
             "timeoutSeconds":45}
        """.trimIndent()
    }
}
