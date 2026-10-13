package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AgentFailureWatchTest {

    @Test
    fun `the third attempt at a tool that failed twice is refused`() {
        val watch = AgentFailureWatch()
        watch.record(AgentTools.CLICK, ok = false, output = "no element with ref 3")
        assertThat(watch.refusal(AgentTools.CLICK)).isNull()
        watch.record(AgentTools.CLICK, ok = false, output = "no element with ref 3")
        val refusal = watch.refusal(AgentTools.CLICK)
        assertThat(refusal).isNotNull()
        assertThat(refusal).contains("failed 2 times in a row")
        assertThat(refusal).contains("no element with ref 3")
    }

    @Test
    fun `a success on the tool clears its streak`() {
        val watch = AgentFailureWatch()
        watch.record(AgentTools.CLICK, ok = false, output = "boom")
        watch.record(AgentTools.CLICK, ok = false, output = "boom")
        watch.record(AgentTools.CLICK, ok = true, output = "clicked")
        assertThat(watch.refusal(AgentTools.CLICK)).isNull()
    }

    @Test
    fun `reading the page is the way out it names, so it clears every streak`() {
        val watch = AgentFailureWatch()
        watch.record(AgentTools.CLICK, ok = false, output = "boom")
        watch.record(AgentTools.CLICK, ok = false, output = "boom")
        watch.record(AgentTools.FILL_INPUT, ok = false, output = "boom")
        watch.record(AgentTools.READ_PAGE, ok = true, output = "[1] button")
        assertThat(watch.refusal(AgentTools.CLICK)).isNull()
        assertThat(watch.refusal(AgentTools.FILL_INPUT)).isNull()
    }

    @Test
    fun `a refusal is per tool, so one tool failing is not another's loop`() {
        val watch = AgentFailureWatch()
        watch.record(AgentTools.CLICK, ok = false, output = "boom")
        watch.record(AgentTools.PRESS_ENTER, ok = false, output = "boom")
        assertThat(watch.refusal(AgentTools.CLICK)).isNull()
        assertThat(watch.refusal(AgentTools.PRESS_ENTER)).isNull()
    }

    @Test
    fun `a failed read does not clear the streaks it was meant to escape`() {
        val watch = AgentFailureWatch()
        watch.record(AgentTools.CLICK, ok = false, output = "boom")
        watch.record(AgentTools.CLICK, ok = false, output = "boom")
        watch.record(AgentTools.READ_PAGE, ok = false, output = "page is blank")
        assertThat(watch.refusal(AgentTools.CLICK)).isNotNull()
    }

    @Test
    fun `the quoted reason is the page's words, not the fence around them`() {
        val watch = AgentFailureWatch()
        val fenced = UntrustedContent.wrap("no element with ref 3", source = "the page")
        watch.record(AgentTools.CLICK, ok = false, output = fenced)
        watch.record(AgentTools.CLICK, ok = false, output = fenced)
        val refusal = watch.refusal(AgentTools.CLICK)
        assertThat(refusal).isNotNull()
        assertThat(refusal).doesNotContain(UntrustedContent.BEGIN)
        assertThat(refusal).contains("no element with ref 3")
    }

    @Test
    fun `reset starts a turn clean`() {
        val watch = AgentFailureWatch()
        watch.record(AgentTools.CLICK, ok = false, output = "boom")
        watch.record(AgentTools.CLICK, ok = false, output = "boom")
        watch.reset()
        assertThat(watch.refusal(AgentTools.CLICK)).isNull()
    }
}
