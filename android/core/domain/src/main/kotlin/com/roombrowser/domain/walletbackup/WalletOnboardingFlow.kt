package com.roombrowser.domain.walletbackup

/** The create/import flow's own pages (the engine's lock state drives entry/exit). */
enum class WalletOnboardingStep {
    CHOICE, CREATE_INTRO, REVEAL, CONFIRM_QUIZ, IMPORT_FORM, RESTORE_BACKUP
}

/**
 * Where a Back press goes from [step], or null when the flow has no page
 * behind it and the press leaves the surface instead.
 *
 * CONFIRM_QUIZ -> REVEAL is the load-bearing one: "I wrote it down" moves
 * FORWARD to the quiz and must not pop the reveal page, so the phrase and the
 * backup options that only exist there are still behind Back.
 *
 * REVEAL has no page behind it. The wallet is already persisted by the time
 * it renders, so returning to the create form would only offer to create a
 * second one; the page is left instead (behind the same one-time "not backed
 * up yet" confirmation as "Go to Home").
 */
object WalletOnboardingFlow {

    fun backFrom(step: WalletOnboardingStep): WalletOnboardingStep? = when (step) {
        WalletOnboardingStep.CHOICE,
        WalletOnboardingStep.REVEAL -> null

        WalletOnboardingStep.CREATE_INTRO,
        WalletOnboardingStep.IMPORT_FORM,
        WalletOnboardingStep.RESTORE_BACKUP -> WalletOnboardingStep.CHOICE

        WalletOnboardingStep.CONFIRM_QUIZ -> WalletOnboardingStep.REVEAL
    }
}
