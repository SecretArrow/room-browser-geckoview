package com.roombrowser.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The keep-alive notification's dedupe and pacing rules, pinned on the JVM.
 *
 * The service itself cannot be instantiated in a unit test, which is exactly
 * why these decisions were extracted into [AgentNotificationPacer]: the rules
 * are the behaviour, and the behaviour is what needed pinning. The failure
 * they prevent came back in two shapes — a notification rebuilt per streamed
 * token for text it already showed, and (the inverse, had the fix been a bare
 * throttle) a rate limit that swallows the LAST status of a turn and leaves
 * the shade lying about what the agent is doing. Every test here ends on a
 * VOID-returning Truth call so JUnit4 keeps the whole class runnable.
 *
 * Time is a mutable fake: window arithmetic with real sleeps is flaky on a
 * loaded CI runner and proves nothing the boundary tests don't.
 */
class AgentNotificationPacerTest {

    /** Mutable fake time; the tests move it by hand. */
    private var now = 0L

    private fun pacer(minIntervalMs: Long = 500L): AgentNotificationPacer =
        AgentNotificationPacer(minIntervalMs) { now }

    @Test
    fun `identical status text never asks for a post`() {
        val p = pacer()

        assertThat(p.status("Writing…")).isEqualTo(AgentNotificationPacer.Verdict.PostNow)
        assertThat(p.status("Writing…")).isEqualTo(AgentNotificationPacer.Verdict.NothingToDo)
        assertThat(p.status("Writing…")).isEqualTo(AgentNotificationPacer.Verdict.NothingToDo)
    }

    @Test
    fun `a real change right after a post is deferred to the window's end`() {
        val p = pacer()
        p.status("Writing…") // posts at t=0

        now = 100L
        assertThat(p.status("Thinking…"))
            .isEqualTo(AgentNotificationPacer.Verdict.PostLater(delayMs = 400L))
    }

    @Test
    fun `the deferred delay shrinks as the window ages`() {
        val p = pacer()
        p.status("Writing…") // posts at t=0

        now = 400L
        assertThat(p.status("Thinking…"))
            .isEqualTo(AgentNotificationPacer.Verdict.PostLater(delayMs = 100L))
    }

    @Test
    fun `changes behind a deferred post need no second post — the pending one carries them`() {
        val p = pacer()
        p.status("Writing…") // posts at t=0

        now = 100L
        assertThat(p.status("Thinking…"))
            .isInstanceOf(AgentNotificationPacer.Verdict.PostLater::class.java)
        now = 150L
        // A real change (not identical text) that still needs no new post:
        // the outstanding deferred post builds from CURRENT state, so it will
        // show this, at the window's end. Nothing may schedule a second one.
        assertThat(p.status("Writing…")).isEqualTo(AgentNotificationPacer.Verdict.NothingToDo)

        now = 500L // the deferred post fires and builds from current state
        p.posted()
        assertThat(p.showingText()).isEqualTo("Writing…")
        // ...and after the post, identical text is dropped as usual.
        assertThat(p.status("Writing…")).isEqualTo(AgentNotificationPacer.Verdict.NothingToDo)
    }

    @Test
    fun `posted() restarts the window from the real post`() {
        val p = pacer()
        p.status("Writing…") // t=0
        now = 100L
        p.status("Thinking…") // deferred to t=500

        now = 500L
        p.posted() // the deferred post happened — the window restarts from HERE
        now = 520L
        // Inside the new window: deferred again (never dropped — the previous
        // deferral's post was made, so this change becomes the trailing edge).
        assertThat(p.status("Step done"))
            .isEqualTo(AgentNotificationPacer.Verdict.PostLater(delayMs = 480L))
        now = 1200L
        // Outside every window: posts at once, and the deferral it supersedes
        // must not linger — a stale `pending` would swallow the next change.
        assertThat(p.status("Done")).isEqualTo(AgentNotificationPacer.Verdict.PostNow)
        now = 1230L
        assertThat(p.status("Really done"))
            .isEqualTo(AgentNotificationPacer.Verdict.PostLater(delayMs = 470L))
    }

    @Test
    fun `pause flips are deduped like status text`() {
        val p = pacer()

        assertThat(p.paused(true)).isEqualTo(AgentNotificationPacer.Verdict.PostNow)
        assertThat(p.paused(true)).isEqualTo(AgentNotificationPacer.Verdict.NothingToDo)
        assertThat(p.isPaused()).isTrue()

        now = 700L
        assertThat(p.paused(false)).isEqualTo(AgentNotificationPacer.Verdict.PostNow)
        assertThat(p.isPaused()).isFalse()
    }

    @Test
    fun `a pause flip inside the window defers only the post, not the state`() {
        val p = pacer()
        p.status("Writing…") // posts at t=0

        now = 200L
        assertThat(p.paused(true))
            .isEqualTo(AgentNotificationPacer.Verdict.PostLater(delayMs = 300L))
        // The state the NEXT build will read has already flipped — the
        // deferral delays the post, never the truth it posts.
        assertThat(p.isPaused()).isTrue()
    }

    @Test
    fun `reset() seeds what a direct startForeground post shows`() {
        val p = pacer()
        p.status("Writing…") // t=0
        now = 100L
        p.status("Thinking…") // deferred — then the service promotes directly

        p.reset("Working…") // goForeground posted "Working…" itself
        assertThat(p.showingText()).isEqualTo("Working…")
        assertThat(p.status("Working…")).isEqualTo(AgentNotificationPacer.Verdict.NothingToDo)

        now = 110L
        // No pacing window is open after a direct post, so the first real
        // change posts at once instead of waiting one out.
        assertThat(p.status("Thinking…")).isEqualTo(AgentNotificationPacer.Verdict.PostNow)
    }

    @Test
    fun `the pause label survives reset because a direct post still shows it`() {
        val p = pacer()
        p.paused(true)

        p.reset("Working…")

        assertThat(p.isPaused()).isTrue()
    }

    @Test
    fun `a thousand distinct statuses a millisecond apart make at most three posts`() {
        val p = pacer()
        var posts = 0
        var fireAt: Long? = null

        for (i in 0 until 1000) {
            now = i.toLong()
            if (fireAt != null && now >= fireAt!!) {
                p.posted() // the deferred post fired on the handler
                posts++
                fireAt = null
            }
            val verdict = p.status("status $i")
            when (verdict) {
                AgentNotificationPacer.Verdict.PostNow -> posts++
                AgentNotificationPacer.Verdict.NothingToDo -> {}
                is AgentNotificationPacer.Verdict.PostLater -> fireAt = now + verdict.delayMs
            }
        }
        // A trailing post still outstanding at the end WILL fire — count it.
        val total = posts + (if (fireAt != null) 1 else 0)

        // This is the cap doing its job with NOTHING to dedupe: a thousand
        // genuinely different statuses collapse to the first post plus one
        // trailing-edge post per 500 ms window. The last true state still
        // lands — it is never the victim of the cap.
        assertThat(total).isAtMost(3)
    }
}
