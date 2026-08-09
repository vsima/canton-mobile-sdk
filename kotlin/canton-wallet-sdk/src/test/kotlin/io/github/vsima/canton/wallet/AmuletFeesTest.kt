// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet

import java.io.File
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The fee preview held to Splice's own numbers. The realistic schedule is
 * the historical MainNet-default `transferConfigUsd` published in Splice's
 * scan bulk-data docs (docs example OpenMiningRound: createFee $0.03,
 * holdingFee rate 0.0000190259/round, transferFee initialRate 0.01 with
 * steps at 100/1000/1000000 → 0.001/0.0001/0.00001, lockHolderFee $0.005);
 * step semantics per `chargeSteppedRate` in splice
 * `daml/splice-amulet/daml/Splice/Fees.daml` — per-tranche rates over
 * absolute boundaries, like tax brackets. The Swift twin runs the same
 * cases; both SDKs must agree.
 */
class AmuletFeesTest {

    /** The documented MainNet-launch schedule (see class doc for provenance). */
    private val mainNetLikeSchedule = TransferFeeSchedule(
        createFeeUsd = BigDecimal("0.03"),
        transferFee = SteppedRate(
            initialRate = BigDecimal("0.01"),
            steps = listOf(
                SteppedRate.Step(BigDecimal("100"), BigDecimal("0.001")),
                SteppedRate.Step(BigDecimal("1000"), BigDecimal("0.0001")),
                SteppedRate.Step(BigDecimal("1000000"), BigDecimal("0.00001")),
            ),
        ),
        holdingFeeUsdPerRound = BigDecimal("0.0000190259"),
        lockHolderFeeUsd = BigDecimal("0.005"),
    )

    /** LocalNet's (and post-CIP-0078 networks') zero-fee schedule. */
    private val zeroFeeSchedule = TransferFeeSchedule(
        createFeeUsd = BigDecimal("0.0"),
        transferFee = SteppedRate(BigDecimal("0.0"), emptyList()),
        holdingFeeUsdPerRound = BigDecimal("0.0000190259"),
        lockHolderFeeUsd = BigDecimal("0.0"),
    )

    private fun assertDecimalEquals(expected: String, actual: BigDecimal, what: String = "") {
        assertEquals(
            0, BigDecimal(expected).compareTo(actual),
            "$what: expected $expected, was $actual",
        )
    }

    @Test
    fun `stepped rate charges per tranche across every boundary`() {
        val rate = mainNetLikeSchedule.transferFee
        // Below the first boundary: flat initial rate.
        assertDecimalEquals("0.5", rate.charge(BigDecimal("50")), "charge(50)")
        // At the boundary the whole amount is still in the first tranche.
        assertDecimalEquals("1.0", rate.charge(BigDecimal("100")), "charge(100)")
        // 100 at 1% + 50 at 0.1%.
        assertDecimalEquals("1.05", rate.charge(BigDecimal("150")), "charge(150)")
        // 100 at 1% + 900 at 0.1%.
        assertDecimalEquals("1.9", rate.charge(BigDecimal("1000")), "charge(1000)")
        // + 1000 at 0.01%.
        assertDecimalEquals("2.0", rate.charge(BigDecimal("2000")), "charge(2000)")
        // + 999000 at 0.01% = 101.8 at the top boundary.
        assertDecimalEquals("101.8", rate.charge(BigDecimal("1000000")), "charge(1000000)")
        // + 1500000 at 0.001% above every step.
        assertDecimalEquals("116.8", rate.charge(BigDecimal("2500000")), "charge(2500000)")
        // Nothing to charge on zero or negative amounts.
        assertDecimalEquals("0", rate.charge(BigDecimal.ZERO), "charge(0)")
        assertDecimalEquals("0", rate.charge(BigDecimal("-5")), "charge(-5)")
    }

