package com.roombrowser.agent.skills

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one door a skill source comes through. Every accepted spelling is a
 * string a person or a README actually writes; every refusal is one the
 * model or the user must be able to act on — so the refusals are pinned by
 * name as well as by shape.
 */
class SkillSourceTest {

    // ---- accepted spellings ----

    @Test
    fun `a bare owner and repo parses with the default ref`() {
        val source = SkillSource.parse("owner/repo").getOrThrow()
        assertThat(source.owner).isEqualTo("owner")
        assertThat(source.repo).isEqualTo("repo")
        assertThat(source.ref).isEqualTo("HEAD")
        assertThat(source.subPath).isEmpty()
        assertThat(source.raw).isEqualTo("owner/repo")
    }

    @Test
    fun `a sub path is kept segment for segment`() {
        val source = SkillSource.parse("owner/repo/skills/binance-web3/wallet").getOrThrow()
        assertThat(source.subPath).isEqualTo("skills/binance-web3/wallet")
    }

    @Test
    fun `an at-branch suffix names the ref`() {
        val source = SkillSource.parse("owner/repo@main").getOrThrow()
        assertThat(source.ref).isEqualTo("main")
        val nested = SkillSource.parse("owner/repo/skills/demo@feature/long-branch").getOrThrow()
        assertThat(nested.ref).isEqualTo("feature/long-branch")
        assertThat(nested.subPath).isEqualTo("skills/demo")
    }

    @Test
    fun `a https url parses to the same shape as the short form`() {
        val short = SkillSource.parse("owner/repo/skills/demo").getOrThrow()
        val url = SkillSource.parse("https://github.com/owner/repo/skills/demo").getOrThrow()
        assertThat(url.owner).isEqualTo(short.owner)
        assertThat(url.repo).isEqualTo(short.repo)
        assertThat(url.subPath).isEqualTo(short.subPath)
        assertThat(url.ref).isEqualTo("HEAD")
    }

    @Test
    fun `a tree url names branch and path`() {
        val source = SkillSource.parse("https://github.com/owner/repo/tree/main/skills/demo").getOrThrow()
        assertThat(source.ref).isEqualTo("main")
        assertThat(source.subPath).isEqualTo("skills/demo")
        // The root of a branch is legal too: tree/BRANCH with nothing after.
        val root = SkillSource.parse("https://github.com/owner/repo/tree/main").getOrThrow()
        assertThat(root.ref).isEqualTo("main")
        assertThat(root.subPath).isEmpty()
    }

    @Test
    fun `a bare host spelling parses like the url`() {
        val source = SkillSource.parse("github.com/owner/repo").getOrThrow()
        assertThat(source.owner).isEqualTo("owner")
    }

    @Test
    fun `a repo dot git suffix is dropped`() {
        val source = SkillSource.parse("https://github.com/owner/repo.git").getOrThrow()
        assertThat(source.repo).isEqualTo("repo")
    }

    @Test
    fun `trailing slashes and whitespace do not change the parse`() {
        val source = SkillSource.parse("  https://github.com/owner/repo/  ").getOrThrow()
        assertThat(source.subPath).isEmpty()
    }

    @Test
    fun `the tarball urls try the refs heads form before the bare form`() {
        val branched = SkillSource.parse("owner/repo@v2").getOrThrow()
        assertThat(branched.tarballUrls).containsExactly(
            "https://codeload.github.com/owner/repo/tar.gz/refs/heads/v2",
            "https://codeload.github.com/owner/repo/tar.gz/v2"
        ).inOrder()
        // The default ref adds only the bare form, which is how codeload
        // names the default branch.
        val head = SkillSource.parse("owner/repo").getOrThrow()
        assertThat(head.tarballUrls)
            .containsExactly("https://codeload.github.com/owner/repo/tar.gz/HEAD")
    }

    @Test
    fun `shortForm names the source the way a report does`() {
        assertThat(SkillSource.parse("owner/repo/skills/demo@main").getOrThrow().shortForm)
            .isEqualTo("owner/repo/skills/demo@main")
        assertThat(SkillSource.parse("owner/repo").getOrThrow().shortForm)
            .isEqualTo("owner/repo")
    }

    // ---- the CLI spellings the manager screen and the tool strip ----

