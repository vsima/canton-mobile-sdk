// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// A piecewise fee rate over a USD amount — Splice's `Splice.Fees.SteppedRate`.
///
/// Semantics (from `chargeSteppedRate` in splice's
/// `daml/splice-amulet/daml/Splice/Fees.daml`): the rate applies **per
/// tranche**, like tax brackets. Step boundaries are *absolute* amounts, not
/// tranche widths: `SteppedRate(0.01, [(100, 0.001), (1000, 0.0001),
/// (1000000, 0.00001)])` charges 1% on the first 100, 0.1% between 100 and
/// 1000, 0.01% between 1000 and 1000000, and 0.001% above that.
public struct SteppedRate: Sendable, Equatable {
    /// One step: `rate` applies to the amount above `boundary` (up to the next boundary).
    public struct Step: Sendable, Equatable {
        /// Absolute amount at which `rate` takes over from the previous tranche's rate.
        public let boundary: Decimal
        public let rate: Decimal

        public init(boundary: Decimal, rate: Decimal) {
            self.boundary = boundary
            self.rate = rate
        }
    }

    /// The rate charged on the amount below the first step boundary.
    public let initialRate: Decimal
    /// Steps in ascending boundary order (validated on-ledger).
    public let steps: [Step]

    public init(initialRate: Decimal, steps: [Step]) {
        self.initialRate = initialRate
        self.steps = steps
    }

    /// The fee this schedule charges on `amount`, per Splice's
    /// `chargeSteppedRate`: each tranche's width times its rate, summed.
    /// Amounts `<= 0` charge zero. Every tranche product is rounded to 10
    /// decimal places bankers-style, mirroring Daml `Decimal` arithmetic.
    public func charge(_ amount: Decimal) -> Decimal {
        guard amount > 0 else { return 0 }
        var fee = Decimal(0)
        var trancheFloor = Decimal(0)
        var rate = initialRate
        for step in steps {
            if amount <= step.boundary { break }
            fee += Self.damlScaled((step.boundary - trancheFloor) * rate)
            trancheFloor = step.boundary
            rate = step.rate
        }
        return fee + Self.damlScaled((amount - trancheFloor) * rate)
    }

    static func damlScaled(_ value: Decimal) -> Decimal {
        var input = value
        var result = Decimal()
        NSDecimalRound(&result, &input, 10, .bankers)
        return result
    }
}

/// The transfer-fee schedule published in AmuletRules' `transferConfig` —
/// **denominated in USD** and converted to Amulet at the open mining round's
/// price when charged (Splice scales the whole config by `1 / amuletPrice`;
/// `scaleFees` in `Splice.AmuletRules`).
///
/// Honesty note: CIP-0078 set every CC transfer fee to zero by governance
/// vote, and CIP-0107 (splice 0.5.16) made non-zero values unrepresentable —
/// on today's Canton Network configs every field below except
/// ``holdingFeeUsdPerRound`` is zero and ``TransferFeeEstimator`` returns
/// zero. The schedule is still decoded faithfully so previews stay correct
/// against older networks and forks whose configs do charge fees.
public struct TransferFeeSchedule: Sendable, Equatable {
    /// Fixed USD fee to create one output amulet (`createFee.fee`).
    public let createFeeUsd: Decimal
    /// Proportional fee over the transferred USD value (`transferFee`).
    public let transferFee: SteppedRate
    /// USD charged per amulet per mining round held (`holdingFee.rate`).
    /// Deducted when a holding is expired/merged — not by transfers (since
    /// CIP-0078 transfers no longer charge holding fees on inputs).
    public let holdingFeeUsdPerRound: Decimal
    /// Fixed USD fee per lock holder of a locked output (`lockHolderFee.fee`).
    public let lockHolderFeeUsd: Decimal

    public init(
        createFeeUsd: Decimal,
        transferFee: SteppedRate,
        holdingFeeUsdPerRound: Decimal,
        lockHolderFeeUsd: Decimal
    ) {
        self.createFeeUsd = createFeeUsd
        self.transferFee = transferFee
        self.holdingFeeUsdPerRound = holdingFeeUsdPerRound
        self.lockHolderFeeUsd = lockHolderFeeUsd
    }
}

