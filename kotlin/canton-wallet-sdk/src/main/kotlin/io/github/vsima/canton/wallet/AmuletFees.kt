// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

/**
 * A piecewise fee rate over a USD amount — Splice's `Splice.Fees.SteppedRate`.
 *
 * Semantics (from `chargeSteppedRate` in splice's
 * `daml/splice-amulet/daml/Splice/Fees.daml`): the rate applies **per
 * tranche**, like tax brackets. Step boundaries are *absolute* amounts, not
 * tranche widths: `SteppedRate(0.01, [(100, 0.001), (1000, 0.0001),
 * (1000000, 0.00001)])` charges 1% on the first 100, 0.1% between 100 and
 * 1000, 0.01% between 1000 and 1000000, and 0.001% above that.
 */
public data class SteppedRate(
    /** The rate charged on the amount below the first step boundary. */
    val initialRate: BigDecimal,
    /** Steps in ascending boundary order (validated on-ledger). */
    val steps: List<Step>,
) {
    /** One step: [rate] applies to the amount above [boundary] (up to the next boundary). */
    public data class Step(
        /** Absolute amount at which [rate] takes over from the previous tranche's rate. */
        val boundary: BigDecimal,
        val rate: BigDecimal,
    )

    /**
     * The fee this schedule charges on [amount], per Splice's
     * `chargeSteppedRate`: each tranche's width times its rate, summed.
     * Amounts `<= 0` charge zero. Every tranche product is rounded to 10
     * decimal places half-even, mirroring Daml `Decimal` arithmetic.
     */
    public fun charge(amount: BigDecimal): BigDecimal {
        if (amount.signum() <= 0) return BigDecimal.ZERO
        var fee = BigDecimal.ZERO
        var trancheFloor = BigDecimal.ZERO
        var rate = initialRate
        for ((boundary, stepRate) in steps) {
            if (amount <= boundary) break
            fee = fee.add(boundary.subtract(trancheFloor).multiply(rate).damlScale())
            trancheFloor = boundary
            rate = stepRate
        }
        return fee.add(amount.subtract(trancheFloor).multiply(rate).damlScale())
    }

    private fun BigDecimal.damlScale(): BigDecimal = setScale(10, RoundingMode.HALF_EVEN)
}

/**
 * The transfer-fee schedule published in AmuletRules' `transferConfig` —
 * **denominated in USD** and converted to Amulet at the open mining round's
 * price when charged (Splice scales the whole config by `1 / amuletPrice`;
 * `scaleFees` in `Splice.AmuletRules`).
 *
 * Honesty note: CIP-0078 set every CC transfer fee to zero by governance
 * vote, and CIP-0107 (splice 0.5.16) made non-zero values unrepresentable —
 * on today's Canton Network configs every field below except
 * [holdingFeeUsdPerRound] is zero and [TransferFeeEstimator] returns zero.
 * The schedule is still decoded faithfully so previews stay correct against
 * older networks and forks whose configs do charge fees.
 */
public data class TransferFeeSchedule(
    /** Fixed USD fee to create one output amulet (`createFee.fee`). */
    val createFeeUsd: BigDecimal,
    /** Proportional fee over the transferred USD value (`transferFee`). */
    val transferFee: SteppedRate,
    /**
     * USD charged per amulet per mining round held (`holdingFee.rate`).
     * Deducted when a holding is expired/merged — not by transfers (since
     * CIP-0078 transfers no longer charge holding fees on inputs).
     */
    val holdingFeeUsdPerRound: BigDecimal,
    /** Fixed USD fee per lock holder of a locked output (`lockHolderFee.fee`). */
    val lockHolderFeeUsd: BigDecimal,
)

/**
 * The synchronizer usage-fee block published in AmuletRules
 * (`decentralizedSynchronizer.fees`, Splice's `SynchronizerFeesConfig`) —
 * what traffic costs and how much comes free.
 */
public data class SynchronizerFeeConfig(
    /** Price of extra (purchased) traffic, in USD per MB (10^6 bytes). */
    val extraTrafficPriceUsdPerMB: BigDecimal,
    /**
     * The smallest extra-traffic purchase the network accepts, in bytes —
     * [ValidatorClient.buyTraffic] below this is rejected.
     */
    val minTopupAmountBytes: Long,
    /** Free base-rate traffic: at most this many bytes per [baseRateBurstWindow]. */
    val baseRateBurstAmountBytes: Long,
    /** The sliding window over which [baseRateBurstAmountBytes] is granted. */
    val baseRateBurstWindow: Duration,
    /**
     * Cost of reads relative to writes, in parts per 10,000 (e.g. `4` means
     * delivering a message to one recipient costs 4/10000 of writing it).
     */
    val readVsWriteScalingFactor: Long,
)

/**
 * The AmuletRules configuration effective at one instant, decoded from
 * scan's `/v0/amulet-rules` (see [ScanClient.amuletRulesConfig]).
 */
