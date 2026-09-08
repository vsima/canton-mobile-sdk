// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// What a transport knows about an inbound request beyond the frame itself.
///
/// A JSON-RPC frame carries no deadline, but the envelope around it often
/// does: a WalletConnect `session_request` names when the dApp stops waiting
/// for an answer. The wallet needs that to run the same clock the dApp runs,
/// so a request it parks for later is declined when the dApp gives up, not
/// on a guess.
public struct DappRequestContext: Sendable, Equatable {
    /// When the dApp stops waiting for an answer, if the transport carries it.
    public var expiresAt: Date?

    /// Creates a context; pass nil for a transport without a deadline.
    public init(expiresAt: Date? = nil) {
        self.expiresAt = expiresAt
    }

    /// A transport that knows nothing extra.
    public static let none = DappRequestContext()
}

/// The wallet-side counterpart of ``DappTransport``: something that answers
/// JSON-RPC frames and emits events.
///
/// This is what a *server* transport routes inbound frames into. It lives here,
/// in `CantonDappKit`, rather than in the wallet module on purpose: a transport
/// that carries both roles (LAN gRPC, later WalletConnect) depends only on
/// `CantonDappKit`, so it must be able to name the provider without reaching
/// into `CantonDappWalletKit`. The provider engine there conforms to this.
///
/// The mirror of ``DappTransport`` — `send` answers a request; this *is* the
/// thing being sent to.
public protocol DappRequestHandler: Sendable {
    /// Handle one request and return its response. Must not throw for
    /// protocol-level failures — return a JSON-RPC error response instead.
    func handle(_ request: JSONRPCRequest) async -> JSONRPCResponse

    /// ``handle(_:)`` with what the transport knew about the request. A
    /// transport that carries a deadline calls this; the default ignores the
    /// context, so a handler that has no use for it implements only the other.
    func handle(_ request: JSONRPCRequest, context: DappRequestContext) async -> JSONRPCResponse

    /// Events to forward to the connected dApp as JSON-RPC notifications.
    var events: AsyncStream<DappEvent> { get }
}

public extension DappRequestHandler {
    /// Default: drops the context and forwards to ``handle(_:)``.
    func handle(_ request: JSONRPCRequest, context: DappRequestContext) async -> JSONRPCResponse {
        await handle(request)
    }
}
