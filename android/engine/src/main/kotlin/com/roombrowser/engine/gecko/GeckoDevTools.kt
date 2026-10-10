package com.roombrowser.engine.gecko

import com.roombrowser.engine.devtools.DeveloperToolsCapabilities
import com.roombrowser.engine.devtools.DevToolsCapability
import com.roombrowser.engine.devtools.EngineInspector

/**
 * What the GeckoView edition can serve to Developer Tools, and the handle that
 * serves it.
 *
 * The declared set is the set this build ACTUALLY implements, and one source
 * feeds both the host's capability report and every session's inspector, so the
 * screen can never describe a capability the engine does not hand out.
 */
internal object GeckoDevTools {

    val CAPABILITIES: DeveloperToolsCapabilities = DeveloperToolsCapabilities(
        capabilities = setOf(DevToolsCapability.PAGE_SCRIPTING)
    )

    fun inspector(): EngineInspector = Handle

    private object Handle : EngineInspector {
        override val capabilities: DeveloperToolsCapabilities = CAPABILITIES
    }
}
