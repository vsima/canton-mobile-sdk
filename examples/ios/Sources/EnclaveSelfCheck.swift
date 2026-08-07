// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonKit
import CantonWalletKit
import CryptoKit
import Foundation

/// On-device verification of the Secure Enclave signing path — the hardware
/// half of the "enclave-held keys" claim. Runs where a Secure Enclave exists
/// (physical devices; skipped in the simulator) and prints machine-greppable
/// `ENCLAVE-CHECK:` lines to the console, so it can be driven headlessly:
///
///   xcrun devicectl device process launch --console --device <udid> \
///     io.github.vsima.canton.sample
///
/// With `DEVICECTL_CHILD_CANTON_HOST`/`_PORT` set in the calling
/// environment, it also allocates a Canton external party with the
/// enclave-resident key — self-custody on hardware, end to end.
enum EnclaveSelfCheck {
    static func run() async -> String {
        var lines: [String] = []
        func report(_ line: String) {
            print("ENCLAVE-CHECK: \(line)")
            lines.append(line)
        }

        guard SecureEnclaveSigningDriver.isAvailable else {
            report("SKIP no Secure Enclave on this device")
            return lines.joined(separator: "\n")
        }

        do {
            // 1. Enclave key generates and signs; signature verifies against
            // the advertised public key in Canton's exact encodings.
            let driver = try SecureEnclaveSigningDriver()
            let hash = Data(SHA256.hash(data: Data("canton enclave multi-hash".utf8)))
            let publicKey = try await driver.publicKey()
            let signature = try await driver.sign(hash)

            guard
                publicKey.keySpec == .ecP256,
                publicKey.format == .derX509SubjectPublicKeyInfo,
                signature.format == .der,
                signature.signingAlgorithmSpec == .ecDsaSha256
            else {
                report("FAIL wrong encodings on enclave signature")
                return lines.joined(separator: "\n")
            }
            let verifier = try P256.Signing.PublicKey(derRepresentation: publicKey.keyData)
            let ecdsa = try P256.Signing.ECDSASignature(derRepresentation: signature.signature)
            guard verifier.isValidSignature(ecdsa, for: hash) else {
                report("FAIL enclave signature did not verify")
                return lines.joined(separator: "\n")
            }
            report("PASS enclave key signs; P-256/DER/ECDSA-SHA256 verified")

            // 2. Persistence round trip: the stored handle restores and
            // still signs — what per-launch key reuse relies on.
            let restored = try SecureEnclaveSigningDriver(
                dataRepresentation: driver.dataRepresentation
            )
            let restoredKey = try await restored.publicKey()
            let restoredSig = try await restored.sign(hash)
            let restoredECDSA = try P256.Signing.ECDSASignature(
                derRepresentation: restoredSig.signature
            )
            guard
                restoredKey.keyData == publicKey.keyData,
                verifier.isValidSignature(restoredECDSA, for: hash)
            else {
                report("FAIL restored enclave handle mismatch")
                return lines.joined(separator: "\n")
            }
            report("PASS enclave handle round-trips through dataRepresentation")

            // 3. Optional live leg: allocate a Canton external party with the
            // enclave-resident key.
            let environment = ProcessInfo.processInfo.environment
            guard let host = environment["CANTON_HOST"] else {
                report("SKIP live leg (set DEVICECTL_CHILD_CANTON_HOST to enable)")
                return lines.joined(separator: "\n")
            }
            let client = CantonClient(
                configuration: .init(
                    host: host,
                    port: environment["CANTON_PORT"].flatMap(Int.init) ?? 5011,
                    useTLS: false
                )
            )
            let parties = ExternalPartyClient(client: client)
            guard let synchronizer = try await parties.connectedSynchronizers().first else {
                report("FAIL no synchronizer at \(host)")
                return lines.joined(separator: "\n")
            }
            let party = try await parties.allocate(
                driver: driver,
                synchronizerId: synchronizer,
                partyHint: "enclave",
                userId: "participant_admin"
            )
            report("PASS enclave-resident key allocated external party \(party.partyId)")
        } catch {
            report("FAIL \(error)")
        }
        return lines.joined(separator: "\n")
    }
}
