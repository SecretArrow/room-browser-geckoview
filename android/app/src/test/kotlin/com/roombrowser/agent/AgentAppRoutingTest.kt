package com.roombrowser.agent

import com.google.common.truth.Truth.assertThat
import com.roombrowser.browser.ui.agentRoute
import com.roombrowser.data.repo.PermissionKind
import com.roombrowser.domain.agent.AgentAppActions
import org.junit.Test

/**
 * Holds the app-control vocabulary to the code that carries it out.
 *
 * The vocabulary is a pure object a JVM test can read, and the two pieces that
 * give it effect — [agentRoute] for the browser activity's own screens, and
 * the storage enums behind the permission names — live in `:app`, where only
 * this module can see both. A screen name that maps to no route, or a
 * permission name the storage does not know, is a tool that silently does
 * nothing: exactly the failure the vocabulary exists to make impossible.
 */
class AgentAppRoutingTest {

    @Test
    fun every_in_app_route_maps_to_a_screen_of_this_activity() {
        AgentAppActions.IN_APP_ROUTES.forEach { name ->
            assertThat(agentRoute(name)).isNotNull()
        }
    }

    @Test
    fun an_activity_screen_is_not_a_route_of_this_activity() {
        // These are started by BrowserViewModel.openScreen with the profile
        // extras their launchers read; routing them here would push a screen
        // that does not exist.
        AgentAppActions.APP_ACTIVITIES.forEach { name ->
            assertThat(agentRoute(name)).isNull()
        }
    }

    @Test
    fun a_name_outside_the_vocabulary_maps_to_nothing() {
        assertThat(agentRoute("profiles")).isNull()
        assertThat(agentRoute("profile_switch")).isNull()
        assertThat(agentRoute("")).isNull()
    }

    @Test
    fun the_permission_names_are_the_storage_kinds_in_lowercase() {
        val kinds = PermissionKind.values().map { it.name.lowercase() }.toSet()
        assertThat(AgentAppActions.PERMISSIONS).containsExactlyElementsIn(kinds)
        assertThat(kinds).hasSize(11)
    }
}
