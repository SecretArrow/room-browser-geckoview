package com.roombrowser.agent

import com.roombrowser.browser.wallet.DappDecision
import com.roombrowser.browser.wallet.DappRequest
import com.roombrowser.browser.wallet.NetworkRecord
import com.roombrowser.browser.wallet.WalletAccountRecord
import com.roombrowser.browser.wallet.WalletEngineApi
import com.roombrowser.browser.wallet.WalletLockedException
import com.roombrowser.browser.wallet.WalletLockState
import com.roombrowser.domain.agent.AgentJson
import com.roombrowser.domain.agent.ToolResult
import com.roombrowser.domain.wallet.model.BalanceResult
import com.roombrowser.domain.wallet.model.BroadcastResult
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.domain.wallet.model.FeeEstimate
import com.roombrowser.domain.wallet.model.NetworkConfig
import com.roombrowser.domain.wallet.model.WalletException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Narrow slice of [WalletEngineApi] so the tools are JVM-testable and usable
 * without the chat UI. Reads are synchronous (StateFlow); mutations suspend.
 * The send path is deliberately reached ONLY through [sendNative] and
 * [estimateSendFee] — the engine owns the keys, the signing and the broadcast,
 * and nothing here ever sees key material.
 */
interface AgentWalletAccess {
    val lockState: WalletLockState
    val pendingRequests: List<DappRequest>
    val accounts: List<WalletAccountRecord>
    val activeNetworks: Map<ChainType, NetworkConfig>
    val networks: List<NetworkRecord>

    /**
     * Native balance per account id, as the engine last fetched it. The engine
     * refreshes on bind and when the wallet screen asks; the tools read this
     * map as-is rather than triggering a network fan-out on every call.
     */
    val balances: Map<String, BalanceResult>

    fun decideDappRequest(decision: DappDecision)

    suspend fun setActiveNetwork(chainType: ChainType, networkId: String)

    /** Fee preview for a native send; null when the chain cannot estimate one. */
    suspend fun estimateSendFee(
        chainType: ChainType,
        networkId: String,
        fromAccountId: String,
        to: String,
        amount: String
    ): FeeEstimate?

    /** The engine's own send path — the ONLY route a transfer may take. */
    suspend fun sendNative(
        accountId: String,
        networkId: String,
        to: String,
        amount: String
    ): BroadcastResult
}

class WalletEngineAccess(private val engine: WalletEngineApi) : AgentWalletAccess {
    override val lockState: WalletLockState get() = engine.lockState.value
    override val pendingRequests: List<DappRequest> get() = engine.pendingRequests.value
    override val accounts: List<WalletAccountRecord> get() = engine.accounts.value
    override val activeNetworks: Map<ChainType, NetworkConfig> get() = engine.activeNetworks.value
    override val networks: List<NetworkRecord> get() = engine.networks.value
    override val balances: Map<String, BalanceResult> get() = engine.balances.value

    override fun decideDappRequest(decision: DappDecision) = engine.decideDappRequest(decision)

    override suspend fun setActiveNetwork(chainType: ChainType, networkId: String) =
        engine.setActiveNetwork(chainType, networkId)

    override suspend fun estimateSendFee(
        chainType: ChainType,
        networkId: String,
        fromAccountId: String,
        to: String,
        amount: String
    ): FeeEstimate? = engine.estimateSendFee(chainType, networkId, fromAccountId, to, amount)

    override suspend fun sendNative(
        accountId: String,
        networkId: String,
        to: String,
        amount: String
    ): BroadcastResult = engine.sendNative(accountId, networkId, to, amount)
}

/**
 * Wallet tools for the dApp request queue, the active network, the balances
 * and the agent-initiated native send.
 *
 * Reject is never gated. Approve requires the request to have been listed this
 * turn AND the user's explicit confirmation, then settles through the engine's
 * own [AgentWalletAccess.decideDappRequest]. Switching accepts only a known,
 * enabled network; a new network cannot be added here. Sending is the one tool
 * that MOVES funds on its own initiative, so it sits behind the triple gate
 * documented on [AgentWalletTools.send].
 */
