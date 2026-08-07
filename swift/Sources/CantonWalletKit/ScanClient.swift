import Foundation

/// Read layer over the Scan API (Splice) — WP3 skeleton.
///
/// Balances, parsed transaction history (semantics matched to the official
/// TS SDK via golden vectors generated from its output), ANS name lookup,
/// amulet rules and open mining rounds. HTTP, not gRPC — shares the HTTP
/// client layer introduced by WP2.
public struct ScanClient: Sendable {
    public struct AnsEntry: Sendable {
        public let name: String
        public let partyId: String
    }

    public let baseURL: URL

    public init(baseURL: URL) {
        self.baseURL = baseURL
    }

    public func balance(partyId: String, instrumentId: String) async throws -> Decimal {
        fatalError("WP3: scan balance endpoint")
    }

    public func transactionHistory(partyId: String, pageSize: Int = 50) async throws -> [String] {
        fatalError("WP3: parsed tx history, golden-vector matched against the TS SDK")
    }

    public func lookupAnsName(_ name: String) async throws -> AnsEntry? {
        fatalError("WP3: ANS lookup for name-based sending")
    }
}
