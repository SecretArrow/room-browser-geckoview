package com.roombrowser.domain.walletbackup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The delete decision, which exists to make two outcomes unreachable: a
 * wallet deleted without a confirmation, and a wallet deleted straight off
 * the "never backed up" warning.
 */
class WalletDeleteFlowTest {

    @Test
    fun `a wallet never backed up warns first and does not delete`() {
        val warned = WalletDeleteState.idle(backedUp = false).requested()

        assertThat(warned.stage).isEqualTo(WalletDeleteStage.BACKUP_WARNING)
        assertThat(warned.deleteConfirmed).isFalse()
    }

    @Test
    fun `delete anyway on the warning still only reaches the confirmation`() {
        val confirmed = WalletDeleteState.idle(backedUp = false).requested().acceptedRisk()

        assertThat(confirmed.stage).isEqualTo(WalletDeleteStage.CONFIRMATION)
        // Reaching the confirmation IS the delete gate; nothing has deleted yet.
        assertThat(confirmed.deleteConfirmed).isTrue()
    }

    @Test
    fun `a backed-up wallet still gets a confirmation`() {
        val asked = WalletDeleteState.idle(backedUp = true).requested()

        assertThat(asked.stage).isEqualTo(WalletDeleteStage.CONFIRMATION)
        assertThat(asked.deleteConfirmed).isTrue()
    }

    @Test
    fun `the idle and warning pages can never delete`() {
        assertThat(WalletDeleteState.idle(backedUp = true).deleteConfirmed).isFalse()
        assertThat(WalletDeleteState.idle(backedUp = false).deleteConfirmed).isFalse()
        assertThat(WalletDeleteState.idle(backedUp = false).requested().deleteConfirmed).isFalse()
    }

    @Test
    fun `cancel backs out of either page`() {
        assertThat(WalletDeleteState.idle(backedUp = false).requested().cancelled().stage)
            .isEqualTo(WalletDeleteStage.IDLE)
        assertThat(
            WalletDeleteState.idle(backedUp = false).requested().acceptedRisk().cancelled().stage
        ).isEqualTo(WalletDeleteStage.IDLE)
    }

    @Test
    fun `delete anyway is inert when there is no warning on screen`() {
        // A stray caller must not be able to jump from idle to the delete gate.
        assertThat(WalletDeleteState.idle(backedUp = false).acceptedRisk().stage)
            .isEqualTo(WalletDeleteStage.IDLE)
        assertThat(
            WalletDeleteState.idle(backedUp = true).requested().acceptedRisk().stage
        ).isEqualTo(WalletDeleteStage.CONFIRMATION)
    }

    @Test
    fun `a cancelled flow can be reopened`() {
        val reopened = WalletDeleteState.idle(backedUp = true)
            .requested()
            .cancelled()
            .requested()

        assertThat(reopened.stage).isEqualTo(WalletDeleteStage.CONFIRMATION)
    }
}
