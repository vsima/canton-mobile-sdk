// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonDappKit
import Foundation

/// One dApp's session with the wallet: the CIP-0103 **provider** side.
///
/// A session is per-peer and holds that peer's grant — the accounts the user
/// approved for *it*. Two dApps talking to the same wallet get two sessions
/// and cannot see each other's accounts, which is why grants live here rather
/// than in a global store.
///
/// ```swift
/// let session = DappSession(peer: peer, accounts: accounts, approver: ui, network: config)
/// let response = await session.handle(incomingFrame)
/// ```
///
/// ``handle(_:)`` never throws for protocol-level failures — it returns a
/// JSON-RPC error response, because a transport needs something to send back.
public actor DappSession: DappRequestHandler {
    private let peer: DappPeer
    private let accounts: DappAccountsSource
    private let approver: DappApprovalDelegate
    private let network: DappNetworkConfig
    private let provider: DappProvider
    private let messageSigner: DappMessageSigner?
    private let prepareExecutePipeline: PrepareExecutePipeline?
    private let ledgerApiProxy: LedgerApiProxy?
    private let ledgerApiPolicy: LedgerApiPolicy
    private let signMessageMinInterval: TimeInterval

    private var granted: [DappWallet] = []
    private var connected = false
    private var lastSignMessageAt: Date?

    private let eventStream: AsyncStream<DappEvent>
    private let eventContinuation: AsyncStream<DappEvent>.Continuation

    /// Identifies this SDK to dApps. Hosts override it to name themselves.
    public static let defaultProvider = DappProvider(
        id: "io.github.vsima.canton",
        providerType: .mobile
    )

    public init(
        peer: DappPeer,
        accounts: DappAccountsSource,
        approver: DappApprovalDelegate,
        network: DappNetworkConfig,
        provider: DappProvider = DappSession.defaultProvider,
        messageSigner: DappMessageSigner? = nil,
        prepareExecute: PrepareExecutePipeline? = nil,
        ledgerApi: LedgerApiProxy? = nil,
        ledgerApiPolicy: LedgerApiPolicy = .readOnly,
        signMessageMinInterval: TimeInterval = 1
    ) {
        self.peer = peer
        self.accounts = accounts
        self.approver = approver
        self.network = network
        self.provider = provider
        self.messageSigner = messageSigner
        self.prepareExecutePipeline = prepareExecute
        self.ledgerApiProxy = ledgerApi
        self.ledgerApiPolicy = ledgerApiPolicy
        self.signMessageMinInterval = signMessageMinInterval

        var continuation: AsyncStream<DappEvent>.Continuation!
        // Unbounded: dropping an event would silently desynchronise a dApp's
        // view of a transaction it is waiting on.
        self.eventStream = AsyncStream(bufferingPolicy: .unbounded) { continuation = $0 }
        self.eventContinuation = continuation
    }

    /// Events for this peer only.
    public nonisolated var events: AsyncStream<DappEvent> { eventStream }

    /// The accounts this peer may currently see. Empty until `connect` is
    /// approved.
    public var grantedAccounts: [DappWallet] { granted }

    /// Dispatches one JSON-RPC frame.
    public func handle(_ request: JSONRPCRequest) async -> JSONRPCResponse {
        do {
            return .success(id: request.id, result: try await dispatch(request))
        } catch let error as DappError {
            return .failure(id: request.id, error: error)
        } catch {
            // A provider that leaks internals to a dApp is a problem; one that
            // returns nothing leaves the dApp hanging. Neither.
            return .failure(
                id: request.id,
                error: DappError(code: .internalError, message: "\(error)")
            )
        }
    }

    private func dispatch(_ request: JSONRPCRequest) async throws -> JSONValue {
        guard let method = DappMethod(rawValue: request.method) else {
            throw DappError(code: .unsupportedMethod, message: "unknown method '\(request.method)'")
        }
        // Event names are valid wire methods, but only wallet→dApp. A dApp
        // sending one is confused, not unauthorized.
        if method.isEvent {
            throw DappError(
                code: .unsupportedMethod,
                message: "'\(request.method)' is an event, not a callable method"
            )
        }

        switch method {
        case .connect:
            return DappJSON.encode(try await connect())
        case .disconnect:
            await disconnect()
            return .null
        case .isConnected:
            return DappJSON.encode(connectResult())
        case .status:
            return DappJSON.encode(status())
        case .getActiveNetwork:
            _ = try requireGrant()
            return DappJSON.encode(network.dappNetwork)
        case .listAccounts:
            return DappJSON.encodeAccounts(try requireGrant())
        case .getPrimaryAccount:
            return DappJSON.encode(try primaryAccount())
        case .signMessage:
            let params = try DappJSON.decodeSignMessageRequest(try request.requireParams())
            return DappJSON.encode(try await signMessage(params.message))
        case .prepareExecute:
            _ = try await runPrepareExecute(
                try DappJSON.decodePrepareSubmission(try request.requireParams())
            )
            return .null
        case .prepareExecuteAndWait:
            let executed = try await runPrepareExecute(
                try DappJSON.decodePrepareSubmission(try request.requireParams())
            )
            return DappJSON.encodeExecutedResult(executed)
        case .ledgerApi:
            return try await runLedgerApi(
                try DappJSON.decodeLedgerApiRequest(try request.requireParams())
            )
        case .accountsChanged, .txChanged, .messageSignature:
            throw DappError(code: .unsupportedMethod, message: "unreachable: handled above")
        }
    }

    // ── Connection ─────────────────────────────────────────────────────

    private func connect() async throws -> ConnectResult {
        let available = try await accounts.accounts()
        let decision = await approver.approve(
            .connection(peer: peer, network: network.dappNetwork, available: available)
        )
        guard case .approved(let approved) = decision else {
            guard case .rejected(let reason) = decision else {
                throw DappError(code: .internalError, message: "unreachable approval case")
            }
            return ConnectResult(isConnected: false, isNetworkConnected: false, reason: reason)
        }
        // Approving zero accounts is a rejection wearing a different hat.
        // Treating it as success would leave a dApp "connected" to nothing.
        if approved.isEmpty {
            return ConnectResult(
                isConnected: false,
                isNetworkConnected: false,
                reason: "No accounts were shared"
            )
        }
        // A delegate must not widen the grant beyond what the wallet offered —
        // it is UI, and UI does not get to invent accounts.
        let offered = Set(available.map(\.partyId))
        let unknown = approved.filter { !offered.contains($0.partyId) }
        if !unknown.isEmpty {
            throw DappError(
                code: .internalError,
                message: "approval returned accounts the wallet did not offer: "
                    + unknown.map(\.partyId).joined(separator: ", ")
            )
        }
        granted = approved
        connected = true
        eventContinuation.yield(.accountsChanged(approved))
        return connectResult()
    }

    private func disconnect() async {
        let wasConnected = connected
        granted = []
        connected = false
        // Idempotent by design: a dApp retrying disconnect after a dropped
        // transport should not get an error for succeeding twice.
        if wasConnected { eventContinuation.yield(.accountsChanged([])) }
    }

    private func connectResult() -> ConnectResult {
        ConnectResult(
            isConnected: connected,
            isNetworkConnected: connected,
            reason: connected ? nil : "Not connected"
        )
    }

    private func status() -> DappStatus {
        let result = connectResult()
        return DappStatus(
            provider: provider,
            connection: result,
            network: result.isConnected ? network.dappNetwork : nil,
            // Session carries an access token, and dApps do not get one.
            session: nil
        )
    }

    /// The peer's grant, or `4100`.
    ///
    /// `4100` rather than `4900`: EIP-1193 reserves 4900 for the provider being
    /// disconnected from every chain, while "you have not been authorized for
    /// these accounts" is exactly what 4100 means.
    private func requireGrant() throws -> [DappWallet] {
        guard connected, !granted.isEmpty else {
            throw DappError(
                code: .unauthorized,
                message: "'\(peer.name)' has no approved accounts; call connect first"
            )
        }
        return granted
    }

    private func primaryAccount() throws -> DappWallet {
        let grant = try requireGrant()
        return grant.first(where: \.primary) ?? grant[0]
    }

    // ── signMessage ────────────────────────────────────────────────────

    private func signMessage(_ message: String) async throws -> SignMessageResult {
        guard let signer = messageSigner else {
            throw DappError(code: .unsupportedMethod, message: "this wallet does not implement signMessage")
        }
        let account = try primaryAccount()
        try rateLimitSignMessage()

        let messageId = UUID().uuidString
        eventContinuation.yield(.messageSignature(.pending(messageId: messageId)))

        let decision = await approver.approve(.message(peer: peer, signWith: account, message: message))
        if case .rejected(let reason) = decision {
            eventContinuation.yield(.messageSignature(.failed(messageId: messageId)))
            throw DappError(code: .userRejected, message: reason)
        }

        do {
            let signature = try await signer.sign(account: account, message: message)
            eventContinuation.yield(.messageSignature(.signed(messageId: messageId, signature: signature)))
            return SignMessageResult(signature: signature)
        } catch {
            eventContinuation.yield(.messageSignature(.failed(messageId: messageId)))
            if let dappError = error as? DappError { throw dappError }
            throw DappError(code: .internalError, message: "\(error)")
        }
    }

    /// Throttles `signMessage`.
    ///
    /// Not about compute: an unthrottled signMessage lets a peer spray approval
    /// prompts until one is confirmed by reflex. Per session, so per peer.
    private func rateLimitSignMessage() throws {
        if let last = lastSignMessageAt, Date().timeIntervalSince(last) < signMessageMinInterval {
            throw DappError(
                code: .invalidInput,
                message: "signMessage is rate-limited to one call per \(signMessageMinInterval)s"
            )
        }
        lastSignMessageAt = Date()
    }

    // ── prepareExecute ─────────────────────────────────────────────────

    private func runPrepareExecute(_ submission: PrepareSubmission) async throws -> TxChangedEvent {
        guard let pipeline = prepareExecutePipeline else {
            throw DappError(code: .unsupportedMethod, message: "this wallet does not implement prepareExecute")
        }
        let account = try actAsAccount(submission)
        try authorizeReadAs(submission)
        let commandId = submission.commandId ?? UUID().uuidString

        eventContinuation.yield(.txChanged(.pending(commandId: commandId)))
        let decision = await approver.approve(
            .transaction(peer: peer, actAs: account, network: network.dappNetwork, submission: submission)
        )
        if case .rejected(let reason) = decision {
            eventContinuation.yield(.txChanged(.failed(commandId: commandId)))
            throw DappError(code: .userRejected, message: reason)
        }

        let continuation = eventContinuation
        do {
            let executed = try await pipeline.execute(
                PrepareExecuteContext(
                    commandId: commandId,
                    actAs: account,
                    submission: submission,
                    network: network,
                    emitEvent: { continuation.yield(.txChanged($0)) }
                )
            )
            guard case .executed = executed else {
                throw DappError(
                    code: .internalError,
                    message: "pipeline returned a '\(executed.statusWireDescription)' event, expected 'executed'"
                )
            }
            eventContinuation.yield(.txChanged(executed))
            return executed
        } catch {
            eventContinuation.yield(.txChanged(.failed(commandId: commandId)))
            if let dappError = error as? DappError { throw dappError }
            throw DappError(code: .internalError, message: "\(error)")
        }
    }

    /// Resolves which account acts, enforcing the rule that makes the whole
    /// proxy design safe: **a dApp may request an `actAs`, it may not choose
    /// one.** A dApp that could set `actAs` freely could make the wallet act as
    /// any party it names, so an unrecognised request is `4100` rather than a
    /// silent substitution.
    private func actAsAccount(_ submission: PrepareSubmission) throws -> DappWallet {
        let grant = try requireGrant()
        let requested = submission.actAs
        if requested.isEmpty { return grant.first(where: \.primary) ?? grant[0] }
        if requested.count > 1 {
            throw DappError(
                code: .invalidParams,
                message: "multi-party actAs is not supported; requested \(requested.count) parties"
            )
        }
        let partyId = requested[0]
        guard let account = grant.first(where: { $0.partyId == partyId }) else {
            throw DappError(
                code: .unauthorized,
                message: "actAs '\(partyId)' is not among the accounts approved for '\(peer.name)'"
            )
        }
        return account
    }

    /// The `readAs` counterpart to ``actAsAccount(_:)``. A dApp may *request*
    /// extra read parties, but only ones the user already approved for this
    /// peer: the read authority a `readAs` draws on is the wallet's own ledger
    /// token, so an unrecognised party is `4100` rather than a silent widening
    /// of what the dApp can make the wallet read. Requested parties already in
    /// the grant pass through unchanged.
    private func authorizeReadAs(_ submission: PrepareSubmission) throws {
        if submission.readAs.isEmpty { return }
        let granted = Set(try requireGrant().map(\.partyId))
        let foreign = submission.readAs.filter { !granted.contains($0) }
        if !foreign.isEmpty {
            throw DappError(
                code: .unauthorized,
                message: "readAs \(foreign.map { "'\($0)'" }.joined(separator: ", ")) "
                    + "is not among the accounts approved for '\(peer.name)'"
            )
        }
    }

    // ── ledgerApi ──────────────────────────────────────────────────────

    private func runLedgerApi(_ request: LedgerApiRequest) async throws -> JSONValue {
        guard let proxy = ledgerApiProxy else {
            throw DappError(code: .unsupportedMethod, message: "this wallet does not implement ledgerApi")
        }
        _ = try requireGrant()
        guard ledgerApiPolicy.allows(request) else {
            throw DappError(
                code: .unauthorized,
                message: "ledgerApi \(request.requestMethod.rawValue) \(request.resource) "
                    + "is outside this wallet's policy"
            )
        }
        return try await proxy.call(request)
    }
}

extension JSONRPCRequest {
    /// This request's params, or `-32602`.
    func requireParams() throws -> JSONValue {
        guard let params else {
            throw DappError(code: .invalidParams, message: "method '\(method)' requires params")
        }
        return params
    }
}

extension TxChangedEvent {
    /// The wire spelling, for error messages inside this module.
    var statusWireDescription: String {
        switch self {
        case .pending: return "pending"
        case .signed: return "signed"
        case .executed: return "executed"
        case .failed: return "failed"
        }
    }
}
