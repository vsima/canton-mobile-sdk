// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonLedgerAPI
import Foundation

/// The custody hook: a ``SigningDriver`` whose key operations are delegated
/// to caller-supplied async closures. Wrap any external signer — Fireblocks,
/// BitGo, Dfns, an HSM, a remote co-signer — by adapting its SDK calls to
/// the two closures; everything above the driver (party onboarding,
/// interactive submission, token-standard writes) works unchanged.
///
/// The closures own the encodings: `publicKeyProvider` must return a
/// Ledger-API-compatible key and `signer` a signature whose format and
/// algorithm match it. For P-256 custody backends that emit raw `r‖s`
/// signatures, convert to DER before returning.
public struct DelegatingSigningDriver: SigningDriver {
    private let publicKeyProvider: @Sendable () async throws -> Com_Daml_Ledger_Api_V2_SigningPublicKey
    private let signer: @Sendable (Data) async throws -> Com_Daml_Ledger_Api_V2_Signature

    public init(
        publicKeyProvider: @escaping @Sendable () async throws -> Com_Daml_Ledger_Api_V2_SigningPublicKey,
        signer: @escaping @Sendable (Data) async throws -> Com_Daml_Ledger_Api_V2_Signature
    ) {
        self.publicKeyProvider = publicKeyProvider
        self.signer = signer
    }

    public func publicKey() async throws -> Com_Daml_Ledger_Api_V2_SigningPublicKey {
        try await publicKeyProvider()
    }

    public func sign(_ bytes: Data) async throws -> Com_Daml_Ledger_Api_V2_Signature {
        try await signer(bytes)
    }
}
