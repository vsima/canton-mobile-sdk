// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonKit
import CantonLedgerAPI
import CryptoKit
import Foundation
import Testing

/// Live verification of token-expiry behaviour against a real participant —
/// the Swift sibling of the Kotlin SDK's `LocalNetTokenExpiryProbe`, and the
/// same findings apply: an expired token is rejected on ADMISSION with
/// `unauthenticated` and no RetryInfo, while this participant does not
/// abort an already-open idle stream at expiry (deployments with ongoing
/// auth checks abort with `ACCESS_TOKEN_EXPIRED`).
struct LocalNetTokenExpiryIntegrationTests {
    private static var enabled: Bool {
        ProcessInfo.processInfo.environment["SPLICE_LOCALNET"] == "1"
    }

    private static func env(_ name: String, _ fallback: String) -> String {
        ProcessInfo.processInfo.environment[name] ?? fallback
    }

    private static var host: String { env("SPLICE_LOCALNET_LEDGER_HOST", "127.0.0.1") }
    private static var port: Int { Int(env("SPLICE_LOCALNET_LEDGER_PORT", "2901"))! }
    private static var adminUser: String { env("SPLICE_LOCALNET_ADMIN_USER", "ledger-api-user") }

    /// Unsafe HS256 LocalNet JWT with an explicit `exp`.
    private static func jwt(sub: String, expEpochSecond: Int) -> String {
        func b64(_ data: Data) -> String {
            data.base64EncodedString()
                .replacingOccurrences(of: "+", with: "-")
                .replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: "")
        }
        let audience = env("SPLICE_LOCALNET_AUDIENCE", "https://canton.network.global")
        let header = b64(Data(#"{"alg":"HS256","typ":"JWT"}"#.utf8))
        let payload = b64(
            Data(#"{"sub":"\#(sub)","aud":"\#(audience)","exp":\#(expEpochSecond)}"#.utf8)
        )
        let mac = HMAC<SHA256>.authenticationCode(
            for: Data("\(header).\(payload)".utf8),
            using: SymmetricKey(data: Data("unsafe".utf8))
        )
        return "\(header).\(payload).\(b64(Data(mac)))"
    }

    private static func now() -> Int { Int(Date().timeIntervalSince1970) }

    @Test(.enabled(if: enabled, "SPLICE_LOCALNET not set"))
    func expiredTokenFailsNewCallsAsUnauthenticated() async throws {
        let client = CantonClient(
            configuration: .init(
                host: Self.host,
                port: Self.port,
                useTLS: false,
                accessTokenProvider: { Self.jwt(sub: Self.adminUser, expEpochSecond: Self.now() - 60) }
            )
        )
        do {
            _ = try await client.ledgerEnd()
            Issue.record("an expired token must be rejected")
        } catch let error as CantonError {
            #expect(error.grpcCode == .unauthenticated)
            #expect(!error.isRetryable, "no RetryInfo rides on auth failures")
            #expect(error.isAuthFailure)
        }
    }

    /// End-to-end recovery on the enforced path: connection 1 presents an
    /// expired token and is rejected; the client refreshes through the
    /// provider, reconnects, and the stream must still be open — no error —
    /// after outliving the healthy window.
    @Test(.enabled(if: enabled, "SPLICE_LOCALNET not set"))
    func streamRecoversFromAnExpiredTokenThroughTheProvider() async throws {
        // Offset and party come from a separate, always-valid client so the
        // client under test performs exactly one RPC: the stream connect.
        let setupClient = CantonClient(
            configuration: .init(
                host: Self.host,
                port: Self.port,
                useTLS: false,
                accessTokenProvider: { Self.jwt(sub: Self.adminUser, expEpochSecond: Self.now() + 300) }
            )
        )
        let end = try await setupClient.ledgerEnd()
        let party = try await setupClient.withServices { services in
            let admin = Com_Daml_Ledger_Api_V2_Admin_PartyManagementService.Client(
                wrapping: services.grpc
            )
            let known = try await admin.listKnownParties(.init())
            return try #require(
                known.partyDetails.first(where: \.isLocal)?.party,
                "no local party on the participant"
            )
        }

        let fetches = Fetches()
        let client = CantonClient(
            configuration: .init(
                host: Self.host,
                port: Self.port,
                useTLS: false,
                accessTokenProvider: {
                    // The stream's first connection gets an EXPIRED token;
                    // the recovery refresh gets a valid one.
                    let n = await fetches.next()
                    return Self.jwt(
                        sub: Self.adminUser,
                        expEpochSecond: n == 1 ? Self.now() - 60 : Self.now() + 3600
                    )
                }
            )
        )

        let subscription = UpdateSubscription(parties: [party], beginExclusive: end)
        let outcome = try await withThrowingTaskGroup(of: String.self) { group in
            group.addTask {
                do {
                    for try await _ in client.updates(subscription) {}
                    return "stream-ended"
                } catch is CancellationError {
                    return "still-open"
                } catch {
                    return "stream-failed: \(error)"
                }
            }
            group.addTask {
                try await Task.sleep(for: .seconds(15))
                return "still-open"
            }
            let first = try await group.next()!
            group.cancelAll()
            return first
        }

        #expect(outcome == "still-open",
                "the stream must recover from the expired token: \(outcome)")
        #expect(await fetches.count == 2,
                "expected exactly one recovery refresh after the expired token")
    }

    private actor Fetches {
        private(set) var count = 0
        func next() -> Int {
            count += 1
            return count
        }
    }
}
