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
/// the onboarding, faucet, and traffic-purchase operations a wallet app
/// drives against its own validator, authenticated as the end user.
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

    /// A created buy-traffic request, identified for status polling.
    public struct BuyTrafficRequest: Sendable, Equatable {
        /// The tracking id the request was created under — poll
        /// ``ValidatorClient/buyTrafficStatus(trackingId:)`` with it.
        public let trackingId: String
        /// Contract id of the on-ledger `BuyTrafficRequest` the wallet
        /// automation executes.
        public let requestContractId: String
    }

    /// Where a buy-traffic request stands (``buyTrafficStatus(trackingId:)``).
    public enum BuyTrafficStatus: Sendable, Equatable {
        /// Why a buy-traffic request failed permanently.
        public enum FailureReason: Sendable, Equatable {
            /// The wallet automation did not process the request before its expiry.
            case expired
            /// The automation rejected it — e.g. insufficient funds or below
            /// the minimum top-up.
            case rejected
        }

        /// Created and waiting for the validator's wallet automation to execute it.
        case created
        /// The traffic has been purchased; the payload is the update id of
        /// the ledger transaction that purchased it.
        case completed(transactionId: String)
        /// Failed permanently; no CC was spent. Retry with a *fresh*
        /// tracking id — the failed one stays burned. `rejectionReason` is
        /// the automation's human-readable detail, when provided.
        case failed(reason: FailureReason, rejectionReason: String?)
    }

    /// Requests a synchronizer extra-traffic purchase
    /// (`/v0/wallet/buy-traffic-requests`), paid in Amulet from the
    /// authenticated user's wallet.
    ///
    /// **Whose traffic:** the sequencer member that gets the bytes is the
    /// *participant node hosting `receivingValidatorPartyId`* — for a wallet
    /// user that is the validator's own participant, bought on the user's
    /// behalf (participant-level traffic is shared by every party the
    /// validator hosts). Watch it land via
    /// ``ScanClient/memberTrafficStatus(synchronizerId:memberId:)`` for that
    /// participant (``ScanClient/partyParticipantId(synchronizerId:partyId:)``
    /// resolves it).
    ///
    /// This call only *creates* the request; the validator's wallet
    /// automation executes `AmuletRules_BuyMemberTraffic` asynchronously.
    /// Poll ``buyTrafficStatus(trackingId:)`` with the returned tracking id
    /// until it reports ``BuyTrafficStatus/completed(transactionId:)`` or
    /// ``BuyTrafficStatus/failed(reason:rejectionReason:)``.
    ///
    /// The purchase burns Amulet worth `bytes × extraTrafficPrice` (USD/MB,
    /// converted at the open round's amulet price) and must buy at least
    /// `minTopupAmount` bytes — both published in
    /// ``ScanClient/amuletRulesConfig(asOf:)``'s ``SynchronizerFeeConfig``.
    ///
    /// - Parameters:
    ///   - trafficAmountBytes: bytes of extra traffic to buy, at least the
    ///     network's `minTopupAmount` (the automation rejects smaller
    ///     requests).
    ///   - receivingValidatorPartyId: traffic goes to the participant
    ///     hosting this party — pass the user's wallet party to top up its
    ///     own validator.
    ///   - synchronizerId: the synchronizer to buy traffic on
    ///     (``AmuletRulesConfig/activeSynchronizerId``).
    ///   - trackingId: exactly-once key: reuse the same id when retrying a
    ///     submission that may already have gone through (a duplicate
    ///     answers 409/429, see ``ValidatorError/statusCode``); use a fresh
    ///     id for a genuinely new purchase.
    ///   - expiresAt: when the unexecuted request lapses (compared against
    ///     ledger time; default 10 minutes out).
    public func buyTraffic(
        trafficAmountBytes: Int64,
        receivingValidatorPartyId: String,
        synchronizerId: String,
        trackingId: String = UUID().uuidString,
        expiresAt: Date = Date().addingTimeInterval(600)
    ) async throws -> BuyTrafficRequest {
        guard trafficAmountBytes > 0 else {
            throw ValidatorError(
                statusCode: nil,
                description: "trafficAmountBytes must be positive, got \(trafficAmountBytes)"
            )
        }
        let body: [String: Any] = [
            "receiving_validator_party_id": receivingValidatorPartyId,
            "domain_id": synchronizerId,
            "traffic_amount": NSNumber(value: trafficAmountBytes),
            "tracking_id": trackingId,
            "expires_at": NSNumber(value: Int64(expiresAt.timeIntervalSince1970 * 1_000_000)),
        ]
        let response = try await request(
            path: "v0/wallet/buy-traffic-requests", method: "POST", body: body
        )
        guard let contractId = response["request_contract_id"] as? String else {
            throw ValidatorError(
                statusCode: nil,
                description: "buy-traffic response missing request_contract_id"
            )
        }
        return BuyTrafficRequest(trackingId: trackingId, requestContractId: contractId)
    }

    /// Where the buy-traffic request created under `trackingId` stands
    /// (`/v0/wallet/buy-traffic-requests/{tracking_id}/status`), or nil if
    /// the validator knows no such request — not yet processed, or already
    /// beyond the wallet's transaction-log horizon.
    public func buyTrafficStatus(trackingId: String) async throws -> BuyTrafficStatus? {
        let response: [String: Any]
        do {
            response = try await request(
                path: "v0/wallet/buy-traffic-requests/\(trackingId)/status",
                method: "POST",
                body: [:]
            )
        } catch let error as ValidatorError where error.statusCode == 404 {
            return nil
        }
        switch response["status"] as? String {
        case "created":
            return .created
        case "completed":
            guard let transactionId = response["transaction_id"] as? String else {
                throw ValidatorError(
                    statusCode: nil,
                    description: "completed buy-traffic status missing transaction_id"
                )
            }
            return .completed(transactionId: transactionId)
        case "failed":
            let reason: BuyTrafficStatus.FailureReason
            switch response["failure_reason"] as? String {
            case "expired": reason = .expired
            case "rejected": reason = .rejected
            case let other:
                throw ValidatorError(
                    statusCode: nil,
                    description: "unknown buy-traffic failure reason: \(other ?? "nil")"
                )
            }
            return .failed(
                reason: reason,
                rejectionReason: response["rejection_reason"] as? String
            )
        case let other:
            throw ValidatorError(
                statusCode: nil,
                description: "unknown buy-traffic status: \(other ?? "nil")"
            )
        }
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