class AgentWalletTools(
    private val wallet: () -> AgentWalletAccess,
    private val confirmApproval: suspend (String) -> Boolean = { false }
) {

    /** Request ids listed this turn; approve requires membership. */
    private val surfaced = mutableSetOf<String>()

    fun readState(): ToolResult {
        val w = wallet()
        val text = buildString {
            appendLine("lock_state: ${w.lockState.name}")
            appendLine("accounts:")
            if (w.accounts.isEmpty()) {
                appendLine("  (none)")
            } else {
                w.accounts.forEach { account ->
                    append("  - ${account.chainType.displayName} · ${account.label} · ${account.address}")
                    // A compact balance on the same line answers "what do I
                    // have" without a second call. A missing or failed balance
                    // stays SILENT here — this tool must stay small, and
                    // wallet_balances is the one that reports those honestly.
                    (w.balances[account.id] as? BalanceResult.Ok)?.let {
                        append(" · ${it.amount} ${it.symbol}")
                    }
                    appendLine()
                }
            }
            appendLine("active_networks:")
            if (w.activeNetworks.isEmpty()) {
                appendLine("  (none)")
            } else {
                w.activeNetworks.forEach { (chain, net) ->
                    appendLine("  - ${chain.displayName}: ${net.name} (${net.id})")
                }
            }
            appendLine("known_networks:")
            if (w.networks.isEmpty()) {
                appendLine("  (none)")
            } else {
                w.networks.forEach {
                    val disabled = if (it.enabled) "" else " (disabled)"
                    appendLine("  - ${it.config.id} · ${it.config.name} · ${it.config.chainType.displayName}$disabled")
                }
            }
        }.trimEnd()
        return ToolResult(true, text)
    }

    /**
     * The native balances, per account, on each chain's active network.
     *
     * Read-only and never confirmed. The engine's map is read AS-IS: a balance
     * refresh fans out one RPC per account, which is neither cheap nor
     * non-blocking, and the engine already refreshes on bind and whenever the
     * wallet screen opens — so the honest answer to a stale row is "not fetched
     * yet", not a hidden network round-trip.
     */
    fun readBalances(accountId: String?): ToolResult {
        lockedOrAbsent()?.let { return it }
        val w = wallet()
        val accounts = if (accountId.isNullOrBlank()) {
            w.accounts
        } else {
            listOf(
                w.accounts.firstOrNull { it.id == accountId }
                    ?: return ToolResult(false, "Unknown account '$accountId' — call wallet_state for the ids.")
            )
        }
        if (accounts.isEmpty()) {
            return ToolResult(true, "The wallet has no accounts to read balances for.")
        }
        val body = accounts.joinToString("\n\n") { account ->
            val network = w.activeNetworks[account.chainType]
            val header = "${account.label} (${account.chainType.displayName}) · ${account.address}"
            val networkLine = network?.let { "network: ${it.name} (${it.id})" }
                ?: "network: none active for this chain"
            when (val balance = w.balances[account.id]) {
                is BalanceResult.Ok -> "$header\n$networkLine\nbalance: ${balance.amount} ${balance.symbol}"
                is BalanceResult.Error -> "$header\n$networkLine\nbalance: unavailable — ${balance.message}"
                null -> "$header\n$networkLine\nbalance: not fetched yet — the wallet fetches when its screen opens; " +
                    "ask the user to open it, then call this again"
            }
        }
        return ToolResult(true, "Native balances on each chain's active network:\n\n$body")
    }

    /** Lists pending requests with full detail and marks them approvable. */
    fun listRequests(): ToolResult {
        val pending = wallet().pendingRequests
        if (pending.isEmpty()) return ToolResult(true, "No wallet requests are waiting for a decision.")
        surfaced += pending.map { it.id }
        val body = pending.mapIndexed { index, request ->
            "[$index]\n${WalletRequestFormat.detail(request)}"
        }.joinToString("\n\n")
        return ToolResult(
            true,
            "Pending wallet requests (${pending.size}):\n\n$body\n\n" +
                "Approve one with wallet_approve using its exact request_id, " +
                "or refuse it with wallet_reject."
        )
    }

    /** Approves one listed, still-pending request after the user confirms. */
    suspend fun approve(requestId: String?): ToolResult {
        val id = requestId?.takeIf { it.isNotBlank() }
            ?: return ToolResult(false, "missing 'request_id' argument")
        val request = wallet().pendingRequests.firstOrNull { it.id == id }
            ?: return ToolResult(false, "No pending wallet request with id $id — call wallet_requests for the current list.")
        if (id !in surfaced) {
            return ToolResult(
                false,
                "This wallet request has not been shown to the user, so it cannot be approved. " +
                    "Call wallet_requests, read the request, then approve it — or refuse it with wallet_reject."
            )
        }
        // The user must see the same detail the model saw.
        if (!confirmApproval("Approve wallet request:\n${WalletRequestFormat.detail(request)}")) {
            return ToolResult(false, "the user did not approve this wallet request")
        }
        wallet().decideDappRequest(DappDecision(requestId = id, approved = true))
        return ToolResult(
            true,
            "Approved ${WalletRequestFormat.summary(request)}. The wallet is processing it. " +
                "Approving a transaction broadcasts it and approving a signature makes that " +
                "signature usable by the site — neither can be undone."
        )
    }

    /** Rejects one pending request; never confirmed, never requires listing. */
    fun reject(requestId: String?): ToolResult {
        val id = requestId?.takeIf { it.isNotBlank() }
            ?: return ToolResult(false, "missing 'request_id' argument")
        val request = wallet().pendingRequests.firstOrNull { it.id == id }
            ?: return ToolResult(false, "No pending wallet request with id $id — call wallet_requests for the current list.")
        wallet().decideDappRequest(DappDecision(requestId = id, approved = false))
        return ToolResult(
            true,
            "Rejected ${WalletRequestFormat.summary(request)}. The site received a " +
                "user-rejected error and nothing was signed or sent."
        )
    }

    /** Switches to a known, enabled network after the user confirms. */
    suspend fun switchNetwork(networkId: String?): ToolResult {
        val id = networkId?.takeIf { it.isNotBlank() }
            ?: return ToolResult(false, "missing 'network_id' argument")
        val w = wallet()
        val record = w.networks.firstOrNull { it.config.id == id }
            ?: return ToolResult(
                false,
                "Unknown network '$id'. The wallet can only switch to a network it already knows — " +
                    "call wallet_state to list them. This tool cannot add a network."
            )
        if (!record.enabled) {
            return ToolResult(
                false,
                "Network '${record.config.name}' is disabled in the wallet and cannot be made active. " +
                    "Enable it in the wallet first."
            )
        }
        val label = "Switch the wallet's active ${record.config.chainType.displayName} " +
            "network to ${record.config.name} (${record.config.id})?"
        if (!confirmApproval(label)) {
            return ToolResult(false, "the user did not approve the network switch")
        }
        return try {
            // The engine re-checks existence and chain, so a guessed id fails.
            w.setActiveNetwork(record.config.chainType, record.config.id)
            ToolResult(
                true,
                "Active ${record.config.chainType.displayName} network is now " +
                    "${record.config.name} (${record.config.id}). Connected sites receive chainChanged."
            )
        } catch (e: WalletException) {
            ToolResult(false, e.message ?: "the network could not be switched")
        }
    }

    /**
     * The agent-initiated native send, behind the owner's TRIPLE GATE.
     *
     * Gate C — mode refusals (checked before this method is ever reached):
     * [com.roombrowser.domain.agent.AgentTools.WALLET_SEND] sits in
     * [com.roombrowser.domain.agent.PlanModePolicy.REFUSED] and in
     * [com.roombrowser.domain.agent.AgentTools.WALLET_TOOLS], which is what a
     * scheduled run, a hidden-page turn and the task-permission map all refuse
     * by — no transfer may be initiated where nobody is watching.
     *
     * Gate A — surfaced THIS TURN: the summary is computed HERE, from the
     * engine's own records, and shown to the user on the wallet approval card
     * (the same [confirmApproval] mechanism wallet_approve uses). There is no
     * route where the funds move on a summary the user was not shown in the
     * same breath, because the showing and the confirming are one prompt: the
     * card and the execution cannot be separated the way a model could sepa-
     * rate a listing from an approval. The model may name the account and the
     * amount; the from-address, the network and the fee are read from engine
     * state it cannot rewrite.
     *
     * Gate B — explicit confirmation of the IDENTICAL text: [confirmApproval]
     * is the wallet's own prompt — deliberately NOT the generic confirm gate,
     * which YOLO and the Confirm actions switch bypass — it defaults to deny
     * when unwired, and it is handed exactly the string [WalletSendFormat]
     * built, so the user answers that transfer, never a paraphrase.
     *
     * Execution goes through [AgentWalletAccess.sendNative] and nowhere else:
     * the engine validates the recipient and the amount, holds the keys and
     * records the activity. Key material never crosses this file and nothing
     * here logs the arguments beyond the summary the user already approved.
     * LOGIN/CONSENT NOTE: a connected site or a signed-in page is not consent;
     * only the user's answer to the card is.
     */
    suspend fun send(
        accountId: String?,
        networkId: String?,
        to: String?,
        amount: String?
    ): ToolResult {
        lockedOrAbsent()?.let { return it }
        val w = wallet()
        val fromId = accountId?.takeIf { it.isNotBlank() }
            ?: return ToolResult(false, "missing 'account_id' argument")
        val netId = networkId?.takeIf { it.isNotBlank() }
            ?: return ToolResult(false, "missing 'network_id' argument")
        val destination = to?.takeIf { it.isNotBlank() }
            ?: return ToolResult(false, "missing 'to' argument")
        val howMuch = amount?.takeIf { it.isNotBlank() }
            ?: return ToolResult(false, "missing 'amount' argument")
        val account = w.accounts.firstOrNull { it.id == fromId }
            ?: return ToolResult(
                false,
                "Unknown account '$fromId' — call wallet_state for the accounts. " +
                    "Funds can only leave an account the wallet holds."
            )
        val record = w.networks.firstOrNull { it.config.id == netId }
            ?: return ToolResult(
                false,
                "Unknown network '$netId'. The wallet can only send on a network it already knows — " +
                    "call wallet_state to list them. This tool cannot add a network."
            )
        if (!record.enabled) {
            return ToolResult(
                false,
                "Network '${record.config.name}' is disabled in the wallet and cannot be sent on. " +
                    "Enable it in the wallet first."
            )
        }
        if (record.config.chainType != account.chainType) {
            return ToolResult(
                false,
                "Network '${record.config.name}' is a ${record.config.chainType.displayName} network, " +
                    "but '${account.label}' is a ${account.chainType.displayName} account — the two do not match."
            )
        }
        // The fee preview is best-effort by design: several chains cannot be
        // estimated cheaply, and refusing them would make the tool EVM-only.
        // The summary says so; the engine's send path applies its usual fee.
        val fee = w.estimateSendFee(account.chainType, record.config.id, account.id, destination, howMuch)
        val summary = WalletSendFormat.summary(account, record.config, destination, howMuch, fee)
        if (!confirmApproval(summary)) {
            return ToolResult(
                false,
                "the user did not approve this transfer. Nothing was sent. Re-surface the request " +
                    "only if the user asks for it again in their own words."
            )
        }
        return try {
            // The engine re-validates account, network, chain, recipient and
            // amount, so a stale id between confirmation and broadcast fails
            // closed here rather than sending somewhere else.
            when (val result = w.sendNative(account.id, record.config.id, destination, howMuch)) {
                is BroadcastResult.Ok -> ToolResult(
                    true,
                    "Sent. $summary\n\nTransaction hash: ${result.hash}. " +
                        "This cannot be undone; the wallet's activity list follows the broadcast."
                )
                is BroadcastResult.Error -> ToolResult(false, result.message)
            }
        } catch (e: WalletLockedException) {
            // The session can lock between the user's confirmation and the
            // broadcast; the honest answer is the locked guidance, never a
            // silent retry of a confirmed transfer.
            lockedOrAbsent() ?: ToolResult(false, e.message ?: "the wallet locked before the send could run")
        } catch (e: WalletException) {
            ToolResult(false, e.message ?: "the transfer could not be broadcast")
        }
    }

    /**
     * The shared front door for the tools that touch key-adjacent state:
     * a LOCKED session or an absent wallet gets ONE guidance answer — the user
     * unlocks it in the wallet screen — and never a prompt, a balance or a
     * transfer. Null when the session is unlocked and the tool may proceed.
     */
    private fun lockedOrAbsent(): ToolResult? = when (wallet().lockState) {
        WalletLockState.NO_WALLET -> NO_WALLET_RESULT
        WalletLockState.LOCKED -> LOCKED_RESULT
        WalletLockState.UNLOCKED -> null
    }

    companion object {
        /**
         * The locked and no-wallet answers, shared by every wallet tool so the
         * model reads ONE instruction about how access is restored — and so no
         * wallet tool ever asks for the material that would provide it. The
         * unlock lives behind the user's own screen lock in the wallet screen;
         * a seed phrase or private key must never be requested, typed into a
         * page, or echoed back, and no tool here can reach them.
         */
        val LOCKED_RESULT = ToolResult(
            false,
            "The wallet is locked, so balances and sends are unavailable. Ask the user to open the " +
                "wallet screen and unlock it with their own screen lock, then try again. NEVER ask " +
                "for a password, PIN, seed phrase or private key — no tool reaches those, and they " +
                "must never be typed into a page."
        )

        val NO_WALLET_RESULT = ToolResult(
            false,
            "This profile has no wallet yet, so there is nothing to read or send. The user can create " +
                "or import one in the wallet screen. NEVER ask for a seed phrase or private key."
        )
    }
}

