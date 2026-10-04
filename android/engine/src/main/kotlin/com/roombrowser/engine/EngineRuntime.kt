package com.roombrowser.engine

import com.roombrowser.engine.gecko.GeckoEngineHost

/**
 * The only symbol the app needs in order to reach an engine.
 *
 * The app never names an engine class, and this is why that is possible: each
 * edition of this browser ships its own `:engine` module, and the body of
 * [host] is the single line that differs between them. Everything the app
 * writes above the facade is byte-identical in both editions -- which is what
 * makes "apply the feature to both" a file copy rather than a rewrite.
 *
 * THIS EDITION RUNS GECKOVIEW, and the line below is the flip that makes it
 * so. It was WebView for exactly one landing, and deliberately: the app had
 * just been moved off `android.webkit` types onto this facade, and both engine
 * implementations were code that had never executed. Naming GeckoView in the
 * same commit would have given every failure two possible authors with no way
 * to tell them apart.
 *
 * That proving run came back at PARITY, which is the claim it was run to test,
 * not green: 41 tests, one failure, and that failure is the same test failing
 * the same way two commits earlier, before the facade existed. The conversion
 * introduced no new failure -- and from here a red e2e run means the engine
 * and nothing else.
 *
 * The remainder of the facade is untouched by the flip; if a future change
 * needs the WebView edition to run this same app, this one line is the whole
 * difference.
 */
object EngineRuntime {

    @Volatile
    private var instance: EngineHost? = null

    /**
     * The engine for this process.
     *
     * Created lazily: constructing the engine is a heavyweight, once-per-
     * process act that must not happen before the process has decided which
     * profile it is bound to.
     */
    fun host(): EngineHost =
        instance ?: synchronized(this) {
            instance ?: GeckoEngineHost().also { instance = it }
        }
}