public data class AmuletRulesConfig(
    /** The USD-denominated transfer-fee schedule (`transferConfig`). */
    val transferFees: TransferFeeSchedule,
    /** Synchronizer traffic pricing (`decentralizedSynchronizer.fees`). */
    val synchronizerFees: SynchronizerFeeConfig,
    /**
     * The active synchronizer's id
     * (`decentralizedSynchronizer.activeSynchronizer`) — the `synchronizerId`
     * to buy traffic on and read traffic status for.
     */
    val activeSynchronizerId: String,
)

/**
 * One open mining round from scan's `/v0/open-and-issuing-mining-rounds`
 * (see [ScanClient.openMiningRounds]) — carries the USD price of Amulet
 * that fees and taps convert at.
 */
public data class OpenMiningRound(
    /** The round number; rounds open every tick (10 minutes on Splice defaults). */
    val roundNumber: Long,
    /** The round's amulet price in USD per CC. */
    val amuletPriceUsd: BigDecimal,
    /** When this round becomes usable for submissions. */
    val opensAt: Instant,
    /** When the round is expected to stop accepting submissions. */
    val targetClosesAt: Instant,
)

/**
 * The round a submission "now" would execute against — the newest round
 * already open at [at], mirroring Splice's `latestUsableRound` selection.
 * Null when no round has opened yet (right after network bootstrap).
 */
public fun List<OpenMiningRound>.latestUsable(at: Instant = Instant.now()): OpenMiningRound? =
    filter { !it.opensAt.isAfter(at) }.maxByOrNull { it.roundNumber }

/**
 * A pure, deterministic preview of the fees a two-output Amulet transfer
 * would be charged — "Fee: ~X CC" before sending. No I/O: feed it
 * [ScanClient.amuletRulesConfig] and a round price from
 * [ScanClient.openMiningRounds].
 */
public object TransferFeeEstimator {

    /** The estimated fees of one transfer, total and by component. */
    public data class Estimate(
        /** Total estimated fee in CC ([feeUsd] converted at the round price). */
        val feeCc: BigDecimal,
        /** Total estimated fee in USD: [createFeesUsd] + [transferFeeUsd]. */
        val feeUsd: BigDecimal,
        /** Fixed create fees: `createFeeUsd × outputCount`. */
        val createFeesUsd: BigDecimal,
        /** The proportional stepped fee over the transferred USD value. */
        val transferFeeUsd: BigDecimal,
    )

    /**
     * Estimates the fee for sending [amountCc] with [outputCount] created
     * outputs, converted at [amuletPriceUsd].
     *
     * Mirrors the fee arithmetic of Splice's classic (pre-CIP-0078)
     * `AmuletRules_Transfer`: fees are **denominated in USD** — the amount
     * is converted to USD at the round price, the stepped rate is charged
     * per tranche ([SteppedRate.charge]), each created output pays the fixed
     * create fee, and the USD total converts back to CC at the same price.
     * On current Canton Network configs every schedule value is zero
     * (CIP-0078/0107 — see [TransferFeeSchedule]) and the estimate is zero.
     *
     * This is an **estimate**, not a quote:
     * - fees convert at the price of the round the transfer *executes* in,
     *   which can differ from the round previewed here (prices move between
     *   rounds; rounds tick every ~10 minutes);
     * - holding fees accrued on the *input* holdings are excluded — they
     *   depend on which inputs the wallet selects (and since CIP-0078,
     *   transfers do not charge them at all; they only reduce value when a
     *   holding is expired);
     * - lock-holder fees are excluded (no output of a plain transfer is
     *   locked).
     *
     * @param schedule the USD fee schedule from [ScanClient.amuletRulesConfig].
     * @param amuletPriceUsd the open round's USD price per CC, > 0 — pick
     *   the round via [latestUsable].
     * @param amountCc the CC amount being sent to the receiver, > 0.
     * @param outputCount created outputs paying the fixed create fee.
     *   Default 2: one receiver output + one sender change output — the
     *   wallet default for a simple send (senders with exact-amount inputs
     *   produce no change; pass 1 then).
     */
    public fun estimate(
        schedule: TransferFeeSchedule,
        amuletPriceUsd: BigDecimal,
        amountCc: BigDecimal,
        outputCount: Int = 2,
    ): Estimate {
        require(amuletPriceUsd.signum() > 0) { "amuletPriceUsd must be > 0, got $amuletPriceUsd" }
        require(amountCc.signum() > 0) { "amountCc must be > 0, got $amountCc" }
        require(outputCount >= 1) { "outputCount must be >= 1, got $outputCount" }
        val amountUsd = amountCc.multiply(amuletPriceUsd).setScale(10, RoundingMode.HALF_EVEN)
        val transferFeeUsd = schedule.transferFee.charge(amountUsd)
        val createFeesUsd = schedule.createFeeUsd.multiply(BigDecimal(outputCount))
        val feeUsd = createFeesUsd.add(transferFeeUsd)
        return Estimate(
            feeCc = feeUsd.divide(amuletPriceUsd, 10, RoundingMode.HALF_EVEN),
            feeUsd = feeUsd,
            createFeesUsd = createFeesUsd,
            transferFeeUsd = transferFeeUsd,
        )
    }
}
