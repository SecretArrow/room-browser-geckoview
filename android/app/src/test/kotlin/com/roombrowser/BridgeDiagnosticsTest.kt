package com.roombrowser

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Guards the contract between the engine's bridge log and the instrumented
 * probe that reads it.
 *
 * WHY THE PROBE NEEDS THE LOG AT ALL. The page bridge has no failure mode the
 * app can see. GeckoView does not report a content script that never injected,
 * a port that never connected, or a page that never called; in every one of
 * those cases the dApp simply sees no wallet, and the emulator run ends on an
 * assertion about a sheet that did not appear. Three different faults, one
 * symptom, and each used to cost a full E2E cycle to guess at -- which is what
 * happened while this was being written.
 *
 * So the engine writes one line per boundary under one tag:
 *
 *     extension installed  ->  port connected  ->  port message
 *
 * and the probe dumps that tag on failure. The lines are read as a SEQUENCE:
 * whichever boundary a run never reached is the hypothesis that survives, and
 * no single line answers the question on its own.
 *
 * WHY THAT NEEDS A GUARD IN THE FAST JOB. The probe filters by the tag, and
 * androidTest cannot import it -- `:engine` is an `implementation` dependency,
 * so the constant is not on `:app`'s compile classpath and the value is
 * declared a second time in the test. Two copies of one string that neither a
 * compiler nor a reader compares is the exact shape of the manifest bug this
 * suite already carries a guard for, and the consequence here is worse than a
 * failed test: a drifted tag makes the dump come back empty, and an empty dump
 * reads as "the bridge never ran" -- a wrong answer delivered with the same
 * confidence as a right one, twenty minutes into the job whose whole purpose
 * was to get that answer. Deleting a log site is quieter still: the dump
 * prints, and the boundary being looked for is simply absent.
 *
 * Like the sibling guards in this suite this reads source rather than
 * reflecting, because what it checks exists as text across two modules that no
 * compiler compares.
 */
class BridgeDiagnosticsTest {

    @Test
    fun the_probe_filters_by_the_tag_the_engine_logs_under() {
        val engineTag = tagIn(source(ENGINE_LOG))
        assertThat(engineTag).isNotNull()
        // The probe declares its own copy (it cannot import the engine's) --
        // so the two VALUES are what have to agree, not the two spellings.
        assertThat(tagIn(source(WALLET_E2E))).isEqualTo(engineTag)
        // And the command has to use that constant rather than the literal.
        // Asserted on the interpolation for a reason: a dump that stopped
        // filtering would otherwise become a slice of GeckoView's chatter and
        // still look like a working probe.
        assertThat(source(WALLET_E2E).readText())
            .contains("logcat -d -s \$BRIDGE_LOG_TAG")
    }

    @Test
    fun every_boundary_that_makes_the_dump_readable_is_still_written() {
        // One phrase per boundary, because the dump is read by phrase: a
        // reader scanning it looks for these, and re-wording one without
        // re-wording this list leaves the probe reporting a boundary that no
        // longer exists.
        val host = source(ENGINE_HOST).readText()
        val session = source(ENGINE_SESSION).readText()
        assertThat(host).contains("extension installed")
        assertThat(session).contains("port connected")
        assertThat(session).contains("port message type=")
        assertThat(session).contains("port disconnected")
        // The unknown-type branch matters as much as the known ones: without
        // it, a message whose type was renamed on either side of the port is
        // dropped in silence, and the dump then shows a port that received
        // nothing instead of one that received something it did not know.
        assertThat(session).contains("unknown type=")
    }

    /** The value a `BRIDGE_LOG_TAG = "..."` declaration binds, if there is one. */
    private fun tagIn(file: File): String? =
        Regex("BRIDGE_LOG_TAG\\s*=\\s*\"([^\"]+)\"")
            .find(file.readText())
            ?.groupValues
            ?.get(1)

    /**
     * A source file, from wherever this test happens to run.
     *
     * A Gradle test's working directory is its module directory, so the first
     * candidate is the one that matches -- but each is checked rather than
     * assumed, because a guard that silently reads nothing passes for the
     * wrong reason. The module-relative and repository-relative spellings
     * differ per file, so both are named per file rather than derived.
     */
    private fun source(candidates: List<String>): File =
        candidates.map(::File).firstOrNull { it.isFile }
            ?: error(
                "none of $candidates exists from ${File(".").absolutePath}; " +
                    "this guard would otherwise pass without reading anything"
            )

    private companion object {
        val ENGINE_LOG = listOf(
            "../engine/src/main/kotlin/com/roombrowser/engine/gecko/BridgeLog.kt",
            "engine/src/main/kotlin/com/roombrowser/engine/gecko/BridgeLog.kt"
        )
        val ENGINE_HOST = listOf(
            "../engine/src/main/kotlin/com/roombrowser/engine/gecko/GeckoEngineHost.kt",
            "engine/src/main/kotlin/com/roombrowser/engine/gecko/GeckoEngineHost.kt"
        )
        val ENGINE_SESSION = listOf(
            "../engine/src/main/kotlin/com/roombrowser/engine/gecko/GeckoEngineSession.kt",
            "engine/src/main/kotlin/com/roombrowser/engine/gecko/GeckoEngineSession.kt"
        )
        val WALLET_E2E = listOf(
            "src/androidTest/kotlin/com/roombrowser/WalletE2eTest.kt",
            "app/src/androidTest/kotlin/com/roombrowser/WalletE2eTest.kt"
        )
    }
}
