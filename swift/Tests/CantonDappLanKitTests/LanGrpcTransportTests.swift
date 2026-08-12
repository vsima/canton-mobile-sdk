// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonDappKit
import Foundation
import Testing

@testable import CantonDappLanKit
@testable import CantonDappWalletKit

/// The transport slice's proof (risk register R6), Swift side: one CIP-0103
/// session run across a **real gRPC socket** between two independent
/// implementations — the dApp-side ``LanGrpcDappTransport`` and the wallet-side
/// ``LanGrpcDappServer`` fronting the real `DappSession` engine.
///
/// This is the first thing to exercise the Swift engine over anything but the
/// in-process transport, so it is the first to serialize the frames to bytes,
/// cross a socket, and correlate responses against a concurrent event channel.
@Suite struct LanGrpcTransportTests {

    private let alice = wallet("alice::1220aa", primary: true)
    private let bob = wallet("bob::1220bb", primary: false)

    private static func wallet(_ partyId: String, primary: Bool) -> DappWallet {
        DappWallet(
            primary: primary,
            partyId: partyId,
            status: .allocated,
            hint: String(partyId.split(separator: ":").first ?? ""),
            publicKey: "00",
            namespace: String(partyId.split(separator: ":").last ?? ""),
            networkId: "canton:localnet",
            signingProviderId: "software"
        )
    }

    private struct Accounts: DappAccountsSource {
        let available: [DappWallet]
        func accounts() async throws -> [DappWallet] { available }
    }

    private struct Approver: DappApprovalDelegate {
        let answer: @Sendable (DappApprovalRequest) -> DappApproval
        func approve(_ request: DappApprovalRequest) async -> DappApproval { answer(request) }
    }

    private struct Pipeline: PrepareExecutePipeline {
        func execute(_ context: PrepareExecuteContext) async throws -> TxChangedEvent {
            .executed(commandId: context.commandId, updateId: "update-1", completionOffset: 42)
        }
    }

    /// Stands up the real engine on a real loopback port and returns a client
    /// dialing it, plus the server and transport for teardown.
    private func connectOverSocket(
        approve: @escaping @Sendable (DappApprovalRequest) -> DappApproval = { request in
            if case .connection(_, _, let available) = request { return .approved(accounts: available) }
            return .approved(accounts: [])
        }
    ) async throws -> (DappClient, LanGrpcDappServer, LanGrpcDappTransport) {
        let session = DappSession(
            peer: DappPeer(id: "merchant", name: "Merchant POS", verified: true),
            accounts: Accounts(available: [alice, bob]),
            approver: Approver(answer: approve),
            network: DappNetworkConfig(networkId: "canton:localnet"),
            prepareExecute: Pipeline()
        )
        let server = LanGrpcDappServer(handler: session)
        let port = try await server.start()
        let transport = LanGrpcDappTransport(host: "127.0.0.1", port: port)
        return (DappClient(transport: transport), server, transport)
    }

    private var commands: [JSONValue] {
        [.object(["CreateCommand": .object(["templateId": .string("pkg:M:T")])])]
    }

    // ── The proof ──────────────────────────────────────────────────────

    @Test func aFullSessionRunsAcrossARealGRPCSocket() async throws {
        let (client, server, transport) = try await connectOverSocket()
        defer { transport.close(); server.shutdown() }
        let events = EventBox(client.events)

        // connect: a ConnectResult crosses the wire and back.
        let connected = try await client.connect()
        #expect(connected.isConnected)
        #expect(connected.isNetworkConnected)

        // listAccounts: a JSON array of accounts round-trips.
        let accounts = try await client.listAccounts()
        #expect(accounts == [alice, bob])

        // prepareExecuteAndWait: a nested executed event round-trips, and the
        // completion offset survives JSON text serialization as an Int64 — the
        // failure mode the in-process transport could never have caught.
        let executed = try await client.prepareExecuteAndWait(PrepareSubmission(commands: commands))
        guard case .executed(_, let updateId, let offset) = executed else {
            Issue.record("expected an executed event, got \(executed)")
            return
        }
        #expect(updateId == "update-1")
        #expect(offset == 42)

        // Events crossed the wire as notification frames: accountsChanged on
        // connect, then pending and executed for the transfer.
        let observed = try await events.wait { list in
            list.contains { if case .txChanged(.executed) = $0 { return true } else { return false } }
        }
        #expect(observed.contains { if case .accountsChanged = $0 { return true } else { return false } })
        #expect(observed.contains { if case .txChanged(.pending) = $0 { return true } else { return false } })
    }

