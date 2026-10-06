package com.roombrowser.domain.wallet.chains.evm

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.wallet.model.NetworkConfig
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.math.BigInteger

/**
 * A swap-shaped `eth_sendTransaction` on Sepolia, signed offline.
 *
 * Every nonce, gas and fee field is supplied, so `prepareAndSign` makes no
 * network call and what is under test is what the signature commits to.
 * `data` is the substance: a wallet that signed the router and the fee but not
 * the calldata would call the router with empty input.
 */
class EvmAdapterTest {

    private val adapter = EvmAdapter()

    private val sepolia = NetworkConfig.evm(
        chainId = 11155111,
        name = "Ethereum Sepolia",
        rpcUrls = listOf("https://ethereum-sepolia-rpc.publicnode.com"),
        symbol = "ETH",
        explorer = "https://sepolia.etherscan.io",
        testnet = true
    )

    // Synthetic values. The sender is derived from the signature, so neither
    // a funded key nor a published one is needed for this to mean anything.
    private val key = BigInteger("7f9c2ba4e88f827d616045507605853ed73b8093f6efbc88eb1a6eacfa66ef26", 16)
    private val from = "0x2c7536e3605d9c16a7a3d7b1898e529396a65c23"

    /** A contract, never an EOA: this must not sign as a plain transfer. */
    private val router = "0x3bfa4769fb09eefc5a80d6e87c3b9c650f7ae48e"

    private val gas = BigInteger("250000")
    private val maxFee = BigInteger("3000000000")
    private val priority = BigInteger("1500000000")
    private val gasPrice = BigInteger("2000000000")
    private val nonce = BigInteger("7")

    /** Uniswap V3 `exactInputSingle`: selector plus eight ABI words. */
    private val swapCalldata = "0x04e45aaf" + listOf(
        word("fff9976782d46cc05630d1f6ebab18b2324d6b14"), // tokenIn
        word("1c7d4b196cb0c7b01d743fbc6116a902379c7238"), // tokenOut
        word("0bb8"), // fee: 0.3%
        word("2c7536e3605d9c16a7a3d7b1898e529396a65c23"), // recipient
        word("67a1b2c3"), // deadline
        word("38d7ea4c68000"), // amountIn: 1e15, i.e. 0.001 ETH
        word("1"), // amountOutMinimum
        word("0") // sqrtPriceLimitX96
    ).joinToString("")

    private fun word(hex: String) = hex.padStart(64, '0')

    @Test
    fun `a swap shaped send signs the calldata, the router and the fee`() {
        runBlocking {
            val (summary, signed) = adapter.prepareAndSign(
                network = sepolia,
                privateKey = key,
                from = from,
                to = router,
                value = BigInteger.ZERO,
                data = swapCalldata,
                gasLimit = gas,
                gasPrice = null,
                maxFeePerGas = maxFee,
                maxPriorityFeePerGas = priority,
                nonce = nonce
            )

            assertThat(signed.startsWith("0x02")).isTrue()
            // `to` and `data` are RLP fields of the bytes that were signed, so
            // their hex appears inside them. A calldata that was dropped or
            // re-encoded could not be here.
            assertThat(signed).contains(router.removePrefix("0x"))
            assertThat(signed).contains(swapCalldata.removePrefix("0x"))
            assertThat(summary.chainId).isEqualTo(11155111L)
            assertThat(summary.isEip1559).isTrue()
            assertThat(summary.params.data).isEqualTo(swapCalldata)
            assertThat(summary.params.to).isEqualTo(router)
            assertThat(summary.params.value).isEqualTo(BigInteger.ZERO)
            assertThat(summary.params.nonce).isEqualTo(nonce)
            assertThat(summary.estimatedFeeWei).isEqualTo(gas.multiply(maxFee))
        }
    }

    @Test
    fun `the signature follows the calldata it was given`() {
        runBlocking {
            // The control for the containment claim above: with different
            // calldata the old bytes are gone and the new ones are present, so
            // that claim cannot hold for a reason unrelated to `data`.
            val other = swapCalldata.replaceFirst("04e45aaf", "414bf389")
            val signed = sign(swapCalldata)
            val signedOther = sign(other)

            assertThat(signedOther).isNotEqualTo(signed)
            assertThat(signedOther).contains(other.removePrefix("0x"))
            assertThat(signedOther).doesNotContain(swapCalldata.removePrefix("0x"))
        }
    }

    @Test
    fun `an explicit gasPrice signs a legacy transaction`() {
        runBlocking {
            // Chains and dApps that still price by gasPrice: the calldata has
            // to survive this path too, and the fee shown is gas * gasPrice.
            val (summary, signed) = adapter.prepareAndSign(
                network = sepolia,
                privateKey = key,
                from = from,
                to = router,
                value = BigInteger.ZERO,
                data = swapCalldata,
                gasLimit = gas,
                gasPrice = gasPrice,
                maxFeePerGas = null,
                maxPriorityFeePerGas = null,
                nonce = nonce
            )

            assertThat(summary.isEip1559).isFalse()
            // Legacy transactions are bare RLP lists (0xf8...), never typed.
            assertThat(signed.startsWith("0x02")).isFalse()
            assertThat(signed).contains(swapCalldata.removePrefix("0x"))
            assertThat(summary.estimatedFeeWei).isEqualTo(gas.multiply(gasPrice))
        }
    }

    private suspend fun sign(data: String): String = adapter.prepareAndSign(
        network = sepolia,
        privateKey = key,
        from = from,
        to = router,
        value = BigInteger.ZERO,
        data = data,
        gasLimit = gas,
        gasPrice = null,
        maxFeePerGas = maxFee,
        maxPriorityFeePerGas = priority,
        nonce = nonce
    ).second
}
