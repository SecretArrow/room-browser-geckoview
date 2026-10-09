package com.roombrowser.domain.proxy

/**
 * Chooses which candidates one sweep may probe.
 *
 * The bundled catalogue holds thousands of endpoints and a sweep must never be a
 * burst of thousands of connections, so a sweep is a bounded SLICE. The slice
 * rotates: a later sweep starts where the previous one stopped, so the whole
 * catalogue is covered over several sweeps without any single one being oversized.
 */
object ProxySweepPlan {

    /** Candidates one sweep may probe. */
    const val MAX_PROBES: Int = 400

    /** A sweep stops early once this many candidates have verified: the list is good enough. */
    const val ENOUGH_VERIFIED: Int = 8

    /**
     * The slice starting at [startIndex], wrapping at the end. The returned index is where
     * the NEXT sweep should start, so the caller only has to persist one number.
     */
    fun slice(
        candidates: List<ProxyCandidate>,
        startIndex: Int = 0,
        max: Int = MAX_PROBES
    ): List<ProxyCandidate> {
        if (candidates.isEmpty() || max <= 0) return emptyList()
        val from = Math.floorMod(startIndex, candidates.size)
        val size = minOf(max, candidates.size)
        return (0 until size).map { candidates[(from + it) % candidates.size] }
    }

    fun nextIndex(candidates: List<ProxyCandidate>, startIndex: Int, probed: Int): Int =
        if (candidates.isEmpty()) 0 else Math.floorMod(startIndex, candidates.size) + probed
}
