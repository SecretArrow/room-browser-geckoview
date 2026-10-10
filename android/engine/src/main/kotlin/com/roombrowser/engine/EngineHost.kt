package com.roombrowser.engine

import android.content.Context
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.proxy.ProxyScheme

/**
 * One entry in the diagnostics screen's engine list.
 *
 * WebView has several interchangeable providers installed on a device and the
 * screen lists them; GeckoView ships inside this APK and has exactly one. The
 * list shape covers both without the screen knowing which edition it is
 * running in.
 */
data class EngineOption(
    val packageName: String,
    val versionName: String,
    val isCurrent: Boolean
)

/**
 * The engine, as the rest of the app is allowed to see it.
 *
 * ONE PROCESS, ONE PROFILE. This is not an implementation detail that leaked
 * into the interface -- it is the isolation model, and it is the same in both
 * editions for the same reason. WebView's data directory suffix is settable
 * exactly once per process; GeckoView's runtime likewise owns one profile
 * directory for the life of the process. So [bind] succeeds the first time,
 * refuses a second bind to a DIFFERENT profile, and the caller's response to
 * that refusal is to restart the process. See PROFILE_ISOLATION.md.
 *
 * Implementations must be safe to reach from multiple threads. The app
 * configures a profile from the main thread and reads storage sizes from a
 * worker.
 */
interface EngineHost {

    /** Human-readable engine identity for the diagnostics screen. */
    fun engineName(context: Context): String

    /** Every engine provider this device offers, current one flagged. */
    fun engineOptions(context: Context): List<EngineOption>

    /**
     * The user agent this engine sends when a profile chooses no identity of its
     * own -- the string the "What's my IP" screen reports under "engine default".
     *
     * The engine is the only thing that knows it, which is why it is asked rather
     * than reconstructed here: the WebView edition carries the device's engine
     * version, and a second implementation of that answer would drift from the
     * version a site actually sees.
     *
     * Null when the engine cannot say yet (GeckoView's answer comes from a runtime
     * that may not be up), and the screen reports that as unknown rather than
     * inventing a string.
     */
    fun defaultUserAgent(context: Context): String?

    /**
     * Bind this process to [profileId]. Must be called before the first
     * session is created.
     *
     * Returns true when the process is (now) bound to this profile. Returns
     * false when it is already bound to a DIFFERENT one -- the caller must
     * restart the process rather than proceed, because the engine's on-disk
     * state is already committed to the other profile.
     */
    fun bind(context: Context, profileId: ProfileId): Boolean

    /** The profile this process is bound to, or null before [bind]. */
    fun boundProfile(): ProfileId?

    /**
     * Create a session for the bound profile. Throws when the process is not
     * bound to [profile]'s id, mirroring the check the WebView edition's
     * factory already performs.
     *
     * [sessionId] is the app's own identity for the tab being created, not
     * anything the engine derives or chooses. It is a parameter rather than
     * something the engine invents because the app reads it back off the
     * session to answer "which tab owns this engine?" from a background
     * thread, and a value the engine made up could not answer that.
     *
     * [isPrivate] says whether the tab this session serves is a private one,
     * and it has NO default: the app tracks privacy on the tab, so a caller
     * that omits the argument would be asking for a session whose isolation
     * nobody chose. This parameter exists because privacy is not something the
     * engine can infer and not something the app can fix after the fact --
     * GeckoView decides a session's storage context when the session is
     * CONSTRUCTED, so a session built without knowing it is private is a
     * private tab writing to the profile's on-disk jar from its first request.
     * It is deliberately a plain Boolean and not a new type, unlike the
     * desktop-mode pair: there is one bit here and no second value it must
     * agree with.
     */
    fun createSession(
        context: Context,
        profile: Profile,
        sessionId: String,
        isPrivate: Boolean
    ): EngineSession

    /**
     * Apply [profile]'s settings to a live session. Safe to call repeatedly:
     * the app re-runs it whenever settings change, so an implementation must
     * replace prior configuration rather than stack it.
     */
    fun configure(session: EngineSession, profile: Profile)

    /** Per-session desktop-mode toggle. */
    fun applyDesktopMode(session: EngineSession, profile: Profile, desktop: Boolean)

