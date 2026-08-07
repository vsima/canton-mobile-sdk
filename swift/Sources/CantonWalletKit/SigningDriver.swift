// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonLedgerAPI
import CryptoKit
import Foundation

/// Produces Canton signatures over ledger-provided hashes.
///
/// Implementations decide where the private key lives: in software, in the
/// Secure Enclave, or with a custody provider. The driver never sees a full
/// transaction — only the hash the ledger asks the party to sign, which is
/// what makes hardware-backed and custody-held keys possible.
///
/// The returned signature carries no `signedBy` fingerprint; callers that
/// know the canonical fingerprint (returned by
/// `GenerateExternalPartyTopology`) fill it in.
public protocol SigningDriver: Sendable {
    /// The public key in a Ledger-API-compatible encoding.
    func publicKey() async throws -> Com_Daml_Ledger_Api_V2_SigningPublicKey

    /// Signs `bytes` (a Canton-provided hash) with the driver's private key.
    func sign(_ bytes: Data) async throws -> Com_Daml_Ledger_Api_V2_Signature
}

/// RFC 8410 SubjectPublicKeyInfo prefix for an Ed25519 public key; CryptoKit
/// only exposes the 32-byte raw form.
private let ed25519SPKIPrefix = Data([
    0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
])

/// CryptoKit-backed software keys, supporting the two schemes relevant to
/// mobile wallets: Ed25519 (Canton's default) and ECDSA P-256 (the only
/// scheme the Secure Enclave signs). Both are accepted by the Ledger API;
/// verified live in the Kotlin `ExternalPartyIntegrationTest`.
public struct SoftwareSigningDriver: SigningDriver {
    public enum Algorithm: Sendable {
        case ed25519
        case ecP256
    }

    private enum Key: Sendable {
        case ed25519(Curve25519.Signing.PrivateKey)
        case ecP256(P256.Signing.PrivateKey)
    }

    private let key: Key

    public var algorithm: Algorithm {
        switch key {
        case .ed25519: .ed25519
        case .ecP256: .ecP256
        }
    }

    public static func generate(_ algorithm: Algorithm) -> SoftwareSigningDriver {
        switch algorithm {
        case .ed25519: SoftwareSigningDriver(key: .ed25519(Curve25519.Signing.PrivateKey()))
        case .ecP256: SoftwareSigningDriver(key: .ecP256(P256.Signing.PrivateKey()))
        }
    }

    public func publicKey() async throws -> Com_Daml_Ledger_Api_V2_SigningPublicKey {
        var proto = Com_Daml_Ledger_Api_V2_SigningPublicKey()
        proto.format = .derX509SubjectPublicKeyInfo
        switch key {
        case .ed25519(let key):
            proto.keyData = ed25519SPKIPrefix + key.publicKey.rawRepresentation
            proto.keySpec = .ecCurve25519
        case .ecP256(let key):
            proto.keyData = key.publicKey.derRepresentation
            proto.keySpec = .ecP256
        }
        return proto
    }

    public func sign(_ bytes: Data) async throws -> Com_Daml_Ledger_Api_V2_Signature {
        var proto = Com_Daml_Ledger_Api_V2_Signature()
        switch key {
        case .ed25519(let key):
            proto.signature = try key.signature(for: bytes)
            proto.format = .concat
            proto.signingAlgorithmSpec = .ed25519
        case .ecP256(let key):
            proto.signature = try key.signature(for: bytes).derRepresentation
            proto.format = .der
            proto.signingAlgorithmSpec = .ecDsaSha256
        }
        return proto
    }
}

#if canImport(LocalAuthentication)
/// P-256 keys generated inside the Secure Enclave: the private key is
/// non-exportable and every signature is produced by the enclave.
///
/// The Ledger API accepts P-256 external parties (verified against a live
/// participant), so this is genuine on-device self-custody. Requires real
/// hardware — unavailable in the simulator; check ``isAvailable`` first.
public struct SecureEnclaveSigningDriver: SigningDriver {
    private let key: SecureEnclave.P256.Signing.PrivateKey

    public static var isAvailable: Bool { SecureEnclave.isAvailable }

    /// Generates a fresh enclave-resident key. Pass access control flags to
    /// require biometry per signature (e.g. `.biometryCurrentSet`).
    public init(accessControl: SecAccessControlCreateFlags = [.privateKeyUsage]) throws {
        var error: Unmanaged<CFError>?
        guard
            let control = SecAccessControlCreateWithFlags(
                nil,
                kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
                accessControl,
                &error
            )
        else {
            throw error!.takeRetainedValue() as Error
        }
        self.key = try SecureEnclave.P256.Signing.PrivateKey(accessControl: control)
    }

    /// Reconstructs the driver from a previously stored data representation
    /// (an opaque, enclave-bound handle — not key material).
    public init(dataRepresentation: Data) throws {
        self.key = try SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: dataRepresentation)
    }

    /// Persist this alongside the party id to reuse the key across launches.
    public var dataRepresentation: Data { key.dataRepresentation }

    public func publicKey() async throws -> Com_Daml_Ledger_Api_V2_SigningPublicKey {
        var proto = Com_Daml_Ledger_Api_V2_SigningPublicKey()
        proto.format = .derX509SubjectPublicKeyInfo
        proto.keyData = key.publicKey.derRepresentation
        proto.keySpec = .ecP256
        return proto
    }

    public func sign(_ bytes: Data) async throws -> Com_Daml_Ledger_Api_V2_Signature {
        var proto = Com_Daml_Ledger_Api_V2_Signature()
        proto.signature = try key.signature(for: bytes).derRepresentation
        proto.format = .der
        proto.signingAlgorithmSpec = .ecDsaSha256
        return proto
    }
}
#endif
