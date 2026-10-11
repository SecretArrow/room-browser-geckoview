package com.roombrowser.domain.agent

/** What the page is asking a human to do, if anything. */
enum class ChallengeKind {
    CLOUDFLARE_TURNSTILE,
    RECAPTCHA,
    HCAPTCHA,
    GENERIC_INTERSTITIAL,
    NONE
}

/** A classification plus the one piece of evidence that produced it. */
data class ChallengeHit(val kind: ChallengeKind, val evidence: String = "") {
    val detected: Boolean get() = kind != ChallengeKind.NONE

    companion object {
        val NONE = ChallengeHit(ChallengeKind.NONE)
    }
}

/**
 * Classifies the evidence a challenge page leaves behind.
 *
 * The agent NEVER solves one of these, so this decides exactly one thing: that
 * the turn stops and a person is asked. That makes a false positive cheap — it
 * costs the user one tap — which is why the markers below are allowed to be
 * generous, and why no attempt is made to tell a real widget from a fake one.
 *
 * Specific vendors are checked before the generic wording, because a Turnstile
 * interstitial also says "Verify you are human" and the user should be told
 * which one they are looking at.
 */
object ChallengeDetector {

    private val TURNSTILE = listOf(
        "challenges.cloudflare.com",
        "cf-turnstile",
        "turnstile",
        "cf_chl_opt",
        "cf_chl_",
        "just a moment"
    )

    private val RECAPTCHA = listOf(
        "www.google.com/recaptcha",
        "gstatic.com/recaptcha",
        "g-recaptcha",
        "grecaptcha",
        "recaptcha"
    )

    private val HCAPTCHA = listOf(
        "newassets.hcaptcha.com",
        "h-captcha",
        "hcaptcha"
    )

    private val GENERIC = listOf(
        "verify you are human",
        "verify you are a human",
        "confirm you are human",
        "are you a robot",
        "unusual traffic",
        "checking your browser",
        "enable javascript and cookies to continue",
        "complete the security check",
        "attention required!",
        "ddos protection by",
        "press and hold"
    )

    /**
     * [pageText], [console] and [network] are all attacker-controlled and all
     * equally worth reading: a challenge can show no visible text and exist only
     * as a script URL.
     */
    fun detect(
        pageText: String? = null,
        console: List<String> = emptyList(),
        network: List<String> = emptyList(),
        url: String? = null,
        title: String? = null
    ): ChallengeHit {
        val haystack = buildString {
            pageText?.let { append(it).append('\n') }
            title?.let { append(it).append('\n') }
            url?.let { append(it).append('\n') }
            console.forEach { append(it).append('\n') }
            network.forEach { append(it).append('\n') }
        }.lowercase()
        if (haystack.isBlank()) return ChallengeHit.NONE

        return firstMatch(haystack, ChallengeKind.CLOUDFLARE_TURNSTILE, TURNSTILE)
            ?: firstMatch(haystack, ChallengeKind.RECAPTCHA, RECAPTCHA)
            ?: firstMatch(haystack, ChallengeKind.HCAPTCHA, HCAPTCHA)
            ?: firstMatch(haystack, ChallengeKind.GENERIC_INTERSTITIAL, GENERIC)
            ?: ChallengeHit.NONE
    }

    private fun firstMatch(haystack: String, kind: ChallengeKind, markers: List<String>): ChallengeHit? =
        markers.firstOrNull { haystack.contains(it) }?.let { ChallengeHit(kind, it) }

    /** The sentence the UI shows next to the state. */
    fun describe(kind: ChallengeKind): String = when (kind) {
        ChallengeKind.CLOUDFLARE_TURNSTILE -> "a Cloudflare Turnstile check"
        ChallengeKind.RECAPTCHA -> "a reCAPTCHA check"
        ChallengeKind.HCAPTCHA -> "an hCaptcha check"
        ChallengeKind.GENERIC_INTERSTITIAL -> "a human-verification check"
        ChallengeKind.NONE -> "no check"
    }
}