    @Test
    fun `estimates convert USD fees at the round price with exact values`() {
        // 30000 CC at 0.005 USD/CC = 150 USD: stepped fee 1.05, create fees
        // 2 × 0.03, total 1.11 USD = 222 CC.
        val estimate = TransferFeeEstimator.estimate(
            schedule = mainNetLikeSchedule,
            amuletPriceUsd = BigDecimal("0.005"),
            amountCc = BigDecimal("30000"),
        )
        assertDecimalEquals("1.05", estimate.transferFeeUsd, "transferFeeUsd")
        assertDecimalEquals("0.06", estimate.createFeesUsd, "createFeesUsd")
        assertDecimalEquals("1.11", estimate.feeUsd, "feeUsd")
        assertDecimalEquals("222", estimate.feeCc, "feeCc")

        // Exactly at the first boundary: 20000 CC = 100 USD → 1.00 + 0.06.
        val boundary = TransferFeeEstimator.estimate(
            schedule = mainNetLikeSchedule,
            amuletPriceUsd = BigDecimal("0.005"),
            amountCc = BigDecimal("20000"),
        )
        assertDecimalEquals("1.06", boundary.feeUsd, "boundary feeUsd")
        assertDecimalEquals("212", boundary.feeCc, "boundary feeCc")

        // A single output (no change) pays one create fee.
        val oneOutput = TransferFeeEstimator.estimate(
            schedule = mainNetLikeSchedule,
            amuletPriceUsd = BigDecimal("0.005"),
            amountCc = BigDecimal("30000"),
            outputCount = 1,
        )
        assertDecimalEquals("0.03", oneOutput.createFeesUsd, "single-output createFeesUsd")
        assertDecimalEquals("1.08", oneOutput.feeUsd, "single-output feeUsd")

        // Sub-cent price: 1 CC at 0.003 USD → 0.00003 stepped + 0.06 create
        // = 0.06003 USD = 20.01 CC.
        val small = TransferFeeEstimator.estimate(
            schedule = mainNetLikeSchedule,
            amuletPriceUsd = BigDecimal("0.003"),
            amountCc = BigDecimal("1"),
        )
        assertDecimalEquals("0.06003", small.feeUsd, "small feeUsd")
        assertDecimalEquals("20.01", small.feeCc, "small feeCc")
    }

    @Test
    fun `zero-fee schedules estimate zero, as on current networks`() {
        val estimate = TransferFeeEstimator.estimate(
            schedule = zeroFeeSchedule,
            amuletPriceUsd = BigDecimal("0.005"),
            amountCc = BigDecimal("5.0"),
        )
        assertDecimalEquals("0", estimate.feeCc, "feeCc")
        assertDecimalEquals("0", estimate.feeUsd, "feeUsd")
        assertDecimalEquals("0", estimate.createFeesUsd, "createFeesUsd")
        assertDecimalEquals("0", estimate.transferFeeUsd, "transferFeeUsd")
    }

