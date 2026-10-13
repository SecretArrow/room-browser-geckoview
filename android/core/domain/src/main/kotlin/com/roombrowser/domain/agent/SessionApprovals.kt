package com.roombrowser.domain.agent

/**
 * The approvals that hold for the rest of ONE chat session, on ONE site.
 *
 * "Ask before every action" is the right default, but a task that fills a
 * five-field form asks five times for the same answer about the same page, and
 * the third prompt is where a person starts tapping Allow without reading it.
 * This remembers the answer to exactly that question — this tool, on this
 * origin, until the session ends — and nothing wider.
 *
 * What it can never cover, by construction rather than by care: an action that
 * cannot be undone, and a wallet request. Both are asked through their own
 * prompts and never consult this. A tool can also be barred outright with
 * [canRemember], which is how a code typed into a login field stays a question.
 *
 * The origin comes from the URL the ENGINE reports, never from page text, so a
 * page cannot choose the bucket its approvals are filed under. A page with no
 * origin — `about:`, `data:`, a blank tab — can never be remembered, because
 * "this site" would then be a promise about nothing.
 */
class SessionApprovals {

    private val byOrigin = HashMap<String, MutableSet<String>>()

    /** Whether [tool] may be remembered at all, on any site. */
    fun canRemember(tool: String): Boolean = tool !in NEVER_REMEMBERED

    /** Records the user's "allow on this site" answer. A no-op without an origin. */
    fun remember(origin: String?, tool: String) {
        if (origin.isNullOrBlank() || !canRemember(tool)) return
        byOrigin.getOrPut(origin) { mutableSetOf() }.add(tool)
    }

    /** Whether the user already allowed [tool] on [origin] this session. */
    fun allows(origin: String?, tool: String): Boolean {
        if (origin.isNullOrBlank() || !canRemember(tool)) return false
        return tool in byOrigin[origin].orEmpty()
    }

    /** What is remembered for one origin, for the settings screen and tests. */
    fun toolsFor(origin: String?): Set<String> =
        origin?.let { byOrigin[it]?.toSet() }.orEmpty()

    val origins: Set<String> get() = byOrigin.keys.toSet()

    /** A new chat session starts from the same questions as the first one did. */
    fun clear() = byOrigin.clear()

    companion object {
        /**
         * Tools that keep asking however the user answered. `app_2fa` types a
         * live code into a login form, which is the one action where the prompt
         * IS the point; the wallet names are here as a second lock, since their
         * own prompt never routes through the session memory anyway.
         */
        val NEVER_REMEMBERED: Set<String> = setOf(
            AgentTools.APP_2FA,
            AgentTools.WALLET_APPROVE,
            AgentTools.WALLET_SWITCH_NETWORK
        )
    }
}
