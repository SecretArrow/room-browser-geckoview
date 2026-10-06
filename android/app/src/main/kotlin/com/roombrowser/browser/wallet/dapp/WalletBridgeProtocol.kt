package com.roombrowser.browser.wallet.dapp

import com.roombrowser.browser.wallet.DappRequest
import com.roombrowser.browser.wallet.WalletBridgeError
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.domain.wallet.model.NetworkConfig
import com.roombrowser.domain.wallet.model.WalletException
import com.roombrowser.domain.wallet.rpc.RpcEndpointChain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * A parsed page-originated bridge call: kind "request" (wallet-level, may
 * prompt) or kind "rpc" (read-only relay to the active network).
 */
sealed interface BridgeCall {
    /** Opaque page-generated correlation id (echoed in every response). */
    val id: String
    val chainType: ChainType
    val method: String
    /** Raw params element as the page sent it (JsonNull when absent). */
    val params: JsonElement
}

/** kind "request" — a wallet-level call that may require user confirmation. */
data class WalletDappCall(
    override val id: String,
    override val chainType: ChainType,
    override val method: String,
    override val params: JsonElement
) : BridgeCall

/** kind "rpc" — a read-only EVM call relayed to the chain's active network. */
data class WalletRpcCall(
    override val id: String,
    override val chainType: ChainType,
    override val method: String,
    override val params: JsonElement
) : BridgeCall

/** [WalletBridgeProtocol.parseRequest] outcome: a typed call or a typed error (never an exception). */
sealed interface BridgeParseResult {
    data class Ok(val call: BridgeCall) : BridgeParseResult
    data class Invalid(val pageId: String?, val error: WalletBridgeError) : BridgeParseResult
}

/** [WalletBridgeProtocol.buildDappRequest] outcome. */
sealed interface DappBuildResult {
    data class Ok(val request: DappRequest) : DappBuildResult
    data class Invalid(val error: WalletBridgeError) : DappBuildResult
}

/** [WalletBridgeProtocol.decodeMessageParam] outcome. */
sealed interface MessageDecodeResult {
    /** [raw] is the message as the dApp sent it (hex string or decoded UTF-8); [display] is for the sheet. */
    data class Ok(val raw: String, val display: String) : MessageDecodeResult
    data class Invalid(val error: WalletBridgeError) : MessageDecodeResult
}

/**
 * Pure-Kotlin codec for the dApp wallet bridge — everything that crosses
 * the WebView boundary in either direction is built or parsed HERE, with
 * kotlinx.serialization only (never naive string interpolation of page
 * data). JVM-testable; no Android imports.
 *
 * WIRE PROTOCOL (page -> native, one string argument of
 * `window.RoomWallet.request`):
 * `{"id": "…", "kind": "request"|"rpc", "chain": "EVM"|…, "method": "…",
 *   "params": <any JSON>}`
 *
 * WIRE PROTOCOL (native -> page, via `evaluateJavascript` of
 * [encodeResponseScript]): `window.__roomWalletResponse(id, resultJson,
 * errorCode, errorMessage)` where `resultJson` is a RAW JSON VALUE string
 * ("\"0x1\"", "[\"0x…\"]", "null") and errorCode 0 means success. Native
 * pushes use [encodeEmitScript] (`window.__roomWalletEmit`) for
 * `accountsChanged`/`chainChanged`.
 *
 * MESSAGE ENCODING CONVENTION (both sides implement it): a
 * `personal_sign`/`signMessage` payload that is a 0x-hex string passes
 * through verbatim; the injected script encodes EVERY other message
 * payload (UTF-8 text or binary) to its raw bytes and wraps it as
 * `{"__roomB64": "<base64>"}` — plain text would be ambiguous or mangled
 * across the JSON boundary (surrogates, emoji, control bytes). The codec
 * decodes that wrapper symmetrically; a bare non-hex string (a page
 * calling the interface directly, bypassing the script) is treated as
 * UTF-8 text verbatim.
 *
 * SECURITY: the codec never trusts the page — hosts/origins arrive only
 * via the bridge's WebView-verified parameters, params are validated into
 * typed errors (never exceptions), and all script-building goes through
 * [jsStringLiteral] (JSON quoting incl. \u2028/\u2029 — mirroring the
 * vault bridge's escaping rules).
 */
object WalletBridgeProtocol {

    // ------------------------------------------------------------------
    // Method names (single source of truth for bridge + script + tests)
    // ------------------------------------------------------------------

    /** EVM: silent account read (no prompt; [] while not permitted). */
    const val METHOD_ETH_ACCOUNTS = "eth_accounts"

    /** EVM connect (prompts unless already permitted). */
    const val METHOD_ETH_REQUEST_ACCOUNTS = "eth_requestAccounts"

    /** Non-EVM connect (solana/aptos/sui/tron). */
    const val METHOD_CONNECT = "connect"

    /** Sui connect alias. */
    const val METHOD_REQUEST_ACCOUNTS = "requestAccounts"

    /** TronLink connect method, normalized to [METHOD_CONNECT] by the script. */
    const val METHOD_TRON_REQUEST_ACCOUNTS = "tron_requestAccounts"

    /** EIP-191 message signature. */
    const val METHOD_PERSONAL_SIGN = "personal_sign"

    /** Non-EVM message signature (solana/aptos/sui). */
    const val METHOD_SIGN_MESSAGE = "signMessage"

    /** EIP-712 typed-data signature (v4). */
    const val METHOD_SIGN_TYPED_DATA_V4 = "eth_signTypedData_v4"

    /** EIP-712 typed-data signature (v3 — same signer, older payload shape). */
    const val METHOD_SIGN_TYPED_DATA_V3 = "eth_signTypedData_v3"

    /** Legacy EIP-712 alias. */
    const val METHOD_SIGN_TYPED_DATA = "eth_signTypedData"

    /** EVM transaction submission. */
    const val METHOD_SEND_TRANSACTION = "eth_sendTransaction"

    /** EIP-3326 chain switch. */
    const val METHOD_SWITCH_CHAIN = "wallet_switchEthereumChain"

