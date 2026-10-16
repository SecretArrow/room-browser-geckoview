package com.roombrowser.node

import android.content.Context
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import kotlinx.serialization.json.JsonObject
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * The registry of reverse-request handlers one plugin call wires: which
 * command this side can answer while the plugin runs, and what answers it.
 *
 * The family on the wire is `agent_generate` (the chat's own model writing a
 * piece of text for `room.agent.generate` / `zagents.agent.compose`) and
 * `user_input` (the runtime form `room.ui.form` / `zagents.ui.form` shows a
 * person). Registering a handler is the ONLY way a command gets answered;
 * a command nobody registered is answered in its own default words — a
 * generation is "not available", a form gets [NO_INTERACTIVE_UI] — so a
 * probe or a legacy call site behaves exactly like a host that wired none,
 * and the plugin reads the refusal and carries on rather than waiting out
 * the runtime's own timeout.
 *
 * Handlers are BLOCKING and run on the exchange's read, one reverse line at
 * a time (the loop answers one before reading the next, so a plugin firing
 * several gets them served in arrival order); each is budgeted by whoever
 * registered it. Building one:
 *
 * ```
 * ReverseHandlers()
 *     .register(NodeProtocol.REVERSE_GENERATE, generateHandler)
 *     .register(NodeProtocol.REVERSE_USER_INPUT, formHandler)
 * ```
 */
class ReverseHandlers {

    private val handlers = LinkedHashMap<String, (JsonObject) -> JsonObject>()

    /**
     * Registers [handler] as the answerer of the reverse command [cmd],
     * replacing any earlier one. Returns this, so registrations read as one
     * wiring statement. The handler receives the WHOLE reverse line (its
     * `request` member is the payload) and returns the result object the
     * reply carries; anything it throws is answered as the line's own error
     * envelope with the thrown words.
     */
    fun register(cmd: String, handler: (JsonObject) -> JsonObject): ReverseHandlers = apply {
        handlers[cmd] = handler
    }

    /** The registered answerer of [cmd], or null when this call wired none. */
    internal fun handlerFor(cmd: String?): ((JsonObject) -> JsonObject)? = handlers[cmd]

    companion object {
        /**
         * The sentence a `user_input` request is answered with when nobody
         * registered a form handler — the same words the runtime form itself
         * refuses with, so the plugin cannot tell a dialog that never opened
         * from a host that wires none.
         */
        const val NO_INTERACTIVE_UI = "No interactive UI is available for this call"
    }
}

/**
 * The app side of the bridge: it starts the runtime's process, connects to it
 * and exchanges one line of JSON per command.
 *
 * The runtime LISTENS and this side connects, so readiness is a completed
 * connection rather than a guess. The socket lives in the app's process, so it
 * dies with that process — which is also what stops the runtime, since the
 * runtime exits when its only connection closes.
 */
