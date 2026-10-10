package com.roombrowser.devtools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The dock/tab invariant behind the panel's "Developer Tools looked broken"
 * states: an open surface always names a tab, and the tab going away takes the
 * surface with it.
 *
 * Every path here is reachable without an engine — the inspector registry only
 * builds an [InspectorSession] once a real one is attached, which is exactly
 * the difference between "this tab has no engine yet" (stay open) and "this tab
 * is gone" (close).
 */
class DeveloperToolsManagerTest {

    @Test
    fun a_tab_with_no_engine_yet_keeps_the_surface_open() {
        val manager = DeveloperToolsManager()
        manager.open("A")
        // The start page, or an engine being rebuilt under a live tab.
        assertThat(manager.attach("A", null)).isNull()
        assertThat(manager.isOpen).isTrue()
        assertThat(manager.tabId).isEqualTo("A")
    }

    @Test
    fun closing_the_inspected_tab_closes_the_surface() {
        val manager = DeveloperToolsManager()
        manager.open("A")
        manager.onTabClosed("A")
        assertThat(manager.isOpen).isFalse()
        assertThat(manager.tabId).isNull()
    }

    @Test
    fun closing_another_tab_leaves_the_surface_where_it_was() {
        val manager = DeveloperToolsManager()
        manager.open("A")
        manager.onTabClosed("B")
        assertThat(manager.isOpen).isTrue()
        assertThat(manager.tabId).isEqualTo("A")
    }

    @Test
    fun the_tab_list_watcher_closes_a_surface_whose_tab_vanished() {
        val manager = DeveloperToolsManager()
        manager.open("A")
        manager.retainTabs(setOf("B", "C"))
        assertThat(manager.isOpen).isFalse()
        assertThat(manager.tabId).isNull()
    }

    @Test
    fun the_tab_list_watcher_leaves_a_surface_whose_tab_is_still_open() {
        val manager = DeveloperToolsManager()
        manager.open("A")
        manager.retainTabs(setOf("A", "B"))
        assertThat(manager.isOpen).isTrue()
        assertThat(manager.tabId).isEqualTo("A")
    }

    @Test
    fun switching_tabs_retargets_the_surface_instead_of_closing_it() {
        val manager = DeveloperToolsManager()
        manager.open("A")
        manager.focus("B")
        assertThat(manager.isOpen).isTrue()
        assertThat(manager.tabId).isEqualTo("B")
    }

    @Test
    fun a_closed_surface_forgets_the_tab_it_was_on() {
        val manager = DeveloperToolsManager()
        manager.open("A")
        manager.close()
        assertThat(manager.isOpen).isFalse()
        assertThat(manager.tabId).isNull()
    }

    @Test
    fun the_two_dock_shortcuts_never_close_the_surface() {
        val manager = DeveloperToolsManager()
        manager.open("A")
        manager.toggleMinimize()
        assertThat(manager.dock).isEqualTo(DevToolsDock.MINIMIZED)
        manager.toggleFullscreen()
        assertThat(manager.dock).isEqualTo(DevToolsDock.FULLSCREEN)
        manager.toggleFullscreen()
        assertThat(manager.dock).isEqualTo(DevToolsDock.DOCKED)
        assertThat(manager.isOpen).isTrue()
    }
}
