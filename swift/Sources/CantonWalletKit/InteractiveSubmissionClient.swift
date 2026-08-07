// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonKit
import CantonLedgerAPI
import Foundation

extension CantonClient.Services {
    /// Prepare → sign → execute service for externally-signed transactions.
    public var interactiveSubmission:
        Com_Daml_Ledger_Api_V2_Interactive_InteractiveSubmissionService.Client<CantonClient.Transport>
    {
        .init(wrapping: grpc)
    }
}

/// The prepare → sign → execute flow for externally-signed transactions
/// (Interactive Submission Service).
///
/// WP1 scaffold. Working: prepare, sign-and-execute round trip. TODO before
/// 1.0:
/// - client-side re-computation and verification of the prepared transaction
///   hash (never trust the node's hash blindly)
/// - completion tracking (wait for the command's completion event)
/// - command deduplication config parity with `CommandSubmission`
public struct InteractiveSubmissionClient: Sendable {
    private let client: CantonClient

    public init(client: CantonClient) {
        self.client = client
    }

    public func prepare(
        commands: [Com_Daml_Ledger_Api_V2_Command],
        actAs: String,
        synchronizerId: String,
        userId: String? = nil,
        commandId: String = UUID().uuidString,
        disclosedContracts: [Com_Daml_Ledger_Api_V2_DisclosedContract] = []
    ) async throws -> Com_Daml_Ledger_Api_V2_Interactive_PrepareSubmissionResponse {
        var request = Com_Daml_Ledger_Api_V2_Interactive_PrepareSubmissionRequest()
        request.commands = commands
        request.actAs = [actAs]
        request.synchronizerID = synchronizerId
        request.commandID = commandId
        if let userId {
            request.userID = userId
        }
        request.disclosedContracts = disclosedContracts
        request.verboseHashing = false
        let frozenRequest = request
        return try await client.withServices { services in
            try await services.interactiveSubmission.prepareSubmission(frozenRequest)
        }
    }

    /// Signs the prepared transaction hash with `driver` and executes.
    /// The signature's `signedBy` fingerprint must identify the party's
    /// registered key — pass the fingerprint returned at party onboarding.
    public func signAndExecute(
        prepared: Com_Daml_Ledger_Api_V2_Interactive_PrepareSubmissionResponse,
        driver: any SigningDriver,
        partyId: String,
        keyFingerprint: String,
        userId: String? = nil,
        submissionId: String = UUID().uuidString
    ) async throws {
        var signature = try await driver.sign(prepared.preparedTransactionHash)
        signature.signedBy = keyFingerprint

        var single = Com_Daml_Ledger_Api_V2_Interactive_SinglePartySignatures()
        single.party = partyId
        single.signatures = [signature]

        var request = Com_Daml_Ledger_Api_V2_Interactive_ExecuteSubmissionRequest()
        request.preparedTransaction = prepared.preparedTransaction
        request.partySignatures.signatures = [single]
        request.hashingSchemeVersion = prepared.hashingSchemeVersion
        request.submissionID = submissionId
        if let userId {
            request.userID = userId
        }
        let frozenRequest = request
        _ = try await client.withServices { services in
            try await services.interactiveSubmission.executeSubmission(frozenRequest)
        }
    }
}
