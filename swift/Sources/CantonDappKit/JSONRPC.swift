// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// The only value of the `jsonrpc` member this protocol accepts.
public let jsonRpcVersion = "2.0"

/// A JSON-RPC 2.0 request or notification.
///
/// A nil `id` means a **notification**: no response is expected, which is how
/// the wallet pushes `accountsChanged`, `txChanged` and `messageSignature`.
///
/// `params` is a bare object, not a positional array. That is what the
/// reference TypeScript client sends — `provider.request({ method, params })`
/// passes the params object straight through — and OpenRPC 0.5.0 leaves
/// `paramStructure` unset on every method, so the by-name form is the one
/// with an implementation behind it.
public struct JSONRPCRequest: Sendable, Equatable {
    public var method: String
    public var params: JSONValue?
    public var id: JSONValue?

    public init(method: String, params: JSONValue? = nil, id: JSONValue? = nil) {
        self.method = method
        self.params = params
        self.id = id
    }

    public var isNotification: Bool { id == nil }

    public func encoded() -> JSONValue {
        var object: [String: JSONValue] = ["jsonrpc": .string(jsonRpcVersion), "method": .string(method)]
        if let params { object["params"] = params }
        // A null id is a legal JSON-RPC id, distinct from an absent one, so
        // this cannot collapse into a nil check on the encoded value.
        if let id { object["id"] = id }
        return .object(object)
    }

    public static func decode(_ json: JSONValue) throws -> JSONRPCRequest {
        let object = try json.requireObject("JSON-RPC request")
        try object.requireVersion()
        guard let method = object["method"]?.stringValue else {
            throw DappError(code: .invalidParams, message: "JSON-RPC request has no string 'method'")
        }
        return JSONRPCRequest(method: method, params: object["params"], id: object["id"])
    }
}

/// A JSON-RPC 2.0 response. Exactly one of `result` and `error` is set.
///
/// `result` may legitimately be JSON null — `disconnect` and `prepareExecute`
/// both return the OpenRPC `Null` schema — so "result is null" cannot stand in
/// for "this is an error".
public struct JSONRPCResponse: Sendable, Equatable {
    public var id: JSONValue?
    public var result: JSONValue?
    public var error: JSONRPCErrorBody?

    public init(id: JSONValue?, result: JSONValue? = nil, error: JSONRPCErrorBody? = nil) {
        self.id = id
        self.result = result
        self.error = error
    }

    public var isOK: Bool { error == nil }

    public static func success(id: JSONValue?, result: JSONValue?) -> JSONRPCResponse {
        JSONRPCResponse(id: id, result: result ?? .null)
    }

    public static func failure(id: JSONValue?, error: JSONRPCErrorBody) -> JSONRPCResponse {
        JSONRPCResponse(id: id, error: error)
    }

    public static func failure(id: JSONValue?, error: DappError) -> JSONRPCResponse {
        .failure(id: id, error: JSONRPCErrorBody(from: error))
    }

    public func encoded() -> JSONValue {
        var object: [String: JSONValue] = ["jsonrpc": .string(jsonRpcVersion), "id": id ?? .null]
        if let error {
            object["error"] = error.encoded()
        } else {
            object["result"] = result ?? .null
        }
        return .object(object)
    }

    /// The `result`, or throws the `error` as a ``DappError``.
    public func resultOrThrow() throws -> JSONValue {
        if let error { throw error.asDappError() }
        return result ?? .null
    }

    public static func decode(_ json: JSONValue) throws -> JSONRPCResponse {
        let object = try json.requireObject("JSON-RPC response")
        try object.requireVersion()
        let error = try object["error"].flatMap { try JSONRPCErrorBody.decode($0) }
        return JSONRPCResponse(id: object["id"], result: object["result"], error: error)
    }
}

/// The `error` member of a JSON-RPC response.
public struct JSONRPCErrorBody: Sendable, Equatable {
    public var code: Int
    public var message: String
    public var data: JSONValue?

    public init(code: Int, message: String, data: JSONValue? = nil) {
        self.code = code
        self.message = message
        self.data = data
    }

    public init(from error: DappError) {
        self.init(code: error.rawCode, message: error.message, data: error.data)
    }

    public func encoded() -> JSONValue {
        var object: [String: JSONValue] = ["code": .int(Int64(code)), "message": .string(message)]
        if let data { object["data"] = data }
        return .object(object)
    }

    /// Maps onto ``DappError``. An unrecognised code becomes
    /// ``DappErrorCode/internalError`` rather than throwing: a wallet is free
    /// to use codes this SDK predates, and losing the message would be worse
    /// than losing the exact code.
    public func asDappError() -> DappError {
        if let known = DappErrorCode(rawValue: code) {
            return DappError(code: known, message: message, data: data)
        }
        return DappError(code: .internalError, message: "\(message) (code \(code))", data: data)
    }

    public static func decode(_ json: JSONValue) throws -> JSONRPCErrorBody {
        let object = try json.requireObject("JSON-RPC error")
        guard let code = object["code"]?.int64Value else {
            throw DappError(code: .internalError, message: "JSON-RPC error has no numeric 'code'")
        }
        return JSONRPCErrorBody(
            code: Int(code),
            message: object["message"]?.stringValue ?? "",
            data: object["data"]
        )
    }
}

// ── Shared helpers ─────────────────────────────────────────────────────

extension JSONValue {
    func requireObject(_ what: String) throws -> [String: JSONValue] {
        guard let object = objectValue else {
            throw DappError(code: .invalidParams, message: "\(what) must be a JSON object")
        }
        return object
    }
}

extension [String: JSONValue] {
    func requireVersion() throws {
        let version = self["jsonrpc"]?.stringValue
        guard version == jsonRpcVersion else {
            throw DappError(
                code: .invalidParams,
                message: "expected jsonrpc '\(jsonRpcVersion)', was '\(version ?? "absent")'"
            )
        }
    }
}