class NodeClient private constructor(
    private val socket: LocalSocket,
    private val token: String,
    private val socketFile: File
) : Closeable {

    private val out = OutputStreamWriter(socket.outputStream, Charsets.UTF_8)
    private val input = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
    private var nextId = 0

    /**
     * [userAgent] travels with every command rather than being fixed at
     * start-up, because the profile that owns this runtime can change under it
     * and the runtime must never fall back to an identity of its own.
     */
    @Synchronized
    fun status(userAgent: String?): NodeStatus = call("status", userAgent, NodeProtocol::statusOf)

    /**
     * Runs [script] and returns what it produced.
     *
     * [timeoutMillis] is the script's own budget, sent with the command so the
     * runtime stops the script itself and says so; the read below waits longer
     * than that, so the honest message from the runtime is what arrives and the
     * socket timeout is only the backstop for a runtime that cannot answer at
     * all.
     */
    @Synchronized
    fun run(
        script: String,
        userAgent: String?,
        timeoutMillis: Long
    ): NodeRunResult = call(
        cmd = "run",
        userAgent = userAgent,
        read = NodeProtocol::runOf,
        script = script,
        timeoutMs = timeoutMillis,
        readTimeoutMillis = readTimeoutFor(timeoutMillis)
    )

    /**
     * Runs one plugin event and returns what it produced. Same line protocol,
     * same id matching and same error envelope as every other command: a
     * refusal from the runtime is an [IllegalStateException] carrying its
     * words, while a plugin that failed is an answer inside a successful
     * envelope — see [PluginCallResult].
     *
     * [onReverse] is the one-command shorthand this call's
     * `room.agent.generate` has always been answered by, registered under
     * [NodeProtocol.REVERSE_GENERATE]. Null (the default) means this call has
     * no generation wired, and any reverse request is answered with "agent
     * generation is not available" rather than ignored: the plugin reads the
     * refusal in its own words and the call itself carries on.
     */
    @Synchronized
    fun plugin(
        call: PluginCallRequest,
        onReverse: ((JsonObject) -> JsonObject)? = null
    ): PluginCallResult = plugin(
        call = call,
        reverse = onReverse?.let { handler ->
            ReverseHandlers().register(NodeProtocol.REVERSE_GENERATE, handler)
        }
    )

    /**
     * Runs one plugin event with the reverse commands answered by a REGISTRY:
     * [NodeProtocol.REVERSE_GENERATE] for `room.agent.generate` (a module's
     * `zagents.agent.compose` rides the same channel),
     * [NodeProtocol.REVERSE_USER_INPUT] for the runtime form
     * (`room.ui.form` / `zagents.ui.form`). Every command the registry does
     * not carry is answered with its own default refusal — the form's is
     * [ReverseHandlers.NO_INTERACTIVE_UI] — so a call that wired only a
     * generator still behaves honestly the moment a module asks for a dialog,
     * and null wires nothing at all.
     */
    @Synchronized
    fun plugin(
        call: PluginCallRequest,
        reverse: ReverseHandlers?
    ): PluginCallResult = exchange(
        request = { id -> NodeProtocol.pluginRequest(id, token, call) },
        read = NodeProtocol::pluginOf,
        readTimeoutMillis = readTimeoutFor(call.timeoutMs),
        reverse = reverse
    )

    @Synchronized
    private fun <T> call(
        cmd: String,
        userAgent: String?,
        read: (JsonObject) -> T,
        script: String? = null,
        timeoutMs: Long? = null,
        readTimeoutMillis: Long = READ_TIMEOUT_MS
    ): T = exchange(
        request = { id -> NodeProtocol.request(id, cmd, token, userAgent, script, timeoutMs) },
        read = read,
        readTimeoutMillis = readTimeoutMillis
    )

    /**
     * Writes one request line and reads lines until the reply arrives — the
     * whole of the protocol, plus the one wrinkle a plugin call adds: while
     * the runtime runs the plugin it may write REVERSE REQUESTS, and each is
     * answered HERE (token-checked, one command deep, the registered handler
     * doing the work) before the read goes back to waiting for the reply the
     * exchange is actually owed. A line with no `cmd` member is that reply —
     * the existing id-checked path, unchanged.
     *
     * A registered handler is BLOCKING — a form's dialog may hold this read
     * for minutes, which the protocol budgets for
     * ([NodeProtocol.REVERSE_USER_INPUT_TIMEOUT_MS] on the runtime's side) —
     * and that is safe precisely because no read is pending while it runs:
     * the socket's own timeout only ever bounds the wait for the NEXT line,
     * which is a compute stretch the module's paused budget already caps.
     */
    @Synchronized
    private fun <T> exchange(
        request: (Int) -> String,
        read: (JsonObject) -> T,
        readTimeoutMillis: Long,
        reverse: ReverseHandlers? = null
    ): T {
        val id = ++nextId
        out.write(request(id))
        out.write("\n")
        out.flush()
        // A runtime that is wedged in a script would otherwise hold this
        // thread — and the turn behind it — forever, with nothing to report.
        socket.soTimeout = readTimeoutMillis.toInt()
        while (true) {
            val line = input.readLine() ?: throw IOException("the Node runtime closed the connection")
            val parsed = NodeProtocol.parse(line)
            if (NodeProtocol.isReverse(parsed)) {
                out.write(NodeProtocol.reverseAnswer(parsed, token, reverse ?: ReverseHandlers()))
                out.write("\n")
                out.flush()
                continue
            }
            return read(NodeProtocol.resultOf(parsed, id))
        }
    }

    @Synchronized
    override fun close() {
        runCatching { socket.close() }
        // The runtime creates this file and leaves it behind when it exits, so
        // whoever made the call is who removes it.
        runCatching { socketFile.delete() }
    }

    companion object {

        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val RETRY_PAUSE_MS = 50L

        /** How often the start command is repeated while no connection is made. */
        private const val RESTART_EVERY_MS = 250L

        /** How long a plain reply may take before the socket gives up. */
        private const val READ_TIMEOUT_MS = 45_000L

        /** What a script's own budget is padded by before the read gives up. */
        private const val READ_SLACK_MS = 15_000L

        /**
         * How long the socket waits for a script's reply. Deliberately longer
         * than the budget the runtime was given, so a script the runtime stops
         * on its own is reported in the runtime's words rather than as a dead
         * connection.
         */
        internal fun readTimeoutFor(budgetMillis: Long): Long = budgetMillis + READ_SLACK_MS

        private val counter = AtomicInteger()
        private val random = SecureRandom()

        /**
         * Starts the runtime and connects to it, under a name nothing has used
         * before.
         *
         * THE NAME IS THE REQUEST, and the start command is repeated until the
         * deadline rather than sent once. A runtime already running in this
         * app's `:node` process is listening on an older name and cannot rebind,
         * so the process that receives the first command may be one nobody is
         * connected to any more — still shutting down, or wedged in a script.
         * [NodeService] ends such a process, and the command repeated on the
         * next pass is what starts the fresh one; sending it once would leave
         * this side waiting for a socket that will never be bound.
         *
         * Blocking, and bounded by the deadline rather than by a per-attempt
         * timeout, because there is no per-attempt timeout to have:
         * `LocalSocket.connect(LocalSocketAddress, int)` is an unimplemented
         * stub in the framework and throws `UnsupportedOperationException` on
         * every API level (the 30, 33 and 34 sources all read
         * `throw new UnsupportedOperationException()`, and the impl beneath it
         * ignored the argument anyway). The one-argument overload is the real
         * one, and a connect to an abstract name nobody is listening on fails
         * immediately — which is what makes the retry loop the thing that
         * actually waits for the runtime to come up.
         */
        fun connect(context: Context, timeoutMillis: Long = CONNECT_TIMEOUT_MS): NodeClient {
            if (!NodeRuntime.available) {
                throw IOException("this build carries no Node runtime for this device's CPU")
            }
            val name = "roombrowser-node-${Process.myPid()}-${counter.incrementAndGet()}"
            val token = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            val socketFile = NodeRuntime.socketFile(context, name)
            // A file left by a run that died is not ours to bind over, and a
            // connect to it would reach a socket nobody is listening on.
            runCatching { socketFile.delete() }
            val startedAt = System.currentTimeMillis()
            val deadline = startedAt + timeoutMillis
            var askedAt = 0L
            var lastFailure: IOException? = null
            while (true) {
                if (System.currentTimeMillis() - askedAt >= RESTART_EVERY_MS) {
                    val requested = runCatching {
                        context.startService(
                            Intent(context, NodeService::class.java)
                                .putExtra(NodeRuntime.SOCKET_EXTRA, socketFile.absolutePath)
                                .putExtra(NodeRuntime.TOKEN_EXTRA, token)
                        )
                    }.isSuccess
                    if (!requested) {
                        throw IOException("the system would not start the Node runtime's process")
                    }
                    askedAt = System.currentTimeMillis()
                }
                val socket = LocalSocket()
                try {
                    socket.connect(
                        LocalSocketAddress(
                            socketFile.absolutePath,
                            LocalSocketAddress.Namespace.FILESYSTEM
                        )
                    )
                    return NodeClient(socket, token, socketFile)
                } catch (err: IOException) {
                    lastFailure = err
                    runCatching { socket.close() }
                    if (System.currentTimeMillis() >= deadline) {
                        throw IOException(
                            timedOut(context, timeoutMillis, socketFile.absolutePath, startedAt, lastFailure)
                        )
                    }
                    Thread.sleep(RETRY_PAUSE_MS)
                }
            }
        }

        /**
         * What to say when no connection was ever made.
         *
         * The runtime's own last report is carried, because it is the only thing
         * that can say whether the runtime never started, stopped before it
         * listened, or is listening on something else — and a caller reading
         * "did not start" has no other way to tell those apart. A report older
         * than this attempt belongs to a previous run and is ignored.
         */
        private fun timedOut(
            context: Context,
            timeoutMillis: Long,
            socketPath: String,
            startedAt: Long,
            lastFailure: IOException?
        ): String {
            val status = NodeRuntime.statusFile(context)
            val report = if (status.lastModified() >= startedAt) {
                runCatching { status.readText().trim() }.getOrNull().orEmpty()
            } else {
                ""
            }
            val runtime = if (report.isEmpty()) {
                "the runtime wrote nothing, so it never reached the first line of its script"
            } else {
                "the runtime's own last report: $report"
            }
            val connect = lastFailure?.message?.let { "the last connect said: $it" }
                ?: "no connect was ever attempted"
            return "the Node runtime did not start within ${timeoutMillis / 1000}s " +
                "(socket $socketPath) — $runtime — $connect"
        }
    }
}
