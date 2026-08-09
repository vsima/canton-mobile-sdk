// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import Foundation
import Testing

@testable import CantonWalletKit

/// Decodes the shared fixture (testdata/scan/holdings-summary-v1.json) — a
/// captured `/v1/holdings/summary` response from a live Splice LocalNet scan.
/// The Kotlin twin consumes the same fixture; both SDKs must agree.
struct ScanHoldingsSummaryTests {

    private static func fixture() throws -> [String: Any] {
        let fixtureURL = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()  // CantonWalletKitTests
            .deletingLastPathComponent()  // Tests
            .deletingLastPathComponent()  // swift
            .deletingLastPathComponent()  // repo root
            .appendingPathComponent("testdata/scan/holdings-summary-v1.json")
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: fixtureURL))
        return json as? [String: Any] ?? [:]
    }

    @Test func decodesTheCapturedLiveResponse() throws {
        let result = try ScanClient.holdingsSummaryResult(Self.fixture())

        #expect(result.recordTime == ISO8601DateFormatter().date(from: "2026-08-09T18:00:00Z"))
        #expect(result.migrationId == 0)
        // Three parties were queried; the never-funded one is absent.
        #expect(result.summaries.count == 2)

        let operatorSummary = try #require(result.summaries.first)
        #expect(
            operatorSummary.partyId
                == "app_user_localnet-localparty-1::12206e297fb60b09f7a0ae0cc6f81b672c69ca04a72fc34042f5a6364967ab87d7d0"
        )
        #expect(operatorSummary.totalUnlockedCoin == "6111277.1600000000")
        #expect(operatorSummary.totalLockedCoin == "226.0000000000")
        #expect(operatorSummary.totalCoinHoldings == "6111503.1600000000")

        let receiver = try #require(result.summaries.last)
        #expect(
            receiver.partyId
                == "historyreceiver::1220e9bf928f82e3f2208ddbea88165bd13e9b6da4892510f3bda11066da4b672425"
        )
        #expect(receiver.totalUnlockedCoin == "5.0000000000")
        #expect(receiver.totalLockedCoin == "0.0000000000")
        #expect(receiver.totalCoinHoldings == "5.0000000000")
    }

    @Test func emptySummariesArrayDecodesToAnEmptyResult() throws {
        let result = try ScanClient.holdingsSummaryResult([
            "record_time": "2026-08-09T18:00:00Z",
            "migration_id": 4,
            "summaries": [Any](),
        ])
        #expect(result.migrationId == 4)
        #expect(result.summaries.isEmpty)
    }

    @Test func malformedPayloadsRaiseScanErrorNotSilentZeros() {
        #expect(throws: ScanError.self) {
            _ = try ScanClient.holdingsSummaryResult([
                "migration_id": 0,
                "summaries": [Any](),
            ])
        }
        #expect(throws: ScanError.self) {
            _ = try ScanClient.holdingsSummaryResult([
                "record_time": "2026-08-09T18:00:00Z",
                "migration_id": 0,
                "summaries": [
                    [
                        "party_id": "p::1220aa",
                        "total_unlocked_coin": "not-a-number",
                        "total_locked_coin": "0",
                        "total_coin_holdings": "0",
                    ]
                ],
            ])
        }
    }
}
