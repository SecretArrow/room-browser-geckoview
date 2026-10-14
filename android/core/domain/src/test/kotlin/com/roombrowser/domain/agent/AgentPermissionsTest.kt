package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The granular agent permissions, held against the tool catalogue.
 *
 * This is the join between two lists that are easy to let drift: the tools the
 * model can call, and the permissions that govern them. A devtools tool added
 * without an entry in [AgentPermissions.required] would read like a tool that
 * needs nothing — the same silent hole as a permission naming no tool at all —
 * and neither mistake is visible from the running app.
 *
 * Every test ends on a VOID-returning Truth call: a Kotlin function whose last
 * expression has a value compiles to a non-void method, and JUnit4 then refuses
 * the whole class instead of running the rest of it. `containsExactly…` and
 * `containsAtLeast…` return `Ordered`, so they are never the last line.
 */
class AgentPermissionsTest {

    private val catalogue = AgentTools.toolDefs().map { it.function.name }

    @Test
    fun `every devtools tool is in the catalogue`() {
        assertThat(catalogue).containsAtLeastElementsIn(AgentTools.DEVTOOLS_TOOLS)
        assertThat(AgentTools.DEVTOOLS_TOOLS).isNotEmpty()
    }

    @Test
    fun `each devtools tool declares the permission it needs`() {
        val expected = mapOf(
            AgentTools.DEVTOOLS_CONSOLE to AgentPermissions.DEVTOOLS_READ,
            AgentTools.DEVTOOLS_INSPECT to AgentPermissions.DEVTOOLS_READ,
            AgentTools.DEVTOOLS_ELEMENTS to AgentPermissions.DEVTOOLS_READ,
            AgentTools.DEVTOOLS_DIAGNOSE to AgentPermissions.DEVTOOLS_READ,
            AgentTools.DEVTOOLS_NETWORK to AgentPermissions.DEVTOOLS_NETWORK,
            AgentTools.DEVTOOLS_AUDIT to AgentPermissions.DEVTOOLS_STORAGE,
            AgentTools.DEVTOOLS_STORAGE to AgentPermissions.DEVTOOLS_STORAGE,
            AgentTools.DEVTOOLS_SECURITY to AgentPermissions.DEVTOOLS_SECURITY,
            AgentTools.DEVTOOLS_REPORT to AgentPermissions.EXPORT_DIAGNOSTICS
        )
        // Everything that reads a buffer or writes a file is covered here; the
        // two that only move the page are the subject of the next test.
        assertThat(expected.keys)
            .containsAtLeastElementsIn(
                AgentTools.DEVTOOLS_TOOLS - AgentTools.DEVTOOLS_RELOAD - AgentTools.DEVTOOLS_PATCH
            )
        expected.forEach { (tool, permission) ->
            assertThat(AgentPermissions.required(tool)).containsExactly(permission)
        }
    }

    @Test
    fun `the page reload and the page patch need no read permission`() {
        // devtools_reload is a navigation and devtools_patch is a WRITE; neither
        // reads a buffer, so neither is governed by a read permission. The patch
        // is held back by its own confirmation instead — it is interactive, and
        // the executors classify it with the destructive set.
        assertThat(AgentPermissions.required(AgentTools.DEVTOOLS_RELOAD)).isEmpty()
        assertThat(AgentPermissions.required(AgentTools.DEVTOOLS_PATCH)).isEmpty()
        assertThat(AgentTools.INTERACTIVE_TOOLS).contains(AgentTools.DEVTOOLS_PATCH)
    }

    @Test
    fun `a withheld permission refuses the tool by name and names the setting`() {
        val withheld = setOf(AgentPermissions.DEVTOOLS_NETWORK)
        val refusal = AgentPermissions.refusal(AgentTools.DEVTOOLS_NETWORK, withheld)
        assertThat(refusal).isNotNull()
        assertThat(refusal).contains(AgentTools.DEVTOOLS_NETWORK)
        assertThat(refusal).contains(AgentPermissions.label(AgentPermissions.DEVTOOLS_NETWORK))
        // Honest about WHY, so the model stops rather than retrying the call.
        assertThat(refusal).doesNotContain("unknown tool")
    }

    @Test
    fun `withholding one permission leaves the tools that do not need it alone`() {
        val withheld = setOf(AgentPermissions.DEVTOOLS_READ)
        assertThat(AgentPermissions.refusal(AgentTools.DEVTOOLS_CONSOLE, withheld)).isNotNull()
        assertThat(AgentPermissions.refusal(AgentTools.DEVTOOLS_NETWORK, withheld)).isNull()
        assertThat(AgentPermissions.refusal(AgentTools.DEVTOOLS_REPORT, withheld)).isNull()
        assertThat(AgentPermissions.refusal(AgentTools.DEVTOOLS_PATCH, withheld)).isNull()
    }

    @Test
    fun `an empty withheld set never refuses a tool`() {
        // A profile that predates this field stores nothing, so every tool must
        // still be granted here — the safe direction of a negated set.
        catalogue.forEach { tool ->
            assertThat(AgentPermissions.refusal(tool, emptySet())).isNull()
            assertThat(AgentPermissions.granted(tool, emptySet())).isTrue()
        }
    }

    @Test
    fun `a withheld permission flips granted to false`() {
        val withheld = setOf(AgentPermissions.EXPORT_DIAGNOSTICS)
        assertThat(AgentPermissions.granted(AgentTools.DEVTOOLS_REPORT, withheld)).isFalse()
        assertThat(AgentPermissions.granted(AgentTools.DEVTOOLS_REPORT, emptySet())).isTrue()
    }

    @Test
    fun `every permission a tool requires is one this build knows`() {
        // A typo here would store a name the settings screen never writes and no
        // test would notice; ALL is the contract the settings UI reads.
        AgentTools.DEVTOOLS_TOOLS.forEach { tool ->
            AgentPermissions.required(tool).forEach { permission ->
                assertThat(AgentPermissions.ALL).contains(permission)
            }
        }
    }

    @Test
    fun `the node tools both ride the one node permission`() {
        listOf(AgentTools.NODE_RUN, AgentTools.NODE_PACKAGES).forEach { tool ->
            assertThat(AgentPermissions.required(tool)).containsExactly(AgentPermissions.AGENT_NODE)
        }
        assertThat(AgentPermissions.refusal(AgentTools.NODE_PACKAGES, setOf(AgentPermissions.AGENT_NODE)))
            .isNotNull()
    }

    @Test
    fun `every permission has a label and a description`() {
        AgentPermissions.ALL.forEach { permission ->
            assertThat(AgentPermissions.label(permission)).isNotEqualTo(permission)
            assertThat(AgentPermissions.description(permission)).isNotEmpty()
        }
    }

    @Test
    fun `cookies are declared but name no tool yet`() {
        // An honest boundary rather than an oversight: the permission exists so
        // settings and stored profiles agree with the plan, but the Application
        // panel that will read cookies is a separate change. A tool that starts
        // requiring it should make this test fail on purpose.
        assertThat(AgentPermissions.ALL).contains(AgentPermissions.DEVTOOLS_COOKIES)
        catalogue.forEach { tool ->
            assertThat(AgentPermissions.required(tool)).doesNotContain(AgentPermissions.DEVTOOLS_COOKIES)
        }
    }
}
