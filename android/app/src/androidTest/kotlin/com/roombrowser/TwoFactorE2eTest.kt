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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E for the 2FA Management surface:
 *
 *   BrowserActivity (':browser') -> page menu -> "2FA Management" row
 *     -> TwoFactorActivity (default process) opens its own screen
 *
 * The CI emulator has no biometric AND no device credential, so
 * BiometricGate.canAuthenticate() is false. TwoFactorRoot's own LaunchedEffect
 * then calls repo.unlock() directly — there is no credential to prompt with —
 * so the screen opens UNLOCKED with the no-screen-lock banner, and the add
 * sheet is reachable with no interactive gate. This test never unlocks the
 * repo, never sets a device lock, and never touches the profile PIN: it only
 * reads what the screen does on a no-lock device.
 *
 * On a device WITH a credential the entry raises the system prompt and the
 * locked pane is what renders; that leg is asserted too but never runs on CI.
 */
@RunWith(AndroidJUnit4::class)
class TwoFactorE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext

    private val tag = (System.currentTimeMillis() % 100000).toString()
    private val profileName = "E2E2FA$tag"
    private val accountName = "e2e2fa$tag"

    /** RFC 4648 Base32, decodes cleanly and is not a real account's key. */
    private val setupKey = "JBSWY3DPEHPK3PXP"

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

    private fun hasTextContains(text: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.textContains(text)), timeoutMs)

    private fun hasDesc(desc: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.desc(desc)), timeoutMs)

    // By.desc is an exact match and the toolbar's contentDescription is
    // "Address bar: <url>", so the exact form can never match it.
    private fun hasDescContains(part: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.descContains(part)), timeoutMs)

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            try { Thread.sleep(250) } catch (_: InterruptedException) { }
        }
        return condition()
    }

    private fun waitGone(text: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (device.findObjects(By.text(text)).isEmpty()) return true
            try { Thread.sleep(250) } catch (_: InterruptedException) { }
        }
        return device.findObjects(By.text(text)).isEmpty()
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
        // Shell tap (input tap) — UiAutomator's gesture injection waits for an
        // a11y-idle window and TIMES OUT on busy Compose screens, losing the
        // tap silently. The shell tap is fire-and-forget and has never been lost.
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

    private fun dragUpQuarter() {
        device.swipe(
            device.displayWidth / 2, device.displayHeight * 3 / 4,
            device.displayWidth / 2, device.displayHeight / 4, 100
        )
        device.waitForIdle(800)
        try { Thread.sleep(300) } catch (_: InterruptedException) { }
    }

    private fun rectSettled(node: UiObject2, tries: Int = 6): Boolean {
        var previous = runCatching { node.visibleBounds }.getOrNull() ?: return false
        repeat(tries) {
            try { Thread.sleep(200) } catch (_: InterruptedException) { }
            val current = runCatching { node.visibleBounds }.getOrNull() ?: return false
            if (current == previous) return true
            previous = current
        }
        return false
    }

    private fun imeShown(): Boolean = try {
        // No shell pipe: `executeShellCommand` hands the whole string to the
        // process, so a `| grep` never runs — it just becomes extra arguments.
        device.executeShellCommand("dumpsys input_method")
            .lineSequence()
            .any { it.contains("mInputShown=true") }
    } catch (_: Exception) {
        false
    }

    private fun waitImeShown(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (imeShown()) return true
            try { Thread.sleep(200) } catch (_: InterruptedException) { }
        }
        return imeShown()
    }

    private fun hideImeIfNeeded() {
        if (imeShown()) {
            device.pressBack()
            device.waitForIdle(600)
        }
    }

    private fun engineUiUp(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (hasDescContains("Address bar", 400)) return true
            if (hasText("Search or type URL", 400)) return true
            if (hasText("Privacy Dashboard", 400)) return true
            try { Thread.sleep(250) } catch (_: InterruptedException) { }
        }
        return hasDescContains("Address bar", 500)
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

    /** Opens the Page Actions sheet and verifies its own header came up. */
    private fun openPageActionsSheet(): Boolean {
        for (round in 1..3) {
            if (!hasDesc("Page actions and settings", 1_500)) {
                device.pressBack()
                device.waitForIdle(1_000)
            }
            if (clickDesc("Page actions and settings", 6_000) && hasText("Page Actions", 4_000)) {
                return true
            }
        }
        return hasText("Page Actions", 2_000)
    }

    /** The row's own vertical position in the sheet, or null when off-tree. */
    private fun sheetRowTop(text: String): Int? = runCatching {
        device.findObjects(By.text(text)).minByOrNull { it.visibleBounds.top }
    }.getOrNull()?.let { runCatching { it.visibleBounds.top }.getOrNull() }

    /**
     * Notes / 2FA Management / Shields tops, read in ONE pass so a scroll
     * between reads cannot shift them apart. Rows below the fold are not in
     * the a11y tree, so a small drag brings them in before the comparison.
     */
    private fun rowOrderInSheet(): Triple<Int, Int, Int>? {
        repeat(8) {
            val notes = sheetRowTop("Notes")
            val twoFactor = sheetRowTop("2FA Management")
            val shields = sheetRowTop("Shields")
            if (notes != null && twoFactor != null && shields != null) {
                return Triple(notes, twoFactor, shields)
            }
            dragUpQuarter()
        }
        return null
    }

    /**
     * The live EditText node for the field whose floating [label] was found.
     * When the field is empty the label sits INSIDE the box, so the nearest
     * horizontally-spanning EditText is the field itself either way.
     */
    private fun fieldForLabel(label: String): UiObject2? {
        val labelNode = device.wait(Until.findObject(By.text(label)), 3_000) ?: return null
        if (runCatching { labelNode.className }.getOrNull()?.contains("EditText") == true) {
            return labelNode
        }
        val lb = runCatching { labelNode.visibleBounds }.getOrNull() ?: return null
        val cx = lb.centerX()
        val cy = lb.centerY()
        return runCatching {
            device.findObjects(By.clazz("android.widget.EditText"))
                .filter {
                    val b = it.visibleBounds
                    b.left <= cx && b.right >= cx
                }
                .minByOrNull { kotlin.math.abs(it.visibleBounds.centerY() - cy) }
        }.getOrNull()
    }

    /** The [index]-th editable field top-to-bottom (the sheet's own order). */
    private fun editTextAt(index: Int): UiObject2? = runCatching {
        device.findObjects(By.clazz("android.widget.EditText"))
            // A field clipped out of the sheet reports an empty rect; if it
            // still sorted in, the index would name a field nobody can click.
            .filter { it.visibleBounds.height() > 0 }
            .sortedBy { it.visibleBounds.top }
            .getOrNull(index)
    }.getOrNull()

    /** Every editable node's text and vertical span, for a readable failure. */
    private fun editTextDump(): String = runCatching {
        device.findObjects(By.clazz("android.widget.EditText")).joinToString(" | ") {
            val b = it.visibleBounds
            "'${it.text}'@${b.top}..${b.bottom}"
        }
    }.getOrElse { "dump failed: $it" }

    /** Fresh lookups — a cached UiObject2 reports the properties it was found with. */
    private fun readBack(value: String): Boolean = waitUntil(6_000) {
        device.findObjects(By.clazz("android.widget.EditText"))
            .mapNotNull { it.text }
            .any { it.contains(value) }
    }

    /**
     * Types [value] into the field under [label] and reads the value back.
     *
     * The add-account form renders inside a ModalBottomSheet, which has its own
     * window that never takes input focus. The tap still focuses the field and
     * LatinIME still attaches ("Starting input. Cursor position = 0,0"), so a
     * person can type — the soft IME commits through the InputConnection — but
     * injected hardware key events are dispatched to the ACTIVITY window and
     * land nowhere, which is why the shell burst alone never wrote anything.
     * That is also why this is engine-independent: the GeckoView edition fails
     * on the same assertion, in the same way.
     *
     * So ACTION_SET_TEXT goes first: it travels the accessibility layer and
     * needs neither input focus nor a live IME. It is issued against a handle
     * re-resolved immediately before the write, because the tap opens the IME
     * and the sheet re-lays out for it, which re-creates the semantics node.
     *
     * Both mechanisms are still attempted, in every round, each gated on the
     * read-back — so a tree where either explanation turns out to be wrong
     * still has the other path, and a silent no-op from either is caught by the
     * gate rather than by a thrown exception.
     */
    private val typeTrace = StringBuilder()

    private fun trace(msg: String) {
        typeTrace.append("\n  ").append(msg)
    }

    /** A node's text and vertical span, so a failure names what it actually hit. */
    private fun traceNode(what: String, n: UiObject2?): String {
        if (n == null) return "$what=<none>"
        val b = runCatching { n.visibleBounds }.getOrNull()
        val t = runCatching { n.text }.getOrNull()
        return "$what='$t'@${b?.top}..${b?.bottom}"
    }

    private fun typeIntoField(label: String, value: String, index: Int): Boolean {
        // The label is the primary handle; the index is the fallback for a
        // build whose decoration does not expose the label as its own node.
        fun locateNow(): UiObject2? {
            fieldForLabel(label)?.let { trace("  viaLabel ${traceNode("", it)}"); return it }
            val byIndex = editTextAt(index)
            trace("  viaIndex ${traceNode("", byIndex)}")
            return byIndex
        }
        fun locateScrolling(): UiObject2? {
            var found = locateNow()
            var scrolled = 0
            while (found == null && scrolled < 6) {
                dragUpQuarter()
                found = locateNow()
                scrolled++
            }
            return found
        }
        for (round in 1..3) {
            trace("$label round $round")
            // Only before the first click: later rounds press back with a
            // Dialog open, and dismissal is not worth the risk for a cleanup.
            if (round == 1) hideImeIfNeeded()
            val target = locateScrolling() ?: continue
            clickCenter(target)
            device.waitForIdle(600)

            val fresh = locateNow()
            if (fresh != null) {
                val outcome = runCatching { fresh.setText(value) }
                trace("  setText -> ${outcome.exceptionOrNull()?.let { "${it.javaClass.name}: ${it.message}" } ?: "no throw"}")
                trace("  afterSetText ${editTextDump()}")
                if (readBack(value)) return true
            }

            // The click has focused the field; a burst sent before the
            // InputConnection attaches is dropped whole, so the shown IME is
            // waited for as a best effort. It is deliberately NOT a gate: a
            // probe that answers wrong would otherwise disable the only path
            // that writes, and the read-back below retries anyway.
            val imeUp = waitImeShown(3_000)
            trace("  imeShown=$imeUp")
            device.clearFocusedField(60)
            device.waitForIdle(400)
            device.executeShellCommand("input text $value")
            trace("  afterBurst ${editTextDump()}")
            if (readBack(value)) return true
        }
        return false
    }

    /** Scrolls to [text], clicks it and waits for [verify] to become true. */
    private fun clickTextScrollableVerified(
        text: String,
        attempts: Int = 10,
        verify: () -> Boolean
    ): Boolean {
        for (i in 1..attempts) {
            if (verify()) return true
            val node = device.wait(Until.findObject(By.text(text)), 1_500)
            if (node != null && rectSettled(node)) {
                clickSmart(node)
                device.waitForIdle(1_000)
                if (verify()) return true
                runCatching { clickCenter(node) }
                if (verify()) return true
            }
            dragUpQuarter()
        }
        return verify()
    }

    private fun canAuthenticate(): Boolean {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return BiometricManager.from(targetContext).canAuthenticate(authenticators) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    // ---------- The contract ------------------------------------------------

    @Test
    fun two_factor_row_sits_between_notes_and_shields_in_the_page_actions_sheet() {
        E2eDeterminism.suppressOrganicNetworkWarnings()
        assertTrue("Engine must come up on a fresh profile", bootstrapFreshEngine())
        assertTrue("The Page Actions sheet must open\n${uiTree()}", openPageActionsSheet())

        val order = rowOrderInSheet()
        assertTrue(
            "Notes, 2FA Management and Shields must all be visible in the sheet\n${uiTree()}",
            order != null
        )
        val (notesTop, twoFactorTop, shieldsTop) = order!!
        assertTrue(
            "The 2FA Management row must sit below Notes (tops: $notesTop / $twoFactorTop)\n${uiTree()}",
            notesTop < twoFactorTop
        )
        assertTrue(
            "The 2FA Management row must sit above Shields (tops: $twoFactorTop / $shieldsTop)\n${uiTree()}",
            twoFactorTop < shieldsTop
        )
    }

    @Test
    fun two_factor_screen_opens_and_the_manual_setup_key_path_adds_an_account() {
        E2eDeterminism.suppressOrganicNetworkWarnings()
        assertTrue("Engine must come up on a fresh profile", bootstrapFreshEngine())

        assertTrue("The Page Actions sheet must open\n${uiTree()}", openPageActionsSheet())
        // The sheet renders its rows lazily: run 37375880648's dump ended at
        // "Notes", so every row below it -- this one included -- is outside the
        // composed window until the sheet is scrolled. Waiting without
        // scrolling can never see it. (Test 1 above passes for the same reason
        // rowOrderInSheet drags before each read.)
        assertTrue(
            "The 2FA Management row must be clickable and open its screen\n${uiTree()}",
            clickTextScrollableVerified("2FA Management") { waitGone("Page Actions", 1_500) }
        )
        assertTrue(
            "The sheet must be dismissed before the 2FA screen is asserted\n${uiTree()}",
            waitGone("Page Actions", 8_000)
        )
        assertTrue(
            "TwoFactorActivity must show its own title\n${uiTree()}",
            hasText("2FA Management", 10_000)
        )

        if (!canAuthenticate()) {
            // No lock on the CI emulator: the screen opens by itself and SAYS
            // so — the banner is the whole contract of this branch.
            assertTrue(
                "The no-screen-lock banner must say the codes are unprotected\n${uiTree()}",
                hasTextContains("This device has no screen lock", 10_000)
            )
            assertTrue(
                "A profile with no accounts must show the empty state\n${uiTree()}",
                hasText("No 2FA accounts yet", 8_000)
            )
            assertTrue("The empty state must offer Add 2FA\n${uiTree()}", hasText("Add 2FA", 5_000))

            // Add sheet, manual setup-key path only (no camera, no clipboard).
            typeTrace.clear()
            assertTrue(
                "The Add 2FA button must be clickable\n${uiTree()}",
                clickText("Add 2FA", 5_000)
            )
            assertTrue(
                "The add sheet must open\n${uiTree()}",
                hasText("Add 2FA account", 8_000)
            )
            assertTrue(
                "The Account field must take the account name\n${uiTree()}\nFIELDS: ${editTextDump()}\nTRACE:$typeTrace",
                typeIntoField("Account", accountName, index = 1)
            )
            assertTrue(
                "The Setup key field must take the Base32 secret\n${uiTree()}\nFIELDS: ${editTextDump()}\nTRACE:$typeTrace",
                typeIntoField("Setup key (Base32)", setupKey, index = 2)
            )
            assertTrue(
                "The Add button must save and close the sheet\n${uiTree()}\nFIELDS: ${editTextDump()}",
                clickTextScrollableVerified("Add") { waitGone("Add 2FA account", 1_500) }
            )
            assertTrue(
                "The saved account must be listed on the 2FA screen\n${uiTree()}",
                hasText(accountName, 15_000)
            )
        } else {
            // A device WITH a credential raises the system prompt on entry, so
            // the locked pane — never the list — is what renders behind it.
            assertTrue(
                "A locked screen must show its locked pane, not the list\n${uiTree()}",
                hasText("2FA locked", 15_000)
            )
            runCatching { device.pressBack() }
        }
    }
}
