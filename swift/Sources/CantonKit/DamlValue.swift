// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonLedgerAPI
import Foundation
import SwiftProtobuf

/// Thrown when a `Value` does not have the shape a reader expects.
public struct DamlDecodeError: Error, Sendable, CustomStringConvertible {
    public let description: String
}

/// Concise constructors and typed readers for Daml `Value`s, mirroring the
/// Kotlin SDK's `DamlValues`. Both implementations are held to the same
/// golden vectors in `testdata/values/`.
extension Com_Daml_Ledger_Api_V2_Value {
    public static var unit: Self {
        var value = Self()
        value.sum = .unit(Google_Protobuf_Empty())
        return value
    }

    public static func bool(_ bool: Bool) -> Self {
        var value = Self()
        value.bool = bool
        return value
    }

    public static func int64(_ int64: Int64) -> Self {
        var value = Self()
        value.int64 = int64
        return value
    }

    /// Days since 1970-01-01.
    public static func date(daysSinceEpoch: Int32) -> Self {
        var value = Self()
        value.date = daysSinceEpoch
        return value
    }

    /// Microseconds since epoch, UTC.
    public static func timestamp(microsecondsSinceEpoch: Int64) -> Self {
        var value = Self()
        value.timestamp = microsecondsSinceEpoch
        return value
    }

    public static func timestamp(_ date: Date) -> Self {
        .timestamp(microsecondsSinceEpoch: Int64((date.timeIntervalSince1970 * 1_000_000).rounded()))
    }

    public static func numeric(_ numeric: String) -> Self {
        var value = Self()
        value.numeric = numeric
        return value
    }

    public static func party(_ party: String) -> Self {
        var value = Self()
        value.party = party
        return value
    }

    public static func text(_ text: String) -> Self {
        var value = Self()
        value.text = text
        return value
    }

    public static func contractId(_ contractId: String) -> Self {
        var value = Self()
        value.contractID = contractId
        return value
    }

    /// `Some(wrapped)`, or `None` when `wrapped` is nil.
    public static func optional(_ wrapped: Self? = nil) -> Self {
        var value = Self()
        value.optional = Com_Daml_Ledger_Api_V2_Optional()
        if let wrapped {
            value.optional.value = wrapped
        }
        return value
    }

    public static func list(_ elements: [Self]) -> Self {
        var value = Self()
        value.list = Com_Daml_Ledger_Api_V2_List()
        value.list.elements = elements
        return value
    }

    public static func record(_ fields: KeyValuePairs<String, Self>) -> Self {
        var value = Self()
        value.record = .of(fields)
        return value
    }

    public static func variant(constructor: String, value wrapped: Self) -> Self {
        var value = Self()
        value.variant = Com_Daml_Ledger_Api_V2_Variant()
        value.variant.constructor = constructor
        value.variant.value = wrapped
        return value
    }

    public static func enumeration(constructor: String) -> Self {
        var value = Self()
        value.enum = Com_Daml_Ledger_Api_V2_Enum()
        value.enum.constructor = constructor
        return value
    }

    // MARK: Typed readers

    public func asUnit() throws {
        guard case .unit = sum else { throw mismatch("unit") }
    }

    public func asBool() throws -> Bool {
        guard case .bool(let bool)? = sum else { throw mismatch("bool") }
        return bool
    }

    public func asInt64() throws -> Int64 {
        guard case .int64(let int64)? = sum else { throw mismatch("int64") }
        return int64
    }

    /// Days since 1970-01-01.
    public func asDate() throws -> Int32 {
        guard case .date(let date)? = sum else { throw mismatch("date") }
        return date
    }

    /// Microseconds since epoch, UTC.
    public func asTimestampMicroseconds() throws -> Int64 {
        guard case .timestamp(let timestamp)? = sum else { throw mismatch("timestamp") }
        return timestamp
    }

    public func asTimestamp() throws -> Date {
        Date(timeIntervalSince1970: Double(try asTimestampMicroseconds()) / 1_000_000)
    }

    public func asNumeric() throws -> String {
        guard case .numeric(let numeric)? = sum else { throw mismatch("numeric") }
        return numeric
    }

    public func asParty() throws -> String {
        guard case .party(let party)? = sum else { throw mismatch("party") }
        return party
    }

    public func asText() throws -> String {
        guard case .text(let text)? = sum else { throw mismatch("text") }
        return text
    }

    public func asContractId() throws -> String {
        guard case .contractID(let contractId)? = sum else { throw mismatch("contractId") }
        return contractId
    }

    /// The wrapped value for `Some`, or nil for `None`.
    public func asOptional() throws -> Self? {
        guard case .optional(let optional)? = sum else { throw mismatch("optional") }
        return optional.hasValue ? optional.value : nil
    }

    public func asList() throws -> [Self] {
        guard case .list(let list)? = sum else { throw mismatch("list") }
        return list.elements
    }

    public func asRecord() throws -> Com_Daml_Ledger_Api_V2_Record {
        guard case .record(let record)? = sum else { throw mismatch("record") }
        return record
    }

    public func asVariant() throws -> Com_Daml_Ledger_Api_V2_Variant {
        guard case .variant(let variant)? = sum else { throw mismatch("variant") }
        return variant
    }

    public func asEnumConstructor() throws -> String {
        guard case .enum(let enumeration)? = sum else { throw mismatch("enum") }
        return enumeration.constructor
    }

    private func mismatch(_ expected: String) -> DamlDecodeError {
        DamlDecodeError(description: "expected \(expected), was \(sum.map(String.init(describing:)) ?? "unset")")
    }
}

extension Com_Daml_Ledger_Api_V2_Record {
    public static func of(_ fields: KeyValuePairs<String, Com_Daml_Ledger_Api_V2_Value>) -> Self {
        var record = Self()
        record.fields = fields.map { label, value in
            var field = Com_Daml_Ledger_Api_V2_RecordField()
            field.label = label
            field.value = value
            return field
        }
        return record
    }

    /// The value of the field labelled `label`, or nil if absent.
    public func field(_ label: String) -> Com_Daml_Ledger_Api_V2_Value? {
        fields.first { $0.label == label }?.value
    }

    /// The value of the field labelled `label`.
    public func requireField(_ label: String) throws -> Com_Daml_Ledger_Api_V2_Value {
        guard let value = field(label) else {
            throw DamlDecodeError(description: "missing record field '\(label)'")
        }
        return value
    }
}