/** Renders a [DappRequest] for the model and the user; pure and JVM-testable. */
object WalletRequestFormat {

    const val MAX_FIELD_CHARS = 400
    const val MAX_RAW_CHARS = 1600

    fun methodName(request: DappRequest): String = when (request) {
        is DappRequest.Connect -> "connect"
        is DappRequest.SignMessage -> "sign_message"
        is DappRequest.SignTypedData -> "sign_typed_data"
        is DappRequest.SendTransaction -> "send_transaction"
        is DappRequest.SwitchChain -> "switch_chain"
        is DappRequest.AddChain -> "add_chain"
    }

    /** One line, for a result sentence rather than a decision. */
    fun summary(request: DappRequest): String =
        "${methodName(request)} from ${request.host} on ${request.chainType.displayName}"

    /** The detail shown identically to the model and to the confirming user. */
    fun detail(request: DappRequest): String = buildString {
        appendLine("id: ${request.id}")
        appendLine("host: ${request.host}")
        appendLine("method: ${methodName(request)}")
        appendLine("chain: ${request.chainType.displayName}")
        when (request) {
            is DappRequest.Connect -> appendLine("origin: ${clip(request.originUrl)}")
            is DappRequest.SignMessage -> {
                appendLine("account: ${request.accountAddress}")
                appendLine("message: ${clip(request.displayMessage)}")
                if (request.message != request.displayMessage && request.message.isNotBlank()) {
                    appendLine("raw_message: ${clip(request.message)}")
                }
            }
            is DappRequest.SignTypedData -> {
                appendLine("account: ${request.accountAddress}")
                appendLine("typed_data: ${clip(request.typedDataJson, MAX_RAW_CHARS)}")
            }
            is DappRequest.SendTransaction -> {
                appendLine("account: ${request.accountAddress}")
                appendLine("network: ${request.networkId}")
                append(transactionFields(request.txParamsJson))
                request.feeEstimate?.let { appendLine("fee: ${it.label} ${it.estimatedCost}") }
            }
            is DappRequest.SwitchChain -> appendLine("target_network: ${request.targetNetworkId}")
            is DappRequest.AddChain -> {
                val proposed = request.proposed
                appendLine("proposed_name: ${clip(proposed.name)}")
                appendLine("proposed_chain_id: ${clip(proposed.chainId)}")
                appendLine("proposed_rpc: ${clip(proposed.rpcUrls.joinToString(", "))}")
            }
        }
    }.trimEnd()