    /** EIP-3085 chain add. */
    const val METHOD_ADD_CHAIN = "wallet_addEthereumChain"

    /** Solana sign + broadcast. */
    const val METHOD_SIGN_AND_SEND_TRANSACTION = "signAndSendTransaction"

    /** Aptos sign + submit. */
    const val METHOD_SIGN_AND_SUBMIT_TRANSACTION = "signAndSubmitTransaction"

    /** Sui sign + execute. */
    const val METHOD_SIGN_AND_EXECUTE_TX_BLOCK = "signAndExecuteTransactionBlock"

    /** Sui wallet-standard alias for [METHOD_SIGN_AND_EXECUTE_TX_BLOCK]. */
    const val METHOD_SIGN_AND_EXECUTE_TX = "signAndExecuteTransaction"

    /** Sui wallet-standard personal-message name (the shim's own name is [METHOD_SIGN_MESSAGE]). */
    const val METHOD_SIGN_PERSONAL_MESSAGE = "signPersonalMessage"

    /** Sign WITHOUT broadcasting — Aptos serialized tx, TRON transaction object. */
    const val METHOD_SIGN_TRANSACTION = "signTransaction"

    /** Cosmos (Keplr) connect. */
    const val METHOD_COSMOS_ENABLE = "enable"

    /** Cosmos (Keplr) key read — permitted hosts only, never a prompt. */
    const val METHOD_COSMOS_GET_KEY = "getKey"

    /** Cosmos SIGN_MODE_LEGACY_AMINO_JSON signature (sign-only). */
    const val METHOD_COSMOS_SIGN_AMINO = "signAmino"

    /** Cosmos SIGN_MODE_DIRECT signature (sign-only). */
    const val METHOD_COSMOS_SIGN_DIRECT = "signDirect"

    /** Cosmos off-chain message signature. */
    const val METHOD_COSMOS_SIGN_ARBITRARY = "signArbitrary"

    /** dApp-initiated disconnect: revokes the host's permission for the chain. */
    const val METHOD_DISCONNECT = "disconnect"

    /** Methods that mean "connect this host" for [call.chainType]. */
    val CONNECT_METHODS: Set<String> = setOf(
        METHOD_ETH_REQUEST_ACCOUNTS,
        METHOD_CONNECT,
        METHOD_REQUEST_ACCOUNTS,
        METHOD_TRON_REQUEST_ACCOUNTS,
        METHOD_COSMOS_ENABLE
    )

    /**
     * Methods the BRIDGE answers itself (permission bookkeeping and the
     * permitted-account read) — they never reach [buildDappRequest], so they
     * are routable even though no builder branch names them.
     */
    val BRIDGE_LOCAL_METHODS: Set<String> = setOf(
        METHOD_ETH_ACCOUNTS,
        METHOD_COSMOS_GET_KEY,
        METHOD_DISCONNECT
    )

    /**
     * The canonical method set recorded with a Connect permission. This is
     * the SINGLE source of truth for "what this wallet advertises" — the
     * engine grants exactly this list, so every advertised method must
     * either have a [buildDappRequest] branch or be bridge-local
     * ([BRIDGE_LOCAL_METHODS]); advertising anything else would hand the
     * dApp a promise the wallet cannot keep. `WalletBridgeProtocolTest`
     * enforces that invariant for every chain.
     *
     * Deliberately absent (the adapters can do it, this wallet does not
     * route it yet): `eth_signTransaction` (sign-without-broadcast),
     * Solana/Sui `signTransaction`, Bitcoin `signTransaction` — the engine
     * has no sign-only path for those chains.
     */
    fun grantedMethodsFor(chainType: ChainType): List<String> = when (chainType) {
        ChainType.EVM -> listOf(
            METHOD_ETH_ACCOUNTS, METHOD_ETH_REQUEST_ACCOUNTS, METHOD_PERSONAL_SIGN,
            METHOD_SIGN_TYPED_DATA_V3, METHOD_SIGN_TYPED_DATA_V4,
            METHOD_SEND_TRANSACTION, METHOD_SWITCH_CHAIN, METHOD_ADD_CHAIN
        )
        ChainType.SOLANA -> listOf(
            METHOD_CONNECT, METHOD_SIGN_MESSAGE, METHOD_SIGN_AND_SEND_TRANSACTION,
            METHOD_DISCONNECT
        )
        ChainType.APTOS -> listOf(
            METHOD_CONNECT, METHOD_SIGN_MESSAGE, METHOD_SIGN_TRANSACTION,
            METHOD_SIGN_AND_SUBMIT_TRANSACTION
        )
        ChainType.SUI -> listOf(
            METHOD_CONNECT, METHOD_SIGN_MESSAGE, METHOD_SIGN_PERSONAL_MESSAGE,
            METHOD_SIGN_AND_EXECUTE_TX_BLOCK, METHOD_SIGN_AND_EXECUTE_TX
        )
        ChainType.COSMOS -> listOf(
            METHOD_COSMOS_ENABLE, METHOD_COSMOS_GET_KEY, METHOD_COSMOS_SIGN_ARBITRARY,
            METHOD_COSMOS_SIGN_AMINO, METHOD_COSMOS_SIGN_DIRECT
        )
        ChainType.BITCOIN -> listOf(METHOD_CONNECT, METHOD_SIGN_MESSAGE)
        ChainType.TRON -> listOf(
            METHOD_CONNECT, METHOD_SIGN_MESSAGE, METHOD_SIGN_TRANSACTION
        )
        // Deliberately `connect` alone: no published provider API for Octra
        // exists to route against, so this advertises only what every chain
        // already answers and promises nothing Octra-specific.
        ChainType.OCTRA -> listOf(METHOD_CONNECT)
    }

    /** JSON-RPC server-error-range code used when a flood is shed (never a 4xxx user code). */
    const val CODE_RATE_LIMITED = -32005

    /** Confirmation-sheet truncation limit for message display. */
    const val DISPLAY_MAX_CHARS = 500

