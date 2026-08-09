// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import Foundation
import Testing

@testable import CantonWalletKit

/// The fee preview held to Splice's own numbers — the Swift twin of the
/// Kotlin `AmuletFeesTest`; both SDKs must agree on every case. The
/// realistic schedule is the historical MainNet-default `transferConfigUsd`
/// published in Splice's scan bulk-data docs (docs example OpenMiningRound:
/// createFee $0.03, holdingFee rate 0.0000190259/round, transferFee
/// initialRate 0.01 with steps at 100/1000/1000000 → 0.001/0.0001/0.00001,
/// lockHolderFee $0.005); step semantics per `chargeSteppedRate` in splice
/// `daml/splice-amulet/daml/Splice/Fees.daml` — per-tranche rates over
/// absolute boundaries, like tax brackets.
struct AmuletFeesTests {

    /// The documented MainNet-launch schedule (see type doc for provenance).
    private static let mainNetLikeSchedule = TransferFeeSchedule(
        createFeeUsd: Decimal(string: "0.03")!,
        transferFee: SteppedRate(
            initialRate: Decimal(string: "0.01")!,
            steps: [
                .init(boundary: 100, rate: Decimal(string: "0.001")!),
                .init(boundary: 1000, rate: Decimal(string: "0.0001")!),
                .init(boundary: 1_000_000, rate: Decimal(string: "0.00001")!),
            ]
        ),
        holdingFeeUsdPerRound: Decimal(string: "0.0000190259")!,
        lockHolderFeeUsd: Decimal(string: "0.005")!
    )

    /// LocalNet's (and post-CIP-0078 networks') zero-fee schedule.
    private static let zeroFeeSchedule = TransferFeeSchedule(
        createFeeUsd: 0,
        transferFee: SteppedRate(initialRate: 0, steps: []),
        holdingFeeUsdPerRound: Decimal(string: "0.0000190259")!,
        lockHolderFeeUsd: 0
    )

    @Test func steppedRateChargesPerTrancheAcrossEveryBoundary() {
        let rate = Self.mainNetLikeSchedule.transferFee
        // Below the first boundary: flat initial rate.
        #expect(rate.charge(50) == Decimal(string: "0.5"))
        // At the boundary the whole amount is still in the first tranche.
        #expect(rate.charge(100) == Decimal(string: "1.0"))
        // 100 at 1% + 50 at 0.1%.
        #expect(rate.charge(150) == Decimal(string: "1.05"))
        // 100 at 1% + 900 at 0.1%.
        #expect(rate.charge(1000) == Decimal(string: "1.9"))
        // + 1000 at 0.01%.
        #expect(rate.charge(2000) == Decimal(string: "2.0"))
        // + 999000 at 0.01% = 101.8 at the top boundary.
        #expect(rate.charge(1_000_000) == Decimal(string: "101.8"))
        // + 1500000 at 0.001% above every step.
        #expect(rate.charge(2_500_000) == Decimal(string: "116.8"))
        // Nothing to charge on zero or negative amounts.
        #expect(rate.charge(0) == 0)
        #expect(rate.charge(-5) == 0)
    }

    @Test func estimatesConvertUsdFeesAtTheRoundPriceWithExactValues() {
        // 30000 CC at 0.005 USD/CC = 150 USD: stepped fee 1.05, create fees
        // 2 × 0.03, total 1.11 USD = 222 CC.
        let estimate = TransferFeeEstimator.estimate(
            schedule: Self.mainNetLikeSchedule,
            amuletPriceUsd: Decimal(string: "0.005")!,
            amountCc: 30000
        )
        #expect(estimate.transferFeeUsd == Decimal(string: "1.05"))
        #expect(estimate.createFeesUsd == Decimal(string: "0.06"))
        #expect(estimate.feeUsd == Decimal(string: "1.11"))
        #expect(estimate.feeCc == 222)

        // Exactly at the first boundary: 20000 CC = 100 USD → 1.00 + 0.06.
        let boundary = TransferFeeEstimator.estimate(
            schedule: Self.mainNetLikeSchedule,
            amuletPriceUsd: Decimal(string: "0.005")!,
            amountCc: 20000
        )
        #expect(boundary.feeUsd == Decimal(string: "1.06"))
        #expect(boundary.feeCc == 212)

        // A single output (no change) pays one create fee.
        let oneOutput = TransferFeeEstimator.estimate(
            schedule: Self.mainNetLikeSchedule,
            amuletPriceUsd: Decimal(string: "0.005")!,
            amountCc: 30000,
            outputCount: 1
        )
        #expect(oneOutput.createFeesUsd == Decimal(string: "0.03"))
        #expect(oneOutput.feeUsd == Decimal(string: "1.08"))

        // Sub-cent price: 1 CC at 0.003 USD → 0.00003 stepped + 0.06 create
        // = 0.06003 USD = 20.01 CC.
        let small = TransferFeeEstimator.estimate(
            schedule: Self.mainNetLikeSchedule,
            amuletPriceUsd: Decimal(string: "0.003")!,
            amountCc: 1
        )
        #expect(small.feeUsd == Decimal(string: "0.06003"))
        #expect(small.feeCc == Decimal(string: "20.01"))
    }

    @Test func zeroFeeSchedulesEstimateZeroAsOnCurrentNetworks() {
        let estimate = TransferFeeEstimator.estimate(
            schedule: Self.zeroFeeSchedule,
            amuletPriceUsd: Decimal(string: "0.005")!,
            amountCc: Decimal(string: "5.0")!
        )
        #expect(estimate.feeCc == 0)
        #expect(estimate.feeUsd == 0)
        #expect(estimate.createFeesUsd == 0)
        #expect(estimate.transferFeeUsd == 0)
    }

