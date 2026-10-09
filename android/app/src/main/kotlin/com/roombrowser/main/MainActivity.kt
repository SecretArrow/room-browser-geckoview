package com.roombrowser.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import com.roombrowser.browser.BrowserActivity
import com.roombrowser.domain.oct.OctUri
import com.roombrowser.security.BiometricGate
import com.roombrowser.ui.common.RoomBrowserTheme
import com.roombrowser.main.ui.MainScreen

/**
 * Launcher activity (default process): profile selector, first-run flow,
 * profile CRUD, external link routing and share-sheet receiving.
 *
 * This process NEVER hosts a WebView — that is exclusive to the ':browser'
 * process so per-profile storage isolation is guaranteed.
 */
class MainActivity : FragmentActivity() {

    private lateinit var viewModel: MainViewModel

    // Notifications (API 33+): agent progress + download completions.
    // The result used to be thrown away ({}), which made a denial completely
    // invisible: downloads finished and the agent worked while no
    // notification ever appeared and nothing told the user why. A denial now
    // explains itself through the screen's snackbar channel and offers the
    // system screen that can still grant it.
    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) viewModel.onNotificationPermissionDenied()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: insets are consumed by the Compose UI so the profile
        // list never runs under the system navigation buttons.
        enableEdgeToEdge()
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        requestNotificationPermissionIfNeeded()
        // Route external links (VIEW intent from other apps) through the
        // "Open with profile" chooser — never silently open the wrong profile.
        intent?.dataString?.let { handleExternalUrl(it) }
        intent?.takeIf { it.action == Intent.ACTION_SEND }
            ?.getStringExtra(Intent.EXTRA_TEXT)
            ?.let { handleExternalUrl(it) }

        setContent {
            // The picker previews the per-profile theme system: it wears the
            // DEFAULT profile's theme and restyles live when themes change.
            RoomBrowserTheme(spec = viewModel.appTheme) {
                MainScreen(
                    activity = this,
                    viewModel = viewModel,
                    onOpenProfile = { profileId, url ->
                        openBrowser(profileId, url)
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.dataString?.let { handleExternalUrl(it) }
        intent.takeIf { it.action == Intent.ACTION_SEND }
            ?.getStringExtra(Intent.EXTRA_TEXT)
            ?.let { handleExternalUrl(it) }
    }

    private fun handleExternalUrl(raw: String) {
        val url = raw.trim()
        val target = when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            // A malformed circle is dropped rather than forwarded to be searched.
            url.startsWith(OctUri.PREFIX) -> OctUri.parse(url)?.raw ?: return
            else -> return
        }
        viewModel.submitExternalUrl(target)
    }

    /** Requests POST_NOTIFICATIONS once on API 33+ so the agent's background
     *  progress notification (and download alerts) are visible. ONCE is the
     *  ViewModel's job (see claimNotificationPrompt): a twice-denied
     *  permission is answered instantly with no UI, so an unconditional ask
     *  here would re-post the denial hint on every rotation. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            viewModel.claimNotificationPrompt()
        ) {
            runCatching { notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    /**
     * The system's notification screen for this app — the snackbar action of
     * a denied POST_NOTIFICATIONS request. It has to be the system screen:
     * once the user has denied twice, the app can never prompt again itself.
     *
     * The app-details page is the fallback so the action is never a dead end
     * on a ROM that lacks the per-app notification screen.
     */
    fun openNotificationSettings() {
        val notifications = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        runCatching { startActivity(notifications) }.onFailure {
            val details = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
            runCatching { startActivity(details) }
        }
    }

    /** Launch the browser process for the selected profile. */
    private fun openBrowser(profileId: String, url: String?) {
        val intent = Intent(this, com.roombrowser.browser.BrowserActivity::class.java).apply {
            putExtra(BrowserActivity.EXTRA_PROFILE_ID, profileId)
            url?.let { putExtra(BrowserActivity.EXTRA_INITIAL_URL, it) }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    /** Biometric gate for locked profiles (called from the UI). */
    fun gateProfile(profileName: String, onUnlocked: () -> Unit) {
        BiometricGate.unlock(this, profileName, onUnlocked, onUnlocked)
    }

    /**
     * Biometric gate for the password vault (backup export / import of
     * saved logins). Unlike [gateProfile] — which treats "no biometric
     * hardware" as proceed — a FAILED vault gate ABORTS the action: the
     * caller keeps the vault locked and reports the abort (nothing is
     * read, written or built).
     */
    fun gateVault(onSuccess: () -> Unit, onFailure: () -> Unit) {
        BiometricGate.unlock(this, "Password vault", onSuccess, onFailure)
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
