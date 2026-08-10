// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// The error codes CIP-0103 inherits from EIP-1474 / EIP-1193.
///
/// Two families share the space and mean different things. The 4xxx codes are
/// *provider* errors — the wallet understood the request and declined it. The
/// negative codes are JSON-RPC's own, for requests that were malformed or
/// failed downstream. Getting this wrong is user-visible: a dApp that treats
/// ``userRejected`` as a transport failure will retry a request the user just
/// declined.
public enum DappErrorCode: Int, Sendable, CaseIterable {
    /// The user declined. Terminal: do not retry, and change no state.
    case userRejected = 4001

    /// The caller has no grant for this account or method.
    case unauthorized = 4100

    /// The method is not supported by this provider.
    ///
    /// Not JSON-RPC's `-32601 Method not found`: EIP-1193 defines 4200 for a
    /// provider that does not implement an otherwise valid method, and dApp
    /// SDKs in this ecosystem branch on 4200.
    case unsupportedMethod = 4200

    /// The provider is disconnected from the dApp.
    case disconnected = 4900

    /// The provider is connected, but not to a network.
    case chainDisconnected = 4901

    /// Malformed parameters — ours or the caller's.
    case invalidParams = -32602

    /// An unexpected failure inside the provider.
    case internalError = -32603

    /// Well-formed parameters the ledger nonetheless rejected.
    case invalidInput = -32000

    /// The transaction was rejected by the ledger.
    case transactionRejected = -32003
}

/// A CIP-0103 error, thrown by ``DappClient`` when the wallet returns one and
/// by the wallet-side engine to produce one.
///
/// `data` carries the optional JSON-RPC `error.data` unchanged, so a caller
/// can surface a participant `traceId` without this SDK having to model every
/// shape a wallet might attach.
public struct DappError: Error, Sendable, Equatable {
    public var code: DappErrorCode
    public var message: String
    public var data: JSONValue?

    public init(code: DappErrorCode, message: String, data: JSONValue? = nil) {
        self.code = code
        self.message = message
        self.data = data
    }

    /// The numeric wire code, for callers that branch on it directly.
    public var rawCode: Int { code.rawValue }

    /// True when the user declined — the one error a dApp should never retry.
    public var isUserRejection: Bool { code == .userRejected }
}

extension DappError: CustomStringConvertible {
    public var description: String { "DappError(\(code)/\(rawCode)): \(message)" }
}

extension DappError: LocalizedError {
    public var errorDescription: String? { message }
}
