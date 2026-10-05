package com.roombrowser.browser

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Guards the restart-token cancel path against the rewrite that broke it.
 *
 * `Intent.filterEquals` -- which decides whether two PendingIntents are the
 * same token -- reads neither flags nor extras, so the two restart tokens are
 * separated by their request code alone (see RestartAlarm.kt). A cancel that
 * reads them back with FLAG_UPDATE_CURRENT therefore does not merely fail to
 * cancel: it REWRITES the armed token to the throwaway intent. Run 37264659353
 * is what that costs -- the backstop was deliberately built without CLEAR_TASK
 * so that it could never tear down a live engine, the cancel put CLEAR_TASK
 * back on it, the alarm fired anyway (`flg=0x10008000`, no extras) and the
 * ':browser' process was left alive with a full Gecko runtime, no activity and
 * no frame for two minutes.
 *
 * A source scan, like AndroidTestNamingTest, because nothing else in the build
 * can see this: it compiles, lints and unit-tests green either way, and the
 * damage only shows up in an emulator run that costs an hour.
 */
class RestartAlarmCancelTest {

    @Test
    fun restart_alarm_cancel_reads_the_armed_token_instead_of_rewriting_it() {
        val source = sourceFile().readText()
        val start = source.indexOf("private fun cancelPendingRestartAlarm()")
        assertThat(start).isAtLeast(0)

        val rest = source.substring(start)
        // UP TO the next member, not FROM it. `range.first` is the match's start
        // index, so `rest::substring` would take the tail of the file and the
        // scan would be about whatever code happens to follow.
        val end = NEXT_MEMBER.find(rest)?.range?.first ?: rest.length
        val body = rest.substring(0, end)

        assertThat(body).contains("RESTART_REQUEST_CODES")
        assertThat(body).contains("FLAG_NO_CREATE")
        assertThat(body).doesNotContain("FLAG_UPDATE_CURRENT")
    }

    /**
     * From wherever this test runs. A Gradle test's working directory is its
     * module directory, but the answer is checked rather than assumed: a guard
     * that silently scans nothing is worse than none.
     */
    private fun sourceFile(): File =
        listOf("src/main/kotlin", "app/src/main/kotlin")
            .map { File(it, "com/roombrowser/browser/BrowserActivity.kt") }
            .firstOrNull { it.isFile }
            ?: error(
                "BrowserActivity.kt not found from ${File(".").absolutePath}; " +
                    "this guard would otherwise pass without reading anything"
            )

    private companion object {
        /** The next member declaration, at the class body's own indent. */
        val NEXT_MEMBER = Regex("\n    (private )?fun ")
    }
}
