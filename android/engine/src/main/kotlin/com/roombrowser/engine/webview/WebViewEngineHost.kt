package com.roombrowser.engine.webview

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.engine.BlockedResourceSink
import com.roombrowser.engine.EngineActivityDelegate
import com.roombrowser.engine.EngineHost
import com.roombrowser.engine.EngineOption
import com.roombrowser.engine.EngineSession
import com.roombrowser.engine.ResourceFilter
import java.io.File

/**
 * The WebView implementation of [EngineHost].
 *
 * ONE PROCESS, ONE PROFILE, AND HERE THAT IS ENFORCED BY ANDROID RATHER THAN BY
 * US. `WebView.setDataDirectorySuffix` is the whole isolation mechanism -- it
 * gives the suffix's profile its own cookie jar, localStorage, IndexedDB,
 * service workers, HTTP cache and Web SQL -- and it is settable EXACTLY ONCE
 * PER PROCESS: a second call throws, and there is no way to undo or retarget
 * one. That single platform fact is the origin of the whole two-edition
 * isolation model (see PROFILE_ISOLATION.md): [bind] succeeds the first time,
 * refuses a second bind to a DIFFERENT profile, and the caller's response to
 * that refusal is to restart the process.
 *
 * WHY THE SUFFIX IS DERIVED FROM THE PROFILE UUID, NEVER THE NAME: a name is
 * user-editable and need not be unique, so two profiles could map to one
 * directory -- which is a cookie jar shared between two identities, the exact
 * thing the suffix exists to prevent. [ProfileId.safeSuffix] is the immutable
 * id with the hyphens stripped, and it is the same identity the GeckoView
 * edition passes as its session context id, so the two editions partition
 * storage by the same key.
 *
 * This file is the port of `com.roombrowser.browser.engine.ProfileEngine`
 * (`android/app/src/main/kotlin/com/roombrowser/browser/engine/ProfileEngine.kt`)
 * plus the tab-side lifecycle that used to live in `BrowserViewModel`. Nothing
 * here is new behaviour; the citations on each member name the lines the
 * behaviour was taken from.
 */
internal class WebViewEngineHost : EngineHost {

    /**
     * The bound profile. Written once per process on the thread that calls
     * [bind]; read from any thread afterwards. `@Volatile` for the same reason
     * the WebView edition's `boundProfileId` was: the app configures a profile
     * from the main thread and reads storage sizes from a worker.
     */
    @Volatile
    private var bound: ProfileId? = null

    /**
     * WebView package name + version for the diagnostics screen.
     *
     * Port of `ProfileEngine.engineName` (ProfileEngine.kt:113-124), including
     * its reasoning: [WebView.getCurrentWebViewPackage] is asked FIRST because
     * it names the provider this process is actually rendering with, which is
     * the only answer a diagnostics screen wants; the package-name probe is
     * only a fallback for the pre-provider-selection window and for vendor
     * builds that return null. The chain is `runCatching { ... }` rather than
     * `getPackageInfo(a) ?: getPackageInfo(b)` because `getPackageInfo` signals
     * "not installed" by THROWING, not by returning null -- the elvis form was
     * dead code and the throw unwound to the placeholder on a device carrying
     * only one of the two providers.
     */
    override fun engineName(context: Context): String {
        runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()?.let { info ->
            return "${info.packageName} ${info.versionName ?: "?"}"
        }
        val pm = context.packageManager
        listOf("com.google.android.webview", "com.android.webview").forEach { name ->
            runCatching { pm.getPackageInfo(name, 0) }.getOrNull()?.let { info ->
                return "${info.packageName} ${info.versionName ?: "?"}"
            }
        }
        return "Android WebView"
    }

    /**
     * Every WebView-provider package this device offers, the active one
     * flagged. Port of `ProfileEngine.installedWebViewEngines`
     * (ProfileEngine.kt:135-169).
     *
     * This is the list the facade's [EngineOption] shape was written for: the
     * device may carry several interchangeable providers and Android -- not
     * this app -- decides which one is active, so the screen can only report
     * what is installed and mark the one in use. The GeckoView edition returns
     * the same list with exactly one entry.
     */
    override fun engineOptions(context: Context): List<EngineOption> {
        val currentInfo = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        val currentPackage = currentInfo?.packageName
        val pm = context.packageManager
        val candidates = listOf(
            "com.google.android.webview",
            "com.android.webview",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.android.chrome"
        )
        val found = mutableListOf<EngineOption>()
        candidates.forEach { name ->
            runCatching { pm.getPackageInfo(name, 0) }.getOrNull()?.let { info ->
                if (found.none { it.packageName == info.packageName }) {
                    found.add(EngineOption(info.packageName, info.versionName ?: "?", false))
                }
            }
        }
        // The actually-active provider may be a vendor package outside the
        // candidate list -- make sure it is listed too.
        if (currentPackage != null && found.none { it.packageName == currentPackage }) {
            found.add(EngineOption(currentPackage, currentInfo?.versionName ?: "?", false))
        }
        if (found.isEmpty()) {
            // Defensive: nothing detectable at all -- always fall back to at
            // least one entry matching engineName()'s source package so the
            // dropdown never renders empty.
            return listOf(EngineOption("com.google.android.webview", "?", true))
        }
        return found
            .map { it.copy(isCurrent = it.packageName == currentPackage) }
            .sortedWith(compareByDescending<EngineOption> { it.isCurrent }.thenBy { it.packageName })
    }

