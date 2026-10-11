package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AgentVisionSupportTest {

    @Test
    fun `providers that can see images are reported able`() {
        listOf(
            AgentVisionSupport.PROTOCOL_OPENAI,
            AgentVisionSupport.PROTOCOL_ANTHROPIC,
            AgentVisionSupport.PROTOCOL_OLLAMA
        ).forEach { protocol ->
            assertThat(AgentVisionSupport.supports(protocol)).isTrue()
            assertThat(AgentVisionSupport.unsupportedReason(protocol)).isNull()
        }
    }

    @Test
    fun `providers that cannot see images are reported unable with a reason`() {
        listOf(
            AgentVisionSupport.PROTOCOL_OPENCODE,
            AgentVisionSupport.PROTOCOL_LOCAL
        ).forEach { protocol ->
            assertThat(AgentVisionSupport.supports(protocol)).isFalse()
            val reason = AgentVisionSupport.unsupportedReason(protocol)
            assertThat(reason).isNotNull()
            assertThat(reason!!.isNotBlank()).isTrue()
        }
    }

    @Test
    fun `an unknown protocol falls through to the openai capable default`() {
        assertThat(AgentVisionSupport.supports("SOMETHING_ELSE")).isTrue()
    }
}
