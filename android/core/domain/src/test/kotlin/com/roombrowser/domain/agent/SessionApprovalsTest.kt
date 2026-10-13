package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SessionApprovalsTest {

    @Test
    fun `an allowance holds for that tool on that origin`() {
        val approvals = SessionApprovals()
        approvals.remember("https://shop.example", AgentTools.FILL_INPUT)
        assertThat(approvals.allows("https://shop.example", AgentTools.FILL_INPUT)).isTrue()
        // Another tool on the same site is a different question.
        assertThat(approvals.allows("https://shop.example", AgentTools.CLICK)).isFalse()
        // And the same tool on another site is a different site.
        assertThat(approvals.allows("https://bank.example", AgentTools.FILL_INPUT)).isFalse()
    }

    @Test
    fun `a page with no origin is never remembered`() {
        val approvals = SessionApprovals()
        approvals.remember(null, AgentTools.CLICK)
        approvals.remember("", AgentTools.CLICK)
        assertThat(approvals.origins).isEmpty()
        assertThat(approvals.allows(null, AgentTools.CLICK)).isFalse()
    }

    @Test
    fun `a typed authenticator code keeps asking`() {
        val approvals = SessionApprovals()
        assertThat(approvals.canRemember(AgentTools.APP_2FA)).isFalse()
        approvals.remember("https://shop.example", AgentTools.APP_2FA)
        assertThat(approvals.allows("https://shop.example", AgentTools.APP_2FA)).isFalse()
    }

    @Test
    fun `the wallet never rides the session memory`() {
        val approvals = SessionApprovals()
        for (tool in AgentTools.WALLET_TOOLS) {
            assertThat(approvals.canRemember(tool)).isFalse()
            approvals.remember("https://dapp.example", tool)
            assertThat(approvals.allows("https://dapp.example", tool)).isFalse()
        }
    }

    @Test
    fun `a new session starts from the same questions as the first one`() {
        val approvals = SessionApprovals()
        approvals.remember("https://shop.example", AgentTools.CLICK)
        assertThat(approvals.toolsFor("https://shop.example")).containsExactly(AgentTools.CLICK)
        approvals.clear()
        assertThat(approvals.allows("https://shop.example", AgentTools.CLICK)).isFalse()
        assertThat(approvals.origins).isEmpty()
    }
}
