package com.roombrowser.engine

import com.google.common.truth.Truth.assertThat
import com.roombrowser.engine.devtools.DevToolsCapability
import com.roombrowser.engine.gecko.GeckoDevTools
import org.junit.Test

/**
 * The GeckoView edition's declared Developer Tools capabilities, pinned.
 *
 * The set is not decoration: the panel hides a capability that is absent and
 * explains the ones that carry a note, so a set that over-claims shows a panel
 * that cannot fill, and one that under-claims hides work this build really
 * does. The asymmetry with the WebView edition is deliberate and is asserted
 * here rather than left to drift: `NETWORK_RESPONSE_HEADERS` IS present in this
 * edition (the extension's `onHeadersReceived`) and `ENGINE_CONSOLE` is NOT.
 */
class GeckoDevToolsCapabilitiesTest {

    private val capabilities = GeckoDevTools.CAPABILITIES

    @Test
    fun the_declared_set_is_exactly_what_this_edition_serves() {
        assertThat(capabilities.capabilities).isEqualTo(
            setOf(
                DevToolsCapability.PAGE_SCRIPTING,
                DevToolsCapability.CONSOLE_CAPTURE,
                DevToolsCapability.NETWORK_REQUEST_LINE,
                DevToolsCapability.NETWORK_RESPONSE_HEADERS
            )
        )
    }

    @Test
    fun response_headers_are_present_and_engine_console_is_not() {
        assertThat(capabilities.has(DevToolsCapability.NETWORK_RESPONSE_HEADERS)).isTrue()
        assertThat(capabilities.has(DevToolsCapability.ENGINE_CONSOLE)).isFalse()
    }

    @Test
    fun the_engine_console_absence_carries_its_exact_reason() {
        // Spelled out here rather than read from the constant: a test that
        // asserts a constant against itself agrees with itself when the text
        // is reworded, and this text is a claim about the engine.
        assertThat(capabilities.noteFor(DevToolsCapability.ENGINE_CONSOLE)).isEqualTo(
            "GeckoView exposes no console callback: GeckoSession has no onConsoleMessage and " +
                "there is no ConsoleDelegate, and GeckoRuntimeSettings.consoleOutput(true) " +
                "only writes engine messages to logcat under the tag GeckoConsole at a fixed " +
                "level, with no way to read them in the app."
        )
    }

    @Test
    fun no_other_absent_capability_carries_a_reason() {
        // "not built yet" and "cannot" are different answers, and only the
        // second one may put a sentence on the screen.
        DevToolsCapability.entries
            .filterNot { capabilities.has(it) }
            .filterNot { it == DevToolsCapability.ENGINE_CONSOLE }
            .forEach { capability ->
                assertThat(capabilities.noteFor(capability)).isNull()
            }
    }
}
