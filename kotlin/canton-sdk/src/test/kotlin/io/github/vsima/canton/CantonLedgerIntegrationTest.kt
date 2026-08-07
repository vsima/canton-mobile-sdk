package io.github.vsima.canton

import com.daml.ledger.api.v2.CommandsOuterClass
import com.daml.ledger.api.v2.ValueOuterClass
import com.daml.ledger.api.v2.admin.PackageManagementServiceGrpcKt
import com.daml.ledger.api.v2.admin.PackageManagementServiceOuterClass.UploadDarFileRequest
import com.daml.ledger.api.v2.admin.PartyManagementServiceGrpcKt
import com.daml.ledger.api.v2.admin.PartyManagementServiceOuterClass.AllocatePartyRequest
import com.google.protobuf.ByteString
import io.grpc.okhttp.OkHttpChannelBuilder
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs against a live Canton participant (see integration/run-canton.sh).
 * Skipped unless CANTON_LEDGER_PORT is set.
 */
class CantonLedgerIntegrationTest {

    private val port = System.getenv("CANTON_LEDGER_PORT")
    private val host = System.getenv("CANTON_LEDGER_HOST") ?: "127.0.0.1"

    @Test
    fun `fetches the version from a live canton participant`() {
        assumeTrue(port != null, "CANTON_LEDGER_PORT not set; skipping live-ledger test")

        runBlocking {
            CantonClient(
                CantonClientConfiguration(host = host, port = port!!.toInt(), useTls = false)
            ).use { client ->
                val version = client.ledgerApiVersion()
                println("live canton ledger api version: $version")
                assertTrue(version.isNotBlank())
            }
        }
    }

    @Test
    fun `allocates a party, uploads a dar, and creates a contract`() {
        assumeTrue(port != null, "CANTON_LEDGER_PORT not set; skipping live-ledger test")
        val darPath = System.getenv("CANTON_EXAMPLES_DAR")
        assumeTrue(darPath != null, "CANTON_EXAMPLES_DAR not set; skipping submission test")

        val channel = OkHttpChannelBuilder.forAddress(host, port!!.toInt()).usePlaintext().build()
        val client = CantonClient(channel)
        try {
            runBlocking {
                val party = PartyManagementServiceGrpcKt.PartyManagementServiceCoroutineStub(channel)
                    .allocateParty(AllocatePartyRequest.getDefaultInstance())
                    .partyDetails.party

                PackageManagementServiceGrpcKt.PackageManagementServiceCoroutineStub(channel)
                    .uploadDarFile(
                        UploadDarFileRequest.newBuilder()
                            .setDarFile(ByteString.copyFrom(File(darPath!!).readBytes()))
                            .build()
                    )

                val transaction = client.submitAndWaitForTransaction(
                    CommandSubmission(
                        commands = listOf(iouCreate(payer = party)),
                        actAs = listOf(party),
                        // No auth on the test ledger, so user_id cannot be
                        // defaulted from token claims and must be explicit.
                        userId = "participant_admin",
                    )
                )
                println("created Iou contract in update ${transaction.updateId}")
                assertTrue(transaction.updateId.isNotBlank())
                assertTrue(transaction.eventsList.any { it.hasCreated() })
            }
        } finally {
            client.close()
        }
    }

    private fun iouCreate(payer: String): CommandsOuterClass.Command {
        fun value(build: ValueOuterClass.Value.Builder.() -> Unit): ValueOuterClass.Value =
            ValueOuterClass.Value.newBuilder().apply(build).build()

        fun field(label: String, v: ValueOuterClass.Value): ValueOuterClass.RecordField =
            ValueOuterClass.RecordField.newBuilder().setLabel(label).setValue(v).build()

        val amount = value {
            record = ValueOuterClass.Record.newBuilder()
                .addFields(field("value", value { numeric = "100.0" }))
                .addFields(field("currency", value { text = "USD" }))
                .build()
        }

        return CommandsOuterClass.Command.newBuilder()
            .setCreate(
                CommandsOuterClass.CreateCommand.newBuilder()
                    .setTemplateId(
                        ValueOuterClass.Identifier.newBuilder()
                            .setPackageId("#CantonExamples")
                            .setModuleName("Iou")
                            .setEntityName("Iou")
                    )
                    .setCreateArguments(
                        ValueOuterClass.Record.newBuilder()
                            .addFields(field("payer", value { party = payer }))
                            .addFields(field("owner", value { party = payer }))
                            .addFields(field("amount", amount))
                            .addFields(
                                field(
                                    "viewers",
                                    value { list = ValueOuterClass.List.getDefaultInstance() },
                                )
                            )
                    )
            )
            .build()
    }
}
