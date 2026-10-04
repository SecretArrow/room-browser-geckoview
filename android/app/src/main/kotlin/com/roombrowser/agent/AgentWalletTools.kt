package com.roombrowser.agent

import com.roombrowser.browser.wallet.DappDecision
import com.roombrowser.browser.wallet.DappRequest
import com.roombrowser.browser.wallet.NetworkRecord
import com.roombrowser.browser.wallet.WalletAccountRecord
import com.roombrowser.browser.wallet.WalletEngineApi
import com.roombrowser.browser.wallet.WalletLockState
import com.roombrowser.domain.agent.AgentJson
import com.roombrowser.domain.agent.ToolResult
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.domain.wallet.model.NetworkConfig
import com.roombrowser.domain.wallet.model.WalletException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The wallet surface the agent's wallet tools need — a slice of
 * [WalletEngineApi] rather than the whole contract, so the tools can be
 * driven from a JVM test with a small fake and reused by a future scheduled
 * runner that has no chat UI.
 *
 * The read side is synchronous because the engine holds it in [StateFlow]s and
 * every wallet-tool call runs on the main thread (the executor dispatches
 * there). Only the two operations that mutate the engine are suspending.
 */
interface AgentWalletAccess {
    val lockState: WalletLockState
    val pendingRequests: List<DappRequest>
    val accounts: List<WalletAccountRecord>
    val activeNetworks: Map<ChainType, NetworkConfig>
    val networks: List<NetworkRecord>

    fun decideDappRequest(decision: DappDecision)

    suspend fun setActiveNetwork(chainType: ChainType, networkId: String)
}

/** Adapts the app-wide [WalletEngineApi] to the narrow agent surface. */
class WalletEngineAccess(private val engine: WalletEngineApi) : AgentWalletAccess {
    override val lockState: WalletLockState get() = engine.lockState.value
    override val pendingRequests: List<DappRequest> get() = engine.pendingRequests.value
    override val accounts: List<WalletAccountRecord> get() = engine.accounts.value
    override val activeNetworks: Map<ChainType, NetworkConfig> get() = engine.activeNetworks.value
    override val networks: List<NetworkRecord> get() = engine.networks.value

    override fun decideDappRequest(decision: DappDecision) = engine.decideDappRequest(decision)

    override suspend fun setActiveNetwork(chainType: ChainType, networkId: String) =
        engine.setActiveNetwork(chainType, networkId)
}

/**
 * The agent's wallet tools: inspect the wallet, list the dApp requests waiting
 * for a decision, approve or reject one, and switch the active network.
 *
 * SAFETY, because an LLM approving wallet requests is how funds leave:
 *
 *  - REJECT ALWAYS WORKS. [reject] asks nothing and is gated by nothing — it is
 *    the safe direction, and a request the model cannot evaluate must be
 *    refusable without friction.
 *  - APPROVE IS NEVER BLIND. The model can only approve a request it has
 *    surfaced through [listRequests] in this same turn ([surfaced]); an id it
 *    has not listed is refused. The detail shown to the model ([WalletRequestFormat.detail])
 *    is the SAME text handed to the confirmation, so the user sees exactly what
 *    the model saw — host, method, and the to/value/data or message being
 *    signed.
 *  - APPROVAL IS ALWAYS CONFIRMED. [confirmApproval] must return true before
 *    anything is decided, and the production wiring asks the user directly,
 *    bypassing the agent's generic gate (see `BrowserAgentController`): an
 *    approval can never ride the YOLO / local-model shortcuts. The default when
 *    no confirmation is wired is DENY.
 *  - APPROVAL SETTLES THROUGH THE ENGINE'S OWN ENTRY POINT:
 *    [AgentWalletAccess.decideDappRequest] is the same call the wallet's
 *    interactive confirmation sheet makes, so the same validation and the same
 *    page outcome apply. The tool never grants a permission or signs anything
 *    itself.
 *  - SWITCHING CANNOT INVENT A NETWORK. [switchNetwork] only accepts an id
 *    already in [AgentWalletAccess.networks] and enabled, and still lets the
 *    engine re-validate it; adding a network is not something these tools can
 *    do.
 *
 * IRREVERSIBLE: approving [DappRequest.SendTransaction] broadcasts a
 * transaction (funds move), and approving [DappRequest.SignMessage] /
 * [DappRequest.SignTypedData] produces a signature the site can use and that
 * cannot be recalled. [switchNetwork] moves no funds and is reversible.
 */
