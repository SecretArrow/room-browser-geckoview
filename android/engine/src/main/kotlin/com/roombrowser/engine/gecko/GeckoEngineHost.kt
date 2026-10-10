package com.roombrowser.engine.gecko

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.roombrowser.domain.engine.FilterEngine
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.proxy.ProxyScheme
import com.roombrowser.engine.BlockedResourceSink
import com.roombrowser.engine.EngineActivityDelegate
import com.roombrowser.engine.EngineHost
import com.roombrowser.engine.EngineOption
import com.roombrowser.engine.EngineProxyConfig
import com.roombrowser.engine.EngineSession
import com.roombrowser.engine.ResourceFilter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.ExperimentalGeckoViewApi
import org.mozilla.geckoview.GeckoPreferenceController
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
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

    /**
     * The background page's native port -- the sub-resource blocker's channel.
     *
     * A SEPARATE PORT FROM THE PAGE BRIDGES, and a separate delegate kind. The
     * content scripts' ports are session-scoped: GeckoView routes each one to
     * the `MessageDelegate` its own session registered. A background script has
     * no session, and GeckoView routes its port to the delegate registered on
     * the EXTENSION instead (see `WebExtension.setMessageDelegate`). Two ports,
     * two lookups, one extension -- and they must not be given the same
     * native-app name, or the log would attribute a page bridge message to the
     * blocker and vice versa.
     */
    @Volatile
    private var blockerPort: WebExtension.Port? = null

    /**
     * The filter the app last handed over, or null before the first push.
     *
     * Held here rather than pushed straight at the port because the port and
     * the filter arrive in either order: the app configures its settings as
     * soon as it has a profile, while the background page connects when Gecko
     * gets round to it. Whichever arrives second causes the push, so neither
     * ordering can silently leave the blocker without rules.
     */
    @Volatile
    private var resourceFilter: ResourceFilter? = null

    /** Where a block the extension cancelled is reported. */
    @Volatile
    private var blockedSink: BlockedResourceSink? = null

    /**
     * The app's window, or null while the app has offered none.
     *
     * HELD BEFORE THE RUNTIME EXISTS, which is the ordering the app actually
     * uses: it installs this in `onCreate` and binds in the same method, and
     * [runtime] is what [bind] creates. A delegate that arrives first is
     * applied by [bindOnMainThread] instead of being dropped.
     */
    @Volatile
    private var activityDelegate: EngineActivityDelegate? = null

    /**
     * GeckoView's own mobile UA, which is what a DEFAULT-mode profile sends:
     * the session is built with USER_AGENT_MODE_MOBILE and no override.
     *
     * Null while the runtime is not up -- the call reaches a GeckoThread that
     * is not running -- and the screen reports that as unknown rather than
     * inventing a string.
     */
    override fun defaultUserAgent(context: Context): String? =
        runCatching { GeckoSession.getDefaultUserAgent() }.getOrNull()

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
        // Before the bridge and before any session: a page's FIRST script may
        // feature-detect the passkey API, and the answer must already be final.
        hideWebAuthn()
        installBridge(created)
        // Applied here, not only from [setActivityDelegate]: the app's call
        // arrives before this runtime exists, and a delegate dropped on that
        // ordering would leave every passkey request failing.
        applyActivityDelegate(created)
        return true
    }

    /**
     * Take WebAuthn away from every page.
     *
     * This build cannot service an assertion -- the request falls through to
     * GMS FIDO2, whose failure GeckoView never reports, so the page's promise
     * never settles and a sign-in offering a passkey spins forever. A site that
     * can still see `PublicKeyCredential` will not offer its password instead,
     * so the API has to be gone rather than merely fail. The pref gates that
     * interface and `navigator.credentials`' `publicKey` overloads together.
     *
     * Delete this, and the e2e test pinning it, once an assertion can be
     * serviced in a third-party app: that needs Google's provider allowlist or
     * per-site Digital Asset Links, not app code. Then clear the pref too -- the
     * user branch is persisted into the Gecko profile, so deleting this code
     * alone leaves passkeys hidden with nothing left to explain why.
     */
    @androidx.annotation.OptIn(ExperimentalGeckoViewApi::class)
    private fun hideWebAuthn() {
        GeckoPreferenceController.setGeckoPref(
            WEBAUTHN_PREF,
            false,
            GeckoPreferenceController.PREF_BRANCH_USER
        )
    }

    /**
     * Necko carries SOCKS as well as HTTP, so unlike WebView nothing is refused here.
     */
    override fun proxySupported(): Boolean = true

    /** Necko carries every scheme the settings can express. */
    override fun proxySupportsScheme(scheme: ProxyScheme): Boolean = true

    /**
     * GeckoView exposes no proxy API; Necko's own prefs are the only route, reached
     * through the same experimental preference controller [hideWebAuthn] already uses.
     *
     * These prefs PERSIST into the profile, which is the opposite of WebView's override:
     * a stale one would outlive the profile that set it, so this runs at every bind and
     * a null config clears the branch rather than merely switching the mode off.
     */
    @androidx.annotation.OptIn(ExperimentalGeckoViewApi::class)
    override suspend fun setProxy(config: EngineProxyConfig?) {
        if (config == null) {
            PROXY_MANAGED_PREFS.forEach { GeckoPreferenceController.clearGeckoUserPref(it) }
            awaitPref(GeckoPreferenceController.setGeckoPref(PROXY_TYPE, 0, GeckoPreferenceController.PREF_BRANCH_USER))
            return
        }
        val branch = GeckoPreferenceController.PREF_BRANCH_USER
        when (config.scheme) {
            ProxyScheme.SOCKS4, ProxyScheme.SOCKS5 -> {
                GeckoPreferenceController.setGeckoPref("network.proxy.socks", config.host, branch)
                GeckoPreferenceController.setGeckoPref("network.proxy.socks_port", config.port, branch)
                GeckoPreferenceController.setGeckoPref(
                    "network.proxy.socks_version",
                    if (config.scheme == ProxyScheme.SOCKS4) 4 else 5,
                    branch
                )
                // Without this the hostname is resolved locally and leaks the DNS query
                // past the tunnel, which is the part of "using a proxy" that actually hides.
                GeckoPreferenceController.setGeckoPref("network.proxy.socks_remote_dns", true, branch)
            }
            else -> {
                GeckoPreferenceController.setGeckoPref("network.proxy.http", config.host, branch)
                GeckoPreferenceController.setGeckoPref("network.proxy.http_port", config.port, branch)
                // An https page is carried by CONNECT through the same endpoint, so both
                // entries are the same host; a separate ssl entry could only be a second
                // proxy the user never chose.
                GeckoPreferenceController.setGeckoPref("network.proxy.ssl", config.host, branch)
                GeckoPreferenceController.setGeckoPref("network.proxy.ssl_port", config.port, branch)
                GeckoPreferenceController.setGeckoPref("network.proxy.share_proxy_settings", true, branch)
            }
        }
        // Pinned, not defaulted: the oct:// document is served from a local origin and a
        // future engine default must not be able to put it behind the proxy.
        GeckoPreferenceController.setGeckoPref("network.proxy.allow_hijacking_localhost", false, branch)
        awaitPref(GeckoPreferenceController.setGeckoPref(PROXY_TYPE, 1, branch))
    }

    /**
     * Wait for the pref service to confirm a write, bounded: the caller is holding the
     * profile bind, and a result that never arrives must not hold it forever.
     */
    private suspend fun awaitPref(result: GeckoResult<Void>) {
        val settled = withTimeoutOrNull(PROXY_APPLY_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { continuation ->
                result.accept(
                    { continuation.resume(Unit) },
                    // The listener is declared @Nullable, so a failure without a cause is a
                    // real shape here and still has to settle the continuation.
                    { error -> continuation.resumeWithException(error ?: IllegalStateException("pref write rejected")) }
                )
            }
        }
        if (settled == null) Log.w(TAG, "proxy pref not acknowledged before the timeout")
    }

    /**
     * Runs [block] on the main thread and returns its value, blocking the
     * caller until it has finished.
     *
     * Re-entrant by construction: a caller already on the main thread runs
     * [block] inline. That is both the production path and the only way to
     * avoid deadlocking against the very looper the work must run on.
     *
     * A Handler and a latch rather than a coroutine: re-entrancy is the
     * requirement here and `runBlocking` on the main thread would deadlock.
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
     * Run [block] on the main thread without waiting for it.
     *
     * For work whose thread is fixed by an annotation but whose RESULT nothing
     * depends on: registering the blocker's message delegate, and posting a
     * message on its port. `onMainThread` would block the Gecko handler thread
     * waiting on the main thread -- the direction that deadlocks if the main
     * thread is meanwhile waiting on that same handler thread -- so it is the
     * wrong tool for a call nobody is waiting on.
     */
    private fun postToMain(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
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
        // Read the controller ONCE, here, and never from inside the callback
        // below. `GeckoRuntime.getWebExtensionController()` is annotated
        // @UiThread, and this function is only ever reached through `bind` ->
        // `onMainThread { bindOnMainThread(...) }`, so reading it here is on
        // the thread the annotation asks for. A GeckoResult callback's thread
        // is not a documented contract -- Android Lint infers "any thread" for
        // it and failed the build on exactly that line, and the runtime is
        // only *probably* fine there (GeckoResult's default constructor adopts
        // the creating thread's Looper). Hoisting removes the dependency
        // instead of resting on it.
        val controller = created.webExtensionController
        controller
            .ensureBuiltIn(BRIDGE_URI, BRIDGE_ID)
            .accept(
                { extension ->
                    if (extension == null) return@accept
                    bridge = extension
                    // GeckoView does NOT let an extension run in private
                    // browsing unless it is told to, and that gate covers
                    // content scripts too. This session's private tabs run in
                    // the engine's private context, so without the call below
                    // the wallet, vault and device-shim bridges would simply
                    // stop existing in every private tab -- and they would
                    // fail the one way this bridge always fails: the install
                    // succeeds, the delegate installs, and the port is never
                    // opened.
                    controller
                        .setAllowedInPrivateBrowsing(extension, true)
                        .accept(
                            { allowed ->
                                // The flag lives on the extension's MetaData, not
                                // on the extension: `setAllowedInPrivateBrowsing`
                                // resolves with the UPDATED extension, and the
                                // answer is read from there.
                                android.util.Log.i(
                                    BRIDGE_LOG_TAG,
                                    "bridge allowedInPrivateBrowsing=" +
                                        "${allowed?.metaData?.allowedInPrivateBrowsing}"
                                )
                            },
                            { error ->
                                android.util.Log.e(
                                    BRIDGE_LOG_TAG,
                                    "Could not allow the bridge in private browsing: " +
                                        "the page bridges will be dead in private tabs",
                                    error
                                )
                            }
                        )
                    // Read the flag back instead of assuming it. A content
                    // script may only hold a port when GeckoView set
                    // ALLOW_CONTENT_MESSAGING, which it derives from the
                    // manifest -- and a manifest missing `geckoViewAddons`,
                    // `nativeMessaging` or `nativeMessagingFromContent` still
                    // produces a successful install, a successful
                    // setMessageDelegate, and a port that is never opened. That
                    // failure has no other symptom on either side, so it is
                    // recorded here, where the value is still knowable.
                    val contentMessaging =
                        (extension.flags and WebExtension.Flags.ALLOW_CONTENT_MESSAGING) != 0L
                    android.util.Log.i(
                        BRIDGE_LOG_TAG,
                        "extension installed uri=$BRIDGE_URI id=$BRIDGE_ID " +
                            "builtIn=${extension.isBuiltIn} flags=${extension.flags} " +
                            "contentMessaging=$contentMessaging"
                    )
                    if (!contentMessaging) {
                        android.util.Log.e(
                            BRIDGE_LOG_TAG,
                            "Bridge extension lacks ALLOW_CONTENT_MESSAGING: the " +
                                "isolated-world content script cannot open a port " +
                                "and every page bridge is dead. The manifest must " +
                                "list geckoViewAddons, nativeMessaging and " +
                                "nativeMessagingFromContent."
                        )
                    }
                    val waiting = synchronized(bridgeWaiters) {
                        val copy = bridgeWaiters.toList()
                        bridgeWaiters.clear()
                        copy
                    }
                    waiting.forEach { it(extension) }
                    installBlocker(extension)
                },
                { error ->
                    // No bridge means no wallet, no vault and no device shim.
                    // Loud on purpose: this is not a degraded-but-usable state.
                    android.util.Log.e(
                        BRIDGE_LOG_TAG,
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

    // ---- the sub-resource blocker ----------------------------------------

    /**
     * Attach the blocker's channel to the extension that is already installed.
     *
     * THE EXTENSION, NOT A SESSION. `webRequest.onBeforeRequest` is registered
     * by a background script, so its port belongs to the extension as a whole
     * -- there is no session to hang it on, and the blocks it reports are
     * reported without one. The app's block statistics are host-and-category
     * facts (`recordBlockEvent(host, category)`) with no session in them
     * either, which is why nothing here has to answer "which tab?".
     *
     * Posted rather than called inline because `setMessageDelegate` is
     * @UiThread and this runs on Gecko's handler thread. Nothing waits on the
     * registration: a port opened before the delegate exists is queued by
     * GeckoView and released by this very call (see
     * `WebExtensionController.releasePendingMessages`), so a connect that
     * races the install is not lost, and it is not lost silently either --
     * the port connects and the filter is pushed a moment later.
     */
    private fun installBlocker(extension: WebExtension) {
        postToMain {
            extension.setMessageDelegate(blockerDelegate, BLOCKER_NATIVE_APP)
            android.util.Log.i(
                BRIDGE_LOG_TAG,
                "blocker delegate installed on port=$BLOCKER_NATIVE_APP"
            )
        }
    }

    private val blockerDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            blockerPort = port
            port.setDelegate(blockerPortDelegate)
            if (resourceFilter == null) {
                // Not fatal, and not silent. The blocker fails OPEN: until the
                // app pushes a filter it has no rules, so it allows everything.
                // That window looks exactly like "blocking is broken", which
                // is why the order is recorded here.
                android.util.Log.w(
                    BRIDGE_LOG_TAG,
                    "blocker port connected before the app handed over a filter: " +
                        "sub-resources are unfiltered until setResourceFilter arrives"
                )
            }
            pushFilter(port)
        }
    }

    private val blockerPortDelegate = object : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, port: WebExtension.Port) {
            // Every message the blocker sends is an object (see `report` in
            // blocker.js), so anything else is a protocol mismatch -- and a
            // dropped report is a block missing from the dashboard, which is
            // the failure that looks like "the blocker stopped working".
            val json = message as? JSONObject
            if (json == null) {
                android.util.Log.w(
                    BRIDGE_LOG_TAG,
                    "blocker port message was not a JSON object: " +
                        message.javaClass.name
                )
                return
            }
            when (val type = json.optString("type")) {
                "blocked" -> reportBlocked(json)
                else -> android.util.Log.w(
                    BRIDGE_LOG_TAG,
                    "blocker port message with unknown type=$type"
                )
            }
        }

        override fun onDisconnect(port: WebExtension.Port) {
            if (blockerPort !== port) return
            blockerPort = null
            // A background page is restarted by Gecko when it dies, and the
            // restart opens a new port which pushes the filter again. So this
            // is a gap in blocking, not the end of it -- and one worth seeing,
            // since the alternative explanation for "the dashboard stopped
            // counting" is a code defect in the blocker itself.
            android.util.Log.w(
                BRIDGE_LOG_TAG,
                "blocker port disconnected: sub-resources are unfiltered until it reconnects"
            )
        }
    }

    private fun reportBlocked(json: JSONObject) {
        val host = json.optString("host")
        val category = runCatching {
            FilterEngine.FilterCategory.valueOf(json.optString("category"))
        }.getOrNull()
        if (host.isEmpty() || category == null) {
            android.util.Log.w(
                BRIDGE_LOG_TAG,
                "blocker reported an unreadable block: host='$host' " +
                    "category='${json.optString("category")}'"
            )
            return
        }
        // Delivered on the UI thread (PortDelegate is @UiThread). The sink is
        // the app's own report path, the same one the WebView edition reaches
        // from `shouldInterceptRequest`, so a block is counted identically in
        // both editions.
        blockedSink?.onBlocked(host, category)
    }

    /**
     * Send the current filter to the blocker, or do nothing when the app has
     * not handed one over yet.
     *
     * THE RULESET IS SENT AS SETS, NOT AS A LIST OF RULES TO INSTALL. The
     * blocker replaces its whole view of the filter on every push, so the
     * app can hand over a snapshot whenever anything changes -- a settings
     * toggle, a per-site exemption, a profile switch -- without this side
     * having to compute a difference. That is what makes the push safe to
     * repeat unconditionally, which is the only way it can be driven from the
     * paths that already reload those snapshots.
     */
    private fun pushFilter(port: WebExtension.Port) {
        val filter = resourceFilter ?: return
        val keywords = JSONArray()
        filter.keywordRules.forEach { rule ->
            keywords.put(
                JSONObject()
                    .put("pattern", rule.pattern)
                    .put("category", rule.category.name)
            )
        }
        val message = JSONObject()
            .put("type", "filter")
            .put("adHosts", JSONArray(filter.adHosts.toList()))
            .put("trackerHosts", JSONArray(filter.trackerHosts.toList()))
            .put("maliciousHosts", JSONArray(filter.maliciousHosts.toList()))
            .put("keywordRules", keywords)
            .put("blockAds", filter.blockAds)
            .put("blockTrackers", filter.blockTrackers)
            .put("blockCrossSite", filter.blockCrossSite)
            .put("blockMalicious", filter.blockMalicious)
            .put("shieldsDisabledHosts", JSONArray(filter.shieldsDisabledHosts.toList()))
        runCatching { port.postMessage(message) }.onFailure { error ->
            android.util.Log.e(
                BRIDGE_LOG_TAG,
                "could not push the resource filter to the blocker: it keeps the " +
                    "previous rules until the next push",
                error
            )
        }
    }

    override fun setResourceFilter(filter: ResourceFilter, blocked: BlockedResourceSink) {
        resourceFilter = filter
        blockedSink = blocked
        // A port that is not open yet is not a lost push: [blockerDelegate]
        // sends the filter on connect. The reverse order -- port first, filter
        // second -- is this line.
        val port = blockerPort ?: return
        postToMain { pushFilter(port) }
    }

    override fun boundProfile(): ProfileId? = bound

    override fun setActivityDelegate(delegate: EngineActivityDelegate?) {
        activityDelegate = delegate
        val active = runtime ?: return
        // @UiThread on the engine's side, and the app calls this from
        // `onCreate`, so onMainThread runs it inline there and hops on the
        // withdrawal path, which can arrive from any thread.
        onMainThread { applyActivityDelegate(active) }
    }

    /**
     * Build the engine's delegate out of the app's, and install it.
     *
     * THE ADAPTER IS NOT CEREMONY. GeckoView's contract is
     * `GeckoResult<Intent>` and it states that a result which is not
     * `RESULT_OK` MUST be completed with an exception -- so the app's "the
     * user cancelled, here is null" has to be translated rather than forwarded,
     * or a cancelled prompt would be reported to the page as an activity that
     * succeeded and returned nothing.
     */
    private fun applyActivityDelegate(active: GeckoRuntime) {
        val delegated = activityDelegate
        if (delegated == null) {
            active.setActivityDelegate(null)
            return
        }
        active.setActivityDelegate(
            GeckoRuntime.ActivityDelegate { pendingIntent ->
                val result = GeckoResult<Intent>()
                postToMain {
                    delegated.startIntentSenderForResult(pendingIntent.intentSender) { data ->
                        if (data != null) {
                            result.complete(data)
                        } else {
                            result.completeExceptionally(
                                IllegalStateException("activity result was cancelled")
                            )
                        }
                    }
                }
                result
            }
        )
    }

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
        val active = runtime
        check(active != null) { "bind() must be called before createSession()" }
        check(Looper.myLooper() != null) {
            "EngineHost.createSession must run on a Looper thread (the main thread)"
        }

        val session = GeckoEngineSession(
            id = sessionId,
            runtime = active,
            context = context,
            profile = profile,
            isPrivate = isPrivate
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

    override fun clearCache(context: Context, profileId: ProfileId) {
        check(bound == profileId) { "Process not bound to ${profileId.value}" }
        val active = runtime ?: return
        // ALL_CACHES, never ALL: this is the "Cache" checkbox, and ALL would
        // also drop cookies and site data.
        active.storageController
            .clearData(StorageController.ClearFlags.ALL_CACHES)
            .accept({ }, { error ->
                android.util.Log.e("GeckoEngineHost", "clearData(ALL_CACHES) failed", error)
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
        blockerPort = null
        resourceFilter = null
        blockedSink = null
        activityDelegate = null
        synchronized(bridgeWaiters) { bridgeWaiters.clear() }
    }

    private companion object {
        const val TAG = "RoomProxy"

        /** A pref service that never answers must not hold the profile bind open. */
        const val PROXY_APPLY_TIMEOUT_MS = 5_000L

        /** Matches `browser_specific_settings.gecko.id` in the manifest. */
        const val BRIDGE_ID = "roombridge@roombrowser.com"

        /** WebAuthn's master switch, `[Pref=...]` on the WebIDL interfaces. */
        const val WEBAUTHN_PREF = "security.webauth.webauthn"

        /** 0 = no proxy, 1 = manual. Set LAST, so the endpoint is in place before it is read. */
        const val PROXY_TYPE = "network.proxy.type"

        /** Every pref [setProxy] writes, so returning to direct clears the branch and not just the mode. */
        val PROXY_MANAGED_PREFS = listOf(
            "network.proxy.http",
            "network.proxy.http_port",
            "network.proxy.ssl",
            "network.proxy.ssl_port",
            "network.proxy.share_proxy_settings",
            "network.proxy.socks",
            "network.proxy.socks_port",
            "network.proxy.socks_version",
            "network.proxy.socks_remote_dns",
            "network.proxy.allow_hijacking_localhost"
        )

        /**
         * The native-app name the BACKGROUND page's port is opened under.
         *
         * Deliberately not `roombridge`: that name is the content scripts', and
         * GeckoView looks a port's delegate up by (extension, nativeApp) --
         * content-script ports on the session, background ports on the
         * extension. Two different lookups under one name would work, and
         * would make every log line ambiguous about which half of the bridge
         * it came from. Must match `NATIVE_APP` in `blocker.js`.
         */
        const val BLOCKER_NATIVE_APP = "roomblock"

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
