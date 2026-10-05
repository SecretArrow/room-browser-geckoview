package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.model.DnsMode
import com.roombrowser.domain.model.ProfileSettings
import com.roombrowser.domain.model.UaMode
import org.junit.Test

/**
 * The app-control vocabulary decides three things the Android side cannot
 * test: which action names exist, which of them ask a person every single
 * time, and which names may be written to a profile's settings. A typo in any
 * of them is invisible on screen — the tool just does nothing, or quietly
 * skips the confirmation.
 */
class AgentAppActionsTest {

    @Test
    fun every_data_kind_declares_its_own_actions() {
        assertThat(AgentAppActions.DATA_ACTIONS.keys).containsExactlyElementsIn(AgentAppActions.DATA_KINDS)
        AgentAppActions.DATA_ACTIONS.forEach { (_, actions) ->
            assertThat(actions).isNotEmpty()
            assertThat(actions).contains("list")
            assertThat(actions.contains("")).isFalse()
        }
    }

    @Test
    fun the_actions_that_lose_something_are_the_destructive_ones() {
        // Each of these throws away something the user cannot get back, so each
        // must ask a person every time rather than ride the confirmation switch.
        assertThat(AgentAppActions.isDestructive(AgentTools.APP_TABS, "close_others")).isTrue()
        assertThat(AgentAppActions.isDestructive(AgentTools.APP_DATA, "clear")).isTrue()
        assertThat(AgentAppActions.isDestructive(AgentTools.APP_DATA, "remove")).isTrue()
        assertThat(AgentAppActions.isDestructive(AgentTools.APP_DATA, "cancel")).isTrue()
        assertThat(AgentAppActions.isDestructive(AgentTools.APP_SHIELDS, "clear_site_data")).isTrue()
    }

    @Test
    fun reading_and_reversible_changes_are_not_destructive() {
        assertThat(AgentAppActions.isDestructive(AgentTools.APP_OPEN, "settings")).isFalse()
        assertThat(AgentAppActions.isDestructive(AgentTools.APP_TABS, "list")).isFalse()
        assertThat(AgentAppActions.isDestructive(AgentTools.APP_TABS, "pin")).isFalse()
        assertThat(AgentAppActions.isDestructive(AgentTools.APP_DATA, "list")).isFalse()
        assertThat(AgentAppActions.isDestructive(AgentTools.APP_DATA, "pause")).isFalse()
        assertThat(AgentAppActions.isDestructive(AgentTools.APP_SETTINGS, "set")).isFalse()
        assertThat(AgentAppActions.isDestructive(AgentTools.APP_SHIELDS, "toggle")).isFalse()
        assertThat(AgentAppActions.isDestructive(AgentTools.APP_SITE_PERMISSION, "set")).isFalse()
        assertThat(AgentAppActions.isDestructive(AgentTools.APP_PAGE, "bookmark_add")).isFalse()
        assertThat(AgentAppActions.isDestructive(AgentTools.APP_PAGE, "bookmark_remove")).isFalse()
    }

    @Test
    fun an_action_nobody_declared_is_not_treated_as_destructive() {
        assertThat(AgentAppActions.isDestructive(AgentTools.APP_DATA, "banana")).isFalse()
        assertThat(AgentAppActions.isDestructive(AgentTools.APP_DATA, null)).isFalse()
        assertThat(AgentAppActions.isDestructive("run_shell", "clear")).isFalse()
    }

    @Test
    fun every_destructive_action_is_also_a_write() {
        // The executor checks destruction first, so a destructive action that
        // read as "not a write" would still ask — but the two answers disagreeing
        // is how a later refactor loses the ask, and this is the cheapest place
        // to notice.
        val cases = listOf(
            AgentTools.APP_TABS to "close_others",
            AgentTools.APP_DATA to "clear",
            AgentTools.APP_DATA to "remove",
            AgentTools.APP_DATA to "cancel",
            AgentTools.APP_SHIELDS to "clear_site_data"
        )
        cases.forEach { (tool, action) ->
            assertThat(AgentAppActions.isDestructive(tool, action)).isTrue()
            assertThat(AgentAppActions.isWrite(tool, action)).isTrue()
        }
    }

