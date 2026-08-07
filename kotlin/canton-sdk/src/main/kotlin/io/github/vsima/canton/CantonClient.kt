package io.github.vsima.canton

import com.daml.ledger.api.v2.CommandServiceGrpcKt
import com.daml.ledger.api.v2.CommandServiceOuterClass
import com.daml.ledger.api.v2.TransactionOuterClass
import com.daml.ledger.api.v2.VersionServiceGrpcKt
import com.daml.ledger.api.v2.VersionServiceOuterClass.GetLedgerApiVersionRequest
import io.grpc.CallCredentials
import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.AbstractStub
import java.io.Closeable
import java.util.concurrent.TimeUnit

/**
 * A client for the Canton Ledger API.
 *
 * ```kotlin
 * val client = CantonClient(
 *     CantonClientConfiguration(host = "validator.example.com")
 * )
 * val version = client.ledgerApiVersion()
 * ```
 *
 * The channel-based constructor is useful for tests (in-process transport)
 * and for callers who need full control over the [ManagedChannel].
 */
public class CantonClient(
    private val channel: ManagedChannel,
    callCredentials: CallCredentials? = null,
    private val retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
) : Closeable {

    public constructor(configuration: CantonClientConfiguration) : this(
        configuration.buildChannel(),
        configuration.accessTokenProvider?.let(::BearerTokenCallCredentials),
        configuration.retryPolicy,
    )

    private val versionService =
        VersionServiceGrpcKt.VersionServiceCoroutineStub(channel).withAuth(callCredentials)
    private val commandService =
        CommandServiceGrpcKt.CommandServiceCoroutineStub(channel).withAuth(callCredentials)

    /**
     * Fetches the Ledger API version from the participant.
     *
     * @throws CantonException if the call fails with a gRPC error.
     */
    public suspend fun ledgerApiVersion(): String = withRetries(retryPolicy) {
        mapCantonErrors {
            versionService
                .getLedgerApiVersion(GetLedgerApiVersionRequest.getDefaultInstance())
                .version
        }
    }

    /**
     * Submits [submission] and waits for it to be committed, returning the
     * update id. Retryable failures are retried with the same command id, so
     * the participant deduplicates re-executions.
     *
     * @throws CantonException if the submission ultimately fails.
     */
    public suspend fun submitAndWait(submission: CommandSubmission): String =
        withRetries(retryPolicy) {
            mapCantonErrors {
                commandService.submitAndWait(
                    CommandServiceOuterClass.SubmitAndWaitRequest.newBuilder()
                        .setCommands(submission.toProto())
                        .build()
                ).updateId
            }
        }

    /**
     * Submits [submission], waits for it to be committed, and returns the
     * resulting transaction (flat/ACS-delta shape, filtered to the
     * submitting parties). Retries reuse the same command id.
     *
     * @throws CantonException if the submission ultimately fails.
     */
    public suspend fun submitAndWaitForTransaction(
        submission: CommandSubmission,
    ): TransactionOuterClass.Transaction =
        withRetries(retryPolicy) {
            mapCantonErrors {
                commandService.submitAndWaitForTransaction(
                    CommandServiceOuterClass.SubmitAndWaitForTransactionRequest.newBuilder()
                        .setCommands(submission.toProto())
                        .build()
                ).transaction
            }
        }

    override fun close() {
        channel.shutdown()
        if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
            channel.shutdownNow()
        }
    }
}

private fun <S : AbstractStub<S>> S.withAuth(credentials: CallCredentials?): S =
    if (credentials != null) withCallCredentials(credentials) else this

private fun CantonClientConfiguration.buildChannel(): ManagedChannel =
    OkHttpChannelBuilder.forAddress(host, port)
        .apply { if (useTls) useTransportSecurity() else usePlaintext() }
        .build()
