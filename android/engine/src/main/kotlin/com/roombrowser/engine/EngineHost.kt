package com.roombrowser.engine

import android.content.Context
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId

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
     */
    fun createSession(context: Context, profile: Profile, sessionId: String): EngineSession

    /**
     * Apply [profile]'s settings to a live session. Safe to call repeatedly:
     * the app re-runs it whenever settings change, so an implementation must
     * replace prior configuration rather than stack it.
     */
    fun configure(session: EngineSession, profile: Profile)

    /** Per-session desktop-mode toggle. */
    fun applyDesktopMode(session: EngineSession, profile: Profile, desktop: Boolean)

    /**
     * Erase all engine storage for the bound profile.
     *
     * Refuses -- rather than silently clearing the wrong profile -- when this
     * process is not bound to [profileId], because the directories involved
     * are per-profile.
     */
    fun clearBrowsingData(context: Context, profileId: ProfileId)

    /** Remove the profile's engine directories from disk. */
    fun wipeProfileData(context: Context, profileId: ProfileId)

    /** Storage footprint in bytes, for the diagnostics screen. */
    fun storageBytes(context: Context, profileId: ProfileId): Long

    /**
     * Release process-wide engine resources. After this the host is unusable;
     * it exists for tests and for the process-exit path, not for profile
     * switching (which restarts the process instead).
     */
    fun shutdown()
}
