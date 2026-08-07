import CryptoKit
import Foundation
import Testing

@testable import CantonWalletKit

struct SigningDriverTests {
    /// The signature a driver emits must verify against the public key it
    /// advertises, in the exact encodings the Ledger API expects.
    @Test func ed25519SignatureRoundTrips() async throws {
        let driver = SoftwareSigningDriver.generate(.ed25519)
        let hash = Data(SHA256.hash(data: Data("canton multi-hash".utf8)))

        let publicKey = try await driver.publicKey()
        let signature = try await driver.sign(hash)

        #expect(publicKey.keySpec == .ecCurve25519)
        #expect(publicKey.format == .derX509SubjectPublicKeyInfo)
        #expect(signature.format == .concat)
        #expect(signature.signingAlgorithmSpec == .ed25519)

        // SPKI = 12-byte RFC 8410 prefix + 32-byte raw key.
        #expect(publicKey.keyData.count == 44)
        let raw = publicKey.keyData.suffix(32)
        let verifier = try Curve25519.Signing.PublicKey(rawRepresentation: raw)
        #expect(verifier.isValidSignature(signature.signature, for: hash))
    }

    @Test func p256SignatureRoundTrips() async throws {
        let driver = SoftwareSigningDriver.generate(.ecP256)
        let hash = Data(SHA256.hash(data: Data("canton multi-hash".utf8)))

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
}
