// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonLedgerAPI
import Foundation
import Testing
@testable import CantonKit

/// Decodes every golden vector in testdata/values/vectors.txt, checks the
/// typed readers, and re-encodes it with the builders — keeping this SDK
/// byte-compatible with the Kotlin SDK, which runs the same vectors.
@Suite struct GoldenValueVectorsTests {
    private typealias Value = Com_Daml_Ledger_Api_V2_Value

    @Test func decodesAndReencodesEveryGoldenVector() throws {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent() // this file
            .deletingLastPathComponent() // CantonKitTests
            .deletingLastPathComponent() // Tests
            .deletingLastPathComponent() // swift
            .appendingPathComponent("testdata/values/vectors.txt")
        let vectors = try String(contentsOf: url, encoding: .utf8)
            .split(separator: "\n")
            .filter { !$0.isEmpty && !$0.hasPrefix("#") }
            .map { line -> (String, String) in
                let parts = line.split(separator: " ", maxSplits: 1)
                return (String(parts[0]), String(parts[1]))
            }
        #expect(vectors.count == 15, "vector count changed; update both SDK tests")

        for (name, base64) in vectors {
            let data = try #require(Data(base64Encoded: base64), "bad base64 in vector '\(name)'")
            let decoded = try Value(serializedBytes: data)
            let rebuilt = try checkVector(name, decoded)
            #expect(decoded == rebuilt, "builder for '\(name)' does not reproduce the golden value")
        }
    }

    /// Asserts the readers for vector `name` and returns the builder-made equivalent.
    private func checkVector(_ name: String, _ value: Value) throws -> Value {
        switch name {
        case "unit":
            try value.asUnit()
            return .unit
        case "bool_true":
            #expect(try value.asBool() == true)
            return .bool(true)
        case "int64":
            #expect(try value.asInt64() == 42)
            return .int64(42)
        case "date":
            #expect(try value.asDate() == 19700)
            return .date(daysSinceEpoch: 19700)
        case "timestamp":
            #expect(try value.asTimestampMicroseconds() == 1_700_000_000_000_000)
            return .timestamp(microsecondsSinceEpoch: 1_700_000_000_000_000)
        case "numeric":
            #expect(try value.asNumeric() == "3.1415926535")
            return .numeric("3.1415926535")
        case "party":
            #expect(try value.asParty() == "alice::122abc")
            return .party("alice::122abc")
        case "text":
            #expect(try value.asText() == "hello, canton")
            return .text("hello, canton")
        case "contract_id":
            #expect(try value.asContractId() == "00deadbeef")
            return .contractId("00deadbeef")
        case "optional_none":
            #expect(try value.asOptional() == nil)
            return .optional()
        case "optional_some_text":
            #expect(try value.asOptional()?.asText() == "present")
            return .optional(.text("present"))
        case "list_int64":
            #expect(try value.asList().map { try $0.asInt64() } == [1, 2, 3])
            return .list([.int64(1), .int64(2), .int64(3)])
        case "record_amount":
            let record = try value.asRecord()
            #expect(try record.requireField("value").asNumeric() == "100.0")
            #expect(try record.requireField("currency").asText() == "USD")
            return .record(["value": .numeric("100.0"), "currency": .text("USD")])
        case "variant_left_int64":
            let variant = try value.asVariant()
            #expect(variant.constructor == "Left")
            #expect(try variant.value.asInt64() == 1)
            return .variant(constructor: "Left", value: .int64(1))
        case "enum_red":
            #expect(try value.asEnumConstructor() == "Red")
            return .enumeration(constructor: "Red")
        default:
            Issue.record("unhandled golden vector '\(name)' — add coverage in BOTH SDKs")
            throw DamlDecodeError(description: "unhandled vector \(name)")
        }
    }
}
