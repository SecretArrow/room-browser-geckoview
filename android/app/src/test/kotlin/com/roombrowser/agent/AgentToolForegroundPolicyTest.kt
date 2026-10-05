package com.roombrowser.agent

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.agent.AgentTools
import org.junit.Test

/**
 * Pins WHICH tools may run while the chat's tab is in the background.
 *
 * This is the one decision of the per-tab binding whose wrong answer is not
 * visible on screen. A navigation-capable tool let loose on a background
 * engine can wedge that tab permanently (see [MOVES_THE_PAGE]); a tool held
 * back by mistake makes the agent stall for a reason nobody can see. Both are
 * one Set entry away, and neither is reachable from a JVM test that drives
 * the executor itself — so the classification is a value a test can hold
 * against the tool list, and this is that test.
 *
 * Three sets, not two, because "needs the tab on screen" has two unrelated
 * reasons: [MOVES_THE_PAGE] must not start a load on a detached engine, and
 * [NEEDS_SCREEN_TAB] acts on the page the USER is looking at rather than on
 * this chat's own engine. They are disjoint, and together with
 * [staysInBackground] they must cover the catalogue exactly.
 *
 * Every test here ends on a VOID-returning Truth call on purpose: a Kotlin
 * function whose last expression has a value compiles to a non-void method,
 * and JUnit4 then refuses the whole class instead of running the rest of it.
 * `containsExactly…` and `containsAtLeast…` return `Ordered`, so they are
 * never the last line.
 */
class AgentToolForegroundPolicyTest {

    /**
     * The tools that need no tab on screen: they read or scroll this chat's
     * own engine, or they act on the app rather than on a page, so they keep
     * working while the user is elsewhere.
     *
     * Written out rather than derived from the list so that a tool added to
     * [AgentTools.toolDefs] and to none of the three sides fails the partition
     * test below. The choice has to be made on purpose, and this file is where
     * it is recorded.
     */
    private val staysInBackground = setOf(
        AgentTools.READ_PAGE,
        AgentTools.SCROLL,
        AgentTools.OPEN_NEW_TAB,
        AgentTools.LIST_TABS,
        AgentTools.SWITCH_TAB,
        AgentTools.CLOSE_TAB,
        AgentTools.WAIT,
        AgentTools.RUN_JS,
        AgentTools.WAIT_FOR,
        AgentTools.APP_OPEN,
        AgentTools.APP_TABS,
        AgentTools.APP_DATA,
        AgentTools.APP_SETTINGS,
        // Wallet tools talk to the wallet engine, never to a page, so they
        // start no navigation and are safe on a background tab.
        AgentTools.WALLET_STATE,
        AgentTools.WALLET_REQUESTS,
        AgentTools.WALLET_APPROVE,
        AgentTools.WALLET_REJECT,
        AgentTools.WALLET_SWITCH_NETWORK
    )

    @Test
    fun `a click or a submit needs its tab on screen`() {
        // A click can follow a link and a submit can navigate, so both are a
        // page load wearing another name — and a page load started on an
        // engine with no parent is the documented wedge.
        assertThat(MOVES_THE_PAGE).containsAtLeast(
            AgentTools.NAVIGATE,
            AgentTools.SEARCH_WEB,
            AgentTools.CLICK,
            AgentTools.FILL_INPUT,
            AgentTools.PRESS_ENTER,
            AgentTools.GO_BACK,
            AgentTools.SELECT_OPTION,
            AgentTools.PRESS_KEYS,
            AgentTools.AUTO_LIKE,
            AgentTools.AUTO_REPOST,
            AgentTools.AUTO_REPLY,
            AgentTools.AUTO_POST
        )
        assertThat(staysInBackground).containsNoneOf(AgentTools.CLICK, AgentTools.FILL_INPUT)
    }

    @Test
    fun `reading and scrolling keep working while the user is elsewhere`() {
        // These are what make a background turn possible at all: the user
        // switching tabs must not stop the agent reading the page it was
        // asked about.
        assertThat(staysInBackground).containsAtLeast(
            AgentTools.READ_PAGE,
            AgentTools.SCROLL,
            AgentTools.WAIT
        )
        assertThat(MOVES_THE_PAGE).containsNoneOf(
            AgentTools.READ_PAGE,
            AgentTools.SCROLL,
            AgentTools.WAIT
        )
    }

    @Test
    fun `the page-shaped app tools wait for their tab to be on screen`() {
        // The find bar, reader mode, shields and site permissions describe the
        // VISIBLE tab — there is one of each in the whole app. Running them
        // while the user reads another page would answer about that page.
        assertThat(NEEDS_SCREEN_TAB).containsExactly(
            AgentTools.APP_PAGE,
            AgentTools.APP_SHIELDS,
            AgentTools.APP_SITE_PERMISSION
        )
        assertThat(staysInBackground).containsNoneIn(NEEDS_SCREEN_TAB)
        assertThat(MOVES_THE_PAGE).containsNoneIn(NEEDS_SCREEN_TAB)
    }

    @Test
    fun `every tool the model can call is on exactly one side`() {
        val everyTool = AgentTools.toolDefs().map { it.function.name }
        val classified = MOVES_THE_PAGE + NEEDS_SCREEN_TAB + staysInBackground

        // Together the three sides are exactly the tools the model can call:
        // nothing left unclassified, and nothing claimed by two of them.
        assertThat(classified).containsExactlyElementsIn(everyTool)
        assertThat(staysInBackground.intersect(MOVES_THE_PAGE)).isEmpty()
        assertThat(staysInBackground.intersect(NEEDS_SCREEN_TAB)).isEmpty()
        assertThat(MOVES_THE_PAGE.intersect(NEEDS_SCREEN_TAB)).isEmpty()
    }
}
