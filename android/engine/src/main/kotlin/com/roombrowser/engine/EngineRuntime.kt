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
 */
object EngineRuntime {

    @Volatile
    private var instance: EngineHost? = null

    /**
     * The engine for this process.
     *
     * Created lazily: constructing a GeckoRuntime is a heavyweight, once-per-
     * process act that must not happen before the process has decided which
     * profile it is bound to.
     */
    fun host(): EngineHost =
        instance ?: synchronized(this) {
            instance ?: GeckoEngineHost().also { instance = it }
        }
}
