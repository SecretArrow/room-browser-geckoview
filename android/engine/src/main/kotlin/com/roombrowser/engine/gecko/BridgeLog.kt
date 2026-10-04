package com.roombrowser.engine.gecko

/**
 * The one logcat tag under which every step of the page bridge's lifecycle is
 * written.
 *
 * WHY ONE TAG AND NOT ONE PER CLASS. The bridge fails silently by
 * construction: GeckoView reports neither a content script that never
 * injected, nor a port that never connected, nor a page that never called, and
 * the only symptom visible from the app is a dApp that sees no wallet. A run
 * that fails that way leaves exactly one question worth asking -- how far did
 * the bridge get -- and its answer is the ORDER of three lines:
 *
 *     extension installed  ->  port connected  ->  port message
 *
 * Whichever boundary a run never reaches is the surviving hypothesis; the
 * lines only answer the question together, which is also why they are worth
 * more than the sum of three greps. Split across three tags the question
 * becomes three interleaved searches of a buffer that GeckoView fills fast
 * enough to rotate out the very lines being looked for.
 *
 * The literal is asserted against the `-s` filter the instrumented probe
 * passes to logcat (`BridgeDiagnosticsTest` in :app, which is also where the
 * three lines are read as a sequence). A rename on either side would make that
 * dump print nothing at all -- which reads exactly like "the bridge never ran"
 * and so would answer the question wrongly instead of failing to answer it.
 */
internal const val BRIDGE_LOG_TAG = "RoomBridge"
