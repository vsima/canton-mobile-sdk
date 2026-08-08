// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet

import com.daml.ledger.api.v2.CommandsOuterClass
import io.github.vsima.canton.CantonClient
import io.github.vsima.canton.CantonClientConfiguration
import io.github.vsima.canton.CantonException
import io.github.vsima.canton.DamlValues
import io.grpc.okhttp.OkHttpChannelBuilder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Proves completion tracking against Splice LocalNet:
 * [InteractiveSubmissionClient.signAndExecuteAndWait] must surface the
 * ledger's completion for a live interactive submission — a real update id
 * on success, and the typed rejection when the ledger refuses the command.
 * Skipped unless SPLICE_LOCALNET=1 (same environment as
 * [LocalNetTokenStandardIntegrationTest]).
 *
 * Because the awaited variant returns only once the command is committed,
 * the created contract must already be in the ACS — the test reads it once,
 * with no polling. The rejection leg manufactures contention: two archives
 * of the same contract are prepared while it is still active, then executed
 * one after the other — the second can only fail asynchronously, in its
 * completion event.
 */
class LocalNetCompletionTrackingIntegrationTest {

    private val enabled = System.getenv("SPLICE_LOCALNET") == "1"

    private val ledgerHost = env("SPLICE_LOCALNET_LEDGER_HOST", "127.0.0.1")
    private val ledgerPort = env("SPLICE_LOCALNET_LEDGER_PORT", "2901").toInt()
    private val adminUser = env("SPLICE_LOCALNET_ADMIN_USER", "ledger-api-user")

    private fun env(name: String, default: String) = System.getenv(name) ?: default

    @Test
    fun `signAndExecuteAndWait returns the live update id and raises typed rejections`() {
        assumeTrue(enabled, "SPLICE_LOCALNET not set; skipping LocalNet test")
        val support = LocalNetTokenStandardIntegrationTest()
        runBlocking {
            val plain = OkHttpChannelBuilder.forAddress(ledgerHost, ledgerPort).usePlaintext().build()
            try {
                val adminChannel = support.authed(plain, adminUser)

                val driver = SoftwareSigningDriver.generate(SoftwareSigningDriver.Algorithm.EC_P256)
                val parties = ExternalPartyClient(adminChannel)
                val synchronizer = parties.connectedSynchronizers().first()
                val external = parties.allocate(driver, synchronizer, "completion", userId = adminUser)
                println("external party: ${external.partyId}")

                val submission = InteractiveSubmissionClient(adminChannel)

                // Create a TransferPreapprovalProposal (provider == receiver
                // keeps it self-contained), awaited: the returned completion
                // proves commitment.
                val create = CommandsOuterClass.Command.newBuilder()
                    .setCreate(
                        CommandsOuterClass.CreateCommand.newBuilder()
                            .setTemplateId(SpliceWallet.transferPreapprovalProposalTemplateId)
                            .setCreateArguments(
                                DamlValues.recordOf(
                                    "receiver" to DamlValues.party(external.partyId),
                                    "provider" to DamlValues.party(external.partyId),
                                    "expectedDso" to DamlValues.optional(
                                        DamlValues.party(external.partyId)
                                    ),
                                )
                            )
                    )
                    .build()
                val created = submission.signAndExecuteAndWait(
                    prepared = submission.prepare(
                        commands = listOf(create),
                        actAs = external.partyId,
                        synchronizerId = synchronizer,
                        userId = adminUser,
                    ),
                    driver = driver,
                    partyId = external.partyId,
                    keyFingerprint = external.publicKeyFingerprint,
                    userId = adminUser,
                )
                println("create completion: updateId=${created.updateId} offset=${created.offset}")
                assertTrue(created.updateId.isNotBlank(), "completion must carry a real update id")
                assertTrue(created.offset > 0, "completion must carry a real offset")

                // Committed means visible: one ACS read, no polling.
                val proposalId = CantonClient(
                    CantonClientConfiguration(
                        host = ledgerHost,
                        port = ledgerPort,
                        useTls = false,
                        accessTokenProvider = { support.jwt(adminUser) },
                    )
                ).use {
                    it.activeContractsSnapshot(listOf(external.partyId))
                        .contracts.firstOrNull()?.createdEvent?.contractId
                }
                assertNotNull(proposalId, "awaited create must already be in the ACS")
                println("proposal contract: $proposalId")

                // Prepare TWO archives of the live proposal, then execute
                // both: the second passes interpretation but can only be
                // refused at commit time — asynchronously, in its completion.
                suspend fun preparedArchive() = submission.prepare(
                    commands = listOf(
                        CommandsOuterClass.Command.newBuilder()
                            .setExercise(
                                CommandsOuterClass.ExerciseCommand.newBuilder()
                                    .setTemplateId(SpliceWallet.transferPreapprovalProposalTemplateId)
                                    .setContractId(proposalId)
                                    .setChoice("Archive")
                                    .setChoiceArgument(DamlValues.record())
                            )
                            .build()
                    ),
                    actAs = external.partyId,
                    synchronizerId = synchronizer,
                    userId = adminUser,
                )
                val firstArchive = preparedArchive()
                val secondArchive = preparedArchive()

                val archived = submission.signAndExecuteAndWait(
                    prepared = firstArchive,
                    driver = driver,
                    partyId = external.partyId,
                    keyFingerprint = external.publicKeyFingerprint,
                    userId = adminUser,
                )
                println("archive completion: updateId=${archived.updateId} offset=${archived.offset}")
                assertTrue(archived.updateId.isNotBlank())
                assertTrue(archived.offset > created.offset)
                assertNotEquals(created.updateId, archived.updateId)

                val rejection = assertFailsWith<CantonException> {
                    submission.signAndExecuteAndWait(
                        prepared = secondArchive,
                        driver = driver,
                        partyId = external.partyId,
                        keyFingerprint = external.publicKeyFingerprint,
                        userId = adminUser,
                    )
                }
                println(
                    "typed rejection: grpcCode=${rejection.error.grpcCode} " +
                        "errorCode=${rejection.error.errorCode} " +
                        "description=${rejection.error.description.take(120)}"
                )
                assertNotNull(
                    rejection.error.errorCode,
                    "completion rejection must decode Canton's typed error code",
                )
            } finally {
                plain.shutdownNow()
            }
        }
    }
}
