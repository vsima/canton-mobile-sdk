// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CryptoKit
import Foundation
import Testing

@testable import CantonWalletKit

struct WalletStoreAndDelegatingDriverTests {

    /// The custody hook composes: a delegating driver wrapping any backend
    /// produces signatures the wrapped key verifies.
    @Test func delegatingDriverRoundTripsSignaturesThroughItsClosures() async throws {
        let backend = SoftwareSigningDriver.generate(.ecP256)
        let driver = DelegatingSigningDriver(
            publicKeyProvider: { try await backend.publicKey() },
            signer: { try await backend.sign($0) }
        )

        let hash = Data((0..<32).map(UInt8.init))
        let publicKey = try await driver.publicKey()
        let signature = try await driver.sign(hash)

        let verifier = try P256.Signing.PublicKey(derRepresentation: publicKey.keyData)
        let ecdsa = try P256.Signing.ECDSASignature(derRepresentation: signature.signature)
        #expect(verifier.isValidSignature(ecdsa, for: hash))
    }

    @Test func inMemoryStoreSavesListsInOrderFindsAndDeletes() async throws {
        let store = InMemoryWalletStore()
        let first = WalletRecord(
            partyId: "alice::1220aa", publicKeyFingerprint: "1220ff",
            synchronizerId: "sync::1", keyHandle: Data([1, 2]), createdAt: Date(timeIntervalSince1970: 0)
        )
        let second = WalletRecord(
            partyId: "bob::1220bb", publicKeyFingerprint: "1220ee",
            synchronizerId: "sync::1", keyHandle: nil, createdAt: Date(timeIntervalSince1970: 1)
        )

        try await store.save(first)
        try await store.save(second)
        #expect(try await store.list() == [first, second])
        #expect(try await store.find(partyId: "alice::1220aa") == first)

        // Saving the same party replaces its record, keeping its position.
        let renewed = WalletRecord(
            partyId: first.partyId, publicKeyFingerprint: "1220dd",
            synchronizerId: first.synchronizerId, keyHandle: first.keyHandle, createdAt: first.createdAt
        )
        try await store.save(renewed)
        #expect(try await store.find(partyId: "alice::1220aa") == renewed)
        #expect(try await store.list() == [renewed, second])

        try await store.delete(partyId: "alice::1220aa")
        #expect(try await store.find(partyId: "alice::1220aa") == nil)
        #expect(try await store.list() == [second])
    }
}
