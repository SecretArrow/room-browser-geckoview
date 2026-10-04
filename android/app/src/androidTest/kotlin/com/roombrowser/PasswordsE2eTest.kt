package com.roombrowser

import android.content.Context
import android.content.Intent
import androidx.biometric.BiometricManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.model.ProfileId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E for the per-profile Password Manager (PasswordsActivity +
 * CredentialRepository + VaultCrypto, Room v7):
 *
 *   UI leg (the CI emulator has no biometrics and no screen lock —
 *   canAuthenticate() is false, which is exactly the state under test):
 *     -> profile settings -> Autofill -> "Passwords" row
 *     -> PasswordsActivity opens in ':browser' and shows the LOCKED pane
 *        (no crash, contents never rendered)
 *     -> tapping "Unlock" fails gracefully (no credentials to prompt with)
 *        and the locked pane with its retry button stays
 *
 *   Repository leg (always runs — no UI needed, no biometrics needed):
 *     the app's own AppGraph from THIS process, against the real Room DB
 *     and the real AndroidKeyStore vault:
 *     unlock -> save (domain canonicalized) -> search -> edit-by-id ->
 *     get -> delete -> gone. The DB row is verified to hold ciphertext
 *     that decrypts under this profile's key and nothing else.
 *
 * On a device WITH enrolled biometrics the entry auto-gate would raise a
 * system prompt, so the UI leg is skipped there (the locked pane is what
 * the no-credential CI emulator must render honestly); the repository leg
 * is device-independent.
 */
