package io.github.vsima.canton

import com.daml.ledger.api.v2.CommandServiceGrpcKt
import com.daml.ledger.api.v2.CommandServiceOuterClass.SubmitAndWaitRequest
import com.daml.ledger.api.v2.CommandServiceOuterClass.SubmitAndWaitResponse
import com.daml.ledger.api.v2.CommandsOuterClass
import com.google.protobuf.Any as ProtoAny
import com.google.protobuf.Duration as ProtoDuration
import com.google.rpc.RetryInfo
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds
import com.google.rpc.Status as RpcStatus

class CommandRetryTest {

    /** Fails the first [failures] calls with a retryable rich error, then succeeds. */
    private class FlakyCommandService(private val failures: Int) :
        CommandServiceGrpcKt.CommandServiceCoroutineImplBase() {
        val attempts = AtomicInteger()
        val seenCommandIds = CopyOnWriteArrayList<String>()

        override suspend fun submitAndWait(request: SubmitAndWaitRequest): SubmitAndWaitResponse {
            seenCommandIds += request.commands.commandId
            if (attempts.incrementAndGet() <= failures) {
                throw retryableAborted()
            }
            return SubmitAndWaitResponse.newBuilder().setUpdateId("update-1").build()
        }

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
            return StatusRuntimeException(Status.ABORTED.withDescription("CONTENTION"), trailers)
        }
    }

    private val testPolicy = RetryPolicy(
        maxAttempts = 4,
        initialBackoff = 1.milliseconds,
        maxBackoff = 2.milliseconds,
    )

    private fun <T> withService(
        service: CommandServiceGrpcKt.CommandServiceCoroutineImplBase,
        policy: RetryPolicy = testPolicy,
        block: suspend (CantonClient) -> T,
    ): T {
        val name = InProcessServerBuilder.generateName()
        val server = InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start()
        val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
        return try {
            runBlocking { CantonClient(channel, retryPolicy = policy).use { block(it) } }
        } finally {
            server.shutdownNow()
        }
    }

    private val submission = CommandSubmission(
        commands = listOf(CommandsOuterClass.Command.getDefaultInstance()),
        actAs = listOf("alice::ns"),
    )

    @Test
    fun `retries retryable failures and reuses the command id`() {
        val service = FlakyCommandService(failures = 2)
        val updateId = withService(service) { it.submitAndWait(submission) }
        assertEquals("update-1", updateId)
        assertEquals(3, service.attempts.get())
        assertEquals(1, service.seenCommandIds.distinct().size)
    }

    @Test
    fun `gives up after maxAttempts`() {
        val service = FlakyCommandService(failures = Int.MAX_VALUE)
        val e = assertFailsWith<CantonException> {
            withService(service) { it.submitAndWait(submission) }
        }
        assertEquals(4, service.attempts.get())
        assertEquals(Status.Code.ABORTED, e.error.grpcCode)
    }

    @Test
    fun `does not retry non-retryable failures`() {
        val service = object : CommandServiceGrpcKt.CommandServiceCoroutineImplBase() {
            val attempts = AtomicInteger()
            override suspend fun submitAndWait(request: SubmitAndWaitRequest): SubmitAndWaitResponse {
                attempts.incrementAndGet()
                throw StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("bad command"))
            }
        }
        val e = assertFailsWith<CantonException> {
            withService(service) { it.submitAndWait(submission) }
        }
        assertEquals(1, service.attempts.get())
        assertFalse(e.error.retryable)
    }
}