    @Test
    fun listings_and_reads_are_not_writes() {
        // The Confirm actions switch is for state: an agent that has to be
        // approved once per listing gets the switch turned off, and then
        // nothing is approved at all.
        assertThat(AgentAppActions.isWrite(AgentTools.APP_OPEN, "history")).isFalse()
        assertThat(AgentAppActions.isWrite(AgentTools.APP_TABS, "list")).isFalse()
        assertThat(AgentAppActions.isWrite(AgentTools.APP_DATA, "list")).isFalse()
        assertThat(AgentAppActions.isWrite(AgentTools.APP_DATA, "search")).isFalse()
        assertThat(AgentAppActions.isWrite(AgentTools.APP_SETTINGS, "get")).isFalse()
        assertThat(AgentAppActions.isWrite(AgentTools.APP_SHIELDS, "read")).isFalse()
        assertThat(AgentAppActions.isWrite(AgentTools.APP_SITE_PERMISSION, "list")).isFalse()
        assertThat(AgentAppActions.isWrite(AgentTools.APP_PAGE, "find")).isFalse()
        assertThat(AgentAppActions.isWrite(AgentTools.APP_PAGE, "reader_on")).isFalse()
    }

    @Test
    fun the_changes_are_writes() {
        assertThat(AgentAppActions.isWrite(AgentTools.APP_TABS, "pin")).isTrue()
        assertThat(AgentAppActions.isWrite(AgentTools.APP_TABS, "private")).isTrue()
        assertThat(AgentAppActions.isWrite(AgentTools.APP_DATA, "add")).isTrue()
        assertThat(AgentAppActions.isWrite(AgentTools.APP_DATA, "remove")).isTrue()
        assertThat(AgentAppActions.isWrite(AgentTools.APP_SETTINGS, "set")).isTrue()
        assertThat(AgentAppActions.isWrite(AgentTools.APP_SHIELDS, "toggle")).isTrue()
        assertThat(AgentAppActions.isWrite(AgentTools.APP_SITE_PERMISSION, "set")).isTrue()
        assertThat(AgentAppActions.isWrite(AgentTools.APP_PAGE, "desktop_on")).isTrue()
        assertThat(AgentAppActions.isWrite(AgentTools.APP_PAGE, "bookmark_remove")).isTrue()
    }

    @Test
    fun permission_decisions_are_the_three_the_storage_knows() {
        assertThat(AgentAppActions.PERMISSION_DECISIONS).containsExactly("ask", "allow", "block")
        assertThat(AgentAppActions.PERMISSIONS).containsAtLeast(
            "camera", "microphone", "location", "notifications"
        )
        assertThat(AgentAppActions.PERMISSIONS).isNotEmpty()
    }

    @Test
    fun the_screens_are_the_two_kinds_and_nothing_is_both() {
        assertThat(AgentAppActions.IN_APP_ROUTES.intersect(AgentAppActions.APP_ACTIVITIES)).isEmpty()
        assertThat(AgentAppActions.SCREENS)
            .containsExactlyElementsIn(AgentAppActions.IN_APP_ROUTES + AgentAppActions.APP_ACTIVITIES)
        // Profile creation, renaming, deletion and SWITCHING are deliberately
        // not reachable: switching restarts the ':browser' process the turn
        // itself runs in.
        assertThat(AgentAppActions.SCREENS).containsNoneOf("profiles", "profile_switch")
    }

    @Test
    fun the_app_tools_are_the_seven_the_schema_declares() {
        assertThat(AgentAppActions.TOOLS).containsExactly(
            AgentTools.APP_OPEN, AgentTools.APP_TABS, AgentTools.APP_DATA,
            AgentTools.APP_SETTINGS, AgentTools.APP_SHIELDS,
            AgentTools.APP_SITE_PERMISSION, AgentTools.APP_PAGE
        )
        assertThat(AgentAppActions.TOOLS).hasSize(7)
    }

