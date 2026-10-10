package com.roombrowser

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.repo.ProfileRepositoryImpl
import com.roombrowser.domain.export.ProfileBackup
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E for the bulk profile transfer: several profiles exported into ONE file
 * and restored in ONE Room transaction, all or nothing.
 *
 * The restore tests drive ProfileRepositoryImpl.importBundle directly — the
 * same seam MainViewModel.finalizeBundleImport calls — because that is where
 * the transaction guarantee lives. Bookmarks are read back through
 * BrowserRepository, reachable from the app graph.
 */
@RunWith(AndroidJUnit4::class)
class BulkTransferE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext
    private val appContext: RoomBrowserApp =
        instrumentation.targetContext.applicationContext as RoomBrowserApp

    private val tag = (System.currentTimeMillis() % 100000).toString()

    // ---------- UiAutomator helpers (proven patterns) --------------------

    private fun launchMainActivity() {
        val intent = targetContext.packageManager.getLaunchIntentForPackage(targetContext.packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                setClassName(targetContext.packageName, "com.roombrowser.main.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)
    }

    private fun hasText(text: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.text(text)), timeoutMs)

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            try { Thread.sleep(250) } catch (_: InterruptedException) { }
        }
        return condition()
    }

    private fun clickCenter(node: UiObject2): Boolean = try {
        val b = node.visibleBounds
        device.executeShellCommand("input tap ${b.centerX()} ${b.centerY()}")
        device.waitForIdle(1_000)
        true
    } catch (_: Exception) {
        false
    }

    private fun clickSmart(node: UiObject2): Boolean {
        // Shell `input tap`, never UiObject2.click(): the latter waits for an
        // accessibility-idle window and times out on busy screens.
        var current: UiObject2? = node
        var hops = 0
        while (current != null && hops < 8) {
            val clickable = try { current.isClickable } catch (_: Exception) { false }
            if (clickable) {
                val b = runCatching { current.visibleBounds }.getOrNull()
                if (b != null && b.width() > 0) {
                    device.executeShellCommand("input tap ${b.centerX()} ${b.centerY()}")
                    device.waitForIdle(1_000)
                    return true
                }
            }
            current = try { current.parent } catch (_: Exception) { null }
            hops++
        }
        return clickCenter(node)
    }

    private fun clickText(text: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.text(text)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    private fun clickDesc(desc: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.desc(desc)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    private fun uiTree(): String = try {
        val texts = runCatching {
            device.findObjects(By.textContains("")).mapNotNull { it.text }.distinct().take(70)
        }.getOrDefault(emptyList())
        "TEXTS: $texts"
    } catch (t: Throwable) {
        "probe dump failed: $t"
    }

    /** The profile list must not be empty for the dialog's select flow. */
    private fun seedProfileIfNone() {
        val graph = appContext.graph
        if (runBlocking { graph.profileRepo.profiles() }.isEmpty()) {
            runBlocking { graph.profileManager.create("BulkSeed$tag", "👤", 0xFF6750A4) }
        }
    }

    // ---------- 1. The export dialog ------------------------------------

    @Test
    fun bulk_export_dialog_lists_every_profile_and_gates_its_confirm() {
        seedProfileIfNone()
        launchMainActivity()
        assertTrue(
            "The profile list must come up\n${uiTree()}",
            hasText("Your profiles", 30_000)
        )

        // N is read from the shared Room repository — the same list the screen
        // renders — never assumed.
        val n = runBlocking { appContext.graph.profileRepo.profiles().size }
        assertTrue("At least one profile must exist for this flow\n${uiTree()}", n >= 1)

        assertTrue(
            "The bulk-export icon must be reachable\n${uiTree()}",
            clickDesc("Export profiles", 15_000)
        )
        // Plural-aware title: 1 -> "Export 1 profile".
        val title = if (n == 1) "Export 1 profile" else "Export $n profiles"
        assertTrue(
            "The dialog must open titled \"$title\"\n${uiTree()}",
            hasText(title, 10_000)
        )
        assertTrue(
            "Every profile must start ticked, so the confirm reads \"Continue with $n\"\n${uiTree()}",
            hasText("Continue with $n", 5_000)
        )

        assertTrue("\"Select none\" must be offered\n${uiTree()}", clickText("Select none", 6_000))
        assertTrue(
            "Unticking everything must relabel the confirm button\n${uiTree()}",
            hasText("Continue with 0", 6_000)
        )
        // The gate is asserted as BEHAVIOUR, not as `isEnabled`: Compose
        // surfaces `enabled = false` as a semantics node of its own, so the
        // flag reads back true on a button that cannot be pressed (the same
        // lesson AgentPillE2eTest records). A refused press leaves the dialog
        // exactly where it was.
        clickText("Continue with 0", 4_000)
        assertTrue(
            "A confirm with nothing ticked must start nothing\n${uiTree()}",
            device.hasObject(By.text(title)) && device.hasObject(By.text("Continue with 0"))
        )

        assertTrue("\"Select all\" must be offered again\n${uiTree()}", clickText("Select all", 6_000))
        assertTrue(
            "Ticking everything back must relabel the confirm button\n${uiTree()}",
            hasText("Continue with $n", 6_000)
        )

        assertTrue("Cancel must be reachable\n${uiTree()}", clickText("Cancel", 6_000))
        assertTrue(
            "The dialog must be gone after Cancel\n${uiTree()}",
            waitUntil(6_000) { !device.hasObject(By.text(title)) }
        )
    }

    // ---------- 2. One call restores every profile -----------------------

    @Test
    fun importing_a_bundle_in_one_call_restores_every_profile() {
        val graph = appContext.graph
        val repo = graph.profileRepo
        val before = runBlocking { repo.profiles().size }

        val now = System.currentTimeMillis()
        val suffix = now.toString()
        val idA = ProfileId.new()
        val idB = ProfileId.new()
        val nameA = "BulkA$suffix"
        val nameB = "BulkB$suffix"
        val urlA = "https://a.bulk.example/$suffix"
        val urlB = "https://b.bulk.example/$suffix"

        val a = ProfileRepositoryImpl.BundleProfileImport(
            profile = Profile(id = idA, name = nameA, createdAt = now),
            bookmarks = listOf(ProfileBackup.BookmarkExport(url = urlA, title = "Bookmark A"))
        )
        val b = ProfileRepositoryImpl.BundleProfileImport(
            profile = Profile(id = idB, name = nameB, createdAt = now),
            bookmarks = listOf(ProfileBackup.BookmarkExport(url = urlB, title = "Bookmark B"))
        )

        val summaries = runBlocking { repo.importBundle(listOf(a, b)) }

        assertThat(summaries).hasSize(2)
        assertThat(summaries.map { it.profile.name }).containsExactly(nameA, nameB).inOrder()

        val names = runBlocking { repo.profiles().map { it.name } }
        assertThat(names).contains(nameA)
        assertThat(names).contains(nameB)
        assertThat(runBlocking { repo.profiles().size }).isEqualTo(before + 2)

        // Each entry's rows landed under its OWN fresh profile id.
        assertThat(runBlocking { graph.browserRepo.bookmarks(idA) }.map { it.url }).contains(urlA)
        assertThat(runBlocking { graph.browserRepo.bookmarks(idB) }.map { it.url }).contains(urlB)
    }

    // ---------- 3. All or nothing ---------------------------------------

    @Test
    fun a_failing_entry_rolls_the_whole_bundle_back() {
        val repo = appContext.graph.profileRepo
        val before = runBlocking { repo.profiles().size }

        val now = System.currentTimeMillis()
        val suffix = now.toString()
        val goodName = "RollbackGood$suffix"
        val badName = "RollbackBad$suffix"

        val good = ProfileRepositoryImpl.BundleProfileImport(
            profile = Profile(id = ProfileId.new(), name = goodName, createdAt = now),
            bookmarks = listOf(
                ProfileBackup.BookmarkExport(
                    url = "https://good.rollback.example/$suffix",
                    title = "Good"
                )
            )
        )
        // The SECOND entry throws from inside the transaction: the first
        // entry's profile row and bookmark must go with it.
        val bad = ProfileRepositoryImpl.BundleProfileImport(
            profile = Profile(id = ProfileId.new(), name = badName, createdAt = now),
            writeCredentials = { error("boom") }
        )

        val result = runCatching { runBlocking { repo.importBundle(listOf(good, bad)) } }
        assertTrue("The failing entry must fail the whole call", result.isFailure)

        assertThat(runBlocking { repo.profiles().size }).isEqualTo(before)
        val names = runBlocking { repo.profiles().map { it.name } }
        assertThat(names).doesNotContain(goodName)
        assertThat(names).doesNotContain(badName)
    }
}
