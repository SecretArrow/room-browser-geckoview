package com.roombrowser.browser

import com.roombrowser.agent.AgentProfileData
import com.roombrowser.agent.AgentToolExecutor
import com.roombrowser.agent.AiTaskPage
import com.roombrowser.agent.AiTaskPageHost
import com.roombrowser.agent.AiTaskPageRequest
import com.roombrowser.agent.ScheduledRunToolGate
import com.roombrowser.domain.agent.ActionVerdict
import com.roombrowser.domain.task.unattendedRefusal

/**
 * [AiTaskPageHost] for this edition: a task's page is a real tab of the browser
 * this ViewModel drives, so a page that only fills itself in from animation
 * frames or lazy loading really does render — and the person can watch it work.
 *
 * The tab is opened for the run and pinned against LRU eviction, because an
 * evicted background tab loses the page the rest of the run is reading.
 *
 * A visible run drives the tab through [AgentToolExecutor], the executor built
 * for a live tab — the headless one installs its own client, which on a tab the
 * browser owns would take the content blocker and the page-state wiring away
 * from it. [ScheduledRunToolGate] is what keeps that from widening the task's
 * grants.
 */
class BrowserTaskPageHost(
    private val vm: BrowserViewModel,
    /**
     * The profile's own notes and authenticator accounts, so this run's
     * executor can offer `app_2fa`/`app_notes` when the task is allowed them.
     */
    private val profileData: AgentProfileData,
    /** Whether the digits of a generated code may be returned to the model. */
    private val otpDigitsAllowed: suspend () -> Boolean = { false }
) : AiTaskPageHost {

    override suspend fun openPage(request: AiTaskPageRequest): AiTaskPage? {
        // The run already checked that this process is bound to the task's
        // profile; this is the same check against the tab strip's own idea of
        // it, because a mismatch here would put a task's page in someone
        // else's session.
        if (vm.profileId.value != request.profileId) return null

        vm.openNewTab(START_URL)
        val tabId = vm.activeTabId ?: return null
        vm.pinTabForAgent(tabId)
        if (vm.tabSession(tabId) == null) {
            vm.pinTabForAgent(null)
            if (!request.keepTab) vm.closeTab(tabId)
            return null
        }
        return object : AiTaskPage {

            override val executor = ScheduledRunToolGate(
                delegate = AgentToolExecutor(
                    vm = vm,
                    tabId = tabId,
                    // Nobody is watching a scheduled run, so a gate is a refusal
                    // with the reason, never a prompt that waits for an answer
                    // that will not come.
                    confirmGate = { name, _ -> ActionVerdict.Deny(unattendedRefusal(name)) },
                    walletConfirm = { false },
                    destructiveGate = { name, _ -> ActionVerdict.Deny(unattendedRefusal(name)) },
                    profileData = profileData,
                    otpDigitsAllowed = otpDigitsAllowed
                ),
                permissions = request.permissions,
                confirmActions = request.confirmActions,
                allowProfileTools = request.allowProfileTools
            )

            override fun close() {
                vm.pinTabForAgent(null)
                if (!request.keepTab) vm.closeTab(tabId)
            }
        }
    }

    private companion object {
        /** A real URL, unlike `about:home`, which is the start page and gets no
         *  engine of its own — and a page with no engine is no use to a run. */
        const val START_URL = "about:blank"
    }
}
