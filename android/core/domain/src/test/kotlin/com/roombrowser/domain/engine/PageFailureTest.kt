package com.roombrowser.domain.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The one call the engine's error code cannot make: whether the DEVICE is at
 * fault or the site is.
 *
 * It matters because the two pages say different things to the user, and the
 * wrong one sends them to fix a network that is working.
 */
class PageFailureTest {

    @Test
    fun `an offline device is offline whatever the engine reported`() {
        assertThat(PageFailure.of(hostLookup = false, deviceOnline = false))
            .isEqualTo(PageFailure.OFFLINE)
        assertThat(PageFailure.of(hostLookup = true, deviceOnline = false))
            .isEqualTo(PageFailure.OFFLINE)
    }

    @Test
    fun `a name that did not resolve is a dns failure while online`() {
        assertThat(PageFailure.of(hostLookup = true, deviceOnline = true))
            .isEqualTo(PageFailure.DNS)
    }

    @Test
    fun `an unanswered connection while online is unreachable, not offline`() {
        // The reported defect: a working connection plus one silent server
        // used to read as "No Internet".
        assertThat(PageFailure.of(hostLookup = false, deviceOnline = true))
            .isEqualTo(PageFailure.UNREACHABLE)
    }
}
