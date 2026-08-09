// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton

import com.daml.ledger.api.v2.TransactionOuterClass
import com.daml.ledger.api.v2.UpdateServiceGrpcKt
import com.daml.ledger.api.v2.UpdateServiceOuterClass.GetUpdatesRequest
import com.daml.ledger.api.v2.UpdateServiceOuterClass.GetUpdatesResponse
import com.daml.ledger.api.v2.OffsetCheckpointOuterClass.OffsetCheckpoint
import com.google.protobuf.Any as ProtoAny
import com.google.protobuf.Duration as ProtoDuration
import com.google.rpc.RetryInfo
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import com.google.rpc.Status as RpcStatus

class UpdateStreamTest {

    /**
     * Emits offsets 1 (transaction) and 2 (checkpoint), dies with a
     * retryable error, then serves offset 3 and completes on the retry.
     */
    private class FlakyUpdateService : UpdateServiceGrpcKt.UpdateServiceCoroutineImplBase() {
        val requests = CopyOnWriteArrayList<GetUpdatesRequest>()

        override fun getUpdates(request: GetUpdatesRequest): Flow<GetUpdatesResponse> = flow {
            requests += request
            if (requests.size == 1) {
                emit(transaction(offset = 1))
                emit(checkpoint(offset = 2))
                throw retryableAborted()
            } else {
                emit(transaction(offset = 3))
            }
        }
    }

    /**
     * Every connection delivers one update at the next offset and then
     * immediately dies with a retryable error — progress on every attempt,
     * but never a connection that lives long enough to count as healthy.
     */
    private class DiesYoungUpdateService : UpdateServiceGrpcKt.UpdateServiceCoroutineImplBase() {
        val requests = CopyOnWriteArrayList<GetUpdatesRequest>()

        override fun getUpdates(request: GetUpdatesRequest): Flow<GetUpdatesResponse> = flow {
            requests += request
            emit(transaction(offset = request.beginExclusive + 1))
            throw retryableAborted()
        }
    }

    /**
     * Each connection delivers one update, stays alive for [aliveFor], and
     * then dies with a retryable error; the connection after [failures]
     * deaths completes normally.
     */
    private class HealthyThenFailService(
        private val failures: Int,
        private val aliveFor: Duration,
    ) : UpdateServiceGrpcKt.UpdateServiceCoroutineImplBase() {
        val requests = CopyOnWriteArrayList<GetUpdatesRequest>()

        override fun getUpdates(request: GetUpdatesRequest): Flow<GetUpdatesResponse> = flow {
            requests += request
            emit(transaction(offset = request.beginExclusive + 1))
            if (requests.size <= failures) {
                delay(aliveFor)
                throw retryableAborted()
            }
        }
    }

    private val subscription = UpdateSubscription(parties = listOf("alice::ns"), beginExclusive = 0)

    private val testPolicy = RetryPolicy(
        maxAttempts = 4,
        initialBackoff = 1.milliseconds,
        maxBackoff = 2.milliseconds,
    )

    private fun <T> withService(
        service: UpdateServiceGrpcKt.UpdateServiceCoroutineImplBase,
        policy: RetryPolicy = testPolicy,
        block: suspend (CantonClient) -> T,
    ): T {
        val name = InProcessServerBuilder.generateName()
        val server = InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start()
        val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
        return try {
            runBlocking { CantonClient(channel, retryPolicy = policy).use { block(it) } }
        } finally {
            server.shutdownNow()
        }
    }

    @Test
    fun `resumes after a mid-stream failure from the last received offset`() {
        val service = FlakyUpdateService()
        val updates = withService(service) { it.updates(subscription).toList() }

        assertEquals(listOf(1L, 2L, 3L), updates.map { it.offset })
        assertTrue(updates[0] is LedgerUpdate.Transaction)
        assertTrue(updates[1] is LedgerUpdate.Checkpoint)

        assertEquals(2, service.requests.size)
        assertEquals(0, service.requests[0].beginExclusive)
        // Resumed from the checkpoint offset, not the original begin —
        // no duplicates, no gaps.
        assertEquals(2, service.requests[1].beginExclusive)
        assertEquals(service.requests[0].updateFormat, service.requests[1].updateFormat)
    }

