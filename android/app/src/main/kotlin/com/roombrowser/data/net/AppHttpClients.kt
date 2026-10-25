package com.roombrowser.data.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The app's own OkHttp clients.
 *
 * The engine's page traffic is NOT here — it belongs to the engine. What is here is the
 * traffic the app itself originates.
 *
 * The address probes deliberately never come through here either: the per-profile
 * address-conflict warning has to compare the REAL address a site would see, so it builds
 * its own bare client rather than inheriting anything this object configures.
 */
object AppHttpClients {

    /**
     * The agent's own network calls: model providers and anything a task fetches itself.
     *
     * ONLY THE CONNECT TIMEOUT IS SET. A read or call deadline would be a
     * correctness bug here, not a safety net: a non-streaming completion sends
     * nothing until the model has finished, and a long one legitimately holds
     * the socket for minutes, so any deadline short enough to catch a dead
     * connection would also abort a healthy slow answer. Connecting is the one
     * phase with no such excuse — a TCP handshake that has not completed in
     * twenty seconds is not going to.
     *
     * Callers that need a hard deadline on their OWN traffic (the skill
     * installer's tarball fetch) build one from this client with
     * `newBuilder()`, so the deadline stays scoped to the request that wanted
     * it instead of reaching every model call in the app.
     */
    fun agent(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .build()
}
