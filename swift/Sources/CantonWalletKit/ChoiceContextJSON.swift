// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonKit
import CantonLedgerAPI
import Foundation

/// Bridges the registry's off-ledger JSON (Daml JSON API encoding) and the
/// gRPC Ledger API's proto values.
///
/// Registries return `choiceContextData` as the Daml JSON encoding of
/// `Splice.Api.Token.MetadataV1.ChoiceContext` — a `TextMap` of `AnyValue`
/// variants (`{"tag": "AV_ContractId", "value": "00…"}`). The official TS SDK
/// submits through the JSON API where that encoding is native; we submit over
/// gRPC, so the context must be re-encoded as proto values. `AnyValue`'s
/// closed constructor set is what makes this translation total.
enum ChoiceContextJSON {

    /// `ExtraArgs { context, meta }` ready to embed in a choice-argument record.
    static func extraArgsValue(
        choiceContextData: Any?,
        meta: [String: String] = [:]
    ) throws -> Com_Daml_Ledger_Api_V2_Value {
        .record([
            "context": try choiceContextValue(choiceContextData),
            "meta": metadataValue(meta),
        ])
    }

    /// Daml JSON `ChoiceContext` -> proto record. Nil/null means an empty context.
    static func choiceContextValue(_ json: Any?) throws -> Com_Daml_Ledger_Api_V2_Value {
        var entries: [(String, Com_Daml_Ledger_Api_V2_Value)] = []
        switch json {
        case nil, is NSNull:
            break
        case let object as [String: Any]:
            let values = object["values"] as? [String: Any] ?? [:]
            for key in values.keys.sorted() {
                entries.append((key, try anyValueToValue(values[key]!)))
            }
        default:
            throw WalletDecodeError( "choiceContextData must be an object")
        }
        return .record(["values": .textMap(entries)])
    }

    /// One `AnyValue` variant from Daml JSON to its proto encoding.
    static func anyValueToValue(_ json: Any) throws -> Com_Daml_Ledger_Api_V2_Value {
        guard let object = json as? [String: Any], let tag = object["tag"] as? String else {
            throw WalletDecodeError( "AnyValue must be a tagged object")
        }
        let value = object["value"]
        let payload: Com_Daml_Ledger_Api_V2_Value
        switch tag {
        case "AV_Text":
            payload = .text(try string(value, tag))
        case "AV_Int":
            payload = .int64(try int64(value, tag))
        case "AV_Decimal":
            payload = .numeric(try string(value, tag))
        case "AV_Bool":
            guard let bool = value as? Bool else {
                throw WalletDecodeError( "AV_Bool value must be a boolean")
            }
            payload = .bool(bool)
        case "AV_Date":
            payload = .date(daysSinceEpoch: try days(fromISODate: string(value, tag)))
        case "AV_Time":
            payload = .timestamp(try instant(fromISO: string(value, tag)))
        case "AV_RelTime":
            let micros = (value as? [String: Any])?["microseconds"] ?? value
            payload = .record(["microseconds": .int64(try int64(micros, tag))])
        case "AV_Party":
            payload = .party(try string(value, tag))
        case "AV_ContractId":
            payload = .contractId(try string(value, tag))
        case "AV_List":
            guard let array = value as? [Any] else {
                throw WalletDecodeError( "AV_List value must be an array")
            }
            payload = .list(try array.map { try anyValueToValue($0) })
        case "AV_Map":
            guard let map = value as? [String: Any] else {
                throw WalletDecodeError( "AV_Map value must be an object")
            }
            payload = .textMap(try map.keys.sorted().map { ($0, try anyValueToValue(map[$0]!)) })
        default:
            throw WalletDecodeError( "unknown AnyValue constructor \(tag)")
        }
        return .variant(constructor: tag, value: payload)
    }

    /// `TransferFactory_Transfer` choice arguments in Daml JSON API encoding,
    /// for `GetFactoryRequest.choiceArguments` — `extraArgs` empty per spec.
    static func transferFactoryChoiceArguments(
        expectedAdmin: String,
        transfer: Transfer
    ) -> [String: Any] {
        [
            "expectedAdmin": expectedAdmin,
            "transfer": [
                "sender": transfer.sender,
                "receiver": transfer.receiver,
                "amount": transfer.amount,
                "instrumentId": ["admin": transfer.instrumentId.admin, "id": transfer.instrumentId.id],
                "requestedAt": isoInstant(transfer.requestedAt),
                "executeBefore": isoInstant(transfer.executeBefore),
                "inputHoldingCids": transfer.inputHoldingCids,
                "meta": ["values": transfer.meta],
            ] as [String: Any],
            "extraArgs": [
                "context": ["values": [String: Any]()],
                "meta": ["values": [String: Any]()],
            ] as [String: Any],
        ]
    }

    // MARK: - Primitive parsing

    private static func string(_ value: Any?, _ tag: String) throws -> String {
        guard let string = value as? String else {
            throw WalletDecodeError( "\(tag) value must be a string")
        }
        return string
    }

    private static func int64(_ value: Any?, _ tag: String) throws -> Int64 {
        if let string = value as? String, let parsed = Int64(string) { return parsed }
        if let number = value as? NSNumber, !(value is Bool) { return number.int64Value }
        throw WalletDecodeError( "\(tag) value must be an integer")
    }

    private static func days(fromISODate string: String) throws -> Int32 {
        var components = DateComponents()
        let parts = string.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else {
            throw WalletDecodeError( "AV_Date must be YYYY-MM-DD, was \(string)")
        }
        (components.year, components.month, components.day) = (parts[0], parts[1], parts[2])
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        guard let date = calendar.date(from: components) else {
            throw WalletDecodeError( "invalid AV_Date \(string)")
        }
        return Int32(date.timeIntervalSince1970 / 86_400)
    }

    private static func instant(fromISO string: String) throws -> Date {
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = fractional.date(from: string) { return date }
        let plain = ISO8601DateFormatter()
        if let date = plain.date(from: string) { return date }
        throw WalletDecodeError( "invalid AV_Time \(string)")
    }

    static func isoInstant(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.string(from: date)
    }
}
