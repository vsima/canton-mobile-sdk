// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton

import com.google.rpc.ErrorInfo
import com.google.rpc.RequestInfo
import com.google.rpc.RetryInfo
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import com.google.rpc.Status as RpcStatus

/**
 * A decoded Canton Ledger API error.
 *
 * Canton attaches structured `google.rpc` error details to failed RPCs:
 * [ErrorInfo] carries the Canton error code (e.g. `CONTRACT_NOT_FOUND`,
 * `NOT_CONNECTED_TO_ANY_SYNCHRONIZER`), [RetryInfo] marks retryable errors
 * with a server-suggested backoff, and [RequestInfo] carries the correlation
 * id to quote when filing support requests against a validator operator.
 */
public data class CantonError(
    /** The gRPC status code of the failed call. */
    val grpcCode: Status.Code,
    /** Canton's self-service error code from `ErrorInfo.reason`, if present. */
    val errorCode: String?,
    /** Correlation id for tracing the error on the participant, if present. */
    val correlationId: String?,
    /** Whether the caller should retry (RetryInfo present, or a transient gRPC code). */
    val retryable: Boolean,
    /** Server-suggested minimum backoff before retrying, if provided. */
    val retryDelay: Duration?,
    /** Human-readable description from the server. */
    val description: String,
) {
    public companion object {
        private val STATUS_DETAILS_KEY: Metadata.Key<ByteArray> =
            Metadata.Key.of("grpc-status-details-bin", Metadata.BINARY_BYTE_MARSHALLER)

        /**
         * Decodes a [CantonError] from a gRPC failure, or returns null if
         * [throwable] is not a gRPC status error.
         */
        public fun from(throwable: Throwable): CantonError? {
            val (status, trailers) = when (throwable) {
                is StatusRuntimeException -> throwable.status to throwable.trailers
                is StatusException -> throwable.status to throwable.trailers
                else -> return null
            }

            val details = trailers?.get(STATUS_DETAILS_KEY)
                ?.let { bytes -> runCatching { RpcStatus.parseFrom(bytes) }.getOrNull() }
                ?.let(::decodeDetails)
                ?: DecodedDetails()

            return CantonError(
                grpcCode = status.code,
                errorCode = details.errorCode,
                correlationId = details.correlationId,
                retryable = details.retryDelay != null || status.code == Status.Code.UNAVAILABLE,
                retryDelay = details.retryDelay,
                description = status.description ?: throwable.message ?: status.code.name,
            )
        }

        /**
         * Decodes a [CantonError] from a `google.rpc.Status` proto — the
         * form carried by command `Completion` events when the ledger
         * rejects a command asynchronously (contention, time bounds). Uses
         * the same `google.rpc` detail decoding as gRPC failures.
         */
        public fun from(status: RpcStatus): CantonError {
            val grpcCode = Status.fromCodeValue(status.code).code
            val details = decodeDetails(status)
            return CantonError(
                grpcCode = grpcCode,
                errorCode = details.errorCode,
                correlationId = details.correlationId,
                retryable = details.retryDelay != null || grpcCode == Status.Code.UNAVAILABLE,
                retryDelay = details.retryDelay,
                description = status.message.ifEmpty { grpcCode.name },
            )
        }

        private data class DecodedDetails(
            val errorCode: String? = null,
            val correlationId: String? = null,
            val retryDelay: Duration? = null,
        )

        private fun decodeDetails(rpcStatus: RpcStatus): DecodedDetails {
            var errorCode: String? = null
            var correlationId: String? = null
            var retryDelay: Duration? = null
            for (detail in rpcStatus.detailsList) {
                when {
                    detail.typeUrl.endsWith("/google.rpc.ErrorInfo") ->
                        runCatching { ErrorInfo.parseFrom(detail.value) }.getOrNull()?.let {
                            errorCode = it.reason
                        }
                    detail.typeUrl.endsWith("/google.rpc.RetryInfo") ->
                        runCatching { RetryInfo.parseFrom(detail.value) }.getOrNull()?.let {
                            retryDelay = it.retryDelay.seconds.seconds + it.retryDelay.nanos.nanoseconds
                        }
                    detail.typeUrl.endsWith("/google.rpc.RequestInfo") ->
                        runCatching { RequestInfo.parseFrom(detail.value) }.getOrNull()?.let {
                            correlationId = it.requestId
                        }
                }
            }
            return DecodedDetails(errorCode, correlationId, retryDelay)
        }
    }
}

/**
 * A ledger failure decoded into a [CantonError] — either a gRPC failure
 * ([cause] set) or an asynchronous command rejection reported via a
 * completion event (no cause).
 */
public class CantonException(
    public val error: CantonError,
    cause: Throwable? = null,
) : RuntimeException(buildString {
    append(error.grpcCode)
    error.errorCode?.let { append('/').append(it) }
    append(": ").append(error.description)
    error.correlationId?.let { append(" (correlation id: ").append(it).append(')') }
}, cause)

/** Runs [block], rethrowing gRPC failures as [CantonException]. */
internal suspend fun <T> mapCantonErrors(block: suspend () -> T): T =
    try {
        block()
    } catch (t: Throwable) {
        throw CantonError.from(t)?.let { CantonException(it, t) } ?: t
    }
