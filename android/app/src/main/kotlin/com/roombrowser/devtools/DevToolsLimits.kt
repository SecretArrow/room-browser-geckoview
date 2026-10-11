package com.roombrowser.devtools

import com.roombrowser.engine.devtools.DeveloperToolsCapabilities
import com.roombrowser.engine.devtools.DevToolsCapability

/** One "why not" line: what is missing, and the reason the code can give for it. */
internal data class LimitsRow(val title: String, val detail: String)

/** A titled group of [LimitsRow]s. */
internal data class LimitsSection(val title: String, val rows: List<LimitsRow>)

/**
 * The permanent wording the Audit panel carries, quoted here rather than
 * paraphrased so the app never says two different things about it.
 */
internal const val NOT_LIGHTHOUSE_CAPTION =
    "Not Google Lighthouse. An in-page audit built only from signals the page can observe."

/**
 * The limits that hold on BOTH editions.
 *
 * Every row is a statement that something is absent or impossible, never that
 * a feature does something — so a row cannot outrun the code. The per-edition
 * reasons live in [capabilityLimits] and come from the capability set itself,
 * so these two lists never restate each other.
 */
internal fun sharedLimits(): List<LimitsSection> = listOf(
    LimitsSection(
        title = "The same on both editions",
        rows = listOf(
            LimitsRow(
                "Memory and heap snapshots",
                "No Memory panel is built on either edition and no heap snapshot is shipped: a " +
                    "snapshot of a phone-sized page is hundreds of megabytes, which is not a cost " +
                    "that belongs on a phone, and the panel is gated on a CPU profiler that " +
                    "neither engine offers."
            ),
            LimitsRow(
                "Audit, and what it is not",
                "$NOT_LIGHTHOUSE_CAPTION There is no score and no invented category numbers: " +
                    "the audit reports the signals the page can observe, each as an observed " +
                    "fact, never as a figure rounded into a verdict."
            ),
            LimitsRow(
                "Puppeteer and Node export",
                "There is no Node runtime in an Android app, so a Puppeteer or Node-script export " +
                    "could not run on the device, and none is offered under that name."
            ),
            LimitsRow(
                "iOS",
                "There is no iOS edition and no WKWebView code in this project. Every iOS " +
                    "question about these tools is inapplicable by the absence of a target, and " +
                    "this page says so rather than staying silent about it."
            )
        )
    ),
    LimitsSection(
        title = "Conditions on these tools",
        rows = listOf(
            LimitsRow(
                "Private tabs",
                "Developer tools is unavailable in a private tab: the row is not offered and no " +
                    "inspector session is ever created for that tab."
            ),
            LimitsRow(
                "Cross-origin frames",
                "A page-side inspector can count a cross-origin frame but cannot read into it, " +
                    "because the browser correctly refuses cross-origin access. This panel reports " +
                    "the refusal rather than drawing an empty tree that looks like a bug."
            ),
            LimitsRow(
                "The GeckoView protocol ceiling",
                "Whether an engine-backed JavaScript debugger, CPU profiler or true shadow-DOM " +
                    "cascade can be built here at all depends on the engine offering a " +
                    "debugging-protocol session to the app. GeckoView offers none to an embedded " +
                    "build: its remote debugger is a separate desktop connection and it does not " +
                    "speak CDP. Until that changes, those capabilities stay absent and those " +
                    "panels stay hidden."
            )
        )
    )
)

/**
 * The per-edition reasons, read straight off the capability set rather than
 * restated here, so this page cannot drift from what the engine reports.
 *
 * A capability that is absent *with* a note goes under "Not possible on this
 * engine" carrying the engine's own reason; one that is absent *without* a note
 * goes under "Not built yet" carrying the capability's own description. The two
 * groups are kept apart because "this engine cannot" and "nobody has built it
 * yet" send a reader to different places.
 */
internal fun capabilityLimits(capabilities: DeveloperToolsCapabilities): List<LimitsSection> {
    val absent = DevToolsCapability.entries.filterNot { capabilities.has(it) }
    val sections = mutableListOf<LimitsSection>()

    val cannot = absent.filter { capabilities.noteFor(it) != null }
    if (cannot.isNotEmpty()) {
        sections += LimitsSection(
            title = "Not possible on this engine",
            rows = cannot.mapNotNull { capability ->
                capabilities.noteFor(capability)?.let { LimitsRow(capabilityLabel(capability), it) }
            }
        )
    }

    val notYet = absent.filter { capabilities.noteFor(it) == null }
    if (notYet.isNotEmpty()) {
        sections += LimitsSection(
            title = "Not built yet",
            rows = notYet.map { LimitsRow(capabilityLabel(it), capabilityDetail(it)) }
        )
    }
    return sections
}

/** Everything the page shows, in reading order: what this engine cannot, then what neither edition can. */
internal fun devToolsLimits(capabilities: DeveloperToolsCapabilities): List<LimitsSection> =
    capabilityLimits(capabilities) + sharedLimits()

/**
 * The whole page as plain text, for the copy control, so a bug report can carry
 * the same statement the screen shows.
 */
internal fun devToolsLimitsText(engineName: String, capabilities: DeveloperToolsCapabilities): String {
    val lines = mutableListOf<String>()
    lines += if (engineName.isBlank()) {
        "What these tools cannot do, and why"
    } else {
        "What these tools cannot do, and why — $engineName"
    }
    devToolsLimits(capabilities).forEach { section ->
        lines += ""
        lines += "${section.title}:"
        section.rows.forEach { row -> lines += "  ${row.title} — ${row.detail}" }
    }
    return lines.joinToString("\n")
}
