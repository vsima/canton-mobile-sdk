// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonKit
import CantonLedgerAPI
import Foundation

/// An external party whose signing key never left the ``SigningDriver``.
public struct AllocatedExternalParty: Sendable {
    /// The allocated party id, `hint::fingerprint`.
    public let partyId: String
    /// Canonical fingerprint of the signing key, as computed by the
    /// participant; goes into `signedBy` on every signature.
    public let publicKeyFingerprint: String

    /// Reconstructs a persisted identity — the party is already allocated on
    /// the ledger; pair with the driver revived from its stored key handle
    /// (see `WalletStore`).
    public init(partyId: String, publicKeyFingerprint: String) {
        self.partyId = partyId
        self.publicKeyFingerprint = publicKeyFingerprint
    }
}

extension CantonClient.Services {
    /// Admin party management (external party onboarding lives here).
    public var partyManagement:
        Com_Daml_Ledger_Api_V2_Admin_PartyManagementService.Client<CantonClient.Transport>
    {
        .init(wrapping: grpc)
    }
}

/// Onboards external parties: parties whose signing keys are held outside
/// the participant node (device enclave, custody provider), so the node
/// cannot act for them unilaterally.
///
/// Flow (all heavy lifting is server-side):
/// 1. `GenerateExternalPartyTopology` — the participant builds the topology
///    transactions and returns the multi-hash to sign plus the canonical
///    fingerprint of the supplied public key.
/// 2. The ``SigningDriver`` signs the multi-hash.
/// 3. `AllocateExternalParty` — submits the transactions with the
///    multi-hash signature; the participant waits for allocation.
public struct ExternalPartyClient: Sendable {
    private let client: CantonClient

    /// Creates a client over `client`'s connection settings.
    public init(client: CantonClient) {
        self.client = client
    }

    /// Synchronizers this participant is connected to (for
    /// ``allocate(driver:synchronizerId:partyHint:userId:)``).
    public func connectedSynchronizers() async throws -> [String] {
        try await client.withServices { services in
            try await services.state
                .getConnectedSynchronizers(.init())
                .connectedSynchronizers.map(\.synchronizerID)
        }
    }

    /// Runs the three-step flow: generates the topology for `driver`'s public
    /// key, signs the multi-hash with `driver`, and allocates. `partyHint`
    /// becomes the party id's prefix; `userId`, when given, is passed as the
    /// allocation request's user id.
    public func allocate(
        driver: any SigningDriver,
        synchronizerId: String,
        partyHint: String,
        userId: String? = nil
    ) async throws -> AllocatedExternalParty {
        let publicKey = try await driver.publicKey()

        var generateRequest = Com_Daml_Ledger_Api_V2_Admin_GenerateExternalPartyTopologyRequest()
        generateRequest.synchronizer = synchronizerId
        generateRequest.partyHint = partyHint
        generateRequest.publicKey = publicKey

        let frozenGenerateRequest = generateRequest
        let generated = try await client.withServices { services in
            try await services.partyManagement.generateExternalPartyTopology(frozenGenerateRequest)
        }

        var multiHashSignature = try await driver.sign(generated.multiHash)
        multiHashSignature.signedBy = generated.publicKeyFingerprint

        var allocateRequest = Com_Daml_Ledger_Api_V2_Admin_AllocateExternalPartyRequest()
        allocateRequest.synchronizer = synchronizerId
        allocateRequest.onboardingTransactions = generated.topologyTransactions.map {
            var signed = Com_Daml_Ledger_Api_V2_Admin_AllocateExternalPartyRequest.SignedTransaction()
            signed.transaction = $0
            return signed
        }
        allocateRequest.multiHashSignatures = [multiHashSignature]
        if let userId {
            allocateRequest.userID = userId
        }

        let frozenAllocateRequest = allocateRequest
        let allocated = try await client.withServices { services in
            try await services.partyManagement.allocateExternalParty(frozenAllocateRequest)
        }
        return AllocatedExternalParty(
            partyId: allocated.partyID,
            publicKeyFingerprint: generated.publicKeyFingerprint
        )
    }
}
