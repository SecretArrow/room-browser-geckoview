package com.roombrowser.domain.walletbackup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the two navigation rules the wallet backup flow lives or dies by:
 * Back from the confirmation quiz returns to the SAME recovery-phrase page
 * ("I wrote it down" must not pop it), and the create/import forms return to
 * the choice rather than anywhere that would create a second wallet.
 */
class WalletOnboardingFlowTest {

    @Test
    fun `back from the confirmation quiz returns to the recovery phrase page`() {
        // The acceptance case: create -> reveal -> I wrote it down -> Back.
        // The reveal page must still be behind it (same mnemonic, same
        // export options), never Home.
        assertThat(WalletOnboardingFlow.backFrom(WalletOnboardingStep.CONFIRM_QUIZ))
            .isEqualTo(WalletOnboardingStep.REVEAL)
    }

    @Test
    fun `the create and import forms return to the choice`() {
        assertThat(WalletOnboardingFlow.backFrom(WalletOnboardingStep.CREATE_INTRO))
            .isEqualTo(WalletOnboardingStep.CHOICE)
        assertThat(WalletOnboardingFlow.backFrom(WalletOnboardingStep.IMPORT_FORM))
            .isEqualTo(WalletOnboardingStep.CHOICE)
        assertThat(WalletOnboardingFlow.backFrom(WalletOnboardingStep.RESTORE_BACKUP))
            .isEqualTo(WalletOnboardingStep.CHOICE)
    }

    @Test
    fun `the recovery page and the choice have nothing behind them`() {
        // The wallet is already persisted once REVEAL renders, so there is no
        // "create form" to go back to — null means the press leaves the flow.
        assertThat(WalletOnboardingFlow.backFrom(WalletOnboardingStep.REVEAL)).isNull()
        assertThat(WalletOnboardingFlow.backFrom(WalletOnboardingStep.CHOICE)).isNull()
    }

    @Test
    fun `every step has an answer, and no answer loops`() {
        WalletOnboardingStep.entries.forEach { step ->
            val back = WalletOnboardingFlow.backFrom(step)
            // A step whose Back returns to itself would be a navigation loop.
            assertThat(back).isNotEqualTo(step)
        }
    }
}
