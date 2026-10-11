package com.roombrowser.domain.wallet.rpc

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.domain.wallet.model.NetworkConfig
import com.roombrowser.domain.wallet.model.WalletException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * The failover primitive, tested without a network.
 *
 * `firstWorking` takes the call as a lambda, so every case here is decided by
 * what the endpoints "answer" — which is the point: what these tests pin is
 * WHICH endpoint the chain decides to try, never whether some public host is
 * up today. A test that needed a live RPC would be a test that fails for
 * reasons that have nothing to do with this class.
 *
 * The `<Unit>` on `runBlocking<Unit>` is load-bearing, not noise. A test
 * written `fun x() = runBlocking { ... }` whose last statement is a Truth
 * assertion infers its return type from that assertion, so the method returns
 * a `Subject` rather than void — and JUnit4 answers a non-void test method
 * with `InvalidTestClassError`, which fails the WHOLE CLASS as one
 * `initializationError` and runs none of it. Every assertion in such a method
 * silently stops being a test. The explicit type argument is the same fix the
 * instrumented tests use (see DatabaseIsolationTest).
 */
class RpcEndpointChainTest {

    private val chain = { urls: List<String> ->
        RpcEndpointChain(rpc = JsonRpcClient(), key = "TEST:1", urls = urls)
    }

    @Before
    fun forgetMemo() {
        // The last-known-good memo is process-wide; without this, a test that
        // asserts try-order inherits the order an earlier test left behind.
        RpcEndpointChain.clearMemo()
    }

    // ------------------------------------------------------------- ordering

    @Test
    fun `the first endpoint is used when it answers`() = runBlocking<Unit> {
        val tried = mutableListOf<String>()

        val result = chain(listOf("https://a", "https://b")).firstWorking { url ->
            tried.add(url)
            "answer from $url"
        }

        assertThat(result).isEqualTo("answer from https://a")
        assertThat(tried).containsExactly("https://a")
    }

    @Test
    fun `a dead endpoint hands over to the next one`() = runBlocking<Unit> {
        val tried = mutableListOf<String>()

        val result = chain(listOf("https://dead", "https://alive")).firstWorking { url ->
            tried.add(url)
            if (url == "https://dead") throw WalletException.NetworkUnavailable("connection refused")
            "answer from $url"
        }

        assertThat(result).isEqualTo("answer from https://alive")
        assertThat(tried).containsExactly("https://dead", "https://alive").inOrder()
    }

    @Test
    fun `an endpoint answering with an HTTP status is also a dead endpoint`() = runBlocking<Unit> {
        // 525 is the Cloudflare "TLS handshake failed" that several bundled
        // endpoints really answer with: the host is up, its own upstream
        // certificate is not, and it never gets as far as speaking JSON-RPC.
        val tried = mutableListOf<String>()

        val result = chain(listOf("https://rotted", "https://alive")).firstWorking { url ->
            tried.add(url)
            if (url == "https://rotted") throw WalletException.RpcError(525, "SSL handshake failed")
            "answer from $url"
        }

        assertThat(result).isEqualTo("answer from https://alive")
        assertThat(tried).hasSize(2)
    }

    @Test
    fun `a node that answers no is not asked twice`() = runBlocking<Unit> {
        // `execution reverted` is an ANSWER. Retrying it on a second node
        // produces the same answer, more slowly, and hides the real problem
        // behind a timeout.
        val tried = mutableListOf<String>()

        val thrown = assertThrows(WalletException.RpcError::class.java) {
            runBlocking {
                chain(listOf("https://a", "https://b")).firstWorking { url ->
                    tried.add(url)
                    throw WalletException.RpcError(-32000, "execution reverted")
                }
            }
        }

        assertThat(thrown.code).isEqualTo(-32000)
        assertThat(tried).containsExactly("https://a")
    }

    @Test
    fun `every endpoint failing reports how many were tried`() = runBlocking<Unit> {
        val thrown = assertThrows(WalletException.NetworkUnavailable::class.java) {
            runBlocking {
                chain(listOf("https://a", "https://b", "https://c")).firstWorking { url ->
                    throw WalletException.NetworkUnavailable("unreachable: $url")
                }
            }
        }

        // The count is the difference between "the network is down" and "the
        // one endpoint I have is down", which are different things to tell a
        // user and different things to fix.
        assertThat(thrown).hasMessageThat().contains("all 3 endpoints failed")
    }

    @Test
    fun `an endpoint with a rejected certificate hands over to the next one`() = runBlocking<Unit> {
        // The certificate is refused, never bypassed — so the chain moves on.
        // This is what the second url in a network's list is for, and it is
        // the case that matters most in practice: a host whose own upstream
        // certificate has expired is a common way for a free endpoint to rot.
        val tried = mutableListOf<String>()

        val result = chain(listOf("https://expired", "https://alive")).firstWorking { url ->
            tried.add(url)
            if (url == "https://expired") {
                throw WalletException.TlsFailure("certificate rejected for $url")
            }
            "answer from $url"
        }

        assertThat(result).isEqualTo("answer from https://alive")
        assertThat(tried).containsExactly("https://expired", "https://alive").inOrder()
    }

