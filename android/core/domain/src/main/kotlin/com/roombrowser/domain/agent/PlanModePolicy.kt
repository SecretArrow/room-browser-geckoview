package com.roombrowser.domain.agent

/**
 * What a Plan-mode turn refuses: every tool that can put something INTO a
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
object PlanModePolicy {

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
        // An agent-initiated send is the wallet's own way of moving funds, with
        // no page involved — which is exactly what a plan promises not to do.
        // The mode can still answer "what would this cost": read the state and
        // the balances, write the transfer into the plan, and run it once the
        // user has switched to Ask or YOLO and confirmed the approval card.
        AgentTools.WALLET_SEND,
        AgentTools.WALLET_SWITCH_NETWORK,
        // A patch runs script in a live page, so it belongs with run_js and
        // every other tool that can change what the page does.
        AgentTools.DEVTOOLS_PATCH,
        // A download is not a submission, but it does WRITE a file into the
        // user's storage — work carried out, which is what this mode promises
        // not to do. Saving a page as a PDF is the same act on the same device.
        AgentTools.DOWNLOAD_FILE,
        AgentTools.SAVE_PDF,
        // A program with the network and this profile's files behind it: a mode
        // that promised nothing is carried out cannot leave that open.
        AgentTools.NODE_RUN,
        // Installing a package reaches the registry and writes to disk, which
        // is a change a plan-only turn has no business making.
        AgentTools.NODE_PACKAGES,
        // A skill install fetches a repository and writes its files, and a
        // removal deletes them — both are work carried out, which this mode
        // promises to propose instead of do. Reading an already-installed
        // skill (skills_list, skills_read) is a read like read_page and stays
        // legal here: a plan is exactly where the instructions matter.
        AgentTools.SKILLS_INSTALL,
        AgentTools.SKILLS_REMOVE
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
     *
     * A chat plugin's tools are refused here by PREFIX rather than by list:
     * they are named per chat (see [AgentTools.PLUGIN_TOOL_PREFIX]) and join
     * the catalogue at run time, so [REFUSED] stays the static catalogue's
     * own set — this is the one family where membership is by shape.
     */
    fun refusal(toolName: String, action: String?): String? {
        val refused = toolName in REFUSED ||
            toolName.startsWith(AgentTools.PLUGIN_TOOL_PREFIX) ||
            (action != null && action in REFUSED_ACTIONS[toolName].orEmpty())
        if (!refused) return null
        return "tool '$toolName' is switched off while this chat is in Plan mode: the turn " +
            "opens and reads pages and proposes the work instead of doing it — nothing is " +
            "clicked, typed, submitted, posted, signed or granted. Answer from read_page and " +
            "lay the steps out instead — and if this action is really wanted, tell the user to " +
            "switch the chat mode to Ask or YOLO in the agent panel."
    }
}
