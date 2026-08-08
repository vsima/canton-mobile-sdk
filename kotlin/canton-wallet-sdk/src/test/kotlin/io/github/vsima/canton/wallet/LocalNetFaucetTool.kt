// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet

import com.daml.ledger.api.v2.CommandServiceGrpcKt
import com.daml.ledger.api.v2.CommandServiceOuterClass.SubmitAndWaitRequest
import com.daml.ledger.api.v2.CommandsOuterClass
import io.github.vsima.canton.DamlValues
import io.grpc.okhttp.OkHttpChannelBuilder
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test

/**
 * Not a test — a LocalNet dev tool wearing a test harness. Taps Amulet to
 * the operator wallet and sends a token-standard transfer offer to any
 * party, e.g. a wallet app under development:
 *
 *   integration/localnet-faucet.sh <party-id> [amount]
 *
 * The receiver sees the offer in its inbox (or receives instantly if
 * preapproved). Gated on FAUCET_PARTY so normal test runs skip it.
 */
class LocalNetFaucetTool {

    @Test
    fun `send amulet to the requested party`() {
        val receiver = System.getenv("FAUCET_PARTY")
        assumeTrue(receiver != null, "FAUCET_PARTY not set; this is a dev tool, not a test")
        val amount = System.getenv("FAUCET_AMOUNT") ?: "50.0"
        val count = (System.getenv("FAUCET_COUNT") ?: "1").toInt()
        val memos = listOf(
            "Invoice #4021", "Coffee ☕", "Rent split", "Consulting fee",
            "Birthday 🎁", "Refund", "Groceries",
        )
        val host = System.getenv("SPLICE_LOCALNET_LEDGER_HOST") ?: "127.0.0.1"
        val port = (System.getenv("SPLICE_LOCALNET_LEDGER_PORT") ?: "2901").toInt()

        val support = LocalNetTokenStandardIntegrationTest()
        runBlocking {
            val plain = OkHttpChannelBuilder.forAddress(host, port).usePlaintext().build()
            try {
                val walletParty = support.onboardWalletUser()
                support.tap("2000.0")

                val walletChannel = support.authed(plain, "app-user")
                val registry = TransferRegistryClient("http://scan.localhost:4000", support.http)
                val tokens = TokenStandardClient(walletChannel, registry)
                val holdings = tokens.listHoldings(walletParty).filter { it.lock == null }
                val amulet = holdings.first().instrumentId

                repeat(count) { index ->
                val step = BigDecimal(amount).add(BigDecimal(index * 7))
                val transfer = Transfer(
                    sender = walletParty,
                    receiver = receiver!!,
                    amount = step,
                    instrumentId = amulet,
                    requestedAt = java.time.Instant.now(),
                    executeBefore = java.time.Instant.now().plusSeconds(24 * 3600),
                    inputHoldingCids = tokens.listHoldings(walletParty)
                        .filter { it.lock == null }.map { it.contractId },
                    meta = mapOf("splice.lfdecentralizedtrust.org/reason" to memos[index % memos.size]),
                )
                val factory = registry.transferFactory(
                    ChoiceContextJson.transferFactoryChoiceArguments(amulet.admin, transfer)
                )
                CommandServiceGrpcKt.CommandServiceCoroutineStub(walletChannel).submitAndWait(
                    SubmitAndWaitRequest.newBuilder()
                        .setCommands(
                            CommandsOuterClass.Commands.newBuilder()
                                .setCommandId(java.util.UUID.randomUUID().toString())
                                .setUserId("app-user")
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
                println("FAUCET: sent $step CC to $receiver (kind=${factory.transferKind}, memo=${memos[index % memos.size]})")
                }
            } finally {
                plain.shutdownNow()
            }
        }
    }
}
