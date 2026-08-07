package io.github.vsima.canton

import com.daml.ledger.api.v2.EventOuterClass
import com.daml.ledger.api.v2.StateServiceGrpcKt
import com.daml.ledger.api.v2.StateServiceOuterClass.ActiveContract as ActiveContractProto
import com.daml.ledger.api.v2.StateServiceOuterClass.GetActiveContractsRequest
import com.daml.ledger.api.v2.StateServiceOuterClass.GetActiveContractsResponse
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class ActiveContractsRetryTest {

    /**
     * First attempt emits one contract and dies; the retry serves the full
     * two-contract snapshot. UNAVAILABLE is retryable without RetryInfo.
     */
    private class FlakyStateService : StateServiceGrpcKt.StateServiceCoroutineImplBase() {
        val requests = CopyOnWriteArrayList<GetActiveContractsRequest>()

        override fun getActiveContracts(
            request: GetActiveContractsRequest,
        ): Flow<GetActiveContractsResponse> = flow {
            requests += request
            if (requests.size == 1) {
                emit(contract("stale-partial"))
                throw StatusRuntimeException(Status.UNAVAILABLE.withDescription("stream lost"))
            }
            emit(contract("c1"))
            emit(contract("c2"))
        }

        private fun contract(contractId: String): GetActiveContractsResponse =
            GetActiveContractsResponse.newBuilder()
                .setActiveContract(
                    ActiveContractProto.newBuilder()
                        .setCreatedEvent(EventOuterClass.CreatedEvent.newBuilder().setContractId(contractId))
                        .setSynchronizerId("sync::1")
                        .setReassignmentCounter(0)
                )
                .build()
    }

    @Test
    fun `retries restart the snapshot from scratch without partial duplicates`() {
        val service = FlakyStateService()
        val name = InProcessServerBuilder.generateName()
        val server = InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start()
        val channel = InProcessChannelBuilder.forName(name).directExecutor().build()

        try {
            val contracts = runBlocking {
                CantonClient(
                    channel,
                    retryPolicy = RetryPolicy(maxAttempts = 3, initialBackoff = 1.milliseconds, maxBackoff = 2.milliseconds),
                ).use { it.activeContracts(parties = listOf("alice::ns"), activeAtOffset = 10) }
            }
            // The partial first attempt is discarded entirely.
            assertEquals(listOf("c1", "c2"), contracts.map { it.createdEvent.contractId })
            assertEquals(2, service.requests.size)
            assertEquals(10, service.requests[0].activeAtOffset)
            assertEquals(service.requests[0], service.requests[1])
        } finally {
            server.shutdownNow()
        }
    }
}
