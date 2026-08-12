// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonDappKit
import CryptoKit
import Foundation
import Testing

@testable import CantonDappWalletKit
@testable import CantonWalletKit

/// The R4 reality check (Swift mirror): a `signMessage` signature must be
/// *verifiable by a dApp*, and over the domain-separated bytes rather than the
/// raw message.
///
/// It signs through the public `SigningDriverMessageSigner`, then verifies with
/// CryptoKit exactly as an independent dApp would. That it validates proves the
/// scheme is usable; that the *raw* message does **not** validate proves the
/// domain separation is real rather than decorative.
@Suite struct SigningDriverMessageSignerTests {

    private let account = DappWallet(
        primary: true,
        partyId: "alice::1220aa",
        status: .allocated,
        hint: "alice",
        publicKey: "",
        namespace: "1220aa",
        networkId: "canton:localnet",
        signingProviderId: "software"
    )

    private let message = "Sign in to Example dApp\nnonce: 7f3a9c2e"

    /// RFC 8410 SubjectPublicKeyInfo prefix the driver puts before an Ed25519
    /// raw key; stripped here to recover what CryptoKit's verifier wants.
    private let ed25519SPKIPrefix = 12

    @Test func anEd25519SignMessageSignatureVerifiesOverTheDomainSeparatedBytes() async throws {
        let driver = SoftwareSigningDriver.generate(.ed25519)
        let signer = SigningDriverMessageSigner(signer: driver)

        let signature = try #require(Data(base64Encoded: try await signer.sign(account: account, message: message)))
        let spki = try await driver.publicKey().keyData
        let publicKey = try Curve25519.Signing.PublicKey(rawRepresentation: spki.dropFirst(ed25519SPKIPrefix))

        // A dApp applying the same transform validates the signature.
        #expect(publicKey.isValidSignature(signature, for: DappSignMessage.signingBytes(message)))
        // The wallet did not sign the raw message — domain separation is real.
        #expect(!publicKey.isValidSignature(signature, for: Data(message.utf8)))
    }

    @Test func aP256SignMessageSignatureVerifiesOverTheDomainSeparatedBytes() async throws {
        let driver = SoftwareSigningDriver.generate(.ecP256)
        let signer = SigningDriverMessageSigner(signer: driver)

        let signatureDER = try #require(Data(base64Encoded: try await signer.sign(account: account, message: message)))
        let signature = try P256.Signing.ECDSASignature(derRepresentation: signatureDER)
        let spki = try await driver.publicKey().keyData
        let publicKey = try P256.Signing.PublicKey(derRepresentation: spki)

        // CryptoKit's P256 verifier hashes with SHA-256 internally, matching
        // the driver's SHA256withECDSA.
        #expect(publicKey.isValidSignature(signature, for: DappSignMessage.signingBytes(message)))
        #expect(!publicKey.isValidSignature(signature, for: Data(message.utf8)))
    }
}