    @Test
    fun `every endpoint rejecting its certificate is still reported as a certificate problem`() = runBlocking<Unit> {
        // The aggregate step is where the distinction is easiest to lose: a
        // list whose every endpoint failed on a bad certificate is still a
        // certificate problem, and flattening it to NetworkUnavailable here
        // would send the user to check their own connection for a fault that
        // belongs to all three hosts.
        val thrown = assertThrows(WalletException.TlsFailure::class.java) {
            runBlocking {
                chain(listOf("https://a", "https://b", "https://c")).firstWorking { url ->
                    throw WalletException.TlsFailure("certificate rejected for $url")
                }
            }
        }

        assertThat(thrown).hasMessageThat().contains("certificate rejected for https://c")
        assertThat(thrown).hasMessageThat().contains("all 3 endpoints failed")
    }

    @Test
    fun `one endpoint failing reports its own error unchanged`() = runBlocking<Unit> {
        val thrown = assertThrows(WalletException.NetworkUnavailable::class.java) {
            runBlocking {
                chain(listOf("https://only")).firstWorking { url ->
                    throw WalletException.NetworkUnavailable("unreachable: $url")
                }
            }
        }

        assertThat(thrown).hasMessageThat().contains("unreachable: https://only")
        assertThat(thrown).hasMessageThat().doesNotContain("all ")
    }

    @Test
    fun `a network with no endpoint at all says so`() {
        // Not NetworkUnavailable: nothing was unreachable, there was nothing
        // to reach, and the fix is a config change rather than a retry.
        val thrown = assertThrows(WalletException.InvalidParams::class.java) {
            runBlocking { chain(emptyList()).firstWorking { "never" } }
        }

        assertThat(thrown).hasMessageThat().contains("no RPC endpoint")
    }

    // ----------------------------------------------------------- memoisation

    @Test
    fun `the endpoint that answered is tried first next time`() = runBlocking<Unit> {
        val first = chain(listOf("https://dead", "https://alive"))
        first.firstWorking { url ->
            if (url == "https://dead") throw WalletException.NetworkUnavailable("down")
            "ok"
        }

        // A second call against an equivalent chain starts at the host that
        // worked, so the dead endpoint's connect timeout is not paid again on
        // every single request.
        val second = chain(listOf("https://dead", "https://alive"))
        assertThat(second.ordered()).containsExactly("https://alive", "https://dead").inOrder()
    }

    @Test
    fun `a remembered endpoint the user removed is not used`() = runBlocking<Unit> {
        chain(listOf("https://a", "https://b")).firstWorking { "ok" } // memo now says https://a

        val edited = chain(listOf("https://b", "https://c"))

        // The memo is a cache, never a commitment: a url that is gone from
        // the network's list is gone, and the chain does not resurrect it.
        assertThat(edited.ordered()).containsExactly("https://b", "https://c").inOrder()
    }

    @Test
    fun `a failed memory falls through to the rest of the list`() = runBlocking<Unit> {
        chain(listOf("https://a", "https://b")).firstWorking { "ok" } // memo says https://a

        val tried = mutableListOf<String>()
        val result = chain(listOf("https://a", "https://b")).firstWorking { url ->
            tried.add(url)
            if (url == "https://a") throw WalletException.NetworkUnavailable("just died")
            "answer from $url"
        }

        assertThat(result).isEqualTo("answer from https://b")
        assertThat(tried).containsExactly("https://a", "https://b").inOrder()
    }

    // -------------------------------------------------------------- mapping

    @Test
    fun `only transport failures count as endpoint failures`() {
        assertThat(RpcEndpointChain.isEndpointFailure(
            WalletException.NetworkUnavailable("down")
        )).isTrue()
        assertThat(RpcEndpointChain.isEndpointFailure(
            WalletException.RpcError(525, "SSL handshake failed")
        )).isTrue()
        assertThat(RpcEndpointChain.isEndpointFailure(
            WalletException.RpcError(429, "rate limited")
        )).isTrue()

        // A rejected certificate is a transport failure too, and this is the
        // reason TlsFailure exists as its own class rather than being folded
        // into NetworkUnavailable: it must still fail over. Certificate
        // validation is not bypassed anywhere, so the ONLY correct response
        // to a host with a bad certificate is to try the network's next one.
        assertThat(RpcEndpointChain.isEndpointFailure(
            WalletException.TlsFailure("certificate rejected")
        )).isTrue()

        // An application answer: retrying cannot change it.
        assertThat(RpcEndpointChain.isEndpointFailure(
            WalletException.RpcError(-32000, "execution reverted")
        )).isFalse()
        assertThat(RpcEndpointChain.isEndpointFailure(
            WalletException.RpcError(3, "method not found")
        )).isFalse()

        // User decisions and bad input are not endpoint problems either.
        assertThat(RpcEndpointChain.isEndpointFailure(WalletException.UserRejected())).isFalse()
        assertThat(RpcEndpointChain.isEndpointFailure(
            WalletException.InvalidParams("bad address")
        )).isFalse()
    }

    // ------------------------------------------------------------------ of()

    @Test
    fun `blank urls are dropped rather than dialled`() {
        val network = NetworkConfig(
            id = "EVM:1", chainType = ChainType.EVM, chainId = "1", name = "Ethereum",
            rpcUrls = listOf("  ", "https://a", "", "https://b", " "), nativeSymbol = "ETH"
        )

        assertThat(RpcEndpointChain.of(JsonRpcClient(), network).urls)
            .containsExactly("https://a", "https://b").inOrder()
    }
}
