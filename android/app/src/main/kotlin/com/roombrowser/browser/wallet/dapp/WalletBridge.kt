package com.roombrowser.browser.wallet.dapp

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.roombrowser.browser.wallet.DappOutcome
import com.roombrowser.browser.wallet.WalletAccountRecord
import com.roombrowser.browser.wallet.WalletBridgeError
import com.roombrowser.browser.wallet.WalletEngineApi
import com.roombrowser.domain.engine.UrlIntelligence
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.domain.wallet.model.NetworkConfig
import com.roombrowser.domain.wallet.model.WalletException
import com.roombrowser.domain.wallet.rpc.JsonRpcClient
import com.roombrowser.engine.EngineSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * NATIVE half of the dApp wallet bridge, exposed to page JS as
 * `window.RoomWallet` (see [RoomWalletScript] for the injected half and
 * [WalletBridgeProtocol] for the codec). One bridge per session, built by
 * the integrator alongside the document-start script install.
 *
 * PROTOCOL (what page JS can call — nothing else is exported):
 *  - `RoomWallet.request(json)` — one bridge call, answered asynchronously
 *    with `window.__roomWalletResponse(id, resultJson, errorCode,
 *    errorMessage)` once the engine settles it (or the RPC relay completes).
 *
 * HOW THE CALLS ARRIVE NOW. There is no `@JavascriptInterface` on this side
 * any more — the page reaches native code through the
 * engine's own page bridge and the call surfaces as
 * [com.roombrowser.browser.RoomSessionListener.onPageMessage], carrying the
 * channel this object is registered under
 * ([com.roombrowser.browser.PageBridgeChannels.WALLET]). [onMessage] is what
 * that channel lands on. The PAGE-VISIBLE wire is unchanged — the promise
 * still resolves through `window.__roomWalletResponse`, because the answer is
 * still written with a script; only the way IN changed.
 *
 * SECURITY MODEL — the bridge never trusts the page (mirrors
 * RoomVaultBridge):
 *  1. Only [onMessage] is reachable, and every payload is parsed by the codec
 *     into typed calls or typed errors — never an exception.
 *  2. Every call hops to the MAIN thread first, then the session's OWN
 *     current URL ([EngineSession.url], the facade's trust anchor — written
 *     only from a top-level navigation) supplies the host and origin for the
 *     contract [DappRequest] — a page's claimed origin is metadata only and
 *     params never carry a trusted domain. Solana/Aptos/Sui/Tron Connect calls
 *     carry no origin claim at all: the verified host IS the host.
 *  3. Anti-flood: a 300ms minimum gap per (host, method) and at most 8
 *     concurrent pending calls per host — the OLDEST is auto-rejected with
 *     4001 when a 9th arrives, so a hostile page cannot pile prompts onto
 *     the confirmation queue.
 *  4. Nothing is ever logged.
 *
 * ROUTING:
 *  - kind "rpc" (read-only EVM calls) is relayed to the chain's ACTIVE
 *    network RPC via [JsonRpcClient] (8s timeouts, Dispatchers.IO inside
 *    the client) — no prompt is needed for reads; offline maps to 4901
 *    CHAIN_DISCONNECTED. The engine is not involved.
 *  - kind "request" is answered WITHOUT a prompt whenever it can be:
 *    already-permitted Connects auto-approve silently
 *    ([WalletEngineApi.isDappPermitted] with `eth_requestAccounts` for EVM,
 *    `enable` for Cosmos, `connect` for the other families), `eth_accounts`
 *    returns the permitted address list (empty while not permitted — never a
 *    prompt), Keplr's `getKey` answers for a permitted host only (4100
 *    otherwise), and `disconnect` revokes the host's permission for the
 *    calling chain. Everything else becomes a contract [DappRequest] (UUID
 *    id) submitted to the engine queue; the outcome settles the page promise.
 *  - A null [engineProvider] (engine not bound) answers 4900 DISCONNECTED
 *    for wallet-level calls; the RPC relay works regardless.
 *
 * THREADING: engine callbacks may arrive on the engine's own threads, so
 * every call hops inside [main] before touching state; the session URL it
 * reads is readable from any thread, which is what makes that safe. The
 * engine settles outcomes on the main thread (contract), and [finish]
 * re-checks the looper defensively. All bookkeeping maps are confined to
 * the main thread.
 *
 * LIFETIME: the host keeps one bridge per session in a weak map, so this
 * object holds its session only through the [sessionRef] [WeakReference] —
 * a destroyed session releases its bridge.
 */
