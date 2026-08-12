// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import Foundation
import Testing

@testable import CantonDappKit

/// The `signMessage` domain-separation scheme, against the shared golden
/// vector (`testdata/dapp/signmessage.json`) the Kotlin suite reads too.
/// Byte-exact agreement is what lets a wallet on one platform and a dApp on the
/// other interoperate on a signed message.
@Suite struct DappSignMessageTests {

    private static let vectorURL = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()   // CantonDappKitTests
        .deletingLastPathComponent()   // Tests
        .deletingLastPathComponent()   // swift
        .deletingLastPathComponent()   // repo root
        .appendingPathComponent("testdata/dapp/signmessage.json")

    private let vector: [String: JSONValue]

    init() throws {
        let data = try Data(contentsOf: Self.vectorURL)
        vector = try #require(try JSONValue.parse(data).objectValue)
    }

    @Test func signingBytesMatchTheSharedGoldenVector() throws {
        let message = try #require(vector["message"]?.stringValue)
        let expectedHex = try #require(vector["signingBytesHex"]?.stringValue)

        let actualHex = DappSignMessage.signingBytes(message).map { String(format: "%02x", $0) }.joined()

        #expect(actualHex == expectedHex, "signing bytes diverged from the shared vector")
    }

    @Test func theDomainMatchesTheVectorAndExceedsATransactionHashInLength() throws {
        #expect(DappSignMessage.domain == vector["domain"]?.stringValue)
        // The structural guarantee: a 32-byte prepared transaction hash can
        // never equal these signing bytes, because the domain alone is longer.
        #expect(Data(DappSignMessage.domain.utf8).count > 32)
        #expect(DappSignMessage.signingBytes("").count > 32)
    }

    @Test func theDomainPrefixesTheSigningBytes() {
        #expect(DappSignMessage.signingBytes("hello").starts(with: Data(DappSignMessage.domain.utf8)))
    }
}
