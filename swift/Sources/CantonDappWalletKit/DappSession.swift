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
    private let spendPolicy: @Sendable () -> DappSpendPolicy?
    private let spendLedger: any SpendLedger
    private let wallClock: @Sendable () -> Date
    private let activityObserver: DappActivityObserver?

    /// Reports to the host's activity feed. The observer cannot throw, so a
    /// broken log can never break a request; see ``DappActivityObserver``.
    private func report(
        _ kind: DappActivity.Kind,
        transfer: DappTransferSummary? = nil,
        detail: String? = nil
    ) {
        activityObserver?(
            DappActivity(
                peerId: peer.id,
                peerName: peer.name,
                at: wallClock(),
                kind: kind,
                transfer: transfer,
                detail: detail
            )
        )
    }

    private var granted: [DappWallet] = []
    private var connected = false
    private var lastSignMessageAt: Date?
    private var lastTransactionAt: Date?

    /// Serializes the whole transaction path (see `withSubmissionLock`): the
    /// actor alone cannot, because every `await` is a reentrancy point.
    private var submissionInFlight = false
    private var submissionWaiters: [CheckedContinuation<Void, Never>] = []

    private let eventStream: AsyncStream<DappEvent>
    private let eventContinuation: AsyncStream<DappEvent>.Continuation

    /// Identifies this SDK to dApps. Hosts override it to name themselves.
    public static let defaultProvider = DappProvider(
        id: "io.github.vsima.canton",
        providerType: .mobile
    )

    /// Creates a session for one peer.
    ///
    /// The three optional collaborators (`messageSigner`, `prepareExecute`,
    /// `ledgerApi`) are independent: leave one nil and its method answers
    /// `4200` (unsupported), which is the honest reply.
    ///
    /// - Parameters:
    ///   - peer: Who is asking, as attested by the transport; see ``DappPeer``.
    ///   - accounts: The accounts the wallet could offer on `connect`.
    ///   - approver: The wallet UI that answers every approval.
    ///   - network: The network and JSON Ledger API the session operates on.
    ///   - provider: How the wallet identifies itself in `status`; defaults
    ///     to ``defaultProvider``.
    ///   - messageSigner: Implements `signMessage`.
    ///   - prepareExecute: Implements `prepareExecute` and
    ///     `prepareExecuteAndWait`.
    ///   - ledgerApi: Implements the `ledgerApi` proxy.
    ///   - ledgerApiPolicy: Which `ledgerApi` resources the peer may reach;
    ///     ``LedgerApiPolicy/readOnly`` by default.
    ///   - signMessageMinInterval: Minimum seconds between `signMessage`
    ///     calls from this peer; a faster call is refused with `-32602`, so
    ///     a peer cannot spray approval prompts.
    ///   - spendPolicy: The current ``DappSpendPolicy`` for this peer, read
    ///     fresh on every transaction so the wallet's policy editor takes
    ///     effect immediately. Nil (the default) means no policy: every
    ///     transaction asks the human.
    ///   - spendLedger: Where spends are recorded and the rolling daily cap
    ///     is read from.
    ///   - wallClock: Wall-clock for receipts and the rolling window;
    ///     injectable for tests.
    ///   - activityObserver: The wallet-facing activity feed; see
    ///     ``DappActivityObserver``.
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
        signMessageMinInterval: TimeInterval = 1,
        spendPolicy: @escaping @Sendable () -> DappSpendPolicy? = { nil },
        spendLedger: any SpendLedger = InMemorySpendLedger(),
        wallClock: @escaping @Sendable () -> Date = { Date() },
        activityObserver: DappActivityObserver? = nil
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
        self.spendPolicy = spendPolicy
        self.spendLedger = spendLedger
        self.wallClock = wallClock
        self.activityObserver = activityObserver

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
        await handle(request, context: .none)
    }

    /// ``handle(_:)`` with what the transport knew about the request — the
    /// dApp's deadline — which is passed on to the approver so the sheet can
    /// run the dApp's clock.
    public func handle(_ request: JSONRPCRequest, context: DappRequestContext) async -> JSONRPCResponse {
        do {
            return .success(id: request.id, result: try await dispatch(request, context: context))
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

    private func dispatch(_ request: JSONRPCRequest, context: DappRequestContext) async throws -> JSONValue {
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
            return DappJSON.encode(try await connect(context: context))
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
            return DappJSON.encode(try await signMessage(params.message, context: context))
        case .prepareExecute:
            _ = try await runPrepareExecute(
                try DappJSON.decodePrepareSubmission(try request.requireParams()),
                context: context
            )
            return .null
        case .prepareExecuteAndWait:
            let executed = try await runPrepareExecute(
                try DappJSON.decodePrepareSubmission(try request.requireParams()),
                context: context
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

    private func connect(context: DappRequestContext) async throws -> ConnectResult {
        // Idempotent: agents call connect before each request to ensure they
        // have accounts, so a peer that is already connected and granted must
        // not re-raise the account-share sheet. Return the existing grant.
        if connected, !granted.isEmpty { return connectResult() }
        let available = try await accounts.accounts()
        let decision = await approver.approve(
            .connection(peer: peer, network: network.dappNetwork, available: available),
            context: context
        )
        guard case .approved(let approved) = decision else {
            guard case .rejected(let reason) = decision else {
                throw DappError(code: .internalError, message: "unreachable approval case")
            }
            report(.connectionDeclined, detail: reason)
            return ConnectResult(isConnected: false, isNetworkConnected: false, reason: reason)
        }
        // Approving zero accounts is a rejection wearing a different hat.
        // Treating it as success would leave a dApp "connected" to nothing.
        if approved.isEmpty {
            report(.connectionDeclined, detail: "No accounts were shared")
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
        report(.connected, detail: approved.map(\.partyId).joined(separator: ", "))
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

    private func signMessage(_ message: String, context: DappRequestContext) async throws -> SignMessageResult {
        guard let signer = messageSigner else {
            throw DappError(code: .unsupportedMethod, message: "this wallet does not implement signMessage")
        }
        let account = try primaryAccount()
        try rateLimitSignMessage()

        let messageId = UUID().uuidString
        eventContinuation.yield(.messageSignature(.pending(messageId: messageId)))

        let decision = await approver.approve(
            .message(peer: peer, signWith: account, message: message),
            context: context
        )
        if case .rejected(let reason) = decision {
            report(.messageDeclined, detail: reason)
            eventContinuation.yield(.messageSignature(.failed(messageId: messageId)))
            throw DappError(code: .userRejected, message: reason)
        }

        do {
            let signature = try await signer.sign(account: account, message: message)
            report(.messageSigned)
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

    /// One transaction at a time per session: the policy check, the
    /// approval, the execution, and the spend record happen inside one
    /// critical section, so concurrent frames cannot both pass a cap check
    /// that only one of them fits under (the R3 time-of-check/time-of-use
    /// race). The actor's isolation is not enough: every `await` in the path
    /// (the sheet, the pipeline) is a reentrancy point.
    private func withSubmissionLock<T: Sendable>(_ body: () async throws -> T) async rethrows -> T {
        while submissionInFlight {
            await withCheckedContinuation { submissionWaiters.append($0) }
        }
        submissionInFlight = true
        defer {
            submissionInFlight = false
            if !submissionWaiters.isEmpty { submissionWaiters.removeFirst().resume() }
        }
        return try await body()
    }

    private func runPrepareExecute(
        _ submission: PrepareSubmission,
        context: DappRequestContext
    ) async throws -> TxChangedEvent {
        guard let pipeline = prepareExecutePipeline else {
            throw DappError(code: .unsupportedMethod, message: "this wallet does not implement prepareExecute")
        }
        let account = try actAsAccount(submission)
        try authorizeReadAs(submission)
        let commandId = submission.commandId ?? UUID().uuidString
        return try await withSubmissionLock {
            try await executeGated(submission, account: account, commandId: commandId, context: context, with: pipeline)
        }
    }

    private func executeGated(
        _ submission: PrepareSubmission,
        account: DappWallet,
        commandId: String,
        context: DappRequestContext,
        with pipeline: PrepareExecutePipeline
    ) async throws -> TxChangedEvent {
        let policy = spendPolicy()
        if let policy { try rateLimitTransaction(policy) }
        let summary = DappCommandSummary.transferOf(submission)
        let spendDecision: SpendDecision
        if let policy {
            spendDecision = policy.decide(summary: summary, spentLast24h: try await spentLast24h(summary))
        } else {
            spendDecision = .askHuman
        }

        eventContinuation.yield(.txChanged(.pending(commandId: commandId)))
        if case .refuse(let reason) = spendDecision {
            report(.transactionRefused, transfer: summary, detail: reason)
            eventContinuation.yield(.txChanged(.failed(commandId: commandId)))
            throw DappError(code: .userRejected, message: "spend policy: \(reason)")
        }
        if spendDecision == .autoApprove {
            report(.transactionAutoApproved, transfer: summary)
        } else {
            report(.transactionRequested, transfer: summary)
            let decision = await approver.approve(
                .transaction(peer: peer, actAs: account, network: network.dappNetwork, submission: submission),
                context: context
            )
            if case .rejected(let reason) = decision {
                report(.transactionDeclined, transfer: summary, detail: reason)
                eventContinuation.yield(.txChanged(.failed(commandId: commandId)))
                throw DappError(code: .userRejected, message: reason)
            }
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
            try await recordSpend(summary, autoApproved: spendDecision == .autoApprove, commandId: commandId)
            if case .executed(_, let updateId, _) = executed {
                report(.transactionExecuted, transfer: summary, detail: updateId)
            } else {
                report(.transactionExecuted, transfer: summary)
            }
            return executed
        } catch {
            report(.transactionFailed, transfer: summary, detail: "\(error)")
            eventContinuation.yield(.txChanged(.failed(commandId: commandId)))
            if let dappError = error as? DappError { throw dappError }
            throw DappError(code: .internalError, message: "\(error)")
        }
    }

    /// The trailing-24h receipt total for the submission's instrument, the
    /// input to ``DappSpendPolicy/decide(summary:spentLast24h:)``'s daily
    /// cap. Zero when the submission is not a parsed transfer: an unparsed
    /// submission is never refused by amount, so the total is irrelevant.
    private func spentLast24h(_ summary: DappTransferSummary?) async throws -> Decimal {
        guard let summary else { return 0 }
        let since = wallClock().addingTimeInterval(-24 * 60 * 60)
        return try await spendLedger.receiptsSince(peerId: peer.id, since: since)
            .filter { $0.instrumentId == summary.instrumentId }
            .reduce(Decimal(0)) { $0 + $1.amount }
    }

    /// Executed spends become receipts, the source of truth for the rolling
    /// cap and the app's receipts UI. Written only after execution succeeds,
    /// and only for parsed transfers: an unparsed submission has no amount
    /// to record, and the human explicitly approved whatever it was.
    private func recordSpend(_ summary: DappTransferSummary?, autoApproved: Bool, commandId: String) async throws {
        guard let summary, let amount = DappSpendPolicy.strictDecimal(summary.amount) else { return }
        try await spendLedger.append(
            SpendReceipt(
                peerId: peer.id,
                at: wallClock(),
                instrumentId: summary.instrumentId,
                amount: amount,
                receiver: summary.receiver,
                autoApproved: autoApproved,
                commandId: commandId
            )
        )
    }

    /// The transaction counterpart of `rateLimitSignMessage`, driven by the
    /// policy's ``DappSpendPolicy/minRequestInterval``: an unthrottled peer
    /// can spray sheets until reflex confirms one.
    private func rateLimitTransaction(_ policy: DappSpendPolicy) throws {
        guard policy.minRequestInterval > 0 else { return }
        if let last = lastTransactionAt,
           wallClock().timeIntervalSince(last) < policy.minRequestInterval {
            report(
                .transactionRateLimited,
                detail: "more than one request per \(policy.minRequestInterval)s"
            )
            throw DappError(
                code: .invalidInput,
                message: "spend policy: transaction requests are rate-limited to "
                    + "one per \(policy.minRequestInterval)s"
            )
        }
        lastTransactionAt = wallClock()
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
