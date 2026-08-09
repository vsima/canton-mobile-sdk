// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonKit
import CryptoKit
import Foundation
import Testing

@testable import CantonWalletKit

/// SDK-level DevNet tap against Splice LocalNet — the Swift twin of the
/// Kotlin tap leg in `LocalNetTokenStandardIntegrationTest`. Skipped unless
/// SPLICE_LOCALNET=1.
///
/// `ValidatorClient.tap` mints the requested amount to the authenticated
/// user's wallet party and returns the minted holding's contract id, which
/// must show up in `TokenStandardClient.listHoldings` with exactly that
/// amount.
struct LocalNetTapIntegrationTests {
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

    @Test(.enabled(if: enabled, "SPLICE_LOCALNET not set"))
    func sdkLevelTapMintsTheRequestedAmountToTheWalletParty() async throws {
        let walletUser = Self.env("SPLICE_LOCALNET_WALLET_USER", "app-user")
        let validator = ValidatorClient(
            baseURL: URL(
                string: Self.env(
                    "SPLICE_LOCALNET_VALIDATOR_URL", "http://wallet.localhost:2000/api/validator"
                )
            )!,
            accessTokenProvider: { Self.jwt(sub: walletUser) }
        )

        // Onboard the wallet user (idempotent) through the public API.
        let status = try await validator.userStatus()
        let walletParty: String
        if status.userOnboarded, !status.partyId.isEmpty {
            walletParty = status.partyId
        } else {
            walletParty = try await validator.register()
        }
        print("wallet party: \(walletParty)")

        let client = CantonClient(
            configuration: .init(
                host: Self.env("SPLICE_LOCALNET_LEDGER_HOST", "127.0.0.1"),
                port: Int(Self.env("SPLICE_LOCALNET_LEDGER_PORT", "2901"))!,
                useTLS: false,
                accessTokenProvider: { Self.jwt(sub: walletUser) }
            )
        )
        let tokens = TokenStandardClient(client: client)
        let beforeTotal = try await tokens.listHoldings(partyId: walletParty)
            .compactMap { Decimal(string: $0.amount) }
            .reduce(0, +)

        // Distinctive amount: nothing else on LocalNet mints this value.
        // Right after a fresh boot the tap fails until a mining round opens,
        // so retry with a deadline.
        let amount = "231.7654"
        var mintedCid: String?
        for attempt in 1...60 {
            do {
                mintedCid = try await validator.tap(amount: amount)
                break
            } catch {
                print("  (tap attempt \(attempt): \(String(describing: error).prefix(160)))")
                try await Task.sleep(for: .seconds(5))
            }
        }
        let contractId = try #require(mintedCid, "tap never succeeded")
        print("tap minted contract: \(contractId.prefix(20))…")

        // The minted holding reaches the wallet party's ACS with the exact amount.
        var holdings: [Holding] = []
        for attempt in 1...60 {
            holdings = try await tokens.listHoldings(partyId: walletParty)
            if holdings.contains(where: { $0.contractId == contractId }) {
                break
            }
            print("  (attempt \(attempt): minted holding not in the ACS yet)")
            try await Task.sleep(for: .seconds(2))
        }
        let minted = try #require(
            holdings.first { $0.contractId == contractId },
            "tapped holding must reach the ACS"
        )
        print("minted holding: \(minted.amount) \(minted.instrumentId.id)")
        #expect(minted.owner == walletParty)
        #expect(minted.instrumentId.id == "Amulet")
        #expect(
            Decimal(string: minted.amount) == Decimal(string: amount),
            "tapped holding must carry the exact amount, got \(minted.amount)"
        )
        let afterTotal = holdings.compactMap { Decimal(string: $0.amount) }.reduce(0, +)
        #expect(
            afterTotal >= beforeTotal + Decimal(string: amount)!,
            "holdings must increase by the tapped amount: before=\(beforeTotal) after=\(afterTotal)"
        )
    }
}