/// The synchronizer usage-fee block published in AmuletRules
/// (`decentralizedSynchronizer.fees`, Splice's `SynchronizerFeesConfig`) —
/// what traffic costs and how much comes free.
public struct SynchronizerFeeConfig: Sendable, Equatable {
    /// Price of extra (purchased) traffic, in USD per MB (10^6 bytes).
    public let extraTrafficPriceUsdPerMB: Decimal
    /// The smallest extra-traffic purchase the network accepts, in bytes —
    /// ``ValidatorClient/buyTraffic(trafficAmountBytes:receivingValidatorPartyId:synchronizerId:trackingId:expiresAt:)``
    /// below this is rejected.
    public let minTopupAmountBytes: Int64
    /// Free base-rate traffic: at most this many bytes per ``baseRateBurstWindow``.
    public let baseRateBurstAmountBytes: Int64
    /// The sliding window over which ``baseRateBurstAmountBytes`` is granted.
    public let baseRateBurstWindow: Duration
    /// Cost of reads relative to writes, in parts per 10,000 (e.g. `4` means
    /// delivering a message to one recipient costs 4/10000 of writing it).
    public let readVsWriteScalingFactor: Int64

    public init(
        extraTrafficPriceUsdPerMB: Decimal,
        minTopupAmountBytes: Int64,
        baseRateBurstAmountBytes: Int64,
        baseRateBurstWindow: Duration,
        readVsWriteScalingFactor: Int64
    ) {
        self.extraTrafficPriceUsdPerMB = extraTrafficPriceUsdPerMB
        self.minTopupAmountBytes = minTopupAmountBytes
        self.baseRateBurstAmountBytes = baseRateBurstAmountBytes
        self.baseRateBurstWindow = baseRateBurstWindow
        self.readVsWriteScalingFactor = readVsWriteScalingFactor
    }
}

/// The AmuletRules configuration effective at one instant, decoded from
/// scan's `/v0/amulet-rules` (see ``ScanClient/amuletRulesConfig(asOf:)``).
public struct AmuletRulesConfig: Sendable, Equatable {
    /// The USD-denominated transfer-fee schedule (`transferConfig`).
    public let transferFees: TransferFeeSchedule
    /// Synchronizer traffic pricing (`decentralizedSynchronizer.fees`).
    public let synchronizerFees: SynchronizerFeeConfig
    /// The active synchronizer's id
    /// (`decentralizedSynchronizer.activeSynchronizer`) — the
    /// `synchronizerId` to buy traffic on and read traffic status for.
    public let activeSynchronizerId: String

    public init(
        transferFees: TransferFeeSchedule,
        synchronizerFees: SynchronizerFeeConfig,
        activeSynchronizerId: String
    ) {
        self.transferFees = transferFees
        self.synchronizerFees = synchronizerFees
        self.activeSynchronizerId = activeSynchronizerId
    }
}

/// One open mining round from scan's `/v0/open-and-issuing-mining-rounds`
/// (see ``ScanClient/openMiningRounds()``) — carries the USD price of Amulet
/// that fees and taps convert at.
public struct OpenMiningRound: Sendable, Equatable {
    /// The round number; rounds open every tick (10 minutes on Splice defaults).
    public let roundNumber: Int64
    /// The round's amulet price in USD per CC.
    public let amuletPriceUsd: Decimal
    /// When this round becomes usable for submissions.
    public let opensAt: Date
    /// When the round is expected to stop accepting submissions.
    public let targetClosesAt: Date

    public init(roundNumber: Int64, amuletPriceUsd: Decimal, opensAt: Date, targetClosesAt: Date) {
        self.roundNumber = roundNumber
        self.amuletPriceUsd = amuletPriceUsd
        self.opensAt = opensAt
        self.targetClosesAt = targetClosesAt
    }
}

