package com.roombrowser.browser

import com.roombrowser.agent.AgentPermissionGate
import com.roombrowser.agent.AgentProfileData
import com.roombrowser.agent.AgentToolExecutor
import com.roombrowser.agent.AiTaskPage
import com.roombrowser.agent.AiTaskPageHost
import com.roombrowser.agent.AiTaskPageRequest
import com.roombrowser.agent.ScheduledRunToolGate
import com.roombrowser.domain.agent.ActionVerdict
import com.roombrowser.domain.agent.AgentVisionSupport
import com.roombrowser.domain.agent.SiteMemoryStore
import com.roombrowser.domain.agent.ToolExecutor
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
     * The browser operations a task's executor may name. Passed in rather than
     * built here so the same one surface serves every route the agent has.
     */
    private val control: BrowserControlApi,
    /**
     * The profile's own notes and authenticator accounts, so this run's
     * executor can offer `app_2fa`/`app_notes` when the task is allowed them.
     */
    private val profileData: AgentProfileData,
    /** Whether the digits of a generated code may be returned to the model. */
    private val otpDigitsAllowed: suspend () -> Boolean = { false },
    /**
     * The agent's per-site memory for this run's profile, and the per-profile
     * toggle that gates it. Passed in rather than read here because the
     * ViewModel does not expose the graph these come from.
     */
    private val siteMemory: SiteMemoryStore? = null,
    private val siteMemoryEnabled: suspend () -> Boolean = { false }
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

            override val executor: ToolExecutor = AgentPermissionGate(
                inner = ScheduledRunToolGate(
                    delegate = AgentToolExecutor(
                        vm = vm,
                        control = control,
                        tabId = tabId,
                        // Nobody is watching a scheduled run, so a gate is a refusal
                        // with the reason, never a prompt that waits for an answer
                        // that will not come.
                        confirmGate = { name, _ -> ActionVerdict.Deny(unattendedRefusal(name)) },
                        walletConfirm = { false },
                        destructiveGate = { name, _ -> ActionVerdict.Deny(unattendedRefusal(name)) },
                        profileData = profileData,
                        otpDigitsAllowed = otpDigitsAllowed,
                        // The "console execution requires approval" setting is an
                        // APPROVAL requirement, and a scheduled run has nobody to
                        // give one; its run_js is instead governed by the task's
                        // own "clicking and typing" permission, which the user
                        // grants per task. So the setting is not applied here.
                        runJsApprovalRequired = { false },
                        visionUnsupportedReason = {
                            request.providerProtocol?.let { AgentVisionSupport.unsupportedReason(it) }
                        },
                        siteMemory = siteMemory,
                        siteMemoryEnabled = siteMemoryEnabled
                    ),
                    permissions = request.permissions,
                    confirmActions = request.confirmActions,
                    allowProfileTools = request.allowProfileTools
                ),
                // Read live from the profile, never the turn's snapshot: the
                // user can withdraw a permission while the run is going.
                withheld = { vm.profileSettings().agentWithheldPermissions }
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