    /**
     * Bind this process to [profileId]. Port of
     * `ProfileEngine.bindProcessToProfile` (ProfileEngine.kt:176-187).
     *
     * THE ONCE-PER-PROCESS GUARD IS THE POINT, and it is not a cached flag that
     * could be cleared: [WebView.setDataDirectorySuffix] has already committed
     * this process to [profileId]'s on-disk state by the time [bound] is
     * written, and the platform offers no way to take that back. So a second
     * bind to a DIFFERENT profile returns false rather than attempting the
     * call, which would throw and leave the caller with no way to tell "already
     * bound to the profile you asked for" from "bound to another one".
     *
     * The suffix is validated with the app's `require` before it reaches the
     * platform: the platform's own reaction to a bad suffix is not specified,
     * and a suffix containing a path separator would be a directory traversal
     * out of the app's data dir.
     */
    override fun bind(context: Context, profileId: ProfileId): Boolean {
        val current = bound
        if (current == profileId) return true
        // Already committed to a different profile: the on-disk state belongs
        // to that one and the suffix cannot be changed. Refusing here is what
        // makes the caller restart the process.
        if (current != null) return false
        val suffix = profileId.safeSuffix
        require(suffix.length in 1..32 && suffix.all { it.isLetterOrDigit() }) {
            "Invalid WebView data directory suffix"
        }
        WebView.setDataDirectorySuffix(suffix)
        bound = profileId
        return true
    }

    override fun boundProfile(): ProfileId? = bound

    /**
     * Create a session for the bound profile. Port of
     * `ProfileEngine.createWebView` (ProfileEngine.kt:195-204) and the WebView
     * factory in `BrowserViewModel.createWebView`
     * (BrowserViewModel.kt:1288-1333) minus the parts that live above the
     * facade (the dApp bridges' dependencies, the download engine).
     *
     * The application context is used, not whatever context the caller
     * happened to hold: the app passed `getApplication()` here, and a WebView
     * holding an Activity is an Activity that cannot be collected.
     */
    override fun createSession(
        context: Context,
        profile: Profile,
        sessionId: String,
        isPrivate: Boolean
    ): EngineSession {
        val id = bound
        check(id == profile.id) {
            "Process is not bound to profile ${profile.id} -- restart required"
        }
        return WebViewEngineSession(
            id = sessionId,
            context = context.applicationContext,
            profile = profile,
            isPrivate = isPrivate
        )
    }

    override fun configure(session: EngineSession, profile: Profile) {
        (session as? WebViewEngineSession)?.configure(profile)
    }

    override fun applyDesktopMode(session: EngineSession, profile: Profile, desktop: Boolean) {
        (session as? WebViewEngineSession)?.applyDesktopMode(profile, desktop)
    }

    /**
     * DELIBERATELY A NO-OP, and the reason is the engine's, not a gap.
     *
     * Android WebView reports every sub-resource to
     * `WebViewClient.shouldInterceptRequest`, which the app implements
     * (`WebClients.onResourceRequest`): the app holds the filter, applies it
     * in-process, and reports the blocks it makes itself. There is nothing
     * below the facade to configure, and no blocker that could call [blocked]
     * -- the app reaches its own report path directly, at the moment it
     * decides, which is *earlier* than a round trip through an engine could
     * be.
     *
     * The GeckoView edition is the one that needs this member: it has no
     * per-request callback, so the filter travels to a WebExtension and
     * [blocked] is how those decisions come back. Accepting the argument and
     * ignoring it is honest here; the alternative -- throwing, or storing a
     * filter nothing reads -- would make the WebView edition refuse a call
     * that is correct for the app to make in both editions.
     */
    override fun setResourceFilter(filter: ResourceFilter, blocked: BlockedResourceSink) {
        // Intentionally empty: see the KDoc above. Not a TODO.
    }

    /**
     * DELIBERATELY A NO-OP, for the same reason as [setResourceFilter]: the
     * window this delegate would open is one the app already opens itself.
     *
     * Android WebView asks the APP for anything that needs an Activity -- the
     * app implements `WebChromeClient.onShowFileChooser` and answers its own
     * permission and auth prompts, and it IS an Activity, so it starts what it
     * needs with its own `registerForActivityResult`. There is no engine-side
     * pending intent to hand over. GeckoView has to have this member because it
     * runs outside the app's process and reaches the passkey provider through a
     * `PendingIntent` the app alone can launch.
     */
    override fun setActivityDelegate(delegate: EngineActivityDelegate?) {
        // Intentionally empty: see the KDoc above. Not a TODO.
    }

