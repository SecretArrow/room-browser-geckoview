package com.roombrowser.node

/**
 * The JNI surface: one function, which hands a command line to `node::Start`.
 *
 * libnode.so exports no JNI of its own, so the shim in `src/main/cpp` is the
 * whole bridge. [available] is false on an ABI this APK carries no libnode for
 * — the pinned release ships no 32-bit x86 — where the shim is compiled without
 * it and answers -1 instead of failing the build.
 */
internal object NodeBridge {

    private val loaded: Boolean = runCatching { System.loadLibrary("roomnode") }.isSuccess

    val available: Boolean get() = loaded

    /** Blocks until the runtime exits. [arguments] is the whole argv, argv[0] included. */
    external fun startNode(arguments: Array<String>): Int
}
