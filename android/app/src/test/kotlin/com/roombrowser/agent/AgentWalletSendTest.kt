package com.roombrowser.agent

import com.google.common.truth.Truth.assertThat
import com.roombrowser.browser.wallet.DappDecision
import com.roombrowser.browser.wallet.DappRequest
import com.roombrowser.browser.wallet.NetworkRecord
import com.roombrowser.browser.wallet.WalletAccountRecord
import com.roombrowser.browser.wallet.WalletLockedException
import com.roombrowser.browser.wallet.WalletLockState
import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.domain.task.AiTaskPermissions
import com.roombrowser.domain.task.ToolGroup
import com.roombrowser.domain.wallet.model.BalanceResult
import com.roombrowser.domain.wallet.model.BroadcastResult
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.domain.wallet.model.FeeEstimate
import com.roombrowser.domain.wallet.model.NetworkConfig
import com.roombrowser.domain.wallet.model.WalletException
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Pins the SEND-side gates, not the happy path: no transfer leaves the wallet
 * without an unlocked session, a resolved account and network, and the user's
 * explicit answer to the exact summary the engine computed. The mode gates
 * (Plan mode, unattended runs) live in the policy objects, so they are pinned
 * where they are implemented — the scheduled-run test at the bottom of this
 * file, and PlanModePolicyTest beside PlanModePolicy itself.
 *
 * Every test ends on a VOID-returning Truth call; the contains* families
 * return `Ordered` and would make the method non-void, which JUnit4 refuses.
 */
class AgentWalletSendTest {

    /** Records each prompt; the default answer is deny. */
    private class Recorder(private var answer: Boolean = false) {
        val labels = mutableListOf<String>()
        suspend fun ask(label: String): Boolean {
            labels += label
            return answer
        }

        /** For tests that must prove a prompt NEVER happened. */
        fun refuseToBeAsked(): suspend (String) -> Boolean = { label ->
            error("no prompt was expected but one arrived: ${label.take(60)}")
        }
    }

    private class FakeWalletEngine(
        override var lockState: WalletLockState = WalletLockState.UNLOCKED
    ) : AgentWalletAccess {
        override var accounts: List<WalletAccountRecord> = emptyList()
        override var activeNetworks: Map<ChainType, NetworkConfig> = emptyMap()
        override var networks: List<NetworkRecord> = emptyList()
        override val pendingRequests: List<DappRequest> get() = pending
        override var balances: Map<String, BalanceResult> = emptyMap()

        var fee: FeeEstimate? = null
        var feeCalls = 0
        var sendAnswer: BroadcastResult = BroadcastResult.Ok("0xconfirmed")
        var sendThrows: Exception? = null

        /** Flips the session's lock state the moment a send is attempted. */
        var lockDuringSend: WalletLockState? = null
        val sends = mutableListOf<SendCall>()

        // The dApp queue, only so the unsurfaced-approval gate can be pinned on
        // the same fake; no send test routes through it.
        val pending = mutableListOf<DappRequest>()
        val decisions = mutableListOf<DappDecision>()

        data class SendCall(
            val accountId: String,
            val networkId: String,
            val to: String,
            val amount: String
        )

        override fun decideDappRequest(decision: DappDecision) {
            decisions += decision
            pending.removeAll { it.id == decision.requestId }
        }

        override suspend fun setActiveNetwork(chainType: ChainType, networkId: String) =
            error("no network switch is involved in these tests")

        override suspend fun estimateSendFee(
            chainType: ChainType,
            networkId: String,
            fromAccountId: String,
            to: String,
            amount: String
        ): FeeEstimate? {
            feeCalls++
            return fee
        }

        override suspend fun sendNative(
            accountId: String,
            networkId: String,
            to: String,
            amount: String
        ): BroadcastResult {
            lockDuringSend?.let {
                lockState = it
                throw WalletLockedException()
            }
            sendThrows?.let { throw it }
            sends += SendCall(accountId, networkId, to, amount)
            return sendAnswer
        }
    }

    private fun account(id: String = "a1", chain: ChainType = ChainType.EVM) = WalletAccountRecord(
        id = id,
        walletId = "w1",
        chainType = chain,
        address = "0x1234567890abcdef",
        label = "Main",
        path = "m/44'/60'/0'/0/0",
        source = WalletAccountRecord.Source.DERIVED
    )

    private fun network(id: String, chain: ChainType = ChainType.EVM, enabled: Boolean = true) =
        NetworkRecord(
            config = NetworkConfig(
                id = id,
                chainType = chain,
                chainId = id.substringAfter(':'),
                name = "Testnet $id",
                rpcUrls = listOf("https://rpc.example"),
                nativeSymbol = "POL"
            ),
            enabled = enabled,
            isCustom = false
        )

