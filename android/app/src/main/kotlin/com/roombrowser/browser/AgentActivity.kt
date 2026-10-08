package com.roombrowser.browser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import com.roombrowser.agent.ui.AgentScreenHost
import com.roombrowser.agent.ui.AgentSessionsActivity
import com.roombrowser.agent.ui.AgentSettingsActivity
import com.roombrowser.ui.common.RoomBrowserTheme

/**
 * Room Agent as its own screen — the alternative to the panel that opens over
 * the page, chosen from AI settings.
 *
 * It does NOT build a ViewModel of its own. `:browser` holds one profile at a
 * time on one engine, and a second [BrowserViewModel] would restore the tabs
 * again, start a second agent and re-run the download recovery against the
 * engine the browser already owns. So this screen renders the live one
 * through [BrowserSession]: same conversation, same page, same turn — only the
 * window it is drawn in differs.
 *
 * The browser stays alive underneath, so the ViewModel is there in practice.
 * When it is not — the system reclaimed the browser while this screen was on
 * top — there is no agent to show, and an empty chat would be a lie: the
 * browser is brought back with the agent already open instead.
 */
class AgentActivity : FragmentActivity() {

    private val sessionsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val sessionId = result.data?.getLongExtra(AgentSessionsActivity.EXTRA_SESSION_ID, -1L) ?: -1L
        if (sessionId > 0) BrowserSession.current()?.agent?.openSession(sessionId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val viewModel = BrowserSession.current()
        if (viewModel == null) {
            finish()
            relaunchBrowserWithAgent()
            return
        }

        setContent {
            RoomBrowserTheme(spec = viewModel.themeSpec) {
                AgentScreenHost(
                    viewModel = viewModel,
                    onClose = { finish() },
                    onOpenSettings = {
                        AgentSettingsActivity.launch(this, viewModel.profileId.value)
                    },
                    onOpenSessions = {
                        sessionsLauncher.launch(
                            Intent(this, AgentSessionsActivity::class.java).apply {
                                putExtra(AgentSessionsActivity.EXTRA_PROFILE_ID, viewModel.profileId.value)
                            }
                        )
                    }
                )
            }
        }
    }

    /** No agent to render: hand back to the browser, which owns one. */
    private fun relaunchBrowserWithAgent() {
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID) ?: return
        startActivity(
            Intent(this, BrowserActivity::class.java)
                .putExtra(BrowserActivity.EXTRA_PROFILE_ID, profileId)
                .putExtra(EXTRA_OPEN_AGENT, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    companion object {
        const val EXTRA_PROFILE_ID = "agent_profile_id"

        /** Set on a BrowserActivity launch that should arrive with the agent open. */
        const val EXTRA_OPEN_AGENT = "com.roombrowser.extra.OPEN_AGENT"

        fun launch(context: Context, profileId: String) {
            context.startActivity(
                Intent(context, AgentActivity::class.java)
                    .putExtra(EXTRA_PROFILE_ID, profileId)
            )
        }
    }
}