    @Test
    fun the_setting_whitelist_has_no_blank_names() {
        assertThat(AgentAppActions.PROFILE_SETTINGS).isNotEmpty()
        AgentAppActions.PROFILE_SETTINGS.forEach { (name, kind) ->
            assertThat(name.trim()).isEqualTo(name)
            assertThat(name).isNotEmpty()
            assertThat(AgentAppActions.setting(name)).isEqualTo(kind)
        }
        assertThat(AgentAppActions.setting("javascriptenabled")).isNull()
        assertThat(AgentAppActions.setting(null)).isNull()
    }

    @Test
    fun every_writable_setting_can_be_read_back_and_described() {
        val settings = ProfileSettings()
        AgentAppActions.PROFILE_SETTINGS.keys.forEach { name ->
            assertThat(AgentAppActions.readSetting(settings, name)).isNotNull()
        }
        val described = AgentAppActions.describeSettings(settings)
        AgentAppActions.PROFILE_SETTINGS.keys.forEach { name ->
            assertThat(described).contains("$name = ")
        }
        assertThat(AgentAppActions.readSetting(settings, "nope")).isNull()
    }

    @Test
    fun a_switch_takes_true_or_false_and_refuses_anything_else() {
        val settings = ProfileSettings()
        val on = AgentAppActions.applySetting(settings, "blockAds", "true")
        assertThat(on).isInstanceOf(AgentAppActions.SettingWrite.Applied::class.java)
        assertThat(
            (on as AgentAppActions.SettingWrite.Applied).settings.blockAds
        ).isTrue()

        val off = AgentAppActions.applySetting(settings, "blockAds", "off")
        assertThat(off).isInstanceOf(AgentAppActions.SettingWrite.Applied::class.java)
        assertThat(
            (off as AgentAppActions.SettingWrite.Applied).settings.blockAds
        ).isFalse()

        val refused = AgentAppActions.applySetting(settings, "blockAds", "maybe")
        assertThat(refused).isInstanceOf(AgentAppActions.SettingWrite.Refused::class.java)
        assertThat((refused as AgentAppActions.SettingWrite.Refused).reason).contains("true or false")
    }

    @Test
    fun a_text_setting_refuses_an_empty_value_and_an_unknown_name() {
        val settings = ProfileSettings()
        assertThat(AgentAppActions.applySetting(settings, "searchEngineId", " "))
            .isInstanceOf(AgentAppActions.SettingWrite.Refused::class.java)
        assertThat(AgentAppActions.applySetting(settings, "noSuchSetting", "x"))
            .isInstanceOf(AgentAppActions.SettingWrite.Refused::class.java)
    }

    @Test
    fun the_coupled_settings_carry_their_partner() {
        // A profile carries ONE identity, so a custom user agent clears the
        // device; and an address under another DNS mode is never read, so
        // setting one selects its mode. These are the two ways a settings write
        // from a name the model guessed could otherwise leave dead state behind.
        val settings = ProfileSettings()
        val custom = AgentAppActions.applySetting(settings, "customUserAgent", "Mozilla/5.0 (X11)")
        val afterCustom = (custom as AgentAppActions.SettingWrite.Applied).settings
        assertThat(afterCustom.uaMode).isEqualTo(UaMode.CUSTOM)
        assertThat(afterCustom.deviceId).isNull()
        assertThat(afterCustom.uaPresetId).isNull()

        val doh = AgentAppActions.applySetting(settings, "dohUrl", "https://doh.example/dns-query")
        assertThat((doh as AgentAppActions.SettingWrite.Applied).settings.dnsMode)
            .isEqualTo(DnsMode.DOH)

        val cleared = AgentAppActions.applySetting(settings, "dohUrl", " ")
        val afterCleared = (cleared as AgentAppActions.SettingWrite.Applied).settings
        assertThat(afterCleared.dohUrl).isNull()
        assertThat(afterCleared.dnsMode).isEqualTo(DnsMode.SYSTEM)
    }
}