    /** One EVM account on one enabled EVM network: the minimal sendable wallet. */
    private fun sendableWallet(): FakeWalletEngine = FakeWalletEngine().apply {
        accounts = listOf(account())
        networks = listOf(network("EVM:137"))
        activeNetworks = mapOf(ChainType.EVM to network("EVM:137").config)
        fee = FeeEstimate("Estimated gas fee", "0.00021 POL")
    }

    private fun tools(wallet: FakeWalletEngine, recorder: Recorder): AgentWalletTools =
        AgentWalletTools({ wallet }, recorder::ask)

    // ------------------------------------------------------------- locked / absent

    @Test
    fun `a send is refused while the wallet is locked, before anything is asked`() {
        val wallet = FakeWalletEngine(WalletLockState.LOCKED)
        val result = runBlocking {
            AgentWalletTools({ wallet }, { error("a locked wallet must not be prompted") })
                .send("a1", "EVM:137", "0xdead", "1.5")
        }

        assertThat(result.ok).isFalse()
        assertThat(result.output).contains("wallet is locked")
        assertThat(result.output).contains("NEVER ask")
        assertThat(wallet.sends).isEmpty()
        assertThat(wallet.feeCalls).isEqualTo(0)
    }

    @Test
    fun `a send is refused when the profile has no wallet`() {
        val wallet = FakeWalletEngine(WalletLockState.NO_WALLET)
        val result = runBlocking {
            AgentWalletTools({ wallet }, { error("an absent wallet must not be prompted") })
                .send("a1", "EVM:137", "0xdead", "1.5")
        }

        assertThat(result.ok).isFalse()
        assertThat(result.output).contains("no wallet")
        assertThat(wallet.sends).isEmpty()
    }

    @Test
    fun `the balances are refused while locked, with the same guidance`() {
        val wallet = FakeWalletEngine(WalletLockState.LOCKED)
        val tools = AgentWalletTools({ wallet }, { error("a locked wallet must not be prompted") })

        val result = tools.readBalances(null)

        assertThat(result.ok).isFalse()
        assertThat(result.output).contains("wallet is locked")
    }

    // ------------------------------------------------------------- argument validation

    @Test
    fun `a send with missing arguments is refused without prompting or estimating`() = runBlocking<Unit> {
        val wallet = sendableWallet()
        val recorder = Recorder(answer = true)
        val tools = tools(wallet, recorder)

        val cases = listOf(
            tools.send(null, "EVM:137", "0xdead", "1.5") to "account_id",
            tools.send("a1", null, "0xdead", "1.5") to "network_id",
            tools.send("a1", "EVM:137", null, "1.5") to "to",
            tools.send("a1", "EVM:137", "0xdead", null) to "amount"
        )

        cases.forEach { (result, missing) ->
            assertThat(result.ok).isFalse()
            assertThat(result.output).contains("missing")
            assertThat(result.output).contains(missing)
        }
        assertThat(recorder.labels).isEmpty()
        assertThat(wallet.sends).isEmpty()
        assertThat(wallet.feeCalls).isEqualTo(0)
    }

    @Test
    fun `a send from an unknown account is refused without prompting`() {
        val wallet = sendableWallet()
        val recorder = Recorder(answer = true)
        val tools = tools(wallet, recorder)

        val result = runBlocking { tools.send("a9", "EVM:137", "0xdead", "1.5") }

        assertThat(result.ok).isFalse()
        assertThat(result.output).contains("Unknown account")
        assertThat(recorder.labels).isEmpty()
        assertThat(wallet.sends).isEmpty()
        assertThat(wallet.feeCalls).isEqualTo(0)
    }

    @Test
    fun `a send on a network the wallet does not know is refused without prompting`() {
        val wallet = sendableWallet()
        val recorder = Recorder(answer = true)
        val tools = tools(wallet, recorder)

        val result = runBlocking { tools.send("a1", "EVM:999", "0xdead", "1.5") }

        assertThat(result.ok).isFalse()
        assertThat(result.output).contains("Unknown network")
        assertThat(recorder.labels).isEmpty()
        assertThat(wallet.sends).isEmpty()
        assertThat(wallet.feeCalls).isEqualTo(0)
    }

    @Test
    fun `a send on a disabled network is refused`() {
        val wallet = sendableWallet().apply {
            networks = listOf(network("EVM:137", enabled = false))
        }
        val recorder = Recorder(answer = true)
        val tools = tools(wallet, recorder)

        val result = runBlocking { tools.send("a1", "EVM:137", "0xdead", "1.5") }

        assertThat(result.ok).isFalse()
        assertThat(result.output).contains("disabled")
        assertThat(recorder.labels).isEmpty()
        assertThat(wallet.sends).isEmpty()
        assertThat(wallet.feeCalls).isEqualTo(0)
    }

