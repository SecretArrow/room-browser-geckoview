package com.roombrowser.domain.proxy

import kotlinx.serialization.Serializable

/**
 * What the last sweep learned, as it is persisted. Kept whole rather than as three keys so a
 * reader can never see a direct IP from one sweep beside health rows from another — the whole
 * verdict is only meaningful if both halves came from the same observation.
 */
@Serializable
data class ProxySweepState(
    /** The address this device reaches the network from, with no proxy. */
    val directIp: String? = null,
    /** Where the next bounded sweep resumes in the catalogue. */
    val cursor: Int = 0,
    val checkedAtMs: Long = 0L,
    val health: List<ProxyHealth> = emptyList()
) {
    fun byId(): Map<String, ProxyHealth> = health.associateBy { it.id }
}
