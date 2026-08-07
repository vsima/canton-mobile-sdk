// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton

import com.daml.ledger.api.v2.VersionServiceGrpcKt
import com.daml.ledger.api.v2.VersionServiceOuterClass.GetLedgerApiVersionRequest
import com.daml.ledger.api.v2.VersionServiceOuterClass.GetLedgerApiVersionResponse
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class CantonClientTest {

    private object FakeVersionService : VersionServiceGrpcKt.VersionServiceCoroutineImplBase() {
        override suspend fun getLedgerApiVersion(
            request: GetLedgerApiVersionRequest,
        ): GetLedgerApiVersionResponse =
            GetLedgerApiVersionResponse.newBuilder().setVersion("2.fake.0").build()
    }

    @Test
    fun `fetches the ledger api version over grpc`() = runBlocking {
        val name = InProcessServerBuilder.generateName()
        val server = InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(FakeVersionService)
            .build()
            .start()
        val channel = InProcessChannelBuilder.forName(name).directExecutor().build()

        try {
            CantonClient(channel).use { client ->
                assertEquals("2.fake.0", client.ledgerApiVersion())
            }
        } finally {
            server.shutdownNow()
        }
    }
}
