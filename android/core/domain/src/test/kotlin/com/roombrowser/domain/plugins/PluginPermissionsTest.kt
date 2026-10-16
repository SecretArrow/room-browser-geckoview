package com.roombrowser.domain.plugins

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The consent vocabulary: names, labels, and where a host list is cut off. */
class PluginPermissionsTest {

    @Test
    fun `the vocabulary is exactly the four the manifest may declare`() {
        assertThat(PluginPermissions.PERMISSIONS)
            .containsExactly("storage", "network", "agent.tools", "chat.context")
    }

    @Test
    fun `each permission has its own consent sentence`() {
        assertThat(PluginPermissions.labelOf(PluginPermissions.STORAGE))
            .isEqualTo("Store data for this chat")
        assertThat(PluginPermissions.labelOf(PluginPermissions.AGENT_TOOLS))
            .isEqualTo("Add tools the AI agent can call")
        assertThat(PluginPermissions.labelOf(PluginPermissions.CHAT_CONTEXT))
            .isEqualTo("Read this chat's conversation")
        assertThat(PluginPermissions.labelOf(PluginPermissions.NETWORK))
            .isEqualTo("Reach the internet")
    }

    @Test
    fun `the network consent names the hosts it agrees to`() {
        assertThat(
            PluginPermissions.labelOf(
                PluginPermissions.NETWORK,
                listOf("api.example.com", "*.example.com")
            )
        ).isEqualTo("Reach the internet (hosts: api.example.com, *.example.com)")
    }

    @Test
    fun `a long host list is cut off at the label's own cap`() {
        val hosts = (1..20).map { "host$it.example.com" }
        val label = PluginPermissions.labelOf(PluginPermissions.NETWORK, hosts)
        assertThat(label).startsWith("Reach the internet (hosts: host1.example.com")
        // The list is cut INSIDE the interpolation, so the label closes
        // with the ellipsis and the sentence's own paren after it.
        assertThat(label).endsWith("…)")
        assertThat(label.length).isAtMost("Reach the internet (hosts: ".length + 120 + 2)
    }

    @Test
    fun `a name this build does not know labels as itself`() {
        assertThat(PluginPermissions.labelOf("whatever")).isEqualTo("whatever")
    }
}
