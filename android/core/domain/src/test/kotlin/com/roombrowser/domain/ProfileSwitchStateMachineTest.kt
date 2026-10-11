package com.roombrowser.domain.profile

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.profile.ProfileSwitchStateMachine.Event
import com.roombrowser.domain.profile.ProfileSwitchStateMachine.State
import com.roombrowser.domain.profile.ProfileSwitchStateMachine.Step
import org.junit.Test

class ProfileSwitchStateMachineTest {

    private val a = ProfileId("profile-a")
    private val b = ProfileId("profile-b")

    @Test
    fun `full happy path follows spec order`() {
        val sm = ProfileSwitchStateMachine()
        sm.onEvent(Event.Begin(a, b))
        Step.entries.forEach { _ ->
            assertThat(sm.snapshot().state).isEqualTo(State.SWITCHING)
            sm.onEvent(Event.StepCompleted)
        }
        val snap = sm.snapshot()
        assertThat(snap.state).isEqualTo(State.COMPLETE)
        assertThat(snap.completedSteps).containsExactly(
            Step.STOP_NAVIGATION,
            Step.SAVE_TAB_STATE,
            Step.DESTROY_BROWSER_CONTEXT,
            Step.FLUSH_PROFILE_STATE,
            Step.RELEASE_PROFILE_RESOURCES,
            Step.LOAD_NEW_PROFILE_CONTEXT,
            Step.RESTORE_NEW_PROFILE_TABS
        ).inOrder()
    }

    @Test
    fun `cannot begin while switching`() {
        val sm = ProfileSwitchStateMachine()
        sm.onEvent(Event.Begin(a, b))
        var thrown = false
        try {
            sm.onEvent(Event.Begin(a, b))
        } catch (e: IllegalStateException) {
            thrown = true
        }
        assertThat(thrown).isTrue()
    }

    @Test
    fun `cannot switch to same profile`() {
        val sm = ProfileSwitchStateMachine()
        var thrown = false
        try {
            sm.onEvent(Event.Begin(a, a))
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertThat(thrown).isTrue()
    }

    @Test
    fun `error fails the switch`() {
        val sm = ProfileSwitchStateMachine()
        sm.onEvent(Event.Begin(a, b))
        sm.onEvent(Event.Error(Step.DESTROY_BROWSER_CONTEXT, "webview destroy failed"))
        val snap = sm.snapshot()
        assertThat(snap.state).isEqualTo(State.FAILED)
        assertThat(snap.error).contains("webview destroy failed")
    }

    @Test
    fun `involves tracks from and to`() {
        val sm = ProfileSwitchStateMachine()
        sm.onEvent(Event.Begin(a, b))
        assertThat(sm.involves(a)).isTrue()
        assertThat(sm.involves(b)).isTrue()
        assertThat(sm.involves(ProfileId("c"))).isFalse()
    }

    @Test
    fun `abort returns to idle`() {
        val sm = ProfileSwitchStateMachine()
        sm.onEvent(Event.Begin(a, b))
        sm.onEvent(Event.StepCompleted)
        sm.onEvent(Event.Abort)
        assertThat(sm.snapshot().state).isEqualTo(State.IDLE)
    }

    @Test
    fun `reset clears everything`() {
        val sm = ProfileSwitchStateMachine()
        sm.onEvent(Event.Begin(a, b))
        sm.onEvent(Event.StepCompleted)
        sm.reset()
        assertThat(sm.snapshot()).isEqualTo(
            ProfileSwitchStateMachine.Snapshot(State.IDLE, null, emptyList(), null, null, null)
        )
    }
}
