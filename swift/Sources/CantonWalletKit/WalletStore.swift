// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// One onboarded wallet identity: the party, the fingerprint of its
/// registered key, and an opaque handle for reconstructing the signer.
///
/// `keyHandle` is whatever the driver needs to come back to life — a Secure
/// Enclave `dataRepresentation`, a custody provider's key id, a keychain
/// reference. It is opaque to the SDK and MUST NOT contain raw private key
/// material for hardware/custody drivers (their handles are references, not
/// keys).
public struct WalletRecord: Sendable, Equatable, Codable {
    public let partyId: String
    public let publicKeyFingerprint: String
    public let synchronizerId: String
    public let keyHandle: Data?
    public let createdAt: Date

    public init(
        partyId: String,
        publicKeyFingerprint: String,
        synchronizerId: String,
        keyHandle: Data?,
        createdAt: Date
    ) {
        self.partyId = partyId
        self.publicKeyFingerprint = publicKeyFingerprint
        self.synchronizerId = synchronizerId
        self.keyHandle = keyHandle
        self.createdAt = createdAt
    }
}

/// Persistence for wallet identities across launches. The SDK ships
/// ``InMemoryWalletStore`` (tests, previews) and ``KeychainWalletStore``
/// (durable, device-bound — the right home for enclave key handles); the
/// surface is deliberately small so custom backends are a page of code.
public protocol WalletStore: Sendable {
    func save(_ record: WalletRecord) async throws

    /// All records, oldest first.
    func list() async throws -> [WalletRecord]

    func find(partyId: String) async throws -> WalletRecord?

    func delete(partyId: String) async throws
}

/// Non-durable ``WalletStore``; suitable for tests and previews.
public actor InMemoryWalletStore: WalletStore {
    private var records: [String: WalletRecord] = [:]
    private var order: [String] = []

    public init() {}

    public func save(_ record: WalletRecord) {
        if records[record.partyId] == nil {
            order.append(record.partyId)
        }
        records[record.partyId] = record
    }

    public func list() -> [WalletRecord] {
        order.compactMap { records[$0] }
    }

    public func find(partyId: String) -> WalletRecord? {
        records[partyId]
    }

    public func delete(partyId: String) {
        records[partyId] = nil
        order.removeAll { $0 == partyId }
    }
}

#if canImport(Security)
import Security

/// Keychain-backed ``WalletStore``: records live as generic-password items
/// under a service namespace, protected `afterFirstUnlockThisDeviceOnly` —
/// device-bound like the enclave handles it typically stores, and available
/// to background refresh once the device has been unlocked.
public struct KeychainWalletStore: WalletStore {
    public let service: String

    public init(service: String = "io.github.vsima.canton.wallet") {
        self.service = service
    }

    public struct KeychainError: Error, CustomStringConvertible {
        public let description: String
    }

    public func save(_ record: WalletRecord) async throws {
        let payload = try JSONEncoder().encode(record)
        var query = baseQuery(account: record.partyId)
        let update: [String: Any] = [kSecValueData as String: payload]
        let status = SecItemUpdate(query as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound {
            query[kSecValueData as String] = payload
            query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            try check(SecItemAdd(query as CFDictionary, nil), "add")
        } else {
            try check(status, "update")
        }
    }

    public func list() async throws -> [WalletRecord] {
        var query = baseQuery(account: nil)
        query[kSecMatchLimit as String] = kSecMatchLimitAll
        query[kSecReturnData as String] = true
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return [] }
        try check(status, "list")
        let items = (result as? [Data]) ?? (result as? Data).map { [$0] } ?? []
        return try items
            .map { try JSONDecoder().decode(WalletRecord.self, from: $0) }
            .sorted { $0.createdAt < $1.createdAt }
    }

    public func find(partyId: String) async throws -> WalletRecord? {
        var query = baseQuery(account: partyId)
        query[kSecReturnData as String] = true
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        try check(status, "find")
        guard let data = result as? Data else { return nil }
        return try JSONDecoder().decode(WalletRecord.self, from: data)
    }

    public func delete(partyId: String) async throws {
        let status = SecItemDelete(baseQuery(account: partyId) as CFDictionary)
        if status != errSecItemNotFound {
            try check(status, "delete")
        }
    }

    private func baseQuery(account: String?) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
        ]
        if let account {
            query[kSecAttrAccount as String] = account
        }
        return query
    }

    private func check(_ status: OSStatus, _ operation: String) throws {
        guard status == errSecSuccess else {
            throw KeychainError(description: "keychain \(operation) failed: OSStatus \(status)")
        }
    }
}
#endif