class WalletBridge(
    private val engineProvider: () -> WalletEngineApi?,
    private val activeNetworkProvider: (ChainType) -> NetworkConfig?,
    session: EngineSession
) {

    private val sessionRef = WeakReference(session)

    private val main = Handler(Looper.getMainLooper())

    /**
     * Relay coroutines. SupervisorJob so one failed relay cannot cancel
     * siblings; Main dispatcher because respond* must touch main-confined
     * state (the IO hop happens inside JsonRpcClient).
     */
    private val relayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * The bridge's relay client. The OkHttp client behind it is SHARED across
     * every bridge (see [sharedRelayClient]) — one bridge is built per engine
     * and engines churn under the live-session LRU budget, so a per-bridge
     * client meant every tab create and every eviction allocated a fresh
     * connection pool and dispatcher thread pool that nothing ever shut down.
     */
    private val rpc = JsonRpcClient(sharedRelayClient)

    /**
     * Main-thread-confined pending state. [pendingById] tracks every call
     * until its response is delivered; [pageIdByDappId] maps engine request
     * ids back to page ids; [pendingByHost] enforces the per-host caps;
     * [lastDispatchAt] enforces the per-(host, method) gap.
     *
     * [method] and [isRead] are what the gap and the caps are decided on: a
     * read can neither raise a prompt nor pile one up, so it is neither
     * gapped nor counted against the prompt cap.
     */
    private class Pending(val host: String, val method: String, val isRead: Boolean) {
        var dappRequestId: String? = null
    }

    private val pendingById = LinkedHashMap<String, Pending>()
    private val pageIdByDappId = HashMap<String, String>()
    private val pendingByHost = HashMap<String, ArrayDeque<String>>()
    private val lastDispatchAt = HashMap<String, Long>()

    /**
     * The single page-reachable entry point: one channel message from this
     * session's page bridge. Nothing is returned to the page here — answers
     * are asynchronous (the async-response pattern; a synchronous return
     * would be evaluated on the engine's JavaScript thread and is unreliable).
     */
    fun onMessage(payload: String) {
        main.post { onMain(payload) }
    }

    /**
     * Pushes `accountsChanged` / `chainChanged` to the page's providers
     * (`window.__roomWalletEmit(event, payloadJson)`). Public for the
     * integrator: call it after a switch or account change settles, with a
     * raw JSON value payload (`["0x…"]` / `"0x…"`).
     */
    fun emitEvent(event: String, payloadJson: String) {
        runOnMain {
            val script = WalletBridgeProtocol.encodeEmitScript(event, payloadJson)
            sessionRef.get()?.evaluateJs(script)
        }
    }

    // ------------------------------------------------------------------
    // Main-thread pipeline
    // ------------------------------------------------------------------

    private fun onMain(payload: String) {
        val session = sessionRef.get() ?: return
        when (val parsed = WalletBridgeProtocol.parseRequest(payload)) {
            is BridgeParseResult.Invalid -> respondError(parsed.pageId, parsed.error)
            is BridgeParseResult.Ok -> dispatch(session, parsed.call)
        }
    }

    private fun dispatch(session: EngineSession, call: BridgeCall) {
        // The session's own URL is the ONLY host/origin source — never a
        // page claim, never a param.
        val url = session.url ?: run {
            respondError(call.id, WalletBridgeError(WalletBridgeError.DISCONNECTED, "No page loaded"))
            return
        }
        val host = UrlIntelligence.hostOf(url) ?: run {
            respondError(call.id, WalletBridgeError(WalletBridgeError.DISCONNECTED, "No host for current page"))
            return
        }
        val isRead = call is WalletRpcCall && WalletBridgeProtocol.isReadonlyRpcMethod(call.method)

        // The gap sheds a flood, and the flood worth shedding is PROMPTS —
        // that is the whole point of it (see the security model above). Two
        // kinds of call are therefore let through:
        //  - a read, which raises no prompt at all;
        //  - a repeat of a method that is still in flight, which is a retry or
        //    a second component asking the same question, and which the engine
        //    coalesces into the one prompt already on screen anyway.
        // Rejecting those is what a dApp reports as a failed connection, and
        // eth_requestAccounts — the connect call — is the one a dApp is most
        // likely to repeat while it waits.
        if (!isRead && !hasPendingFor(host, call.method)) {
            val now = SystemClock.elapsedRealtime()
            if (lastDispatchAt.size > THROTTLE_TABLE_LIMIT) lastDispatchAt.clear()
            val throttleKey = host + '\n' + call.method
            val last = lastDispatchAt[throttleKey] ?: 0L
            if (now - last < MIN_METHOD_GAP_MS) {
                respondError(
                    call.id,
                    WalletBridgeError(WalletBridgeProtocol.CODE_RATE_LIMITED, "Too many requests, retry shortly")
                )
                return
            }
            lastDispatchAt[throttleKey] = now
        }

        // Cap concurrent work per host, counting reads and prompt-raising
        // calls against their own limits: a page may have many reads in
        // flight at once (that is what a dApp does on load) but only
        // [MAX_PENDING_PER_HOST] prompts waiting for the user. The OLDEST of
        // whichever kind overflows is shed.
        val queue = pendingByHost.getOrPut(host) { ArrayDeque() }
        val cap = if (isRead) MAX_PENDING_READS_PER_HOST else MAX_PENDING_PER_HOST
        while (queue.count { isReadPending(it) == isRead } >= cap) {
            val oldest = queue.firstOrNull { isReadPending(it) == isRead } ?: break
            queue.remove(oldest)
            shed(oldest)
        }
        queue.addLast(call.id)
        pendingById[call.id] = Pending(host, call.method, isRead)

        when (call) {
            is WalletRpcCall -> relay(call)
            is WalletDappCall -> handleDappCall(call, host, url)
        }
    }

    /**
     * Read-only relay to the chain's active network. Needs only a
     * NetworkConfig + method + params — no prompt, so it raises no sheet and
     * never reaches the confirmation queue.
     *
     * Chain-identity calls never reach the network: see
     * [WalletBridgeProtocol.localChainAnswer] for why answering them from
     * local configuration is a correctness fix and not a shortcut.
     *
     * The active network is the one thing here the engine owns, and it is
     * absent until the deferred bind lands — see [awaitActiveNetwork].
     */
    private fun relay(call: WalletRpcCall) {
        if (call.chainType != ChainType.EVM || !WalletBridgeProtocol.isReadonlyRpcMethod(call.method)) {
            respondError(
                call.id,
                WalletBridgeError(WalletBridgeError.UNSUPPORTED_METHOD, "Read-only relay supports known EVM methods only")
            )
            return
        }
        val params = WalletBridgeProtocol.rpcParamsList(call) ?: run {
            respondError(call.id, WalletBridgeError(WalletBridgeError.INVALID_PARAMS, "Relay params must be an array"))
            return
        }
        relayScope.launch {
            val network = awaitActiveNetwork() ?: run {
                respondError(call.id, WalletBridgeError(WalletBridgeError.CHAIN_DISCONNECTED, "No active EVM network"))
                return@launch
            }
            WalletBridgeProtocol.localChainAnswer(call.method, network)?.let { answer ->
                respondSuccess(call.id, answer)
                return@launch
            }
            // Every configured endpoint is a candidate, in the order the network
            // lists them. Only the first was ever tried, which made the others
            // decorative: a network whose primary RPC is rate-limited or blocked
            // failed every read even though a working endpoint was configured
            // right behind it.
            val endpoints = network.rpcUrls.filter { it.isNotBlank() }
            if (endpoints.isEmpty()) {
                respondError(call.id, WalletBridgeError(WalletBridgeError.CHAIN_DISCONNECTED, "No active EVM network"))
                return@launch
            }
            // The page's promise must always settle, and settling it late is
            // the same thing to a dApp as never settling it: every endpoint is
            // tried in turn, each with its own 8s connect/read timeout, so a
            // run of dead or blocked endpoints could take 24s to answer a call
            // wagmi abandons after 10. The budget bounds the whole walk.
            val answer = withTimeoutOrNull(RELAY_BUDGET_MS) { relayAcross(endpoints, call, params) }
            when (answer) {
                is RelayAnswer.Ok -> respondSuccess(call.id, answer.json)
                is RelayAnswer.Failed -> respondError(call.id, answer.error)
                // Budget spent, or every endpoint exhausted: nothing answered.
                null, RelayAnswer.Unreachable -> respondError(
                    call.id,
                    WalletBridgeError(WalletBridgeError.CHAIN_DISCONNECTED, "No RPC endpoint answered")
                )
            }
        }
    }

    /**
     * The active EVM network, waiting out the deferred bind when a read
     * arrives inside it.
     *
     * The bind runs ~2.5 s after startup on purpose (it class-loads the crypto
     * stack), and until it lands there is no active network at all — so every
     * read a page issued in that window answered 4901 "No active EVM network".
     * A dApp cannot tell that from a broken wallet, and for `eth_chainId` it is
     * not one read that failed: wagmi and everything built on it — Uniswap
     * among them — treat a chain-id failure as the CONNECT failing, so the user
     * is shown "Error connecting" and a Try again that repeats the same doomed
     * call against a wallet that was milliseconds from answering. The chain is
     * knowable a moment later, so this waits for the bind rather than guessing;
     * bounded, so an engine that never binds still settles the page's promise
     * instead of leaving it hanging.
     *
     * The wait is on the NETWORK and not on the bind: the bind publishes the
     * profile synchronously and only fills the networks a moment later, so a
     * signal that said "bound" would let this read a bound engine with nothing
     * in it yet. A bound profile that enables no EVM chain can never satisfy
     * it and pays the whole window before its reads are answered 4901 — those
     * reads were failing either way, which is a cheaper price than a chain id
     * this wallet refuses to give.
     */
    private suspend fun awaitActiveNetwork(): NetworkConfig? {
        activeNetworkProvider(ChainType.EVM)?.let { return it }
        val engine = engineProvider() ?: return null
        withTimeoutOrNull(BIND_WAIT_MS) {
            engine.activeNetworks.first { it[ChainType.EVM] != null }
        }
        return activeNetworkProvider(ChainType.EVM)
    }

    /**
     * Walks [endpoints] in order for one read, moving on whenever the endpoint
     * could not answer. The test for that is
     * [WalletBridgeProtocol.shouldTryNextEndpoint] and not a local rule: an
     * endpoint rejecting the certificate, or answering HTTP 429/525 instead of
     * a JSON-RPC result, never got to speak JSON-RPC and is exactly what the
     * second url in a network's list exists for. Judging it here by a narrower
     * rule is what made a dApp read fail on the first url while a working one
     * sat next to it.
     */
    private suspend fun relayAcross(
        endpoints: List<String>,
        call: WalletRpcCall,
        params: List<JsonElement>
    ): RelayAnswer {
        for (endpoint in endpoints) {
            try {
                return RelayAnswer.Ok(rpc.call(endpoint, call.method, params).toString())
            } catch (e: CancellationException) {
                // The relay budget ran out. Rethrown so withTimeoutOrNull can
                // end the walk — swallowing it here would just move on to the
                // next endpoint and spend a timeout that is already gone.
                throw e
            } catch (e: WalletException) {
                if (!WalletBridgeProtocol.shouldTryNextEndpoint(e)) {
                    return RelayAnswer.Failed(WalletBridgeProtocol.relayError(e))
                }
            } catch (e: Exception) {
                // Transport-level failure outside the typed hierarchy —
                // also nothing answered.
            }
        }
        return RelayAnswer.Unreachable
    }

    /** What one read's walk across the endpoints produced. */
    private sealed interface RelayAnswer {
        data class Ok(val json: String) : RelayAnswer
        data class Failed(val error: WalletBridgeError) : RelayAnswer
        object Unreachable : RelayAnswer
    }

    /**
     * Settles one call whose payload needs the account's PUBLIC key.
     *
     * The key is derived by the engine from the account's own key material,
     * which is a suspend call, so this answer is pushed from [relayScope]
     * instead of being written inline the way the address-only replies are.
     * Ordering stays sane: [respondSuccess] is main-confined, the page sees
     * exactly one response per id, and nothing else can settle this call.
     *
     * A NULL KEY IS NOT AN ERROR — that is the whole point of the signature.
     * Every call that reaches here has a working address-only answer; the key
     * is additive. A locked wallet, or an account whose key cannot be read,
     * therefore degrades to exactly the result this bridge produced before the
     * key existed, rather than failing a connect that used to succeed.
     */
    private fun settleWithPublicKey(
        id: String,
        account: WalletAccountRecord,
        build: (String?) -> String
    ) {
        val engine = engineProvider()
        if (engine == null) {
            respondSuccess(id, build(null))
            return
        }
        relayScope.launch {
            val hex = runCatching { engine.publicKeyOf(account.id) }.getOrNull()
            respondSuccess(id, build(hex))
        }
    }

    private fun handleDappCall(call: WalletDappCall, host: String, originUrl: String) {
        val engine = engineProvider()
        if (engine == null) {
            respondError(call.id, WalletBridgeError(WalletBridgeError.DISCONNECTED, "Wallet is not available"))
            return
        }

        // dApp-initiated disconnect: revoke, then resolve. The page's cached
        // account state is cleared by the injected script on its side; the
        // NEXT connect for this host prompts again.
        if (call.method == WalletBridgeProtocol.METHOD_DISCONNECT) {
            engine.revokeDappPermission(host, call.chainType)
            respondSuccess(call.id, "{}")
            return
        }

        // eth_accounts: the permitted address list, never a prompt.
        if (call.method == WalletBridgeProtocol.METHOD_ETH_ACCOUNTS) {
            val addresses = engine.accounts.value
                .filter { it.chainType == ChainType.EVM && engine.isDappPermitted(host, ChainType.EVM, it.address, call.method) }
                .map { it.address }
            respondSuccess(call.id, WalletBridgeProtocol.accountsResult(addresses))
            return
        }

        val primary = engine.accounts.value.firstOrNull { it.chainType == call.chainType }

        // Keplr getKey: a read for an already-permitted host (no prompt, and
        // no prompt-free path to it otherwise); a non-permitted host gets
        // 4100 so the dApp calls enable() first.
        if (call.method == WalletBridgeProtocol.METHOD_COSMOS_GET_KEY) {
            val permitted = primary != null && engine.isDappPermitted(
                host, call.chainType, primary.address,
                WalletBridgeProtocol.permissionMethodFor(call.chainType)
            )
            if (!permitted) {
                respondError(
                    call.id,
                    WalletBridgeError(WalletBridgeError.UNAUTHORIZED, "Connect to this chain first")
                )
                return
            }
            settleWithPublicKey(call.id, primary) { hex ->
                WalletBridgeProtocol.keplrKeyResult(primary.address, hex)
            }
            return
        }

        // Cosmos is served from the profile's ACTIVE network only: a dApp
        // naming a different chain id is told so (4902) instead of being
        // handed an address on a chain the wallet will not sign for.
        if (call.chainType == ChainType.COSMOS && call.method == WalletBridgeProtocol.METHOD_COSMOS_ENABLE) {
            val requested = (call.params as? JsonObject)?.let { (it["chainId"] as? JsonPrimitive)?.contentOrNull }
            val active = activeNetworkProvider(ChainType.COSMOS)?.chainId
            if (!requested.isNullOrBlank() && !active.isNullOrBlank() && requested != active) {
                respondError(
                    call.id,
                    WalletBridgeError(
                        WalletBridgeError.UNRECOGNIZED_CHAIN,
                        "This wallet serves " + active + " for Cosmos"
                    )
                )
                return
            }
        }

        // Already-permitted connects auto-approve silently.
        if (call.method in WalletBridgeProtocol.CONNECT_METHODS && primary != null &&
            engine.isDappPermitted(
                host, call.chainType, primary.address,
                WalletBridgeProtocol.permissionMethodFor(call.chainType)
            )
        ) {
            // Same shape the engine settles a PROMPTED connect with, key and
            // all — so a dApp cannot tell the two paths apart.
            settleWithPublicKey(call.id, primary) { hex ->
                WalletBridgeProtocol.connectSuccessResult(call.chainType, primary.address, hex)
            }
            return
        }

        val dappId = UUID.randomUUID().toString()
        val networkId = activeNetworkProvider(call.chainType)?.id
        when (val built = WalletBridgeProtocol.buildDappRequest(call, dappId, host, originUrl, networkId, primary?.address)) {
            is DappBuildResult.Invalid -> respondError(call.id, built.error)
            is DappBuildResult.Ok -> {
                pendingById[call.id]?.dappRequestId = dappId
                pageIdByDappId[dappId] = call.id
                engine.submitDappRequest(built.request) { outcome -> onOutcome(outcome) }
            }
        }
    }

    private fun onOutcome(outcome: DappOutcome) {
        runOnMain {
            val pageId = pageIdByDappId.remove(outcome.requestId) ?: return@runOnMain
            val error = outcome.error
            if (error != null) {
                respondError(pageId, error)
            } else {
                respondSuccess(pageId, outcome.resultJson)
            }
        }
    }

    // ------------------------------------------------------------------
    // Response delivery
    // ------------------------------------------------------------------

    private fun respondSuccess(pageId: String, resultJson: String?) {
        finish(pageId, WalletBridgeProtocol.encodeResponseScript(pageId, resultJson, 0, null))
    }

    private fun respondError(pageId: String?, error: WalletBridgeError) {
        if (pageId == null) return
        finish(pageId, WalletBridgeProtocol.encodeResponseScript(pageId, null, error.code, error.message))
    }

    /** Delivers one script and retires the pending call (idempotent). */
    private fun finish(pageId: String, script: String) {
        runOnMain {
            removePending(pageId)
            sessionRef.get()?.evaluateJs(script)
        }
    }

    private fun removePending(pageId: String) {
        val entry = pendingById.remove(pageId) ?: return
        entry.dappRequestId?.let { pageIdByDappId.remove(it) }
        val queue = pendingByHost[entry.host]
        if (queue != null) {
            queue.remove(pageId)
            if (queue.isEmpty()) pendingByHost.remove(entry.host)
        }
    }

    /** True when this host already has a call of [method] waiting for an answer. */
    private fun hasPendingFor(host: String, method: String): Boolean =
        pendingByHost[host]?.any { pendingById[it]?.method == method } == true

    /** Whether a still-pending call is a read (never null for a queued id). */
    private fun isReadPending(pageId: String): Boolean = pendingById[pageId]?.isRead == true

    /**
     * Drops one pending call because the host is at its cap.
     *
     * The code matters as much as the drop: this used to answer 4001, which
     * EIP-1193 defines as the USER declining. A dApp told that mid-connect
     * reports a rejected connection — or, worse, believes the user said no
     * and gives up — when what actually happened is that the wallet was busy.
     * -32005 says busy, which is true and retryable.
     *
     * A call that had already reached the engine also has a confirmation
     * waiting for the user, and that has to go with it: the page will never
     * see its answer, so leaving the prompt up asks the user to approve
     * something that can no longer be delivered.
     */
    private fun shed(pageId: String) {
        pendingById[pageId]?.dappRequestId?.let { dappId ->
            pageIdByDappId.remove(dappId)
            runCatching { engineProvider()?.cancelDappRequests(listOf(dappId)) }
        }
        // respondError retires the call from pendingById AND from the host
        // queue, so the shed id cannot linger and be counted again.
        respondError(
            pageId,
            WalletBridgeError(WalletBridgeProtocol.CODE_RATE_LIMITED, "Too many pending requests")
        )
    }

    /** Runs [action] on the main thread (immediate when already there). */
    private inline fun runOnMain(crossinline action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            main.post { action() }
        }
    }

    /**
     * Releases everything this bridge owns. MUST be called when the session
     * behind it is destroyed.
     *
     * [sessionRef] being weak is not enough on its own: an in-flight relay
     * coroutine is a strong reference to the bridge, and through it to the
     * Handler and the whole pending-state bookkeeping. Without this the bridge
     * of every closed or LRU-evicted tab stayed alive until its last relay
     * timed out, and any relay still running kept issuing `respond*` calls
     * into a session that was already gone.
     *
     * The shared relay client is deliberately NOT shut down here — it belongs
     * to the process, not to one bridge.
     */
    fun dispose() {
        relayScope.cancel()
        runOnMain {
            // The engine outlives this bridge and keeps a callback per
            // outstanding request. Hand those back before clearing the map,
            // or a prompt for a page that no longer exists stays queued and
            // the callback keeps this bridge reachable from the singleton.
            if (pageIdByDappId.isNotEmpty()) {
                val ids = pageIdByDappId.keys.toList()
                runCatching { engineProvider()?.cancelDappRequests(ids) }
            }
            pendingById.clear()
            pageIdByDappId.clear()
            pendingByHost.clear()
            lastDispatchAt.clear()
        }
    }

    companion object {
        /** JS object name the injected script (and, defensively, pages) see. */
        const val JS_INTERFACE_NAME = "RoomWallet"

        /**
         * ONE relay client for every bridge in the process.
         *
         * OkHttp is explicitly designed to be shared: the connection pool and
         * the dispatcher's thread pool are the expensive parts, and they are
         * exactly what a per-bridge client duplicated. Lazy so a process that
         * never touches a dApp never builds one.
         */
        private val sharedRelayClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        }

        /** Minimum gap between calls of the same (host, method). */
        private const val MIN_METHOD_GAP_MS = 300L

        /**
         * How long a read may wait for the engine's first bind before it is
         * answered as CHAIN_DISCONNECTED. Comfortably longer than the 2.5 s
         * the bind is deferred by, so only an engine that never binds reaches
         * it.
         */
        private const val BIND_WAIT_MS = 8_000L

        /** Prompt-raising calls allowed to wait for the user at once, per host. */
        private const val MAX_PENDING_PER_HOST = 8

        /**
         * Reads allowed in flight at once, per host.
         *
         * Deliberately separate from [MAX_PENDING_PER_HOST] and much larger:
         * a dApp loads by firing many reads at once, and none of them can pile
         * a prompt on the confirmation queue. It still has to be a bound —
         * the relay client is shared process-wide, so an unbounded page could
         * starve every other tab's reads — and 16 concurrent HTTP reads is
         * already more than any dApp does.
         */
        private const val MAX_PENDING_READS_PER_HOST = 16

        /**
         * Total time one read may spend walking its endpoints.
         *
         * The endpoints are tried in order, each with [RPC_TIMEOUT_SECONDS]
         * connect and read timeouts, so an unreachable network cost as long as
         * the endpoint list was — a promise settled after the dApp had already
         * given up is a promise that never settled.
         */
        private const val RELAY_BUDGET_MS = 12_000L

        /** Hard cap on throttle bookkeeping before it is reset (hostile-method growth). */
        private const val THROTTLE_TABLE_LIMIT = 256

        /** Relay timeout (connect/read/write) per call. */
        private const val RPC_TIMEOUT_SECONDS = 8L
    }
}
