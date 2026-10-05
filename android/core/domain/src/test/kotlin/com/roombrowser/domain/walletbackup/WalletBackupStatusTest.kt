package com.roombrowser.domain.walletbackup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The backup status is the fact the whole flow branches on: "Go to Home" only
 * asks once when it is [WalletBackupStatus.NOT_BACKED_UP], and the delete flow
 * only softens its first warning then. The distinction that must not blur is
 * that WRITTEN_DOWN is a STATEMENT by the user while EXPORTED is an event this
 * app witnessed by writing the file.
 */
class WalletBackupStatusTest {

    @Test
    fun `only not-backed-up counts as not backed up`() {
        assertThat(WalletBackupStatus.NOT_BACKED_UP.isBackedUp).isFalse()
        assertThat(WalletBackupStatus.WRITTEN_DOWN.isBackedUp).isTrue()
        assertThat(WalletBackupStatus.EXPORTED.isBackedUp).isTrue()
    }

    @Test
    fun `the states are exactly the three the flow can reach, in order`() {
        // The order matters to the UI: a later state must never be overwritten
        // by an earlier one (an export must not be downgraded by a re-render
        // that only remembers "wrote it down").
        assertThat(WalletBackupStatus.entries.toList()).containsExactly(
            WalletBackupStatus.NOT_BACKED_UP,
            WalletBackupStatus.WRITTEN_DOWN,
            WalletBackupStatus.EXPORTED
        ).inOrder()
    }
}
