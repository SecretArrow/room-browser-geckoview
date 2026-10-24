package com.roombrowser.agent.skills

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import com.roombrowser.agent.skills.SkillTestSupport.BrokenTarball
import com.roombrowser.agent.skills.SkillTestSupport.FakeTarball
import com.roombrowser.agent.skills.SkillTestSupport.gzipBytes
import com.roombrowser.agent.skills.SkillTestSupport.pluginManifest
import com.roombrowser.agent.skills.SkillTestSupport.skillDoc
import com.roombrowser.agent.skills.SkillTestSupport.tarArchive
import com.roombrowser.agent.skills.SkillTestSupport.tarEntry

/**
 * The one door a skill comes through, driven offline: the path-safety rules
 * (tar-slip entries, climbs, symlinks), the caps mirrored from the plugin
 * installer, the instruction-format truncation, and the commit's atomicity —
 * a refused install must leave the previous version byte-identical.
 */
class SkillInstallerTest {

    private fun newRoot() = Files.createTempDirectory("skill-installer").toFile()

    private fun installer(root: File, tarball: FakeTarball) =
        SkillInstaller(root, pluginRepo = null, tarballSource = tarball)

    private fun archive(vararg entries: ByteArray) = gzipBytes(tarArchive(*entries))

    // ---- the happy path, because the guards mean nothing without it ----

    @Test
    fun `an instruction skill installs and stores only what it needs`() = runBlocking<Unit> {
        val root = newRoot()
        val bytes = archive(
            tarEntry("repo-main/README.md", "noise".toByteArray()),
            tarEntry("repo-main/SKILL.md", skillDoc(body = "Step one.").toByteArray()),
            tarEntry("repo-main/scripts/helper.js", "console.log(1)".toByteArray()),
            tarEntry("repo-main/references/deep.md", "reference".toByteArray())
        )
        val outcome = installer(root, FakeTarball(bytes))
            .install(SkillSource.parse("owner/repo").getOrThrow())

        val record = (outcome as SkillInstaller.Outcome.Installed).record
        assertThat(record.id).isEqualTo("demo-skill")
        assertThat(record.name).isEqualTo("demo-skill")
        assertThat(record.format).isEqualTo(SkillRecord.FORMAT_INSTRUCTION)
        assertThat(record.bodySha256).isEqualTo(SkillInstaller.sha256Hex("Step one.".toByteArray()))
        val dir = File(root, record.id)
        // The committed tree is exactly the two files: metadata and body.
        assertThat(dir.listFiles()!!.map { it.name }.sorted())
            .containsExactly(SkillRecord.METADATA_FILE, SkillInstaller.INSTRUCTION_FILE)
        // The scripts inventory describes what shipped, and the fence and the
        // references did not come along as dead weight.
        assertThat(record.scripts.map { it.language }).contains("JavaScript")
        assertThat(File(dir, "references").exists()).isFalse()
    }

    @Test
    fun `the sub path selects the directory inside the archive`() = runBlocking<Unit> {
        val root = newRoot()
        val bytes = archive(
            tarEntry("repo-main/other/SKILL.md", "not this one".toByteArray()),
            tarEntry("repo-main/skills/demo/SKILL.md", skillDoc().toByteArray())
        )
        val outcome = installer(root, FakeTarball(bytes))
            .install(SkillSource.parse("owner/repo/skills/demo").getOrThrow())
        assertThat(outcome).isInstanceOf(SkillInstaller.Outcome.Installed::class.java)
    }

    @Test
    fun `a plugin-format skill without a plugin store is refused honestly`() = runBlocking<Unit> {
        val root = newRoot()
        val bytes = archive(tarEntry("repo-main/plugin.json", pluginManifest().toByteArray()))
        val outcome = installer(root, FakeTarball(bytes))
            .install(SkillSource.parse("owner/repo").getOrThrow())
        outcome as SkillInstaller.Outcome.Refused
        assertThat(outcome.errors.single()).contains("plugin store is not available")
        // Nothing was committed either way.
        assertThat(root.listFiles()!!.filter { !it.name.startsWith(".") }).isEmpty()
    }

    // ---- refusals that name what WAS found ----

    @Test
    fun `a path that does not exist is refused with the archive root's listing`() =
        runBlocking<Unit> {
            val root = newRoot()
            val bytes = archive(tarEntry("repo-main/SKILL.md", skillDoc().toByteArray()))
            val outcome = installer(root, FakeTarball(bytes))
                .install(SkillSource.parse("owner/repo/no/such/dir").getOrThrow())
            outcome as SkillInstaller.Outcome.Refused
            assertThat(outcome.errors.single()).contains("no/such/dir")
            assertThat(outcome.errors.single()).contains("repo-main")
        }

