package com.roombrowser.domain.net

import com.roombrowser.domain.model.ProfileSettings
import com.roombrowser.domain.model.UaMode
import com.roombrowser.domain.model.UserAgents

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
}
