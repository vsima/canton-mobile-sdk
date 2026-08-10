// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Which certificates to trust when connecting to a participant over TLS.
 *
 * Mobile clients roam onto networks where TLS interception is a real
 * possibility — a corporate proxy or a debugging proxy whose CA the device
 * already trusts will otherwise be accepted like any other. Pinning the
 * trust anchors to the operator's own CA rejects those, because the
 * intercepting certificate does not chain to it.
 *
 * The default is [TrustRoots.SystemDefault]: the platform's trust store,
 * which on Android also means the app's Network Security Config — often
 * the better place to pin on Android, since a `network_security_config.xml`
 * covers every connection the app makes, not only the Ledger API.
 *
 * Pin **certificate authorities**, not leaves. Leaf certificates rotate
 * (every 90 days with Let's Encrypt) and a wallet talks to validators
 * whose rotation schedule it does not control, so a leaf pin is an outage
 * waiting for a renewal. This type deliberately offers no way to express
 * one.
 *
 * ```kotlin
 * val operatorCa = context.assets.open("operator-ca.der").readBytes()
 * CantonClientConfiguration(
 *     host = "validator.example.com",
 *     tlsTrust = TlsTrust(TlsTrust.TrustRoots.Certificates(listOf(operatorCa))),
 * )
 * ```
 *
 * @property trustRoots the anchors a server certificate must chain to.
 * @property verifyHostname whether the certificate must also match the
 *   host being connected to. Leave this on: turning it off accepts any
 *   certificate the pinned CA ever issued, for any name. It exists for
 *   self-signed deployment certificates issued without a matching SAN.
 */
public data class TlsTrust(
    val trustRoots: TrustRoots = TrustRoots.SystemDefault,
    val verifyHostname: Boolean = true,
) {
    public sealed interface TrustRoots {
        /** The platform trust store (plus Network Security Config on Android). */
        public data object SystemDefault : TrustRoots

        /**
         * Trust only these certificates and what they sign. Each entry is
         * one DER-encoded X.509 certificate — normally an operator's CA.
         */
        public data class Certificates(val der: List<ByteArray>) : TrustRoots {
            init {
                require(der.isNotEmpty()) { "pin at least one certificate, or use SystemDefault" }
            }

            override fun equals(other: Any?): Boolean =
                other is Certificates &&
                    der.size == other.der.size &&
                    der.indices.all { der[it].contentEquals(other.der[it]) }

            override fun hashCode(): Int = der.sumOf { it.contentHashCode() }
        }
    }

    /**
     * An [X509TrustManager] enforcing [trustRoots], or null to keep the
     * platform default. Used for the Ledger API channel; the wallet SDK's
     * `okHttpClient` extension builds on it so the off-ledger REST clients
     * can be given the same anchors.
     */
    public fun trustManager(): X509TrustManager? = when (val roots = trustRoots) {
        is TrustRoots.SystemDefault -> null
        is TrustRoots.Certificates -> {
            val factory = CertificateFactory.getInstance("X.509")
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
            roots.der.forEachIndexed { index, bytes ->
                val certificate = ByteArrayInputStream(bytes).use {
                    factory.generateCertificate(it) as X509Certificate
                }
                keyStore.setCertificateEntry("pinned-$index", certificate)
            }
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(keyStore) }
                .trustManagers
                .filterIsInstance<X509TrustManager>()
                .firstOrNull()
                ?: error("no X509TrustManager for the pinned certificates")
        }
    }
}
