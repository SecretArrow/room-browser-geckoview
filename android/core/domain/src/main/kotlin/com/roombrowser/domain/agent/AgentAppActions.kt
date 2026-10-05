package com.roombrowser.domain.agent

import com.roombrowser.domain.model.DnsMode
import com.roombrowser.domain.model.ProfileSettings
import com.roombrowser.domain.model.UaMode

/**
 * The vocabulary of the app-control tools: which screens and actions exist,
 * and which of them cannot be undone.
 *
 * WHY it is a pure object: the Android code that carries these out cannot be
 * unit-tested, so the accepted values and the destructive set are kept where a
 * JVM test can hold them. A typo in an action name would otherwise surface as
 * a tool that silently does nothing.
 */
object AgentAppActions {

    /** The tools this vocabulary belongs to. */
    val TOOLS = setOf(
        AgentTools.APP_OPEN, AgentTools.APP_TABS, AgentTools.APP_DATA,
        AgentTools.APP_SETTINGS, AgentTools.APP_SHIELDS,
        AgentTools.APP_SITE_PERMISSION, AgentTools.APP_PAGE
    )

    /** Per-profile browsing settings, the only scope the agent may write. */
    const val SCOPE_PROFILE = "profile"

    /**
     * Screens the browser activity itself shows, as Compose routes.
     * "home" is the browsing surface together with the start page.
     */
    val IN_APP_ROUTES = setOf(
        "home", "tabs", "bookmarks", "history", "downloads", "privacy",
        "settings", "profile_settings", "about"
    )

    /** Screens that are an activity of their own. */
    val APP_ACTIVITIES = setOf(
        "theme", "agent_settings", "agent_chats", "ai_tasks", "local_ai",
        "passwords", "wallet", "devices"
    )

    /**
     * Every screen [AgentTools.APP_OPEN] accepts. There is deliberately no
     * "profiles" entry: profile creation, renaming and deletion are not the
     * agent's to do, and SWITCHING profile restarts the ':browser' process,
     * which is the process the agent turn is running in.
     */
    val SCREENS = IN_APP_ROUTES + APP_ACTIVITIES

    val TAB_ACTIONS = setOf(
        "list", "pin", "unpin", "duplicate", "reopen", "close_others", "move",
        "group", "ungroup", "private"
    )

    val DATA_KINDS = setOf("bookmarks", "history", "downloads")

    val DATA_ACTIONS: Map<String, Set<String>> = mapOf(
        "bookmarks" to setOf("list", "add", "remove"),
        "history" to setOf("list", "search", "remove", "clear"),
        "downloads" to setOf("list", "pause", "resume", "cancel", "retry", "open", "remove")
    )

    val SHIELD_ACTIONS = setOf("read", "toggle", "clear_site_data")

    val PERMISSION_ACTIONS = setOf("list", "set")

    val PERMISSIONS = setOf(
        "camera", "microphone", "location", "notifications", "clipboard",
        "bluetooth", "usb", "popups", "downloads", "sensors", "autoplay"
    )

    val PERMISSION_DECISIONS = setOf("ask", "allow", "block")

    val PAGE_ACTIONS = setOf(
        "find", "find_next", "find_previous", "clear_find",
        "reader_on", "reader_off", "desktop_on", "desktop_off",
        "bookmark_add", "bookmark_remove"
    )

    enum class SettingKind { BOOL, TEXT }

    /** The outcome of writing one setting by name. */
    sealed interface SettingWrite {
        data class Applied(val settings: ProfileSettings) : SettingWrite
        data class Refused(val reason: String) : SettingWrite
    }

    /** One whitelisted setting: what it is, how to read it, how to write it. */
    private class Access(
        val kind: SettingKind,
        val read: (ProfileSettings) -> String,
        val write: (ProfileSettings, String) -> SettingWrite
    )

