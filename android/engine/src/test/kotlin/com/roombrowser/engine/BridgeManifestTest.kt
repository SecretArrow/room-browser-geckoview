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
 *  - the permission TRIO `geckoViewAddons`, `nativeMessaging` and
 *    `nativeMessagingFromContent`, which together are what let a content
 *    script hold a port at all;
 *  - `"world": "MAIN"` on the page-world half, without which the app's globals
 *    are defined in a world the page cannot see;
 *  - `run_at: document_start`, without which the device shim runs after the
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
 * THE SPELLING IS snake_case, AND THIS TEST ONCE ASSERTED THE WRONG ONE.
 * `run_at`, `all_frames` and `match_about_blank` are the manifest keys; the
 * camelCase forms (`runAt`, `allFrames`, `matchAboutBlank`) belong to the
 * separate `contentScripts.register()` JS API. The first version of this file
 * asserted the camelCase forms, so it did not merely fail to catch the bug --
 * it *held it in place*: the manifest, the test and a green quality job all
 * agreed on a spelling GeckoView warned about once and then dropped, and the
 * only place the truth surfaced was an emulator run an hour long. A guard test
 * is written from the specification, never from the file it is guarding.
 *
 * Hence [no_camel_case_manifest_keys]: asserting the right keys is not enough,
 * because a manifest can carry both, and the wrong one is the one that warns.
 *
 * THE SAME MISTAKE WAS THEN MADE A SECOND TIME, WITH THE PERMISSIONS. The
 * first version of the permission test asserted `nativeMessagingFromContent`
 * alone -- because that was the one the manifest happened to carry -- and the
 * GeckoView example lists three. `geckoViewAddons` is what makes the extension
 * privileged enough for a message delegate to exist at all, `nativeMessaging`
 * is what puts `connectNative` on `runtime`, and `nativeMessagingFromContent`
 * is what extends that to a content script rather than a background page.
 * Missing two of the three produced an extension that installed cleanly, a
 * delegate that installed cleanly, and a port that was never opened -- and the
 * guard test passed throughout, because it had been read off the file instead
 * of off the specification. The lesson of the paragraph above had already been
 * written down when this happened.
 *
 * Deliberately a text scan rather than a JSON parse: the JVM unit-test
 * classpath has no JSON implementation (android.jar's org.json is a stub that
 * throws), and adding a dependency to validate eight fields would be the
 * larger change.
 */
class BridgeManifestTest {

    private val manifest = File(MANIFEST_PATH).readText()

    @Test
    fun manifest_grants_the_documented_permission_trio() {
        // All three, from the GeckoView web-extensions example rather than from
        // the manifest. Asserting one and finding it present is not evidence
        // about the other two: `nativeMessaging` is a strict prefix of
        // `nativeMessagingFromContent`, so the quoted forms are compared, and
        // an absent permission fails here in a minute instead of in an
        // emulator run an hour long.
        listOf("geckoViewAddons", "nativeMessaging", "nativeMessagingFromContent")
            .forEach { permission ->
                assertThat(manifest).contains("\"$permission\"")
            }
    }

    @Test
    fun page_world_half_is_declared_as_the_main_world() {
        assertThat(manifest).contains("\"world\": \"MAIN\"")
    }

    @Test
    fun both_halves_start_at_document_start() {
        val occurrences = Regex("\"run_at\":\\s*\"document_start\"").findAll(manifest).count()
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
        assertThat(manifest).contains("\"all_frames\": true")
        assertThat(manifest).contains("\"all_frames\": false")
    }

    @Test
    fun page_world_half_matches_about_blank_documents() {
        assertThat(manifest).contains("\"match_about_blank\": true")
    }

    @Test
    fun no_camel_case_manifest_keys() {
        // GeckoView does not reject these: it logs one
        // "An unexpected property was found in the WebExtension manifest" per
        // key and drops the key, so `runAt` degrades `document_start` to
        // `document_idle` and `allFrames` degrades to the top frame only. Both
        // are invisible from the app side -- the page just never calls the
        // bridge -- which is exactly why this asserts absence rather than
        // trusting the keys above to imply it.
        listOf("runAt", "allFrames", "matchAboutBlank").forEach { key ->
            assertThat(manifest).doesNotContain("\"$key\"")
        }
    }

    private companion object {
        /** Relative to the module directory, which Gradle makes the test's cwd. */
        const val MANIFEST_PATH = "src/main/assets/roombridge/manifest.json"
    }
}
