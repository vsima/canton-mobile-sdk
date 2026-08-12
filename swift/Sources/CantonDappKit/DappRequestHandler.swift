// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

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

    /// Events to forward to the connected dApp as JSON-RPC notifications.
    var events: AsyncStream<DappEvent> { get }
}
