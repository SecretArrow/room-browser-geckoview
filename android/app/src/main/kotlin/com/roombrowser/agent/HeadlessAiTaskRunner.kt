package com.roombrowser.agent

import android.content.Context
import com.roombrowser.browser.engine.ProfileEngine
import com.roombrowser.data.db.AgentProviderEntity
import com.roombrowser.data.db.AiTaskEntity
import com.roombrowser.data.net.AppHttpClients
import com.roombrowser.data.repo.permissions
import com.roombrowser.data.repo.runConfig
import com.roombrowser.di.AppGraph
import com.roombrowser.domain.agent.AgentConfig
import com.roombrowser.domain.agent.AgentEvent
import com.roombrowser.domain.agent.AgentGateway
import com.roombrowser.domain.agent.AgentLoop
import com.roombrowser.domain.agent.AgentPrompts
import com.roombrowser.domain.agent.AutoModelPicker
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.model.SearchEngines
import com.roombrowser.domain.task.AiTaskExecutionMode
import com.roombrowser.domain.task.needsVisibleBrowser
import com.roombrowser.engine.EngineSession
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Runs a deferred task for real: a page on the task's own profile — hidden by
 * default, a tab of the running browser when the task asks for one — the agent
 * loop, and the answer stored back on the row.
 *
 * IT ONLY WORKS IN ':browser', and that is a fact about the engine rather than
 * a preference. This app creates ONE engine runtime per process, bound to one
 * profile and never retargeted, and every process shares the same on-disk
 * profile. A second runtime over those files — the default process running a
 * task while ':browser' has the browser open — is the one thing that must not
 * happen, so the run belongs in the process that owns the runtime and the
 * answer to "cannot run here" is to leave the occurrence queued rather than to
 * start a second engine.
 *
 * The runner never BINDS the process: it runs on the profile the process is
 * already bound to, or it waits. Binding here would be a race the browser must
 * lose — [ProfileEngine.bindProcessToProfile] happens in BrowserActivity's
 * `onCreate`, which is strictly after this process's Application, so a task
 * that grabbed the binding first would leave the browser restarting the
 * process to claim the profile it was opened with, over and over.
 *
 * A run that cannot go ahead says exactly why, and the row keeps the reason:
 * the profile the user is browsing is not the task's, the agent is switched
 * off, no provider is configured. None of those are failures of the task, and
 * recording them as failures would make the list lie about what happened.
 */
