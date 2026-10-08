package com.roombrowser.browser

import java.util.concurrent.atomic.AtomicReference

/**
 * The `:browser` process's live [BrowserViewModel], for surfaces that are
 * their own Activity but are still the same browser.
 *
 * A second BrowserViewModel is not an option: its `initialize()` restores the
 * tabs again, starts a second agent and re-runs the download recovery, all
 * against the one engine this process is bound to. So an Activity that wants
 * to show the agent full-screen borrows the live one instead.
 */
object BrowserSession {

    private val live = AtomicReference<BrowserViewModel?>(null)

    fun publish(viewModel: BrowserViewModel) {
        live.set(viewModel)
    }

    fun clear(viewModel: BrowserViewModel) {
        live.compareAndSet(viewModel, null)
    }

    fun current(): BrowserViewModel? = live.get()
}
