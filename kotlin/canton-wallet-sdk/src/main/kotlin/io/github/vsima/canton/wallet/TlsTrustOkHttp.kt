// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet

import io.github.vsima.canton.TlsTrust
import javax.net.ssl.SSLContext
import okhttp3.OkHttpClient

/**
 * An [OkHttpClient] honouring the same trust anchors as the Ledger API
 * channel, for the off-ledger REST APIs — [ScanClient], [ValidatorClient]
 * and [TransferRegistryClient].
 *
 * Pinning the ledger channel while these keep the platform trust store is
 * a quiet asymmetry: balances, fee schedules and registry choice contexts
 * would still arrive over an interceptable connection. Configure trust
 * once and pass the result to all three.
 *
 * ```kotlin
 * val trust = TlsTrust(TlsTrust.TrustRoots.Certificates(listOf(operatorCa)))
 * val http = trust.okHttpClient()
 * val scan = ScanClient(scanUrl, http)
 * ```
 *
 * Returns [base] unchanged when the trust is the platform default.
 */
public fun TlsTrust.okHttpClient(base: OkHttpClient = OkHttpClient()): OkHttpClient {
    val manager = trustManager() ?: return base
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
    return base.newBuilder()
        .sslSocketFactory(context.socketFactory, manager)
        .apply {
            // Mirrors the channel: see TlsTrust.verifyHostname for why this
            // should stay on.
            if (!verifyHostname) hostnameVerifier { _, _ -> true }
        }
        .build()
}
