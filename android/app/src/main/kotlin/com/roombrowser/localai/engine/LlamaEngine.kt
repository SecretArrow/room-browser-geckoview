package com.roombrowser.localai.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Contract of the on-device llama.cpp inference engine (Task 13 part b).
 *
 * All calls are safe to issue from any dispatcher; the engine serializes
 * load / generate internally and exposes observable [LlamaEngine.state]
 * for the Local AI UI.
 */
interface LlamaEngineApi {
    /** True when the native `llama_jni` library was linked into this build. */
    val available: Boolean

    /** llama.cpp version string (e.g. "b4364"), or null when unavailable. */
    fun version(): String?

    /**
     * Loads a GGUF model file. Returns null on success, otherwise an honest,
     * user-presentable error message. [contextTokens] 0 = model default.
     */
    suspend fun load(modelId: String, file: java.io.File, contextTokens: Int, threads: Int): String?

    /** Frees the current model (idempotent, safe during a cancelled generation). */
    fun unload()

    /**
     * Runs a raw completion on the loaded model. Cancellable: coroutine
     * cancellation aborts the native decode via the ggml abort callback.
     * Returns failure when no model is loaded or generation failed.
     */
    suspend fun completeRaw(prompt: String, maxTokens: Int, temperature: Float, topP: Float): Result<String>

    /**
     * One chat turn: applies the model's built-in chat template (plain
     * "role: content" fallback when the model has none), generates the
     * assistant reply and returns its text. Throws on failure.
     */
    suspend fun chat(messages: List<Pair<String, String>>): String
}

/**
 * The embedded llama.cpp engine wrapper (JNI via [LlamaBridge]).
 *
 * Threading & lifetime discipline that the C++ side (llama_jni.cpp) relies on:
 *  - A [Mutex] serializes [load] and [completeRaw] for their whole duration.
 *  - Generation runs on a dedicated single lane ([lane]) so at most ONE
 *    nativeGenerate per handle is ever in flight.
 *  - [unload] is *not* a suspend function, so it cannot take the coroutine
 *    mutex. It instead (1) zero-sets [handle] atomically, (2) calls
 *    nativeCancel to abort any in-flight generation, and (3) queues the
 *    nativeFree ON THE LANE — the lane's strict FIFO guarantees the free
 *    never races a generate (the C++ map lookup also fails safely for
 *    already-freed handles, so late callers just see "no model loaded").
 *  - Known bounded imperfection, documented honestly: a generation that was
 *    already queued/running when unload() is called finishes its bounded
 *    token budget before the queued free executes (no crash, no leak —
 *    worst case one extra bounded generation of CPU time).
 *  - Cancellation uses the Task-11 watcher-sibling pattern: a watcher
 *    coroutine calls nativeCancel the instant the caller is cancelled, which
 *    trips the ggml abort callback inside llama_decode. A plain
 *    try/finally around withContext would fire only AFTER the blocking
 *    native call eventually returned — too late to be useful.
 *  - The C++ nativeGenerate RESETS its per-handle cancel flag at entry, so a
 *    stale `true` from an aborted run can never poison a later generation.
 */
object LlamaEngine : LlamaEngineApi {

    /** Observable engine state for the Local AI UI (modelId / loading / error). */
    data class EngineState(
        val modelId: String? = null,
        val loading: Boolean = false,
        val error: String? = null
    )

    private val _state = MutableStateFlow(EngineState(modelId = null, loading = false, error = null))
    val state: StateFlow<EngineState> = _state.asStateFlow()

    override val available: Boolean = LlamaBridge.libraryLoaded

    /** Serializes load / completeRaw for their entire duration. */
    private val mutex = Mutex()

    /** Single lane for all native engine work: generations never overlap. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val lane = Dispatchers.IO.limitedParallelism(1)

    /** Scope for lane-queued cleanup jobs triggered from non-suspend [unload]. */
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Current native handle (>0), or 0 when no model is loaded. @Volatile: 64-bit atomicity. */
    @Volatile
    private var handle: Long = 0L

    override fun version(): String? = if (available) LlamaBridge.nativeVersion() else null

