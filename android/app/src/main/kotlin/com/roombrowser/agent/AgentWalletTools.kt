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
 * Narrow slice of [WalletEngineApi] so the tools are JVM-testable and usable
 * without the chat UI. Reads are synchronous (StateFlow); mutations suspend.
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
 * Wallet tools for the dApp request queue and the active network.
 *
 * Reject is never gated. Approve requires the request to have been listed this
 * turn AND the user's explicit confirmation, then settles through the engine's
 * own [AgentWalletAccess.decideDappRequest]. Switching accepts only a known,
 * enabled network; a new network cannot be added here.
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
