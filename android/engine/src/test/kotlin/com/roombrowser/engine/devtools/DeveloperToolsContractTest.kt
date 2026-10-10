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
}
