package com.roombrowser.domain.task

import kotlinx.serialization.Serializable

/**
 * Where a scheduled run's page lives. HEADLESS is the only mode that needs no
 * visible browser: the other two drive a real tab of the running browser.
 */
@Serializable
enum class AiTaskExecutionMode { HEADLESS, HEADED, STANDARD }

/** True for the modes that need a browser on screen — the reason the runner
 *  defers a visible task instead of quietly running it hidden. */
val AiTaskExecutionMode.needsVisibleBrowser: Boolean
    get() = this != AiTaskExecutionMode.HEADLESS

/**
 * The provider, model and surface one task runs on.
 *
 * A null [providerId] or [model] means AUTO — use whatever answers. Rows written
 * before this config existed decode to [DEFAULT] (AUTO on the agent's default
 * provider, headless), which is how every existing task already behaves, so the
 * upgrade changes nobody's schedule.
 */
@Serializable
data class AiTaskRunConfig(
    val providerId: Long? = null,
    val model: String? = null,
    val executionMode: AiTaskExecutionMode = AiTaskExecutionMode.HEADLESS
) {
    companion object {
        val DEFAULT = AiTaskRunConfig()
    }
}
