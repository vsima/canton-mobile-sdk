// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// A JSON document as a value type.
///
/// The rest of this SDK reads JSON as `[String: Any]` straight out of
/// `JSONSerialization` (see ``ScanClient``), which is fine inside a function
/// but not here: `Any` is not `Sendable`, and the dApp API's types cross
/// concurrency boundaries constantly — through an `async` transport, into an
/// actor-isolated session, out again on an `AsyncStream`. This enum is the
/// Kotlin side's `JsonElement` in Swift terms, and it makes the codec
/// exhaustive rather than a chain of conditional casts.
public enum JSONValue: Sendable, Equatable {
    case null
    case bool(Bool)
    /// Numbers are kept as their source text so an integer stays an integer.
    /// Round-tripping through `Double` would turn `completionOffset: 4711`
    /// into `4711.0`, which is a different document.
    case number(String)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    public static func int(_ value: Int64) -> JSONValue { .number(String(value)) }

    // ── Accessors ──────────────────────────────────────────────────────

    public var objectValue: [String: JSONValue]? {
        if case .object(let value) = self { return value }
        return nil
    }

    public var arrayValue: [JSONValue]? {
        if case .array(let value) = self { return value }
        return nil
    }

    public var stringValue: String? {
        if case .string(let value) = self { return value }
        return nil
    }

    public var boolValue: Bool? {
        if case .bool(let value) = self { return value }
        return nil
    }

    public var int64Value: Int64? {
        if case .number(let text) = self { return Int64(text) }
        return nil
    }

    public var isNull: Bool { self == .null }

    // ── Foundation bridging ────────────────────────────────────────────

    /// Parses JSON text. Throws ``DappError`` so callers have one error type.
    public static func parse(_ data: Data) throws -> JSONValue {
        let object = try JSONSerialization.jsonObject(
            with: data,
            options: [.fragmentsAllowed]
        )
        return from(object)
    }

    public static func parse(_ text: String) throws -> JSONValue {
        guard let data = text.data(using: .utf8) else {
            throw DappError(code: .invalidParams, message: "input is not valid UTF-8")
        }
        return try parse(data)
    }

    /// Serialises to JSON text. Keys are sorted so output is reproducible.
    public func serialized() throws -> Data {
        try JSONSerialization.data(
            withJSONObject: foundationObject,
            options: [.sortedKeys, .fragmentsAllowed]
        )
    }

    static func from(_ object: Any) -> JSONValue {
        switch object {
        case is NSNull:
            return .null
        case let number as NSNumber:
            // CFBoolean and CFNumber are both NSNumber; the type encoding is
            // the only way to tell `true` from `1`, and conflating them
            // would encode booleans as numbers.
            if CFGetTypeID(number) == CFBooleanGetTypeID() {
                return .bool(number.boolValue)
            }
            return .number(numberText(number))
        case let string as String:
            return .string(string)
        case let array as [Any]:
            return .array(array.map { from($0) })
        case let dictionary as [String: Any]:
            return .object(dictionary.mapValues { from($0) })
        default:
            return .null
        }
    }

    private static func numberText(_ number: NSNumber) -> String {
        // Integers must not acquire a ".0"; anything else keeps NSNumber's
        // own rendering.
        switch CFNumberGetType(number) {
        case .float32Type, .float64Type, .cgFloatType:
            return number.stringValue
        default:
            return number.stringValue
        }
    }

    var foundationObject: Any {
        switch self {
        case .null:
            return NSNull()
        case .bool(let value):
            return value
        case .number(let text):
            if let integer = Int64(text) { return NSNumber(value: integer) }
            if let double = Double(text) { return NSNumber(value: double) }
            return text
        case .string(let value):
            return value
        case .array(let values):
            return values.map { $0.foundationObject }
        case .object(let values):
            return values.mapValues { $0.foundationObject }
        }
    }
}

extension JSONValue: ExpressibleByStringLiteral {
    public init(stringLiteral value: String) { self = .string(value) }
}

extension JSONValue: ExpressibleByBooleanLiteral {
    public init(booleanLiteral value: Bool) { self = .bool(value) }
}

extension JSONValue: ExpressibleByIntegerLiteral {
    public init(integerLiteral value: Int) { self = .number(String(value)) }
}
