package com.roombrowser.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The stream coalescer's contract, pinned on the JVM.
 *
 * Two failures this file guards against, both invisible while everything
 * works. A dropped or reordered delta is the worse one: the e2e suite reads
 * final transcript text, and a coalescer that loses the last window's tail
 * would only fail on device, seconds into a real stream — so every test here
 * ends on the exact concatenation of what went in. The quiet failure is a
 * coalescer that stops coalescing: nothing breaks, the per-token copy of the
 * whole entries list just quietly returns. The window tests assert on flush
 * COUNTS for exactly that reason.
 *
 * Time is a mutable fake rather than sleeps: a real-clock test would be
 * flaky on a loaded CI runner and would still prove nothing about the window
 * arithmetic. Every test drives the clock by hand to the boundary it cares
 * about, so a boundary bug fails deterministically.
 */
class AgentStreamCoalescerTest {

    /** Mutable fake time; the tests move it by hand. */
    private var now = 0L

    private fun coalescer(
        intervalMs: Long = 80L,
        onFlush: (text: String, thinking: String) -> Unit
    ): AgentStreamCoalescer = AgentStreamCoalescer(intervalMs, { now }, onFlush)

    @Test
    fun `the final text is the exact concatenation of every delta in order`() {
        val deltas = (0 until 200).map { "word$it " }
        val flushed = mutableListOf<Pair<String, String>>()
        val c = coalescer(intervalMs = 80L) { text, thinking -> flushed.add(text to thinking) }

        // 7 ms per delta: several deltas share each window, the way a
        // 30-80/s stream does — this is the shape the coalescer exists for.
        deltas.forEach { delta ->
            now += 7L
            c.appendText(delta)
        }
        c.flush()

        assertThat(flushed.joinToString("") { it.first }).isEqualTo(deltas.joinToString(""))
        // The perf property itself: 200 deltas became a handful of writes,
        // not 200. If this ever fails, coalescing quietly stopped.
        assertThat(flushed.size).isAtMost(deltas.size / 4)
        assertThat(flushed.all { it.second.isEmpty() }).isTrue()
    }

    @Test
    fun `interleaved text and thinking land with both fields exact`() {
        val flushed = mutableListOf<Pair<String, String>>()
        val c = coalescer(intervalMs = 1000L) { text, thinking -> flushed.add(text to thinking) }

        c.appendText("T1") // the first delta flushes at once
        c.appendThinking("K1") // buffered — the window is open
        c.appendText("T2")
        c.appendThinking("K2")
        c.flush() // boundary: drains both builders in ONE call

        assertThat(flushed).containsExactly("T1" to "", "T2" to "K1K2").inOrder()
    }

    @Test
    fun `the first delta flushes at once so the bubble appears with it`() {
        val flushed = mutableListOf<Pair<String, String>>()
        val c = coalescer { text, thinking -> flushed.add(text to thinking) }

        c.appendText("hello")

        assertThat(flushed).containsExactly("hello" to "").inOrder()
    }

    @Test
    fun `the first thinking delta also flushes at once`() {
        val flushed = mutableListOf<Pair<String, String>>()
        val c = coalescer { text, thinking -> flushed.add(text to thinking) }

        c.appendThinking("weighing it up")

        assertThat(flushed).containsExactly("" to "weighing it up").inOrder()
    }

    @Test
    fun `deltas inside one window become a single later flush`() {
        val flushed = mutableListOf<Pair<String, String>>()
        val c = coalescer(intervalMs = 80L) { text, thinking -> flushed.add(text to thinking) }

        c.appendText("a") // t=0: immediate flush
        for (i in 0 until 50) {
            now = 1L + i // all inside the 80 ms window
            c.appendText("x$i")
        }
        assertThat(flushed).hasSize(1)

        now = 500L // long past the window
        c.appendText("b")
        assertThat(flushed).hasSize(2)
        // The exact input, end to end: the "a" the first window flushed, the
        // fifty deltas the open window swallowed, and the "b" that opened the
        // next one — the no-drop/no-reorder contract across a window cycle.
        assertThat(flushed.joinToString("") { it.first })
            .isEqualTo("a" + (0 until 50).joinToString("") { "x$it" } + "b")
    }

    @Test
    fun `a boundary flush drains immediately regardless of the clock`() {
        val flushed = mutableListOf<Pair<String, String>>()
        val c = coalescer(intervalMs = 1000L) { text, thinking -> flushed.add(text to thinking) }

        c.appendText("first") // immediate
        c.appendText("tail") // buffered — the window is wide open
        c.flush() // a tool is starting / the answer is final: drain NOW

        assertThat(flushed).containsExactly("first" to "", "tail" to "").inOrder()
    }

    @Test
    fun `a flush with nothing pending calls the sink not at all`() {
        var calls = 0
        val c = coalescer { _, _ -> calls++ }

        c.flush()
        c.flush()

        assertThat(calls).isEqualTo(0)
    }

    @Test
    fun `an empty delta does not buy a flush of its own`() {
        var calls = 0
        val flushed = mutableListOf<String>()
        val c = coalescer { text, _ -> calls++; flushed.add(text) }

        c.appendText("") // nothing to drain — must not reach the sink
        assertThat(calls).isEqualTo(0)

        c.appendText("a") // still inside the window opened above
        c.flush()
        assertThat(flushed).containsExactly("a").inOrder()
    }

    @Test
    fun `a stop-shaped ending still drains the tail the window was holding`() {
        val flushed = mutableListOf<Pair<String, String>>()
        val c = coalescer(intervalMs = 80L) { text, thinking -> flushed.add(text to thinking) }

        c.appendText("partial answer ") // t=0: visible at once
        now = 10L
        c.appendText("tail") // inside the window — invisible so far
        // The turn is cancelled HERE: no further delta will ever arrive, so
        // the finally's boundary flush is the only thing that can carry the
        // tail. This is the Stop correctness story in miniature.
        c.flush()

        assertThat(flushed.map { it.first }.joinToString(""))
            .isEqualTo("partial answer tail")
    }
}
