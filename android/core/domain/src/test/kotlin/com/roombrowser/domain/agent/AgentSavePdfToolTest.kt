package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * The PDF tool's own contract: what it asks for, when it is confirmed, and
 * where it is refused.
 *
 * The classification is held by the partition tests beside this file
 * ([AgentToolForegroundPolicyTest] and [HeadlessToolCatalogTest] in `:app`).
 * What is here is the part those cannot see: that keeping a page is treated as
 * a WRITE to the user's device, and that the model is told how this differs
 * from `download_file` — the two are one word apart on a page that both names
 * a PDF, and a model that confuses them sends a receipt link to the wrong tool.
 */
class AgentSavePdfToolTest {

    private val def = AgentTools.toolDefs()
        .single { it.function.name == AgentTools.SAVE_PDF }

    @Test
    fun `saving a page takes only an optional name`() {
        val params = def.function.parameters
        val properties = params.getValue("properties").jsonObject

        assertThat(properties.keys).containsExactly("name")
        // No required argument: the page is whatever this chat's tab is on, and
        // the browser names the file from it when the model suggests nothing.
        assertThat(params.keys).doesNotContain("required")
    }

    @Test
    fun `saving a page is confirmed like a click, because it writes to the device`() {
        assertThat(AgentTools.INTERACTIVE_TOOLS).contains(AgentTools.SAVE_PDF)
    }

    @Test
    fun `plan mode refuses saving a page, because it carries the work out`() {
        val refusal = PlanModePolicy.refusal(AgentTools.SAVE_PDF, null)

        assertThat(refusal).isNotNull()
        assertThat(refusal).contains(AgentTools.SAVE_PDF)
    }

    @Test
    fun `the description separates keeping a page from downloading a link`() {
        val description = def.function.description ?: ""

        assertThat(description).contains(AgentTools.DOWNLOAD_FILE)
        assertThat(description).contains("confirmed")
    }

    @Test
    fun `the label names the file when one is suggested`() {
        assertThat(AgentTools.describeTool(AgentTools.SAVE_PDF, """{"name":"receipt.pdf"}"""))
            .isEqualTo("Save the page as \"receipt.pdf\"")
        assertThat(AgentTools.describeTool(AgentTools.SAVE_PDF, "{}"))
            .isEqualTo("Save the page as PDF")
        assertThat(AgentTools.describeTool(AgentTools.SAVE_PDF, """{"name":"  "}"""))
            .isEqualTo("Save the page as PDF")
    }
}
