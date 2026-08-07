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
/// party and ANS name resolution (name-based sending). Not yet covered —
/// holdings summaries (require server-side ACS snapshots), amulet rules and
/// mining rounds (arrive with traffic-purchase support).
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
        let expiresAt = (payload?["expiresAt"] as? String).flatMap { iso -> Date? in
            let fractional = ISO8601DateFormatter()
            fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            return fractional.date(from: iso) ?? ISO8601DateFormatter().date(from: iso)
        }
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
