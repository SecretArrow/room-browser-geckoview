package com.roombrowser.data.skills

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The skills grammar's two pure halves, pinned before anything is fetched or
 * written: [SkillSpecs] turns what was SAID into a repository location (a
 * wrong guess here is a network fetch of whatever the guess names), and
 * [SkillManifest] turns a fetched SKILL.md into the row the rest of the
 * system reads. Both refuse rather than sanitise, so every refusal is a
 * sentence pinned here.
 *
 * Every test ends on a VOID-returning Truth call; the contains* families
 * return `Ordered` and would make the method non-void, which JUnit4 refuses.
 */
class SkillManifestTest {

    // ------------------------------------------------------------- spec: the CLI shapes

    @Test
    fun `the npx skills add spelling parses with the path as the sub path`() {
        val parsed = SkillSpecs.parse(
            "npx skills add binance/binance-skills-hub/skills/binance-web3/binance-agentic-wallet"
        )
        val spec = (parsed as SkillSpecParse.Ok).spec
        assertThat(spec.owner).isEqualTo("binance")
        assertThat(spec.repo).isEqualTo("binance-skills-hub")
        assertThat(spec.subPath).isEqualTo("skills/binance-web3/binance-agentic-wallet")
        assertThat(spec.sourceRepo).isEqualTo("binance/binance-skills-hub")
        assertThat(spec.ref).isNull()
    }

    @Test
    fun `the bare skills add spelling and the -y flag both parse`() {
        val bare = SkillSpecs.parse("skills add owner/repo/skills/demo") as SkillSpecParse.Ok
        assertThat(bare.spec.sourceRepo).isEqualTo("owner/repo")
        assertThat(bare.spec.subPath).isEqualTo("skills/demo")

        val flagged = SkillSpecs.parse("npx -y skills add owner/repo") as SkillSpecParse.Ok
        assertThat(flagged.spec.subPath).isEmpty()
    }

    @Test
    fun `the install verb is accepted beside add`() {
        val parsed = SkillSpecs.parse("skills install owner/repo") as SkillSpecParse.Ok
        assertThat(parsed.spec.subPath).isEmpty()
    }

    @Test
    fun `a bare owner-repo-path parses without any prefix`() {
        val parsed = SkillSpecs.parse("owner/repo/skills/demo") as SkillSpecParse.Ok
        assertThat(parsed.spec.owner).isEqualTo("owner")
        assertThat(parsed.spec.repo).isEqualTo("repo")
        assertThat(parsed.spec.subPath).isEqualTo("skills/demo")
    }

    @Test
    fun `a bare tree spec names the branch and the path`() {
        val parsed = SkillSpecs.parse("owner/repo/tree/legacy/skills/demo") as SkillSpecParse.Ok
        assertThat(parsed.spec.repo).isEqualTo("repo")
        assertThat(parsed.spec.subPath).isEqualTo("skills/demo")
        assertThat(parsed.spec.ref).isEqualTo("legacy")
    }

    @Test
    fun `a dot git repo suffix is stripped`() {
        val parsed = SkillSpecs.parse("owner/repo.git") as SkillSpecParse.Ok
        assertThat(parsed.spec.repo).isEqualTo("repo")
    }

    // ------------------------------------------------------------- spec: URLs

    @Test
    fun `a full github tree url parses to branch and path`() {
        val parsed = SkillSpecs.parse(
            "https://github.com/owner/repo/tree/main/skills/demo"
        ) as SkillSpecParse.Ok
        assertThat(parsed.spec.owner).isEqualTo("owner")
        assertThat(parsed.spec.repo).isEqualTo("repo")
        assertThat(parsed.spec.subPath).isEqualTo("skills/demo")
        assertThat(parsed.spec.ref).isEqualTo("main")
    }

    @Test
    fun `a plain repo url parses with no path and a url with a branch only names the branch`() {
        val plain = SkillSpecs.parse("https://github.com/owner/repo") as SkillSpecParse.Ok
        assertThat(plain.spec.subPath).isEmpty()
        assertThat(plain.spec.ref).isNull()

        val branch = SkillSpecs.parse("https://github.com/owner/repo/tree/main") as SkillSpecParse.Ok
        assertThat(branch.spec.subPath).isEmpty()
        assertThat(branch.spec.ref).isEqualTo("main")
    }

    @Test
    fun `a url prefixed with the cli spelling still parses`() {
        val parsed = SkillSpecs.parse(
            "npx skills add https://github.com/owner/repo/tree/main/skills/demo"
        ) as SkillSpecParse.Ok
        assertThat(parsed.spec.subPath).isEqualTo("skills/demo")
        assertThat(parsed.spec.ref).isEqualTo("main")
    }

