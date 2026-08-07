package io.github.vsima.canton

import io.grpc.CallCredentials
import io.grpc.Metadata
import io.grpc.Status
import java.util.concurrent.Executor

/**
 * Injects `authorization: Bearer <token>` into every request, fetching the
 * token from the configured provider.
 */
internal class BearerTokenCallCredentials(
    private val tokenProvider: () -> String,
) : CallCredentials() {

    override fun applyRequestMetadata(
        requestInfo: RequestInfo,
        appExecutor: Executor,
        applier: MetadataApplier,
    ) {
        appExecutor.execute {
            try {
                val headers = Metadata()
                headers.put(AUTHORIZATION, "Bearer ${tokenProvider()}")
                applier.apply(headers)
            } catch (t: Throwable) {
                applier.fail(Status.UNAUTHENTICATED.withCause(t))
            }
        }
    }

    private companion object {
        private val AUTHORIZATION: Metadata.Key<String> =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
    }
}
