// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet

import com.daml.ledger.api.v2.CommandServiceGrpcKt
import com.daml.ledger.api.v2.CommandServiceOuterClass.SubmitAndWaitRequest
import com.daml.ledger.api.v2.CommandsOuterClass
import com.daml.ledger.api.v2.StateServiceGrpcKt
import com.daml.ledger.api.v2.StateServiceOuterClass.GetLedgerEndRequest
import io.github.vsima.canton.DamlValues
import io.grpc.okhttp.OkHttpChannelBuilder
import java.math.BigDecimal
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Transfer-level history semantics against Splice LocalNet. Skipped unless
 * SPLICE_LOCALNET=1 (see [LocalNetTokenStandardIntegrationTest] for the
 * environment).
 *
 * The two-party scenario:
 *  1. fund the operator wallet party (sender) via tap
 *  2. allocate a P-256 external party (receiver)
 *  3. sender creates a token-standard transfer offer WITH a memo
 *  4. receiver accepts, externally signed
 *  5. [TokenStandardClient.holdingsHistory] then must read, on the sender
 *     side, SENT rows toward the receiver whose total is a fee-inclusive
 *     debit of the transfer amount, carrying the memo — and on the receiver
 *     side the mirrored RECEIVED credit of exactly the transfer amount with
 *     the same memo.
 */
class LocalNetTransferHistoryIntegrationTest {

    private val enabled = System.getenv("SPLICE_LOCALNET") == "1"

