// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonLedgerAPI
import Foundation
import GRPCCore
import GRPCNIOTransportHTTP2
import GRPCNIOTransportHTTP2Posix
import GRPCProtobuf
import Testing

import CantonKit

/// Trust-root pinning against a real TLS server: the fixtures in
/// testdata/tls/ (regenerate with tools/gen-test-certs.sh) give a CA, a
/// server leaf it signed, and a second unrelated CA.
///
/// The negative cases carry the weight. A test that only shows the right
/// pin connecting would pass just as happily if pinning were ignored
/// altogether — it is the system-default case failing that proves the
/// pinned anchors are what the handshake is actually using.
///
/// The server always runs on NIOSSL (the Posix transport) while the client
/// takes whatever the platform selects, so this exercises the
/// Network.framework path on Apple and the NIOSSL path on Linux.
@Suite struct TLSTrustTests {

    private static let fixtures = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()   // CantonKitTests
        .deletingLastPathComponent()   // Tests
        .deletingLastPathComponent()   // swift
        .deletingLastPathComponent()   // repo root
        .appendingPathComponent("testdata/tls")

    private static func der(_ name: String) throws -> Data {
        try Data(contentsOf: fixtures.appendingPathComponent(name))
    }

    /// Answers `GetLedgerApiVersion` so a successful handshake is provable
    /// by a real response rather than the absence of an error.
    private struct VersionService: RegistrableRPCService {
        func registerMethods<Transport: ServerTransport>(with router: inout RPCRouter<Transport>) {
            router.registerHandler(
                forMethod: Com_Daml_Ledger_Api_V2_VersionService.Method.GetLedgerApiVersion.descriptor,
                deserializer: ProtobufDeserializer<Com_Daml_Ledger_Api_V2_GetLedgerApiVersionRequest>(),
                serializer: ProtobufSerializer<Com_Daml_Ledger_Api_V2_GetLedgerApiVersionResponse>()
            ) { _, _ in
                var response = Com_Daml_Ledger_Api_V2_GetLedgerApiVersionResponse()
                response.version = "test-1.2.3"
                return StreamingServerResponse(single: .init(message: response))
            }
        }
    }

    private func withTLSServer<Result: Sendable>(
        _ body: @Sendable (Int) async throws -> Result
    ) async throws -> Result {
        let certificate = Self.fixtures.appendingPathComponent("server.crt").path
        let key = Self.fixtures.appendingPathComponent("server.key").path
        return try await withGRPCServer(
            transport: .http2NIOPosix(
                address: .ipv4(host: "127.0.0.1", port: 0),
                transportSecurity: .tls(
                    certificateChain: [.file(path: certificate, format: .pem)],
                    privateKey: .file(path: key, format: .pem)
                )
            ),
            services: [VersionService()]
        ) { server in
            let port = try #require(try await server.listeningAddress?.ipv4?.port)
            return try await body(port)
        }
    }

    /// The trust that must work, used as the control in rejection tests.
    private var pinnedCA: TLSTrust {
        get throws { .init(trustRoots: .certificates([try Self.der("ca.der")])) }
    }

    /// Asserts the connection is refused *and* that the same server accepts
    /// a correctly pinned client — otherwise a rejection test would pass
    /// against a server that never started.
    private func expectRejected(
        port: Int,
        trust: TLSTrust,
        host: String = "localhost"
    ) async throws {
        var rejected = configuration(port: port, trust: trust)
        rejected.host = host
        let client = CantonClient(configuration: rejected)
        await #expect(throws: (any Error).self) {
            _ = try await client.ledgerApiVersion()
        }

        let control = CantonClient(configuration: configuration(port: port, trust: try pinnedCA))
        let version = try await control.ledgerApiVersion()
        #expect(version == "test-1.2.3", "control: the server must be reachable, or the rejection proves nothing")
    }

    private func configuration(port: Int, trust: TLSTrust) -> CantonClientConfiguration {
        .init(
            host: "localhost",
            port: port,
            useTLS: true,
            tlsTrust: trust,
            retryPolicy: CantonKit.RetryPolicy(maxAttempts: 1)
        )
    }

    @Test func pinningTheIssuingCAConnects() async throws {
        let trust = try pinnedCA
        try await withTLSServer { port in
            let client = CantonClient(configuration: configuration(port: port, trust: trust))
            do {
                let version = try await client.ledgerApiVersion()
                #expect(version == "test-1.2.3")
            } catch {
                // Apple caps TLS server certificates at 398 days, so the
                // fixture leaf is short-dated by necessity: a handshake
                // failure here usually means it lapsed.
                Issue.record(
                    """
                    pinned connection failed — if the TLS fixture has expired,                     regenerate it with tools/gen-test-certs.sh: \(error)
                    """
                )
            }
        }
    }

    @Test func pinningAnUnrelatedCARejectsTheServer() async throws {
        let other = try Self.der("other-ca.der")
        try await withTLSServer { port in
            try await expectRejected(port: port, trust: .init(trustRoots: .certificates([other])))
        }
    }

    @Test func thePlatformTrustStoreRejectsTheFixtureServer() async throws {
        // Proves the pin is load-bearing: the same server the control
        // reaches is untrusted without it.
        try await withTLSServer { port in
            try await expectRejected(port: port, trust: .init())
        }
    }

    @Test func aPinnedCAStillEnforcesTheHostname() async throws {
        let trust = try pinnedCA
        try await withTLSServer { port in
            // The fixture leaf carries SANs for localhost and 127.0.0.1
            // only; any other name resolving here must still be rejected,
            // or a pinned CA would become a licence to impersonate every
            // host it signs.
            try await expectRejected(port: port, trust: trust, host: "not-the-server.test")
        }
    }

    @Test func pinnedCertificatesReflectsTheTrustRoots() throws {
        #expect(TLSTrust().pinnedCertificates == nil)
        let ca = try Self.der("ca.der")
        #expect(TLSTrust(trustRoots: .certificates([ca])).pinnedCertificates == [ca])
    }
}
