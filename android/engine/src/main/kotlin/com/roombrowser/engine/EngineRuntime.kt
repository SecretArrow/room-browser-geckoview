package com.roombrowser.engine

import com.roombrowser.engine.webview.WebViewEngineHost

/**
 * The only symbol the app needs in order to reach an engine.
 *
 * The app never names an engine class, and this is why that is possible: each
 * edition of this browser ships its own `:engine` module, and the body of
 * [host] is the single line that differs between them. Everything the app
 * writes above the facade is byte-identical in both editions -- which is what
 * makes "apply the feature to both" a file copy rather than a rewrite.
 *
 * TEMPORARY, and it is worth being explicit about that because this is the
 * GeckoView edition: the line below names the WEBVIEW engine on purpose, for
 * one landing only. The app has just been converted from `android.webkit`
 * types to this facade -- 14 files, ~350 symbol references, a mechanical
 * change but a large one -- and both engines on either side of it are code
 * that has never executed. Pointing at GeckoView in the same commit that lands
 * the conversion would mean every e2e failure had two possible authors, and no
 * way to tell them apart.
 *
 * So the conversion lands against the engine whose behaviour it was written
 * against. The e2e suite passing UNEDITED is the evidence that the conversion
 * itself changed nothing; the one-line flip to `GeckoEngineHost` follows as
 * its own commit, and from then on a red e2e run means the engine and nothing
 * else. Do not read this line as the geckoview edition's settled choice.
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
            instance ?: WebViewEngineHost().also { instance = it }
        }
}
