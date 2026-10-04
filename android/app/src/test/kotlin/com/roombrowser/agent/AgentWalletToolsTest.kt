package com.roombrowser.agent

import com.google.common.truth.Truth.assertThat
import com.roombrowser.browser.wallet.DappDecision
import com.roombrowser.browser.wallet.DappRequest
import com.roombrowser.browser.wallet.NetworkRecord
import com.roombrowser.browser.wallet.WalletAccountRecord
import com.roombrowser.browser.wallet.WalletLockState
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.domain.wallet.model.NetworkConfig
import com.roombrowser.domain.wallet.model.WalletException
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Pins the safety properties, not the happy path: approval must never reach
 * the wallet without the user's confirmation.
 */
class AgentWalletToolsTest {

    private class FakeWallet(
        override var lockState: WalletLockState = WalletLockState.UNLOCKED
    ) : AgentWalletAccess {
        val pending = mutableListOf<DappRequest>()
        override val pendingRequests: List<DappRequest> get() = pending
        override var accounts: List<WalletAccountRecord> = emptyList()
        override var activeNetworks: Map<ChainType, NetworkConfig> = emptyMap()
        override var networks: List<NetworkRecord> = emptyList()
        val decisions = mutableListOf<DappDecision>()
        var switchCalls = 0
        var switchThrows: WalletException? = null

        override fun decideDappRequest(decision: DappDecision) {
            decisions += decision
            pending.removeAll { it.id == decision.requestId }
        }

        override suspend fun setActiveNetwork(chainType: ChainType, networkId: String) {
            switchThrows?.let { throw it }
            switchCalls++
        }
    }

    /** Records each prompt; the default answer is deny. */
    private class Recorder(private var answer: Boolean = false) {
        val labels = mutableListOf<String>()
        suspend fun ask(label: String): Boolean {
            labels += label
            return answer
        }

        fun allow() { answer = true }
    }

    private fun sendRequest(id: String = "req-1") = DappRequest.SendTransaction(
        id = id,
        host = "app.example.com",
        chainType = ChainType.EVM,
        networkId = "EVM:1",
        accountAddress = "0xabc",
        txParamsJson = """{"to":"0xdead","value":"0xde0b6b3a7640000","data":"0x"}""",
        feeEstimate = null
    )

    private fun network(id: String, enabled: Boolean = true) = NetworkRecord(
        config = NetworkConfig(
            id = id,
            chainType = ChainType.EVM,
            chainId = id.substringAfter(':'),
            name = "Testnet $id",
            rpcUrls = listOf("https://rpc.example"),
            nativeSymbol = "ETH"
        ),
        enabled = enabled,
        isCustom = false
    )

    @Test
    fun `an approval for a request that was never listed is refused before any prompt`() {
        val wallet = FakeWallet().apply { pending += sendRequest() }
        val recorder = Recorder(answer = true)
        val tools = AgentWalletTools({ wallet }, recorder::ask)

        val result = runBlocking { tools.approve("req-1") }

        assertThat(result.ok).isFalse()
        assertThat(wallet.decisions).isEmpty()
        assertThat(recorder.labels).isEmpty()
    }

    @Test
    fun `a denied confirmation never reaches the wallet`() {
        val wallet = FakeWallet().apply { pending += sendRequest() }
        val recorder = Recorder(answer = false)
        val tools = AgentWalletTools({ wallet }, recorder::ask)
        tools.listRequests()

        val result = runBlocking { tools.approve("req-1") }

        assertThat(result.ok).isFalse()
        assertThat(wallet.decisions).isEmpty()
        assertThat(recorder.labels).hasSize(1)
    }

    @Test
    fun `a confirmed approval settles through the engine's own decision entry point`() {
        val wallet = FakeWallet().apply { pending += sendRequest() }
        val recorder = Recorder(answer = true)
        val tools = AgentWalletTools({ wallet }, recorder::ask)
        tools.listRequests()

        val result = runBlocking { tools.approve("req-1") }

        assertThat(result.ok).isTrue()
        assertThat(wallet.decisions).hasSize(1)
        assertThat(wallet.decisions[0]).isEqualTo(DappDecision(requestId = "req-1", approved = true))
    }

    @Test
    fun `the confirmation shows the same detail the model was given`() {
        val wallet = FakeWallet().apply { pending += sendRequest() }
        val recorder = Recorder(answer = true)
        val tools = AgentWalletTools({ wallet }, recorder::ask)
        val listed = tools.listRequests().output

        runBlocking<Unit> { tools.approve("req-1") }

        val prompt = recorder.labels.single()
        assertThat(prompt).contains("app.example.com")
        assertThat(prompt).contains("to: 0xdead")
        assertThat(prompt).contains("value:")
        assertThat(prompt).contains("data:")
        assertThat(listed).contains("to: 0xdead")
    }