    @Test
    fun `estimator rejects nonsensical arguments`() {
        assertFailsWith<IllegalArgumentException> {
            TransferFeeEstimator.estimate(zeroFeeSchedule, BigDecimal.ZERO, BigDecimal.ONE)
        }
        assertFailsWith<IllegalArgumentException> {
            TransferFeeEstimator.estimate(zeroFeeSchedule, BigDecimal("0.005"), BigDecimal.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            TransferFeeEstimator.estimate(
                zeroFeeSchedule, BigDecimal("0.005"), BigDecimal.ONE, outputCount = 0,
            )
        }
    }

    // -- decoding ----------------------------------------------------------

    private fun fixture(name: String): JsonObject =
        Json.parseToJsonElement(File("../../testdata/scan/$name").readText()).jsonObject

    @Test
    fun `decodes the captured LocalNet amulet-rules response`() {
        val config = ScanClient.decodeAmuletRulesConfig(
            fixture("amulet-rules-v0.json"), Instant.parse("2026-08-09T20:00:00Z"),
        )
        assertDecimalEquals("0.0", config.transferFees.createFeeUsd, "createFeeUsd")
        assertDecimalEquals("0.0", config.transferFees.transferFee.initialRate, "initialRate")
        assertTrue(config.transferFees.transferFee.steps.isEmpty(), "steps must be empty")
        assertDecimalEquals(
            "0.0000190259", config.transferFees.holdingFeeUsdPerRound, "holdingFeeUsdPerRound",
        )
        assertDecimalEquals("0.0", config.transferFees.lockHolderFeeUsd, "lockHolderFeeUsd")

        assertDecimalEquals(
            "16.67", config.synchronizerFees.extraTrafficPriceUsdPerMB, "extraTrafficPrice",
        )
        assertEquals(200000L, config.synchronizerFees.minTopupAmountBytes)
        assertEquals(400000L, config.synchronizerFees.baseRateBurstAmountBytes)
        assertEquals(Duration.ofSeconds(1200), config.synchronizerFees.baseRateBurstWindow)
        assertEquals(4L, config.synchronizerFees.readVsWriteScalingFactor)
        assertEquals(
            "global-domain::12206b93aa60fc251f939fd727b46a65a64991243600e6d8f1ba0099c3a5b331d924",
            config.activeSynchronizerId,
        )
    }

    @Test
    fun `resolves the config schedule the way the ledger does`() {
        val json = fixture("amulet-rules-with-fees.json")

        // Before the future value: the MainNet-like fee schedule.
        val before = ScanClient.decodeAmuletRulesConfig(json, Instant.parse("2026-01-01T00:00:00Z"))
        assertDecimalEquals("0.03", before.transferFees.createFeeUsd, "createFeeUsd before")
        assertDecimalEquals("0.01", before.transferFees.transferFee.initialRate, "initialRate before")
        assertEquals(3, before.transferFees.transferFee.steps.size)
        assertDecimalEquals("100", before.transferFees.transferFee.steps[0].boundary, "step 0 boundary")
        assertDecimalEquals("0.001", before.transferFees.transferFee.steps[0].rate, "step 0 rate")
        assertDecimalEquals("1000000", before.transferFees.transferFee.steps[2].boundary, "step 2 boundary")
        assertDecimalEquals("0.00001", before.transferFees.transferFee.steps[2].rate, "step 2 rate")
        assertDecimalEquals("0.005", before.transferFees.lockHolderFeeUsd, "lockHolderFeeUsd before")
        assertEquals(200000L, before.synchronizerFees.minTopupAmountBytes)

        // At the boundary the future value is already effective
        // (Splice.Schedule.getValueAsOf uses time < effectiveAsOf).
        val atBoundary =
            ScanClient.decodeAmuletRulesConfig(json, Instant.parse("2030-01-01T00:00:00Z"))
        assertDecimalEquals("0.0", atBoundary.transferFees.createFeeUsd, "createFeeUsd at boundary")

        // Past it: the zero-fee config with its own synchronizer fees.
        val after = ScanClient.decodeAmuletRulesConfig(json, Instant.parse("2031-06-01T00:00:00Z"))
        assertDecimalEquals("0.0", after.transferFees.createFeeUsd, "createFeeUsd after")
        assertTrue(after.transferFees.transferFee.steps.isEmpty(), "steps after must be empty")
        assertDecimalEquals(
            "20.0", after.synchronizerFees.extraTrafficPriceUsdPerMB, "extraTrafficPrice after",
        )
        assertEquals(250000L, after.synchronizerFees.minTopupAmountBytes)
        assertEquals(Duration.ofSeconds(1800), after.synchronizerFees.baseRateBurstWindow)
    }

    @Test
    fun `decodes the captured open mining rounds and picks the usable one`() {
        val rounds = ScanClient.decodeOpenMiningRounds(fixture("open-mining-rounds-v0.json"))
        assertEquals(listOf(129L, 130L, 131L), rounds.map { it.roundNumber })
        rounds.forEach { assertDecimalEquals("0.005", it.amuletPriceUsd, "round ${it.roundNumber} price") }
        assertEquals(Instant.parse("2026-08-09T19:38:01.304622Z"), rounds[0].opensAt)
        assertEquals(Instant.parse("2026-08-09T19:58:01.304622Z"), rounds[0].targetClosesAt)

        // Round 131 hasn't opened yet at 19:50 — the usable round is 130.
        val usable = rounds.latestUsable(Instant.parse("2026-08-09T19:50:00Z"))
        assertEquals(130L, usable?.roundNumber)
        // Before any round opened there is nothing usable.
        assertNull(rounds.latestUsable(Instant.parse("2026-08-09T00:00:00Z")))
    }

    @Test
    fun `decodes member traffic status and rejects malformed payloads`() {
        val status = ScanClient.decodeMemberTrafficStatus(
            Json.parseToJsonElement(
                """{"actual":{"total_consumed":7,"total_limit":1200000},
                    "target":{"total_purchased":1400000}}"""
            ).jsonObject
        )
        assertEquals(7L, status.totalConsumedBytes)
        assertEquals(1200000L, status.totalLimitBytes)
        assertEquals(1400000L, status.totalPurchasedBytes)

        assertFailsWith<ScanException> {
            ScanClient.decodeMemberTrafficStatus(
                Json.parseToJsonElement("""{"actual":{"total_consumed":7}}""").jsonObject
            )
        }
        assertFailsWith<ScanException> {
            ScanClient.decodeAmuletRulesConfig(
                Json.parseToJsonElement("""{"amulet_rules_update":{}}""").jsonObject,
                Instant.now(),
            )
        }
    }
}
