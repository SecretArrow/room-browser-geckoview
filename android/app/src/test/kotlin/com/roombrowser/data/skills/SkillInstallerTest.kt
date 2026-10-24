package com.roombrowser.data.skills

import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.db.AgentSkillEntity
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * The one door a skill gets through, driven over a fake transport — the same
 * way [com.roombrowser.node.packages.NodePackageStore] is driven over a fake
 * registry. What is pinned here is what a NETWORK can and cannot make happen:
 * a fetched archive lands as files only after every rule holds, a refusal
 * leaves the previous install untouched, and nothing from the skill is ever
 * executed (the files the happy path writes are read back as text, which is
 * all any of this code will ever do with them).
 *
 * The tarball fixtures are built by the tiny ustar writer at the bottom — a
 * private copy of the builders beside TarGz's own tests, which live in the
 * `:node` module and are `internal` to it.
 *
 * Every test ends on a VOID-returning Truth call; the contains* families
 * return `Ordered` and would make the method non-void, which JUnit4 refuses.
 */
class SkillInstallerTest {

    private class FakeFetch(
        vararg routes: Pair<String, Pair<Int, ByteArray>>
    ) : SkillFetch {
        val requests = mutableListOf<String>()
        private val table = routes.toMap()

        override fun get(url: okhttp3.HttpUrl, accept: String?): SkillHttpResponse {
            requests += url.toString()
            val hit = table.entries.firstOrNull { url.toString() == it.key }
                ?: table.entries.firstOrNull { url.toString().startsWith(it.key) }
            return hit?.let { SkillHttpResponse(it.value.first, it.value.second, "fixture") }
                ?: SkillHttpResponse(404, ByteArray(0), "fixture miss")
        }
    }

    /** An in-memory stand-in for the Room DAO's two writes an install makes. */
    private class FakeStore : SkillStore {
        val rows = linkedMapOf<String, AgentSkillEntity>()
        private var nextId = 1L

        override suspend fun bySkillId(skillId: String): AgentSkillEntity? = rows[skillId]

        override suspend fun upsert(entity: AgentSkillEntity) {
            val id = if (entity.id == 0L) nextId++ else entity.id
            rows[entity.skillId] = entity.copy(id = id)
        }
    }

    private val root: File = Files.createTempDirectory("skill-installer").toFile()
    private val skillsDir: File = File(root, "skills")
    private val store = FakeStore()

    private fun installer(fetch: SkillFetch) = SkillInstaller(skillsDir, fetch, store)

    // ------------------------------------------------------------- the happy path

    @Test
    fun `an npx spec installs the skill directory and upserts the row`() = runBlocking<Unit> {
        val fetch = FakeFetch(
            "https://api.github.com/repos/binance/binance-skills-hub" to ok("""{"default_branch":"main"}"""),
            "https://api.github.com/repos/binance/binance-skills-hub/branches/main" to
                ok("""{"commit":{"sha":"deadbeef1234"}}"""),
            "https://codeload.github.com/binance/binance-skills-hub/tar.gz/refs/heads/main" to
                ok(hubTarball())
        )
        val result = installer(fetch).install(
            "npx skills add binance/binance-skills-hub/skills/binance-web3/binance-agentic-wallet",
            nowMs = 1_000
        )
        val installed = result as SkillInstallResult.Installed

        // The row, exactly as the rest of the system will read it.
        val entity = installed.entity
        assertThat(entity.skillId).isEqualTo("binance-agentic-wallet")
        assertThat(entity.displayName).isEqualTo("binance-agentic-wallet")
        assertThat(entity.sourceRepo).isEqualTo("binance/binance-skills-hub")
        assertThat(entity.subPath).isEqualTo("skills/binance-web3/binance-agentic-wallet")
        assertThat(entity.version).isEqualTo("1.13.0")
        assertThat(entity.commitRef).isEqualTo("deadbeef1234")
        assertThat(entity.skillMdSha256)
            .isEqualTo(sha256Hex(SKILL_MD_BYTES))
        assertThat(entity.npmDepsJson).isEqualTo("[\"@binance/agentic-wallet\"]")
        assertThat(entity.installedAt).isEqualTo(1_000)
        assertThat(installed.updated).isFalse()
        assertThat(installed.npmDeps).containsExactly("@binance/agentic-wallet")

        // The FILES: SKILL.md plus the reference, copied inert, and nothing
        // from the wider repo (the hub README) leaks into the skill.
        val dir = File(skillsDir, "binance-agentic-wallet")
        assertThat(File(dir, "SKILL.md").readBytes()).isEqualTo(SKILL_MD_BYTES)
        assertThat(File(dir, "references/send.md").isFile).isTrue()
        assertThat(File(dir, "README.md").exists()).isFalse()
        assertThat(store.rows).containsKey("binance-agentic-wallet")
    }