extension Array where Element == OpenMiningRound {
    /// The round a submission "now" would execute against — the newest round
    /// already open at `date`, mirroring Splice's `latestUsableRound`
    /// selection. Nil when no round has opened yet (right after network
    /// bootstrap).
    public func latestUsable(at date: Date = Date()) -> OpenMiningRound? {
        filter { $0.opensAt <= date }.max { $0.roundNumber < $1.roundNumber }
    }
}

/// A pure, deterministic preview of the fees a two-output Amulet transfer
/// would be charged — "Fee: ~X CC" before sending. No I/O: feed it
/// ``ScanClient/amuletRulesConfig(asOf:)`` and a round price from
/// ``ScanClient/openMiningRounds()``.
public enum TransferFeeEstimator {

    /// The estimated fees of one transfer, total and by component.
    public struct Estimate: Sendable, Equatable {
        /// Total estimated fee in CC (``feeUsd`` converted at the round price).
        public let feeCc: Decimal
        /// Total estimated fee in USD: ``createFeesUsd`` + ``transferFeeUsd``.
        public let feeUsd: Decimal
        /// Fixed create fees: `createFeeUsd × outputCount`.
        public let createFeesUsd: Decimal
        /// The proportional stepped fee over the transferred USD value.
        public let transferFeeUsd: Decimal
    }

    /// Estimates the fee for sending `amountCc` with `outputCount` created
    /// outputs, converted at `amuletPriceUsd`.
    ///
    /// Mirrors the fee arithmetic of Splice's classic (pre-CIP-0078)
    /// `AmuletRules_Transfer`: fees are **denominated in USD** — the amount
    /// is converted to USD at the round price, the stepped rate is charged
    /// per tranche (``SteppedRate/charge(_:)``), each created output pays
    /// the fixed create fee, and the USD total converts back to CC at the
    /// same price. On current Canton Network configs every schedule value is
    /// zero (CIP-0078/0107 — see ``TransferFeeSchedule``) and the estimate
    /// is zero.
    ///
    /// This is an **estimate**, not a quote:
    /// - fees convert at the price of the round the transfer *executes* in,
    ///   which can differ from the round previewed here (prices move between
    ///   rounds; rounds tick every ~10 minutes);
    /// - holding fees accrued on the *input* holdings are excluded — they
    ///   depend on which inputs the wallet selects (and since CIP-0078,
    ///   transfers do not charge them at all; they only reduce value when a
    ///   holding is expired);
    /// - lock-holder fees are excluded (no output of a plain transfer is
    ///   locked).
    ///
    /// - Parameters:
    ///   - schedule: the USD fee schedule from ``ScanClient/amuletRulesConfig(asOf:)``.
    ///   - amuletPriceUsd: the open round's USD price per CC, > 0 — pick the
    ///     round via ``Swift/Array/latestUsable(at:)``.
    ///   - amountCc: the CC amount being sent to the receiver, > 0.
    ///   - outputCount: created outputs paying the fixed create fee.
    ///     Default 2: one receiver output + one sender change output — the
    ///     wallet default for a simple send (senders with exact-amount
    ///     inputs produce no change; pass 1 then).
    public static func estimate(
        schedule: TransferFeeSchedule,
        amuletPriceUsd: Decimal,
        amountCc: Decimal,
        outputCount: Int = 2
    ) -> Estimate {
        precondition(amuletPriceUsd > 0, "amuletPriceUsd must be > 0, got \(amuletPriceUsd)")
        precondition(amountCc > 0, "amountCc must be > 0, got \(amountCc)")
        precondition(outputCount >= 1, "outputCount must be >= 1, got \(outputCount)")
        let amountUsd = SteppedRate.damlScaled(amountCc * amuletPriceUsd)
        let transferFeeUsd = schedule.transferFee.charge(amountUsd)
        let createFeesUsd = schedule.createFeeUsd * Decimal(outputCount)
        let feeUsd = createFeesUsd + transferFeeUsd
        return Estimate(
            feeCc: SteppedRate.damlScaled(feeUsd / amuletPriceUsd),
            feeUsd: feeUsd,
            createFeesUsd: createFeesUsd,
            transferFeeUsd: transferFeeUsd
        )
    }
}
