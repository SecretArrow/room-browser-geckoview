package com.roombrowser.node

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The runtime side of the bridge — what runs in the `:node` process.
 *
 * The entry script is copied out of the APK before it runs: an archive is not a
 * file system, and the runtime resolves its entry point by path. The copy is
 * rewritten whenever the packaged one differs, so a new APK never runs the
 * previous version's script.
 */
object NodeRuntime {

    const val SOCKET_EXTRA = "com.roombrowser.node.socket"
    const val TOKEN_EXTRA = "com.roombrowser.node.token"

    private const val SCRIPT_NAME = "bridge.js"
    private const val STATUS_NAME = "status.json"
    private const val SOCKET_SUFFIX = ".sock"

    /** False on an ABI this APK carries no libnode for. */
    val available: Boolean get() = NodeBridge.available

    private val started = AtomicBoolean(false)

    /** argv as the runtime reads it: argv[0] is the program name, not a path. */
    internal fun argvFor(script: File, socketName: String, token: String): Array<String> =
        arrayOf("node", script.absolutePath, socketName, token)

    /**
     * The directory the runtime's own files live in, `<filesDir>/node`. Its
     * `node_modules` is what `require()` resolves from, so a caller that
     * installs packages must not build the path a second time.
     */
    fun directory(context: Context): File = File(context.filesDir, "node")

    /**
     * Where the runtime records how far it got, one line of JSON per stage. The
     * script writes it beside itself, so nothing has to pass the path in; a
     * caller that never connected reads it to say what the runtime was doing
     * instead of guessing.
     */
    internal fun statusFile(context: Context): File = File(directory(context), STATUS_NAME)

    /**
     * The socket the runtime binds and this side connects to, as a FILE in this
     * app's own data directory rather than a name in the abstract namespace.
     *
     * The abstract namespace was the first choice and did not work here: the
     * runtime bound the name it was asked for and reported it, no denial was
     * logged, and the app's own other process still could not reach it for the
     * full 30s — see the timeout message's own report. A socket file is also the
     * conventional Android transport between two processes of one app: both are
     * the same UID and the directory is theirs, so nothing else has to hold.
     */
    internal fun socketFile(context: Context, name: String): File =
        File(directory(context), name + SOCKET_SUFFIX)

    /** Removes socket files left behind by calls whose runtime is gone. */
    internal fun sweepSockets(context: Context, keep: File) {
        directory(context).listFiles()?.forEach { file ->
            if (file.name.endsWith(SOCKET_SUFFIX) && file.name != keep.name) {
                runCatching { file.delete() }
            }
        }
    }

    internal fun scriptFile(context: Context): File {
        val target = File(directory(context), SCRIPT_NAME)
        target.parentFile?.mkdirs()
        val packaged = context.assets.open("node/$SCRIPT_NAME").use { it.readBytes() }
        if (!target.exists() || !target.readBytes().contentEquals(packaged)) {
            target.writeBytes(packaged)
        }
        return target
    }

    /**
     * Runs the runtime on the calling thread until it exits, and answers whether
     * this call was the one that owned it.
     *
     * `false` means nothing ran here — this build carries no runtime for this
     * ABI, or another thread in this process got there first — and the caller
     * must then leave the process alone, because killing it would take down a
     * runtime that is serving somebody. `node::Start` is a process-wide
     * singleton and cannot be started twice, which is why whoever owned it is
     * also whoever ends the process (see [NodeService]).
     */
    fun run(context: Context, socketName: String, token: String): Boolean {
        if (!available) return false
        if (!started.compareAndSet(false, true)) return false
        try {
            NodeBridge.startNode(argvFor(scriptFile(context), socketName, token))
            return true
        } finally {
            started.set(false)
        }
    }
}
