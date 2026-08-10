// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonLedgerAPI
import Foundation
import GRPCCore
import GRPCNIOTransportHTTP2
import GRPCProtobuf
import Testing

import CantonKit

/// The reconnect behaviour LocalNet probing pinned down: an auth-coded
/// stream failure must reconnect with a freshly fetched token when the
/// provider can mint one — and must NOT spin when it can't. The Swift
/// sibling of the Kotlin SDK's `UpdateStreamAuthRecoveryTest`.
@Suite struct UpdateStreamAuthRecoveryTests {

    private typealias GetUpdatesResponse = Com_Daml_Ledger_Api_V2_GetUpdatesResponse
    private typealias Writer = RPCWriter<Com_Daml_Ledger_Api_V2_GetUpdatesResponse>

    /// Serves `GetUpdates` by running `behavior` per accepted connection
    /// (0-based index) and records each connection's bearer token.
    private final class FakeUpdateService: RegistrableRPCService {
        private actor Log {
            private(set) var tokens: [String?] = []

            func record(_ token: String?) -> Int {
                tokens.append(token)
                return tokens.count - 1
            }
        }

        private let log = Log()
        private let behavior: @Sendable (Int, Writer) async throws -> Void

        init(_ behavior: @escaping @Sendable (Int, Writer) async throws -> Void) {
            self.behavior = behavior
        }

        var tokens: [String?] {
            get async { await log.tokens }
        }

        var connections: Int {
            get async { await log.tokens.count }
        }

        func registerMethods<Transport: ServerTransport>(with router: inout RPCRouter<Transport>) {
            router.registerHandler(
                forMethod: Com_Daml_Ledger_Api_V2_UpdateService.Method.GetUpdates.descriptor,
                deserializer: ProtobufDeserializer<Com_Daml_Ledger_Api_V2_GetUpdatesRequest>(),
                serializer: ProtobufSerializer<GetUpdatesResponse>()
            ) { request, _ in
                let bearer = Array(request.metadata[stringValues: "authorization"]).first
                let connection = await self.log.record(
                    bearer.map { $0.replacingOccurrences(of: "Bearer ", with: "") }
                )
                var messages = request.messages.makeAsyncIterator()
                _ = try await messages.next()
                return StreamingServerResponse(metadata: [:]) { writer in
                    try await self.behavior(connection, writer)
                    return [:]
                }
            }
        }
    }

    private final class Counter: @unchecked Sendable {
        private let lock = NSLock()
        private var n = 0
        func next() -> Int {
            lock.lock()
            defer { lock.unlock() }
            n += 1
            return n
        }
    }

    private let subscription = UpdateSubscription(parties: ["alice::ns"], beginExclusive: 0)

    private let testPolicy = CantonKit.RetryPolicy(
        maxAttempts: 4,
        initialBackoff: .milliseconds(1),
        maxBackoff: .milliseconds(2)
    )

    private func checkpoint(offset: Int64) -> GetUpdatesResponse {
        var checkpoint = Com_Daml_Ledger_Api_V2_OffsetCheckpoint()
        checkpoint.offset = offset
        var response = GetUpdatesResponse()
        response.offsetCheckpoint = checkpoint
        return response
    }

    private var authDenied: RPCError {
        RPCError(code: .permissionDenied, message: "auth stand-in")
    }

    /// Serves `service` on a loopback port and runs `body` against a
    /// `CantonClient` whose provider is `tokenProvider`.
    private func withService<Result: Sendable>(
        _ service: FakeUpdateService,
        tokenProvider: @escaping @Sendable () async throws -> String,
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
                configuration: .init(
                    host: "127.0.0.1",
                    port: port,
                    useTLS: false,
                    accessTokenProvider: tokenProvider,
                    retryPolicy: testPolicy
                )
            )
            return try await body(client)
        }
    }

    @Test func streamSurvivesAnAuthTerminationWhenTheProviderMintsAFreshToken() async throws {
        let denied = authDenied
        let checkpoint = self.checkpoint
        let service = FakeUpdateService { connection, writer in
            if connection == 0 {
                throw denied
            }
            try await writer.write(checkpoint(7))
        }
        let minted = Counter()

        let offset = try await withService(service, tokenProvider: { "token-\(minted.next())" }) { client in
            for try await update in client.updates(subscription) {
                return update.offset
            }
            return Int64(-1)
        }

        #expect(offset == 7, "the update after recovery must reach the consumer")
        #expect(await service.connections == 2, "one reconnect, no spinning")
        #expect(await service.tokens == ["token-1", "token-2"],
                "the reconnect must carry the freshly minted token")
    }

    @Test func streamDoesNotSpinWhenTheProviderHasNothingFresher() async throws {
        let denied = authDenied
        let service = FakeUpdateService { _, _ in throw denied }

        await #expect(throws: CantonError.self) {
            try await self.withService(service, tokenProvider: { "static-token" }) { client in
                for try await _ in client.updates(self.subscription) {}
            }
        }
        #expect(await service.connections == 1,
                "an unchanged token must fail without reconnecting")
    }

    @Test func aSecondAuthFailureWithoutAHealthyConnectionPropagates() async throws {
        let denied = authDenied
        let service = FakeUpdateService { _, _ in throw denied }
        let minted = Counter()

        await #expect(throws: CantonError.self) {
            try await self.withService(service, tokenProvider: { "token-\(minted.next())" }) { client in
                for try await _ in client.updates(self.subscription) {}
            }
        }
        #expect(await service.connections == 2,
                "exactly one recovery attempt even though every fetch differs")
    }
}
