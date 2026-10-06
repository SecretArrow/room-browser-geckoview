package com.roombrowser.domain.wallet.model

import kotlinx.serialization.Serializable

/** The chain families this wallet supports. Each has its own adapter. */
enum class ChainType(val displayName: String) {
    EVM("EVM"),
    SOLANA("Solana"),
    APTOS("Aptos"),
    SUI("Sui"),
    COSMOS("Cosmos"),
    BITCOIN("Bitcoin"),
    TRON("TRON"),
    OCTRA("Octra");

    companion object {
        fun fromName(name: String): ChainType? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/**
 * A user-visible network/chain configuration. Chain IDs are strings because
 * different ecosystems identify chains differently: EVM uses decimal/hex
 * numbers ("1", "0x1"), Solana uses genesis hashes ("mainnet-beta"), Cosmos
 * uses chain-id slugs ("cosmoshub-4"), Bitcoin/TRON use a small enum.
 */
@Serializable
data class NetworkConfig(
    /** Stable identifier: "<chainType>:<chainId>", e.g. "EVM:137". */
    val id: String,
    val chainType: ChainType,
    val chainId: String,
    val name: String,
    val rpcUrls: List<String>,
    val nativeSymbol: String,
    val nativeDecimals: Int = 18,
    val explorerUrl: String? = null,
    /** Extra endpoints some chains need (LCD for Cosmos, indexer for BTC). */
    val lcdUrl: String? = null,
    /**
     * Further LCD hosts for the same chain — see [lcdUrl].
     *
     * Cosmos is the chain family that needs these: its REST endpoint is the
     * ONLY way this app talks to the chain (accounts, balances, broadcast),
     * and a Cosmos host is a single point of failure in a way an EVM one is
     * not, because there is no second protocol to fall back to. Listing a
     * spare here is what lets `RpcEndpointChain` fail over to it instead of
     * leaving the network unusable while a perfectly good endpoint sits one
     * field away, unread.
     *
     * Ordered after [lcdUrl] but not inferior to it: the chain tries the
     * last-known-good host first regardless of position.
     */
    val lcdFallbackUrls: List<String> = emptyList(),
    val indexerUrl: String? = null,
    val isTestnet: Boolean = false,
    /** bech32 human-readable part for Cosmos chains (e.g. "cosmos", "osmo"). */
    val bech32Hrp: String? = null,
    /** Cosmos coin type for BIP44 (118 default; Injective uses 60). */
    val coinType: Int? = null
) {
    companion object {
        fun evm(chainId: Long, name: String, rpcUrls: List<String>, symbol: String, explorer: String?, testnet: Boolean = false): NetworkConfig =
            NetworkConfig(
                id = "EVM:$chainId",
                chainType = ChainType.EVM,
                chainId = chainId.toString(),
                name = name,
                rpcUrls = rpcUrls,
                nativeSymbol = symbol,
                nativeDecimals = 18,
                explorerUrl = explorer,
                isTestnet = testnet
            )
    }
}

/** An account derived or imported for one chain. Pure data — no key material. */
data class WalletAccountInfo(
    val id: String,
    val walletId: String,
    val chainType: ChainType,
    val address: String,
    val label: String,
    /** Derivation path for mnemonic-derived accounts ("" for imports). */
    val path: String,
    /** DERIVED = from the wallet mnemonic, IMPORTED = private key/keystore. */
    val source: Source
) {
    enum class Source { DERIVED, IMPORTED }
}

/** Result of a balance query; formatted decimal string or null when offline. */
sealed interface BalanceResult {
    data class Ok(val amount: String, val symbol: String) : BalanceResult
    data class Error(val message: String) : BalanceResult
}

/** Result of broadcasting a signed transaction. */
sealed interface BroadcastResult {
    data class Ok(val hash: String) : BroadcastResult
    data class Error(val message: String) : BroadcastResult
}

/** A chain-agnostic fee/gas estimate shown on the confirmation screen. */
@Serializable
data class FeeEstimate(
    val label: String,
    val estimatedCost: String
)

/** Standard wallet errors surfaced to the dApp bridge and dashboard. */
sealed class WalletException(message: String) : Exception(message) {
    class NetworkUnavailable(message: String = "Network unavailable") : WalletException(message)

    /**
     * The endpoint was reached, and its certificate was rejected.
     *
     * Separate from [NetworkUnavailable] because the two send the user to
     * different places: "network unavailable" is a problem on this device,
     * while a rejected certificate is a problem at the endpoint — an expired
     * chain, a hostname that does not match, or a TLS-intercepting proxy on
     * the path. Reporting the first when the truth is the second costs the
     * user a pointless round of checking their own connection.
     *
     * It is still a TRANSPORT failure for failover purposes, and deliberately
     * so: certificate validation is never bypassed here, so the only correct
     * response to a bad certificate is to try the network's next endpoint.
     */
    class TlsFailure(message: String) : WalletException(message)
    class RpcError(val code: Int, message: String) : WalletException(message)
    class UserRejected(message: String = "User rejected the request") : WalletException(message)
    class Unauthorized(message: String) : WalletException(message)
    class UnsupportedMethod(message: String) : WalletException(message)
    class InvalidParams(message: String) : WalletException(message)
    class ChainNotSupported(message: String) : WalletException(message)
}