    /**
     * The permission key the bridge queries [com.roombrowser.browser.wallet.WalletEngineApi.isDappPermitted]
     * with for a silent connect auto-approve: EVM dApps grant per
     * `eth_requestAccounts`, Cosmos per Keplr's `enable`, the other chain
     * families per `connect`.
     */
    fun permissionMethodFor(chainType: ChainType): String = when (chainType) {
        ChainType.EVM -> METHOD_ETH_REQUEST_ACCOUNTS
        ChainType.COSMOS -> METHOD_COSMOS_ENABLE
        else -> METHOD_CONNECT
    }

    private val json = Json {
        ignoreUnknownKeys = true
    }

    // ------------------------------------------------------------------
    // Parsing (page -> native)
    // ------------------------------------------------------------------

    /**
     * Parses a page envelope into a typed [BridgeCall]. NEVER throws:
     * malformed JSON, a non-object payload, a missing/blank id, an unknown
     * chain, a bad kind or a blank method all come back as
     * [BridgeParseResult.Invalid] with the page id preserved when it could
     * be read (so the page's promise does not hang).
     */
    fun parseRequest(payload: String): BridgeParseResult = try {
        val root = json.parseToJsonElement(payload)
        val obj = root as? JsonObject
            ?: return BridgeParseResult.Invalid(
                null,
                WalletBridgeError(WalletBridgeError.INVALID_PARAMS, "Payload must be a JSON object")
            )
        val pageId = obj.str("id")
            ?: return BridgeParseResult.Invalid(
                null,
                WalletBridgeError(WalletBridgeError.INVALID_PARAMS, "Missing request id")
            )
        val invalid = { message: String ->
            BridgeParseResult.Invalid(pageId, WalletBridgeError(WalletBridgeError.INVALID_PARAMS, message))
        }
        val chainName = obj.str("chain") ?: return invalid("Missing chain")
        val chainType = ChainType.fromName(chainName)
            ?: return invalid("Unknown chain")
        val kind = obj.str("kind") ?: return invalid("Missing kind")
        val method = obj.str("method")?.takeIf { it.isNotBlank() } ?: return invalid("Blank method")
        val params = obj["params"] ?: JsonNull
        when (kind) {
            "request" -> BridgeParseResult.Ok(WalletDappCall(pageId, chainType, method, params))
            "rpc" -> BridgeParseResult.Ok(WalletRpcCall(pageId, chainType, method, params))
            else -> invalid("Unknown kind")
        }
    } catch (e: Exception) {
        BridgeParseResult.Invalid(
            null,
            WalletBridgeError(WalletBridgeError.INVALID_PARAMS, "Malformed payload")
        )
    }

    // ------------------------------------------------------------------
    // Read-only RPC relay
    // ------------------------------------------------------------------

    /**
     * The EVM methods that may be relayed without any approval (reads only).
     *
     * This is the whole standard read surface a dApp uses, not a curated
     * handful. It used to hold seven methods, and every other read fell
     * through to [buildDappRequest] — which has no branch for a read and
     * answers 4200 Unsupported Method. That is not a harmless refusal: a
     * wagmi app reads the block number (`useBlockNumber`, which watches by
     * default), the nonce before it builds a transaction
     * (`eth_getTransactionCount`), the fee market (`eth_feeHistory`, inside
     * `estimateFeesPerGas`) and contract code (`eth_getCode`) as a matter of
     * course, and a wallet that calls those unsupported is a wallet the app
     * reports as failing.
     *
     * Nothing here can change state, spend, or sign — every entry is a pure
     * read of the chain, which is exactly why no prompt is needed.
     *
     * Kept IDENTICAL to the injected script's READONLY_METHODS on purpose:
     * the script picks the transport kind from its own copy, so a method
     * listed there and missing here would arrive as a relay this refuses.
     * `WalletBridgeProtocolTest` asserts the two sets are equal, because
     * nothing else can — the JS list is a string literal.
     */
    val READONLY_RPC_METHODS: Set<String> = setOf(
        // Chain and node identity.
        "eth_chainId", "net_version", "eth_blockNumber", "eth_syncing",
        "web3_clientVersion", "net_listening", "net_peerCount",
        // Account and contract state.
        "eth_getBalance", "eth_getCode", "eth_getStorageAt",
        "eth_getTransactionCount", "eth_getProof",
        // Blocks and their uncles.
        "eth_getBlockByNumber", "eth_getBlockByHash",
        "eth_getBlockTransactionCountByNumber", "eth_getBlockTransactionCountByHash",
        "eth_getUncleCountByBlockNumber", "eth_getUncleCountByBlockHash",
        "eth_getUncleByBlockNumberAndIndex", "eth_getUncleByBlockHashAndIndex",
        // Transactions and logs.
        "eth_getTransactionByHash", "eth_getTransactionReceipt",
        "eth_getTransactionByBlockNumberAndIndex", "eth_getTransactionByBlockHashAndIndex",
        "eth_getLogs",
        // Fees, simulation and pure computation.
        "eth_gasPrice", "eth_maxPriorityFeePerGas", "eth_feeHistory",
        "eth_call", "eth_estimateGas", "web3_sha3"
    )

    /** True when [method] is an allowlisted read-only EVM call. */
    fun isReadonlyRpcMethod(method: String): Boolean = method in READONLY_RPC_METHODS

    /**
     * The answer to a chain-IDENTITY call, or null when [method] is not one.
     *
     * These two are answered from the wallet's own configuration instead of
     * being relayed, and that is a correctness fix rather than an
     * optimisation. `eth_chainId` is what a dApp calls immediately after
     * `eth_requestAccounts`, and wagmi-based apps (Uniswap among them) treat
     * a failure there as the connect itself failing — the user sees
     * "Error connecting" and "Try again", which retries the same doomed
     * round trip forever.
     *
     * A relayed `eth_chainId` fails whenever the active network's RPC is
     * unreachable: rate-limited, blocked on the user's network, or simply
     * down. None of that has anything to do with which chain this wallet
     * signs for, and a wallet whose own chain id depends on a third-party
     * endpoint is a wallet that cannot be connected to while that endpoint
     * is blocked. The chain is a local fact, so it is answered locally.
     */
    fun localChainAnswer(method: String, network: NetworkConfig?): String? {
        val chainId = network?.chainId?.toLongOrNull() ?: return null
        return when (method) {
            "eth_chainId" -> JsonPrimitive("0x" + chainId.toString(16)).toString()
            "net_version" -> JsonPrimitive(chainId.toString()).toString()
            else -> null
        }
    }

