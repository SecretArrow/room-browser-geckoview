package com.roombrowser.domain.task

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which modes need a browser on screen. The runner reads this to decide between
 * opening a page of its own and deferring, so a mode silently landing in the
 * wrong branch is a task that never runs — or one that runs hidden after being
 * saved as visible.
 */
class AiTaskExecutionModeTest {

    @Test
    fun only_headless_runs_without_a_browser_on_screen() {
        assertThat(AiTaskExecutionMode.HEADLESS.needsVisibleBrowser).isFalse()
        assertThat(AiTaskExecutionMode.HEADED.needsVisibleBrowser).isTrue()
        assertThat(AiTaskExecutionMode.STANDARD.needsVisibleBrowser).isTrue()
    }

    @Test
    fun an_absent_choice_is_headless_so_no_existing_task_changes_surface() {
        assertThat(AiTaskRunConfig.DEFAULT.executionMode).isEqualTo(AiTaskExecutionMode.HEADLESS)
        assertThat(AiTaskRunConfig.DEFAULT.executionMode.needsVisibleBrowser).isFalse()
    }
}