    @Test
    fun `the sha256 recorded is the digest of the installed SKILL md`() = runBlocking<Unit> {
        val fetch = hubFetch()
        val result = installer(fetch).install("owner/demo-hub/skills/w", nowMs = 1) as SkillInstallResult.Installed
        val expected = MessageDigest.getInstance("SHA-256").digest(SKILL_MD_BYTES)
            .joinToString("") { "%02x".format(it) }
        assertThat(result.entity.skillMdSha256).isEqualTo(expected)
        assertThat(result.entity.skillMdSha256).hasLength(64)
    }

    // ------------------------------------------------------------- refusals

    @Test
    fun `a path with no SKILL md is refused with the path named and nothing written`() =
        runBlocking<Unit> {
            val fetch = FakeFetch(
                "https://api.github.com/repos/owner/demo-hub" to ok("""{"default_branch":"main"}"""),
                "https://codeload.github.com/owner/demo-hub/tar.gz/refs/heads/main" to
                    ok(hubTarball())
            )
            val result = installer(fetch).install("owner/demo-hub/skills/nowhere", nowMs = 1)

            val reason = (result as SkillInstallResult.Refused).reason
            assertThat(reason).contains("no SKILL.md")
            assertThat(reason).contains("skills/nowhere")
            assertThat(store.rows).isEmpty()
            assertThat(skillsDir.listFiles().orEmpty().filterNot { it.name.startsWith(".") }).isEmpty()
        }

    @Test
    fun `a forbidden executable in the archive refuses the whole install`() = runBlocking<Unit> {
        val fetch = FakeFetch(
            "https://api.github.com/repos/owner/demo-hub" to ok("""{"default_branch":"main"}"""),
            "https://codeload.github.com/owner/demo-hub/tar.gz/refs/heads/main" to
                ok(
                    hubTarball(
                        "demo-hub-main",
                        skillMdFor("1.13.0"),
                        "demo-hub-main/skills/binance-web3/binance-agentic-wallet/native.node" to "binary"
                    )
                )
        )
        val result = installer(fetch).install("owner/demo-hub/skills/w", nowMs = 1)

        val reason = (result as SkillInstallResult.Refused).reason
        // The plugin installer's rulebook, word for word: a native addon is
        // code that would load into a process, so the package is refused, not
        // stripped.
        assertThat(reason).contains("native.node")
        assertThat(reason).contains("native")
        assertThat(store.rows).isEmpty()
        assertThat(File(skillsDir, "binance-agentic-wallet").exists()).isFalse()
    }

    @Test
    fun `a skill md without a name is refused as an invalid skill`() = runBlocking<Unit> {
        val fetch = FakeFetch(
            "https://api.github.com/repos/owner/demo-hub" to ok("""{"default_branch":"main"}"""),
            "https://codeload.github.com/owner/demo-hub/tar.gz/refs/heads/main" to
                ok(hubTarball(skillMd = "---\ndescription: no name\n---\nBody"))
        )
        val result = installer(fetch).install("owner/demo-hub/skills/w", nowMs = 1)

        val reason = (result as SkillInstallResult.Refused).reason
        assertThat(reason).contains("name")
        assertThat(store.rows).isEmpty()
    }

    @Test
    fun `an unreachable repository is refused with the branches it tried`() = runBlocking<Unit> {
        val fetch = FakeFetch() // everything 404s
        val result = installer(fetch).install("owner/missing-repo/skills/w", nowMs = 1)

        val reason = (result as SkillInstallResult.Refused).reason
        assertThat(reason).contains("could not fetch owner/missing-repo")
        assertThat(reason).contains("main")
        assertThat(reason).contains("master")
        // The tarball was actually attempted on both fallback branches.
        assertThat(fetch.requests.any { it.contains("refs/heads/main") }).isTrue()
        assertThat(fetch.requests.any { it.contains("refs/heads/master") }).isTrue()
    }

