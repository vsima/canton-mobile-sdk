// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton

import com.daml.ledger.api.v2.CommandServiceGrpcKt
import com.daml.ledger.api.v2.CommandServiceOuterClass
import com.daml.ledger.api.v2.StateServiceGrpcKt
import com.daml.ledger.api.v2.StateServiceOuterClass.GetActiveContractsRequest
import com.daml.ledger.api.v2.StateServiceOuterClass.GetLedgerEndRequest
import com.daml.ledger.api.v2.TransactionOuterClass
import com.daml.ledger.api.v2.UpdateServiceGrpcKt
import com.daml.ledger.api.v2.VersionServiceGrpcKt
import com.daml.ledger.api.v2.VersionServiceOuterClass.GetLedgerApiVersionRequest
import io.grpc.CallCredentials
import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.AbstractStub
import java.io.Closeable
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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
    accessTokenProvider: (suspend () -> String)? = null,
) : Closeable {

    public constructor(configuration: CantonClientConfiguration) : this(
        configuration.buildChannel(),
        null,
        configuration.retryPolicy,
        configuration.accessTokenProvider,
    )

    /** Bridges suspending token fetches into gRPC's async credentials API. */
    private val authScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val tokenCache: CachingTokenProvider? =
        accessTokenProvider?.let { CachingTokenProvider(fetch = it) }

    private val credentials: CallCredentials? =
        callCredentials ?: tokenCache?.let { BearerTokenCallCredentials(authScope, it::token) }

    private val versionService =
        VersionServiceGrpcKt.VersionServiceCoroutineStub(channel).withAuth(credentials)
    private val commandService =
        CommandServiceGrpcKt.CommandServiceCoroutineStub(channel).withAuth(credentials)
    private val updateService =
        UpdateServiceGrpcKt.UpdateServiceCoroutineStub(channel).withAuth(credentials)
    private val stateService =
        StateServiceGrpcKt.StateServiceCoroutineStub(channel).withAuth(credentials)

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

    /**
     * The participant's current ledger end offset — the natural
     * [UpdateSubscription.beginExclusive] for a fresh subscription.
     *
     * @throws CantonException if the call fails with a gRPC error.
     */
    public suspend fun ledgerEnd(): Long = withRetries(retryPolicy) {
        mapCantonErrors {
            stateService.getLedgerEnd(GetLedgerEndRequest.getDefaultInstance()).offset
        }
    }

    /**
     * The active contract set visible to [parties] at [activeAtOffset]
     * (all templates). Retryable failures restart the snapshot from scratch,
     * so the result is never partial.
     *
     * @throws CantonException if the call ultimately fails.
     */
    public suspend fun activeContracts(
        parties: List<String>,
        activeAtOffset: Long,
        verbose: Boolean = true,
    ): List<ActiveContract> = withRetries(retryPolicy) {
        mapCantonErrors {
            val request = GetActiveContractsRequest.newBuilder()
                .setActiveAtOffset(activeAtOffset)
                .setEventFormat(wildcardEventFormat(parties, verbose))
                .build()
            val contracts = mutableListOf<ActiveContract>()
            stateService.getActiveContracts(request).collect { response ->
                if (response.hasActiveContract()) {
                    val entry = response.activeContract
                    contracts += ActiveContract(
                        createdEvent = entry.createdEvent,
                        synchronizerId = entry.synchronizerId,
                        reassignmentCounter = entry.reassignmentCounter,
                    )
                }
            }
            contracts
        }
    }

    /**
     * The active contract set at the current ledger end — the starting point
     * for a full state sync. Apply [ActiveContractsSnapshot.contracts], then
     * consume [updates] from [ActiveContractsSnapshot.offset]:
     *
     * ```kotlin
     * val snapshot = client.activeContractsSnapshot(listOf(party))
     * // apply snapshot.contracts ...
     * client.updates(UpdateSubscription(listOf(party), beginExclusive = snapshot.offset))
     *     .collect { /* deltas, gap-free */ }
     * ```
     */
    public suspend fun activeContractsSnapshot(
        parties: List<String>,
        verbose: Boolean = true,
    ): ActiveContractsSnapshot {
        val offset = ledgerEnd()
        return ActiveContractsSnapshot(offset, activeContracts(parties, offset, verbose))
    }

    /**
     * Streams ledger updates for [subscription], transparently reconnecting
     * on retryable failures and resuming from the offset of the last
     * received update — consumers see one uninterrupted, gap-free stream.
     * The retry budget resets only once a connection has proven healthy:
     * it delivered at least one update and stayed alive for
     * [RetryPolicy.streamHealthyWindow]. Shorter-lived connections keep
     * escalating the backoff until [RetryPolicy.maxAttempts] is exhausted.
     *
     * The flow completes normally when the server ends the stream (only for
     * subscriptions with [UpdateSubscription.endInclusive] set) and throws
     * [CantonException] on non-retryable failures.
     */
    public fun updates(subscription: UpdateSubscription): Flow<LedgerUpdate> = flow {
        var cursor = subscription.beginExclusive
        var attempt = 1
        var authRecovered = false
        while (true) {
            val connectedAt = TimeSource.Monotonic.markNow()
            var progressed = false
            try {
                updateService.getUpdates(subscription.toRequest(cursor)).collect { response ->
                    val update = LedgerUpdate.from(response) ?: return@collect
                    cursor = update.offset
                    progressed = true
                    emit(update)
                }
                return@flow // server completed the stream (finite subscription)
            } catch (t: Throwable) {
                val error = CantonError.from(t) ?: throw t
                // Only a connection that delivered an update and outlived the
                // healthy window earns a fresh retry budget; a stream that
                // reconnects and dies young keeps escalating instead.
                if (progressed && connectedAt.elapsedNow() >= retryPolicy.streamHealthyWindow) {
                    attempt = 1
                }
                // Auth recovery re-arms on lifetime alone: a connection the
                // server accepted and held past the healthy window proves
                // the last refresh worked even if the ledger was idle —
                // its token simply aged out again.
                if (connectedAt.elapsedNow() >= retryPolicy.streamHealthyWindow) {
                    authRecovered = false
                }
                // The participant terminates a stream whose access token
                // expired mid-flight (observed as PERMISSION_DENIED, no
                // RetryInfo). That is recoverable exactly when the provider
                // can mint a token that differs from the one the failed
                // connection used — its fetch has populated the cache by
                // now, so cached() is that token. Reconnect once with the
                // fresh one; a second auth failure without an intervening
                // healthy connection is a real authorization problem and
                // propagates.
                if (error.isAuthFailure && tokenCache != null && !authRecovered &&
                    tokenCache.refreshIfChanged(tokenCache.cached())
                ) {
                    authRecovered = true
                    continue
                }
                if (!error.retryable || attempt >= retryPolicy.maxAttempts) {
                    throw CantonException(error, t)
                }
                delay(maxOf(retryPolicy.backoffFor(attempt), error.retryDelay ?: Duration.ZERO))
                attempt++
            }
        }
    }

    override fun close() {
        authScope.cancel()
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
