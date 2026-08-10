// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton

import com.daml.ledger.api.v2.VersionServiceGrpcKt
import com.daml.ledger.api.v2.VersionServiceOuterClass.GetLedgerApiVersionRequest
import com.daml.ledger.api.v2.VersionServiceOuterClass.GetLedgerApiVersionResponse
import io.grpc.Server
import io.grpc.TlsServerCredentials
import io.grpc.okhttp.OkHttpServerBuilder
import java.io.File
import java.net.InetSocketAddress
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Trust-root pinning against a real TLS server: the fixtures in
 * testdata/tls/ (regenerate with tools/gen-test-certs.sh) give a CA, a
 * server leaf it signed, and a second unrelated CA.
 *
 * The negative cases carry the weight. A test that only shows the right
 * pin connecting would pass just as happily if pinning were ignored
 * altogether — it is the system-default case failing that proves the
 * pinned anchors are what the handshake is actually using.
 */
class TlsTrustTest {

    private val fixtures = File("../../testdata/tls")
    private fun der(name: String) = File(fixtures, name).readBytes()

    private fun startServer(): Server {
        val credentials = TlsServerCredentials.newBuilder()
            .keyManager(File(fixtures, "server.crt"), File(fixtures, "server.key"))
            .build()
        return OkHttpServerBuilder
            .forPort(InetSocketAddress("127.0.0.1", 0), credentials)
            .addService(object : VersionServiceGrpcKt.VersionServiceCoroutineImplBase() {
                override suspend fun getLedgerApiVersion(
                    request: GetLedgerApiVersionRequest,
                ): GetLedgerApiVersionResponse =
                    GetLedgerApiVersionResponse.newBuilder().setVersion("test-1.2.3").build()
            })
            .build()
            .start()
    }

    private fun <T> withServer(body: (port: Int) -> T): T {
        val server = startServer()
        return try {
            body(server.port)
        } finally {
            server.shutdownNow()
        }
    }

    /** The trust that must work, used as the control in rejection tests. */
    private fun pinned() = TlsTrust(TlsTrust.TrustRoots.Certificates(listOf(der("ca.der"))))

    /**
     * Asserts the connection is refused *and* that the same server accepts
     * a correctly pinned client — otherwise a rejection test would pass
     * against a server that never started.
     */
    private fun assertRejected(port: Int, trust: TlsTrust, host: String = "localhost") {
        CantonClient(configuration(port, trust).copy(host = host)).use { client ->
            assertFailsWith<CantonException> { runBlocking { client.ledgerApiVersion() } }
        }
        CantonClient(configuration(port, pinned())).use { control ->
            assertEquals(
                "test-1.2.3",
                runBlocking { control.ledgerApiVersion() },
                "control: the server must be reachable, or the rejection proves nothing",
            )
        }
    }

    private fun configuration(port: Int, trust: TlsTrust) = CantonClientConfiguration(
        host = "localhost",
        port = port,
        useTls = true,
        tlsTrust = trust,
        retryPolicy = RetryPolicy(maxAttempts = 1),
    )

    @Test
    fun `pinning the issuing CA connects`(): Unit = withServer { port ->
        val trust = TlsTrust(TlsTrust.TrustRoots.Certificates(listOf(der("ca.der"))))
        CantonClient(configuration(port, trust)).use {
            assertEquals("test-1.2.3", runBlocking { it.ledgerApiVersion() })
        }
    }

    @Test
    fun `pinning an unrelated CA rejects the server`(): Unit = withServer { port ->
        assertRejected(port, TlsTrust(TlsTrust.TrustRoots.Certificates(listOf(der("other-ca.der")))))
    }

    @Test
    fun `the platform trust store rejects the fixture server`(): Unit = withServer { port ->
        // Proves the pin is load-bearing: the same server the pinned client
        // reaches in the control is untrusted without it.
        assertRejected(port, TlsTrust())
    }

    @Test
    fun `a pinned CA still enforces the hostname`(): Unit = withServer { port ->
        // The fixture leaf carries SANs for localhost and 127.0.0.1 only;
        // any other name resolving here must still be rejected, or a pinned
        // CA would become a licence to impersonate every host it signs.
        assertRejected(port, pinned(), host = "not-the-server.test")
    }

    @Test
    fun `the fixture leaf has not expired`() {
        // Apple caps TLS server certificates at 398 days, so this one is
        // short-dated by necessity and needs periodic regeneration. Fail
        // with the instruction rather than as a baffling handshake error.
        val leaf = CertificateFactory.getInstance("X.509")
            .generateCertificate(File(fixtures, "server.crt").inputStream()) as X509Certificate
        assertTrue(
            leaf.notAfter.after(Date()),
            "the TLS test fixture expired on ${leaf.notAfter} — regenerate with tools/gen-test-certs.sh",
        )
    }

    @Test
    fun `trustManager is null for the platform default and present when pinned`() {
        assertNull(TlsTrust().trustManager())
        val pinned = TlsTrust(TlsTrust.TrustRoots.Certificates(listOf(der("ca.der"))))
        val manager = assertNotNull(pinned.trustManager())
        assertEquals(1, manager.acceptedIssuers.size)
        assertEquals("CN=Canton SDK Test CA", manager.acceptedIssuers.single().subjectX500Principal.name)
    }

    @Test
    fun `pinning requires at least one certificate`() {
        assertFailsWith<IllegalArgumentException> {
            TlsTrust.TrustRoots.Certificates(emptyList())
        }
    }
}
