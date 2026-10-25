package com.roombrowser.domain.settings

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Fails the build when a stored settings field has no reader.
 *
 * A field the settings screen writes and nothing else ever reads is a control
 * the user can press that does nothing — the bug class this guard exists to
 * turn into a build failure instead of an audit finding. `homepageEnabled`
 * and `downloadSubfolder` were each exactly that.
 *
 * WHAT DOES NOT COUNT AS A READER. Two files are skipped on purpose:
 * `BrowserSettingsUi.kt` reads every field back to draw its own switch, and
 * `AgentAppActions.kt` reads every whitelisted field to describe it to a
 * model. Both round-trips show that the field is stored, never that anything
 * acts on it, so counting them would make this guard pass on the very fields
 * it is meant to catch.
 *
 * The scan is textual. It cannot follow a value through a helper, so a field
 * read only inside a function that is itself never called still passes here;
 * that is the limit of a source scan, and the reason the allow-list carries a
 * written reason rather than a bare name.
 */
class SettingsReachabilityTest {

    @Test
    fun `every stored settings field has a reader`() {
        val profile = File(root(), "core/domain/src/main/kotlin/com/roombrowser/domain/model/Profile.kt")
        assertWithMessage("settings declaration not found at %s", profile.path)
            .that(profile.isFile).isTrue()

        val declaration = profile.readText()
        val declared = linkedMapOf(
            "ProfileSettings" to declaredFields(declaration, "ProfileSettings"),
            "BrowserGlobalSettings" to declaredFields(declaration, "BrowserGlobalSettings")
        )

        // A regex that quietly stopped matching would leave this guard passing
        // over an empty field list, so the sizes are asserted rather than the
        // extraction being trusted.
        assertThat(declared.getValue("ProfileSettings").size).isAtLeast(40)
        assertThat(declared.getValue("BrowserGlobalSettings").size).isAtLeast(10)

        val sources = scannedSources()
        assertThat(sources.size).isAtLeast(100)

        val unreached = declared.flatMap { (owner, fields) ->
            fields.filter { field -> sources.none { hasRead(it, field) } }
                .map { "$owner.$it" }
        }.filterNot { it in ALLOWED_WITHOUT_READER }

        assertWithMessage(
            "These stored settings are written but never read by anything the " +
                "browser does, so the control that sets them does nothing: %s. " +
                "Wire the field, delete it, or add it to ALLOWED_WITHOUT_READER " +
                "with the reason it is not a dead control.",
            unreached
        ).that(unreached).isEmpty()
    }

    /**
     * The negative control. Without it a detector that reported "read" for
     * every field would pass the test above while proving nothing.
     */
    @Test
    fun `the detector tells a read from a write`() {
        assertThat(hasRead("val x = settings.fontScale", "fontScale")).isTrue()
        assertThat(hasRead("if (it.blockAds) return", "blockAds")).isTrue()
        assertThat(hasRead("textScale = settings.fontScale,", "fontScale")).isTrue()
        assertThat(hasRead("if (settings.fontScale > 1f) zoom()", "fontScale")).isTrue()
        assertThat(hasRead("s.copy(fontScale = v)", "fontScale")).isFalse()
        assertThat(hasRead("settings.fontScale = 2f", "fontScale")).isFalse()
        assertThat(hasRead("val fontScale: Float = 1.0f", "fontScale")).isFalse()
        assertThat(hasRead("var fontScale = 1.0f", "fontScale")).isFalse()
        assertThat(hasRead("val mine = myFontScaleX", "fontScale")).isFalse()
        assertThat(hasRead("val other = legacy.fontScale", "blockAds")).isFalse()
    }

    @Test
    fun `declared field extraction reads both classes`() {
        val fields = declaredFields(DECLARATION_SAMPLE, "Sample")
        assertThat(fields).containsExactly("alpha", "beta", "gamma").inOrder()
    }

    // ---- the scan ---------------------------------------------------------

    /** Working directory of a Gradle test task is the module directory. */
    private fun root(): File = File("..").canonicalFile.parentFile

