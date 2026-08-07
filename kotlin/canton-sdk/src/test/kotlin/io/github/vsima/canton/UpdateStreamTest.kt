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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
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
    }

    private val subscription = UpdateSubscription(parties = listOf("alice::ns"), beginExclusive = 0)

    private val testPolicy = RetryPolicy(
        maxAttempts = 4,
        initialBackoff = 1.milliseconds,
        maxBackoff = 2.milliseconds,
    )

    private fun <T> withService(
        service: UpdateServiceGrpcKt.UpdateServiceCoroutineImplBase,
        block: suspend (CantonClient) -> T,
    ): T {
        val name = InProcessServerBuilder.generateName()
        val server = InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start()
        val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
        return try {
            runBlocking { CantonClient(channel, retryPolicy = testPolicy).use { block(it) } }
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
}