    @Test
    fun `a send whose network does not match the account's chain is refused`() {
        val wallet = sendableWallet().apply {
            networks = listOf(network("EVM:137"), network("SOL:mainnet", ChainType.SOLANA))
        }
        val recorder = Recorder(answer = true)
        val tools = tools(wallet, recorder)

        val result = runBlocking { tools.send("a1", "SOL:mainnet", "0xdead", "1.5") }

        assertThat(result.ok).isFalse()
        assertThat(result.output).contains("do not match")
        assertThat(recorder.labels).isEmpty()
        assertThat(wallet.sends).isEmpty()
        assertThat(wallet.feeCalls).isEqualTo(0)
    }

    // ------------------------------------------------------------- the confirmation gate

    @Test
    fun `a denied confirmation never reaches the engine`() {
        val wallet = sendableWallet()
        val recorder = Recorder(answer = false)
        val tools = tools(wallet, recorder)

        val result = runBlocking { tools.send("a1", "EVM:137", "0xdead", "1.5") }

        assertThat(result.ok).isFalse()
        assertThat(result.output).contains("did not approve")
        assertThat(recorder.labels).hasSize(1)
        assertThat(wallet.sends).isEmpty()
    }

    @Test
    fun `a send with no confirmation wired refuses by default`() {
        // The executor's default is deny; a forgotten wiring must not send.
        val wallet = sendableWallet()
        val tools = AgentWalletTools({ wallet })

        val result = runBlocking { tools.send("a1", "EVM:137", "0xdead", "1.5") }

        assertThat(result.ok).isFalse()
        assertThat(wallet.sends).isEmpty()
    }

    @Test
    fun `a confirmed send executes through the engine and reports the hash`() {
        val wallet = sendableWallet()
        val recorder = Recorder(answer = true)
        val tools = tools(wallet, recorder)

        val result = runBlocking { tools.send("a1", "EVM:137", "0xdead", "1.5") }

        assertThat(result.ok).isTrue()
        assertThat(result.output).contains("0xconfirmed")
        assertThat(result.output).contains("1.5 POL")
        assertThat(result.output).contains("0xdead")
        assertThat(wallet.sends).containsExactly(
            FakeWalletEngine.SendCall("a1", "EVM:137", "0xdead", "1.5")
        )
        // One prompt, and it is the wallet prompt — the transfer was asked
        // about, not assumed. hasSize is VOID, so it closes the test.
        assertThat(recorder.labels).hasSize(1)
    }

    @Test
    fun `the user confirms the exact summary the engine computed`() {
        // The summary-mismatch guard: the string shown to the user is the string
        // WalletSendFormat built from the engine's records, and the result quotes
        // the same string back. Any change to an ingredient changes the summary,
        // so a paraphrased amount or destination cannot ride through unnoticed.
        val wallet = sendableWallet()
        val recorder = Recorder(answer = true)
        val tools = tools(wallet, recorder)

        val result = runBlocking { tools.send("a1", "EVM:137", "0xdead", "1.5") }

        val expected = WalletSendFormat.summary(
            account(),
            network("EVM:137").config,
            "0xdead",
            "1.5",
            FeeEstimate("Estimated gas fee", "0.00021 POL")
        )
        // containsExactly returns Ordered (non-void), so the void isEqualTo and
        // hasSize assertions close the test.
        assertThat(recorder.labels).containsExactly(expected)
        assertThat(result.output).contains(expected)

        // And the summary is sensitive to every ingredient: a different amount
        // produces a DIFFERENT summary, which is what makes a mismatch visible.
        val other = WalletSendFormat.summary(
            account(), network("EVM:137").config, "0xdead", "9.0",
            FeeEstimate("Estimated gas fee", "0.00021 POL")
        )
        assertThat(other).isNotEqualTo(expected)
    }

    @Test
    fun `the summary names the fee honestly when the chain cannot estimate one`() {
        val wallet = sendableWallet().apply { fee = null }
        val recorder = Recorder(answer = false)
        val tools = tools(wallet, recorder)

        runBlocking<Unit> { tools.send("a1", "EVM:137", "0xdead", "1.5") }

        assertThat(recorder.labels.single()).contains("not estimated in advance")
    }

    // ------------------------------------------------------------- execution failures

    @Test
    fun `an engine failure after confirmation is reported, not retried`() {
        val wallet = sendableWallet().apply {
            sendThrows = WalletException.RpcError(-1, "insufficient funds for gas")
        }
        val recorder = Recorder(answer = true)
        val tools = tools(wallet, recorder)

        val result = runBlocking { tools.send("a1", "EVM:137", "0xdead", "1.5") }

        assertThat(result.ok).isFalse()
        assertThat(result.output).contains("insufficient funds for gas")
        assertThat(wallet.sends).isEmpty()
    }

