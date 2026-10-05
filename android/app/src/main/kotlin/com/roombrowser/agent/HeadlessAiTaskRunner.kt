package com.roombrowser.agent

import android.content.Context
import com.roombrowser.browser.engine.ProfileEngine
import com.roombrowser.data.db.AiTaskEntity
import com.roombrowser.data.repo.permissions
import com.roombrowser.di.AppGraph
import com.roombrowser.domain.agent.AgentConfig
import com.roombrowser.domain.agent.AgentEvent
import com.roombrowser.domain.agent.AgentLoop
import com.roombrowser.domain.agent.AgentPrompts
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.model.SearchEngines
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Runs a deferred task for real: one headless session on the task's own
 * profile, the agent loop, and the answer stored back on the row.
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
        val providers = graph.agentRepo.providers()
        val provider = settings.defaultProviderId?.let { id -> providers.firstOrNull { it.id == id } }
            ?: providers.firstOrNull()
            ?: return AiTaskRunOutcome.Deferred("Deferred: no AI provider is configured.")
        val model = settings.defaultModel?.takeIf { it.isNotBlank() }
            ?: provider.defaultModel.takeIf { it.isNotBlank() }
            ?: return AiTaskRunOutcome.Deferred(
                "Deferred: no model is chosen for ${provider.name}."
            )
        val apiKey = provider.apiKeyEnc.takeIf { it.isNotBlank() }
            ?.let { KeyStoreCrypto.decrypt(it) }
            .orEmpty()

        val engineLabel = SearchEngines.byId(profile.settings.searchEngineId).label
        val prompt = settings.systemPromptOverride?.takeIf { it.isNotBlank() }
            ?: AgentPrompts.render(System.currentTimeMillis(), ZoneId.systemDefault(), engineLabel)

        return withContext(Dispatchers.Main) {
            val session = runCatching {
                ProfileEngine.createSession(
                    context = context,
                    profile = profile,
                    sessionId = "ai-task-${task.id}-${System.currentTimeMillis()}",
                    isPrivate = false
                )
            }.getOrNull() ?: return@withContext AiTaskRunOutcome.Deferred(
                "Deferred: the browser engine could not start a session."
            )

            try {
                val executor = HeadlessToolExecutor(
                    session = session,
                    searchEngineId = profile.settings.searchEngineId,
                    permissions = task.permissions,
                    confirmActions = settings.confirmActions
                )
                val gateway = AgentGateways.forProvider(
                    callFactory = OkHttpClient(),
                    provider = provider,
                    apiKey = apiKey,
                    appContext = context
                )
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
                runCatching { session.close() }
            }
        }
    }
}
