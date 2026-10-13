package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * The download tool's own contract: what it asks for, when it is confirmed, and
 * where it is refused.
 *
 * The classification itself is held by the partition tests beside this file
 * ([AgentToolForegroundPolicyTest] and [HeadlessToolCatalogTest] in `:app`); what
 * is here is the part those cannot see — that a download is treated as a WRITE
 * to the user's device rather than as a read, which is the decision that keeps
 * a page from filling their storage.
 */
class AgentDownloadToolTest {

    private val def = AgentTools.toolDefs()
        .single { it.function.name == AgentTools.DOWNLOAD_FILE }

    @Test
    fun `a download needs a url, and a name is optional`() {
        val params = def.function.parameters
        val properties = params.getValue("properties").jsonObject

        assertThat(properties.keys).containsExactly("url", "name")
        // A name the model invents is a suggestion the engine de-duplicates;
        // a URL is the one thing the call cannot work without.
        assertThat(params.getValue("required").jsonPrimitive.content).contains("url")
        assertThat(params.getValue("required").jsonPrimitive.content).doesNotContain("name")
    }

    @Test
    fun `a download is confirmed like a click, because it writes to the device`() {
        assertThat(AgentTools.INTERACTIVE_TOOLS).contains(AgentTools.DOWNLOAD_FILE)
    }

    @Test
    fun `plan mode refuses a download, because it carries the work out`() {
        // Plan mode's promise is that nothing is carried out. A download is not
        // a submission, but it does put a file on the user's device.
        val refusal = PlanModePolicy.refusal(AgentTools.DOWNLOAD_FILE, null)

        assertThat(refusal).isNotNull()
        assertThat(refusal).contains(AgentTools.DOWNLOAD_FILE)
    }

    @Test
    fun `the label names the file rather than the whole url`() {
        assertThat(
            AgentTools.describeTool(AgentTools.DOWNLOAD_FILE, """{"url":"https://x.test/a/invoice.pdf"}""")
        ).isEqualTo("Download \"invoice.pdf\"")
        assertThat(
            AgentTools.describeTool(
                AgentTools.DOWNLOAD_FILE,
                """{"url":"https://x.test/a/invoice.pdf","name":"renamed.pdf"}"""
            )
        ).isEqualTo("Download \"renamed.pdf\"")
    }

    @Test
    fun `a url that names no file still gets a label`() {
        assertThat(AgentTools.describeTool(AgentTools.DOWNLOAD_FILE, """{"url":"https://x.test/"}"""))
            .isEqualTo("Download \"file\"")
        assertThat(AgentTools.describeTool(AgentTools.DOWNLOAD_FILE, "not json"))
            .isEqualTo("Download \"file\"")
    }
}