    /**
     * Erase all engine storage for the bound profile. Port of
     * `ProfileEngine.clearEngineStorage` (ProfileEngine.kt:433-458).
     *
     * `clearCache(true)` is an INSTANCE method that wipes the whole per-suffix
     * HTTP cache, so a throwaway WebView is the only way to ask for it here --
     * and it has to be DESTROYED again. Every clear-browsing-data and
     * profile-delete run otherwise left an undestroyed WebView holding a
     * renderer binding and this Context until the GC happened to collect it.
     * The line has to stay: [wipeProfileData] is a SEPARATE entry point that
     * neither caller of this function runs, so nothing else clears the cache on
     * this path.
     */
    override fun clearBrowsingData(context: Context, profileId: ProfileId) {
        check(bound == profileId) { "Process not bound to ${profileId.value}" }
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.removeSessionCookies(null)
        cookieManager.flush()
        WebStorage.getInstance().deleteAllData()
        WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
        WebViewDatabase.getInstance(context).clearFormData()
        runCatching {
            val scratch = WebView(context)
            try {
                scratch.clearCache(true)
            } finally {
                scratch.destroy()
            }
        }
    }

    /**
     * Remove the profile's WebView data directories from disk. Port of
     * `ProfileEngine.wipeProfileStorage` in the WebView edition.
     *
     * Belt and braces on top of [clearBrowsingData]: the platform's own
     * deletion is asynchronous and has been observed to leave the per-suffix
     * directories behind, and a profile the user deleted must not leave its
     * cookie jar on disk for the next process to find.
     */
    override fun wipeProfileData(context: Context, profileId: ProfileId) {
        val suffix = profileId.safeSuffix
        val candidates = listOf(
            File(context.applicationInfo.dataDir, "app_webview_$suffix"),
            File(context.cacheDir, "WebView_$suffix"),
            File(context.applicationInfo.dataDir, "app_webview_${suffix}_" + "cache"),
            File(context.cacheDir, "http_cache_$suffix")
        )
        candidates.forEach { dir -> if (dir.exists()) dir.deleteRecursively() }
    }

    /**
     * Storage footprint, including the engine's own directories. Port of
     * `ProfileEngine.profileStorageBytes` (ProfileEngine.kt:477-485).
     *
     * The exact directory layout is an Android implementation detail, so this
     * measures the documented layout plus this app's own per-profile dirs and
     * reports the sum. It is a diagnostic number, not a quota.
     */
    override fun storageBytes(context: Context, profileId: ProfileId): Long {
        val suffix = profileId.safeSuffix
        val dirs = listOf(
            File(context.applicationInfo.dataDir, "app_webview_$suffix"),
            File(context.cacheDir, "WebView_$suffix"),
            File(context.filesDir, "profiles/profile_${profileId.value}")
        )
        return dirs.filter { it.exists() }.sumOf { it.dirSize() }
    }

    private fun File.dirSize(): Long = walkBottomUp()
        .filter { it.isFile }
        .sumOf { it.length() }

    /**
     * DELIBERATELY EMPTY, and the emptiness is the correct answer rather than
     * an unfinished member.
     *
     * The WebView edition holds no process-wide engine object: the renderer
     * processes belong to the platform and are torn down when the last WebView
     * is destroyed, which the app already does per tab through
     * [EngineSession.close]. The one resource this host DOES own process-wide
     * is the data-directory suffix, and it is IRREVERSIBLE -- there is no
     * `clearDataDirectorySuffix`, and a second [bind] would throw. So there is
     * nothing here that could be released, and nothing this function could
     * reset without making [bind] lie to the next caller.
     *
     * The GeckoView edition has real work to do here (`GeckoRuntime.shutdown`)
     * and does it. This edition's honest answer is that the process IS the
     * shutdown path: the caller that wants the engine gone restarts it, which
     * is the same protocol profile switching already uses.
     */
    /**
     * Commit WebView's buffered state to disk.
     *
     * WebView holds cookies in memory and writes them out on its own schedule.
     * The app kills its own process on a profile switch, with no orderly
     * shutdown to hang a save on, so anything still unwritten at that moment is
     * lost -- which is why the app used to call `CookieManager.flush()` itself
     * as its last act. The conversion left that call with nothing to call.
     *
     * Deliberately not gated on [bound]: this runs on the process-death path,
     * where a refusal would be the worst possible answer.
     */
    override fun flush(context: Context) {
        runCatching { CookieManager.getInstance().flush() }
    }

    override fun shutdown() {
        // Intentionally empty -- see the KDoc above. Not a TODO.
    }
}