@RunWith(AndroidJUnit4::class)
class PasswordsE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext

    private val tag = (System.currentTimeMillis() % 100000).toString()
    private val profileName = "E2EPass$tag"

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

    private fun hasDesc(desc: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.desc(desc)), timeoutMs)

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
        // The tap is injected via the SHELL `input tap`, never
        // UiObject2.click()/device.click(): those go through
        // UiAutomator's InteractionController, which waits for an
        // accessibility-idle window around the events and TIMES OUT on
        // busy screens (CI ec43763: 'Timed out waiting 1000ms for
        // command and events' — the app received nothing; the shell tap
        // is fire-and-forget and has never lost a tap).
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

    private fun dragUpQuarter() {
        // Half-screen drag (3/4 → 1/4): the CI emulator's default profile is
        // 320x640 mdpi — the Passwords row (Autofill section) sits ~3400px
        // down the profile settings screen there. Slow steps (no fling) keep
        // it a controlled scroll; the settle AFTER the drag lets residual
        // momentum finish before the caller reads node bounds (a tap on
        // bounds captured mid-fling lands on nothing — CI 227ebc3: the
        // Passwords row tap "succeeded" yet no activity started).
        device.swipe(
            device.displayWidth / 2, device.displayHeight * 3 / 4,
            device.displayWidth / 2, device.displayHeight / 4, 100
        )
        device.waitForIdle(800)
        try { Thread.sleep(300) } catch (_: InterruptedException) { }
    }

    /**
     * Clicks the node showing [text] (scrolling to it when needed) and
     * VERIFIES the effect — each attempt re-resolves the node FRESH so a
     * stale-bounds tap can never silently miss.
     */
    private fun clickTextVerifiedScrollable(
        text: String,
        timeoutMs: Long,
        verify: () -> Boolean
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (verify()) return true
            val node = device.wait(Until.findObject(By.text(text)), 1_500)
            if (node != null) {
                clickSmart(node)
                device.waitForIdle(1_000)
                if (verify()) return true
            }
            dragUpQuarter()
        }
        return verify()
    }

    private fun clickTextWithScroll(text: String, attempts: Int = 24): Boolean {
        for (i in 1..attempts) {
            if (clickText(text, 1_500)) return true
            dragUpQuarter()
        }
        return false
    }

    private fun engineUiUp(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (hasDesc("Address bar", 400)) return true
            if (hasText("Search or type URL", 400)) return true
            if (hasText("Privacy Dashboard", 400)) return true
            try { Thread.sleep(250) } catch (_: InterruptedException) { }
        }
        return hasDesc("Address bar", 500)
    }

    private fun uiTree(): String = try {
        val texts = runCatching {
            device.findObjects(By.textContains("")).mapNotNull { it.text }.distinct().take(60)
        }.getOrDefault(emptyList())
        "TEXTS: $texts"
    } catch (t: Throwable) {
        "probe dump failed: $t"
    }

    // ---------- Bootstrap: fresh profile ----------------------------------

    private fun bootstrapFreshEngine(): Boolean {
        device.pressHome()
        launchMainActivity()
        device.waitForIdle(2_000)
        assertTrue(
            "Profile list or first-run state must appear",
            hasText("Your profiles", 90_000) || hasText("Create Profile", 90_000)
        )
        for (i in 1..12) {
            if (clickText("Create Profile", 1_500)) break
            dragUpQuarter()
        }
        assertTrue("Create-profile dialog should open", hasText("Cancel", 8_000))
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 8_000)
        assertTrue("Name text field must be visible", field != null)
        clickCenter(field!!)
        device.executeShellCommand("input text $profileName")
        device.waitForIdle(1_000)
        val cancel = device.findObjects(By.text("Cancel")).minByOrNull { it.visibleBounds.top }
        val confirm = device.findObjects(By.text("Create Profile"))
            .filter { c ->
                cancel != null && kotlin.math.abs(
                    c.visibleBounds.centerY() - cancel.visibleBounds.centerY()
                ) < 200
            }
            .maxByOrNull { it.visibleBounds.centerX() }
        assertTrue("Dialog confirm button must be found", confirm != null)
        clickCenter(confirm!!)
        assertTrue(
            "Create dialog should close after confirm",
            waitUntil(10_000) { device.findObjects(By.text("Cancel")).isEmpty() }
        )
        return engineUiUp(40_000)
    }

    /** Opens a page-actions sheet entry by desc and VERIFIES the effect. */
    private fun openSheetEntry(label: String, verify: () -> Boolean): Boolean {
        for (round in 1..3) {
            if (!hasDesc("Page actions and settings", 1_500)) {
                device.pressBack()
                device.waitForIdle(1_000)
            }
            if (!clickDesc("Page actions and settings", 6_000)) continue
            for (attempt in 1..12) {
                val node = device.wait(Until.findObject(By.desc(label)), 2_000)
                if (node != null) {
                    clickSmart(node)
                    if (verify()) return true
                    runCatching { clickCenter(node) }
                    if (verify()) return true
                }
                dragUpQuarter()
            }
        }
        return false
    }

    private fun clickDesc(desc: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.desc(desc)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    // ---------- The contract ------------------------------------------------

    @Test
    fun locked_vault_renders_gracefully_and_repository_crud_roundtrips() {
        // Determinism: the runner's shared IP arms the organic network
        // warning on fresh-profile boots — suppress it (E2eDeterminism).
        E2eDeterminism.suppressOrganicNetworkWarnings()
        assertTrue("Engine must come up on a fresh profile", bootstrapFreshEngine())

        val appGraph = (targetContext.applicationContext as com.roombrowser.RoomBrowserApp).graph

        // ---- 1. UI leg: the locked pane (no-credential device) -------------
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val canAuthenticate =
            BiometricManager.from(targetContext).canAuthenticate(authenticators) ==
                BiometricManager.BIOMETRIC_SUCCESS

        if (!canAuthenticate) {
            assertTrue(
                "Profile settings must open",
                openSheetEntry("Profile settings") {
                    device.wait(Until.hasObject(By.textContains("Profile Settings —")), 8_000)
                }
            )
            assertTrue(
                "The Passwords row (Autofill section) must be tappable (verified: activity opens)\n${uiTree()}",
                clickTextVerifiedScrollable("Passwords", 60_000) {
                    device.findObjects(By.textContains("Vault locked")).isNotEmpty() ||
                        device.findObjects(By.textContains("Saved passwords")).isNotEmpty()
                }
            )
            assertTrue(
                "PasswordsActivity must open on its LOCKED pane — no crash\n${uiTree()}",
                hasText("Vault locked", 15_000)
            )
            assertTrue("The locked pane must offer the retry (Unlock) button", hasText("Unlock", 5_000))
            // No credentials to prompt with: the gate fails and the locked
            // pane (with its retry button) must stay — gracefully.
            assertTrue("The Unlock button must be clickable", clickText("Unlock", 5_000))
            assertTrue(
                "A failed unlock must keep the vault locked\n${uiTree()}",
                hasText("Vault locked", 5_000)
            )
        }
        // (A device WITH credentials would raise the system biometric prompt
        //  on entry — the locked-pane leg is the no-credential contract and
        //  does not run there; the repository leg below is device-independent.)

        // ---- 2. Repository leg: the real repo + real Keystore vault --------
        val profileId = ProfileId(
            runBlocking {
                appGraph.appState.activeProfileIdSnapshot()
                    ?: error("engine must have persisted the active profile id")
            }
        )
        val repo = appGraph.credentialRepo
        assertThat(repo.isUnlocked.value).isFalse()
        repo.unlock()
        assertThat(repo.isUnlocked.value).isTrue()

        // save — with a messy domain that must canonicalize on the way in.
        val saved = runBlocking {
            repo.save(profileId, "https://Accounts.Example.COM/login", "e2e-user", "s3cret-$tag", "E2E login")
        }
        assertThat(saved.domain).isEqualTo("accounts.example.com")
        assertThat(saved.title).isEqualTo("E2E login")

        // search — the username needle finds the row.
        val found = runBlocking { repo.search(profileId, "e2e-user") }
        assertThat(found.map { it.password }).containsExactly("s3cret-$tag")

        // edit-by-id — same row, new secret, createdAt preserved.
        val edited = runBlocking {
            repo.save(profileId, "accounts.example.com", "e2e-user", "r0tated-$tag", "E2E login", id = saved.id)
        }
        assertThat(edited.id).isEqualTo(saved.id)
        assertThat(edited.createdAt).isEqualTo(saved.createdAt)
        val current = runBlocking { repo.get(profileId, saved.id) }
        assertThat(current!!.password).isEqualTo("r0tated-$tag")

        // The DB row holds ciphertext that decrypts under THIS profile's key.
        val rows = runBlocking { appGraph.database.credentialDao().allForProfile(profileId.value) }
        assertThat(rows).hasSize(1)
        assertThat(rows.single().passwordEnc).doesNotContain("r0tated-$tag")
        assertThat(com.roombrowser.security.VaultCrypto.decrypt(profileId, rows.single().passwordEnc))
            .isEqualTo("r0tated-$tag")

        // delete — the row is gone.
        runBlocking { repo.delete(profileId, saved.id) }
        assertThat(runBlocking { repo.get(profileId, saved.id) }).isNull()
        assertThat(runBlocking { appGraph.database.credentialDao().countForProfile(profileId.value) })
            .isEqualTo(0)

        repo.lock()
        assertThat(repo.isUnlocked.value).isFalse()
    }

    /**
     * The offer a creation is owed must never gate the launch surface.
     *
     * MEASURED FAILURE (run 37221499995): the offer was a modal
     * AlertDialog that opened over the profile list on the first launch
     * after a create. UiAutomator only sees the ACTIVE window, so the
     * next suite's `bootstrapFreshEngine` found neither "Your profiles"
     * nor "Create Profile" and failed after two 90 s waits — the failure
     * surfaced two suites away from the feature that caused it, which is
     * why this guard exists here rather than nowhere.
     *
     * The offer is recorded in app state rather than raised from the
     * create callback because a profile can also be created from the
     * browser quick-switcher, which restarts the process to bind it. So a
     * pending offer being on screen at launch is by design, and it has to
     * be raised INLINE on the card it names.
     */
    @Test
    fun import_offer_survives_a_relaunch_without_gating_the_profile_list() {
        E2eDeterminism.suppressOrganicNetworkWarnings()
        assertTrue("Engine must come up on a fresh profile", bootstrapFreshEngine())

        // The bootstrap's create armed the offer. Come back the way a
        // returning user does — fresh task, no engine in front.
        device.pressHome()
        launchMainActivity()
        device.waitForIdle(2_000)

        // THE regression guard: whatever is pending, the list is what the
        // app opens on. A dialog here fails this line, not a distant suite.
        assertTrue(
            "The profile list must be the launch surface with an offer pending\n${uiTree()}",
            hasText("Your profiles", 90_000)
        )

        // The offer is still owed, so it must be visible — the card it
        // names may sit below the fold on the 320x640 CI screen, where an
        // off-screen node is not in the a11y tree at all.
        val offered = hasText("Bring your passwords over?", 10_000) || run {
            var found = false
            repeat(8) {
                dragUpQuarter()
                if (device.findObjects(By.text("Bring your passwords over?")).isNotEmpty()) {
                    found = true
                }
            }
            found
        }
        assertTrue(
            "The offer must be raised inline on the card it names\n${uiTree()}",
            offered
        )

        // And it must be a prompt, not a wall: answering it costs nothing.
        assertTrue(
            "The offer must be dismissable\n${uiTree()}",
            clickTextVerifiedScrollable("Later", 30_000) {
                device.findObjects(By.text("Bring your passwords over?")).isEmpty()
            }
        )
    }
}