    override suspend fun load(modelId: String, file: File, contextTokens: Int, threads: Int): String? {
        if (!available) return "llama.cpp engine not available in this build"
        return mutex.withLock {
            // Drop any live model first (load is a full replace).
            freeLocked()
            if (!file.exists()) {
                val msg = "model file not found: ${file.absolutePath}"
                _state.value = EngineState(modelId = null, loading = false, error = msg)
                return@withLock msg
            }
            _state.value = EngineState(modelId = modelId, loading = true, error = null)
            try {
                // The handle is assigned INSIDE the IO block: there is no
                // suspension point between nativeLoad returning and the
                // assignment, so a cancellation landing exactly at the
                // withContext boundary can never lose (leak) a loaded model.
                val h = withContext(Dispatchers.IO) {
                    val loaded = LlamaBridge.nativeLoad(file.absolutePath, contextTokens, threads)
                    if (loaded != 0L) handle = loaded
                    loaded
                }
                if (h == 0L) {
                    val msg = "failed to load ${file.absolutePath} — the file may not be a valid " +
                        "GGUF model or may need more RAM (${file.length()} bytes)"
                    _state.value = EngineState(modelId = null, loading = false, error = msg)
                    msg
                } else {
                    _state.value = EngineState(modelId = modelId, loading = false, error = null)
                    null
                }
            } catch (ce: CancellationException) {
                // Cancelled while loading: if a model had just been loaded it is
                // tracked in [handle] — free it honestly before rethrowing.
                freeLocked()
                _state.value = EngineState(modelId = null, loading = false, error = null)
                throw ce
            }
        }
    }

    override fun unload() {
        if (!available) return
        val h = handle
        handle = 0L
        if (h != 0L) {
            // Abort any in-flight generation promptly...
            LlamaBridge.nativeCancel(h)
            // ...then free ON THE LANE so the free can never race a generate.
            engineScope.launch(lane) { LlamaBridge.nativeFree(h) }
        }
        _state.value = EngineState()
    }

    override suspend fun completeRaw(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float
    ): Result<String> {
        if (!available) {
            return Result.failure(IllegalStateException("llama.cpp engine not available in this build"))
        }
        return mutex.withLock {
            val h = handle
            if (h == 0L) {
                return@withLock Result.failure(IllegalStateException("no model loaded"))
            }
            var out: String? = null
            coroutineScope {
                val gen = launch(lane) {
                    out = LlamaBridge.nativeGenerate(h, prompt, maxTokens, temperature, topP)
                }
                // Watcher sibling (Task-11 pattern): runs nativeCancel the
                // moment THIS coroutine is cancelled, tripping the ggml abort
                // callback inside llama_decode. try/finally around the launch
                // would only fire after the blocking call returned.
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        LlamaBridge.nativeCancel(h)
                    }
                }
                gen.join()
                // Join (not just cancel) so the watcher's finally — which sets
                // the cancel flag — fully completes before we return; a stale
                // pending set-true could otherwise race the NEXT generation.
                // (nativeGenerate resets the flag at entry, so even that is
                // only belt-and-braces.)
                watcher.cancel()
                watcher.join()
            }
            if (out == null) {
                Result.failure(IllegalStateException("generation failed"))
            } else {
                Result.success(out!!)
            }
        }
    }

    override suspend fun chat(messages: List<Pair<String, String>>): String {
        if (!available) throw IllegalStateException("llama.cpp engine not available in this build")
        val h = handle
        if (h == 0L) throw IllegalStateException("no model loaded")

        // Chat template via the model's built-in template, else honest plain
        // fallback. Metadata reads + string formatting are safe off-mutex.
        val prompt = withContext(lane) {
            val template = if (messages.isEmpty()) null else {
                val roles = messages.map { it.first }.toTypedArray()
                val contents = messages.map { it.second }.toTypedArray()
                LlamaBridge.nativeApplyChatTemplate(h, roles, contents)
            }
            template ?: plainFallback(messages)
        }

        val result = completeRaw(prompt, maxTokens = 256, temperature = 0.2f, topP = 0.95f)
        val text = result.getOrThrow()
        // Models occasionally echo the assistant header back — strip it.
        var reply = text.trim()
        if (reply.startsWith("assistant:")) {
            reply = reply.removePrefix("assistant:").trim()
        }
        return reply
    }

    /** Plain-text chat prompt for models without a chat template. */
    private fun plainFallback(messages: List<Pair<String, String>>): String =
        messages.joinToString("") { (role, content) ->
            val r = if (role == "system" || role == "user" || role == "assistant") role else "user"
            "$r: $content\n"
        } + "assistant: "

    /** Frees the current model. Caller must hold [mutex] (load path) or be in a cancellation cleanup. */
    private fun freeLocked() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            LlamaBridge.nativeFree(h)
        }
    }
}