    // MARK: - decoding

    private static func fixture(_ name: String) throws -> [String: Any] {
        let fixtureURL = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()  // CantonWalletKitTests
            .deletingLastPathComponent()  // Tests
            .deletingLastPathComponent()  // swift
            .deletingLastPathComponent()  // repo root
            .appendingPathComponent("testdata/scan/\(name)")
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: fixtureURL))
        return json as? [String: Any] ?? [:]
    }

    private static func iso(_ value: String) -> Date {
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return fractional.date(from: value) ?? ISO8601DateFormatter().date(from: value)!
    }

    @Test func decodesTheCapturedLocalNetAmuletRulesResponse() throws {
        let config = try ScanClient.amuletRulesConfig(
            Self.fixture("amulet-rules-v0.json"),
            asOf: Self.iso("2026-08-09T20:00:00Z")
        )
        #expect(config.transferFees.createFeeUsd == 0)
        #expect(config.transferFees.transferFee.initialRate == 0)
        #expect(config.transferFees.transferFee.steps.isEmpty)
        #expect(config.transferFees.holdingFeeUsdPerRound == Decimal(string: "0.0000190259"))
        #expect(config.transferFees.lockHolderFeeUsd == 0)

        #expect(config.synchronizerFees.extraTrafficPriceUsdPerMB == Decimal(string: "16.67"))
        #expect(config.synchronizerFees.minTopupAmountBytes == 200000)
        #expect(config.synchronizerFees.baseRateBurstAmountBytes == 400000)
        #expect(config.synchronizerFees.baseRateBurstWindow == .seconds(1200))
        #expect(config.synchronizerFees.readVsWriteScalingFactor == 4)
        #expect(
            config.activeSynchronizerId
                == "global-domain::12206b93aa60fc251f939fd727b46a65a64991243600e6d8f1ba0099c3a5b331d924"
        )
    }

    @Test func resolvesTheConfigScheduleTheWayTheLedgerDoes() throws {
        let json = try Self.fixture("amulet-rules-with-fees.json")

        // Before the future value: the MainNet-like fee schedule.
        let before = try ScanClient.amuletRulesConfig(json, asOf: Self.iso("2026-01-01T00:00:00Z"))
        #expect(before.transferFees.createFeeUsd == Decimal(string: "0.03"))
        #expect(before.transferFees.transferFee.initialRate == Decimal(string: "0.01"))
        #expect(before.transferFees.transferFee.steps.count == 3)
        #expect(before.transferFees.transferFee.steps[0].boundary == 100)
        #expect(before.transferFees.transferFee.steps[0].rate == Decimal(string: "0.001"))
        #expect(before.transferFees.transferFee.steps[2].boundary == 1_000_000)
        #expect(before.transferFees.transferFee.steps[2].rate == Decimal(string: "0.00001"))
        #expect(before.transferFees.lockHolderFeeUsd == Decimal(string: "0.005"))
        #expect(before.synchronizerFees.minTopupAmountBytes == 200000)

        // At the boundary the future value is already effective
        // (Splice.Schedule.getValueAsOf uses time < effectiveAsOf).
        let atBoundary = try ScanClient.amuletRulesConfig(
            json, asOf: Self.iso("2030-01-01T00:00:00Z")
        )
        #expect(atBoundary.transferFees.createFeeUsd == 0)

        // Past it: the zero-fee config with its own synchronizer fees.
        let after = try ScanClient.amuletRulesConfig(json, asOf: Self.iso("2031-06-01T00:00:00Z"))
        #expect(after.transferFees.createFeeUsd == 0)
        #expect(after.transferFees.transferFee.steps.isEmpty)
        #expect(after.synchronizerFees.extraTrafficPriceUsdPerMB == Decimal(string: "20.0"))
        #expect(after.synchronizerFees.minTopupAmountBytes == 250000)
        #expect(after.synchronizerFees.baseRateBurstWindow == .seconds(1800))
    }

    @Test func decodesTheCapturedOpenMiningRoundsAndPicksTheUsableOne() throws {
        let rounds = try ScanClient.openMiningRounds(Self.fixture("open-mining-rounds-v0.json"))
        #expect(rounds.map { $0.roundNumber } == [129, 130, 131])
        for round in rounds {
            #expect(round.amuletPriceUsd == Decimal(string: "0.005"))
        }
        #expect(rounds[0].opensAt == Self.iso("2026-08-09T19:38:01.304622Z"))
        #expect(rounds[0].targetClosesAt == Self.iso("2026-08-09T19:58:01.304622Z"))

        // Round 131 hasn't opened yet at 19:50 — the usable round is 130.
        let usable = rounds.latestUsable(at: Self.iso("2026-08-09T19:50:00Z"))
        #expect(usable?.roundNumber == 130)
        // Before any round opened there is nothing usable.
        #expect(rounds.latestUsable(at: Self.iso("2026-08-09T00:00:00Z")) == nil)
    }

    @Test func decodesMemberTrafficStatusAndRejectsMalformedPayloads() throws {
        let status = try ScanClient.memberTrafficStatus([
            "actual": ["total_consumed": 7, "total_limit": 1_200_000],
            "target": ["total_purchased": 1_400_000],
        ])
        #expect(status.totalConsumedBytes == 7)
        #expect(status.totalLimitBytes == 1_200_000)
        #expect(status.totalPurchasedBytes == 1_400_000)

        #expect(throws: ScanError.self) {
            _ = try ScanClient.memberTrafficStatus(["actual": ["total_consumed": 7]])
        }
        #expect(throws: ScanError.self) {
            _ = try ScanClient.amuletRulesConfig(["amulet_rules_update": [String: Any]()], asOf: Date())
        }
    }
}