    /**
     * `to`/`value`/`data` when present (EVM); otherwise the clipped raw payload,
     * since a Solana/Aptos/Cosmos transaction has no such pair.
     */
    private fun transactionFields(txParamsJson: String): String {
        val obj = runCatching { AgentJson.parseToJsonElement(txParamsJson) as? JsonObject }.getOrNull()
            ?: return "tx: ${clip(txParamsJson, MAX_RAW_CHARS)}\n"
        fun field(key: String): String? =
            (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val to = field("to")
        val value = field("value")
        val data = field("data") ?: field("input")
        if (to == null && value == null && data == null) {
            return "tx: ${clip(txParamsJson, MAX_RAW_CHARS)}\n"
        }
        return buildString {
            to?.let { appendLine("to: ${clip(it)}") }
            value?.let { appendLine("value: ${clip(it)} (base units)") }
            data?.let { appendLine("data: ${clip(it)}") }
            field("gas")?.let { appendLine("gas_limit: ${clip(it)}") }
            field("gasPrice")?.let { appendLine("gas_price: ${clip(it)}") }
            field("maxFeePerGas")?.let { appendLine("max_fee_per_gas: ${clip(it)}") }
        }
    }

    private fun clip(text: String, max: Int = MAX_FIELD_CHARS): String =
        if (text.length <= max) text else text.take(max) + "…"
}

/**
 * Builds the native-send summary the user confirms and the tool result
 * reports — pure and JVM-testable, like [WalletRequestFormat].
 *
 * One format, two consumers, ONE string: the approval card renders it
 * verbatim, so what the model was told it would pay is exactly what the user
 * was asked about, and what the result quotes afterwards is what they
 * approved. Determinism is a security property here — a summary that could
 * drift between build and show would be a mismatch nobody could catch.
 */
object WalletSendFormat {

    const val MAX_FIELD_CHARS = 400

    fun summary(
        account: WalletAccountRecord,
        network: NetworkConfig,
        to: String,
        amount: String,
        fee: FeeEstimate?
    ): String = buildString {
        appendLine(
            "Send ${amount.trim()} ${network.nativeSymbol} from \"${account.label}\" " +
                "to ${clip(to.trim())} on ${network.name} (${network.id})."
        )
        appendLine("from: ${account.label} · ${account.address} (${account.chainType.displayName})")
        appendLine("to: ${clip(to.trim())}")
        appendLine("amount: ${amount.trim()} ${network.nativeSymbol}")
        appendLine("network: ${network.name} (${network.id})")
        if (fee == null) {
            // Several chains cannot be estimated without a round-trip the
            // engine does not make here; the wallet's own send path still
            // applies its usual fee. Saying so beats inventing a number the
            // user would confirm against nothing.
            appendLine("fee: not estimated in advance — the wallet's usual fee applies")
        } else {
            appendLine("fee: ${fee.estimatedCost} (${fee.label})")
        }
    }.trimEnd()

    private fun clip(text: String, max: Int = MAX_FIELD_CHARS): String =
        if (text.length <= max) text else text.take(max) + "…"
}
