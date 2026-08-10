// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton

import io.grpc.CallCredentials
import io.grpc.Metadata
import io.grpc.Status
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Injects `authorization: Bearer <token>` into every request. The provider
 * may suspend (a cache miss triggering an OIDC refresh), so the metadata is
 * applied from a coroutine on [scope] -- gRPC explicitly supports deferred
 * [MetadataApplier] completion.
 */
internal class BearerTokenCallCredentials(
    private val scope: CoroutineScope,
    private val tokenProvider: suspend () -> String,
) : CallCredentials() {

    override fun applyRequestMetadata(
        requestInfo: RequestInfo,
        appExecutor: Executor,
        applier: MetadataApplier,
    ) {
        if (!scope.isActive) {
            applier.fail(Status.CANCELLED.withDescription("client is closed"))
            return
        }
        scope.launch {
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
