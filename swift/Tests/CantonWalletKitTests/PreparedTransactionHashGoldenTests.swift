// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonLedgerAPI
import Foundation
import Testing

@testable import CantonWalletKit

/// Recomputes the hash of real PreparedTransactions captured from a live
/// Splice LocalNet participant (testdata/preparedtx/vectors.txt, written
/// from the Kotlin LocalNet hash integration test's output) — so plain CI
/// exercises the full hashing scheme V2 encoder without a ledger. The
/// vectors cover a create node and an exercise node with an input contract,
/// and are shared byte-for-byte with the Kotlin SDK's golden test.
@Suite struct PreparedTransactionHashGoldenTests {

    private typealias PreparedTransaction = Com_Daml_Ledger_Api_V2_Interactive_PreparedTransaction
    private typealias PrepareResponse = Com_Daml_Ledger_Api_V2_Interactive_PrepareSubmissionResponse

    private func vectors() throws -> [(name: String, prepared: PreparedTransaction, hash: Data)] {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent() // this file
            .deletingLastPathComponent() // CantonWalletKitTests
            .deletingLastPathComponent() // Tests
            .deletingLastPathComponent() // swift
            .appendingPathComponent("testdata/preparedtx/vectors.txt")
        return try String(contentsOf: url, encoding: .utf8)
            .split(separator: "\n")
            .filter { !$0.isEmpty && !$0.hasPrefix("#") }
            .map { line in
                let parts = line.split(separator: " ")
                try #require(parts.count == 3, "malformed golden vector line: '\(line)'")
                let txData = try #require(
                    Data(base64Encoded: String(parts[1])),
                    "bad proto base64 in vector '\(parts[0])'"
                )
                let hash = try #require(
                    Data(base64Encoded: String(parts[2])),
                    "bad hash base64 in vector '\(parts[0])'"
                )
                return (String(parts[0]), try PreparedTransaction(serializedBytes: txData), hash)
            }
    }

    private func response(_ prepared: PreparedTransaction, _ hash: Data) -> PrepareResponse {
        var response = PrepareResponse()
        response.preparedTransaction = prepared
        response.preparedTransactionHash = hash
        response.hashingSchemeVersion = .v2
        return response
    }

    @Test func recomputesEveryGoldenVectorHashByteForByte() throws {
        let vectors = try vectors()
        #expect(vectors.count == 2, "vector count changed; update the SDK tests")
        for (name, prepared, expected) in vectors {
            let computed = try PreparedTransactionHash.compute(prepared)
            #expect(
                computed == expected,
                "recomputed hash differs from the node's for vector '\(name)'"
            )
            // The verify() path the client uses before signing.
            try PreparedTransactionHash.verify(response(prepared, expected))
        }
    }

    @Test func aTamperedTransactionFailsVerification() throws {
        let (_, prepared, expected) = try #require(try vectors().first)
        var tampered = prepared
        tampered.metadata.submitterInfo.commandID = "attacker-swapped-command"
        #expect(throws: PreparedTransactionHashMismatchError.self) {
            try PreparedTransactionHash.verify(response(tampered, expected))
        }
    }

    @Test func anUnsupportedHashingSchemeIsRejectedRatherThanTrusted() throws {
        let (_, prepared, expected) = try #require(try vectors().first)
        var v3 = response(prepared, expected)
        v3.hashingSchemeVersion = .v3
        let failure = #expect(throws: PreparedTransactionHashError.self) {
            try PreparedTransactionHash.verify(v3)
        }
        #expect(failure?.description.contains("unsupported hashing scheme") == true)
    }
}