    private fun scannedSources(): List<String> = SCAN_ROOTS
        .map { File(root(), it) }
        .onEach { assertWithMessage("scan root %s is not a directory", it.path).that(it.isDirectory).isTrue() }
        .flatMap { it.walkTopDown().toList() }
        .filter { it.isFile && it.extension == "kt" }
        .filterNot { file -> SKIPPED_DIRS.any { "/$it/" in file.path } }
        .filterNot { it.name in SKIPPED_FILES }
        .map { file ->
            val text = file.readText()
            if (file.name == "Profile.kt") blankDataClassBodies(text) else text
        }
        .map(::strip)

    /**
     * A read is a mention of the field that is neither a declaration nor an
     * assignment. `settings.fontScale` is a read; `copy(fontScale = v)`,
     * `val fontScale = 1f` and `settings.fontScale = 2f` are not, and prove
     * only that a value can be stored.
     */
    private fun hasRead(source: String, field: String): Boolean =
        readPattern(field).containsMatchIn(source)

    private fun readPattern(field: String): Regex = Regex(
        """(?<!val\s)(?<!var\s)\b${Regex.escape(field)}\b(?!\s*=(?!=))"""
    )

    /** Drops comments, imports, package lines and string literals. */
    private fun strip(source: String): String {
        val unblocked = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL).replace(source, " ")
        return unblocked.lineSequence().joinToString("\n") { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("import ") || trimmed.startsWith("package ")) {
                ""
            } else {
                STRING_LITERAL.replace(line, "\"\"").substringBefore("//")
            }
        }
    }

    /** The field names declared in one data class body, in declaration order. */
    private fun declaredFields(source: String, className: String): List<String> {
        val header = source.indexOf("data class $className(")
        assertWithMessage("no declaration of %s", className).that(header).isAtLeast(0)
        val open = source.indexOf('(', header)
        var depth = 0
        var close = -1
        for (i in open until source.length) {
            if (source[i] == '(') depth++
            if (source[i] == ')') {
                depth--
                if (depth == 0) {
                    close = i
                    break
                }
            }
        }
        assertWithMessage("unterminated declaration of %s", className).that(close).isAtLeast(0)
        return source.substring(open + 1, close).lineSequence()
            .mapNotNull { FIELD_DECLARATION.find(it.substringBefore("//"))?.groupValues?.get(1) }
            .toList()
    }

    /** Replaces the bodies of the settings data classes with blank lines. */
    private fun blankDataClassBodies(source: String): String {
        var text = source
        for (className in listOf("ProfileSettings", "BrowserGlobalSettings")) {
            val header = text.indexOf("data class $className(")
            if (header < 0) continue
            val open = text.indexOf('(', header)
            var depth = 0
            for (i in open until text.length) {
                if (text[i] == '(') depth++
                if (text[i] == ')') {
                    depth--
                    if (depth == 0) {
                        val blanked = "\n".repeat(text.substring(open + 1, i).count { it == '\n' })
                        text = text.substring(0, open + 1) + blanked + text.substring(i)
                        break
                    }
                }
            }
        }
        return text
    }

    private companion object {
        val SCAN_ROOTS = listOf("app/src", "core/domain/src", "core/wallet/src", "engine/src")

        /** Test sources are not production readers, and neither are build outputs. */
        val SKIPPED_DIRS = listOf("test", "androidTest", "testFixtures", "build")

        val SKIPPED_FILES = setOf("BrowserSettingsUi.kt", "AgentAppActions.kt")

        val FIELD_DECLARATION = Regex("""^\s*val\s+([A-Za-z_][A-Za-z0-9_]*)\s*:""")

        /**
         * Written as an escaped pattern, not a raw string: a raw string cannot
         * carry the closing quote without ambiguity about where it ends.
         */
        val STRING_LITERAL = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")

        /**
         * Fields with no reader anywhere, each with the reason it is not a dead
         * control. Keep this list short: every entry is a control that cannot
         * be checked by this guard.
         */
        val ALLOWED_WITHOUT_READER = emptySet<String>()

        val DECLARATION_SAMPLE = """
            @Serializable
            data class Sample(
                val alpha: Int = 0,
                val beta: String? = null,
                // a comment mentioning gamma: Boolean = false
                val gamma: List<Int> = listOf(1, 2)
            )
        """.trimIndent()
    }
}
