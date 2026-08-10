// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// Moves JSON-RPC frames between a dApp and a wallet.
///
/// The currency is ``JSONRPCRequest``/``JSONRPCResponse`` rather than a typed
/// request union, because that is literally what crosses the wire and every
/// planned transport is a way of carrying those bytes somewhere: in-process,
/// deep link, LAN gRPC. Keeping the seam at the frame means a new transport
/// implements two members and inherits the whole protocol — and it is why the
/// JSON-RPC document stays the single schema.
public protocol DappTransport: Sendable {
    /// Sends a request and awaits its response.
    ///
    /// Throw ``DappError`` for protocol-level failures. A transport-level
    /// failure (socket closed, app not installed) may surface as any error;
    /// ``DappClient`` does not translate those, because a caller needs to tell
    /// "the wallet said no" apart from "the wallet was never reached".
    func send(_ request: JSONRPCRequest) async throws -> JSONRPCResponse

    /// Events pushed by the wallet.
    ///
    /// Defaults to an empty stream: a deep-link transport genuinely cannot
    /// deliver these, and forcing every implementation to write one would only
    /// obscure which ones can.
    var events: AsyncStream<DappEvent> { get }
}

extension DappTransport {
    public var events: AsyncStream<DappEvent> {
        AsyncStream { $0.finish() }
    }
}

/// The dApp side of CIP-0103: typed calls onto a ``DappTransport``.
///
/// ```swift
/// let client = DappClient(transport: transport)
/// if try await client.connect().isConnected {
///     let account = try await client.getPrimaryAccount()
///     let tx = try await client.prepareExecuteAndWait(request)
/// }
/// ```
///
/// Every method throws ``DappError`` when the wallet returns a JSON-RPC error,
/// so `4001` (the user declined) arrives as an error with `isUserRejection`
/// set rather than as a nil that is easy to mistake for "nothing happened".
///
/// Holds no session state. Whether a connection exists is the wallet's answer
/// to give, and caching it here would only let the two disagree.
public actor DappClient {
    private let transport: DappTransport
    private var nextId: Int64 = 1

    public init(transport: DappTransport) {
        self.transport = transport
    }

    /// Events pushed by the wallet, for transports that support them.
    public nonisolated var events: AsyncStream<DappEvent> { transport.events }

    /// Requests a connection. The wallet decides, typically by asking the user.
    public func connect() async throws -> ConnectResult {
        try DappJSON.decodeConnectResult(try await call(.connect))
    }

    /// Ends the session. Idempotent by convention.
    public func disconnect() async throws {
        _ = try await call(.disconnect)
    }

    /// Whether a connection exists, without prompting the user.
    public func isConnected() async throws -> ConnectResult {
        try DappJSON.decodeConnectResult(try await call(.isConnected))
    }

    /// Provider identity, connection state, and — if connected — network.
    public func status() async throws -> DappStatus {
        try DappJSON.decodeStatus(try await call(.status))
    }

    /// The network the wallet is on, as a CAIP-2 id plus optional endpoints.
    public func getActiveNetwork() async throws -> DappNetwork {
        try DappJSON.decodeNetwork(try await call(.getActiveNetwork))
    }

    /// Accounts the user granted this dApp — not every account the wallet holds.
    public func listAccounts() async throws -> [DappWallet] {
        try DappJSON.decodeAccounts(try await call(.listAccounts))
    }

    /// The account the wallet treats as primary, among those granted.
    public func getPrimaryAccount() async throws -> DappWallet {
        try DappJSON.decodeWallet(try await call(.getPrimaryAccount))
    }

    /// Asks the wallet to sign an arbitrary message. Subject to user approval.
    public func signMessage(_ message: String) async throws -> SignMessageResult {
        try DappJSON.decodeSignMessageResult(
            try await call(.signMessage, params: DappJSON.encode(SignMessageRequest(message: message)))
        )
    }

    /// Submits and returns as soon as the wallet accepts.
    ///
    /// Returns nothing — per OpenRPC this method's result is `Null`. The
    /// outcome arrives on ``events`` as `txChanged`, so this is only useful on
    /// a transport that has them; otherwise use ``prepareExecuteAndWait(_:)``.
    public func prepareExecute(_ request: PrepareSubmission) async throws {
        _ = try await call(.prepareExecute, params: DappJSON.encode(request))
    }

    /// Submits and waits until the transaction is executed.
    ///
    /// Throws if the user declines (`4001`) or the ledger rejects it
    /// (`-32003`); returns only on success, which is why the result is the
    /// executed event rather than a union.
    public func prepareExecuteAndWait(_ request: PrepareSubmission) async throws -> TxChangedEvent {
        try DappJSON.decodeExecutedResult(
            try await call(.prepareExecuteAndWait, params: DappJSON.encode(request))
        )
    }

    /// Calls the JSON Ledger API through the wallet, which supplies the
    /// credentials. Expect `4100` for resources outside the wallet's policy.
    public func ledgerApi(_ request: LedgerApiRequest) async throws -> JSONValue {
        try await call(.ledgerApi, params: DappJSON.encode(request))
    }

    /// Convenience for callers that want the raw frame — tests, diagnostics.
    public func request(method: String, params: JSONValue? = nil) async throws -> JSONRPCResponse {
        try await transport.send(JSONRPCRequest(method: method, params: params, id: takeId()))
    }

    private func call(_ method: DappMethod, params: JSONValue? = nil) async throws -> JSONValue {
        let response = try await transport.send(
            JSONRPCRequest(method: method.rawValue, params: params, id: takeId())
        )
        // The response id is deliberately not checked: some transports (a
        // deep-link callback, a multiplexed stream) legitimately reorder or
        // synthesise ids, and refusing the response would break them for no
        // safety gain — the transport is what guarantees pairing.
        return try response.resultOrThrow()
    }

    private func takeId() -> JSONValue {
        defer { nextId += 1 }
        return .int(nextId)
    }
}