    private fun boolSwitch(
        name: String,
        read: (ProfileSettings) -> Boolean,
        write: (ProfileSettings, Boolean) -> ProfileSettings
    ): Pair<String, Access> = name to Access(
        kind = SettingKind.BOOL,
        read = { if (read(it)) "true" else "false" },
        write = { settings, raw ->
            when (raw.trim().lowercase()) {
                "true", "yes", "on", "1" -> SettingWrite.Applied(write(settings, true))
                "false", "no", "off", "0" -> SettingWrite.Applied(write(settings, false))
                else -> SettingWrite.Refused("'$name' takes true or false, not '$raw'")
            }
        }
    )

    private fun text(
        name: String,
        read: (ProfileSettings) -> String,
        write: (ProfileSettings, String) -> ProfileSettings
    ): Pair<String, Access> = name to Access(
        kind = SettingKind.TEXT,
        read = read,
        write = { settings, raw ->
            if (raw.isBlank()) {
                SettingWrite.Refused("'$name' cannot be set to nothing")
            } else {
                SettingWrite.Applied(write(settings, raw.trim()))
            }
        }
    )

    private fun optionalText(
        name: String,
        read: (ProfileSettings) -> String?,
        write: (ProfileSettings, String?) -> ProfileSettings
    ): Pair<String, Access> = name to Access(
        kind = SettingKind.TEXT,
        read = { read(it).orEmpty() },
        write = { settings, raw -> SettingWrite.Applied(write(settings, raw.trim().ifBlank { null })) }
    )

    /**
     * Every per-profile setting the agent may read or write, by name, together
     * with the accessors that do it. Deliberately a WHITELIST: the settings
     * blob has ~40 fields, several of them enum-valued (theme, tab layout,
     * device, UA mode, DNS mode), and writing one from a name the model
     * guessed is corruption the user cannot see. Those stay in the settings
     * screen.
     *
     * The coupled entries carry their partner with them, exactly as the
     * settings screen does: a custom user agent clears the device, because a
     * profile carries ONE identity and the UA is then the whole answer; and a
     * DoH/DoT address selects its own DNS mode, because an address under
     * another mode is never read. An emptied address returns the profile to
     * the system resolver rather than leaving a dead value behind.
     */
    private val ACCESS: Map<String, Access> = listOf(
        boolSwitch("javascriptEnabled", { it.javascriptEnabled }) { s, v -> s.copy(javascriptEnabled = v) },
        boolSwitch("blockAds", { it.blockAds }) { s, v -> s.copy(blockAds = v) },
        boolSwitch("blockTrackers", { it.blockTrackers }) { s, v -> s.copy(blockTrackers = v) },
        boolSwitch("blockCrossSiteTrackers", { it.blockCrossSiteTrackers }) { s, v ->
            s.copy(blockCrossSiteTrackers = v)
        },
        boolSwitch("blockPopups", { it.blockPopups }) { s, v -> s.copy(blockPopups = v) },
        boolSwitch("blockMalicious", { it.blockMalicious }) { s, v -> s.copy(blockMalicious = v) },
        boolSwitch("httpsUpgrade", { it.httpsUpgrade }) { s, v -> s.copy(httpsUpgrade = v) },
        boolSwitch("blockThirdPartyCookies", { it.blockThirdPartyCookies }) { s, v ->
            s.copy(blockThirdPartyCookies = v)
        },
        boolSwitch("blockMixedContent", { it.blockMixedContent }) { s, v -> s.copy(blockMixedContent = v) },
        boolSwitch("searchSuggestions", { it.searchSuggestions }) { s, v -> s.copy(searchSuggestions = v) },
        boolSwitch("desktopModeDefault", { it.desktopModeDefault }) { s, v -> s.copy(desktopModeDefault = v) },
        boolSwitch("autofillEnabled", { it.autofillEnabled }) { s, v -> s.copy(autofillEnabled = v) },
        boolSwitch("homepageEnabled", { it.homepageEnabled }) { s, v -> s.copy(homepageEnabled = v) },
        boolSwitch("showPrivacyStats", { it.showPrivacyStats }) { s, v -> s.copy(showPrivacyStats = v) },
        boolSwitch("showRecentSites", { it.showRecentSites }) { s, v -> s.copy(showRecentSites = v) },
        boolSwitch("showClock", { it.showClock }) { s, v -> s.copy(showClock = v) },
        boolSwitch("reducedMotion", { it.reducedMotion }) { s, v -> s.copy(reducedMotion = v) },
        boolSwitch("highContrast", { it.highContrast }) { s, v -> s.copy(highContrast = v) },
        text("searchEngineId", { it.searchEngineId }) { s, v -> s.copy(searchEngineId = v) },
        text("translateTargetLanguage", { it.translateTargetLanguage }) { s, v ->
            s.copy(translateTargetLanguage = v)
        },
        text("downloadSubfolder", { it.downloadSubfolder }) { s, v -> s.copy(downloadSubfolder = v) },
        text("customUserAgent", { it.customUserAgent.orEmpty() }) { s, v ->
            s.copy(customUserAgent = v, deviceId = null, uaMode = UaMode.CUSTOM, uaPresetId = null)
        },
        optionalText("dohUrl", { it.dohUrl }) { s, v ->
            s.copy(dohUrl = v, dnsMode = if (v == null) DnsMode.SYSTEM else DnsMode.DOH)
        },
        optionalText("dotHostname", { it.dotHostname }) { s, v ->
            s.copy(dotHostname = v, dnsMode = if (v == null) DnsMode.SYSTEM else DnsMode.DOT)
        }
    ).toMap()