    @Test
    fun `does not retry non-retryable stream failures`() {
        val service = object : UpdateServiceGrpcKt.UpdateServiceCoroutineImplBase() {
            val requests = CopyOnWriteArrayList<GetUpdatesRequest>()
            override fun getUpdates(request: GetUpdatesRequest): Flow<GetUpdatesResponse> = flow {
                requests += request
                throw StatusRuntimeException(Status.PERMISSION_DENIED.withDescription("nope"))
            }
        }
        val e = assertFailsWith<CantonException> {
            withService(service) { it.updates(subscription).toList() }
        }
        assertEquals(Status.Code.PERMISSION_DENIED, e.error.grpcCode)
        assertEquals(1, service.requests.size)
    }

    @Test
    fun `a stream that progresses but keeps dying young exhausts the retry budget`() {
        // testPolicy keeps the default 10s healthy window, so no connection
        // here — one update, then an immediate death — ever resets the budget.
        val service = DiesYoungUpdateService()
        val received = mutableListOf<Long>()
        val e = assertFailsWith<CantonException> {
            withService(service) { client ->
                client.updates(subscription).collect { received += it.offset }
            }
        }
        assertEquals(Status.Code.ABORTED, e.error.grpcCode)
        // One connection per attempt in the budget — not an infinite loop —
        // each still resuming from the previous connection's last offset.
        assertEquals(testPolicy.maxAttempts, service.requests.size)
        assertEquals(listOf(0L, 1L, 2L, 3L), service.requests.map { it.beginExclusive })
        assertEquals(listOf(1L, 2L, 3L, 4L), received)
    }

    @Test
    fun `a connection that stays healthy past the window earns a fresh retry budget`() {
        // Window well below the per-connection lifetime and a budget of two
        // attempts: only healthy-window resets let the stream survive four
        // failures and reach the completing fifth connection.
        val policy = testPolicy.copy(maxAttempts = 2, streamHealthyWindow = 10.milliseconds)
        val service = HealthyThenFailService(failures = 4, aliveFor = 50.milliseconds)
        val updates = withService(service, policy) { it.updates(subscription).toList() }

        assertEquals(5, service.requests.size)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), updates.map { it.offset })
    }
}

private fun transaction(offset: Long): GetUpdatesResponse =
    GetUpdatesResponse.newBuilder()
        .setTransaction(
            TransactionOuterClass.Transaction.newBuilder()
                .setUpdateId("update-$offset")
                .setOffset(offset)
        )
        .build()

private fun checkpoint(offset: Long): GetUpdatesResponse =
    GetUpdatesResponse.newBuilder()
        .setOffsetCheckpoint(OffsetCheckpoint.newBuilder().setOffset(offset))
        .build()

private fun retryableAborted(): StatusRuntimeException {
    val rpcStatus = RpcStatus.newBuilder()
        .setCode(Status.Code.ABORTED.value())
        .addDetails(
            ProtoAny.newBuilder()
                .setTypeUrl("type.googleapis.com/google.rpc.RetryInfo")
                .setValue(
                    RetryInfo.newBuilder()
                        .setRetryDelay(ProtoDuration.newBuilder().setNanos(1_000_000))
                        .build()
                        .toByteString()
                )
        )
        .build()
    val trailers = Metadata().apply {
        put(
            Metadata.Key.of("grpc-status-details-bin", Metadata.BINARY_BYTE_MARSHALLER),
            rpcStatus.toByteArray(),
        )
    }
    return StatusRuntimeException(Status.ABORTED.withDescription("stream lost"), trailers)
}
