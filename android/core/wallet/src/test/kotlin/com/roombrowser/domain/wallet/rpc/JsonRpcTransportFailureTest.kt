package com.roombrowser.domain.wallet.rpc

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.wallet.model.WalletException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * How a transport failure becomes a [WalletException].
 *
 * The failure is injected by an OkHttp interceptor rather than by a real TLS
 * server, and that is deliberate: what is under test here is the MAPPING — the
 * decision that an `SSLException` is a certificate problem worth naming and an
 * ordinary `IOException` is not. That OkHttp reports an expired chain, an
 * untrusted issuer and a hostname mismatch as `SSLException` subclasses is a
 * fact about OkHttp, not about this client, and it is the fact the mapping is
 * written against; a test that stood up its own bad-certificate server would
 * be testing the JDK's TLS stack instead.
 *
 * Interceptors added at the application level sit outside OkHttp's connection
 * machinery, so what they throw propagates out of `execute()` unwrapped and
 * untouched — which is exactly the shape the mapping has to handle.
 */
class JsonRpcTransportFailureTest {

    private fun clientThrowing(e: IOException) = JsonRpcClient(
        OkHttpClient.Builder().addInterceptor { throw e }.build()
    )

    private suspend fun callWith(e: IOException, url: String = "https://endpoint.invalid") =
        clientThrowing(e).call(url, "eth_blockNumber")

    @Test
    fun `a rejected certificate is named as one, not as a missing network`() {
        val e = assertThrows(WalletException.TlsFailure::class.java) {
            runBlocking { callWith(SSLHandshakeException("PKIX path building failed")) }
        }

        // The device's network is fine. Saying otherwise sends the user to
        // check their own connection for a fault that is at the endpoint.
        assertThat(e).hasMessageThat().contains("certificate")
        assertThat(e).hasMessageThat().contains("PKIX path building failed")
    }

    @Test
    fun `a hostname mismatch is a certificate problem too`() {
        // This is the one the browser shows as "the certificate hostname does
        // not match": the endpoint answered, on a certificate issued for
        // somebody else. Same treatment, because the user's fix is the same.
        val e = assertThrows(WalletException.TlsFailure::class.java) {
            runBlocking { callWith(SSLPeerUnverifiedException("Hostname wrong.host.example not verified")) }
        }

        assertThat(e).hasMessageThat().contains("certificate")
        assertThat(e).hasMessageThat().contains("not verified")
    }

    @Test
    fun `an ordinary transport failure is still just unreachable`() {
        val e = assertThrows(WalletException.NetworkUnavailable::class.java) {
            runBlocking { callWith(UnknownHostException("endpoint.invalid")) }
        }

        assertThat(e).hasMessageThat().contains("RPC unreachable")
        // Not TlsFailure: nothing was presented to reject.
        assertThat(e).isNotInstanceOf(WalletException.TlsFailure::class.java)
    }

    @Test
    fun `the REST helpers map the same way as the JSON-RPC call`() {
        // Cosmos and Aptos reach the chain over REST rather than JSON-RPC, so
        // a certificate distinction that only existed on the JSON-RPC path
        // would be missing on the chains that need it most.
        val e = assertThrows(WalletException.TlsFailure::class.java) {
            runBlocking { clientThrowing(SSLHandshakeException("expired")).getJson("https://lcd.invalid/x") }
        }

        assertThat(e).hasMessageThat().contains("certificate")
    }

    @Test
    fun `a certificate failure is eligible for failover`() {
        // The mapping and the failover have to agree on this: if a rejected
        // certificate were classified as a final answer, one rotted host
        // would take down the whole chain even with a healthy endpoint
        // sitting next to it in the list.
        assertThat(
            RpcEndpointChain.isEndpointFailure(WalletException.TlsFailure("rejected"))
        ).isTrue()
    }
}