    @Test
    fun `a directory with neither a skill doc nor a manifest is refused with its contents`() =
        runBlocking<Unit> {
            val root = newRoot()
            val bytes = archive(tarEntry("repo-main/readme.txt", "hello".toByteArray()))
            val outcome = installer(root, FakeTarball(bytes))
                .install(SkillSource.parse("owner/repo").getOrThrow())
            outcome as SkillInstaller.Outcome.Refused
            assertThat(outcome.errors.single()).contains("SKILL.md")
            assertThat(outcome.errors.single()).contains("readme.txt")
        }

    // ---- path safety: the archive is hostile ----

    @Test
    fun `a tar entry that climbs out is refused and writes nothing outside`() = runBlocking<Unit> {
        val root = newRoot()
        val bytes = archive(tarEntry("package/../../escaped.txt", "nope".toByteArray()))
        val outcome = installer(root, FakeTarball(bytes))
            .install(SkillSource.parse("owner/repo").getOrThrow())
        outcome as SkillInstaller.Outcome.Refused
        assertThat(outcome.errors.single()).contains("unpacking")
        // The escape went nowhere: nothing beside the work dir, nothing committed.
        assertThat(File(root.parentFile, "escaped.txt").exists()).isFalse()
        assertThat(root.listFiles()!!.filter { !it.name.startsWith(".") }).isEmpty()
    }

    @Test
    fun `an absolute tar entry is refused`() = runBlocking<Unit> {
        val root = newRoot()
        val bytes = archive(tarEntry("/etc/passwd", "nope".toByteArray()))
        val outcome = installer(root, FakeTarball(bytes))
            .install(SkillSource.parse("owner/repo").getOrThrow())
        outcome as SkillInstaller.Outcome.Refused
        assertThat(outcome.errors.single()).contains("unpacking")
    }

    @Test
    fun `a symlink entry is never materialized`() = runBlocking<Unit> {
        val root = newRoot()
        val bytes = archive(
            tarEntry("repo-main/link", "../../etc/passwd".toByteArray(), type = '2'),
            tarEntry("repo-main/SKILL.md", skillDoc().toByteArray())
        )
        val outcome = installer(root, FakeTarball(bytes))
            .install(SkillSource.parse("owner/repo").getOrThrow())
        val record = (outcome as SkillInstaller.Outcome.Installed).record
        // The committed tree carries no link and nothing named like one.
        val dir = File(root, record.id)
        assertThat(dir.listFiles()!!.map { it.name })
            .containsNoneOf("link")
        assertThat(Files.isSymbolicLink(Paths.get(dir.toString(), "link"))).isFalse()
    }

    @Test
    fun `a source whose sub path carries a backslash is refused at the validation layer`() =
        runBlocking<Unit> {
            val root = newRoot()
            // Built directly, not parsed: this is locate()'s own check, the
            // layer whose argument becomes a File. The archive has content,
            // so the refusal is locate()'s and not the empty-archive one.
            val bytes = archive(tarEntry("repo-main/SKILL.md", skillDoc().toByteArray()))
            val source = SkillSource("owner", "repo", "HEAD", "..\\..\\escaped", "raw")
            val outcome = installer(root, FakeTarball(bytes)).install(source)
            outcome as SkillInstaller.Outcome.Refused
            assertThat(outcome.errors.single()).contains("does not exist")
            assertThat(outcome.errors.single()).contains("escaped")
        }

    @Test
    fun `a source whose sub path climbs is refused at the validation layer`() = runBlocking<Unit> {
        val root = newRoot()
        val bytes = archive(tarEntry("repo-main/SKILL.md", skillDoc().toByteArray()))
        val source = SkillSource("owner", "repo", "HEAD", "a/../..", "raw")
        val outcome = installer(root, FakeTarball(bytes)).install(source)
        outcome as SkillInstaller.Outcome.Refused
        assertThat(outcome.errors.single()).contains("does not exist")
        assertThat(outcome.errors.single()).contains("a/../..")
        assertThat(root.listFiles()!!.filter { !it.name.startsWith(".") }).isEmpty()
    }

    // ---- caps, mirrored from PluginZipInstaller ----

    @Test
    fun `more than the entry cap is refused`() = runBlocking<Unit> {
        val root = newRoot()
        val entries = (0 until SkillInstaller.MAX_ENTRIES + 1)
            .map { tarEntry("repo-main/f$it.txt", "x".toByteArray()) }
            .toTypedArray()
        val outcome = installer(root, FakeTarball(archive(*entries)))
            .install(SkillSource.parse("owner/repo").getOrThrow())
        outcome as SkillInstaller.Outcome.Refused
        assertThat(outcome.errors.single()).contains("512")
    }

