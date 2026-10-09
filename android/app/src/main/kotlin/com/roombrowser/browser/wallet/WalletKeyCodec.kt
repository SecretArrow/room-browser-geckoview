package com.roombrowser.browser.wallet

import com.roombrowser.domain.wallet.chains.ChainRegistry
import com.roombrowser.domain.wallet.crypto.Base58
import com.roombrowser.domain.wallet.crypto.Bip32PrivateKey
import com.roombrowser.domain.wallet.crypto.Ed25519
import com.roombrowser.domain.wallet.crypto.Hashes
import com.roombrowser.domain.wallet.crypto.Hex
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.domain.wallet.model.NetworkConfig
import com.roombrowser.domain.wallet.model.WalletException
import java.math.BigInteger

/**
 * Every conversion between raw key material and a chain address, in one place.
 *
 * Shared by [WalletEngine] and [com.roombrowser.data.repo.WalletRepository] so
 * the two can never disagree about what a valid key is: a backup written by
 * this app and the same key typed back in by hand must be accepted, rejected
 * and stored identically. Pure — no storage, no session, no locking — which is
 * what lets the repository restore a wallet into a profile that has never been
 * opened.
 */
object WalletKeyCodec {

    /**
     * Turns a pasted private key into (address, canonical stored form).
     *
     * The parse helpers throw [WalletException.InvalidParams] with the chain's
     * own wording, which is what a caller reports to the user.
     */
    fun parse(chainType: ChainType, privateKey: String, registry: ChainRegistry): Pair<String, String> {
        val trimmed = privateKey.trim()
        return when (chainType) {
            ChainType.EVM -> {
                val key = secp256k1(trimmed, chainType)
                registry.evm.addressFromPrivateKey(key) to canonicalSecp(key)
            }
            ChainType.SOLANA -> {
                val seed = ed25519Seed(trimmed, chainType)
                Base58.encode(Ed25519.publicKeyFromSeed(seed)) to Base58.encode(seed)
            }
            ChainType.APTOS -> {
                val seed = ed25519Seed(trimmed, chainType)
                aptosAddress(Ed25519.publicKeyFromSeed(seed)) to Hex.encode(seed)
            }
            ChainType.SUI -> {
                val seed = ed25519Seed(trimmed, chainType)
                suiAddress(Ed25519.publicKeyFromSeed(seed)) to Hex.encode(seed)
            }
            ChainType.COSMOS -> {
                val key = secp256k1(trimmed, chainType)
                val compressed = Bip32PrivateKey.compressedPublicKeyOf(key)
                val hrp = cosmosHome(registry).bech32Hrp ?: "cosmos"
                registry.cosmos.bech32Address(compressed, hrp) to canonicalSecp(key)
            }
            ChainType.BITCOIN -> {
                val key = secp256k1(trimmed, chainType)
                val compressed = Bip32PrivateKey.compressedPublicKeyOf(key)
                registry.bitcoin.p2wpkhAddress(compressed, testnet = false) to canonicalSecp(key)
            }
            ChainType.TRON -> {
                val key = secp256k1(trimmed, chainType)
                registry.tron.addressFromPrivateKey(key) to canonicalSecp(key)
            }
            ChainType.OCTRA -> {
                val seed = ed25519Seed(trimmed, chainType)
                registry.octra.accountFromSeed(seed).address to Hex.encode(seed)
            }
        }
    }

    /**
     * Canonical per-chain derivation through the chain's own adapter.
     * Returns (address, path). Pure CPU.
     */
    fun deriveAccount(
        chainType: ChainType,
        seed: ByteArray,
        index: Int,
        registry: ChainRegistry
    ): Pair<String, String> = when (chainType) {
        ChainType.EVM -> registry.evm.deriveAccount(seed, index).let { it.address to it.path }
        ChainType.SOLANA -> registry.solana.deriveAccount(seed, index).let { it.address to it.path }
        ChainType.APTOS -> registry.aptos.deriveAccount(seed, index).let { it.address to it.path }
        ChainType.SUI -> registry.sui.deriveAccount(seed, index).let { it.address to it.path }
        ChainType.COSMOS -> registry.cosmos.deriveAccount(seed, cosmosHome(registry), index)
            .let { it.address to it.path }
        ChainType.BITCOIN -> registry.bitcoin.deriveAccount(seed, bitcoinHome(registry), index)
            .let { it.address to it.path }
        ChainType.TRON -> registry.tron.deriveAccount(seed, index).let { it.address to it.path }
        ChainType.OCTRA -> registry.octra.deriveAccount(seed, index).let { it.address to it.path }
    }

    /** secp256k1 scalar (EVM/Cosmos/Bitcoin/TRON) from a stored key string. */
    fun secp256k1(key: String, chainType: ChainType): BigInteger {
        val clean = key.removePrefix("0x").removePrefix("0X")
        val parsed = runCatching { BigInteger(clean, 16) }.getOrNull()
            ?: throw WalletException.InvalidParams("Invalid ${chainType.displayName} private key")
        if (parsed.signum() <= 0 || parsed >= Bip32PrivateKey.CURVE_N) {
            throw WalletException.InvalidParams("${chainType.displayName} private key out of range")
        }
        return parsed
    }

    /** Solana imports are base58; Aptos/Sui SDKs use hex — accept either. */
    fun ed25519Seed(key: String, chainType: ChainType): ByteArray =
        Base58.decodeOrNull(key)?.takeIf { it.size == 32 }
            ?: Hex.decodeOrNull(key)?.takeIf { it.size == 32 }
            ?: throw WalletException.InvalidParams(
                "Invalid ${chainType.displayName} private key (expected 32-byte base58 or hex)"
            )

    /** Stored-key form for secp chains: 64 hex chars, no 0x. */
    private fun canonicalSecp(scalar: BigInteger): String = scalar.toString(16).padStart(64, '0')

    private fun aptosAddress(publicKey: ByteArray): String =
        "0x" + Hex.encode(Hashes.sha3_256(publicKey + byteArrayOf(0x00)))

    private fun suiAddress(publicKey: ByteArray): String =
        "0x" + Hex.encode(Hashes.blake2b256(byteArrayOf(0x00) + publicKey))

    /** The chain's canonical home network for derivation (Cosmos: coin 118 + hrp). */
    private fun cosmosHome(registry: ChainRegistry): NetworkConfig =
        registry.defaultNetworks(ChainType.COSMOS).first()

    /** Bitcoin mainnet home (coin 0, hrp "bc") for derivation. */
    private fun bitcoinHome(registry: ChainRegistry): NetworkConfig =
        registry.defaultNetworks(ChainType.BITCOIN).first()
}
