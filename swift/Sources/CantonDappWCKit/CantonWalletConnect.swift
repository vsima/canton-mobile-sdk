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
/// unit-tested against a real `DappSession`, with no relay.
///
/// The approval, signing and prepare→sign→execute all happen inside the engine;
/// this type adds no policy. Over a WalletConnect session the Canton ecosystem
/// (the official `@canton-network/dapp-sdk`, PartyLayer) names CIP-0103 methods
/// with a `canton_` prefix — the convention EVM uses with `eth_` — so this
/// advertises the `canton_` wire names and normalizes each inbound method back to
/// the bare CIP-0103 name the engine answers. Bare names are still accepted, so a
/// peer that speaks CIP-0103 verbatim (the reference dApp server) keeps working.
/// See ``WcMethod``.
public final class CantonWalletConnect: Sendable {
    private let handler: any DappRequestHandler

    /// The CAIP-2 chain this session advertises (validated from `networkId`).
    public let chainId: String

    /// The `canton_` request methods advertised over a session.
    public var methods: [String] { WcMethod.advertised }

    /// The events a `canton_` dApp subscribes to. Advertised for interop;
    /// proactive emission is a follow-up.
    public var events: [String] { WcMethod.events }

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
            methods: WcMethod.advertised,
            events: WcMethod.events
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
            method: WcMethod.normalize(request.method),
            params: request.params,
            id: .int(request.requestId)
        )
        let response = await handler.handle(frame)
        if let error = response.error {
            return .error(code: error.code, message: error.message)
        }
        return .success(result: response.result ?? .null)
    }

}

/// The WalletConnect method-name convention for Canton CIP-0103.
///
/// The Canton dApp ecosystem — the official `@canton-network/dapp-sdk` and
/// PartyLayer — carries CIP-0103 over a WalletConnect session under `canton_`-
/// prefixed method names, and names the prepare-sign-execute call
/// `canton_prepareSignExecute` (for both `prepareExecute` and
/// `prepareExecuteAndWait`). This advertises that wire set and maps an inbound
/// method back to the bare CIP-0103 name the engine dispatches.
enum WcMethod {
    static let prefix = "canton_"
    static let prepareSignExecute = "canton_prepareSignExecute"

    /// The `canton_` methods advertised at proposal time — the ecosystem's set.
    /// `connect` / `disconnect` / `isConnected` are handled dApp-side and never
    /// sent as requests, so they are not advertised; they are still accepted
    /// inbound (bare or prefixed) via ``normalize(_:)``.
    static let advertised: [String] = [
        prefix + DappMethod.status.rawValue,
        prefix + DappMethod.getActiveNetwork.rawValue,
        prefix + DappMethod.listAccounts.rawValue,
        prefix + DappMethod.getPrimaryAccount.rawValue,
        prefix + DappMethod.signMessage.rawValue,
        prepareSignExecute,
        prefix + DappMethod.ledgerApi.rawValue,
    ]

    /// The events a `canton_` dApp subscribes to.
    static let events: [String] = ["accountsChanged", "statusChanged"]

    /// Maps an inbound wire method to the bare CIP-0103 method the engine answers:
    /// `canton_prepareSignExecute` → `prepareExecuteAndWait`; any other `canton_`
    /// method drops the prefix; a bare method passes through unchanged.
    static func normalize(_ method: String) -> String {
        if method == prepareSignExecute { return DappMethod.prepareExecuteAndWait.rawValue }
        if method.hasPrefix(prefix) { return String(method.dropFirst(prefix.count)) }
        return method
    }
}