class AgentWalletTools(
    private val wallet: () -> AgentWalletAccess,
    private val confirmApproval: suspend (String) -> Boolean = { false }
) {

    /** Request ids this turn has shown the user; approval requires membership. */
    private val surfaced = mutableSetOf<String>()

    /** Accounts, lock state, active networks and known networks. */
    fun readState(): ToolResult {
        val w = wallet()
        val text = buildString {
            appendLine("lock_state: ${w.lockState.name}")
            appendLine("accounts:")
            if (w.accounts.isEmpty()) {
                appendLine("  (none)")
            } else {
                w.accounts.forEach { appendLine("  - ${it.chainType.displayName} · ${it.label} · ${it.address}") }
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
     * Lists the pending dApp requests with full detail and records them as
     * surfaced, which is what makes a later approval possible.
     */
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

    /**
     * Approves one pending request after the user confirms it. Refuses when the
     * request was never listed, when it is no longer pending, or when the user
     * does not approve.
     */
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
        // The user sees the SAME detail the model saw. A prompt the user cannot
        // audit is not a confirmation.
        if (!confirmApproval("Approve wallet request:\n${WalletRequestFormat.detail(request)}")) {
            return ToolResult(false, "the user did not approve this wallet request")
        }
        // The engine's own decision entry point, shared with the interactive
        // sheet — never a direct permission grant or a bypass around it.
        wallet().decideDappRequest(DappDecision(requestId = id, approved = true))
        return ToolResult(
            true,
            "Approved ${WalletRequestFormat.summary(request)}. The wallet is processing it. " +
                "Approving a transaction broadcasts it and approving a signature makes that " +
                "signature usable by the site — neither can be undone."
        )
    }

    /**
     * Rejects one pending request. No confirmation and no prior listing: the
     * safe direction must never be hard to take.
     */
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

    /**
     * Switches the active network for a chain the wallet already knows and has
     * enabled, after the user confirms. Unknown or disabled ids are refused,
     * and the engine re-validates the switch.
     */
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
            // setActiveNetwork re-checks existence and chain, so a guessed id
            // cannot select a network the wallet does not have.
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
}

/**
 * Renders a [DappRequest] for the model and the user. Pure: no engine, no
 * Android, so the wording of an approval prompt is testable on the JVM.
 *
 * The transaction fields matter more than anything else here: `to`, `value`
 * and `data` are what a person has to read to know where funds go, and a
 * signature's message is what they are actually authorising. Everything is
 * clipped rather than truncated silently, and nothing is summarised away.
 */
object WalletRequestFormat {

    const val MAX_FIELD_CHARS = 400
    const val MAX_RAW_CHARS = 1600

    /** The request kind, in the wallet's own vocabulary. */
    fun methodName(request: DappRequest): String = when (request) {
        is DappRequest.Connect -> "connect"
        is DappRequest.SignMessage -> "sign_message"
        is DappRequest.SignTypedData -> "sign_typed_data"
        is DappRequest.SendTransaction -> "send_transaction"
        is DappRequest.SwitchChain -> "switch_chain"
        is DappRequest.AddChain -> "add_chain"
    }

    /** One line, for a result sentence (not for a decision). */
    fun summary(request: DappRequest): String =
        "${methodName(request)} from ${request.host} on ${request.chainType.displayName}"

    /** The full detail shown both to the model and to the confirming user. */
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
     * `to`/`value`/`data` when the payload carries them (EVM), otherwise the
     * raw chain-specific payload clipped — a Solana/Aptos/Cosmos transaction
     * has no `to`/`value` pair to show, and hiding it behind "contract call"
     * would be exactly the blindness this is here to prevent.
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
