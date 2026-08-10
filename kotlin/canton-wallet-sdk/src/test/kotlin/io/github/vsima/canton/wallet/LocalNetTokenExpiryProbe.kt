// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet

import com.daml.ledger.api.v2.StateServiceGrpcKt
import com.daml.ledger.api.v2.StateServiceOuterClass.GetLedgerEndRequest
import io.github.vsima.canton.CantonClient
import io.github.vsima.canton.CantonClientConfiguration
import io.github.vsima.canton.UpdateSubscription
import io.grpc.Metadata
import io.grpc.StatusException
import io.grpc.okhttp.OkHttpChannelBuilder
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Live verification of token-expiry behaviour against a real participant.
 *
 * What probing established (Aug 2026, Splice LocalNet 0.7.1 / Canton 3.5):
 *
 * - An expired token is rejected on ADMISSION with `UNAUTHENTICATED` and no
 *   RetryInfo — not retryable under the ordinary policy.
 * - An ALREADY-RUNNING idle stream is NOT aborted when its token lapses on
 *   this participant (observed alive 70s past `exp`). Deployments with
 *   ongoing auth enforcement abort with Canton's `ACCESS_TOKEN_EXPIRED`;
 *   the client treats that as an auth failure too.
 * - `PERMISSION_DENIED` here means an authorization problem (e.g. reading
 *   for a party this participant doesn't host), not expiry.
 *
 * The headline test proves the recovery loop end to end on the enforced
 * path: a stream whose first connection presents an expired token must
 * refresh through the provider, reconnect, and come up healthy.
 */
@EnabledIfEnvironmentVariable(named = "SPLICE_LOCALNET", matches = "1")
class LocalNetTokenExpiryProbe {

    private val host = System.getenv("SPLICE_LEDGER_HOST") ?: "127.0.0.1"
    private val port = (System.getenv("SPLICE_LEDGER_PORT") ?: "2901").toInt()
    private val adminUser = "ledger-api-user"
    private val audience = "https://canton.network.global"

    /** Unsafe HS256 LocalNet JWT with an explicit `exp`. */
    private fun jwt(sub: String, expEpochSecond: Long): String {
        fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val header = b64("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload =
            b64("""{"sub":"$sub","aud":"$audience","exp":$expEpochSecond}""".toByteArray())
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("unsafe".toByteArray(), "HmacSHA256"))
        return "$header.$payload.${b64(mac.doFinal("$header.$payload".toByteArray()))}"
    }

    private fun bearer(token: String): io.grpc.CallCredentials =
        object : io.grpc.CallCredentials() {
            override fun applyRequestMetadata(
                requestInfo: RequestInfo,
                appExecutor: java.util.concurrent.Executor,
                applier: MetadataApplier,
            ) {
                val headers = Metadata()
                headers.put(
                    Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                    "Bearer $token",
                )
                applier.apply(headers)
            }
        }

    private suspend fun localParty(): String {
        val channel = OkHttpChannelBuilder.forAddress(host, port).usePlaintext().build()
        return try {
            com.daml.ledger.api.v2.admin.PartyManagementServiceGrpcKt
                .PartyManagementServiceCoroutineStub(channel)
                .withCallCredentials(
                    bearer(jwt(adminUser, System.currentTimeMillis() / 1000 + 300))
                )
                .listKnownParties(
                    com.daml.ledger.api.v2.admin.PartyManagementServiceOuterClass
                        .ListKnownPartiesRequest.getDefaultInstance()
                ).partyDetailsList.first { it.isLocal }.party
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun expiredTokenFailsNewCallsAsUnauthenticated() = runBlocking {
        val expired = jwt(adminUser, System.currentTimeMillis() / 1000 - 60)
        val channel = OkHttpChannelBuilder.forAddress(host, port).usePlaintext().build()
        try {
            val thrown = runCatching {
                StateServiceGrpcKt.StateServiceCoroutineStub(channel)
                    .withCallCredentials(bearer(expired))
                    .getLedgerEnd(GetLedgerEndRequest.getDefaultInstance())
            }.exceptionOrNull()

            val status = (thrown as? StatusException)?.status
            assertEquals(io.grpc.Status.Code.UNAUTHENTICATED, status?.code)
            val error = io.github.vsima.canton.CantonError.from(thrown!!)
            assertEquals(false, error?.retryable, "no RetryInfo rides on auth failures")
            assertEquals(true, error?.isAuthFailure)
        } finally {
            channel.shutdownNow()
        }
    }

    /**
     * End-to-end recovery on the enforced path: connection 1 presents an
     * expired token and is rejected UNAUTHENTICATED; the client refreshes
     * through the provider, reconnects, and the stream must be healthy —
     * still open, no error — after outliving the healthy window.
     */
    @Test
    fun streamRecoversFromAnExpiredTokenThroughTheProvider() = runBlocking {
        val fetches = AtomicInteger(0)
        val nowSeconds = { System.currentTimeMillis() / 1000 }

        // Offset and party come from raw stubs so the client under test
        // performs exactly one RPC: the stream connect.
        val party = localParty()
        val end = run {
            val channel = OkHttpChannelBuilder.forAddress(host, port).usePlaintext().build()
            try {
                StateServiceGrpcKt.StateServiceCoroutineStub(channel)
                    .withCallCredentials(bearer(jwt(adminUser, nowSeconds() + 300)))
                    .getLedgerEnd(GetLedgerEndRequest.getDefaultInstance()).offset
            } finally {
                channel.shutdownNow()
            }
        }

        val client = CantonClient(
            CantonClientConfiguration(
                host = host,
                port = port,
                useTls = false,
                accessTokenProvider = {
                    // The stream's first connection gets an EXPIRED token;
                    // the recovery refresh gets a valid one.
                    when (fetches.incrementAndGet()) {
                        1 -> jwt(adminUser, nowSeconds() - 60)
                        else -> jwt(adminUser, nowSeconds() + 3600)
                    }
                },
            )
        )
        client.use { canton ->

            var streamFailure: Throwable? = null
            val completed = withTimeoutOrNull(15_000) {
                try {
                    canton.updates(UpdateSubscription(listOf(party), beginExclusive = end))
                        .collect { }
                } catch (t: kotlinx.coroutines.CancellationException) {
                    throw t // the hold-open timer, not a stream failure
                } catch (t: Throwable) {
                    streamFailure = t
                }
            }

            assertNull(
                streamFailure,
                "the stream must recover from the expired token, but died: $streamFailure",
            )
            assertNull(completed, "the recovered stream must still be open when time is up")
            assertEquals(
                2,
                fetches.get(),
                "expected exactly one recovery refresh after the expired token",
            )
            println("PROBE recovered from expired token; fetches=${fetches.get()}")
        }
    }
}