    @Test
    fun `two-party transfer with memo yields sent and received history rows`() {
        assumeTrue(enabled, "SPLICE_LOCALNET not set; skipping LocalNet test")
        val support = LocalNetTokenStandardIntegrationTest()
        val host = System.getenv("SPLICE_LOCALNET_LEDGER_HOST") ?: "127.0.0.1"
        val port = (System.getenv("SPLICE_LOCALNET_LEDGER_PORT") ?: "2901").toInt()
        val registryUrl = System.getenv("SPLICE_LOCALNET_REGISTRY_URL") ?: "http://scan.localhost:4000"
        val adminUser = System.getenv("SPLICE_LOCALNET_ADMIN_USER") ?: "ledger-api-user"
        val walletUser = System.getenv("SPLICE_LOCALNET_WALLET_USER") ?: "app-user"
        val amount = BigDecimal("5.0")
        val memo = "history probe ${java.util.UUID.randomUUID()}"

        runBlocking {
            val plain = OkHttpChannelBuilder.forAddress(host, port).usePlaintext().build()
            try {
                val adminChannel = support.authed(plain, adminUser)
                val walletChannel = support.authed(plain, walletUser)
                val registry = TransferRegistryClient(registryUrl, support.http)

                // 1. Funded sender: the operator wallet party.
                val walletParty = support.onboardWalletUser()
                support.tap("250.0")
                val walletTokens = TokenStandardClient(walletChannel, registry)
                val holdings = retryUntil("wallet holdings visible") {
                    walletTokens.listHoldings(walletParty)
                        .filter { it.lock == null }.ifEmpty { null }
                }
                val amulet = holdings.first().instrumentId
                println("sender: $walletParty with ${holdings.sumOf { it.amount }} ${amulet.id}")

                // 2. Receiver: a fresh P-256 external party.
                val driver = SoftwareSigningDriver.generate(SoftwareSigningDriver.Algorithm.EC_P256)
                val parties = ExternalPartyClient(adminChannel)
                val synchronizer = parties.connectedSynchronizers().first()
                val external = parties.allocate(driver, synchronizer, "historyreceiver", userId = adminUser)
                println("receiver: ${external.partyId}")

                // Only rows past this offset belong to the scenario.
                val senderBegin = StateServiceGrpcKt.StateServiceCoroutineStub(walletChannel)
                    .getLedgerEnd(GetLedgerEndRequest.getDefaultInstance()).offset

                // 3. The offer, WITH a memo riding on the standard reason key.
                val transfer = Transfer(
                    sender = walletParty,
                    receiver = external.partyId,
                    amount = amount,
                    instrumentId = amulet,
                    requestedAt = java.time.Instant.now(),
                    executeBefore = java.time.Instant.now().plusSeconds(24 * 3600),
                    inputHoldingCids = holdings.map { it.contractId },
                    meta = mapOf(TokenStandard.reasonMetadataKey to memo),
                )
                val factory = registry.transferFactory(
                    ChoiceContextJson.transferFactoryChoiceArguments(amulet.admin, transfer)
                )
                println("factory kind=${factory.transferKind}")
                CommandServiceGrpcKt.CommandServiceCoroutineStub(walletChannel).submitAndWait(
                    SubmitAndWaitRequest.newBuilder()
                        .setCommands(
                            CommandsOuterClass.Commands.newBuilder()
                                .setCommandId(java.util.UUID.randomUUID().toString())
                                .setUserId(walletUser)
                                .addActAs(walletParty)
                                .addCommands(
                                    CommandsOuterClass.Command.newBuilder().setExercise(
                                        CommandsOuterClass.ExerciseCommand.newBuilder()
                                            .setTemplateId(TokenStandard.transferFactoryInterfaceId)
                                            .setContractId(factory.factoryId)
                                            .setChoice("TransferFactory_Transfer")
                                            .setChoiceArgument(
                                                DamlValues.record(
                                                    "expectedAdmin" to DamlValues.party(amulet.admin),
                                                    "transfer" to transfer.toValue(),
                                                    "extraArgs" to ChoiceContextJson.extraArgsValue(
                                                        factory.choiceContext.choiceContextData
                                                    ),
                                                )
                                            )
                                    )
                                )
                                .addAllDisclosedContracts(
                                    factory.choiceContext.disclosedContracts.map { it.toProto() }
                                )
                        )
                        .build()
                )

                // 4. Receiver accepts, externally signed.
                val externalTokens = TokenStandardClient(adminChannel, registry)
                val instruction = retryUntil("transfer instruction in inbox") {
                    externalTokens.pendingTransferInstructions(external.partyId).firstOrNull()
                }
                assertEquals(memo, instruction.transfer.meta[TokenStandard.reasonMetadataKey])
                externalTokens.exerciseTransferInstruction(
                    driver = driver,
                    party = external,
                    transferInstructionId = instruction.contractId,
                    choice = TransferInstructionChoice.ACCEPT,
                    synchronizerId = synchronizer,
                    userId = adminUser,
                )
                retryUntil("received holdings visible") {
                    externalTokens.listHoldings(external.partyId).ifEmpty { null }
                }

                // 5a. Sender side: fee-inclusive SENT debit with the memo.
                val senderHistory = walletTokens.holdingsHistory(walletParty, beginExclusive = senderBegin)
                senderHistory.forEach { println("sender row: ${render(it)}") }
                val senderRows = senderHistory.filter { it.summary?.memo == memo }
                assertTrue(senderRows.isNotEmpty(), "sender history must contain rows with the memo")
                for (row in senderRows) {
                    assertEquals(TransferDirection.SENT, row.summary!!.direction)
                    assertEquals(external.partyId, row.summary!!.counterparty)
                    assertEquals(amulet, row.summary!!.instrumentId)
                    assertEquals(
                        row.archivedContractIds.size, row.archived.size,
                        "every archived holding must resolve to a payload",
                    )
                }
                val senderNet = senderRows.sumOf { it.summary!!.amount }
                println("sender net across memo rows: $senderNet")
                assertTrue(
                    senderNet < amount.negate(),
                    "sender debit must be fee-inclusive: expected < ${amount.negate()}, was $senderNet",
                )

                // 5b. Receiver side: the mirrored RECEIVED credit.
                val receiverHistory = externalTokens.holdingsHistory(external.partyId)
                receiverHistory.forEach { println("receiver row: ${render(it)}") }
                val receiverRows = receiverHistory.filter { it.summary?.memo == memo }
                assertEquals(1, receiverRows.size, "receiver history must contain the memo row once")
                val credit = receiverRows.single().summary!!
                assertEquals(TransferDirection.RECEIVED, credit.direction)
                assertEquals(walletParty, credit.counterparty)
                assertEquals(amulet, credit.instrumentId)
                assertEquals(
                    0, amount.compareTo(credit.amount),
                    "receiver must be credited exactly the transfer amount, was ${credit.amount}",
                )
            } finally {
                plain.shutdownNow()
            }
        }
    }

    private fun render(change: TokenStandardClient.HoldingsChange): String {
        val summary = change.summary
        return "offset=${change.offset} " +
            "created=${change.created.map { "${it.owner.take(16)}:${it.amount}" }} " +
            "archived=${change.archived.map { "${it.owner.take(16)}:${it.amount}" }} " +
            "unresolved=${change.archivedContractIds.size - change.archived.size} " +
            if (summary == null) "summary=null"
            else "direction=${summary.direction} counterparty=${summary.counterparty?.take(16)} " +
                "amount=${summary.amount} memo=${summary.memo}"
    }

    private suspend fun <T : Any> retryUntil(
        what: String,
        attempts: Int = 120,
        delayMs: Long = 5_000,
        block: suspend () -> T?,
    ): T {
        repeat(attempts) { attempt ->
            val result = runCatching { block() }.onFailure {
                println("  ($what attempt ${attempt + 1}: ${it.message?.take(160)})")
            }.getOrNull()
            if (result != null) return result
            delay(delayMs)
        }
        fail("$what: not satisfied after $attempts attempts")
    }
}
