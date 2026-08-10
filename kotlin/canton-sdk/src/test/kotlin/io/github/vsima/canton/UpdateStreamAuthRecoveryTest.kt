// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton

import com.daml.ledger.api.v2.OffsetCheckpointOuterClass
import com.daml.ledger.api.v2.UpdateServiceGrpcKt
import com.daml.ledger.api.v2.UpdateServiceOuterClass.GetUpdatesRequest
import com.daml.ledger.api.v2.UpdateServiceOuterClass.GetUpdatesResponse
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The reconnect behaviour LocalNet probing pinned down: the participant
 * terminates a stream whose token expired with PERMISSION_DENIED and no
 * RetryInfo. The stream must reconnect with a freshly fetched token when the
 * provider can mint one — and must NOT spin when it can't.
 */
class UpdateStreamAuthRecoveryTest {

    /** Fake update service: per-connection behaviour scripted by [script]. */
    private class FakeUpdates(
        private val script: (connection: Int) -> Flow<GetUpdatesResponse>,
    ) : UpdateServiceGrpcKt.UpdateServiceCoroutineImplBase() {
        val connections = AtomicInteger(0)
        override fun getUpdates(request: GetUpdatesRequest): Flow<GetUpdatesResponse> =
            script(connections.incrementAndGet())
    }

    private class AuthRecorder : ServerInterceptor {
        val tokens = CopyOnWriteArrayList<String?>()
        override fun <ReqT, RespT> interceptCall(
            call: ServerCall<ReqT, RespT>,
            headers: Metadata,
            next: ServerCallHandler<ReqT, RespT>,
        ): ServerCall.Listener<ReqT> {
            tokens += headers.get(
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
            )?.removePrefix("Bearer ")
            return next.startCall(call, headers)
        }
    }

    private fun checkpoint(offset: Long): GetUpdatesResponse =
        GetUpdatesResponse.newBuilder()
            .setOffsetCheckpoint(
                OffsetCheckpointOuterClass.OffsetCheckpoint.newBuilder().setOffset(offset)
            )
            .build()

    private fun <T> withClient(
        service: FakeUpdates,
        recorder: AuthRecorder,
        tokenProvider: suspend () -> String,
        body: suspend (CantonClient) -> T,
    ): T {
        val name = InProcessServerBuilder.generateName()
        val server = InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(service)
            .intercept(recorder)
            .build()
            .start()
        val channel = InProcessChannelBuilder.forName(name).build()
        return try {
            CantonClient(channel, accessTokenProvider = tokenProvider).use { client ->
                runBlocking { withTimeout(15_000) { body(client) } }
            }
        } finally {
            server.shutdownNow()
        }
    }

    @Test
    fun `stream survives an auth termination when the provider mints a fresh token`() {
        val minted = AtomicInteger(0)
        val service = FakeUpdates { connection ->
            when (connection) {
                1 -> flow { throw StatusException(Status.PERMISSION_DENIED) }
                else -> flow { emit(checkpoint(7)) }
            }
        }
        val recorder = AuthRecorder()

        val update: LedgerUpdate =
            withClient<LedgerUpdate>(service, recorder, { "token-${minted.incrementAndGet()}" }) {
                it.updates(UpdateSubscription(listOf("p"), beginExclusive = 0)).first()
            }

        assertEquals(7, update.offset, "the update after recovery must reach the consumer")
        assertEquals(2, service.connections.get(), "one reconnect, no spinning")
        assertEquals(
            listOf<String?>("token-1", "token-2"),
            recorder.tokens.toList(),
            "the reconnect must carry the freshly minted token",
        )
    }

    @Test
    fun `stream does not spin when the provider has nothing fresher`() {
        val fetches = AtomicInteger(0)
        val service = FakeUpdates {
            flow { throw StatusException(Status.PERMISSION_DENIED) }
        }
        val recorder = AuthRecorder()

        val failure = assertFailsWith<CantonException> {
            withClient<LedgerUpdate>(service, recorder, { "static-token".also { fetches.incrementAndGet() } }) {
                it.updates(UpdateSubscription(listOf("p"), beginExclusive = 0)).first()
            }
        }

        assertTrue(failure.error.isAuthFailure)
        assertEquals(
            1,
            service.connections.get(),
            "an unchanged token must fail without reconnecting",
        )
    }

    @Test
    fun `a second auth failure without a healthy connection propagates`() {
        val minted = AtomicInteger(0)
        val service = FakeUpdates {
            flow { throw StatusException(Status.PERMISSION_DENIED) }
        }
        val recorder = AuthRecorder()

        val failure = assertFailsWith<CantonException> {
            withClient<LedgerUpdate>(service, recorder, { "token-${minted.incrementAndGet()}" }) {
                it.updates(UpdateSubscription(listOf("p"), beginExclusive = 0)).first()
            }
        }

        assertTrue(failure.error.isAuthFailure)
        assertEquals(
            2,
            service.connections.get(),
            "exactly one recovery attempt even though every fetch differs",
        )
    }
}
