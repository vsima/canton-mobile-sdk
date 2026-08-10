// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonDappKit
import Foundation

// The three collaborators ``DappSession`` needs to do anything that touches a
// key or the ledger.
//
// Separate protocols rather than one, because they fail independently and are
// wanted independently: a wallet may proxy `ledgerApi` reads without
// implementing `signMessage`. Each one absent means that method answers
// `4200`, which is the honest reply — the wallet genuinely does not support it.

/// Signs an arbitrary message on behalf of an approved account.
public protocol DappMessageSigner: Sendable {
    /// Returns the signature, encoded however the wallet and its verifiers
    /// agree — this SDK imposes no format because CIP-0103 does not.
    ///
    /// Called only after the user has approved. Throw to fail the request;
    /// ``DappSession`` converts that into a `messageSignature failed` event
    /// and an error response.
    func sign(account: DappWallet, message: String) async throws -> String
}

/// Everything the prepare→verify→sign→execute pipeline needs for one
/// submission.
///
/// `actAs` is the account ``DappSession`` resolved and the user approved — not
/// whatever the dApp put in `PrepareSubmission.actAs`. Implementations must
/// build the ledger envelope from this field, which is the point at which a
/// dApp is prevented from acting as a party it merely named.
public struct PrepareExecuteContext: Sendable {
    public var commandId: String
    public var actAs: DappWallet
    public var submission: PrepareSubmission
    public var network: DappNetworkConfig
    /// Publishes intermediate lifecycle events — in practice the `signed`
    /// step. `pending` and the terminal states are emitted by ``DappSession``
    /// itself, so an implementation that ignores this still produces a correct
    /// event stream, just a coarser one.
    public var emitEvent: @Sendable (TxChangedEvent) async -> Void

    public init(
        commandId: String,
        actAs: DappWallet,
        submission: PrepareSubmission,
        network: DappNetworkConfig,
        emitEvent: @escaping @Sendable (TxChangedEvent) async -> Void = { _ in }
    ) {
        self.commandId = commandId
        self.actAs = actAs
        self.submission = submission
        self.network = network
        self.emitEvent = emitEvent
    }
}

/// Prepares, verifies, signs and executes one submission.
///
/// The contract that matters: an implementation **must** recompute the
/// transaction hash from the prepared bytes the participant returned and
/// refuse to sign on a mismatch. A wallet signs only what it verified, and
/// this protocol is the only place that check can live.
public protocol PrepareExecutePipeline: Sendable {
    /// Returns the executed event — `.executed` specifically; any other case
    /// is a programming error the session will reject.
    func execute(_ context: PrepareExecuteContext) async throws -> TxChangedEvent
}

/// Performs an authenticated call against the JSON Ledger API.
public protocol LedgerApiProxy: Sendable {
    func call(_ request: LedgerApiRequest) async throws -> JSONValue
}

/// Which `ledgerApi` resources a dApp may reach through the wallet.
///
/// `ledgerApi` is the wallet acting as an authenticating proxy, so without a
/// policy it is an open door onto the ledger with the wallet's own
/// credentials. The default is deliberately narrow; hosts widen it
/// deliberately.
public struct LedgerApiPolicy: Sendable {
    private let predicate: @Sendable (LedgerApiRequest) -> Bool

    public init(_ predicate: @escaping @Sendable (LedgerApiRequest) -> Bool) {
        self.predicate = predicate
    }

    public func allows(_ request: LedgerApiRequest) -> Bool { predicate(request) }

    /// Read-style resources only.
    ///
    /// `GET` alone, and never the administrative surfaces: user management and
    /// party management can grant rights and allocate parties, and neither is
    /// something a dApp should reach *through* a wallet even when the wallet
    /// itself may.
    public static let readOnly = LedgerApiPolicy { request in
        request.requestMethod == .get && !isAdministrative(request.resource)
    }

    /// Refuses everything. The right default for a wallet that has not thought
    /// about it.
    public static let denyAll = LedgerApiPolicy { _ in false }

    /// Allows everything. For tests and for hosts that have made the call.
    public static let allowAll = LedgerApiPolicy { _ in true }

    private static let administrative = ["/users", "/parties", "/idps", "/identity-provider"]

    static func isAdministrative(_ resource: String) -> Bool {
        var path = resource
        if let query = path.firstIndex(of: "?") { path = String(path[path.startIndex..<query]) }
        while path.hasSuffix("/") { path.removeLast() }
        let lowered = path.lowercased()
        return administrative.contains { lowered.hasSuffix($0) || lowered.contains("\($0)/") }
    }
}
