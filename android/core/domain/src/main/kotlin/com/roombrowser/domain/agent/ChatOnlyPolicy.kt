package com.roombrowser.domain.agent

/**
 * What a "Chat only" turn refuses: every tool that can put something INTO a
 * page or send it THROUGH one.
 *
 * The mode exists so a chat can look something up and show the answer without
 * anything reaching a site as a submission, so the rule is about what leaves
 * the turn — a name is refused when running it can click, type, submit, post
 * or sign. Reading and navigating are not refusals; they are the whole point
 * of the mode.
 *
 * This is a deny list rather than an allow list because the read side is the
 * open-ended half, where a tool added later should simply work. Every name
 * here is held to the catalogue by its test.
 */
object ChatOnlyPolicy {

    /**
     * [AgentTools.RUN_JS] is here although it is deliberately absent from
     * [AgentTools.INTERACTIVE_TOOLS]: it can do anything those names can, and
     * a mode that promised "nothing is submitted" while leaving script
     * execution open would be promising nothing.
     */
    val REFUSED: Set<String> = setOf(
        AgentTools.CLICK,
        AgentTools.FILL_INPUT,
        AgentTools.PRESS_ENTER,
        AgentTools.SELECT_OPTION,
        AgentTools.PRESS_KEYS,
        AgentTools.RUN_JS,
        AgentTools.AUTO_LIKE,
        AgentTools.AUTO_REPOST,
        AgentTools.AUTO_REPLY,
        AgentTools.AUTO_POST,
        AgentTools.WALLET_APPROVE,
        AgentTools.WALLET_SWITCH_NETWORK
    )

    /** `app_2fa action=fill` types the code into the page's login form. */
    private const val FILL = "fill"

    /**
     * The refusal the model reads back, or null when the tool may run. It has
     * to name the way out: a refusal that reads like a dead end sends the model
     * looking for another route to the same action.
     */
    fun refusal(toolName: String, action: String?): String? {
        val refused = toolName in REFUSED ||
            (toolName == AgentTools.APP_2FA && action == FILL)
        if (!refused) return null
        return "tool '$toolName' is switched off while this chat is in Chat only mode: the turn " +
            "opens and reads pages, but nothing is clicked, typed, submitted, posted or signed. " +
            "Answer from read_page instead — and if this action is really wanted, tell the user " +
            "to turn Chat only off in the agent panel."
    }
}
