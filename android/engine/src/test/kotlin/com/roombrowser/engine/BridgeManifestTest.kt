package com.roombrowser.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Validates the bridge extension's manifest without running an engine.
 *
 * WHY THIS TEST EXISTS. The whole scripting path -- the wallet bridge, the
 * vault bridge, the device shim -- depends on four things being spelled
 * exactly right in a JSON file that nothing else in the build reads:
 *
 *  - the permission `nativeMessagingFromContent`, which is what lets a
 *    content script hold a port at all;
 *  - `"world": "MAIN"` on the page-world half, without which the app's globals
 *    are defined in a world the page cannot see;
 *  - `runAt: document_start`, without which the device shim runs after the
 *    page has already measured the environment it claims to hide;
 *  - an extension id matching the one the host passes to ensureBuiltIn.
 *
 * Every one of those failures is SILENT. GeckoView does not report a bad
 * manifest, a wrong permission or an unknown world as an error -- it simply
 * does not inject, and the symptom is a dApp that sees no wallet. Finding that
 * out through an emulator run costs the better part of an hour; finding it out
 * here costs milliseconds. That asymmetry is the entire justification for a
 * test whose subject is a text file.
 *
 * Deliberately a text scan rather than a JSON parse: the JVM unit-test
 * classpath has no JSON implementation (android.jar's org.json is a stub that
 * throws), and adding a dependency to validate eight fields would be the
 * larger change.
 */
class BridgeManifestTest {

    private val manifest = File(MANIFEST_PATH).readText()

    @Test
    fun manifest_grants_the_permission_that_allows_a_content_script_port() {
        assertThat(manifest).contains("\"nativeMessagingFromContent\"")
    }

    @Test
    fun page_world_half_is_declared_as_the_main_world() {
        assertThat(manifest).contains("\"world\": \"MAIN\"")
    }

    @Test
    fun both_halves_start_at_document_start() {
        val occurrences = Regex("\"runAt\":\\s*\"document_start\"").findAll(manifest).count()
        assertThat(occurrences).isEqualTo(2)
    }

    @Test
    fun extension_id_matches_the_one_the_host_registers() {
        assertThat(manifest).contains("\"id\": \"roombridge@roombrowser.com\"")
    }

    @Test
    fun page_world_half_covers_every_frame_and_the_port_half_does_not() {
        // One port per document is what the app-side pending-result bookkeeping
        // assumes; the page-world half needs every frame because the device
        // shim must be installed in each of them.
        assertThat(manifest).contains("\"allFrames\": true")
        assertThat(manifest).contains("\"allFrames\": false")
    }

    private companion object {
        /** Relative to the module directory, which Gradle makes the test's cwd. */
        const val MANIFEST_PATH = "src/main/assets/roombridge/manifest.json"
    }
}
