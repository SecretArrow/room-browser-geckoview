package com.roombrowser.agent

import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.domain.agent.ToolExecutor
import com.roombrowser.domain.agent.ToolResult

/**
 * The tools a HEADLESS chat turn has: the page side on the hidden page, and the
 * app's own tools unchanged, because those act on the browser the user is
 * looking at rather than on any page.
 *
 * [HeadlessToolExecutor] refuses the wallet tools and words the refusal for a
 * scheduled run ("run this as a chat") — true there, wrong here, where the turn
 * already is a chat with a person watching. This gate answers those names first
 * with the reason that actually applies, and hands everything else to the page.
 *
 * The app's tools are the exception in the other direction: the ones that act
 * on the browser itself still work, but the ones that act on "the open page"
 * do not — see [PAGE_BOUND_APP_TOOLS].
 */
class HeadlessChatToolGate(
    private val page: ToolExecutor,
    /**
     * The app's own tools, answering null for a name that is not one of theirs —
     * the contract [AgentAppTools.execute] already has. Null here means this
     * turn has no app tools at all, and every app name then falls through.
     */
    private val appTools: (suspend (name: String, argsJson: String) -> ToolResult?)? = null
) : ToolExecutor {

    override suspend fun execute(name: String, argsJson: String): ToolResult {
        if (name in PAGE_BOUND_APP_TOOLS) {
            return ToolResult(
                false,
                "tool '$name' acts on the page the browser has open, and this turn's page is " +
                    "hidden. Use read_page or scroll on it instead, or switch this chat to the " +
                    "browser tab and ask again."
            )
        }
        appTools?.invoke(name, argsJson)?.let { return it }
        if (name in AgentTools.WALLET_TOOLS) {
            return ToolResult(
                false,
                "tool '$name' needs a page you can see: a wallet request signs or broadcasts " +
                    "what that page asked for, and this turn is running on a hidden page. " +
                    "Switch this chat to the browser tab and ask again."
            )
        }
        if (name in HeadlessToolExecutor.TAB_TOOLS) {
            return ToolResult(
                false,
                "tool '$name' works on tabs, and a headless turn has one hidden page and no " +
                    "tabs. Use navigate and read_page to move around it, or switch this chat " +
                    "to the browser tab."
            )
        }
        return page.execute(name, argsJson)
    }

    private companion object {
        /**
         * The app tools that act on "the open page" rather than on the browser
         * itself. Their implementation is a browser-side path into the tab on
         * screen (find in page, reader mode, desktop mode), so answering one
         * here would change a page the agent cannot see and let it report the
         * result as its own.
         */
        val PAGE_BOUND_APP_TOOLS = setOf(AgentTools.APP_PAGE)
    }
}
