package com.roombrowser.engine.webview

import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * The two native entry points a page script can reach in the WebView edition,
 * and how their calls are spelled on the way up.
 *
 * WHY THE ENGINE OWNS THESE AT ALL. Above the facade the app may not name a
 * `WebView`: `EngineSession.view` is a `View` and hosts are forbidden from
 * downcasting it, so `addJavascriptInterface` is not something the app can
 * still reach. The two `@JavascriptInterface` objects the app used to build in
 * `BrowserViewModel.createWebView` (BrowserViewModel.kt:1304-1326) therefore
 * move down into the session and report upward through
 * [com.roombrowser.engine.EngineSessionListener.onPageMessage], which is the
 * facade's stated transport replacement for `@JavascriptInterface` and
 * GeckoView ports alike.
 *
 * WHAT MOVED UP WITH THE CALLBACKS, and must NOT be re-implemented here: the
 * host validation, the rate limiting and the "no argument is ever logged"
 * discipline that `RoomVaultBridge` (WebClients.kt:736-826) and `WalletBridge`
 * (wallet/dapp/WalletBridge.kt:91) apply. Those are decisions about the user's data and
 * they belong above the facade, where the session's own
 * [com.roombrowser.engine.EngineSession.url] is available as the anchor -- the
 * facade says so in as many words. All that is left down here is the transport.
 *
 * THE PAGE-VISIBLE COLLISION SURFACE IS PRESERVED EXACTLY. Each object is a
 * SEPARATE `addJavascriptInterface` registration rather than one object
 * installed twice, because registering one class under two names would expose
 * every public method under both -- `window.RoomVault.request` would exist,
 * and `window.RoomWallet.requestCredentials` with it. The app's contract is
 * that each global exposes only its own two (or one) methods.
 */
internal object WebViewPageChannels {

    /** The global JS sees for the password-manager bridge. */
    const val VAULT_INTERFACE = "RoomVault"

    /** The global JS sees for the wallet dApp provider bridge. */
    const val WALLET_INTERFACE = "RoomWallet"

    /**
     * The `channel` an upward message carries: the name of the native entry
     * point that was called, which is the same string the page sees.
     *
     * INTERPRETATION, NOT A PORT. The facade deliberately does not fix the
     * channel vocabulary -- it names the two entry points in prose as
     * `RoomVaultNative` / `RoomWalletNative` and leaves the wire to the
     * editions, because the GeckoView edition's channel is whatever its
     * page-world script chooses to post. The WebView edition's only defensible
     * choice is the name the page already calls, since anything else would be
     * a third vocabulary neither edition's scripts use.
     */
    const val VAULT_CHANNEL = VAULT_INTERFACE
    const val WALLET_CHANNEL = WALLET_INTERFACE
}

/**
 * `window.RoomVault`: the password manager's page-callable half.
 *
 * The two methods are the app's own protocol (`RoomVaultBridge`,
 * WebClients.kt:763-797) and their signatures are reproduced exactly, because
 * the injected script `RoomVaultScript.SCRIPT` calls them by name and by
 * arity: `requestCredentials(location.host, location.href)` and
 * `reportCredential(location.host, username, password)`. The PAGE-VISIBLE wire
 * does not change by a byte; what changes is only where the arguments go.
 *
 * THE PAYLOAD ENVELOPE IS AN INTERPRETATION. `onPageMessage` carries one
 * `channel` and one `payload` string, while these two methods carry two and
 * three arguments respectively -- and the same channel has to serve both. So
 * each call is encoded as a JSON object naming the method and its arguments:
 *
 *   {"method":"requestCredentials","host":"...","href":"..."}
 *   {"method":"reportCredential","host":"...","username":"...","password":"..."}
 *
 * The alternative -- a bare positional JSON array -- was rejected because the
 * receiving side could not tell the two calls apart without counting
 * arguments. Nothing is lost either way: every argument arrives as the exact
 * string the page passed, or as JSON null when the page passed null (which the
 * native bridge also had to tolerate; `requestCredentials(null, ...)` returned
 * early).
 */
internal class VaultPageBridge(
    private val deliver: (channel: String, payload: String) -> Unit
) {

    @JavascriptInterface
    fun requestCredentials(host: String?, href: String?) {
        deliver(
            WebViewPageChannels.VAULT_CHANNEL,
            envelope("requestCredentials", "host" to host, "href" to href)
        )
    }

    @JavascriptInterface
    fun reportCredential(host: String?, username: String?, password: String?) {
        deliver(
            WebViewPageChannels.VAULT_CHANNEL,
            envelope(
                "reportCredential",
                "host" to host,
                "username" to username,
                "password" to password
            )
        )
    }

    private fun envelope(method: String, vararg args: Pair<String, String?>): String {
        val json = JSONObject().put("method", method)
        args.forEach { (name, value) -> json.put(name, value ?: JSONObject.NULL) }
        return json.toString()
    }
}

/**
 * `window.RoomWallet`: the wallet dApp provider's page-callable half.
 *
 * ONE method, and it already takes a single string: the page's request is a
 * JSON envelope the provider script built (`WalletBridgeProtocol`), and
 * `WalletBridge.request` (wallet/dapp/WalletBridge.kt:140) hands that same string
 * straight to the parse. So this side needs no argument packing, only the one
 * field that tells the receiving side which method was called.
 *
 * RETURN VALUE IS THE EMPTY STRING, exactly as the app's bridge returns it:
 * answers are asynchronous (the async-response pattern), and a synchronous
 * return would be evaluated on WebView's JavaBridge thread where it is
 * unreliable. Callers must not be given the impression that a value means
 * anything.
 */
internal class WalletPageBridge(
    private val deliver: (channel: String, payload: String) -> Unit
) {

    @JavascriptInterface
    fun request(payload: String?): String {
        val json = JSONObject()
            .put("method", "request")
            .put("payload", payload ?: JSONObject.NULL)
            .toString()
        deliver(WebViewPageChannels.WALLET_CHANNEL, json)
        return ""
    }
}
