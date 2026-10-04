package com.roombrowser.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Fails the build if any module other than `:engine` names a rendering engine.
 *
 * WHY THIS TEST EXISTS. `:app` depends on this module as `implementation`, not
 * `api`, so no `org.mozilla.geckoview` type is on its compile classpath -- the
 * app *cannot* compile code that branches on which engine it runs. That is the
 * property the two-edition plan rests on: everything above the facade is
 * byte-identical between the WebView edition and the GeckoView edition, so a
 * feature added once lands in both by copying files rather than by rewriting
 * them.
 *
 * A Gradle dependency is a one-line change, though, and the failure it causes
 * is silent in the direction that matters: someone reaching for a GeckoView
 * convenience would first have to flip `implementation` to `api`, and nothing
 * would object. This test objects. It is the difference between "the boundary
 * is intended" and "the boundary is enforced".
 *
 * It scans source text rather than compiled classes because the violation is
 * legible at the point it is written, and because it needs no classpath -- so
 * it runs in milliseconds as part of the ordinary unit-test task.
 */
class EngineBoundaryTest {

    @Test
    fun no_module_outside_the_engine_names_a_rendering_engine() {
        val offenders = mutableListOf<String>()

        for (root in GUARDED_SOURCE_ROOTS) {
            val dir = File(root)
            // A missing directory means the module was renamed or moved; that
            // should fail loudly rather than quietly guard nothing.
            assertThat(dir.isDirectory).isTrue()
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    if (FORBIDDEN_IMPORT.containsMatchIn(file.readText())) {
                        offenders += file.path.removePrefix("../")
                    }
                }
        }

        assertThat(offenders).isEmpty()
    }

    private companion object {
        /**
         * Relative to the module directory, which Gradle makes the test's cwd.
         * `:engine` itself is absent on purpose -- it is the one place these
         * imports are the point.
         */
        val GUARDED_SOURCE_ROOTS = listOf(
            "../app/src",
            "../core/domain/src",
            "../core/wallet/src",
        )

        val FORBIDDEN_IMPORT = Regex("""^\s*import\s+org\.mozilla\.geckoview""", RegexOption.MULTILINE)
    }
}
