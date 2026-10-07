package com.roombrowser.domain.agent

/**
 * What a "Chat only" turn refuses: every tool that can put something INTO a
 * page or send it THROUGH one.
 *
 * The mode exists so a chat can look something up and show the answer without
 * anything reaching a site as a submission, so the rule is about what leaves
 * the turn — a name is refused when running it can click, type, submit, post
 * or sign, and a tool that both reads and changes is refused per action.
 * Reading and navigating are not refusals; they are the whole point of the
 * mode.
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

    /**
     * Actions refused inside a tool that also reads, so that reporting on the
     * current site stays available while changing it does not. Granting a site
     * the camera is exactly what a page has to talk a model into, and
     * `clear_site_data` is worse than its name suggests: the engine keeps ONE
     * data directory per profile, so it signs the user out everywhere.
     *
     * A name here is held to the catalogue, and an action to the tool's own
     * enum, by the tests beside this file.
     */
    private val REFUSED_ACTIONS: Map<String, Set<String>> = mapOf(
        // Types the code into the page's login form.
        AgentTools.APP_2FA to setOf("fill"),
        AgentTools.APP_SHIELDS to setOf("toggle", "clear_site_data"),
        AgentTools.APP_SITE_PERMISSION to setOf("set")
    )

    /**
     * The refusal the model reads back, or null when the tool may run. It has
     * to name the way out: a refusal that reads like a dead end sends the model
     * looking for another route to the same action.
     */
    fun refusal(toolName: String, action: String?): String? {
        val refused = toolName in REFUSED ||
            (action != null && action in REFUSED_ACTIONS[toolName].orEmpty())
        if (!refused) return null
        return "tool '$toolName' is switched off while this chat is in Chat only mode: the turn " +
            "opens and reads pages and changes nothing else — nothing is clicked, typed, " +
            "submitted, posted, signed or granted. Answer from read_page instead — and if this " +
            "action is really wanted, tell the user to turn Chat only off in the agent panel."
    }
}
