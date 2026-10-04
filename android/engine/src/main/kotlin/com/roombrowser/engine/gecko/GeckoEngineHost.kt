package com.roombrowser.engine.gecko

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.engine.EngineHost
import com.roombrowser.engine.EngineOption
import com.roombrowser.engine.EngineSession
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebExtension
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * The GeckoView implementation of [EngineHost].
 *
 * ONE PROCESS, ONE PROFILE, ONE RUNTIME -- and here that is enforced by the
 * engine rather than by our convention: `GeckoRuntime.create` throws when a
 * runtime is already active in the process, and there is no per-runtime data
 * directory setting anywhere in `GeckoRuntimeSettings`. So the WebView
 * edition's model (a process-wide data-directory suffix, one profile per
 * process, switch by restarting) is preserved exactly, and the per-profile
 * partition is expressed a second time as the session context id. Belt and
 * braces: the process boundary is the isolation guarantee, the context id is
 * what keeps two sessions inside one process from sharing a jar.
 *
 * See PROFILE_ISOLATION.md for the protocol the caller follows.
 */
internal class GeckoEngineHost : EngineHost {

    /**
     * The bound profile. Written once per process on the thread that calls
     * [bind]; read from any thread afterwards.
     */
    @Volatile
    private var bound: ProfileId? = null

    /**
     * THE process-wide runtime. Non-null is the commitment: GeckoView refuses
     * a second one, which is why [bind] cannot retarget and the caller must
     * restart the process instead.
     */
    @Volatile
    private var runtime: GeckoRuntime? = null

    /**
     * The built-in bridge extension, or null until it finishes installing.
     *
     * Installation is asynchronous, so a session created in the first
     * milliseconds of the process can outrun it. Sessions register through
     * [onBridgeReady], which fires immediately when the extension is already
     * in place and defers otherwise.
     */
    @Volatile
    private var bridge: WebExtension? = null

    private val bridgeWaiters = mutableListOf<(WebExtension) -> Unit>()

    override fun engineName(context: Context): String = ENGINE_LABEL

    /**
     * GeckoView is bundled into this APK rather than provided by the device,
     * so there is exactly one engine and it is always the current one. The
     * WebView edition lists the device's interchangeable providers here; this
     * is the same list, of length one.
     */
    override fun engineOptions(context: Context): List<EngineOption> = listOf(
        EngineOption(
            packageName = "org.mozilla.geckoview",
            versionName = org.mozilla.geckoview.BuildConfig.MOZ_APP_VERSION,
            isCurrent = true
        )
    )

    override fun bind(context: Context, profileId: ProfileId): Boolean {
        // GeckoRuntime.create() installs process-wide state and must run on
        // the main thread. That is an ENGINE requirement, and the facade is
        // the only place it is allowed to exist: every caller above the facade
        // -- :app, and the androidTest suite -- is byte-identical between the
        // two editions and was written against an engine that had no threading
        // rule at all, so pushing the rule outward would make one edition's
        // code wrong on the other. Marshal instead.
        //
        // The check this replaces (`Looper.myLooper() != null`) accepted ANY
        // Looper thread, which is not the thread GeckoView needs; it happened
        // to hold in production and threw in the one caller that runs on a
        // plain thread, turning ProfileIsolationTest's subject -- "a second
        // bind to a different profile returns false" -- into an
        // IllegalStateException out of a method whose contract has no throw.
        return onMainThread { bindOnMainThread(context, profileId) }
    }

    private fun bindOnMainThread(context: Context, profileId: ProfileId): Boolean {
        val current = bound
        if (current == profileId) return true
        // Already committed to a different profile: the on-disk state belongs
        // to that one, and a GeckoRuntime cannot be retargeted. Refusing here
        // is what makes the caller restart the process -- the same contract,
        // and the same reason, as the WebView edition's data-directory suffix.
        if (current != null) return false

        val app = context.applicationContext
        val created = GeckoRuntime.create(app)
        runtime = created
        bound = profileId
        installBridge(created)
        return true
    }

