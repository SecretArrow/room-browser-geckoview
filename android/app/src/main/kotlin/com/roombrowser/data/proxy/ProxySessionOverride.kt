package com.roombrowser.data.proxy

import com.roombrowser.domain.proxy.ProxyCandidate

/**
 * A proxy chosen on the network-warning screen, for THIS session only.
 *
 * Process-level and never persisted: a choice made to get past one warning applies to the live
 * session and must not rewrite the profile's own proxy settings, so it can never quietly become
 * a saved policy the user did not ask for.
 */
object ProxySessionOverride {

    @Volatile
    private var byProfile: Map<String, ProxyCandidate> = emptyMap()

    fun set(profileId: String, candidate: ProxyCandidate?) {
        byProfile =
            if (candidate == null) byProfile - profileId else byProfile + (profileId to candidate)
    }

    fun get(profileId: String): ProxyCandidate? = byProfile[profileId]

    fun clear() {
        byProfile = emptyMap()
    }
}
