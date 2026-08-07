package io.github.vsima.canton

import com.daml.ledger.api.v2.VersionServiceGrpcKt
import com.daml.ledger.api.v2.VersionServiceOuterClass.GetLedgerApiVersionRequest
import io.grpc.CallCredentials
import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
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
) : Closeable {

    public constructor(configuration: CantonClientConfiguration) : this(
        configuration.buildChannel(),
        configuration.accessTokenProvider?.let(::BearerTokenCallCredentials),
    )

    private val versionService =
        VersionServiceGrpcKt.VersionServiceCoroutineStub(channel)
            .let { stub -> callCredentials?.let(stub::withCallCredentials) ?: stub }

    /** Fetches the Ledger API version from the participant. */
    public suspend fun ledgerApiVersion(): String =
        versionService
            .getLedgerApiVersion(GetLedgerApiVersionRequest.getDefaultInstance())
            .version

    override fun close() {
        channel.shutdown()
        if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
            channel.shutdownNow()
        }
    }
}

private fun CantonClientConfiguration.buildChannel(): ManagedChannel =
    OkHttpChannelBuilder.forAddress(host, port)
        .apply { if (useTls) useTransportSecurity() else usePlaintext() }
        .build()