    @Test
    fun `an http url and a blob url are refused with the reason`() {
        val http = SkillSpecs.parse("http://github.com/owner/repo")
        assertThat((http as SkillSpecParse.Invalid).reason).contains("https")

        val blob = SkillSpecs.parse("https://github.com/owner/repo/blob/main/skills/demo/SKILL.md")
        assertThat((blob as SkillSpecParse.Invalid).reason).contains("tree")
    }

    @Test
    fun `a url with a query or fragment is refused rather than trimmed`() {
        val parsed = SkillSpecs.parse("https://github.com/owner/repo/tree/main/skills/demo?tab=readme")
        assertThat((parsed as SkillSpecParse.Invalid).reason).contains("query")
    }

    // ------------------------------------------------------------- spec: natural language

    @Test
    fun `a natural language sentence naming one repo path parses`() {
        val parsed = SkillSpecs.parse(
            "please install the binance agentic wallet skill from " +
                "binance/binance-skills-hub/skills/binance-web3/binance-agentic-wallet"
        )
        val spec = (parsed as SkillSpecParse.Ok).spec
        assertThat(spec.sourceRepo).isEqualTo("binance/binance-skills-hub")
        assertThat(spec.subPath).isEqualTo("skills/binance-web3/binance-agentic-wallet")
    }

    @Test
    fun `a sentence with no repo path is refused with a message asking for the path`() {
        val parsed = SkillSpecs.parse("install the wallet skill please")
        val reason = (parsed as SkillSpecParse.Invalid).reason
        assertThat(reason).contains("owner/repo")
    }

    @Test
    fun `a sentence naming two repos is refused as ambiguous, never guessed`() {
        val parsed = SkillSpecs.parse(
            "install the skill from owner/one/skills/a or owner/two/skills/b"
        )
        val reason = (parsed as SkillSpecParse.Invalid).reason
        assertThat(reason).contains("more than one")
        assertThat(reason).contains("owner/one/skills/a")
        assertThat(reason).contains("owner/two/skills/b")
    }

    // ------------------------------------------------------------- spec: refusals

    @Test
    fun `traversal segments are refused in every position`() {
        assertThat((SkillSpecs.parse("owner/..") as SkillSpecParse.Invalid).reason).isNotEmpty()
        assertThat((SkillSpecs.parse("../repo") as SkillSpecParse.Invalid).reason).isNotEmpty()
        assertThat(
            (SkillSpecs.parse("owner/repo/skills/../../etc") as SkillSpecParse.Invalid).reason
        ).isNotEmpty()
        assertThat(
            (SkillSpecs.parse("owner/repo/skills/.hidden") as SkillSpecParse.Invalid).reason
        ).isNotEmpty()
    }

    @Test
    fun `empty input and a lone word are refused with actionable messages`() {
        val empty = SkillSpecs.parse("   ") as SkillSpecParse.Invalid
        assertThat(empty.reason).contains("owner/repo")

        val word = SkillSpecs.parse("wallet") as SkillSpecParse.Invalid
        assertThat(word.reason).contains("owner/repo")
    }

    @Test
    fun `a quoted spec is unwrapped before parsing`() {
        val parsed = SkillSpecs.parse("\"owner/repo/skills/demo\"") as SkillSpecParse.Ok
        assertThat(parsed.spec.subPath).isEqualTo("skills/demo")
    }

    // ------------------------------------------------------------- skillId

    @Test
    fun `the skill id is lowercased collapsed and capped`() {
        assertThat(SkillSpecs.skillIdFor("binance-agentic-wallet")).isEqualTo("binance-agentic-wallet")
        assertThat(SkillSpecs.skillIdFor("Binance Agentic Wallet!")).isEqualTo("binance-agentic-wallet")
        assertThat(SkillSpecs.skillIdFor("  spaced---out  ")).isEqualTo("spaced-out")
        assertThat(SkillSpecs.skillIdFor("x".repeat(80)).length).isEqualTo(64)
        // A name of nothing but symbols still has to produce a usable id —
        // the installer would otherwise key a directory the row cannot name.
        assertThat(SkillSpecs.skillIdFor("***")).isEqualTo("skill")
    }

    // ------------------------------------------------------------- frontmatter