    @Test
    fun `a send whose session locks before broadcast gets the locked guidance`() {
        // The session can lock between the user's confirmation and the
        // broadcast. The honest answer names the lock; it never silently
        // retries a transfer the user already confirmed once.
        val wallet = sendableWallet().apply {
            lockDuringSend = WalletLockState.LOCKED
        }
        val recorder = Recorder(answer = true)
        val tools = tools(wallet, recorder)

        val result = runBlocking { tools.send("a1", "EVM:137", "0xdead", "1.5") }

        assertThat(result.ok).isFalse()
        assertThat(result.output).contains("wallet is locked")
        assertThat(wallet.sends).isEmpty()
    }

    // ------------------------------------------------------------- balances

    @Test
    fun `the balances list per account without any prompt and change nothing`() {
        val wallet = FakeWalletEngine().apply {
            accounts = listOf(account("a1"), account("a2", ChainType.SOLANA), account("a3"))
            activeNetworks = mapOf(
                ChainType.EVM to network("EVM:137").config,
                ChainType.SOLANA to network("SOL:mainnet", ChainType.SOLANA).config
            )
            balances = mapOf(
                "a1" to BalanceResult.Ok("12.4", "POL"),
                "a2" to BalanceResult.Error("rpc down")
                // a3 deliberately absent: not fetched yet
            )
        }
        val tools = AgentWalletTools({ wallet }, Recorder().refuseToBeAsked())

        val result = tools.readBalances(null)

        assertThat(result.ok).isTrue()
        assertThat(result.output).contains("12.4 POL")
        assertThat(result.output).contains("EVM:137")
        assertThat(result.output).contains("unavailable — rpc down")
        assertThat(result.output).contains("not fetched yet")
    }

    @Test
    fun `the balances narrow to one account and refuse an unknown id`() {
        val wallet = FakeWalletEngine().apply {
            accounts = listOf(account("a1"))
            balances = mapOf("a1" to BalanceResult.Ok("12.4", "POL"))
        }
        val tools = AgentWalletTools({ wallet }, Recorder().refuseToBeAsked())

        val one = tools.readBalances("a1")
        val unknown = tools.readBalances("a9")

        assertThat(one.ok).isTrue()
        assertThat(one.output).contains("12.4 POL")
        assertThat(unknown.ok).isFalse()
        assertThat(unknown.output).contains("Unknown account")
    }

    @Test
    fun `the unsurfaced-request refusal still holds beside the send gates`() {
        // The dApp-approval gate this file's fake shares with wallet_approve: a
        // request that IS pending but was never listed this turn is refused
        // BEFORE the prompt — the same refuse-before-asking shape wallet_send
        // applies to every argument and lock failure.
        val wallet = FakeWalletEngine().apply { pending += dappSendRequest("req-1") }
        val result = runBlocking {
            AgentWalletTools({ wallet }, { error("an unsurfaced request must not be prompted") })
                .approve("req-1")
        }

        assertThat(result.ok).isFalse()
        assertThat(result.output).contains("has not been shown")
        assertThat(wallet.decisions).isEmpty()
    }

    /** A dApp transaction request, shaped like the ones the bridge raises. */
    private fun dappSendRequest(id: String) = DappRequest.SendTransaction(
        id = id,
        host = "app.example.com",
        chainType = ChainType.EVM,
        networkId = "EVM:137",
        accountAddress = "0x1234567890abcdef",
        txParamsJson = """{"to":"0xdead","value":"0xde0b6b3a7640000"}""",
        feeEstimate = null
    )

    // ------------------------------------------------------------- the mode gates

    @Test
    fun `a scheduled run refuses the send and the balances`() {
        // WALLET_TOOLS membership is what every unattended gate reads, so the
        // assertion is against the map those gates consult, with every grant
        // switched on: the wallet group is the one no grant reaches.
        val everything = AiTaskPermissions(
            allowReadPage = true,
            allowNavigate = true,
            allowInteract = true,
            allowPost = true
        )

        listOf(AgentTools.WALLET_SEND, AgentTools.WALLET_BALANCES).forEach { tool ->
            assertThat(everything.groupOf(tool)).isEqualTo(ToolGroup.WALLET)
            assertThat(everything.allows(tool)).isFalse()
            val refusal = everything.refusal(tool)
            assertThat(refusal).isNotNull()
            assertThat(refusal).contains(tool)
            assertThat(refusal).doesNotContain("unknown tool")
        }
    }
}
