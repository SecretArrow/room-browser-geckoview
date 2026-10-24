package com.roombrowser.agent.skills

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SKILL.md frontmatter parser: the six flat fields an instruction skill
 * needs, the malformed shapes that are refusals rather than guesses, and the
 * id sanitizer every installed directory name comes from.
 */
class SkillManifestTest {

    @Test
    fun `a valid document parses to its fields and body`() {
        val parsed = SkillFrontmatter.parse(
            "---\n" +
                "name: binance-wallet\n" +
                "description: Swap and check balances on BNB chain.\n" +
                "version: 1.2.0\n" +
                "allowed-tools: read_page, click\n" +
                "---\n" +
                "\n# Steps\n\nDo the thing.\n"
        ) as SkillFrontmatter.Parsed

        assertThat(parsed.name).isEqualTo("binance-wallet")
        assertThat(parsed.description).isEqualTo("Swap and check balances on BNB chain.")
        assertThat(parsed.version).isEqualTo("1.2.0")
        assertThat(parsed.allowedTools).isEqualTo("read_page, click")
        assertThat(parsed.body).startsWith("# Steps")
        assertThat(parsed.body).contains("Do the thing.")
        // Nothing unknown was in this document, so extra is empty.
        assertThat(parsed.extra).isEmpty()
    }

    @Test
    fun `quoted values are unquoted once`() {
        val parsed = SkillFrontmatter.parse(
            "---\nname: \"quoted\"\ndescription: 'single'\n---\nbody"
        ) as SkillFrontmatter.Parsed
        assertThat(parsed.name).isEqualTo("quoted")
        assertThat(parsed.description).isEqualTo("single")
    }

    @Test
    fun `unknown fields are kept and reported, never dropped`() {
        val parsed = SkillFrontmatter.parse(
            "---\nname: demo\nlicense: MIT\n---\nbody"
        ) as SkillFrontmatter.Parsed
        assertThat(parsed.extra).containsEntry("license", "MIT")
    }

    @Test
    fun `a nested yaml block is kept verbatim under its key`() {
        val text = "---\nname: demo\nrules:\n  - never panic\n  - verify twice\n---\nbody"
        val parsed = SkillFrontmatter.parse(text) as SkillFrontmatter.Parsed
        assertThat(parsed.extra).containsKey("rules")
        assertThat(parsed.extra["rules"]).contains("never panic")
        assertThat(parsed.extra["rules"]).contains("verify twice")
    }

    @Test
    fun `a document with no frontmatter fence is a refusal, not a body`() {
        val failure = SkillFrontmatter.parse("just some markdown\nnothing else")
        assertThat(failure).isInstanceOf(SkillFrontmatter.Failure::class.java)
        assertThat((failure as SkillFrontmatter.Failure).reason).contains("---")
    }

    @Test
    fun `a fence that never closes is a refusal`() {
        val failure = SkillFrontmatter.parse("---\nname: demo\nno closing fence")
        assertThat((failure as SkillFrontmatter.Failure).reason).contains("closed")
    }

    @Test
    fun `a document with no name is a refusal that says so`() {
        val failure = SkillFrontmatter.parse("---\ndescription: no name here\n---\nbody")
        assertThat((failure as SkillFrontmatter.Failure).reason).contains("name")
    }

    @Test
    fun `a name longer than the cap is a refusal`() {
        val failure = SkillFrontmatter.parse("---\nname: ${"x".repeat(49)}\n---\nbody")
        assertThat((failure as SkillFrontmatter.Failure).reason).contains("longer than")
        // At the cap exactly, it parses.
        assertTrue(
            SkillFrontmatter.parse("---\nname: ${"x".repeat(48)}\n---\nbody")
                is SkillFrontmatter.Parsed
        )
    }

    @Test
    fun `an oversized frontmatter is refused before any field is read`() {
        val blob = "x: " + "y".repeat(SkillFrontmatter.FRONTMATTER_MAX_BYTES)
        val failure = SkillFrontmatter.parse("---\nname: demo\n$blob\n---\nbody")
        assertThat((failure as SkillFrontmatter.Failure).reason).contains("larger than")
    }

    @Test
    fun `the body is everything after the closing fence`() {
        val parsed = SkillFrontmatter.parse("---\nname: demo\n---\nfirst line\nsecond line")
            as SkillFrontmatter.Parsed
        assertThat(parsed.body).isEqualTo("first line\nsecond line")
    }

    // ---- sanitizeName: every installed directory id comes from here ----

    @Test
    fun `names fold to the safe directory vocabulary`() {
        assertThat(SkillFrontmatter.sanitizeName("Binance Agentic Wallet"))
            .isEqualTo("binance-agentic-wallet")
        assertThat(SkillFrontmatter.sanitizeName("Spaced  --  Out"))
            .isEqualTo("spaced-out")
        assertThat(SkillFrontmatter.sanitizeName("--trim-me--")).isEqualTo("trim-me")
    }

    @Test
    fun `the sanitized id is always a valid registry id`() {
        val registry = SkillRegistry(java.io.File("/tmp/unused-for-this-assertion"))
        for (raw in listOf("Demo", "A  Very  Long  Name ".repeat(3), "___", "0")) {
            val id = SkillFrontmatter.sanitizeName(raw)
            if (id.isNotEmpty()) {
                assertThat(registry.validId(id)).isTrue()
            }
        }
    }

    @Test
    fun `hostile input sanitizes to empty so the caller can fall back`() {
        assertThat(SkillFrontmatter.sanitizeName("")).isEmpty()
        assertThat(SkillFrontmatter.sanitizeName("///")).isEmpty()
        assertThat(SkillFrontmatter.sanitizeName("---")).isEmpty()
    }

    @Test
    fun `the sanitizer caps at the same length the parser allows`() {
        assertThat(SkillFrontmatter.sanitizeName("y".repeat(200)).length)
            .isEqualTo(SkillFrontmatter.NAME_MAX_CHARS)
    }
}
