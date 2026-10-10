package com.roombrowser.engine

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File
import java.security.MessageDigest

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
    fun manifest_grants_the_blocking_webrequest_permissions() {
        // The sub-resource blocker is a `webRequest.onBeforeRequest` listener
        // with `["blocking"]`, and each of these three is separately capable of
        // making it do nothing:
        //
        //  - `webRequest` puts the API on `browser.webRequest` at all;
        //  - `webRequestBlocking` is what makes the third `addListener`
        //    argument legal -- without it the listener still registers and
        //    still runs, and its return value is DISCARDED, so every request
        //    it "cancels" is made anyway;
        //  - `<all_urls>` is the host permission that lets it see requests to
        //    third-party hosts, which is the entire population it is aimed at.
        //
        // `webRequestBlocking` is asserted in its QUOTED form for the reason
        // given above: it contains `webRequest` as a prefix, so a bare
        // substring check would be satisfied by `webRequest` alone -- the test
        // would pass on a manifest that cannot block anything.
        listOf("webRequest", "webRequestBlocking", "<all_urls>").forEach { permission ->
            assertThat(manifest).contains("\"$permission\"")
        }
    }

    @Test
    fun manifest_declares_the_background_page_the_blocker_registers_from() {
        // A blocking listener has to exist BEFORE the request it is meant to
        // cancel, and the only place an extension can register one that early
        // is a background script loaded at extension startup. A content script
        // cannot stand in for it: it runs per document, it has no `webRequest`
        // access, and it starts too late to see the document's own
        // sub-resources.
        assertThat(manifest).contains("\"background\"")
        assertThat(manifest).contains("\"blocker.js\"")
    }

    @Test
    fun the_extension_version_is_bumped_whenever_the_extension_changes() {
        // WHY A FINGERPRINT AND NOT JUST THE VERSION FIELD.
        //
        // `WebExtensionController.ensureBuiltIn` -- the call the host makes --
        // does NOT reinstall an extension that is already present with the
        // SAME VERSION. That is the whole point of it, and it means the version
        // string is not documentation: it is the switch that decides whether a
        // changed script, a changed permission or a changed background page
        // ever reaches a device that already has the previous one. Nothing
        // else in the build notices. The release is cut, the APK installs, the
        // extension keeps running its previous code, and the only symptom is
        // the feature that was supposed to change.
        //
        // So the assets are hashed here, and an edit to any of them fails
        // until the constant below is updated -- at which point the failure
        // message says what else has to be updated with it. This is the same
        // trade the rest of this file makes: a text scan for a silent failure
        // that an emulator run would surface an hour later, if at all.
        val directory = File(EXTENSION_DIR)
        assertThat(directory.isDirectory).isTrue()
        val digest = MessageDigest.getInstance("SHA-256")
        directory.listFiles().orEmpty().sortedBy { it.name }.forEach { file ->
            // The NAME is hashed too, so that adding or removing a script is a
            // change even when the remaining bytes are identical.
            digest.update(file.name.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(file.readBytes())
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        assertWithMessage(
            "The bridge extension's assets changed. GeckoView does NOT reinstall a " +
                "built-in extension whose manifest version is unchanged, so this edit " +
                "reaches no device that already has the previous version installed. " +
                "Bump \"version\" in $MANIFEST_PATH and set EXTENSION_FINGERPRINT to " +
                "the value above."
        ).that(actual).isEqualTo(EXTENSION_FINGERPRINT)
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

        /** The extension's own directory: everything `ensureBuiltIn` ships. */
        const val EXTENSION_DIR = "src/main/assets/roombridge"

        /**
         * SHA-256 over the extension's files, name then bytes, in name order.
         *
         * Update this ONLY together with `"version"` in the manifest -- see
         * [the_extension_version_is_bumped_whenever_the_extension_changes].
         */
        const val EXTENSION_FINGERPRINT =
            "bbc30e5e8a0347c1d2ce5c1354115bbee9203a8ce6f78e425fb5fcc2857e9258"
    }
}
