// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonLedgerAPI
import GRPCCore
import GRPCNIOTransportHTTP2
import GRPCProtobuf
import Testing

import CantonKit

/// Exercises the reconnect/backoff behavior of `CantonClient.updates(_:)`
/// against a scripted local UpdateService (the Swift sibling of the Kotlin
/// SDK's `UpdateStreamTest` fakes) — no live ledger required.
@Suite struct UpdateStreamTests {

    private typealias GetUpdatesResponse = Com_Daml_Ledger_Api_V2_GetUpdatesResponse
    private typealias Writer = RPCWriter<Com_Daml_Ledger_Api_V2_GetUpdatesResponse>

    /// Serves `GetUpdates` by running `behavior` for each accepted
    /// connection (0-based index, requested resume offset, response writer)
    /// and records every `begin_exclusive` it was asked to resume from.
    private final class FakeUpdateService: RegistrableRPCService {
        private actor Log {
            private(set) var begins: [Int64] = []

            func record(_ begin: Int64) -> Int {
                begins.append(begin)
                return begins.count - 1
            }
        }

        private let log = Log()
        private let behavior: @Sendable (Int, Int64, Writer) async throws -> Void

        init(_ behavior: @escaping @Sendable (Int, Int64, Writer) async throws -> Void) {
            self.behavior = behavior
        }

        var begins: [Int64] {
            get async { await log.begins }
        }

        func registerMethods<Transport: ServerTransport>(with router: inout RPCRouter<Transport>) {
            router.registerHandler(
                forMethod: Com_Daml_Ledger_Api_V2_UpdateService.Method.GetUpdates.descriptor,
                deserializer: ProtobufDeserializer<Com_Daml_Ledger_Api_V2_GetUpdatesRequest>(),
                serializer: ProtobufSerializer<GetUpdatesResponse>()
            ) { request, _ in
                var messages = request.messages.makeAsyncIterator()
                let begin = try await messages.next()?.beginExclusive ?? 0
                let connection = await self.log.record(begin)
                return StreamingServerResponse(metadata: [:]) { writer in
                    try await self.behavior(connection, begin, writer)
                    return [:]
                }
            }
        }
    }

    private let subscription = UpdateSubscription(parties: ["alice::ns"], beginExclusive: 0)

    // Fully qualified: GRPCCore also declares a (client-config) RetryPolicy.
    private let testPolicy = CantonKit.RetryPolicy(
        maxAttempts: 4,
        initialBackoff: .milliseconds(1),
        maxBackoff: .milliseconds(2)
    )

    /// Serves `service` on a loopback port and runs `body` against a
    /// `CantonClient` connected to it.
    private func withService<Result: Sendable>(
        _ service: FakeUpdateService,
        policy: CantonKit.RetryPolicy,
        _ body: @Sendable (CantonClient) async throws -> Result
    ) async throws -> Result {
        try await withGRPCServer(
            transport: .http2NIOPosix(
                address: .ipv4(host: "127.0.0.1", port: 0),
                transportSecurity: .plaintext
            ),
            services: [service]
        ) { server in
            let port = try #require(try await server.listeningAddress?.ipv4?.port)
            let client = CantonClient(
                configuration: .init(host: "127.0.0.1", port: port, useTLS: false, retryPolicy: policy)
            )
            return try await body(client)
        }
    }

    private func transaction(offset: Int64) -> GetUpdatesResponse {
        var transaction = Com_Daml_Ledger_Api_V2_Transaction()
        transaction.updateID = "update-\(offset)"
        transaction.offset = offset
        var response = GetUpdatesResponse()
        response.transaction = transaction
        return response
    }

    private func checkpoint(offset: Int64) -> GetUpdatesResponse {
        var checkpoint = Com_Daml_Ledger_Api_V2_OffsetCheckpoint()
        checkpoint.offset = offset
        var response = GetUpdatesResponse()
        response.offsetCheckpoint = checkpoint
        return response
    }

    private var streamLost: RPCError {
        RPCError(code: .unavailable, message: "stream lost")
    }

    @Test func resumesAfterAMidStreamFailureFromTheLastReceivedOffset() async throws {
        // Emits offsets 1 (transaction) and 2 (checkpoint), dies with a
        // retryable error, then serves offset 3 and completes on the retry.
        let lost = streamLost
        let transaction = self.transaction
        let checkpoint = self.checkpoint
        let service = FakeUpdateService { connection, _, writer in
            if connection == 0 {
                try await writer.write(transaction(1))
                try await writer.write(checkpoint(2))
                throw lost
            } else {
                try await writer.write(transaction(3))
            }
        }

        let updates = try await withService(service, policy: testPolicy) { client in
            var collected: [LedgerUpdate] = []
            for try await update in client.updates(subscription) {
                collected.append(update)
            }
            return collected
        }

        #expect(updates.map(\.offset) == [1, 2, 3])
        // Resumed from the checkpoint offset, not the original begin —
        // no duplicates, no gaps.
        #expect(await service.begins == [0, 2])
    }

    @Test func aStreamThatProgressesButKeepsDyingYoungExhaustsTheRetryBudget() async throws {
        // Every connection delivers one update at the next offset and then
        // immediately dies — progress on every attempt, but with testPolicy's
        // default 10s healthy window no connection ever resets the budget.
        let lost = streamLost
        let transaction = self.transaction
        let service = FakeUpdateService { _, begin, writer in
            try await writer.write(transaction(begin + 1))
            throw lost
        }

        await #expect(throws: CantonError.self) {
            try await self.withService(service, policy: self.testPolicy) { client in
                for try await _ in client.updates(self.subscription) {}
            }
        }
        // One connection per attempt in the budget — not an infinite loop —
        // each still resuming from the previous connection's last offset.
        #expect(await service.begins == [0, 1, 2, 3])
    }

    @Test func aConnectionThatStaysHealthyPastTheWindowEarnsAFreshRetryBudget() async throws {
        // Window well below the per-connection lifetime and a budget of two
        // attempts: only healthy-window resets let the stream survive four
        // failures and reach the completing fifth connection.
        var policy = testPolicy
        policy.maxAttempts = 2
        policy.streamHealthyWindow = .milliseconds(10)

        let lost = streamLost
        let transaction = self.transaction
        let service = FakeUpdateService { connection, begin, writer in
            try await writer.write(transaction(begin + 1))
            if connection < 4 {
                try await Task.sleep(for: .milliseconds(50))
                throw lost
            }
        }

        let offsets = try await withService(service, policy: policy) { client in
            var offsets: [Int64] = []
            for try await update in client.updates(subscription) {
                offsets.append(update.offset)
            }
            return offsets
        }

        #expect(offsets == [1, 2, 3, 4, 5])
        #expect(await service.begins == [0, 1, 2, 3, 4])
    }
}
