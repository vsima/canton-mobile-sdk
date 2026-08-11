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
    /// One allowlist entry: an HTTP method plus a path prefix.
    public struct Rule: Sendable, Equatable {
        public let method: LedgerApiMethod
        public let pathPrefix: String

        public init(_ method: LedgerApiMethod, _ pathPrefix: String) {
            self.method = method
            self.pathPrefix = pathPrefix.lowercased()
        }
    }

    private let predicate: @Sendable (LedgerApiRequest) -> Bool

    public init(_ predicate: @escaping @Sendable (LedgerApiRequest) -> Bool) {
        self.predicate = predicate
    }

    public func allows(_ request: LedgerApiRequest) -> Bool { predicate(request) }

    /// The rules ``readOnly`` is built from, exposed so a host can compose a
    /// wider policy without restating the read surface.
    public static let readOnlyRules: [Rule] = [
        Rule(.get, "/v2/version"),
        Rule(.get, "/v2/state/"),
        Rule(.post, "/v2/state/"),
        Rule(.get, "/v2/updates/"),
        Rule(.post, "/v2/updates"),
        Rule(.post, "/v2/events/events-by-contract-id"),
        Rule(.get, "/v2/packages"),
        Rule(.get, "/v2/interactive-submission/preferred-package-version"),
        Rule(.post, "/v2/interactive-submission/preferred-packages"),
    ]

    /// The read surface of the JSON Ledger API — an **allowlist of method +
    /// path prefix**, not a rule about HTTP verbs.
    ///
    /// That distinction is the whole point. An earlier version of this policy
    /// allowed `GET` and nothing else, on the usual HTTP convention that
    /// `GET` is the safe verb. Canton's JSON Ledger API does not follow it:
    /// the ACS query is `POST /v2/state/active-contracts`, update reads are
    /// `POST /v2/updates…`, and event lookup is
    /// `POST /v2/events/events-by-contract-id`. Under a GET-only rule a dApp
    /// could read the ledger end and the synchronizer list and essentially
    /// nothing else — including, fatally, not its own holdings, which a
    /// token-standard dApp needs to choose input UTXOs.
    ///
    /// Meanwhile `GET` is not reliably safe either: `POST /v2/packages`
    /// uploads a DAR, so a verb-shaped rule gets the risk backwards in both
    /// directions.
    ///
    /// What stays denied, by simply not being listed: command submission and
    /// interactive submission (`prepare`/`execute` — a dApp reaches those
    /// through `prepareExecute`, where they are approved and hash-verified),
    /// DAR upload, package vetting, and every user/party/identity-provider
    /// surface, which can grant rights and allocate parties.
    public static let readOnly = LedgerApiPolicy.allowing(readOnlyRules)

    /// Refuses everything. The right default for a wallet that has not
    /// thought about it.
    public static let denyAll = LedgerApiPolicy { _ in false }

    /// Allows everything. For tests and for hosts that have made the call.
    public static let allowAll = LedgerApiPolicy { _ in true }

    /// A policy allowing exactly these rules, for hosts widening
    /// ``readOnly`` deliberately.
    ///
    /// ```swift
    /// let policy = LedgerApiPolicy.allowing(
    ///     LedgerApiPolicy.readOnlyRules + [.init(.post, "/v2/commands/async/submit")]
    /// )
    /// ```
    ///
    /// Matching normalises the query string away and rejects any resource
    /// containing `..`, so a prefix cannot be escaped by traversal.
    public static func allowing(_ rules: [Rule]) -> LedgerApiPolicy {
        LedgerApiPolicy { request in
            var path = request.resource.lowercased()
            if let query = path.firstIndex(of: "?") { path = String(path[path.startIndex..<query]) }
            guard !path.contains("..") else { return false }
            let normalised = path.hasPrefix("/") ? path : "/" + path
            return rules.contains { $0.method == request.requestMethod && normalised.hasPrefix($0.pathPrefix) }
        }
    }
}
