// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet

import com.daml.ledger.api.v2.CommandsOuterClass
import com.daml.ledger.api.v2.ValueOuterClass
import com.daml.ledger.api.v2.admin.PackageManagementServiceGrpcKt
import com.daml.ledger.api.v2.admin.PackageManagementServiceOuterClass.UploadDarFileRequest
import com.google.protobuf.ByteString
import io.github.vsima.canton.CantonClient
import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs against a live Canton participant (see integration/run-canton.sh).
 * Skipped unless CANTON_LEDGER_PORT is set.
 *
 * These tests settle the question the mobile wallet architecture hangs on:
 * which key schemes a real participant accepts for *external parties*.
 * Ed25519 is Canton's documented default; EC P-256 is the only scheme
 * Apple's Secure Enclave and Android StrongBox can sign. If the P-256 test
 * passes, enclave-held self-custody keys are viable.
 */
class ExternalPartyIntegrationTest {

    private val port = System.getenv("CANTON_LEDGER_PORT")
    private val host = System.getenv("CANTON_LEDGER_HOST") ?: "127.0.0.1"

    @Test
    fun `allocates an external party with an Ed25519 key`() {
        withLiveLedger { channel ->
            val driver = SoftwareSigningDriver.generate(SoftwareSigningDriver.Algorithm.ED25519)
            val party = allocate(channel, driver, "ed25519wallet")
            println("allocated Ed25519 external party: ${party.partyId}")
            assertTrue(party.partyId.startsWith("ed25519wallet::"))
        }
    }

    @Test
    fun `allocates an external party with an EC P-256 key (enclave scheme)`() {
        withLiveLedger { channel ->
            val driver = SoftwareSigningDriver.generate(SoftwareSigningDriver.Algorithm.EC_P256)
            val party = allocate(channel, driver, "p256wallet")
            println("allocated P-256 external party: ${party.partyId}")
            assertTrue(party.partyId.startsWith("p256wallet::"))
        }
    }

    /**
     * The full self-custody loop: onboard a P-256 external party, then create
     * a contract acting as that party via prepare → sign → execute. Every
     * signature comes from the driver — the participant never holds the key.
     */
    @Test
    fun `P-256 external party creates a contract via prepare, sign, execute`() {
        val darPath = System.getenv("CANTON_EXAMPLES_DAR")
        assumeTrue(darPath != null, "CANTON_EXAMPLES_DAR not set; skipping submission test")

        withLiveLedger { channel ->
            runBlocking {
                PackageManagementServiceGrpcKt.PackageManagementServiceCoroutineStub(channel)
                    .uploadDarFile(
                        UploadDarFileRequest.newBuilder()
                            .setDarFile(ByteString.copyFrom(File(darPath!!).readBytes()))
                            .build()
                    )

                val driver = SoftwareSigningDriver.generate(SoftwareSigningDriver.Algorithm.EC_P256)
                val partyClient = ExternalPartyClient(channel)
                val synchronizer = partyClient.connectedSynchronizers().first()
                val party =
                    partyClient.allocate(driver, synchronizer, "p256signer", userId = "participant_admin")

                val submission = InteractiveSubmissionClient(channel)
                val prepared = submission.prepare(
                    commands = listOf(iouCreate(payer = party.partyId)),
                    actAs = party.partyId,
                    synchronizerId = synchronizer,
                    userId = "participant_admin",
                )
                submission.signAndExecute(
                    prepared = prepared,
                    driver = driver,
                    partyId = party.partyId,
                    keyFingerprint = party.publicKeyFingerprint,
                    userId = "participant_admin",
                )

                // Execution is async; the contract is committed once it shows
                // up in the party's ACS.
                val canton = CantonClient(channel)
                repeat(30) {
                    val snapshot = canton.activeContractsSnapshot(listOf(party.partyId))
                    if (snapshot.contracts.isNotEmpty()) {
                        println(
                            "P-256 external party created contract " +
                                snapshot.contracts.single().createdEvent.contractId
                        )
                        return@runBlocking
                    }
                    delay(500)
                }
                fail("contract signed by the P-256 external party never reached the ACS")
            }
        }
    }

    private fun allocate(
        channel: ManagedChannel,
        driver: SigningDriver,
        hint: String,
    ): AllocatedExternalParty = runBlocking {
        val client = ExternalPartyClient(channel)
        val synchronizer = client.connectedSynchronizers().first()
        client.allocate(driver, synchronizer, hint, userId = "participant_admin")
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

    private fun withLiveLedger(block: (ManagedChannel) -> Unit) {
        assumeTrue(port != null, "CANTON_LEDGER_PORT not set; skipping live-ledger test")
        val channel = OkHttpChannelBuilder.forAddress(host, port!!.toInt()).usePlaintext().build()
        try {
            block(channel)
        } finally {
            channel.shutdownNow()
        }
    }
}
