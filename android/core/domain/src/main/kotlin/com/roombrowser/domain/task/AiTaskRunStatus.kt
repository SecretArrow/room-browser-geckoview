package com.roombrowser.domain.task

/**
 * What one scheduled-task delivery ended in, as stored in
 * `ai_tasks.last_run_status`.
 *
 * [DEFERRED] is not a failure: it means the occurrence was accounted for but
 * no agent stack was available to run it, and the reason is recorded. It is a
 * distinct state so the list can say so plainly rather than showing a run
 * that never happened as if it had.
 */
enum class AiTaskRunStatus {
    COMPLETED,
    DEFERRED,
    FAILED;

    companion object {
        /** null for "" (never run) or a value this build does not know. */
        fun fromStored(value: String?): AiTaskRunStatus? =
            entries.firstOrNull { it.name == value }
    }
}
