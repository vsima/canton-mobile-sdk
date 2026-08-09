// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// A scan call failed (non-2xx status other than 404, or malformed payload).
public struct ScanError: Error, CustomStringConvertible {
    public let description: String
}

/// Read layer over a Super Validator's Scan API (`.../api/scan`).
///
/// Covers the reads a wallet needs from the network's public index: the DSO
/// party, ANS name resolution (name-based sending), aggregated holdings from
/// scan's server-side ACS snapshots, the AmuletRules fee configuration, open
/// mining rounds (amulet price), and per-member synchronizer traffic status.
///
/// Note the base URL differs from ``TransferRegistryClient``'s: registry
/// endpoints mount at the vhost root (`/registry/...`), scan endpoints under
/// `/api/scan`.
public struct ScanClient: Sendable {
    public let baseURL: URL
    private let session: URLSession

    public init(baseURL: URL, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    /// One ANS (Amulet Name Service) entry — a name bound to a party.
    public struct AnsEntry: Sendable, Equatable {
        public let name: String
        public let party: String
        public let url: String
        public let description: String
    }

    /// The party id of the DSO — the instrument admin for Amulet.
    public func dsoPartyId() async throws -> String {
        guard
            let response = try await get(path: "v0/dso-party-id"),
            let party = response["dso_party_id"] as? String
        else {
            throw ScanError(description: "missing dso_party_id in scan response")
        }
        return party
    }

    /// Resolves an ANS name to its entry, or nil if unregistered.
    public func lookupAnsEntryByName(_ name: String) async throws -> AnsEntry? {
        guard
            let response = try await get(path: "v0/ans-entries/by-name/\(name)"),
            let entry = response["entry"] as? [String: Any]
        else {
            return nil
        }
        return try ansEntry(entry)
    }

    /// An active TransferPreapproval: senders can transfer to `receiver` directly.
    public struct TransferPreapprovalInfo: Sendable, Equatable {
        public let contractId: String
        public let receiver: String?
        public let provider: String?
        public let expiresAt: Date?
    }

    /// The active preapproval for `partyId`, or nil if none — the signal
    /// that transfers to this party settle in one step ("direct") instead of
    /// the two-step offer flow.
    public func transferPreapprovalByParty(_ partyId: String) async throws -> TransferPreapprovalInfo? {
        guard
            let response = try await get(path: "v0/transfer-preapprovals/by-party/\(partyId)"),
            let contract = (response["transfer_preapproval"] as? [String: Any])?["contract"]
                as? [String: Any],
            let contractId = contract["contract_id"] as? String
        else {
            return nil
        }
        let payload = contract["payload"] as? [String: Any]
        let expiresAt = (payload?["expiresAt"] as? String).flatMap(Self.isoDate)
        return TransferPreapprovalInfo(
            contractId: contractId,
            receiver: payload?["receiver"] as? String,
            provider: payload?["provider"] as? String,
            expiresAt: expiresAt
        )
    }

    /// Lists ANS entries, optionally filtered by a name prefix.
    public func listAnsEntries(pageSize: Int = 100, namePrefix: String? = nil) async throws -> [AnsEntry] {
        var query = [URLQueryItem(name: "page_size", value: String(pageSize))]
        if let namePrefix {
            query.append(URLQueryItem(name: "name_prefix", value: namePrefix))
        }
        guard
            let response = try await get(path: "v0/ans-entries", query: query),
            let entries = response["entries"] as? [Any]
        else {
            return []
        }
        return try entries.compactMap { $0 as? [String: Any] }.map { try ansEntry($0) }
    }

    /// Aggregate Amulet totals for one owner party in a scan ACS snapshot.
    public struct HoldingsSummary: Sendable, Equatable {
        public let partyId: String
        /// Sum of unlocked amulet initial amounts (holding fees not deducted),
        /// as the Daml Decimal's canonical string — lossless, convert at the edge.
        public let totalUnlockedCoin: String
        /// Sum of locked amulet initial amounts (holding fees not deducted).
        public let totalLockedCoin: String
        /// `totalUnlockedCoin` + `totalLockedCoin`.
        public let totalCoinHoldings: String
    }

    /// Aggregated holdings answered from one server-side ACS snapshot.
    public struct HoldingsSummaryResult: Sendable, Equatable {
        /// Record time of the snapshot that answered — how stale the totals are.
        public let recordTime: Date
        /// The synchronizer-migration id the snapshot belongs to.
        public let migrationId: Int64
        /// One entry per queried party that held amulet; parties holding nothing are absent.
        public let summaries: [HoldingsSummary]
    }

    /// The network's latest synchronizer-migration id (`/v0/migrations/last`)
    /// — the id scan's ACS snapshots are addressed by. ``holdingsSummary(ownerPartyIds:asOf:migrationId:)``
    /// resolves this automatically; fetch it yourself only to pin the id
    /// across many calls.
    public func latestMigrationId() async throws -> Int64 {
        guard
            let response = try await get(path: "v0/migrations/last"),
            let migrationId = (response["migration_id"] as? NSNumber)?.int64Value
        else {
            throw ScanError(description: "missing migration_id in scan response")
        }
        return migrationId
    }

    /// Server-side aggregated Amulet balances for `ownerPartyIds`
    /// (`/v1/holdings/summary`) — scan folds its ACS snapshot so apps don't
    /// fold the full ACS client-side.
    ///
    /// Snapshot semantics — not real-time: scan persists ACS snapshots on a
    /// fixed cadence (hours apart on typical deployments) and this read
    /// answers from the most recent snapshot at or before `asOf`. Fresh taps
    /// and transfers appear only once the next snapshot lands;
    /// ``HoldingsSummaryResult/recordTime`` says exactly which snapshot
    /// answered. Amounts are amulet initial amounts — holding fees accrued
    /// since creation are not deducted.
    ///
    /// - Parameters:
    ///   - ownerPartyIds: the owners to aggregate; must not be empty. Parties
    ///     that held nothing at the snapshot are absent from the result.
    ///   - asOf: answer from the latest snapshot at or before this instant
    ///     (default: now).
    ///   - migrationId: the synchronizer-migration id whose snapshots to
    ///     read; defaults to the network's latest via ``latestMigrationId()``.
    /// - Returns: the snapshot-backed totals, or nil if scan has no ACS
    ///   snapshot at or before `asOf` for that migration id (e.g. a
    ///   freshly-bootstrapped network that hasn't taken one yet).
    public func holdingsSummary(
        ownerPartyIds: [String],
        asOf: Date? = nil,
        migrationId: Int64? = nil
    ) async throws -> HoldingsSummaryResult? {
        precondition(!ownerPartyIds.isEmpty, "ownerPartyIds must not be empty")
        let resolvedMigrationId: Int64
        if let migrationId {
            resolvedMigrationId = migrationId
        } else {
            resolvedMigrationId = try await latestMigrationId()
        }
        let body: [String: Any] = [
            "migration_id": resolvedMigrationId,
            "record_time": ISO8601DateFormatter().string(from: asOf ?? Date()),
            "record_time_match": "at_or_before",
            "owner_party_ids": ownerPartyIds,
        ]
        guard let response = try await post(path: "v1/holdings/summary", body: body) else {
            return nil
        }
        return try Self.holdingsSummaryResult(response)
    }

    /// The AmuletRules configuration effective at `asOf` (`/v0/amulet-rules`):
    /// the USD transfer-fee schedule, synchronizer traffic pricing, and the
    /// active synchronizer id — the reads that feed ``TransferFeeEstimator``
    /// and ``ValidatorClient/buyTraffic(trafficAmountBytes:receivingValidatorPartyId:synchronizerId:trackingId:expiresAt:)``.
    ///
    /// AmuletRules publishes a `configSchedule` (initial value plus future
    /// values with effective times); this resolves it the way the ledger does
    /// (`Splice.Schedule.getValueAsOf`): the last future value effective at
    /// or before `asOf`, else the initial value. Current networks always
    /// have an empty `futureValues` (CIP-0107 forbids scheduling new ones).
    ///
    /// - Parameter asOf: the instant to resolve the config schedule at
    ///   (default: now).
    public func amuletRulesConfig(asOf: Date? = nil) async throws -> AmuletRulesConfig {
        guard let response = try await post(path: "v0/amulet-rules", body: [:]) else {
            throw ScanError(description: "amulet rules not available (HTTP 404)")
        }
        return try Self.amuletRulesConfig(response, asOf: asOf ?? Date())
    }

    /// The currently open mining rounds (`/v0/open-and-issuing-mining-rounds`),
    /// in ascending round order. Each round carries the USD amulet price
    /// that taps and fee conversions use. Note the list includes rounds
    /// whose ``OpenMiningRound/opensAt`` is still in the future — pick the
    /// round a submission would execute against with
    /// ``Swift/Array/latestUsable(at:)``.
    public func openMiningRounds() async throws -> [OpenMiningRound] {
        let body: [String: Any] = [
            "cached_open_mining_round_contract_ids": [String](),
            "cached_issuing_round_contract_ids": [String](),
        ]
        guard let response = try await post(path: "v0/open-and-issuing-mining-rounds", body: body)
        else {
            throw ScanError(description: "open mining rounds not available (HTTP 404)")
        }
        return try Self.openMiningRounds(response)
    }

    /// The participant hosting `partyId` on `synchronizerId`
    /// (`/v0/domains/{id}/parties/{party}/participant-id`), as a sequencer
    /// member id (`PAR::name::fingerprint`) — the `memberId` that
    /// ``memberTrafficStatus(synchronizerId:memberId:)`` expects. Nil if
    /// scan doesn't know the party.
    public func partyParticipantId(synchronizerId: String, partyId: String) async throws -> String? {
        guard
            let response = try await get(
                path: "v0/domains/\(synchronizerId)/parties/\(partyId)/participant-id"
            )
        else {
            return nil
        }
        return response["participant_id"] as? String
    }

    /// A sequencer member's extra-traffic accounting, all in bytes.
    /// Purchased traffic becomes spendable once the sequencer incorporates
    /// it: ``totalLimitBytes`` catching up to ``totalPurchasedBytes`` means
    /// all purchases are live (either can briefly lead the other, as the two
    /// numbers come from the sequencer and scan's ledger ingestion
    /// respectively).
    public struct MemberTrafficStatus: Sendable, Equatable {
        /// Extra traffic the member has consumed so far.
        public let totalConsumedBytes: Int64
        /// Extra traffic the sequencer currently grants the member.
        public let totalLimitBytes: Int64
        /// Total extra traffic ever purchased for the member.
        public let totalPurchasedBytes: Int64
    }

    /// The extra-traffic status of one sequencer member
    /// (`/v0/domains/{id}/members/{member}/traffic-status`) — the read that
    /// shows a ``ValidatorClient/buyTraffic(trafficAmountBytes:receivingValidatorPartyId:synchronizerId:trackingId:expiresAt:)``
    /// purchase landing.
    ///
    /// - Parameters:
    ///   - synchronizerId: the synchronizer to read traffic for
    ///     (``AmuletRulesConfig/activeSynchronizerId``).
    ///   - memberId: the participant (or mediator) whose traffic to read,
    ///     `PAR::name::fingerprint` — resolve a party's participant with
    ///     ``partyParticipantId(synchronizerId:partyId:)``.
    /// - Returns: the member's traffic totals, or nil if the member is
    ///   unknown to the synchronizer.
    public func memberTrafficStatus(
        synchronizerId: String,
        memberId: String
    ) async throws -> MemberTrafficStatus? {
        guard
            let response = try await get(
                path: "v0/domains/\(synchronizerId)/members/\(memberId)/traffic-status"
            ),
            let status = response["traffic_status"] as? [String: Any]
        else {
            return nil
        }
        return try Self.memberTrafficStatus(status)
    }

    static func amuletRulesConfig(_ json: [String: Any], asOf: Date) throws -> AmuletRulesConfig {
        guard
            let schedule = objectAt(
                json, "amulet_rules_update", "contract", "payload", "configSchedule"
            )
        else {
            throw ScanError(description: "amulet rules response missing configSchedule")
        }
        guard var config = schedule["initialValue"] as? [String: Any] else {
            throw ScanError(description: "amulet rules configSchedule missing initialValue")
        }
        // Splice.Schedule.getValueAsOf: the last future value whose
        // effective time is at or before asOf wins; futureValues are sorted
        // ascending on-ledger.
        for entry in schedule["futureValues"] as? [Any] ?? [] {
            guard
                let future = entry as? [String: Any],
                let effectiveAt = (future["_1"] as? String).flatMap(Self.isoDate)
            else {
                throw ScanError(description: "amulet rules future value missing effective time")
            }
            if effectiveAt > asOf { break }
            guard let futureConfig = future["_2"] as? [String: Any] else {
                throw ScanError(description: "amulet rules future value missing config")
            }
            config = futureConfig
        }

        guard
            let transferConfig = config["transferConfig"] as? [String: Any],
            let transferFee = transferConfig["transferFee"] as? [String: Any]
        else {
            throw ScanError(description: "amulet rules config missing transferConfig")
        }
        guard
            let synchronizer = config["decentralizedSynchronizer"] as? [String: Any],
            let fees = synchronizer["fees"] as? [String: Any],
            let limits = fees["baseRateTrafficLimits"] as? [String: Any]
        else {
            throw ScanError(description: "amulet rules config missing decentralizedSynchronizer.fees")
        }
        guard let activeSynchronizerId = synchronizer["activeSynchronizer"] as? String else {
            throw ScanError(
                description: "amulet rules decentralizedSynchronizer missing activeSynchronizer"
            )
        }
        let steps = try (transferFee["steps"] as? [Any] ?? []).map { step -> SteppedRate.Step in
            guard let step = step as? [String: Any] else {
                throw ScanError(description: "amulet rules transfer fee step is not an object")
            }
            return SteppedRate.Step(
                boundary: try decimal(step, "_1"),
                rate: try decimal(step, "_2")
            )
        }
        return AmuletRulesConfig(
            transferFees: TransferFeeSchedule(
                createFeeUsd: try decimal(transferConfig["createFee"] as? [String: Any], "fee"),
                transferFee: SteppedRate(
                    initialRate: try decimal(transferFee, "initialRate"),
                    steps: steps
                ),
                holdingFeeUsdPerRound: try decimal(
                    transferConfig["holdingFee"] as? [String: Any], "rate"
                ),
                lockHolderFeeUsd: try decimal(
                    transferConfig["lockHolderFee"] as? [String: Any], "fee"
                )
            ),
            synchronizerFees: SynchronizerFeeConfig(
                extraTrafficPriceUsdPerMB: try decimal(fees, "extraTrafficPrice"),
                minTopupAmountBytes: try int64(fees, "minTopupAmount"),
                baseRateBurstAmountBytes: try int64(limits, "burstAmount"),
                baseRateBurstWindow: .microseconds(
                    try int64(limits["burstWindow"] as? [String: Any], "microseconds")
                ),
                readVsWriteScalingFactor: try int64(fees, "readVsWriteScalingFactor")
            ),
            activeSynchronizerId: activeSynchronizerId
        )
    }

    static func openMiningRounds(_ json: [String: Any]) throws -> [OpenMiningRound] {
        let rounds = json["open_mining_rounds"] as? [String: Any] ?? [:]
        return try rounds.values
            .map { round -> OpenMiningRound in
                guard
                    let payload = objectAt(round as? [String: Any] ?? [:], "contract", "payload")
                else {
                    throw ScanError(description: "open mining round missing contract payload")
                }
                guard
                    let opensAt = (payload["opensAt"] as? String).flatMap(Self.isoDate),
                    let targetClosesAt = (payload["targetClosesAt"] as? String).flatMap(Self.isoDate)
                else {
                    throw ScanError(description: "open mining round missing opensAt/targetClosesAt")
                }
                return OpenMiningRound(
                    roundNumber: try int64(payload["round"] as? [String: Any], "number"),
                    amuletPriceUsd: try decimal(payload, "amuletPrice"),
                    opensAt: opensAt,
                    targetClosesAt: targetClosesAt
                )
            }
            .sorted { $0.roundNumber < $1.roundNumber }
    }

    static func memberTrafficStatus(_ status: [String: Any]) throws -> MemberTrafficStatus {
        guard
            let actual = status["actual"] as? [String: Any],
            let target = status["target"] as? [String: Any]
        else {
            throw ScanError(description: "traffic status missing actual/target")
        }
        return MemberTrafficStatus(
            totalConsumedBytes: try int64(actual, "total_consumed"),
            totalLimitBytes: try int64(actual, "total_limit"),
            totalPurchasedBytes: try int64(target, "total_purchased")
        )
    }

    private static func objectAt(_ json: [String: Any], _ path: String...) -> [String: Any]? {
        var current = json
        for key in path {
            guard let next = current[key] as? [String: Any] else { return nil }
            current = next
        }
        return current
    }

    private static func decimal(_ json: [String: Any]?, _ key: String) throws -> Decimal {
        guard
            let text = json?[key] as? String,
            let value = Decimal(string: text, locale: Locale(identifier: "en_US_POSIX"))
        else {
            throw ScanError(description: "expected a decimal at \(key)")
        }
        return value
    }

    private static func int64(_ json: [String: Any]?, _ key: String) throws -> Int64 {
        // Daml numbers arrive as strings in contract payloads and as JSON
        // numbers in scan's own response fields — accept both.
        if let number = json?[key] as? NSNumber {
            return number.int64Value
        }
        if let text = json?[key] as? String, let value = Int64(text) {
            return value
        }
        throw ScanError(description: "expected an integer at \(key)")
    }

    static func holdingsSummaryResult(_ json: [String: Any]) throws -> HoldingsSummaryResult {
        guard
            let recordTime = (json["record_time"] as? String).flatMap(Self.isoDate)
        else {
            throw ScanError(description: "holdings summary missing record_time")
        }
        guard let migrationId = (json["migration_id"] as? NSNumber)?.int64Value else {
            throw ScanError(description: "holdings summary missing migration_id")
        }
        let summaries = try (json["summaries"] as? [Any] ?? [])
            .compactMap { $0 as? [String: Any] }
            .map { summary -> HoldingsSummary in
                func amount(_ key: String) throws -> String {
                    guard
                        let text = summary[key] as? String,
                        Decimal(string: text, locale: Locale(identifier: "en_US_POSIX")) != nil
                    else {
                        throw ScanError(description: "holdings summary missing \(key)")
                    }
                    return text
                }
                guard let partyId = summary["party_id"] as? String else {
                    throw ScanError(description: "holdings summary missing party_id")
                }
                return HoldingsSummary(
                    partyId: partyId,
                    totalUnlockedCoin: try amount("total_unlocked_coin"),
                    totalLockedCoin: try amount("total_locked_coin"),
                    totalCoinHoldings: try amount("total_coin_holdings")
                )
            }
        return HoldingsSummaryResult(
            recordTime: recordTime,
            migrationId: migrationId,
            summaries: summaries
        )
    }

    private static func isoDate(_ iso: String) -> Date? {
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return fractional.date(from: iso) ?? ISO8601DateFormatter().date(from: iso)
    }

    private func ansEntry(_ json: [String: Any]) throws -> AnsEntry {
        guard let name = json["name"] as? String, let party = json["user"] as? String else {
            throw ScanError(description: "ANS entry missing name/user")
        }
        return AnsEntry(
            name: name,
            party: party,
            url: json["url"] as? String ?? "",
            description: json["description"] as? String ?? ""
        )
    }

    /// GET returning the parsed body, or nil on 404.
    private func get(path: String, query: [URLQueryItem] = []) async throws -> [String: Any]? {
        var components = URLComponents(
            url: baseURL.appendingPathComponent(path),
            resolvingAgainstBaseURL: false
        )!
        if !query.isEmpty {
            components.queryItems = query
        }
        let (data, response) = try await session.data(from: components.url!)
        return try Self.parsedBody(data, response, path: path)
    }

    /// POST returning the parsed body, or nil on 404.
    private func post(path: String, body: [String: Any]) async throws -> [String: Any]? {
        var request = URLRequest(url: baseURL.appendingPathComponent(path))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, response) = try await session.data(for: request)
        return try Self.parsedBody(data, response, path: path)
    }

    private static func parsedBody(
        _ data: Data, _ response: URLResponse, path: String
    ) throws -> [String: Any]? {
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 404 {
            return nil
        }
        guard (200..<300).contains(status) else {
            let text = String(data: data, encoding: .utf8)?.prefix(300) ?? ""
            throw ScanError(description: "HTTP \(status) from \(path): \(text)")
        }
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ScanError(description: "scan response from \(path) is not a JSON object")
        }
        return object
    }
}
