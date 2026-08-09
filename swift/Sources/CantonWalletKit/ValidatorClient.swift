// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// A validator API call failed: non-2xx status (see `statusCode`) or a
/// malformed payload (`statusCode` is nil). Taps in particular fail with
/// 400/404 until the network has an open mining round, and 429/503 under
/// load — all worth retrying.
public struct ValidatorError: Error, CustomStringConvertible {
    public let statusCode: Int?
    public let description: String
}

/// Client for a validator's user-facing wallet API (`.../api/validator`) —
/// the onboarding and faucet operations a wallet app drives against its own
/// validator, authenticated as the end user.
///
/// Every call sends a bearer token from `accessTokenProvider`; the validator
/// derives the ledger user from the token's subject claim. On LocalNet that
/// is the unsafe HS256 JWT the integration harness mints; against a real
/// validator it is the user's OAuth access token.
///
/// Unlike ``ScanClient`` (public network index) and
/// ``TransferRegistryClient`` (registry choice contexts), this API is
/// validator-local: it only answers for users of the validator behind
/// `baseURL`.
public struct ValidatorClient: Sendable {
    public let baseURL: URL
    private let accessTokenProvider: @Sendable () async throws -> String
    private let session: URLSession

    public init(
        baseURL: URL,
        accessTokenProvider: @escaping @Sendable () async throws -> String,
        session: URLSession = .shared
    ) {
        self.baseURL = baseURL
        self.accessTokenProvider = accessTokenProvider
        self.session = session
    }

    /// Onboarding state of the authenticated user on this validator.
    public struct WalletUserStatus: Sendable, Equatable {
        /// The user's wallet party — empty until the user is onboarded.
        public let partyId: String
        public let userOnboarded: Bool
        public let userWalletInstalled: Bool
    }

    /// The authenticated user's onboarding state (`/v0/wallet/user-status`).
    public func userStatus() async throws -> WalletUserStatus {
        let response = try await request(path: "v0/wallet/user-status", method: "GET")
        guard
            let partyId = response["party_id"] as? String,
            let userOnboarded = response["user_onboarded"] as? Bool,
            let userWalletInstalled = response["user_wallet_installed"] as? Bool
        else {
            throw ValidatorError(statusCode: nil, description: "malformed user status: \(response)")
        }
        return WalletUserStatus(
            partyId: partyId,
            userOnboarded: userOnboarded,
            userWalletInstalled: userWalletInstalled
        )
    }

    /// Onboards the authenticated user onto this validator (`/v0/register`):
    /// allocates the ledger user and wallet party and installs the wallet
    /// contracts. Idempotent — an already-onboarded user just gets its party
    /// back.
    ///
    /// - Returns: the user's wallet party id.
    public func register() async throws -> String {
        let response = try await request(path: "v0/register", method: "POST", body: [:])
        guard let partyId = response["party_id"] as? String else {
            throw ValidatorError(statusCode: nil, description: "register response missing party_id")
        }
        return partyId
    }

    /// Taps the faucet (`/v0/wallet/tap`): mints Amulet to the authenticated
    /// user's wallet party. **Test networks only** — DevNet and LocalNet
    /// validators expose the tap; on MainNet it fails.
    ///
    /// The amount is denominated in **USD**, matching the validator wallet's
    /// tap: the minted Amulet quantity is `amountUsd / amuletPrice` at the
    /// latest open mining round's price (rounded up). On LocalNet the price
    /// is 0.005 USD/CC, so a 5 USD tap mints 1000 CC.
    ///
    /// Right after network bootstrap the tap fails until the first mining
    /// round opens (400/404, also 429/503 under load — retry those; see
    /// ``ValidatorError``).
    ///
    /// - Parameters:
    ///   - amountUsd: the USD value to mint as Amulet, a positive Daml
    ///     Decimal as its canonical string (e.g. `"50.0"`, at most 10
    ///     decimal places).
    ///   - commandId: optional command id for deduplication; the validator
    ///     generates a random one when absent.
    /// - Returns: the contract id of the minted Amulet holding — watch for
    ///   it in ``TokenStandardClient/listHoldings(partyId:)``.
    public func tap(amountUsd: String, commandId: String? = nil) async throws -> String {
        guard let decimal = Decimal(string: amountUsd, locale: Locale(identifier: "en_US_POSIX")),
            decimal > 0
        else {
            throw ValidatorError(statusCode: nil, description: "tap amount must be positive, got \(amountUsd)")
        }
        var body: [String: Any] = ["amount": amountUsd]
        if let commandId {
            body["command_id"] = commandId
        }
        let response = try await request(path: "v0/wallet/tap", method: "POST", body: body)
        guard let contractId = response["contract_id"] as? String else {
            throw ValidatorError(statusCode: nil, description: "tap response missing contract_id")
        }
        return contractId
    }

    private func request(
        path: String,
        method: String,
        body: [String: Any]? = nil
    ) async throws -> [String: Any] {
        var request = URLRequest(url: baseURL.appendingPathComponent(path))
        request.httpMethod = method
        request.setValue(
            "Bearer \(try await accessTokenProvider())",
            forHTTPHeaderField: "Authorization"
        )
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            let text = String(data: data, encoding: .utf8)?.prefix(300) ?? ""
            throw ValidatorError(statusCode: status, description: "HTTP \(status) from \(path): \(text)")
        }
        if data.isEmpty {
            return [:]
        }
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ValidatorError(
                statusCode: nil,
                description: "validator response from \(path) is not a JSON object"
            )
        }
        return object
    }
}