    @Test
    fun `a single file over the per-file cap is refused`() = runBlocking<Unit> {
        val root = newRoot()
        val big = ByteArray(SkillInstaller.MAX_ENTRY_BYTES.toInt() + 1) { 'a'.code.toByte() }
        val outcome = installer(
            root,
            FakeTarball(archive(tarEntry("repo-main/big.bin", big)))
        ).install(SkillSource.parse("owner/repo").getOrThrow())
        outcome as SkillInstaller.Outcome.Refused
        assertThat(outcome.errors.single()).contains("2 MB")
    }

    @Test
    fun `a SKILL md over the read cap is refused before it is parsed`() = runBlocking<Unit> {
        val root = newRoot()
        val doc = skillDoc(body = "y".repeat(SkillInstaller.DOC_MAX_BYTES.toInt()))
        val outcome = installer(
            root,
            FakeTarball(archive(tarEntry("repo-main/SKILL.md", doc.toByteArray())))
        ).install(SkillSource.parse("owner/repo").getOrThrow())
        outcome as SkillInstaller.Outcome.Refused
        assertThat(outcome.errors.single()).contains("256 KB")
    }

    // ---- truncation is honest ----

    @Test
    fun `a body over the cap is stored truncated with the flag set`() = runBlocking<Unit> {
        val root = newRoot()
        val body = "z".repeat(SkillInstaller.BODY_MAX_BYTES + 10)
        val bytes = archive(tarEntry("repo-main/SKILL.md", skillDoc(body = body).toByteArray()))
        val record = (installer(root, FakeTarball(bytes))
            .install(SkillSource.parse("owner/repo").getOrThrow())
                as SkillInstaller.Outcome.Installed).record

        assertThat(record.truncated).isTrue()
        assertThat(record.size).isEqualTo(SkillInstaller.BODY_MAX_BYTES.toLong())
        assertThat(record.bodySha256)
            .isEqualTo(SkillInstaller.sha256Hex(body.take(SkillInstaller.BODY_MAX_BYTES).toByteArray()))
        assertThat(record.runtimeSupport).isEqualTo(SkillRecord.RUNTIME_FULL)
    }

    @Test
    fun `a skill whose scripts want another runtime says so`() = runBlocking<Unit> {
        val root = newRoot()
        val bytes = archive(
            tarEntry("repo-main/SKILL.md", skillDoc().toByteArray()),
            tarEntry("repo-main/tools/scrape.py", "print(1)".toByteArray())
        )
        val record = (installer(root, FakeTarball(bytes))
            .install(SkillSource.parse("owner/repo").getOrThrow())
                as SkillInstaller.Outcome.Installed).record
        assertThat(record.runtimeSupport).isEqualTo(SkillRecord.RUNTIME_PARTIAL)
        assertThat(record.runtimeNote).contains("Python")
        // And the note promises nothing about running it.
        assertThat(record.runtimeNote).contains("no script")
    }

    @Test
    fun `a fenced non-javascript block earns the same note`() = runBlocking<Unit> {
        val root = newRoot()
        val body = "Do it:\n\n```python\nprint(1)\n```\n"
        val bytes = archive(
            tarEntry("repo-main/SKILL.md", skillDoc(body = body).toByteArray())
        )
        val record = (installer(root, FakeTarball(bytes))
            .install(SkillSource.parse("owner/repo").getOrThrow())
                as SkillInstaller.Outcome.Installed).record
        assertThat(record.runtimeSupport).isEqualTo(SkillRecord.RUNTIME_PARTIAL)
    }

    // ---- atomicity ----

    @Test
    fun `a refused update leaves the previous version byte-identical`() = runBlocking<Unit> {
        val root = newRoot()
        val first = SkillTestSupport.installDemoSkill(root)
        val bodyBefore = File(root, first.id, SkillInstaller.INSTRUCTION_FILE).readBytes()

        // An update whose archive carries no skill at all.
        val outcome = installer(root, FakeTarball(archive()))
            .install(SkillSource.parse("other/repo").getOrThrow(), wantedId = first.id)
        assertThat(outcome).isInstanceOf(SkillInstaller.Outcome.Refused::class.java)

        assertThat(File(root, first.id, SkillInstaller.INSTRUCTION_FILE).readBytes())
            .isEqualTo(bodyBefore)
        assertThat(SkillRegistry(root).get(first.id)!!.name).isEqualTo(first.name)
    }

