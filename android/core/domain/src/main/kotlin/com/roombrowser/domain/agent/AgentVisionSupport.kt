package com.roombrowser.domain.agent

/**
 * Whether a provider protocol can be handed a page screenshot at all, with the
 * honest one-line reason for the ones that cannot. Pure and provider-agnostic:
 * a caller checks this BEFORE building a request, so an image never silently
 * disappears into a gateway that has no field for it.
 *
 * The protocol strings mirror `AgentProviderEntity` in `:app` (that module
 * depends on this one, not the reverse), so the values must stay in step.
 */
object AgentVisionSupport {

    const val PROTOCOL_OPENAI = "OPENAI"
    const val PROTOCOL_ANTHROPIC = "ANTHROPIC"
    const val PROTOCOL_OLLAMA = "OLLAMA"
    const val PROTOCOL_OPENCODE = "OPENCODE"
    const val PROTOCOL_LOCAL = "LOCAL"

    /**
     * null when [protocol] can carry images; otherwise the reason it cannot.
     * An unrecognised protocol falls through to the [OI]-compatible default
     * (what [AgentGateways] routes it to), which can.
     */
    fun unsupportedReason(protocol: String): String? = when (protocol) {
        PROTOCOL_OPENCODE -> "the opencode server API takes text only"
        PROTOCOL_LOCAL -> "the on-device engine takes text prompts only"
        else -> null
    }

    fun supports(protocol: String): Boolean = unsupportedReason(protocol) == null
}