    /**
     * Runs [block] on the main thread and returns its value, blocking the
     * caller until it has finished.
     *
     * Re-entrant by construction: a caller already on the main thread runs
     * [block] inline. That is both the production path and the only way to
     * avoid deadlocking against the very looper the work must run on.
     *
     * A Handler and a latch rather than a coroutine because this module has no
     * coroutines dependency, and a thread hop is not a reason to add one.
     */
    private fun <T> onMainThread(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val done = CountDownLatch(1)
        var value: T? = null
        var failure: Throwable? = null
        Handler(Looper.getMainLooper()).post {
            try {
                value = block()
            } catch (t: Throwable) {
                // Carried back rather than logged: the caller's contract is a
                // Boolean return, and a bind that could not create a runtime
                // must fail where it was called, not on a thread it does not
                // own.
                failure = t
            } finally {
                done.countDown()
            }
        }
        done.await()
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    /**
     * Install the page-bridge extension.
     *
     * The URI form is not a choice: `WebExtensionController.ensureBuiltIn`
     * accepts only `resource://android` URIs, and the AAR's assets merge into
     * this APK's assets, so the extension ships inside the app and is
     * registered by path. The id must equal `browser_specific_settings.gecko.id`
     * in the manifest -- see the asset in `src/main/assets/roombridge/`.
     */
    private fun installBridge(created: GeckoRuntime) {
        created.webExtensionController
            .ensureBuiltIn(BRIDGE_URI, BRIDGE_ID)
            .accept(
                { extension ->
                    if (extension == null) return@accept
                    bridge = extension
                    val waiting = synchronized(bridgeWaiters) {
                        val copy = bridgeWaiters.toList()
                        bridgeWaiters.clear()
                        copy
                    }
                    waiting.forEach { it(extension) }
                },
                { error ->
                    // No bridge means no wallet, no vault and no device shim.
                    // Loud on purpose: this is not a degraded-but-usable state.
                    android.util.Log.e(
                        "GeckoEngineHost",
                        "Built-in bridge extension failed to install; page bridges are dead",
                        error
                    )
                }
            )
    }

    /** Run [action] with the bridge extension, now or as soon as it exists. */
    private fun onBridgeReady(action: (WebExtension) -> Unit) {
        bridge?.let { action(it); return }
        synchronized(bridgeWaiters) {
            // Re-check under the lock: the installer may have finished between
            // the read above and this block.
            bridge?.let { action(it); return }
            bridgeWaiters.add(action)
        }
    }

    override fun boundProfile(): ProfileId? = bound

    override fun createSession(
        context: Context,
        profile: Profile,
        sessionId: String
    ): EngineSession {
        val id = bound
        check(id == profile.id) {
            "Process is not bound to profile ${profile.id} -- restart required"
        }
        val active = runtime
        check(active != null) { "bind() must be called before createSession()" }
        check(Looper.myLooper() != null) {
            "EngineHost.createSession must run on a Looper thread (the main thread)"
        }

        val session = GeckoEngineSession(
            id = sessionId,
            runtime = active,
            context = context,
            profile = profile
        )
        // The bridge is only useful once a session can carry it, and a session
        // created before installation finishes must still get one.
        onBridgeReady { session.installBridge(it) }
        return session
    }

    override fun configure(session: EngineSession, profile: Profile) {
        (session as? GeckoEngineSession)?.configure(profile)
    }

    override fun applyDesktopMode(session: EngineSession, profile: Profile, desktop: Boolean) {
        (session as? GeckoEngineSession)?.applyDesktopMode(profile, desktop)
    }

    override fun clearBrowsingData(context: Context, profileId: ProfileId) {
        check(bound == profileId) { "Process not bound to ${profileId.value}" }
        val active = runtime ?: return
        // Everything: cookies, both caches, DOM storage, auth sessions,
        // permissions and site data. The caller asked for a clean profile and
        // there is no partial answer that is not a privacy bug waiting to be
        // reported -- a "clear browsing data" that leaves cookies behind reads
        // as a broken promise, which is exactly the failure mode to avoid.
        active.storageController
            .clearData(StorageController.ClearFlags.ALL)
            .accept({ }, { error ->
                android.util.Log.e("GeckoEngineHost", "clearData(ALL) failed", error)
            })
    }

    /**
     * Remove the profile's Gecko directories from disk.
     *
     * GeckoView stores under the app's data dir keyed by the session context
     * id, which is derived from the profile UUID -- the same identity the
     * WebView edition's directory suffix used.
     */
    override fun wipeProfileData(context: Context, profileId: ProfileId) {
        val key = profileId.safeSuffix
        val candidates = listOf(
            File(context.applicationInfo.dataDir, "mozac/$key"),
            File(context.filesDir, "mozac/$key"),
            File(context.filesDir, "profiles/profile_${profileId.value}")
        )
        candidates.forEach { dir -> if (dir.exists()) dir.deleteRecursively() }
    }

    /**
     * Storage footprint, including the engine's own directories.
     *
     * GeckoView publishes no equivalent of a "browser data" size query -- the
     * `StorageController.getBrowserData()` that would answer it does not exist
     * in this API -- so this measures the documented layout on disk. It is a
     * diagnostic number, not a quota, and it is honest about being an
     * approximation of what the engine holds.
     */
    override fun storageBytes(context: Context, profileId: ProfileId): Long {
        val key = profileId.safeSuffix
        val dirs = listOf(
            File(context.applicationInfo.dataDir, "mozac/$key"),
            File(context.filesDir, "mozac/$key"),
            File(context.cacheDir, "mozac/$key"),
            File(context.filesDir, "profiles/profile_${profileId.value}")
        )
        return dirs.filter { it.exists() }.sumOf { it.dirSize() }
    }

    private fun File.dirSize(): Long = walkBottomUp()
        .filter { it.isFile }
        .sumOf { it.length() }

    /**
     * A truthful no-op.
     *
     * GeckoView writes its profile data through to disk as it goes, so there
     * is no in-memory cookie jar to commit. The app calls this on the
     * process-death path before switching profiles and there is nothing here
     * that killing the process could lose. Implementing it as anything else --
     * a synchronous flush, a runtime restart -- would invent work that the
     * engine does not need.
     */
    override fun flush(context: Context) {
        // Intentionally empty: see the KDoc above.
    }

    override fun shutdown() {
        runtime?.shutdown()
        runtime = null
        bound = null
        bridge = null
        synchronized(bridgeWaiters) { bridgeWaiters.clear() }
    }

    private companion object {
        /** Matches `browser_specific_settings.gecko.id` in the manifest. */
        const val BRIDGE_ID = "roombridge@roombrowser.com"

        /**
         * Only `resource://android` URIs are accepted by ensureBuiltIn; the
         * assets of a library module merge into the consuming APK.
         *
         * THE MANIFEST'S CONTENT-SCRIPT KEYS ARE SNAKE_CASE, and getting them
         * wrong is silent. `run_at`, `all_frames` and `match_about_blank` are
         * the manifest spellings; the camelCase forms (`runAt`, `allFrames`,
         * `matchAboutBlank`) belong to the `contentScripts.register()` JS API.
         * A manifest that uses the camelCase forms does not fail to load --
         * GeckoConsole logs one `WARN ... An unexpected property was found in
         * the WebExtension manifest` per key and then IGNORES it, so the
         * scripts fall back to `document_idle` and the top frame only. The
         * whole point of main.js is to run before the page's own first script,
         * and the device shim's WebRTC claim is void if it does not; the
         * failure is invisible in the app and shows up only as a page that
         * never calls the bridge.
         */
        const val BRIDGE_URI = "resource://android/assets/roombridge/"

        val ENGINE_LABEL: String =
            "GeckoView " + org.mozilla.geckoview.BuildConfig.MOZ_APP_VERSION
    }
}