class HeadlessAiTaskRunner(
    private val context: Context,
    private val graph: AppGraph
) : AiTaskRunner {

    override suspend fun run(task: AiTaskEntity): AiTaskRunOutcome {
        val profile = graph.profileRepo.getProfile(ProfileId(task.profileId))
            ?: return AiTaskRunOutcome.Deferred(
                "Deferred: this task's profile no longer exists."
            )

        val bound = ProfileEngine.boundProfile()
        if (bound == null) {
            return AiTaskRunOutcome.Deferred(
                "Deferred: the browser is not open, so its engine is not running. This runs the " +
                    "next time you open Room Browser."
            )
        }
        if (bound.value != task.profileId) {
            return AiTaskRunOutcome.Deferred(
                "Deferred: the browser is in use on another profile. This runs the next time " +
                    "${profile.name} is the profile in use."
            )
        }

        val settings = graph.appState.agentSettingsSnapshot()
        if (!settings.enabled) {
            return AiTaskRunOutcome.Deferred("Deferred: the AI agent is switched off in settings.")
        }
        val runConfig = task.runConfig
        val providers = graph.agentRepo.providers()
        val provider = runConfig.providerId?.let { id -> providers.firstOrNull { it.id == id } }
            ?: settings.defaultProviderId?.let { id -> providers.firstOrNull { it.id == id } }
            ?: providers.firstOrNull()
            ?: return AiTaskRunOutcome.Deferred("Deferred: no AI provider is configured.")
        val apiKey = provider.apiKeyEnc.takeIf { it.isNotBlank() }
            ?.let { KeyStoreCrypto.decrypt(it) }
            .orEmpty()
        val gateway = AgentGateways.forProvider(
            callFactory = AppHttpClients.agent(),
            provider = provider,
            apiKey = apiKey,
            appContext = context
        )
        val model = runConfig.model?.takeIf { it.isNotBlank() }
            ?: resolveModel(provider, gateway, settings.defaultModel)
            ?: return AiTaskRunOutcome.Deferred(
                "Deferred: nothing on ${provider.name} answered, so there is no model to run this on."
            )

        val engineLabel = SearchEngines.byId(profile.settings.searchEngineId).label
        val prompt = settings.systemPromptOverride?.takeIf { it.isNotBlank() }
            ?: AgentPrompts.render(System.currentTimeMillis(), ZoneId.systemDefault(), engineLabel)

        return withContext(Dispatchers.Main) {
            // A task saved as visible must not quietly become invisible: with
            // no browser on screen there is no tab to open, and the run waits
            // rather than falling back to a hidden page.
            val page = if (runConfig.executionMode.needsVisibleBrowser) {
                val host = AiTaskPageHosts.current()
                    ?: return@withContext AiTaskRunOutcome.Deferred(
                        "Deferred: this task runs in a visible browser, and none is on screen. " +
                            "This runs the next time Room Browser is open on ${profile.name}."
                    )
                host.openPage(
                    AiTaskPageRequest(
                        profileId = task.profileId,
                        permissions = task.permissions,
                        confirmActions = settings.confirmActions,
                        allowProfileTools = settings.taskProfileTools,
                        keepTab = runConfig.executionMode == AiTaskExecutionMode.STANDARD
                    )
                ) ?: return@withContext AiTaskRunOutcome.Deferred(
                    "Deferred: the browser could not open a tab for this task."
                )
            } else {
                null
            }

            var owned: EngineSession? = null
            try {
                val executor = page?.executor ?: run {
                    val session = createHeadlessSession(task, profile)
                    if (session == null) {
                        return@withContext AiTaskRunOutcome.Deferred(
                            "Deferred: the browser engine could not start a session."
                        )
                    }
                    owned = session
                    HeadlessToolExecutor(
                        session = session,
                        searchEngineId = profile.settings.searchEngineId,
                        permissions = task.permissions,
                        confirmActions = settings.confirmActions,
                        profileData = GraphAgentProfileData(
                            graph = graph,
                            profileId = ProfileId(task.profileId),
                            context = context
                        ),
                        allowProfileTools = settings.taskProfileTools,
                        otpDigitsAllowed = {
                            graph.appState.agentSettingsSnapshot().otpDigitsToAgent
                        }
                    )
                }
                val config = AgentConfig(
                    model = model,
                    maxSteps = settings.maxSteps,
                    temperature = settings.temperature,
                    systemPrompt = prompt
                )
                val history = buildTurnHistory(
                    prompt = prompt,
                    priorTurns = emptyList(),
                    pageSnapshot = null,
                    settings = settings,
                    attachments = emptyList(),
                    request = task.prompt
                )

                var answer: String? = null
                var failure: String? = null
                AgentLoop(gateway, executor, config).runTurn(history) { event ->
                    when (event) {
                        is AgentEvent.FinalAnswer -> answer = event.text
                        is AgentEvent.AgentError -> failure = event.message
                        else -> Unit
                    }
                }
                val text = answer?.takeIf { it.isNotBlank() }
                when {
                    text != null -> AiTaskRunOutcome.Completed(text)
                    failure != null -> AiTaskRunOutcome.Failed(failure!!)
                    else -> AiTaskRunOutcome.Failed("the agent stopped without an answer")
                }
            } catch (t: Throwable) {
                AiTaskRunOutcome.Failed(t.message ?: t.javaClass.simpleName)
            } finally {
                if (page != null) {
                    runCatching { page.close() }
                } else {
                    runCatching { owned?.close() }
                }
            }
        }
    }

    /**
     * The model an AUTO task runs on: the configured default if it answers,
     * otherwise the first of this provider's own models that does.
     *
     * This is a probe and not a lookup on purpose — a name in a /models
     * response is an offer, and an offer the account cannot call fails at the
     * first turn of a run nobody is watching. Null means nothing answered, and
     * the caller records that as a deferral: an unreachable model is not a
     * failure of the task.
     */
    private suspend fun resolveModel(
        provider: AgentProviderEntity,
        gateway: AgentGateway,
        preferred: String?
    ): String? = AutoModelPicker.firstWorkingOn(
        gateway,
        preferred?.takeIf { it.isNotBlank() } ?: provider.defaultModel.takeIf { it.isNotBlank() }
    )

    /**
     * A session of this task's own, on the profile this process is bound to.
     *
     * IT IS NEVER ATTACHED to a view, so nothing here can appear on the user's
     * screen or disturb the tab they are looking at, and it shares the
     * process's own profile context, because that is what makes a signed-in
     * page a signed-in page.
     *
     * HONEST LIMIT: a detached session still loads and runs JavaScript, but no
     * frame is ever drawn from it, so a page that only fills itself in from
     * `requestAnimationFrame` or IntersectionObserver may stay empty.
     */
    private fun createHeadlessSession(
        task: AiTaskEntity,
        profile: com.roombrowser.domain.model.Profile
    ): EngineSession? = runCatching {
        ProfileEngine.createSession(
            context = context,
            profile = profile,
            sessionId = "ai-task-${task.id}-${System.currentTimeMillis()}",
            isPrivate = false
        )
    }.getOrNull()
}
