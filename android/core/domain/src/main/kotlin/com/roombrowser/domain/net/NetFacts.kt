package com.roombrowser.domain.net

import com.roombrowser.domain.model.ProfileSettings
import com.roombrowser.domain.model.UaMode
import com.roombrowser.domain.model.UserAgents
import com.roombrowser.domain.proxy.ProxyMode
import com.roombrowser.domain.proxy.ProxyScheme
import com.roombrowser.domain.proxy.ProxyScope

/** Where the identity a site is told comes from, as the diagnostics screen labels it. */
enum class UaSource { DEVICE, PRESET, CUSTOM, ENGINE_DEFAULT }

/**
 * The label half of "what does the internet see", kept pure so it is unit-tested
 * in the fast job rather than read off a screen.
 *
 * Every answer here has to agree with what the engine actually sends. [uaSource]
 * therefore resolves through [UserAgents.effectiveUserAgent] rather than
 * re-deciding: a second opinion that disagrees with the sender is worse than no
 * label, because it is a screen that lies about the one thing it exists to report.
 */
object NetFacts {

    /**
     * Which control supplies the UA for [settings].
     *
     * The unknown-preset and blank-custom cases are the ones worth pinning: the
     * engine falls back to its own identity there, so a label that claimed
     * "preset" would name an identity nothing sends.
     */
    fun uaSource(settings: ProfileSettings): UaSource {
        UserAgents.device(settings)?.let { return UaSource.DEVICE }
        return when (settings.uaMode) {
            UaMode.DEFAULT -> UaSource.ENGINE_DEFAULT
            UaMode.PRESET ->
                if (settings.uaPresetId?.let { UserAgents.byId(it)?.value?.isNotEmpty() } == true) {
                    UaSource.PRESET
                } else UaSource.ENGINE_DEFAULT
            UaMode.CUSTOM ->
                if (settings.customUserAgent?.isNotBlank() == true) UaSource.CUSTOM
                else UaSource.ENGINE_DEFAULT
        }
    }

    /**
     * The engine's own identity has no stored label — the engine names itself
     * elsewhere on the screen — so this says only that the engine supplies it.
     */
    fun uaSourceLabel(source: UaSource): String = when (source) {
        UaSource.DEVICE -> "Device profile"
        UaSource.PRESET -> "User-agent preset"
        UaSource.CUSTOM -> "Custom string"
        UaSource.ENGINE_DEFAULT -> "Engine default"
    }

    fun scopeLabel(scope: ProxyScope): String = when (scope) {
        ProxyScope.PAGES -> "pages"
        ProxyScope.AGENT -> "AI agent"
        ProxyScope.WALLET -> "wallet"
    }

    /** "pages, wallet" — the scopes a profile routes, in a stable order. */
    fun scopeList(scopes: Set<ProxyScope>): String =
        ProxyScope.entries.filter { it in scopes }.joinToString(", ") { scopeLabel(it) }

    /**
     * What the profile's proxy setting means for page traffic, in one line.
     *
     * [finderEnabled] is the app-wide master switch, which outranks the profile:
     * a profile that opted in still goes direct while the finder is off, and the
     * line says so instead of describing a proxy that is not there.
     */
    fun proxySummary(
        finderEnabled: Boolean,
        mode: ProxyMode,
        host: String?,
        port: Int,
        scheme: ProxyScheme,
        scopes: Set<ProxyScope>
    ): String {
        if (!finderEnabled) return "Direct - the free proxy finder is off"
        if (mode == ProxyMode.OFF) return "Direct - this profile opts out"
        val routed = if (scopes.isEmpty()) "no traffic" else "for ${scopeList(scopes)}"
        if (mode == ProxyMode.MANUAL) {
            val pinned = host?.trim().orEmpty()
            if (pinned.isEmpty() || port !in 1..65535) {
                return "Manual, but no endpoint is saved - nothing is routed"
            }
            return "${scheme.id}://$pinned:$port, $routed"
        }
        return "Chosen from the verified list at every open, $routed"
    }
}
