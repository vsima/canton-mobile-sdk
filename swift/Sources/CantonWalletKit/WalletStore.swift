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
    /// The allocated party id, `hint::fingerprint`.
    public let partyId: String
    /// Canonical fingerprint of the registered signing key; goes into every
    /// signature's `signedBy`.
    public let publicKeyFingerprint: String
    /// The synchronizer the party was allocated on.
    public let synchronizerId: String
    /// Opaque driver handle for reviving the signer; nil if the driver needs
    /// nothing stored.
    public let keyHandle: Data?
    /// When the record was created; ``WalletStore/list()`` orders by it.
    public let createdAt: Date

    /// Creates a record.
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
    /// Inserts, or replaces the record with the same party id.
    func save(_ record: WalletRecord) async throws

    /// All records, oldest first.
    func list() async throws -> [WalletRecord]

    /// The record for `partyId`, or nil.
    func find(partyId: String) async throws -> WalletRecord?

    /// Removes the record for `partyId`; a missing record is not an error.
    func delete(partyId: String) async throws
}

/// Non-durable ``WalletStore``; suitable for tests and previews.
public actor InMemoryWalletStore: WalletStore {
    private var records: [String: WalletRecord] = [:]
    private var order: [String] = []

    /// An empty store.
    public init() {}

    /// Inserts or replaces by party id; first-insertion order is kept for
    /// ``list()``.
    public func save(_ record: WalletRecord) {
        if records[record.partyId] == nil {
            order.append(record.partyId)
        }
        records[record.partyId] = record
    }

    /// Records in insertion order.
    public func list() -> [WalletRecord] {
        order.compactMap { records[$0] }
    }

    /// The record for `partyId`, or nil.
    public func find(partyId: String) -> WalletRecord? {
        records[partyId]
    }

    /// Removes the record, if present.
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
    /// The keychain service attribute records are filed under — one
    /// namespace per app.
    public let service: String

    /// Creates a store under `service`.
    public init(service: String = "io.github.vsima.canton.wallet") {
        self.service = service
    }

    /// A Security framework call failed; carries the operation and `OSStatus`.
    public struct KeychainError: Error, CustomStringConvertible {
        /// Which operation failed, and the `OSStatus`.
        public let description: String
    }

    /// Updates the item for the party, or adds it with
    /// `afterFirstUnlockThisDeviceOnly` protection.
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

    /// All records under ``service``, oldest ``WalletRecord/createdAt``
    /// first.
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

    /// The record for `partyId`, or nil when the keychain has none.
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

    /// Removes the item; a missing item is not an error.
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