    @Test
    fun `an invalid spec is refused before any network call`() = runBlocking<Unit> {
        val fetch = FakeFetch()
        val result = installer(fetch).install("../etc/passwd", nowMs = 1)

        assertThat(result as SkillInstallResult.Refused).isNotNull()
        assertThat(fetch.requests).isEmpty()
    }

    @Test
    fun `a rate limited api falls back to main and records the branch as the ref`() =
        runBlocking<Unit> {
            val fetch = FakeFetch(
                "https://api.github.com/repos/owner/demo-hub" to SkillHttpResponse(403, ByteArray(0), "rate limited"),
                "https://codeload.github.com/owner/demo-hub/tar.gz/refs/heads/main" to ok(hubTarball())
            )
            val result = installer(fetch).install("owner/demo-hub/skills/w", nowMs = 1) as SkillInstallResult.Installed
            assertThat(result.entity.commitRef).isEqualTo("main")
        }

    @Test
    fun `a tree url pins the branch it names`() = runBlocking<Unit> {
        val fetch = FakeFetch(
            "https://api.github.com/repos/owner/demo-hub/branches/legacy" to
                ok("""{"commit":{"sha":"feed0001"}}"""),
            "https://codeload.github.com/owner/demo-hub/tar.gz/refs/heads/legacy" to ok(hubTarball())
        )
        val result = installer(fetch).install(
            "https://github.com/owner/demo-hub/tree/legacy/skills/w",
            nowMs = 1
        ) as SkillInstallResult.Installed

        assertThat(result.entity.commitRef).isEqualTo("feed0001")
        assertThat(fetch.requests.any { it.contains("refs/heads/legacy") }).isTrue()
        assertThat(fetch.requests.none { it.contains("refs/heads/main") }).isTrue()
    }

    // ------------------------------------------------------------- re-installing

    @Test
    fun `reinstalling replaces the directory and updates the row in place`() = runBlocking<Unit> {
        // The FIRST install carries a file the second one does not, so the
        // replace (not merge) of the directory is observable.
        val firstFetch = FakeFetch(
            "https://api.github.com/repos/owner/demo-hub" to ok("""{"default_branch":"main"}"""),
            "https://api.github.com/repos/owner/demo-hub/branches/main" to
                ok("""{"commit":{"sha":"abc"}}"""),
            "https://codeload.github.com/owner/demo-hub/tar.gz/refs/heads/main" to
                ok(
                    hubTarball(
                        "demo-hub-main",
                        skillMdFor("1.13.0"),
                        "demo-hub-main/skills/binance-web3/binance-agentic-wallet/only-first.md" to "first"
                    )
                )
        )
        installer(firstFetch).install("owner/demo-hub/skills/w", nowMs = 1_000)
        val first = store.rows.getValue("binance-agentic-wallet")
        store.upsert(first.copy(enabled = false))

        // A different repo, the same skill name: same id, replaced content.
        val fetch = FakeFetch(
            "https://api.github.com/repos/other/demo-hub" to ok("""{"default_branch":"main"}"""),
            "https://api.github.com/repos/other/demo-hub/branches/main" to
                ok("""{"commit":{"sha":"newsha"}}"""),
            "https://codeload.github.com/other/demo-hub/tar.gz/refs/heads/main" to
                ok(hubTarball(skillMd = skillMdFor("2.0.0")))
        )
        val result = SkillInstaller(skillsDir, fetch, store)
            .install("other/demo-hub/skills/w", nowMs = 2_000) as SkillInstallResult.Installed

        assertThat(result.updated).isTrue()
        val second = store.rows.getValue("binance-agentic-wallet")
        assertThat(second.id).isEqualTo(first.id)
        assertThat(second.installedAt).isEqualTo(1_000) // the first install's moment
        assertThat(second.updatedAt).isEqualTo(2_000)
        assertThat(second.version).isEqualTo("2.0.0")
        assertThat(second.commitRef).isEqualTo("newsha")
        assertThat(second.enabled).isFalse() // the user's switch survives an update

        // The directory was REPLACED, not merged: the reference file that only
        // the first install carried is gone.
        assertThat(File(skillsDir, "binance-agentic-wallet/references/send.md").isFile).isTrue()
        assertThat(
            File(skillsDir, "binance-agentic-wallet").walkTopDown().any { it.name == "only-first.md" }
        ).isFalse()
    }

