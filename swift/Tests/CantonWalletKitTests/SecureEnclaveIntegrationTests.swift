// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonKit
import CryptoKit
import Foundation
import Testing

// Plain import (not @testable): this file is shared with the app-hosted
// device test bundle (examples/ios), which links the built product.
import CantonWalletKit

/// Hardware-gated: runs wherever a Secure Enclave exists — physical iPhones
/// and iPads, and Apple Silicon Macs. Skipped elsewhere (simulators have no
/// enclave).
///
/// This is the proof behind the "enclave-held keys" claim: the private key
/// is generated inside the Secure Enclave, never leaves it, and every
/// signature below is produced by the enclave. The optional live test
/// (CANTON_LEDGER_HOST/PORT set, e.g. via TEST_RUNNER_-prefixed env vars
/// from xcodebuild) allocates a Canton external party with the
/// enclave-resident key — self-custody on hardware, end to end.
struct SecureEnclaveIntegrationTests {
    private static var enclaveAvailable: Bool {
        SecureEnclaveSigningDriver.isAvailable
    }

    private static var ledgerHost: String? {
        ProcessInfo.processInfo.environment["CANTON_LEDGER_HOST"]
    }

    @Test(.enabled(if: enclaveAvailable, "no Secure Enclave on this host"))
    func enclaveKeySignsAndVerifies() async throws {
        let driver = try SecureEnclaveSigningDriver()
        let hash = Data(SHA256.hash(data: Data("canton enclave multi-hash".utf8)))

        let publicKey = try await driver.publicKey()
        let signature = try await driver.sign(hash)

        #expect(publicKey.keySpec == .ecP256)
        #expect(publicKey.format == .derX509SubjectPublicKeyInfo)
        #expect(signature.format == .der)
        #expect(signature.signingAlgorithmSpec == .ecDsaSha256)

        let verifier = try P256.Signing.PublicKey(derRepresentation: publicKey.keyData)
        let ecdsa = try P256.Signing.ECDSASignature(derRepresentation: signature.signature)
        #expect(verifier.isValidSignature(ecdsa, for: hash))
    }

    @Test(.enabled(if: enclaveAvailable, "no Secure Enclave on this host"))
    func enclaveKeyRoundTripsThroughDataRepresentation() async throws {
        let original = try SecureEnclaveSigningDriver()
        let restored = try SecureEnclaveSigningDriver(
            dataRepresentation: original.dataRepresentation
        )

        let originalKey = try await original.publicKey()
        let restoredKey = try await restored.publicKey()
        #expect(originalKey.keyData == restoredKey.keyData)

        // The restored handle still signs — this is what "persist alongside
        // the party id and reuse across launches" relies on.
        let hash = Data(SHA256.hash(data: Data("restored".utf8)))
        let signature = try await restored.sign(hash)
        let verifier = try P256.Signing.PublicKey(derRepresentation: originalKey.keyData)
        let ecdsa = try P256.Signing.ECDSASignature(derRepresentation: signature.signature)
        #expect(verifier.isValidSignature(ecdsa, for: hash))
    }

    @Test(.enabled(
        if: enclaveAvailable && ledgerHost != nil,
        "needs a Secure Enclave and CANTON_LEDGER_HOST"
    ))
    func enclavePartyOnLiveCanton() async throws {
        let client = CantonClient(
            configuration: .init(
                host: Self.ledgerHost!,
                port: ProcessInfo.processInfo.environment["CANTON_LEDGER_PORT"]
                    .flatMap(Int.init) ?? 5011,
                useTLS: false
            )
        )

        let driver = try SecureEnclaveSigningDriver()
        let parties = ExternalPartyClient(client: client)
        let synchronizer = try await parties.connectedSynchronizers().first!
        let party = try await parties.allocate(
            driver: driver,
            synchronizerId: synchronizer,
            partyHint: "enclave",
            userId: "participant_admin"
        )
        #expect(party.partyId.hasPrefix("enclave::"))
        print("enclave-resident key allocated external party: \(party.partyId)")
    }
}
