package com.roombrowser.agent

import com.roombrowser.domain.agent.ScreenshotDiff
import com.roombrowser.domain.agent.ScreenshotFrame
import com.roombrowser.domain.agent.ToolResult
import com.roombrowser.engine.EngineSession
import kotlin.math.roundToInt

/**
 * The frame the previous screenshot in this turn produced.
 *
 * Scoped to the TURN, not to the app: the executors are built per turn, so a
 * comparison can never reach back to a page the user was on in an earlier
 * conversation and call the difference between them a change.
 */
internal class ScreenshotHistory {

    private var last: ScreenshotFrame? = null

    fun record(frame: ScreenshotFrame) {
        last = frame
    }

    fun previous(): ScreenshotFrame? = last
}

/**
 * Capture the page and report how it differs from the last capture in this turn.
 *
 * This is the cheap half of the pair: it answers "did that do anything?" without
 * sending a second picture to the provider, which is the loop an agent falls
 * into after a click — it cannot tell a page that changed from one that ignored
 * the click, and the honest fix is a measurement rather than another screenshot.
 *
 * The FIRST call has nothing to compare against. That is a real state, not an
 * error: it records the frame and says so, so the model's next move is to take a
 * baseline before the action rather than to retry this call.
 */
internal suspend fun compareWithLastScreenshot(
    session: EngineSession,
    history: ScreenshotHistory,
    tolerance: Int
): ToolResult {
    val shot = PageScreenshot.capture(session).getOrElse {
        return ToolResult(false, "could not capture the page: ${it.message ?: "unknown error"}")
    }
    val frame = shot.frame
        ?: return ToolResult(false, "the capture produced no pixels to compare")
    val before = history.previous()
    history.record(frame)
    if (before == null) {
        return ToolResult(
            true,
            "No earlier screenshot in this turn to compare against, so this frame is now the " +
                "baseline. Take a screenshot, do the thing you want to check, then call " +
                "compare_screenshots again."
        )
    }
    return ToolResult(true, describeDiff(ScreenshotDiff.compare(before, frame, tolerance.coerceIn(0, 255))))
}

private fun describeDiff(result: ScreenshotDiff.Result): String {
    val resized = if (result.sizeChanged) {
        " The frame is a different size from the previous one."
    } else {
        ""
    }
    if (result.changedRatio == 0.0) {
        return "No visible change: nothing in the area both screenshots share moved by more than " +
            "the tolerance, so the page looks the same as before.$resized"
    }
    val where = result.box?.let { box ->
        " The change is concentrated in the ${positionOf(box)} of the frame — " +
            "x ${formatPercent(box.left)}..${formatPercent(box.right)}, " +
            "y ${formatPercent(box.top)}..${formatPercent(box.bottom)}."
    }.orEmpty()
    return "The page changed: about ${formatPercent(result.changedRatio)} of the frame differs " +
        "from the previous screenshot.$where$resized"
}

/** A coarse description of where in the frame the change sits. */
private fun positionOf(box: ScreenshotDiff.Box): String {
    val vertical = when {
        box.bottom <= 0.34 -> "upper"
        box.top >= 0.66 -> "lower"
        box.top <= 0.34 && box.bottom >= 0.66 -> "full-height"
        else -> "middle"
    }
    val horizontal = when {
        box.right <= 0.34 -> "left"
        box.left >= 0.66 -> "right"
        box.left <= 0.34 && box.right >= 0.66 -> "full-width"
        else -> "centre"
    }
    return "$vertical $horizontal area"
}

private fun formatPercent(fraction: Double): String {
    val tenths = (fraction * 1000).roundToInt()
    return if (tenths in 1..9) "under 0.1%" else "${tenths / 10}.${tenths % 10}%"
}
