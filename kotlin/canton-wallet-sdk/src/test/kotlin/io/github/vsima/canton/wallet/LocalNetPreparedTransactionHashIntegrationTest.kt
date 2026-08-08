// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet

import com.daml.ledger.api.v2.CommandsOuterClass
import com.daml.ledger.api.v2.interactive.InteractiveSubmissionServiceOuterClass.HashingSchemeVersion
import com.daml.ledger.api.v2.interactive.InteractiveSubmissionServiceOuterClass.PrepareSubmissionResponse
import io.github.vsima.canton.CantonClient
import io.github.vsima.canton.CantonClientConfiguration
import io.github.vsima.canton.DamlValues
import io.grpc.okhttp.OkHttpChannelBuilder
import java.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Proves [PreparedTransactionHash] against a live participant: every
 * `prepare` on Splice LocalNet must re-hash locally to exactly the
 * `prepared_transaction_hash` the node asks the party to sign. Skipped
 * unless SPLICE_LOCALNET=1 (same environment as
 * [LocalNetTokenStandardIntegrationTest]).
 *
 * Two shapes are covered, all through the public SDK surface:
 *  1. a create (TransferPreapprovalProposal) — create node, no inputs
 *  2. its Archive — exercise node + an input contract in the metadata
 *
 * Both are then executed with [InteractiveSubmissionClient.signAndExecute],
 * whose verification is on by default — so the run also proves the
 * verify-then-sign path end to end. The prepared protos and node hashes are
 * printed as golden-vector lines for testdata/preparedtx/vectors.txt.
 */
class LocalNetPreparedTransactionHashIntegrationTest {

    private val enabled = System.getenv("SPLICE_LOCALNET") == "1"

    private val ledgerHost = env("SPLICE_LOCALNET_LEDGER_HOST", "127.0.0.1")
    private val ledgerPort = env("SPLICE_LOCALNET_LEDGER_PORT", "2901").toInt()
    private val adminUser = env("SPLICE_LOCALNET_ADMIN_USER", "ledger-api-user")

    private fun env(name: String, default: String) = System.getenv(name) ?: default

    @Test
    fun `locally recomputed hash matches the node's for live prepared transactions`() {
        assumeTrue(enabled, "SPLICE_LOCALNET not set; skipping LocalNet test")
        val support = LocalNetTokenStandardIntegrationTest()
        runBlocking {
            val plain = OkHttpChannelBuilder.forAddress(ledgerHost, ledgerPort).usePlaintext().build()
            try {
                val adminChannel = support.authed(plain, adminUser)

                val driver = SoftwareSigningDriver.generate(SoftwareSigningDriver.Algorithm.EC_P256)
                val parties = ExternalPartyClient(adminChannel)
                val synchronizer = parties.connectedSynchronizers().first()
                val external = parties.allocate(driver, synchronizer, "hashcheck", userId = adminUser)
                println("external party: ${external.partyId}")

                val submission = InteractiveSubmissionClient(adminChannel)

                // 1. Create node: the receiver proposes its own preapproval.
                // provider == receiver keeps the test self-contained; nothing
                // validates it at create time and we archive it ourselves.
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
                val preparedCreate = submission.prepare(
                    commands = listOf(create),
                    actAs = external.partyId,
                    synchronizerId = synchronizer,
                    userId = adminUser,
                )
                assertHashMatchesAndPrintVector("create_preapproval_proposal", preparedCreate)

                // Executing runs the same verification again (on by default).
                submission.signAndExecute(
                    prepared = preparedCreate,
                    driver = driver,
                    partyId = external.partyId,
                    keyFingerprint = external.publicKeyFingerprint,
                    userId = adminUser,
                )

                val acs = CantonClient(
                    CantonClientConfiguration(
                        host = ledgerHost,
                        port = ledgerPort,
                        useTls = false,
                        accessTokenProvider = { support.jwt(adminUser) },
                    )
                )
                val proposalId = acs.use {
                    retryUntil("proposal visible in the ACS") {
                        it.activeContractsSnapshot(listOf(external.partyId))
                            .contracts.firstOrNull()?.createdEvent?.contractId
                    }
                }
                println("proposal contract: $proposalId")

                // 2. Exercise node + input contract: archive the proposal.
                val archive = CommandsOuterClass.Command.newBuilder()
                    .setExercise(
                        CommandsOuterClass.ExerciseCommand.newBuilder()
                            .setTemplateId(SpliceWallet.transferPreapprovalProposalTemplateId)
                            .setContractId(proposalId)
                            .setChoice("Archive")
                            .setChoiceArgument(DamlValues.record())
                    )
                    .build()
                val preparedArchive = submission.prepare(
                    commands = listOf(archive),
                    actAs = external.partyId,
                    synchronizerId = synchronizer,
                    userId = adminUser,
                )
                assertHashMatchesAndPrintVector("archive_preapproval_proposal", preparedArchive)

                submission.signAndExecute(
                    prepared = preparedArchive,
                    driver = driver,
                    partyId = external.partyId,
                    keyFingerprint = external.publicKeyFingerprint,
                    userId = adminUser,
                )
            } finally {
                plain.shutdownNow()
            }
        }
    }

    private fun assertHashMatchesAndPrintVector(name: String, prepared: PrepareSubmissionResponse) {
        assertEquals(
            HashingSchemeVersion.HASHING_SCHEME_VERSION_V2,
            prepared.hashingSchemeVersion,
            "LocalNet prepared with an unexpected hashing scheme",
        )
        val computed = PreparedTransactionHash.compute(prepared.preparedTransaction)
        val b64 = Base64.getEncoder()
        println(
            "golden-vector: $name " +
                "${b64.encodeToString(prepared.preparedTransaction.toByteArray())} " +
                b64.encodeToString(prepared.preparedTransactionHash.toByteArray())
        )
        assertContentEquals(
            prepared.preparedTransactionHash.toByteArray(),
            computed,
            "locally recomputed hash differs from the node's for '$name'",
        )
    }

    private suspend fun <T : Any> retryUntil(
        what: String,
        attempts: Int = 60,
        delayMs: Long = 1_000,
        block: suspend () -> T?,
    ): T {
        repeat(attempts) {
            runCatching { block() }.getOrNull()?.let { return it }
            delay(delayMs)
        }
        fail("$what: not satisfied after $attempts attempts")
    }
}
