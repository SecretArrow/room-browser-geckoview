package com.roombrowser.agent

import com.roombrowser.domain.agent.ToolExecutor
import com.roombrowser.domain.task.AiTaskPermissions
import java.util.concurrent.atomic.AtomicReference

/**
 * A page a scheduled run drives that the RUN does not own: a real tab of the
 * browser, opened on screen so the page is really drawn.
 *
 * The difference from a headless page is honest and narrow. An unattached
 * WebView loads and runs JavaScript but no frame is ever drawn from it, so a
 * page that fills itself in from animation frames, lazy images or an
 * IntersectionObserver can stay empty. A visible tab does not have that limit.
 */
interface AiTaskPage {

    /** The tools the run drives this page with — the scheduled run's own, under
     *  the task's saved grants, never the chat's. */
    val executor: ToolExecutor

    /** Ends this run's use of the page. [AiTaskPageRequest.keepTab] decides
     *  whether the tab itself outlives the run. */
    fun close()
}

data class AiTaskPageRequest(
    val profileId: String,
    val permissions: AiTaskPermissions,
    val confirmActions: Boolean,
    /** STANDARD leaves the tab open when the run ends, so the person can carry
     *  on from where it stopped; HEADED closes it. */
    val keepTab: Boolean
)

/**
 * The browser UI's half of a HEADED or STANDARD run.
 *
 * Null means "not now, and not by pretending": only a live browser can answer,
 * so the run waits for one instead of falling back to a hidden page — a task
 * saved as visible must not quietly become invisible.
 */
fun interface AiTaskPageHost {
    suspend fun openPage(request: AiTaskPageRequest): AiTaskPage?
}

/** The one live host. Registered by the browser activity, in this same process. */
object AiTaskPageHosts {

    private val live = AtomicReference<AiTaskPageHost?>(null)

    fun register(host: AiTaskPageHost) {
        live.set(host)
    }

    fun unregister(host: AiTaskPageHost) {
        live.compareAndSet(host, null)
    }

    fun current(): AiTaskPageHost? = live.get()
}
