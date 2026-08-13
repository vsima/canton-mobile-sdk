// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonDappKit

/// The wallet side of the WalletConnect transport for CIP-0103.
///
/// WalletConnect is not a new capability — it is a *transport* for the same
/// CIP-0103 JSON-RPC the wallet already answers over the in-process and LAN
/// transports. So this is the `LanGrpcDappServer` of WalletConnect: it wraps a
/// ``DappRequestHandler`` (a `DappSession` satisfies it) and turns a session's
/// traffic into `handle` calls.
///
/// **It depends on no WalletConnect client library.** A wallet's job here has
/// exactly two touch-points, and both are pure:
///
/// - ``sessionNamespaces(accounts:)`` — at proposal time, the CAIP-2 chain,
///   CAIP-10 accounts, and CIP-0103 methods to approve a session with.
/// - ``handle(_:)`` — at request time, route one `session_request` into the
///   engine and map its reply back.
///
/// A Reown WalletKit delegate in the app does the relay I/O: on
/// `sessionProposalPublisher` it approves with ``sessionNamespaces(accounts:)``;
/// on `sessionRequestPublisher` it calls ``handle(_:)`` and responds with the
/// result. Keeping the client out means this adapter is plain Swift and is
/// unit-tested against a real `DappSession`, no relay — the same split the
/// reference dApp server used to prove the transport headless before any device
/// work.
///
/// The approval, signing and prepare→sign→execute all happen inside the engine;
/// this type adds no policy. It advertises the CIP-0103 method names verbatim
/// (`signMessage`, `prepareExecute`, …) so the frames the engine dispatches are
/// exactly what crosses the session.
public final class CantonWalletConnect: Sendable {
    private let handler: any DappRequestHandler

    /// The CAIP-2 chain this session advertises (validated from `networkId`).
    public let chainId: String

    /// The CIP-0103 request methods advertised over a session.
    public var methods: [String] { Self.requestMethods }

    /// Events advertised over a session — none carried yet (see the type doc).
    public var events: [String] { [] }

    public init(handler: any DappRequestHandler, networkId: String) throws {
        self.handler = handler
        self.chainId = try Caip.chainId(networkId)
    }

    /// The namespaces to approve a session with, sharing `accounts`. Which
    /// accounts to share is the wallet's decision (its connect-approval UI);
    /// this only projects them into CAIP-10 form under the `canton` namespace.
    public func sessionNamespaces(accounts: [DappWallet]) -> WcSessionNamespaces {
        WcSessionNamespaces(
            chains: [chainId],
            accounts: accounts.map { Caip.account(chainId: chainId, partyId: $0.partyId) },
            methods: Self.requestMethods,
            events: []
        )
    }

    /// Routes one inbound `session_request` into the engine and maps the reply.
    ///
    /// The engine never throws for protocol failures — it returns a JSON-RPC
    /// error — so this maps error responses to ``WcResponse/error(code:message:)``
    /// (carrying the CIP-0103 / EIP-1193 code) and everything else to
    /// ``WcResponse/success(result:)``.
    public func handle(_ request: WcRequest) async -> WcResponse {
        let frame = JSONRPCRequest(
            method: request.method,
            params: request.params,
            id: .int(request.requestId)
        )
        let response = await handler.handle(frame)
        if let error = response.error {
            return .error(code: error.code, message: error.message)
        }
        return .success(result: response.result ?? .null)
    }

    /// The CIP-0103 **callable** methods, by wire name — the event methods
    /// (`accountsChanged`, `txChanged`, `messageSignature`) are excluded because
    /// they only travel wallet→dApp as notifications.
    public static let requestMethods: [String] = [
        DappMethod.status,
        DappMethod.connect,
        DappMethod.disconnect,
        DappMethod.isConnected,
        DappMethod.getActiveNetwork,
        DappMethod.listAccounts,
        DappMethod.getPrimaryAccount,
        DappMethod.signMessage,
        DappMethod.prepareExecute,
        DappMethod.prepareExecuteAndWait,
        DappMethod.ledgerApi,
    ].map(\.rawValue)
}