    @Test
    fun `a real hub front matter parses name description version and the node package`() {
        // Shaped exactly like the Binance hub's SKILL.md: inline name, a `|`
        // description, metadata.version quoted, and an openclaw install hint
        // whose `kind: node` carries the npm package (with bins, ignored).
        val md = SkillManifest.parse(
            """
            ---
            name: binance-agentic-wallet
            description: |
              Use when the user mentions wallet connect/sign in, check balance, send/transfer
              tokens, swap/buy/sell tokens, DEX trade or any on-chain wallet operation.
            metadata:
              author: binance-web3-team
              version: '1.13.0'
              openclaw:
                requires:
                  bins:
                    - baw
                install:
                  - kind: node
                    package: '@binance/agentic-wallet'
                    bins: [baw]
                    label: Install Binance Agentic Wallet CLI (npm)
            ---

            # Binance Agentic Wallet Skill

            This skill drives the `baw` CLI.
            """.trimIndent()
        )!!

        assertThat(md.name).isEqualTo("binance-agentic-wallet")
        assertThat(md.description).startsWith("Use when the user mentions wallet connect")
        assertThat(md.description).contains("on-chain wallet operation")
        assertThat(md.version).isEqualTo("1.13.0")
        assertThat(md.npmDeps).containsExactly("@binance/agentic-wallet")
        assertThat(md.body).startsWith("# Binance Agentic Wallet Skill")
        assertThat(md.body).contains("baw` CLI")
        // The front matter itself must never leak into the body the prompt shows.
        assertThat(md.body).doesNotContain("metadata:")
    }

    @Test
    fun `an inline description and a folded description both parse`() {
        val inline = SkillManifest.parse(
            "---\nname: a\ndescription: Does one thing well.\n---\nBody."
        )!!
        assertThat(inline.description).isEqualTo("Does one thing well.")

        val folded = SkillManifest.parse(
            "---\nname: a\ndescription: >\n  first line\n  second line\n\n  new paragraph\n---\nBody."
        )!!
        // `>` folds each paragraph's newlines away; the paragraphs themselves
        // are joined with a single space, so the whole scalar reads as prose.
        assertThat(folded.description).isEqualTo("first line second line new paragraph")
    }

    @Test
    fun `crlf front matter parses the same as lf`() {
        // The block scalar keeps the two description lines; the CRLFs are
        // normalised to LF before anything else looks at the text.
        val md = SkillManifest.parse(
            "---\r\nname: crlf-skill\r\ndescription: |\r\n  line one\r\n  line two\r\n---\r\nBody.\r\n"
        )!!
        assertThat(md.name).isEqualTo("crlf-skill")
        assertThat(md.description).isEqualTo("line one\nline two")
        assertThat(md.body).isEqualTo("Body.")
    }

    @Test
    fun `a skill without a name is invalid and a file without fences is refused`() {
        // No name key at all.
        assertThat(SkillManifest.parse("---\ndescription: x\n---\nBody")).isNull()
        // An empty name is as good as none.
        assertThat(SkillManifest.parse("---\nname: \"\"\n---\nBody")).isNull()
        // No front matter, and a fence that never closes.
        assertThat(SkillManifest.parse("# Just markdown")).isNull()
        assertThat(SkillManifest.parse("---\nname: x\nBody without a close fence")).isNull()
    }

    @Test
    fun `unknown keys are ignored and a non node kind contributes no dependency`() {
        val md = SkillManifest.parse(
            """
            ---
            name: mixed
            description: x
            license: MIT
            allowed-tools: [a, b]
            metadata:
              version: 2.0
            openclaw:
              install:
                - kind: binary
                  package: '/usr/local/bin/baw'
                - kind: node
                  package: real-package
            ---
            Body
            """.trimIndent()
        )!!
        assertThat(md.npmDeps).containsExactly("real-package")
        assertThat(md.version).isEqualTo("2.0")
    }

    @Test
    fun `a bom and a dots terminator are tolerated`() {
        val md = SkillManifest.parse("\uFEFF---\nname: bom\n---\nBody")!!
        assertThat(md.name).isEqualTo("bom")
        val dots = SkillManifest.parse("---\nname: dots\n...\nBody")!!
        assertThat(dots.name).isEqualTo("dots")
    }

    @Test
    fun `a name given as a block scalar still counts as a name`() {
        val md = SkillManifest.parse("---\nname: |\n  blocky\n---\nBody")!!
        assertThat(md.name).isEqualTo("blocky")
    }

    @Test
    fun `two install hints for the same package collapse to one entry`() {
        val md = SkillManifest.parse(
            """
            ---
            name: dupe
            openclaw:
              install:
                - kind: node
                  package: '@scope/pkg'
                - kind: node
                  package: '@scope/pkg'
            ---
            Body
            """.trimIndent()
        )!!
        assertThat(md.npmDeps).containsExactly("@scope/pkg")
    }
}