    // ------------------------------------------------------------- fixtures

    private fun ok(body: String) = 200 to body.toByteArray(Charsets.UTF_8)

    /** The same answer for a fixture that is already bytes — a tarball. */
    private fun ok(body: ByteArray) = 200 to body

    /** The API answers a fake needs for one repo whose skill lives at skills/w. */
    private fun hubFetch(host: String = "owner") = FakeFetch(
        "https://api.github.com/repos/$host/demo-hub/branches/" to ok("""{"commit":{"sha":"abc"}}"""),
        "https://api.github.com/repos/$host/demo-hub" to ok("""{"default_branch":"main"}"""),
        "https://codeload.github.com/$host/demo-hub/tar.gz/refs/heads/main" to ok(hubTarball())
    )

    private val SKILL_MD_BYTES = skillMdFor("1.13.0").toByteArray(Charsets.UTF_8)

    /** A SKILL.md shaped like the hub's, with the version as the one dial. */
    private fun skillMdFor(version: String): String =
        "---\n" +
            "name: binance-agentic-wallet\n" +
            "description: |\n" +
            "  Wallet operations: connect, balance, send, swap.\n" +
            "metadata:\n" +
            "  version: '$version'\n" +
            "  openclaw:\n" +
            "    install:\n" +
            "      - kind: node\n" +
            "        package: '@binance/agentic-wallet'\n" +
            "        bins: [baw]\n" +
            "---\n" +
            "\n" +
            "# Binance Agentic Wallet\n" +
            "\n" +
            "Drive the `baw` CLI. Never ask for a seed phrase.\n"

    /**
     * A codeload-shaped tree: everything under one `<repo>-<branch>/` folder,
     * the skill at skills/binance-web3/binance-agentic-wallet, a hub README
     * OUTSIDE the skill (which must not leak into the install).
     */
    private fun hubTarball(
        repoRoot: String = "demo-hub-main",
        skillMd: String = skillMdFor("1.13.0"),
        vararg extra: Pair<String, String>
    ): ByteArray {
        val skillDir = "$repoRoot/skills/binance-web3/binance-agentic-wallet"
        val entries = mutableListOf(
            tarEntry("$skillDir/SKILL.md", skillMd.toByteArray(Charsets.UTF_8)),
            tarEntry("$skillDir/references/send.md", "send: `baw wallet send`".toByteArray()),
            tarEntry("$repoRoot/README.md", "hub readme".toByteArray())
        )
        for ((name, text) in extra) {
            entries += tarEntry(name, text.toByteArray(Charsets.UTF_8))
        }
        return gzipBytes(tarArchive(*entries.toTypedArray()))
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // The ustar writer below mirrors the builders beside TarGz's own tests
    // (android/node/src/test/.../TarGzTest.kt); those are `internal` to the
    // :node module, so the :app tests carry this small private copy.

    private fun tarHeader(name: String, size: Long, type: Char = '0', prefix: String = ""): ByteArray {
        val block = ByteArray(512)
        name.toByteArray(Charsets.US_ASCII).copyInto(block, 0, 0, minOf(name.length, 100))
        prefix.toByteArray(Charsets.US_ASCII).copyInto(block, 345, 0, minOf(prefix.length, 155))
        val octal = size.toString(8).padStart(11, '0') + "\u0000"
        octal.toByteArray(Charsets.US_ASCII).copyInto(block, 124)
        block[156] = type.code.toByte()
        return block
    }

    private fun tarEntry(
        name: String,
        content: ByteArray,
        type: Char = '0',
        prefix: String = ""
    ): ByteArray {
        val padded = ByteArray((content.size + 511) / 512 * 512)
        content.copyInto(padded)
        return tarHeader(name, content.size.toLong(), type, prefix) + padded
    }

    private fun tarArchive(vararg entries: ByteArray): ByteArray =
        entries.fold(ByteArray(0)) { archive, entry -> archive + entry } + ByteArray(1024)

    private fun gzipBytes(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }
}