    /**
     * RPC relay params as a positional list (what JSON-RPC expects).
     * An array becomes its elements, a missing/null params becomes an empty
     * list; any other shape (a page calling the interface directly with a
     * bare object) is rejected as null.
     */
    fun rpcParamsList(call: WalletRpcCall): List<JsonElement>? = when (val p = call.params) {
        is JsonArray -> p.toList()
        is JsonNull -> emptyList()
        else -> null
    }

    /**
     * Maps a [JsonRpcClient] failure to the EIP-1193-style error the page
     * sees: transport failures are CHAIN_DISCONNECTED (4901), provider
     * errors keep their code and message, bad endpoints are INVALID_PARAMS.
     *
     * A rejected certificate is a transport failure from the page's point of
     * view — every endpoint for this chain has been tried by the time one
     * surfaces — so it reads as CHAIN_DISCONNECTED rather than INTERNAL. The
     * page cannot act on the distinction, and 4901 is the code wallets are
     * expected to send for "cannot reach the chain right now".
     */
    fun relayError(e: WalletException): WalletBridgeError = when (e) {
        is WalletException.NetworkUnavailable ->
            WalletBridgeError(WalletBridgeError.CHAIN_DISCONNECTED, "Chain disconnected")
        is WalletException.TlsFailure ->
            WalletBridgeError(WalletBridgeError.CHAIN_DISCONNECTED, "Chain disconnected")
        is WalletException.RpcError ->
            WalletBridgeError(e.code, e.message?.takeIf { it.isNotBlank() } ?: "RPC error")
        is WalletException.InvalidParams ->
            WalletBridgeError(WalletBridgeError.INVALID_PARAMS, e.message?.takeIf { it.isNotBlank() } ?: "Invalid params")
        else -> WalletBridgeError(WalletBridgeError.INTERNAL, "RPC relay failed")
    }

    /**
     * Whether a failed read should move on to the network's next endpoint.
     *
     * The rule is [RpcEndpointChain.isEndpointFailure] and deliberately not a
     * local one: the bridge used to continue only on [WalletException.NetworkUnavailable],
     * so a primary answering HTTP 429 or 525 — or one whose certificate the
     * device rejected — ended the walk while a working endpoint sat next to it
     * in the same list. That is the failure every adapter already fails over
     * on, and a dApp read that hits it reports as a failed wallet connection.
     */
    fun shouldTryNextEndpoint(e: WalletException): Boolean = RpcEndpointChain.isEndpointFailure(e)

    // ------------------------------------------------------------------
    // Message decoding (personal_sign / signMessage payloads)
    // ------------------------------------------------------------------