    @Test
    fun `an approval is refused once the request is no longer pending`() {
        val wallet = FakeWallet().apply { pending += sendRequest() }
        val recorder = Recorder(answer = true)
        val tools = AgentWalletTools({ wallet }, recorder::ask)
        tools.listRequests()
        wallet.pending.clear()

        val result = runBlocking { tools.approve("req-1") }

        assertThat(result.ok).isFalse()
        assertThat(wallet.decisions).isEmpty()
        assertThat(recorder.labels).isEmpty()
    }

    @Test
    fun `reject needs neither listing nor confirmation`() {
        val wallet = FakeWallet().apply { pending += sendRequest() }
        // Would throw if consulted; reject must not ask.
        val tools = AgentWalletTools({ wallet }, { error("reject must not ask") })

        val result = tools.reject("req-1")

        assertThat(result.ok).isTrue()
        assertThat(wallet.decisions).hasSize(1)
        assertThat(wallet.decisions[0]).isEqualTo(DappDecision(requestId = "req-1", approved = false))
    }

    @Test
    fun `reject still works when a denial is the default answer`() {
        val wallet = FakeWallet().apply { pending += sendRequest() }
        val recorder = Recorder(answer = false)
        val tools = AgentWalletTools({ wallet }, recorder::ask)

        val result = tools.reject("req-1")

        assertThat(result.ok).isTrue()
        assertThat(wallet.decisions).hasSize(1)
        assertThat(wallet.decisions[0].approved).isFalse()
        assertThat(recorder.labels).isEmpty()
    }

    @Test
    fun `a wallet tool with no confirmation wired denies approval`() {
        val wallet = FakeWallet().apply { pending += sendRequest() }
        // The executor default is deny; a forgotten wiring must not approve.
        val tools = AgentWalletTools({ wallet })
        tools.listRequests()

        val result = runBlocking { tools.approve("req-1") }

        assertThat(result.ok).isFalse()
        assertThat(wallet.decisions).isEmpty()
    }

    @Test
    fun `switching refuses a network the wallet does not know`() {
        val wallet = FakeWallet().apply { networks = listOf(network("EVM:1")) }
        val recorder = Recorder(answer = true)
        val tools = AgentWalletTools({ wallet }, recorder::ask)

        val result = runBlocking { tools.switchNetwork("EVM:999") }

        assertThat(result.ok).isFalse()
        assertThat(wallet.switchCalls).isEqualTo(0)
        assertThat(recorder.labels).isEmpty()
    }

    @Test
    fun `switching refuses a disabled network`() {
        val wallet = FakeWallet().apply { networks = listOf(network("EVM:1", enabled = false)) }
        val recorder = Recorder(answer = true)
        val tools = AgentWalletTools({ wallet }, recorder::ask)

        val result = runBlocking { tools.switchNetwork("EVM:1") }

        assertThat(result.ok).isFalse()
        assertThat(wallet.switchCalls).isEqualTo(0)
    }

    @Test
    fun `switching a known enabled network needs confirmation`() {
        val wallet = FakeWallet().apply { networks = listOf(network("EVM:1")) }
        val recorder = Recorder(answer = false)
        val tools = AgentWalletTools({ wallet }, recorder::ask)

        val result = runBlocking { tools.switchNetwork("EVM:1") }

        assertThat(result.ok).isFalse()
        assertThat(wallet.switchCalls).isEqualTo(0)
        assertThat(recorder.labels).hasSize(1)
    }

    @Test
    fun `a confirmed switch reaches the engine`() {
        val wallet = FakeWallet().apply { networks = listOf(network("EVM:1")) }
        val recorder = Recorder(answer = true)
        val tools = AgentWalletTools({ wallet }, recorder::ask)

        val result = runBlocking { tools.switchNetwork("EVM:1") }

        assertThat(result.ok).isTrue()
        assertThat(wallet.switchCalls).isEqualTo(1)
    }

    @Test
    fun `a network the engine rejects is reported, not silently ignored`() {
        val wallet = FakeWallet().apply { networks = listOf(network("EVM:1")) }
        wallet.switchThrows = WalletException.InvalidParams("Unknown network 'EVM:1'")
        val tools = AgentWalletTools({ wallet }, { true })

        val result = runBlocking { tools.switchNetwork("EVM:1") }

        assertThat(result.ok).isFalse()
        assertThat(result.output).contains("Unknown network")
    }

    @Test
    fun `the state listing names accounts and known networks`() {
        val wallet = FakeWallet().apply {
            accounts = listOf(
                WalletAccountRecord(
                    id = "a1",
                    walletId = "w1",
                    chainType = ChainType.EVM,
                    address = "0x1234",
                    label = "Main",
                    path = "m/44'/60'/0'/0/0",
                    source = WalletAccountRecord.Source.DERIVED
                )
            )
            networks = listOf(network("EVM:1"))
            activeNetworks = mapOf(ChainType.EVM to network("EVM:1").config)
        }
        val tools = AgentWalletTools({ wallet }, { false })

        val output = tools.readState().output

        assertThat(output).contains("Main")
        assertThat(output).contains("0x1234")
        assertThat(output).contains("EVM:1")
        assertThat(output).contains("lock_state: UNLOCKED")
    }
}
