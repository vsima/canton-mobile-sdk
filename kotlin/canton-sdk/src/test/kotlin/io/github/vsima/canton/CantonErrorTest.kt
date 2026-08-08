// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton

import com.daml.ledger.api.v2.VersionServiceGrpcKt
import com.daml.ledger.api.v2.VersionServiceOuterClass.GetLedgerApiVersionRequest
import com.daml.ledger.api.v2.VersionServiceOuterClass.GetLedgerApiVersionResponse
import com.google.protobuf.Any as ProtoAny
import com.google.protobuf.Duration as ProtoDuration
import com.google.protobuf.MessageLite
import com.google.rpc.ErrorInfo
import com.google.rpc.RequestInfo
import com.google.rpc.RetryInfo
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import com.google.rpc.Status as RpcStatus

class CantonErrorTest {

    private object FailingVersionService : VersionServiceGrpcKt.VersionServiceCoroutineImplBase() {
        override suspend fun getLedgerApiVersion(
            request: GetLedgerApiVersionRequest,
        ): GetLedgerApiVersionResponse {
            val rpcStatus = RpcStatus.newBuilder()
                .setCode(Status.Code.ABORTED.value())
                .setMessage("CONTENTION_ON_CONTRACT: contract is locked")
                .addDetails(
                    pack(
                        "google.rpc.ErrorInfo",
                        ErrorInfo.newBuilder().setReason("CONTENTION_ON_CONTRACT").setDomain("participant").build(),
                    )
                )
                .addDetails(
                    pack(
                        "google.rpc.RetryInfo",
                        RetryInfo.newBuilder()
                            .setRetryDelay(ProtoDuration.newBuilder().setNanos(250_000_000))
                            .build(),
                    )
                )
                .addDetails(
                    pack(
                        "google.rpc.RequestInfo",
                        RequestInfo.newBuilder().setRequestId("corr-123").build(),
                    )
                )
                .build()
            val trailers = Metadata().apply {
                put(
                    Metadata.Key.of("grpc-status-details-bin", Metadata.BINARY_BYTE_MARSHALLER),
                    rpcStatus.toByteArray(),
                )
            }
            throw StatusRuntimeException(
                Status.ABORTED.withDescription("CONTENTION_ON_CONTRACT: contract is locked"),
                trailers,
            )
        }
    }

    @Test
    fun `decodes canton rich error details from a failed call`() = runBlocking {
        val name = InProcessServerBuilder.generateName()
        val server = InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(FailingVersionService)
            .build()
            .start()
        val channel = InProcessChannelBuilder.forName(name).directExecutor().build()

        try {
            val exception = assertFailsWith<CantonException> {
                CantonClient(channel).use { it.ledgerApiVersion() }
            }
            val error = exception.error
            assertEquals(Status.Code.ABORTED, error.grpcCode)
            assertEquals("CONTENTION_ON_CONTRACT", error.errorCode)
            assertEquals("corr-123", error.correlationId)
            assertTrue(error.retryable)
            assertEquals(250.milliseconds, error.retryDelay)
        } finally {
            server.shutdownNow()
        }
    }

    @Test
    fun `plain grpc errors decode without details`() {
        val error = CantonError.from(
            StatusRuntimeException(Status.UNAVAILABLE.withDescription("connection refused"))
        )
        checkNotNull(error)
        assertEquals(Status.Code.UNAVAILABLE, error.grpcCode)
        assertNull(error.errorCode)
        assertTrue(error.retryable) // UNAVAILABLE is transient even without RetryInfo
        assertNull(error.retryDelay)
    }

    @Test
    fun `non-grpc errors are not decoded`() {
        assertNull(CantonError.from(IllegalStateException("boom")))
    }

    /** Completion events carry the rejection as a raw `google.rpc.Status`. */
    @Test
    fun `decodes canton rich error details from a completion status proto`() {
        val error = CantonError.from(
            RpcStatus.newBuilder()
                .setCode(Status.Code.ABORTED.value())
                .setMessage("CONTENTION_ON_CONTRACT: contract is locked")
                .addDetails(
                    pack(
                        "google.rpc.ErrorInfo",
                        ErrorInfo.newBuilder().setReason("CONTENTION_ON_CONTRACT").setDomain("participant").build(),
                    )
                )
                .addDetails(
                    pack(
                        "google.rpc.RetryInfo",
                        RetryInfo.newBuilder()
                            .setRetryDelay(ProtoDuration.newBuilder().setNanos(250_000_000))
                            .build(),
                    )
                )
                .addDetails(
                    pack(
                        "google.rpc.RequestInfo",
                        RequestInfo.newBuilder().setRequestId("corr-123").build(),
                    )
                )
                .build()
        )
        assertEquals(Status.Code.ABORTED, error.grpcCode)
        assertEquals("CONTENTION_ON_CONTRACT", error.errorCode)
        assertEquals("corr-123", error.correlationId)
        assertTrue(error.retryable)
        assertEquals(250.milliseconds, error.retryDelay)
        assertEquals("CONTENTION_ON_CONTRACT: contract is locked", error.description)
    }

    private companion object {
        fun pack(type: String, message: MessageLite): ProtoAny =
            ProtoAny.newBuilder()
                .setTypeUrl("type.googleapis.com/$type")
                .setValue(message.toByteString())
                .build()
    }
}
