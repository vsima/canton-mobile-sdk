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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The fee preview against Splice LocalNet. Skipped unless SPLICE_LOCALNET=1
 * (see [LocalNetTokenStandardIntegrationTest] for the environment).
 *
 * What this proves end-to-end:
 *  1. [ScanClient.amuletRulesConfig] decodes the live AmuletRules — on
 *     LocalNet (splice 0.7.1, post-CIP-0078) a zero transfer-fee schedule
 *     plus the real synchronizer traffic pricing;
 *  2. [ScanClient.openMiningRounds] reads the live open rounds and their
 *     amulet price;
 *  3. [TransferFeeEstimator] previews 0 for a 5 CC transfer on that config;
 *  4. the honest check: a real two-step transfer's sender net equals
 *     −(amount + estimated fee) — which on LocalNet's zero-fee config means
 *     exactly −amount — read back through the public history API.
 */
class LocalNetFeePreviewIntegrationTest {

    private val enabled = System.getenv("SPLICE_LOCALNET") == "1"

    @Test
    fun `live config decodes, estimate is zero, and a real transfer confirms it`() {
        assumeTrue(enabled, "SPLICE_LOCALNET not set; skipping LocalNet test")
        val support = LocalNetTokenStandardIntegrationTest()
        val host = System.getenv("SPLICE_LOCALNET_LEDGER_HOST") ?: "127.0.0.1"
        val port = (System.getenv("SPLICE_LOCALNET_LEDGER_PORT") ?: "2901").toInt()
        val registryUrl = System.getenv("SPLICE_LOCALNET_REGISTRY_URL") ?: "http://scan.localhost:4000"
        val scanUrl = System.getenv("SPLICE_LOCALNET_SCAN_URL") ?: "http://scan.localhost:4000/api/scan"
        val adminUser = System.getenv("SPLICE_LOCALNET_ADMIN_USER") ?: "ledger-api-user"
        val walletUser = System.getenv("SPLICE_LOCALNET_WALLET_USER") ?: "app-user"
        val amount = BigDecimal("5.0")
        val memo = "fee preview probe ${java.util.UUID.randomUUID()}"

        runBlocking {
            val plain = OkHttpChannelBuilder.forAddress(host, port).usePlaintext().build()
            try {
                val scan = ScanClient(scanUrl, support.http)

                // 1. The live AmuletRules config: LocalNet ships splice
                // 0.7.1, where CIP-0078/0107 pin every transfer fee to zero.
                val config = scan.amuletRulesConfig()
                println("transfer fees: ${config.transferFees}")
                println("synchronizer fees: ${config.synchronizerFees}")
                assertEquals(0, BigDecimal.ZERO.compareTo(config.transferFees.createFeeUsd))
                assertEquals(0, BigDecimal.ZERO.compareTo(config.transferFees.transferFee.initialRate))
                assertTrue(config.transferFees.transferFee.steps.isEmpty(), "steps must be empty")
                assertEquals(0, BigDecimal.ZERO.compareTo(config.transferFees.lockHolderFeeUsd))
                assertTrue(
                    config.transferFees.holdingFeeUsdPerRound.signum() > 0,
                    "LocalNet still publishes a non-zero holding fee rate",
                )
                assertTrue(
                    config.synchronizerFees.extraTrafficPriceUsdPerMB.signum() > 0,
                    "extra traffic must have a price",
                )
                assertTrue(config.synchronizerFees.minTopupAmountBytes > 0)
                assertTrue(config.activeSynchronizerId.contains("::"), "synchronizer id must be set")

                // 2. Open rounds carry the amulet price the estimate converts at.
                val rounds = scan.openMiningRounds()
                println("open rounds: ${rounds.map { "${it.roundNumber}@${it.amuletPriceUsd}" }}")
                assertTrue(rounds.isNotEmpty(), "LocalNet must have open mining rounds")
                val usable = assertNotNull(rounds.latestUsable(), "an open round must be usable")
                assertTrue(usable.amuletPriceUsd.signum() > 0, "amulet price must be positive")

                // 3. The preview: zero-fee schedule → zero, whatever the price.
                val estimate = TransferFeeEstimator.estimate(
                    schedule = config.transferFees,
                    amuletPriceUsd = usable.amuletPriceUsd,
                    amountCc = amount,
                )
                println("estimate for $amount CC: $estimate")
                assertEquals(0, BigDecimal.ZERO.compareTo(estimate.feeCc), "feeCc must be 0")
                assertEquals(0, BigDecimal.ZERO.compareTo(estimate.feeUsd), "feeUsd must be 0")

                // 4. The honest check: send 5 CC for real; the sender's net
                // must equal −(amount + estimate) — exactly −5 CC here.
                val adminChannel = support.authed(plain, adminUser)
                val walletChannel = support.authed(plain, walletUser)
                val registry = TransferRegistryClient(registryUrl, support.http)

                val walletParty = support.onboardWalletUser()
                support.tap("50.0")
                val walletTokens = TokenStandardClient(walletChannel, registry)
                val holdings = retryUntil("wallet holdings visible") {
                    walletTokens.listHoldings(walletParty)
                        .filter { it.lock == null }.ifEmpty { null }
                }
                val amulet = holdings.first().instrumentId

                val driver = SoftwareSigningDriver.generate(SoftwareSigningDriver.Algorithm.EC_P256)
                val parties = ExternalPartyClient(adminChannel)
                val synchronizer = parties.connectedSynchronizers().first()
                val external = parties.allocate(driver, synchronizer, "feepreviewreceiver", userId = adminUser)

                val senderBegin = StateServiceGrpcKt.StateServiceCoroutineStub(walletChannel)
                    .getLedgerEnd(GetLedgerEndRequest.getDefaultInstance()).offset

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

                val externalTokens = TokenStandardClient(adminChannel, registry)
                val instruction = retryUntil("transfer instruction in inbox") {
                    externalTokens.pendingTransferInstructions(external.partyId).firstOrNull()
                }
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

                val senderRows = walletTokens
                    .holdingsHistory(walletParty, beginExclusive = senderBegin)
                    .filter { it.summary?.memo == memo }
                assertTrue(senderRows.isNotEmpty(), "sender history must contain the memo rows")
                val senderNet = senderRows.sumOf { it.summary!!.amount }
                val expectedNet = amount.add(estimate.feeCc).negate()
                println("sender net: $senderNet, expected −(amount + estimate) = $expectedNet")
                assertEquals(
                    0, expectedNet.compareTo(senderNet),
                    "sender net must equal −(amount + estimated fee): expected $expectedNet, was $senderNet",
                )
            } finally {
                plain.shutdownNow()
            }
        }
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
