package com.roombrowser.engine.devtools

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The Developer Tools capability contract, asserted without an engine.
 *
 * The point of this file is the distinction the same set is used for
 * everywhere else: an absent capability may mean "this engine cannot" or "this
 * build does not yet", and only the first carries a reason. A screen that
 * cannot tell them apart either invents explanations for work that is merely
 * unfinished, or hides a genuine engine limit behind silence. Both are the
 * "never claim a feature is complete unless it is implemented and verified"
 * rule failing in opposite directions.
 */
class DeveloperToolsContractTest {

    private val allCapabilities = DevToolsCapability.entries.toSet()

    @Test
    fun none_reports_no_capability_and_no_subsets() {
        assertThat(DeveloperToolsCapabilities.NONE.capabilities).isEmpty()
        DevToolsCapability.entries.forEach { capability ->
            assertThat(DeveloperToolsCapabilities.NONE.has(capability)).isFalse()
        }
        assertThat(DeveloperToolsCapabilities.NONE.hasAll(allCapabilities)).isFalse()
    }

    @Test
    fun a_present_capability_never_reports_a_reason() {
        val capabilities = DeveloperToolsCapabilities(
            capabilities = setOf(DevToolsCapability.PAGE_SCRIPTING),
            notes = mapOf(DevToolsCapability.PAGE_SCRIPTING to "should never surface")
        )
        assertThat(capabilities.has(DevToolsCapability.PAGE_SCRIPTING)).isTrue()
        assertThat(capabilities.noteFor(DevToolsCapability.PAGE_SCRIPTING)).isNull()
    }

    @Test
    fun an_absent_capability_without_a_reason_says_nothing() {
        val capabilities = DeveloperToolsCapabilities(capabilities = setOf(DevToolsCapability.PAGE_SCRIPTING))
        assertThat(capabilities.has(DevToolsCapability.JS_DEBUGGER)).isFalse()
        assertThat(capabilities.noteFor(DevToolsCapability.JS_DEBUGGER)).isNull()
    }

    @Test
    fun an_absent_capability_can_carry_its_reason() {
        val capabilities = DeveloperToolsCapabilities(
            capabilities = emptySet(),
            notes = mapOf(DevToolsCapability.SECURITY_CERTIFICATE to "no API exposes it on this engine")
        )
        assertThat(capabilities.noteFor(DevToolsCapability.SECURITY_CERTIFICATE))
            .isEqualTo("no API exposes it on this engine")
    }

    @Test
    fun has_all_requires_every_member() {
        val capabilities = DeveloperToolsCapabilities(
            capabilities = setOf(DevToolsCapability.PAGE_SCRIPTING, DevToolsCapability.CONSOLE_CAPTURE)
        )
        assertThat(
            capabilities.hasAll(
                setOf(DevToolsCapability.PAGE_SCRIPTING, DevToolsCapability.CONSOLE_CAPTURE)
            )
        ).isTrue()
        assertThat(
            capabilities.hasAll(
                setOf(
                    DevToolsCapability.PAGE_SCRIPTING,
                    DevToolsCapability.CONSOLE_CAPTURE,
                    DevToolsCapability.JS_DEBUGGER
                )
            )
        ).isFalse()
    }

    @Test
    fun the_default_inspector_answers_absent_instead_of_failing() {
        val inspector = EngineInspector.NONE
        assertThat(inspector.capabilities.capabilities).isEmpty()
        assertThat(runBlocking { inspector.cookies("https://example.test") }).isEmpty()
        assertThat(runBlocking { inspector.clearCookies("https://example.test") }).isFalse()
        assertThat(runBlocking { inspector.securityInfo() }).isNull()
        assertThat(runBlocking { inspector.openProtocolSession() }).isNull()
        // Every default is a no-op, so an engine that serves nothing is still
        // safe to start, stop and close.
        inspector.startConsoleCapture { }
        inspector.stopConsoleCapture()
        inspector.startNetworkCapture { }
        inspector.stopNetworkCapture()
        inspector.close()
        inspector.close()
    }

    @Test
    fun the_protocol_session_default_is_closable_and_drains_to_nothing() {
        val session = object : EngineProtocolSession {
            override suspend fun send(method: String, paramsJson: String): String = "{}"
            override val events = emptyFlow<String>()
            override fun close() = Unit
        }
        assertThat(runBlocking { session.send("Runtime.evaluate", "{}") }).isEqualTo("{}")
        assertThat(runBlocking { session.events.firstOrNull() }).isNull()
        session.close()
    }

    @Test
    fun an_engine_that_serves_nothing_still_has_the_overview() {
        // The single deliberate exception: the Overview panel reports the page
        // and this very capability set, so it depends on no engine at all. If
        // this ever stops being true, the panel whose job is to explain the gaps
        // is the one that disappears with them.
        assertThat(DeveloperToolsCapabilities.NONE.serves(DevToolsPanelId.OVERVIEW)).isTrue()
        assertThat(DeveloperToolsCapabilities.NONE.servedPanels()).containsExactly(DevToolsPanelId.OVERVIEW)
    }

    @Test
    fun the_console_is_served_by_either_of_its_two_sources() {
        val pageSide = DeveloperToolsCapabilities(setOf(DevToolsCapability.CONSOLE_CAPTURE))
        val engineSide = DeveloperToolsCapabilities(setOf(DevToolsCapability.ENGINE_CONSOLE))
        assertThat(pageSide.serves(DevToolsPanelId.CONSOLE)).isTrue()
        assertThat(engineSide.serves(DevToolsPanelId.CONSOLE)).isTrue()
        assertThat(DeveloperToolsCapabilities.NONE.serves(DevToolsPanelId.CONSOLE)).isFalse()
    }

    @Test
    fun a_panel_whose_capability_is_absent_is_not_served() {
        val scriptOnly = DeveloperToolsCapabilities(setOf(DevToolsCapability.PAGE_SCRIPTING))
        // Page scripting alone is enough for the panels built on probe scripts.
        assertThat(scriptOnly.serves(DevToolsPanelId.APPLICATION)).isTrue()
        // It is not enough for the two that need a signal the page cannot give.
        assertThat(scriptOnly.serves(DevToolsPanelId.NETWORK)).isFalse()
        assertThat(scriptOnly.serves(DevToolsPanelId.SECURITY)).isFalse()
        // Nor for anything that needs the engine's own debugging protocol.
        assertThat(scriptOnly.serves(DevToolsPanelId.SOURCES)).isFalse()
        assertThat(scriptOnly.serves(DevToolsPanelId.MEMORY)).isFalse()
        // This set is the panel IDS the capability unlocks, which is a longer
        // list than the panels that have been BUILT -- Elements, Audit,
        // Recorder and Performance are classified here before their panels
        // exist, so that landing one is an entry in the registry and not also a
        // change to this table.
        assertThat(scriptOnly.servedPanels()).containsExactly(
            DevToolsPanelId.OVERVIEW,
            DevToolsPanelId.APPLICATION,
            DevToolsPanelId.ELEMENTS,
            DevToolsPanelId.AUDIT,
            DevToolsPanelId.RECORDER,
            DevToolsPanelId.PERFORMANCE
        )
    }

    @Test
    fun every_panel_id_has_an_answer() {
        // Exhaustive by construction: a new panel id must be classified here
        // rather than silently defaulting to hidden.
        val served = DeveloperToolsCapabilities(allCapabilities).servedPanels()
        assertThat(served).containsExactlyElementsIn(DevToolsPanelId.entries)
    }
}
