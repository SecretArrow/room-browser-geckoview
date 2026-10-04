package com.roombrowser.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Validates the page bridge's SCRIPT assets against the native side, without
 * running an engine.
 *
 * WHY THIS TEST EXISTS. [BridgeManifestTest] guards the manifest. This guards
 * the three files the manifest points at -- `main.js`, `isolated.js` and
 * `GeckoEngineSession.kt` -- because the contracts between them are written in
 * no compiler's language. A marker name, a port name and a message type are
 * strings agreed on by convention across JavaScript, a manifest and Kotlin,
 * and NOTHING in the build checks that the three agree.
 *
 * That is not a hypothetical. The bug this file is modelled on: `main.js`
 * listened for `__roomToIso`, re-labelled it as `__roomFromPage` and forwarded
 * it -- and **no code anywhere produced `__roomToIso`**. The listener was
 * complete, the relay was correct, the manifest was (after its own fix)
 * correct, and the bridge was dead: nothing defined `window.RoomWallet`, so a
 * dApp's `eth_requestAccounts` was answered `4900 Wallet bridge is unavailable`
 * by the page-side shim, twelve retries deep, forever. Nothing logged a
 * problem from the app's side. The only symptom was a page that never called
 * the bridge, and the only place that surfaced was an emulator job.
 *
 * So these assert the CONTRACT, not the presence of a file: every cross-world
 * marker must appear on BOTH sides it crosses, and the port name and message
 * types must agree between the JavaScript that speaks them and the Kotlin that
 * answers them. Like [BridgeManifestTest], a text scan rather than a parse --
 * the JVM unit-test classpath has no JS engine and no JSON implementation.
 */
class BridgeScriptsTest {

    private val mainJs = File("src/main/assets/roombridge/main.js").readText()
    private val isolatedJs = File("src/main/assets/roombridge/isolated.js").readText()
    private val sessionKt =
        File("src/main/kotlin/com/roombrowser/engine/gecko/GeckoEngineSession.kt").readText()

    @Test
    fun main_js_defines_the_globals_the_page_calls() {
        // The page-callable surface, reproduced from the WebView edition's
        // addJavascriptInterface registrations. A missing one of these is
        // silent in exactly the way described above: the page's own shim
        // reports the bridge as unavailable and retries.
        listOf(
            "window.RoomWallet",
            "window.RoomVault",
            "request:",
            "requestCredentials:",
            "reportCredential:"
        ).forEach { fragment ->
            assertThat(mainJs).contains(fragment)
        }
    }

    @Test
    fun main_js_produces_the_marker_it_also_relays() {
        // The exact shape of the bug: the relay half existed and the producer
        // half did not. Both halves are asserted here so that removing either
        // one fails, and the producer is matched with its literal `: 1` value
        // so that a listener-only remnant cannot satisfy it.
        assertThat(mainJs).contains("__roomToIso: 1")
        assertThat(mainJs).contains("__roomFromPage: 1")
    }

    @Test
    fun every_marker_that_crosses_the_world_boundary_appears_on_both_sides() {
        // page -> isolated
        assertThat(isolatedJs).contains("__roomFromPage")
        // isolated -> page, and the isolated half is the other end of all
        // three. `__roomEval` is matched with a negative lookahead because
        // `__roomEvalResult` contains it as a substring, and a plain contains
        // would let the result marker alone satisfy both.
        listOf("__roomEval", "__roomEvalResult", "__roomScripts").forEach { marker ->
            assertThat(mainJs).contains(marker)
            assertThat(isolatedJs).contains(marker)
        }
        assertThat(mainJs).containsMatch(Regex("__roomEval(?!Result)"))
    }

    @Test
    fun the_port_name_is_the_one_the_host_delegates() {
        // Both files carry a comment saying "must match" the other side. A
        // comment is not a check, and a mismatch here means connectNative
        // throws, the port is never held, and the bridge is dead again -- so
        // the value is read out of the Kotlin and looked for in the JS rather
        // than asserted twice against two literals.
        val nativeApp = Regex("BRIDGE_NATIVE_APP\\s*=\\s*\"([^\"]+)\"")
            .find(sessionKt)
            ?.groupValues
            ?.get(1)
        assertThat(nativeApp).isNotNull()
        // The JS holds it in a variable rather than inline at the call, so the
        // binding is what has to match -- asserting the call site would only
        // ever pass for a literal that is not there.
        assertThat(isolatedJs).contains("NATIVE_APP = \"$nativeApp\"")
        assertThat(isolatedJs).contains("connectNative(NATIVE_APP)")
    }

    @Test
    fun the_message_types_are_the_ones_the_other_side_speaks() {
        // Native -> JS: the host sends these, the isolated half must route them.
        listOf("eval", "scripts").forEach { type ->
            assertThat(sessionKt).contains("\"type\", \"$type\"")
            assertThat(isolatedJs).contains("type === \"$type\"")
        }
        // JS -> native: the isolated half sends these, the host must handle
        // both. An unhandled type is dropped without a log -- `when` with no
        // else -- so a rename on either side is silent.
        listOf("evalResult", "app").forEach { type ->
            assertThat(isolatedJs).contains("type: \"$type\"")
            assertThat(sessionKt).contains("\"$type\" ->")
        }
    }
}