    @Test func aProviderErrorRoundTripsAsAnErrorNotAHang() async throws {
        // listAccounts before connect is 4100. Over the wire that must come
        // back as a completed error response, not a deferred that never
        // resolves — so the whole thing is under a timeout.
        let (client, server, transport) = try await connectOverSocket()
        defer { transport.close(); server.shutdown() }

        await #expect(throws: DappError.self) {
            try await withThrowingTimeout(.seconds(10)) { try await client.listAccounts() }
        }
    }

    @Test func aUserRejectionPropagatesIts4001AcrossTheSocket() async throws {
        let (client, server, transport) = try await connectOverSocket(approve: { request in
            switch request {
            case .connection(_, _, let available): return .approved(accounts: available)
            default: return .rejected(reason: "no thanks")
            }
        })
        defer { transport.close(); server.shutdown() }
        _ = try await client.connect()

        var thrown: DappError?
        do {
            _ = try await withThrowingTimeout(.seconds(10)) {
                try await client.prepareExecuteAndWait(PrepareSubmission(commands: self.commands))
            }
        } catch let error as DappError {
            thrown = error
        }
        #expect(thrown?.code == .userRejected)
    }

    @Test func concurrentRequestsEachGetTheirOwnResponse() async throws {
        // Eight requests in flight on one stream. Correlation by id keeps their
        // responses from being swapped.
        let (client, server, transport) = try await connectOverSocket()
        defer { transport.close(); server.shutdown() }
        _ = try await client.connect()

        let results = try await withThrowingTaskGroup(of: String.self) { group -> [String] in
            for _ in 1...8 {
                group.addTask { try await client.getActiveNetwork().networkId }
            }
            var collected: [String] = []
            for try await id in group { collected.append(id) }
            return collected
        }

        #expect(results.count == 8)
        #expect(results.allSatisfy { $0 == "canton:localnet" })
    }
}

// ── Test plumbing ──────────────────────────────────────────────────────

/// Collects events off the transport's stream (they cross a socket on gRPC
/// threads) and lets a test wait for a predicate without a fixed sleep.
private final class EventBox: @unchecked Sendable {
    private final class Store: @unchecked Sendable {
        private let lock = NSLock()
        private var events: [DappEvent] = []
        func append(_ event: DappEvent) { lock.lock(); events.append(event); lock.unlock() }
        var snapshot: [DappEvent] { lock.lock(); defer { lock.unlock() }; return events }
    }

    private let store = Store()
    private let feed: Task<Void, Never>

    init(_ stream: AsyncStream<DappEvent>) {
        // The task captures `store`, not `self`, so it does not retain the box.
        let store = self.store
        feed = Task { for await event in stream { store.append(event) } }
    }

    func wait(
        timeout: Duration = .seconds(10),
        _ predicate: @Sendable ([DappEvent]) -> Bool
    ) async throws -> [DappEvent] {
        let deadline = ContinuousClock.now.advanced(by: timeout)
        while ContinuousClock.now < deadline {
            let snapshot = store.snapshot
            if predicate(snapshot) { return snapshot }
            try await Task.sleep(for: .milliseconds(20))
        }
        Issue.record("expected events not observed; saw \(store.snapshot)")
        return store.snapshot
    }

    deinit { feed.cancel() }
}

/// Runs `operation` under a deadline so a transport bug surfaces as a failure
/// rather than a hung test.
private func withThrowingTimeout<T: Sendable>(
    _ timeout: Duration,
    _ operation: @escaping @Sendable () async throws -> T
) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask { try await operation() }
        group.addTask {
            try await Task.sleep(for: timeout)
            throw TimeoutError()
        }
        let result = try await group.next()!
        group.cancelAll()
        return result
    }
}

private struct TimeoutError: Error {}
