package com.roombrowser.node

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The runtime side of the bridge — what runs in the `:node` process.
 *
 * The entry script and everything it keeps beside it are copied out of the
 * APK before they run: an archive is not a file system, and the runtime
 * resolves its entry point by path. The copy is a RECURSIVE SYNC of the whole
 * `assets/node/` tree, rewritten file by file whenever the packaged one
 * differs, so a new APK never runs the previous version's script — and never
 * keeps a plugin host or an example ZIP the new APK stopped carrying.
 */
object NodeRuntime {

    const val SOCKET_EXTRA = "com.roombrowser.node.socket"
    const val TOKEN_EXTRA = "com.roombrowser.node.token"

    private const val ASSET_ROOT = "node"
    private const val SCRIPT_NAME = "bridge.js"
    private const val STATUS_NAME = "status.json"
    private const val SOCKET_SUFFIX = ".sock"

    /** Installed packages; `require()` resolves from here. Never the sync's to touch. */
    private const val MODULES_NAME = "node_modules"

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
        val nodeDir = directory(context)
        nodeDir.mkdirs()
        syncPackaged(nodeDir, packagedTree(context.assets))
        return File(nodeDir, SCRIPT_NAME)
    }

    /**
     * Every file under `assets/node/`, keyed by its path relative to the node
     * directory (`bridge.js`, `plugin_host.js`, `plugins/hello-world.zip`, …).
     *
     * A name that lists non-empty is a directory and is walked; an empty file
     * and a file the packer left without an extension both list empty and are
     * read, which is the only honest distinction Android's asset listing
     * offers. A walk failure is skipped rather than thrown: this runs at
     * process start, where the choice is "sync what is there" or "never
     * start".
     */
    private fun packagedTree(assets: AssetManager): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        fun walk(path: String) {
            val children = runCatching { assets.list(path) }.getOrNull() ?: return
            children.forEach { name ->
                val child = "$path/$name"
                val grandChildren = runCatching { assets.list(child) }.getOrNull()
                if (!grandChildren.isNullOrEmpty()) {
                    walk(child)
                } else {
                    runCatching {
                        assets.open(child).use { it.readBytes() }
                    }.getOrNull()?.let { files[child.removePrefix("$ASSET_ROOT/")] = it }
                }
            }
        }
        walk(ASSET_ROOT)
        return files
    }

    /**
     * Makes [nodeDir] hold exactly [packaged], and nothing else that belongs
     * to the sync.
     *
     * Per-file byte comparison, rewrite on diff — the semantics the single
     * bridge.js copy always had, now applied to the whole tree so a plugin
     * host and the bundled example ZIPs arrive the same way. What the sync
     * deliberately never touches: `status.json` (the runtime's own last
     * report), `*.sock` (a live socket, or one a dead run left behind), and
     * `node_modules` (packages the USER installed through `node_packages`,
     * which no APK carries). Anything else on disk that the APK no longer
     * carries is deleted — a stale `plugin_host.js` or example ZIP from an
     * older build is exactly the file this exists to retire.
     */
    internal fun syncPackaged(nodeDir: File, packaged: Map<String, ByteArray>) {
        packaged.forEach { (relative, bytes) ->
            val target = File(nodeDir, relative)
            // Only rewritten when it differs: a same-length rewrite would still
            // touch the file, and this directory is read while the runtime runs.
            val unchanged = target.exists() && target.length() == bytes.size.toLong() &&
                runCatching { target.readBytes().contentEquals(bytes) }.getOrDefault(false)
            if (!unchanged) {
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
        }
        val keptDirs = packaged.keys
            .map { it.substringBeforeLast('/') }
            .filter { it.isNotEmpty() }
            .toSet()
        deleteExtraneous(nodeDir, "", keptDirs, packaged.keys)
    }

    /** Removes what the APK stopped carrying, leaving the runtime's own files alone. */
    private fun deleteExtraneous(dir: File, relative: String, keptDirs: Set<String>, keptFiles: Set<String>) {
        val entries = dir.listFiles() ?: return
        entries.forEach { file ->
            val path = if (relative.isEmpty()) file.name else "$relative/${file.name}"
            when {
                // The runtime's own state and the user's packages: not ours.
                relative.isEmpty() &&
                    (file.name == STATUS_NAME || file.name.endsWith(SOCKET_SUFFIX) || file.name == MODULES_NAME) ->
                    Unit
                file.isDirectory ->
                    if (path !in keptDirs) {
                        runCatching { file.deleteRecursively() }
                    } else {
                        deleteExtraneous(file, path, keptDirs, keptFiles)
                    }
                path !in keptFiles -> runCatching { file.delete() }
                else -> Unit
            }
        }
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
