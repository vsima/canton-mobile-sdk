// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import Foundation
import Testing

@testable import CantonDappKit

/// The shared CIP-0103 codec vectors (testdata/dapp/vectors.json), the same
/// file the Kotlin suite reads. Both platforms agreeing on every one of them
/// is the acceptance criterion for F1.
///
/// The property under test is that **decode-then-encode is a fixpoint** —
/// stronger than "decoding works", and it catches the failures that actually
/// happen: an optional silently dropped on the way out, a null emitted where
/// the field should have been omitted, an enum round-tripping to a different
/// spelling. A decode-only test would pass while the wallet emitted documents
/// no dApp could read.
@Suite struct DappGoldenVectorsTests {

    private static let vectorsURL = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()   // CantonDappKitTests
        .deletingLastPathComponent()   // Tests
        .deletingLastPathComponent()   // swift
        .deletingLastPathComponent()   // repo root
        .appendingPathComponent("testdata/dapp/vectors.json")

    private let document: [String: JSONValue]

    init() throws {
        let data = try Data(contentsOf: Self.vectorsURL)
        document = try #require(try JSONValue.parse(data).objectValue)
    }

    private func entries(_ key: String) throws -> [[String: JSONValue]] {
        let array = try #require(document[key]?.arrayValue, "vectors.json has no '\(key)' array")
        #expect(!array.isEmpty, "'\(key)' is empty — is the fixture the one the Kotlin suite reads?")
        return array.compactMap(\.objectValue)
    }

    @Test func everyVectorRoundTripsToAnEqualDocument() throws {
        for vector in try entries("vectors") {
            let name = try #require(vector["name"]?.stringValue)
            let type = try #require(vector["type"]?.stringValue)
            let json = try #require(vector["json"])

            let reEncoded = try roundTrip(type: type, json: json)

            #expect(reEncoded == json, "vector '\(name)' (\(type)) did not round-trip")
        }
    }

    @Test func everyInvalidVectorIsRejected() throws {
        for vector in try entries("invalid") {
            let name = try #require(vector["name"]?.stringValue)
            let type = try #require(vector["type"]?.stringValue)
            let json = try #require(vector["json"])
            let reason = vector["reason"]?.stringValue ?? ""

            #expect(throws: DappError.self, "'\(name)' should have been rejected: \(reason)") {
                try roundTrip(type: type, json: json)
            }
        }
    }

    /// Covers the full OpenRPC 0.5.0 type surface; an unknown name fails loudly
    /// rather than silently passing, so adding a vector without a decoder here
    /// cannot go unnoticed.
    private func roundTrip(type: String, json: JSONValue) throws -> JSONValue {
        switch type {
        case "Provider": return DappJSON.encode(try DappJSON.decodeProvider(json))
        case "ConnectResult": return DappJSON.encode(try DappJSON.decodeConnectResult(json))
        case "Network": return DappJSON.encode(try DappJSON.decodeNetwork(json))
        case "Session": return DappJSON.encode(try DappJSON.decodeSessionInfo(json))
        case "Wallet": return DappJSON.encode(try DappJSON.decodeWallet(json))
        case "ListAccountsResult": return DappJSON.encodeAccounts(try DappJSON.decodeAccounts(json))
        case "StatusEvent": return DappJSON.encode(try DappJSON.decodeStatus(json))
        case "SignMessageRequest": return DappJSON.encode(try DappJSON.decodeSignMessageRequest(json))
        case "SignMessageResult": return DappJSON.encode(try DappJSON.decodeSignMessageResult(json))
        case "LedgerApiRequest": return DappJSON.encode(try DappJSON.decodeLedgerApiRequest(json))
        case "JsPrepareSubmissionRequest":
            return DappJSON.encode(try DappJSON.decodePrepareSubmission(json))
        case "JsPrepareSubmissionResponse":
            return DappJSON.encode(try DappJSON.decodePrepareSubmissionResult(json))
        case "TxChangedEvent": return DappJSON.encode(try DappJSON.decodeTxChanged(json))
        case "MessageSignatureEvent": return DappJSON.encode(try DappJSON.decodeMessageSignature(json))
        case "prepareExecuteAndWaitResult":
            return DappJSON.encodeExecutedResult(try DappJSON.decodeExecutedResult(json))
        case "JsonRpcRequest": return try JSONRPCRequest.decode(json).encoded()
        case "JsonRpcResponse": return try JSONRPCResponse.decode(json).encoded()
        default:
            Issue.record("vectors.json names a type this test does not cover: '\(type)'")
            return .null
        }
    }

    /// Guards the representation choice underneath the fixpoint property.
    ///
    /// `completionOffset: 4711` must stay an integer. Routing numbers through
    /// `Double` — the obvious way to model JSON numbers in Swift — would emit
    /// `4711.0`, which is a different document that every one of the vectors
    /// above would still accept if they compared decoded values instead of
    /// re-encoded ones.
    @Test func integersDoNotAcquireAFractionalPart() throws {
        let parsed = try JSONValue.parse(#"{"completionOffset":4711,"rate":1.5,"flag":true}"#)
        let object = try #require(parsed.objectValue)

        #expect(object["completionOffset"] == .number("4711"))
        #expect(object["completionOffset"]?.int64Value == 4711)
        #expect(object["rate"]?.int64Value == nil)
        // true must not decode as the number 1.
        #expect(object["flag"] == .bool(true))

        let serialized = try #require(String(data: try parsed.serialized(), encoding: .utf8))
        #expect(serialized.contains("4711"))
        #expect(!serialized.contains("4711.0"))
        #expect(serialized.contains("true"))
    }
}
