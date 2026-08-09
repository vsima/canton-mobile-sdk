// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CryptoKit
import Foundation
import Testing

@testable import CantonWalletKit

#if canImport(FoundationNetworking)
    import FoundationNetworking
#endif

/// The traffic-purchase loop against Splice LocalNet — the Swift twin of the
/// Kotlin `LocalNetTrafficIntegrationTest`. Skipped unless SPLICE_LOCALNET=1.
///
/// The full loop, all through the public SDK surface: resolve the active
/// synchronizer and the wallet party's participant, read the participant's
/// traffic status, tap enough USD to cover the minimum top-up, buy exactly
/// `minTopupAmount` bytes via `ValidatorClient.buyTraffic`, poll
/// `buyTrafficStatus` until the wallet automation completes the purchase,
/// and poll `ScanClient.memberTrafficStatus` until the purchased total
/// reflects the bought bytes.
struct LocalNetTrafficIntegrationTests {
    private static var enabled: Bool {
        ProcessInfo.processInfo.environment["SPLICE_LOCALNET"] == "1"
    }

    private static func env(_ name: String, _ fallback: String) -> String {
        ProcessInfo.processInfo.environment[name] ?? fallback
    }

    /// Unsafe HS256 JWT matching LocalNet's `unsafe-jwt-hmac-256` auth service.
    private static func jwt(sub: String) -> String {
        func b64(_ data: Data) -> String {
            data.base64EncodedString()
                .replacingOccurrences(of: "+", with: "-")
                .replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: "")
        }
        let audience = env("SPLICE_LOCALNET_AUDIENCE", "https://canton.network.global")
        let header = b64(Data(#"{"alg":"HS256","typ":"JWT"}"#.utf8))
        let payload = b64(Data(#"{"sub":"\#(sub)","aud":"\#(audience)"}"#.utf8))
        let mac = HMAC<SHA256>.authenticationCode(
            for: Data("\(header).\(payload)".utf8),
            using: SymmetricKey(data: Data("unsafe".utf8))
        )
        return "\(header).\(payload).\(b64(Data(mac)))"
    }

    private static var validator: ValidatorClient {
        ValidatorClient(
            baseURL: URL(
                string: env("SPLICE_LOCALNET_VALIDATOR_URL", "http://wallet.localhost:2000/api/validator")
            )!,
            accessTokenProvider: { jwt(sub: env("SPLICE_LOCALNET_WALLET_USER", "app-user")) }
        )
    }

    private static func onboardWalletUser() async throws -> String {
        if let status = try? await validator.userStatus(),
            status.userOnboarded, !status.partyId.isEmpty
        {
            return status.partyId
        }
        return try await retryUntil("wallet user onboarding") {
            try await validator.register()
        }
    }

    private static func retryUntil<T>(
        _ what: String,
        attempts: Int = 120,
        delaySeconds: Int = 5,
        _ block: () async throws -> T?
    ) async throws -> T {
        for attempt in 1...attempts {
            do {
                if let result = try await block() { return result }
            } catch {
                print("  (\(what) attempt \(attempt): \(String(describing: error).prefix(160)))")
            }
            try await Task.sleep(for: .seconds(delaySeconds))
        }
        Issue.record("\(what): not satisfied after \(attempts) attempts")
        throw ValidatorError(
            statusCode: nil, description: "\(what): not satisfied after \(attempts) attempts"
        )
    }

    @Test(.enabled(if: enabled, "SPLICE_LOCALNET not set"))
    func buyingTheMinimumTopUpIncreasesThePurchasedTraffic() async throws {
        let scan = ScanClient(
            baseURL: URL(
                string: Self.env("SPLICE_LOCALNET_SCAN_URL", "http://scan.localhost:4000/api/scan")
            )!
        )

        // 1. Where to buy: the active synchronizer; whose traffic: the
        // participant hosting the wallet party.
        let config = try await scan.amuletRulesConfig()
        let synchronizerId = config.activeSynchronizerId
        let minTopup = config.synchronizerFees.minTopupAmountBytes
        print("synchronizer: \(synchronizerId), minTopupAmount: \(minTopup) bytes")

        let walletParty = try await Self.onboardWalletUser()
        let memberId = try await Self.retryUntil("participant id resolves") {
            try await scan.partyParticipantId(synchronizerId: synchronizerId, partyId: walletParty)
        }
        print("member: \(memberId)")
        #expect(memberId.hasPrefix("PAR::"), "member must be a participant id")

        // 2. The starting traffic state.
        let before = try await Self.retryUntil("traffic status readable") {
            try await scan.memberTrafficStatus(synchronizerId: synchronizerId, memberId: memberId)
        }
        print("before: \(before)")

        // An unknown tracking id answers nil, not an error.
        #expect(try await Self.validator.buyTrafficStatus(trackingId: "never-created-\(UUID())") == nil)

        // 3. Fund the purchase (minTopup bytes ≈ $3.33 at LocalNet's
        // extraTrafficPrice) and request it.
        _ = try await Self.retryUntil("tap 25 USD (waits for an open mining round)") {
            try await Self.validator.tap(amountUsd: "25.0")
        }
        let request = try await Self.validator.buyTraffic(
            trafficAmountBytes: minTopup,
            receivingValidatorPartyId: walletParty,
            synchronizerId: synchronizerId
        )
        print("buy-traffic request: \(request.requestContractId.prefix(20))… tracking=\(request.trackingId)")
        #expect(!request.requestContractId.isEmpty)

        // 4. The wallet automation executes it asynchronously.
        let completed = try await Self.retryUntil("buy-traffic request completes", attempts: 36) {
            switch try await Self.validator.buyTrafficStatus(trackingId: request.trackingId) {
            case .completed(let transactionId):
                return transactionId
            case .failed(let reason, let rejectionReason):
                Issue.record("buy-traffic failed: \(reason) \(rejectionReason ?? "")")
                throw ValidatorError(
                    statusCode: nil,
                    description: "buy-traffic failed: \(reason) \(rejectionReason ?? "")"
                )
            case let status:
                print("  (buy-traffic status: \(String(describing: status)))")
                return nil
            }
        }
        print("completed in transaction \(completed)")
        #expect(!completed.isEmpty)

        // 5. The purchase reflects in the member's traffic totals.
        let after = try await Self.retryUntil(
            "purchased traffic reflects the top-up", attempts: 36
        ) {
            let status = try await scan.memberTrafficStatus(
                synchronizerId: synchronizerId, memberId: memberId
            )
            guard let status, status.totalPurchasedBytes >= before.totalPurchasedBytes + minTopup
            else {
                return nil as ScanClient.MemberTrafficStatus?
            }
            return status
        }
        print("after: \(after)")
        #expect(
            after.totalPurchasedBytes >= before.totalPurchasedBytes + minTopup,
            "purchased must grow by >= \(minTopup): before=\(before.totalPurchasedBytes) after=\(after.totalPurchasedBytes)"
        )
    }
}