    /**
     * Decodes one message param per the base64 convention documented on
     * this object. [MessageDecodeResult.Ok.raw] is the message as the dApp
     * sent it (hex string, or the decoded UTF-8 text; the base64 literal
     * itself for binary payloads that are not valid UTF-8);
     * [MessageDecodeResult.Ok.display] is the best-effort human-readable
     * form (hex is decoded, binary falls back to a size marker).
     */
    fun decodeMessageParam(element: JsonElement): MessageDecodeResult {
        when (element) {
            is JsonPrimitive -> {
                if (!element.isString) {
                    return MessageDecodeResult.Invalid(
                        WalletBridgeError(WalletBridgeError.INVALID_PARAMS, "Message must be a string")
                    )
                }
                val text = element.content
                if (isHexMessage(text)) {
                    val bytes = hexToBytes(text)
                    val decoded = bytes?.let { String(it, Charsets.UTF_8) }
                    val display = if (decoded != null && !decoded.contains('�')) decoded else text
                    return MessageDecodeResult.Ok(text, display)
                }
                return MessageDecodeResult.Ok(text, text)
            }
            is JsonObject -> {
                val wrapped = (element["__roomB64"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: return MessageDecodeResult.Invalid(
                        WalletBridgeError(WalletBridgeError.INVALID_PARAMS, "Unsupported message payload")
                    )
                val bytes = try {
                    Base64.getDecoder().decode(wrapped)
                } catch (e: IllegalArgumentException) {
                    null
                } ?: return MessageDecodeResult.Invalid(
                    WalletBridgeError(WalletBridgeError.INVALID_PARAMS, "Malformed base64 message")
                )
                val decoded = String(bytes, Charsets.UTF_8)
                return if (decoded.contains('�')) {
                    MessageDecodeResult.Ok(wrapped, "<binary, " + bytes.size + " bytes>")
                } else {
                    MessageDecodeResult.Ok(decoded, decoded)
                }
            }
            else -> return MessageDecodeResult.Invalid(
                WalletBridgeError(WalletBridgeError.INVALID_PARAMS, "Unsupported message payload")
            )
        }
    }

    /** Confirmation-sheet rendering: at most [maxChars] plus an ellipsis marker. */
    fun truncateForDisplay(text: String, maxChars: Int = DISPLAY_MAX_CHARS): String =
        if (text.length <= maxChars) text else text.take(maxChars) + "…"

    // ------------------------------------------------------------------
    // DappRequest building (page call + VERIFIED host/origin -> contract)
    // ------------------------------------------------------------------

    /**
     * Builds the contract [DappRequest] for a wallet-level call. The host
     * and origin are supplied by the BRIDGE from the WebView's own URL —
     * the page's claimed origin is never an input here. [activeNetworkId]
     * is the bridge-resolved active network id for the call's chain (null
     * = none configured); [fallbackAddress] is the profile's primary
     * account for the chain, used when the method's params carry no
     * address (solana/aptos/sui sign, send-transaction without `from`).
     */
    fun buildDappRequest(
        call: WalletDappCall,
        dappId: String,
        host: String,
        originUrl: String,
        activeNetworkId: String?,
        fallbackAddress: String?
    ): DappBuildResult {
        val method = call.method
        val params = call.params

        // -- connect ----------------------------------------------------
        if (method in CONNECT_METHODS) {
            return DappBuildResult.Ok(DappRequest.Connect(dappId, host, call.chainType, originUrl))
        }

        // -- signatures -------------------------------------------------
        if (method == METHOD_PERSONAL_SIGN) {
            if (call.chainType != ChainType.EVM) return invalidParams("personal_sign is EVM-only")
            val pair = personalSignParams(params) ?: return invalidParams(
                "personal_sign expects params [message, address] or {message, address}"
            )
            when (val decoded = decodeMessageParam(pair.first)) {
                is MessageDecodeResult.Invalid -> return DappBuildResult.Invalid(decoded.error)
                is MessageDecodeResult.Ok -> return DappBuildResult.Ok(
                    DappRequest.SignMessage(
                        dappId, host, call.chainType, pair.second,
                        decoded.raw, truncateForDisplay(decoded.display)
                    )
                )
            }
        }

        if (method == METHOD_SIGN_MESSAGE || method == METHOD_COSMOS_SIGN_ARBITRARY ||
            method == METHOD_SIGN_PERSONAL_MESSAGE
        ) {
            val isSuiAlias = method == METHOD_SIGN_PERSONAL_MESSAGE
            if (call.chainType == ChainType.EVM) return invalidParams("signMessage is not an EVM method")
            if (isSuiAlias && call.chainType != ChainType.SUI) {
                return invalidParams("signPersonalMessage is Sui-only")
            }
            if (method == METHOD_COSMOS_SIGN_ARBITRARY && call.chainType != ChainType.COSMOS) {
                return invalidParams("signArbitrary is Cosmos-only")
            }
            val obj = params as? JsonObject ?: return invalidParams("signMessage expects a params object")
            val messageElement = obj["message"] ?: return invalidParams("signMessage is missing a message")
            when (val decoded = decodeMessageParam(messageElement)) {
                is MessageDecodeResult.Invalid -> return DappBuildResult.Invalid(decoded.error)
                is MessageDecodeResult.Ok -> {
                    val account = fallbackAddress?.takeIf { it.isNotBlank() }
                        ?: return DappBuildResult.Invalid(
                            WalletBridgeError(WalletBridgeError.UNAUTHORIZED, "No " + call.chainType.displayName + " account available")
                        )
                    return DappBuildResult.Ok(
                        DappRequest.SignMessage(
                            dappId, host, call.chainType, account,
                            decoded.raw, truncateForDisplay(decoded.display)
                        )
                    )
                }
            }
        }

        if (method == METHOD_SIGN_TYPED_DATA_V4 || method == METHOD_SIGN_TYPED_DATA_V3 ||
            method == METHOD_SIGN_TYPED_DATA
        ) {
            if (call.chainType != ChainType.EVM) return invalidParams("typed data is EVM-only")
            val pair = typedDataParams(params)
                ?: return invalidParams("typed-data signing expects params [address, typedData]")
            return DappBuildResult.Ok(
                DappRequest.SignTypedData(dappId, host, call.chainType, pair.first, pair.second)
            )
        }

        // -- transactions -------------------------------------------------
        if (method == METHOD_SEND_TRANSACTION) {
            if (call.chainType != ChainType.EVM) return invalidParams("eth_sendTransaction is EVM-only")
            val tx = (params as? JsonArray)?.firstOrNull() as? JsonObject
                ?: return invalidParams("eth_sendTransaction expects params [transaction]")
            val networkId = activeNetworkId
                ?: return DappBuildResult.Invalid(
                    WalletBridgeError(WalletBridgeError.CHAIN_DISCONNECTED, "No active EVM network")
                )
            val declaredFrom = tx.str("from")?.takeIf { it.isNotBlank() }
            val from = declaredFrom ?: fallbackAddress?.takeIf { it.isNotBlank() }
                ?: return DappBuildResult.Invalid(
                    WalletBridgeError(WalletBridgeError.UNAUTHORIZED, "No account available for this transaction")
                )
            val txJson = if (declaredFrom == null) {
                buildJsonObject {
                    tx.forEach { (key, value) -> put(key, value) }
                    put("from", from)
                }.toString()
            } else {
                tx.toString()
            }
            return DappBuildResult.Ok(
                DappRequest.SendTransaction(dappId, host, call.chainType, networkId, from, txJson, null)
            )
        }

        if (method == METHOD_SIGN_AND_SEND_TRANSACTION) {
            if (call.chainType != ChainType.SOLANA) return invalidParams("signAndSendTransaction is Solana-only")
            return buildChainSendTransaction(call, dappId, host, params, activeNetworkId, fallbackAddress)
        }

        if (method == METHOD_SIGN_AND_SUBMIT_TRANSACTION) {
            if (call.chainType != ChainType.APTOS) return invalidParams("signAndSubmitTransaction is Aptos-only")
            return buildChainSendTransaction(call, dappId, host, params, activeNetworkId, fallbackAddress)
        }

        if (method == METHOD_SIGN_AND_EXECUTE_TX_BLOCK || method == METHOD_SIGN_AND_EXECUTE_TX) {
            if (call.chainType != ChainType.SUI) return invalidParams("signAndExecuteTransactionBlock is Sui-only")
            return buildChainSendTransaction(call, dappId, host, params, activeNetworkId, fallbackAddress)
        }

        // -- sign WITHOUT broadcasting -------------------------------------
        if (method == METHOD_SIGN_TRANSACTION) {
            return when (call.chainType) {
                // TronLink hands over the raw transaction object; the engine
                // signs it and BROADCASTS (its TRON dispatch has no sign-only
                // path), so the returned result is the txid.
                ChainType.TRON -> {
                    val tx = params as? JsonObject
                        ?: return invalidParams("signTransaction expects a transaction object")
                    buildChainSendTransaction(call, dappId, host, tx, activeNetworkId, fallbackAddress)
                }
                // Aptos `signTransaction`: the engine's Aptos dispatch signs a
                // serialized tx (`txBytes`) and returns the signature WITHOUT
                // submitting it.
                ChainType.APTOS -> {
                    val obj = params as? JsonObject
                        ?: return invalidParams("signTransaction expects a params object")
                    if (obj["txBytes"] == null) {
                        return invalidParams("signTransaction expects {txBytes} for Aptos")
                    }
                    buildChainSendTransaction(call, dappId, host, obj, activeNetworkId, fallbackAddress)
                }
                else -> invalidParams("signTransaction is not supported for this chain")
            }
        }

        // -- Cosmos (Keplr) sign-only --------------------------------------
        if (method == METHOD_COSMOS_SIGN_DIRECT) {
            if (call.chainType != ChainType.COSMOS) return invalidParams("signDirect is Cosmos-only")
            val obj = params as? JsonObject ?: return invalidParams("signDirect expects a params object")
            val bodyBytes = base64Param(obj["bodyBytes"])
                ?: return invalidParams("signDirect is missing bodyBytes")
            val authInfoBytes = base64Param(obj["authInfoBytes"])
                ?: return invalidParams("signDirect is missing authInfoBytes")
            val chainId = obj.str("chainId")?.takeIf { it.isNotBlank() }
                ?: return invalidParams("signDirect is missing chainId")
            val txJson = buildJsonObject {
                put("signOnly", true)
                put("bodyBytes", bodyBytes)
                put("authInfoBytes", authInfoBytes)
                put("chainId", chainId)
                put("accountNumber", obj.str("accountNumber") ?: "0")
            }.toString()
            return buildCosmosSignOnly(call, dappId, host, obj, activeNetworkId, fallbackAddress, txJson)
        }

        if (method == METHOD_COSMOS_SIGN_AMINO) {
            if (call.chainType != ChainType.COSMOS) return invalidParams("signAmino is Cosmos-only")
            val obj = params as? JsonObject ?: return invalidParams("signAmino expects a params object")
            val signDoc = obj["signDoc"] as? JsonObject
                ?: return invalidParams("signAmino expects a {signDoc} JSON object")
            val txJson = buildJsonObject {
                put("signOnly", true)
                put("aminoSignDoc", signDoc.toString())
            }.toString()
            return buildCosmosSignOnly(call, dappId, host, obj, activeNetworkId, fallbackAddress, txJson)
        }

        // -- networks -----------------------------------------------------
        if (method == METHOD_SWITCH_CHAIN) {
            if (call.chainType != ChainType.EVM) return invalidParams("wallet_switchEthereumChain is EVM-only")
            val obj = singleParamsObject(params)
                ?: return invalidParams("wallet_switchEthereumChain expects params [{chainId}]")
            val rawChainId = obj.str("chainId") ?: return invalidParams("Missing chainId")
            val chainId = parseEvmChainId(rawChainId) ?: return invalidParams("Invalid chainId")
            return DappBuildResult.Ok(
                DappRequest.SwitchChain(dappId, host, call.chainType, "EVM:" + chainId)
            )
        }

        if (method == METHOD_ADD_CHAIN) {
            if (call.chainType != ChainType.EVM) return invalidParams("wallet_addEthereumChain is EVM-only")
            val obj = singleParamsObject(params)
                ?: return invalidParams("wallet_addEthereumChain expects params [{…}]")
            val config = addChainConfig(obj)
                ?: return invalidParams("Invalid wallet_addEthereumChain params")
            return DappBuildResult.Ok(DappRequest.AddChain(dappId, host, call.chainType, config))
        }

        return DappBuildResult.Invalid(
            WalletBridgeError(WalletBridgeError.UNSUPPORTED_METHOD, "Unsupported method: " + method)
        )
    }

    /** Shared builder for the non-EVM sign-and-send family. */
    private fun buildChainSendTransaction(
        call: WalletDappCall,
        dappId: String,
        host: String,
        params: JsonElement,
        activeNetworkId: String?,
        fallbackAddress: String?
    ): DappBuildResult {
        val obj = params as? JsonObject
            ?: return invalidParams(call.method + " expects a params object")
        val networkId = activeNetworkId
            ?: return DappBuildResult.Invalid(
                WalletBridgeError(WalletBridgeError.CHAIN_DISCONNECTED, "No active " + call.chainType.displayName + " network")
            )
        val account = fallbackAddress?.takeIf { it.isNotBlank() }
            ?: return DappBuildResult.Invalid(
                WalletBridgeError(WalletBridgeError.UNAUTHORIZED, "No " + call.chainType.displayName + " account available")
            )
        return DappBuildResult.Ok(
            DappRequest.SendTransaction(dappId, host, call.chainType, networkId, account, obj.toString(), null)
        )
    }

    /**
     * Shared builder for the Cosmos sign-only families (signDirect /
     * signAmino). These still travel as a [DappRequest.SendTransaction]
     * through the SAME confirmation sheet/bio gate as every other signing
     * request — the engine's Cosmos dispatch sees `signOnly` in the params
     * and returns a signature instead of broadcasting.
     *
     * The Keplr signer is honoured when the dApp names one (a sign doc is
     * only valid for the account it was built for); otherwise the profile's
     * primary Cosmos account is used.
     */
    private fun buildCosmosSignOnly(
        call: WalletDappCall,
        dappId: String,
        host: String,
        params: JsonObject,
        activeNetworkId: String?,
        fallbackAddress: String?,
        txJson: String
    ): DappBuildResult {
        val networkId = activeNetworkId
            ?: return DappBuildResult.Invalid(
                WalletBridgeError(WalletBridgeError.CHAIN_DISCONNECTED, "No active Cosmos network")
            )
        val account = params.str("signer")?.takeIf { it.isNotBlank() }
            ?: fallbackAddress?.takeIf { it.isNotBlank() }
            ?: return DappBuildResult.Invalid(
                WalletBridgeError(WalletBridgeError.UNAUTHORIZED, "No Cosmos account available")
            )
        return DappBuildResult.Ok(
            DappRequest.SendTransaction(dappId, host, call.chainType, networkId, account, txJson, null)
        )
    }

    /**
     * A binary sign-doc field (Keplr sends Uint8Array): base64 text passes
     * through, a JSON array of byte values is encoded, anything else is
     * rejected. The page-side provider does the Uint8Array -> base64
     * conversion; the array form is accepted for a page calling the
     * interface directly.
     */
    private fun base64Param(element: JsonElement?): String? {
        return when (element) {
            is JsonPrimitive -> if (element.isString) element.content.takeIf { it.isNotBlank() } else null
            is JsonArray -> {
                val bytes = ByteArray(element.size)
                for ((index, item) in element.withIndex()) {
                    val value = (item as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return null
                    if (value < 0 || value > 255) return null
                    bytes[index] = value.toByte()
                }
                Base64.getEncoder().encodeToString(bytes)
            }
            else -> null
        }
    }

    /** personal_sign params: `[data, address]` or `{message, address}` (extra keys tolerated). */
    private fun personalSignParams(params: JsonElement): Pair<JsonElement, String>? {
        return when (params) {
            is JsonArray -> {
                if (params.size < 2) return null
                val address = (params[1] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.takeIf { it.isNotBlank() } ?: return null
                params[0] to address
            }
            is JsonObject -> {
                val message = params["message"] ?: return null
                val address = params.str("address")?.takeIf { it.isNotBlank() } ?: return null
                message to address
            }
            else -> null
        }
    }

    /** eth_signTypedData params: `[address, typedData]` where typedData is a JSON string or object. */
    private fun typedDataParams(params: JsonElement): Pair<String, String>? {
        if (params !is JsonArray || params.size < 2) return null
        val address = (params[0] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotBlank() } ?: return null
        return when (val data = params[1]) {
            is JsonObject -> address to data.toString()
            is JsonPrimitive -> if (!data.isString) null else {
                val parsed = try {
                    json.parseToJsonElement(data.content)
                } catch (e: Exception) {
                    null
                }
                if (parsed is JsonObject) address to parsed.toString() else null
            }
            else -> null
        }
    }

    /** wallet_switch/addEthereumChain params: `[{…}]` or a bare object (lenient). */
    private fun singleParamsObject(params: JsonElement): JsonObject? = when (params) {
        is JsonArray -> params.firstOrNull() as? JsonObject
        is JsonObject -> params
        else -> null
    }

    /**
     * EIP-3085 params -> [NetworkConfig]. Standalone re-implementation of
     * the ChainlistClient mapping (that helper lives behind a client
     * instance): chainId is 0x-hex (decimal tolerated), rpcUrls are
     * required, nativeCurrency/blockExplorerUrls are optional.
     */
    private fun addChainConfig(params: JsonObject): NetworkConfig? {
        val rawChainId = params.str("chainId") ?: return null
        val chainId = parseEvmChainId(rawChainId) ?: return null
        val rpcUrls = (params["rpcUrls"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val currency = params["nativeCurrency"] as? JsonObject
        val symbol = currency?.str("symbol")?.takeIf { it.isNotBlank() } ?: "ETH"
        val decimals = currency?.get("decimals")?.let { (it as? JsonPrimitive)?.contentOrNull }?.toIntOrNull() ?: 18
        val explorer = (params["blockExplorerUrls"] as? JsonArray)
            ?.firstOrNull()
            ?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
        return NetworkConfig(
            id = "EVM:" + chainId,
            chainType = ChainType.EVM,
            chainId = chainId.toString(),
            name = params.str("chainName")?.takeIf { it.isNotBlank() } ?: ("Chain " + chainId),
            rpcUrls = rpcUrls,
            nativeSymbol = symbol,
            nativeDecimals = decimals,
            explorerUrl = explorer
        )
    }

    /**
     * Parses an EVM chain id: 0x-prefixed values are hexadecimal (the
     * standard), bare digits are decimal (lenient, what some toolkits
     * emit) — "0x1" and "1" both yield 1.
     */
    fun parseEvmChainId(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            if (trimmed.length == 2) return null
            trimmed.substring(2).toLongOrNull(16)
        } else {
            trimmed.toLongOrNull()
        }
    }

    // ------------------------------------------------------------------
    // Result encoding (engine outcomes / relay results -> page script)
    // ------------------------------------------------------------------

    /**
     * The page-visible success value of a connect for [chainType], used by
     * the bridge's silent auto-approve path AND documented as the shape the
     * engine settles prompted Connect outcomes with, so both paths agree:
     * EVM `["0x…"]` (EIP-1193), Solana `{"publicKey":"…"}` (Phantom),
     * Aptos `{"address":"…","publicKey":"…"}`, Sui `["…"]`,
     * Tron `{"address":"…"}`, Bitcoin `{"address":"…","publicKey":"…"}`,
     * Cosmos `{"address":"…","publicKey":"…"}`.
     *
     * The Cosmos object never reaches a page as-is: `window.keplr.enable`
     * rewraps this value into the `[Key]` array Keplr resolves with, and
     * builds each Key from the address and pubKey on it. The key therefore
     * has to ride here — `enable()` is how most Cosmos dApps get their first
     * Key, and a wallet that answers it with a null pubKey is a wallet whose
     * offline signer cannot be constructed.
     *
     * [publicKey] is the account's public key as lowercase hex, and is
     * carried ONLY for the chains whose dApp conventions publish one —
     * Cosmos, Aptos and Bitcoin. Octra's key is withheld: its provider API
     * has no specification, so nothing establishes where a page would read
     * it from, and [`WalletEngineApi.publicKeyOf`] answering for Octra is
     * not a reason to publish it. It stays optional in the payload rather
     * than becoming a null field: a dApp that reads `account.publicKey`
     * gets a string when the key is available and `undefined` when it is
     * not, which is what it would see against a wallet that never had one,
     * and neither is a shape it can mistake for a real key.
     */
    fun connectSuccessResult(
        chainType: ChainType,
        address: String,
        publicKey: String? = null
    ): String = when (chainType) {
        ChainType.EVM, ChainType.SUI -> JsonArray(listOf(JsonPrimitive(address))).toString()
        ChainType.SOLANA -> buildJsonObject { put("publicKey", address) }.toString()
        ChainType.APTOS -> buildJsonObject {
            put("address", address)
            publicKey?.let { put("publicKey", it) }
        }.toString()
        ChainType.BITCOIN -> buildJsonObject {
            put("address", address)
            publicKey?.let { put("publicKey", it) }
        }.toString()
        ChainType.COSMOS -> buildJsonObject {
            put("address", address)
            publicKey?.let { put("publicKey", it) }
        }.toString()
        ChainType.TRON -> buildJsonObject { put("address", address) }.toString()
        ChainType.OCTRA -> buildJsonObject { put("address", address) }.toString()
    }

    /** The page-visible value of `eth_accounts` for a permitted host. */
    fun accountsResult(addresses: List<String>): String =
        JsonArray(addresses.map { JsonPrimitive(it) }).toString()

    /**
     * The page-visible value of Keplr's `getKey` for a permitted host: the
     * `Key` shape Keplr dApps read.
     *
     * `pubKey` is the account's compressed secp256k1 public key as lowercase
     * hex when [publicKey] is supplied, and JSON null when it is not. Keplr
     * itself hands dApps a `Uint8Array` here, so the injected script converts
     * the hex to bytes before the page ever sees it — hex is a transport
     * detail of this bridge, never part of the dApp-facing shape.
     *
     * The key is not secret: it is published on chain with every signature
     * this wallet produces, and Keplr, Leap and every other Cosmos wallet
     * return it from this exact call. It is also load-bearing — a CosmJS
     * client puts the signer's pubkey INSIDE the sign doc it builds, so a
     * wallet that returns null here cannot complete a `signDirect` flow at
     * all. Null is therefore a degraded answer, kept only for the case where
     * the account's key material cannot be read (a locked wallet, an account
     * that has since been removed): the address-only flows still work, and a
     * dApp that needs the key sees it is missing rather than being told a
     * wrong one.
     */
    fun keplrKeyResult(address: String, publicKey: String? = null): String = buildJsonObject {
        put("name", "Room Browser")
        put("algo", "secp256k1")
        if (publicKey.isNullOrBlank()) put("pubKey", JsonNull) else put("pubKey", publicKey)
        put("address", address)
        put("bech32Address", address)
        put("isNanoLedger", false)
        put("isKeystone", false)
    }.toString()

    /**
     * The full `evaluateJavascript` call that settles one page promise:
     * `window.__roomWalletResponse(id, resultJson, errorCode, errorMessage)`.
     * Every string argument goes through [jsStringLiteral]. A success
     * (errorCode 0) whose [resultJson] is blank or not a valid JSON value
     * degrades to an INTERNAL error response instead of handing the page a
     * parse failure.
     */
    fun encodeResponseScript(
        id: String,
        resultJson: String?,
        errorCode: Int,
        errorMessage: String?
    ): String {
        val head = "window.__roomWalletResponse && window.__roomWalletResponse("
        if (errorCode == 0) {
            val value = resultJson?.takeIf { it.isNotBlank() } ?: "null"
            if (!isJsonValue(value)) {
                return encodeResponseScript(
                    id, null, WalletBridgeError.INTERNAL, "Malformed result payload"
                )
            }
            return head + jsStringLiteral(id) + ", " + jsStringLiteral(value) + ", 0, " +
                jsStringLiteral("") + ")"
        }
        return head + jsStringLiteral(id) + ", " + jsStringLiteral("null") + ", " + errorCode +
            ", " + jsStringLiteral(errorMessage ?: "") + ")"
    }

    /**
     * Strict-ish "is this a JSON value" gate. `parseToJsonElement` alone is
     * too lenient for validation (it accepts unquoted single-token literals
     * like `abc` or `0x1-broken`), so structural parses are only trusted for
     * quote/brace/bracket-prefixed text; digit-prefixed text must be a real
     * JSON number; everything else must be exactly `true`/`false`/`null`.
     * The page's own JSON.parse remains the final guard regardless.
     */
    private fun isJsonValue(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        return when (t[0]) {
            '"', '{', '[' -> try {
                json.parseToJsonElement(t)
                true
            } catch (e: Exception) {
                false
            }
            in '0'..'9', '-' -> t.toDoubleOrNull() != null
            else -> t == "true" || t == "false" || t == "null"
        }
    }

    /**
     * The full `evaluateJavascript` call that pushes an event to the page's
     * providers: `window.__roomWalletEmit(event, payloadJson)` where
     * [payloadJson] is a raw JSON value string.
     */
    fun encodeEmitScript(event: String, payloadJson: String): String =
        "window.__roomWalletEmit && window.__roomWalletEmit(" +
            jsStringLiteral(event) + ", " + jsStringLiteral(payloadJson) + ")"

    /**
     * [value] as a double-quoted JS string literal, using JSON quoting
     * rules plus explicit \u2028/\u2029 escaping — the pure-Kotlin mirror
     * of the vault bridge's `JSONObject.quote`-based literal builder (this
     * object must stay JVM-testable, so org.json is not used). A string
     * built here can never break out of its quotes: every `"` and `\` is
     * escaped and every line/control separator is \u-escaped.
     */
    fun jsStringLiteral(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '/' -> sb.append("\\/")
                '\b' -> sb.append("\\b")
                '\t' -> sb.append("\\t")
                '\n' -> sb.append("\\n")
                '\u000C' -> sb.append("\\f")
                '\r' -> sb.append("\\r")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> if (c.code <= 0x1F || (c.code >= 0x7F && c.code <= 0x9F)) {
                    sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private fun invalidParams(message: String): DappBuildResult =
        DappBuildResult.Invalid(WalletBridgeError(WalletBridgeError.INVALID_PARAMS, message))

    /** Strict string field read (numbers/objects/null are not strings). */
    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** 0x-prefixed, even-length, non-empty hex payload check (mirrors the script). */
    private fun isHexMessage(text: String): Boolean {
        if (text.length < 4 || text.length % 2 != 0) return false
        if (!text.startsWith("0x")) return false
        for (i in 2 until text.length) {
            if (hexDigit(text[i]) == null) return false
        }
        return true
    }

    private fun hexToBytes(hex: String): ByteArray? {
        val body = hex.substring(2)
        val out = ByteArray(body.length / 2)
        for (i in out.indices) {
            val hi = hexDigit(body[i * 2]) ?: return null
            val lo = hexDigit(body[i * 2 + 1]) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hexDigit(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }
}