    /** Derived from [ACCESS], so a name can never exist without an accessor. */
    val PROFILE_SETTINGS: Map<String, SettingKind> = ACCESS.mapValues { it.value.kind }

    fun setting(name: String?): SettingKind? = PROFILE_SETTINGS[name]

    /** The current value of one setting, or null when no such name is writable. */
    fun readSetting(settings: ProfileSettings, name: String): String? =
        ACCESS[name]?.read?.invoke(settings)

    /** Every writable setting as `name = value`, one per line. */
    fun describeSettings(settings: ProfileSettings): String =
        ACCESS.entries.joinToString("\n") { "${it.key} = ${it.value.read(settings)}" }

    /** Writes one setting by name; see [SettingWrite]. */
    fun applySetting(settings: ProfileSettings, name: String, raw: String): SettingWrite =
        ACCESS[name]?.write?.invoke(settings, raw)
            ?: SettingWrite.Refused("'$name' is not a setting the agent may change")

    /**
     * Whether an action destroys something the user cannot get back.
     *
     * These are the ones that ask a person EVERY time, however the app's
     * confirmation switch is set and whatever YOLO mode says: closing tabs the
     * user opened, clearing history, removing a history entry or cancelling a
     * download, and wiping the profile's stored site data. Removing a BOOKMARK
     * is deliberately not here — it is restorable by the same tool, which is
     * what separates it from these.
     */
    fun isDestructive(toolName: String, action: String?): Boolean = when (toolName) {
        AgentTools.APP_TABS -> action == "close_others"
        AgentTools.APP_DATA -> when (action) {
            "clear" -> true
            "remove", "cancel" -> true
            else -> false
        }
        AgentTools.APP_SHIELDS -> action == "clear_site_data"
        else -> false
    }

    /**
     * Whether an action changes anything, as opposed to reading it out.
     *
     * The Confirm actions switch is for state, so a listing must not raise a
     * prompt: an agent that has to be approved once per `app_data list` gets
     * the switch turned off, and then nothing is approved at all. Reading and
     * looking (find, reader mode, opening a screen) is not a change here.
     */
    fun isWrite(toolName: String, action: String?): Boolean = when (toolName) {
        AgentTools.APP_OPEN -> false
        AgentTools.APP_TABS -> action != "list"
        AgentTools.APP_DATA -> action != "list" && action != "search"
        AgentTools.APP_SETTINGS -> action == "set"
        AgentTools.APP_SHIELDS -> action != "read"
        AgentTools.APP_SITE_PERMISSION -> action == "set"
        AgentTools.APP_PAGE -> action in setOf("desktop_on", "desktop_off", "bookmark_add", "bookmark_remove")
        else -> false
    }
}
