package com.roombrowser.agent.skills

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Shared fixtures for the skills tests: a hand-built ustar (the same shape
 * codeload answers with, so no network and no tar library), a counting
 * tarball source that stands in for the network, and one call that installs
 * a known instruction skill. Kept in ONE file so the three suites install
 * skills through exactly the same door the app does.
 */
internal object SkillTestSupport {

    // ---- a minimal hostile-input-capable tar builder ----

    internal fun tarHeader(name: String, size: Long, type: Char = '0', prefix: String = ""): ByteArray {
        val block = ByteArray(512)
        name.toByteArray(Charsets.US_ASCII).copyInto(block, 0, 0, minOf(name.length, 100))
        prefix.toByteArray(Charsets.US_ASCII).copyInto(block, 345, 0, minOf(prefix.length, 155))
        val octal = size.toString(8).padStart(11, '0') + "\u0000"
        octal.toByteArray(Charsets.US_ASCII).copyInto(block, 124)
        block[156] = type.code.toByte()
        return block
    }

    internal fun tarEntry(name: String, content: ByteArray, type: Char = '0'): ByteArray {
        val padded = ByteArray((content.size + 511) / 512 * 512)
        content.copyInto(padded)
        return tarHeader(name, content.size.toLong(), type) + padded
    }

    internal fun tarArchive(vararg entries: ByteArray): ByteArray =
        entries.fold(ByteArray(0)) { archive, entry -> archive + entry } + ByteArray(1024)

    internal fun gzipBytes(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    // ---- documents ----

    /** A SKILL.md in the shape an instruction skill is read from. */
    internal fun skillDoc(
        name: String = "demo-skill",
        description: String = "A demo skill",
        body: String = "Step one. Step two."
    ): String =
        "---\nname: $name\ndescription: $description\nversion: 1.0.0\n---\n\n$body\n"

    /** A plugin.json that carries the strict manifest the plugin store wants. */
    internal fun pluginManifest(id: String = "demo-plugin"): String =
        """{"id":"$id","name":"Demo Plugin","version":"1.0.0","description":"d",""" +
            """"entry":"index.js","permissions":["storage"],"networkAllow":["https://example.com"]}"""

    // ---- tarball sources ----

    /**
     * Serves [bytes] for every URL and remembers what it was asked for, so a
     * test can assert the URL the installer built (and that a refused install
     * never fetched anything at all).
     */
    internal class FakeTarball(private val bytes: ByteArray) : SkillInstaller.TarballSource {
        val urls = mutableListOf<String>()

        override fun fetch(url: String): ByteArray {
            urls.add(url)
            return bytes
        }
    }

    /** Fails every fetch with [message] — the download-refused path, offline. */
    internal class BrokenTarball(private val message: String = "the test network is down") :
        SkillInstaller.TarballSource {

        /** Every url it was asked for — the loop's attempts, in order. */
        val urls = mutableListOf<String>()

        val calls: Int get() = urls.size

        override fun fetch(url: String): ByteArray {
            urls.add(url)
            throw java.io.IOException(message)
        }
    }

    // ---- one known-good install ----

    /**
     * Installs an instruction skill named `demo` from a source the tests
     * treat as the fixture's own, through a real installer whose "network"
     * serves the archive built above, and answers the record it committed.
     */
    internal fun installDemoSkill(root: java.io.File): SkillRecord {
        val bytes = gzipBytes(
            tarArchive(
                tarEntry("repo-main/SKILL.md", skillDoc().toByteArray())
            )
        )
        val installer = SkillInstaller(root, pluginRepo = null, tarballSource = FakeTarball(bytes))
        val source = SkillSource.parse("owner/repo").getOrThrow()
        val outcome = runBlocking { installer.install(source) }
        check(outcome is SkillInstaller.Outcome.Installed) {
            "the fixture install failed: ${(outcome as? SkillInstaller.Outcome.Refused)?.errors}"
        }
        return outcome.record
    }
}
