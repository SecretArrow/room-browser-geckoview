package com.roombrowser.node

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The two properties the runtime's kill protocol stands on, pinned without a
 * socket — plus the one this gate was traded from a `ReentrantLock` to buy.
 *
 * The gate must hold ONE call at a time no matter how many arrive together
 * (two overlapped calls would each kill the runtime the other is talking
 * to), and a waiter whose coroutine is cancelled must leave the queue at
 * once — the whole point of the mutex: the old lock parked the waiter's
 * thread uninterruptibly, which was the frozen-turn bug behind this change.
 * The HOLDER's side is pinned too: cancelling the holder's coroutine cannot
 * cut a call in half, because the call's body has no suspension points in
 * it — it answers and opens the gate for the next caller either way.
 */
class RuntimeGateTest {

    @Test
    fun one_call_at_a_time_no_matter_how_many_arrive_behind_a_busy_one() = runBlocking {
        val gate = RuntimeGate()
        val holderAnswered = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val holder = launch(Dispatchers.Default) {
            gate.run(null) {
                inFlight.incrementAndGet()
                peak.accumulateAndGet(inFlight.get(), Math::max)
                holderAnswered.countDown()
                releaseHolder.await()
                inFlight.decrementAndGet()
                "the holder answered"
            }
        }
        assertThat(holderAnswered.await(5, TimeUnit.SECONDS)).isTrue()
        val waiters = 7
        val queued = CountDownLatch(waiters)
        val jobs = (1..waiters).map {
            launch(Dispatchers.Default) {
                gate.run({ queued.countDown() }) {
                    inFlight.incrementAndGet()
                    peak.accumulateAndGet(inFlight.get(), Math::max)
                    inFlight.decrementAndGet()
                    "a waiter answered"
                }
            }
        }
        // Every waiter has told us it found the gate held — only then does
        // the holder leave, so the seven really did overlap one busy gate.
        assertThat(queued.await(5, TimeUnit.SECONDS)).isTrue()
        releaseHolder.countDown()
        jobs.forEach { it.join() }
        holder.join()
        assertThat(peak.get()).isEqualTo(1)
    }

    @Test
    fun a_waiter_cancelled_while_it_waits_leaves_the_queue_without_running() = runBlocking {
        val gate = RuntimeGate()
        val holderAnswered = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)
        val holder = launch(Dispatchers.Default) {
            gate.run(null) {
                holderAnswered.countDown()
                releaseHolder.await()
                "the holder answered"
            }
        }
        assertThat(holderAnswered.await(5, TimeUnit.SECONDS)).isTrue()
        val waiterQueued = CountDownLatch(1)
        var ran = false
        val waiter = launch(Dispatchers.Default) {
            // The queued latch proves the waiter already failed its tryLock,
            // so from here the only way forward is the cancellable wait.
            gate.run({ waiterQueued.countDown() }) {
                ran = true
                "the waiter never ran"
            }
        }
        assertThat(waiterQueued.await(5, TimeUnit.SECONDS)).isTrue()
        // THE assertion of the whole change: cancelling the waiter's coroutine
        // ends the wait NOW. With the old uninterruptible lock this join could
        // not return until the holder left, and the holder never leaves until
        // we say so below — the timeout turns "regressed to a park" into a
        // failure instead of a hang.
        withTimeout(10_000) { waiter.cancelAndJoin() }
        releaseHolder.countDown()
        holder.join()
        assertThat(ran).isFalse()
        // The gate the waiter gave up is takeable again: a leaked lock would
        // wedge this second call forever, and the holder's own unlock is the
        // other half of that proof.
        assertThat(gate.run(null) { "the next call answered" }).isEqualTo("the next call answered")
    }

    @Test
    fun cancelling_the_holder_does_not_cut_the_call_in_half() = runBlocking {
        val gate = RuntimeGate()
        val bodyStarted = CountDownLatch(1)
        val releaseBody = CountDownLatch(1)
        var held: String? = null
        val holder = launch(Dispatchers.Default) {
            held = gate.run(null) {
                bodyStarted.countDown()
                releaseBody.await()
                "answered"
            }
        }
        assertThat(bodyStarted.await(5, TimeUnit.SECONDS)).isTrue()
        // Stop lands mid-body: there is nothing to suspend on in there, so
        // the call runs to its answer and the finally opens the gate — the
        // runtime-kill protocol's "one call at a time" must never depend on
        // the holder's coroutine staying alive.
        holder.cancel()
        releaseBody.countDown()
        holder.join()
        assertThat(held).isEqualTo("answered")
        assertThat(gate.run(null) { "the next call answered" }).isEqualTo("the next call answered")
    }

    @Test
    fun the_notice_fires_once_for_a_call_that_waits_and_cannot_fail_it() = runBlocking {
        val gate = RuntimeGate()
        // An uncontended call says nothing: there was nobody ahead of it.
        var notices = 0
        assertThat(gate.run({ notices++ }) { "first" }).isEqualTo("first")
        assertThat(notices).isEqualTo(0)

        val holderAnswered = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)
        val holder = launch(Dispatchers.Default) {
            gate.run(null) {
                holderAnswered.countDown()
                releaseHolder.await()
                "the holder answered"
            }
        }
        assertThat(holderAnswered.await(5, TimeUnit.SECONDS)).isTrue()
        // A call queued behind it is told once, before the wait.
        val queued = CountDownLatch(1)
        var answer: String? = null
        val waiter = launch(Dispatchers.Default) {
            answer = gate.run({ notices++; queued.countDown() }) { "the waiter answered" }
        }
        assertThat(queued.await(5, TimeUnit.SECONDS)).isTrue()
        releaseHolder.countDown()
        waiter.join()
        assertThat(answer).isEqualTo("the waiter answered")
        holder.join()
        assertThat(notices).isEqualTo(1)

        // And the notice is advisory: a hook that throws cannot fail the call
        // it was announcing — the call still waits, runs and answers.
        val holderAgainAnswered = CountDownLatch(1)
        val releaseAgain = CountDownLatch(1)
        val holderAgain = launch(Dispatchers.Default) {
            gate.run(null) {
                holderAgainAnswered.countDown()
                releaseAgain.await()
                "the second holder answered"
            }
        }
        assertThat(holderAgainAnswered.await(5, TimeUnit.SECONDS)).isTrue()
        val stubborn = CountDownLatch(1)
        var answerAnyway: String? = null
        val toldAnyway = launch(Dispatchers.Default) {
            answerAnyway = gate.run({ stubborn.countDown(); error("the status line blew up") }) {
                "answered anyway"
            }
        }
        assertThat(stubborn.await(5, TimeUnit.SECONDS)).isTrue()
        releaseAgain.countDown()
        toldAnyway.join()
        assertThat(answerAnyway).isEqualTo("answered anyway")
        holderAgain.join()
        assertThat(notices).isEqualTo(1)
    }
}