    /**
     * Hand the current sub-resource filter to an engine that decides
     * sub-resources in its OWN process, and tell it where to report what it
     * blocked.
     *
     * WHO NEEDS THIS. Only an engine with no per-request hook on the app side.
     * Android WebView reports every sub-resource to `shouldInterceptRequest`,
     * so the WebView edition decides in the app and implements this as a
     * documented no-op -- its [blocked] sink is never called because the app
     * reports those blocks itself, at the moment it makes the decision.
     * GeckoView has no such hook, so the filter travels to a WebExtension
     * whose `webRequest.onBeforeRequest` listener applies it, and [blocked] is
     * how the decisions come back.
     *
     * Called on every settings change and every site-settings change, so an
     * implementation must REPLACE the previous filter rather than accumulate
     * one, and must tolerate being called before any session exists.
     *
     * [blocked] describes a block that has ALREADY happened; an implementation
     * makes no promise about how promptly it arrives, and must not deliver a
     * report for a request it allowed.
     */
    fun setResourceFilter(filter: ResourceFilter, blocked: BlockedResourceSink)

    /**
     * Hand the engine a way to open another app's window and read its answer.
     *
     * Needed by an engine that starts system UI on the page's behalf and gets
     * the result back through the app's Activity -- GeckoView's passkey
     * requests are that case, and without a delegate they fail rather than
     * being refused. The WebView edition answers its own prompts inside the
     * app, so it implements this as a documented no-op.
     *
     * EXPECTED TO BE CALLED BEFORE [bind], AND THAT ORDER IS THE POINT. The app
     * installs its delegate in `onCreate` and binds in the same method, while
     * the engine's runtime is created BY [bind]; a delegate that arrives first
     * must therefore be REMEMBERED and applied when the runtime appears rather
     * than dropped. Null withdraws it, for the case where the Activity that
     * owned it is going away and should not be launched into afterwards.
     */
    fun setActivityDelegate(delegate: EngineActivityDelegate?)

    /**
     * Whether this engine can be pointed at a proxy at all.
     *
     * WebView answers from the provider's own feature set and returns false on a device
     * whose provider predates the override API; GeckoView always answers true. The
     * settings screen shows the truthful answer rather than a switch that does nothing.
     */
    fun proxySupported(): Boolean

    /**
     * Whether this engine's proxy mechanism can carry [scheme] at all.
     *
     * WebView's override speaks HTTP and HTTPS; Necko also speaks SOCKS. A candidate the
     * engine cannot carry is refused here rather than applied and silently ignored, which
     * would leave the settings screen claiming a proxy that is not there.
     */
    fun proxySupportsScheme(scheme: ProxyScheme): Boolean

    /**
     * Send this engine's page traffic through [config], or back to the direct network
     * when it is null.
     *
     * Suspends until the engine has ACCEPTED the change, because both mechanisms apply
     * it asynchronously and a page loaded before that point goes out over the real
     * address — the one outcome the setting promises not to have.
     *
     * Replace, never accumulate: this is called at every profile bind, so a profile with
     * no proxy clears whatever the previous one left behind. Suspending work is bounded
     * by the implementation; it must not hang the caller indefinitely.
     */
    suspend fun setProxy(config: EngineProxyConfig?)

    /**
     * Erase all engine storage for the bound profile.
     *
     * Refuses -- rather than silently clearing the wrong profile -- when this
     * process is not bound to [profileId], because the directories involved
     * are per-profile.
     */
    fun clearBrowsingData(context: Context, profileId: ProfileId)

    /**
     * Erase only the bound profile's HTTP and image caches, leaving cookies,
     * site data and permissions alone.
     *
     * The narrower sibling of [clearBrowsingData], for the "Cache" checkbox:
     * dropping a cache is a routine action a user may take repeatedly, while
     * erasing everything signs them out of every site. Refuses, like
     * [clearBrowsingData], when this process is not bound to [profileId].
     */
    fun clearCache(context: Context, profileId: ProfileId)

    /** Remove the profile's engine directories from disk. */
    fun wipeProfileData(context: Context, profileId: ProfileId)

    /** Storage footprint in bytes, for the diagnostics screen. */
    fun storageBytes(context: Context, profileId: ProfileId): Long

    /**
     * Commit any engine state still held in memory to disk.
     *
     * The app's last act before switching profiles is to kill its own process
     * (`Process.killProcess`), with no orderly shutdown to hang a save on. An
     * engine that buffers writes therefore loses them. WebView does -- cookies
     * live in memory until flushed -- so before this member existed the app
     * called `CookieManager.flush()` directly, and the conversion left that
     * call with nothing to call.
     *
     * An engine that writes through to disk as it goes implements this as a
     * no-op, and that is a truthful implementation rather than a placeholder.
     * It must not be used as a general "save everything" hook: it runs on the
     * process-death path and is not a checkpoint.
     */
    fun flush(context: Context)

    /**
     * Release process-wide engine resources. After this the host is unusable;
     * it exists for tests and for the process-exit path, not for profile
     * switching (which restarts the process instead).
     */
    fun shutdown()
}