    @Test
    fun `the npx spelling strips to the bare source`() {
        assertThat(
            SkillSource.stripCliSpelling("npx skills add binance/binance-skills-hub/skills/wallet")
        ).isEqualTo("binance/binance-skills-hub/skills/wallet")
        assertThat(SkillSource.stripCliSpelling("skills add owner/repo")).isEqualTo("owner/repo")
        assertThat(SkillSource.stripCliSpelling("  owner/repo  ")).isEqualTo("owner/repo")
        // A url carries no prefix and passes through untouched.
        assertThat(SkillSource.stripCliSpelling("https://github.com/owner/repo"))
            .isEqualTo("https://github.com/owner/repo")
        // And the stripped string parses end to end.
        assertTrue(
            SkillSource.parse(
                SkillSource.stripCliSpelling("npx skills add owner/repo/skills/demo")
            ).isSuccess
        )
    }

    // ---- refusals ----

    @Test
    fun `an empty source is refused with the fix`() {
        val failure = SkillSource.parse("   ").exceptionOrNull()!!
        assertThat(failure.message).contains("owner/repo")
    }

    @Test
    fun `a non-github host is refused by name`() {
        val failure = SkillSource.parse("https://gitlab.com/owner/repo").exceptionOrNull()!!
        assertThat(failure.message).contains("gitlab.com")
        assertThat(failure.message).contains("github.com")
        // A lookalike host is not a GitHub host either.
        assertThat(
            SkillSource.parse("https://github.com.evil.test/owner/repo").isFailure
        ).isTrue()
    }

    @Test
    fun `plain http is refused rather than upgraded`() {
        val failure = SkillSource.parse("http://github.com/owner/repo").exceptionOrNull()!!
        assertThat(failure.message).contains("https")
    }

    @Test
    fun `an ssh remote is refused with the https route`() {
        val failure = SkillSource.parse("git@github.com:owner/repo.git").exceptionOrNull()!!
        assertThat(failure.message).contains("https://github.com")
    }

    @Test
    fun `a source with no repository is refused`() {
        assertThat(
            SkillSource.parse("owner").exceptionOrNull()!!.message
        ).contains("owner/repo")
        assertThat(
            SkillSource.parse("https://github.com/owner").exceptionOrNull()!!.message
        ).contains("owner/repo")
    }

    @Test
    fun `a traversal in the sub path is refused`() {
        assertThat(SkillSource.parse("owner/repo/../../../etc").isFailure).isTrue()
        assertThat(SkillSource.parse("owner/repo/a/../b").isFailure).isTrue()
        assertThat(SkillSource.parse("owner/repo/..").isFailure).isTrue()
    }

    @Test
    fun `a blob url is refused because a skill is a directory`() {
        val failure = SkillSource.parse("https://github.com/owner/repo/blob/main/SKILL.md")
            .exceptionOrNull()!!
        assertThat(failure.message).contains("tree")
    }

    @Test
    fun `a tree url without a branch is refused`() {
        assertThat(
            SkillSource.parse("https://github.com/owner/repo/tree").exceptionOrNull()!!.message
        ).contains("tree")
    }

    @Test
    fun `a branch named twice is refused`() {
        assertThat(
            SkillSource.parse("owner/repo/tree/main@dev").isFailure
        ).isTrue()
    }

    @Test
    fun `an empty or malformed ref is refused`() {
        assertThat(SkillSource.parse("owner/repo@").isFailure).isTrue()
        assertThat(SkillSource.parse("owner/repo@two words").isFailure).isTrue()
        assertThat(SkillSource.parse("owner/repo@../escape").isFailure).isTrue()
    }

    @Test
    fun `invalid owner or repository names are refused`() {
        assertThat(SkillSource.parse("ow ner/repo").isFailure).isTrue()
        assertThat(SkillSource.parse("../repo").isFailure).isTrue()
        assertThat(SkillSource.parse("owner/../etc").isFailure).isTrue()
        // A Windows drive has no scheme the parser accepts and fails as a name.
        assertThat(SkillSource.parse("C:/repo").isFailure).isTrue()
    }

    @Test
    fun `control characters and over-long sources are refused`() {
        assertThat(SkillSource.parse("owner/repo/\u0007").isFailure).isTrue()
        assertThat(SkillSource.parse("owner/repo/\u001b").isFailure).isTrue()
        assertThat(SkillSource.parse("o".repeat(301) + "/repo").isFailure).isTrue()
        // Exactly at the cap is still a parseable source.
        assertTrue(
            SkillSource.parse("o".repeat(288) + "/repo").isSuccess
        )
    }

    @Test
    fun `the ref marker takes the last at sign, as documented`() {
        // A branch may carry an @; taking the LAST one keeps a branch like
        // `v@2` working and is the documented trade.
        val source = SkillSource.parse("owner/repo@v@2").getOrThrow()
        assertThat(source.ref).isEqualTo("v@2")
    }
}