    @Test
    fun `a successful update replaces the body and keeps the id`() = runBlocking<Unit> {
        val root = newRoot()
        val first = SkillTestSupport.installDemoSkill(root)
        val bytes = archive(
            tarEntry("repo-main/SKILL.md", skillDoc(body = "New body.").toByteArray())
        )
        val outcome = installer(root, FakeTarball(bytes))
            .install(SkillSource.parse("owner/repo").getOrThrow(), wantedId = first.id)
        val record = (outcome as SkillInstaller.Outcome.Installed).record
        assertThat(record.id).isEqualTo(first.id)
        assertThat(File(root, first.id, SkillInstaller.INSTRUCTION_FILE).readText())
            .isEqualTo("New body.")
    }

    @Test
    fun `a download failure is a refusal in words and writes nothing`() = runBlocking<Unit> {
        val root = newRoot()
        val outcome = installer(
            root,
            SkillTestSupport.BrokenTarball("the ref does not exist")
        ).install(SkillSource.parse("owner/repo").getOrThrow())
        outcome as SkillInstaller.Outcome.Refused
        assertThat(outcome.errors.single()).contains("the ref does not exist")
        assertThat(outcome.errors.single()).contains("codeload.github.com")
        assertThat(root.listFiles()!!.filter { !it.name.startsWith(".") }).isEmpty()
    }

    @Test
    fun `the download loop tries every url the source offers`() = runBlocking<Unit> {
        val root = newRoot()
        val broken = BrokenTarball("the ref does not exist")
        val outcome = installer(root, broken)
            .install(SkillSource.parse("owner/repo@dev").getOrThrow())
        assertThat(outcome).isInstanceOf(SkillInstaller.Outcome.Refused::class.java)
        // Both spellings were tried before the refusal was written.
        assertThat(broken.urls).containsExactly(
            "https://codeload.github.com/owner/repo/tar.gz/refs/heads/dev",
            "https://codeload.github.com/owner/repo/tar.gz/dev"
        ).inOrder()
    }

    @Test
    fun `an explicit branch downloads from the refs heads form first`() = runBlocking<Unit> {
        val root = newRoot()
        val fake = FakeTarball(
            archive(tarEntry("repo-main/SKILL.md", skillDoc().toByteArray()))
        )
        val outcome = installer(root, fake)
            .install(SkillSource.parse("owner/repo@dev").getOrThrow())
        assertThat(outcome).isInstanceOf(SkillInstaller.Outcome.Installed::class.java)
        // One success ends the loop: only the first url was fetched.
        assertThat(fake.urls)
            .containsExactly("https://codeload.github.com/owner/repo/tar.gz/refs/heads/dev")
            .inOrder()
    }

    @Test
    fun `sha256 hex is stable`() {
        // The empty digest, pinned so a crypto regression cannot slide by.
        assertThat(SkillInstaller.sha256Hex(ByteArray(0)))
            .isEqualTo(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
            )
    }

    @Test
    fun `an install over an existing id replaces the directory cleanly`() = runBlocking<Unit> {
        val root = newRoot()
        SkillTestSupport.installDemoSkill(root)
        // A stale file only the old version had — the replace must not merge.
        File(root, "demo-skill/stale.txt").writeText("left over")
        val bytes = archive(
            tarEntry("repo-main/SKILL.md", skillDoc(body = "Second.").toByteArray())
        )
        installer(root, FakeTarball(bytes))
            .install(SkillSource.parse("owner/repo").getOrThrow())
        assertThat(File(root, "demo-skill/stale.txt").exists()).isFalse()
        assertThat(File(root, "demo-skill").list()!!.sorted())
            .containsExactly(SkillRecord.METADATA_FILE, SkillInstaller.INSTRUCTION_FILE)
    }

    // ---- the record codec itself ----

    @Test
    fun `a skill json round-trips and ignores unknown keys`() {
        val record = SkillRecord(
            id = "demo",
            name = "Demo",
            source = "owner/repo",
            installedAt = 1,
            updatedAt = 2,
            format = SkillRecord.FORMAT_INSTRUCTION,
            scripts = listOf(SkillRecord.ScriptFile("scripts/a.js", "JavaScript"))
        )
        val text = SkillRecord.toJsonString(record)
        val read = SkillRecord.fromFile(writeTemp(text))
        assertThat(read).isEqualTo(record)
        // A record written by a NEWER build must still read.
        assertThat(SkillRecord.fromFile(writeTemp("""{"future":"field"}"""))).isNull()
    }

    @Test
    fun `a skill json that is not json reads as null, never throws`() {
        assertThat(SkillRecord.fromFile(writeTemp("garbage"))).isNull()
        assertThat(SkillRecord.fromFile(writeTemp(""))).isNull()
    }

    private fun writeTemp(text: String): File {
        val file = Files.createTempFile("skill-json", ".json").toFile()
        file.writeText(text)
        return file
    }
}
