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

    /// Command completion events — the ledger's asynchronous verdict on
    /// submitted commands.
    public var commandCompletion:
        Com_Daml_Ledger_Api_V2_CommandCompletionService.Client<CantonClient.Transport>
    {
        .init(wrapping: grpc)
    }
}

/// The ledger's completion for an executed submission — the proof that the
/// command was actually committed, not merely accepted for execution.
public struct SubmissionCompletion: Sendable, Hashable {
    /// Update id of the resulting transaction; correlate with update streams.
    public let updateId: String

    /// Offset of the completion on the participant.
    public let offset: Int64

    public init(updateId: String, offset: Int64) {
        self.updateId = updateId
        self.offset = offset
    }
}

/// The completion stream ended before the awaited submission's completion
/// arrived (defensive: participants keep completion streams open).
struct CompletionStreamEndedError: Error, CustomStringConvertible {
    let submissionId: String
    var description: String {
        "completion stream ended before the completion for submission id \(submissionId)"
    }
}

/// The prepare → sign → execute flow for externally-signed transactions
/// (Interactive Submission Service).
///
/// `ExecuteSubmission` only acknowledges that the participant accepted the
/// submission; commitment (or rejection — contention, time bounds) is
/// reported asynchronously on the completion stream. Use
/// ``signAndExecuteAndWait(prepared:driver:partyId:keyFingerprint:userId:submissionId:verifyHash:)``
/// to await that verdict.
///
/// TODO before 1.0:
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
    ///
    /// Unless `verifyHash` is disabled, the hash is first recomputed locally
    /// from the raw `PreparedTransaction` proto and compared against the
    /// node-supplied `prepared_transaction_hash`
    /// (`PreparedTransactionHash.verify`); a
    /// `PreparedTransactionHashMismatchError` aborts the submission before
    /// anything is signed. Only disable this against a participant you
    /// fully trust.
    public func signAndExecute(
        prepared: Com_Daml_Ledger_Api_V2_Interactive_PrepareSubmissionResponse,
        driver: any SigningDriver,
        partyId: String,
        keyFingerprint: String,
        userId: String? = nil,
        submissionId: String = UUID().uuidString,
        verifyHash: Bool = true
    ) async throws {
        if verifyHash {
            try PreparedTransactionHash.verify(prepared)
        }

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

    /// ``signAndExecute(prepared:driver:partyId:keyFingerprint:userId:submissionId:verifyHash:)``,
    /// then waits for the command's completion event — the only way to learn
    /// the outcome of an interactive submission, since `ExecuteSubmission`
    /// acknowledges acceptance, not commitment.
    ///
    /// The ledger end is recorded before executing and the completion stream
    /// replays from there, so the completion cannot be missed; it is matched
    /// by `userId` + `submissionId`. On success returns the completion's
    /// update id and offset; if the ledger rejected the command (contention,
    /// time bounds), throws the typed ``CantonError`` decoded from the
    /// completion's `google.rpc.Status` details.
    ///
    /// Suspends until the participant emits the completion; cancel the
    /// surrounding task to bound the wait.
    public func signAndExecuteAndWait(
        prepared: Com_Daml_Ledger_Api_V2_Interactive_PrepareSubmissionResponse,
        driver: any SigningDriver,
        partyId: String,
        keyFingerprint: String,
        userId: String? = nil,
        submissionId: String = UUID().uuidString,
        verifyHash: Bool = true
    ) async throws -> SubmissionCompletion {
        // Recorded before executing: replaying completions from here
        // guarantees ours cannot slip past between execute and subscribe.
        let ledgerEndBeforeExecute = try await client.ledgerEnd()

        try await signAndExecute(
            prepared: prepared,
            driver: driver,
            partyId: partyId,
            keyFingerprint: keyFingerprint,
            userId: userId,
            submissionId: submissionId,
            verifyHash: verifyHash
        )

        var request = Com_Daml_Ledger_Api_V2_CompletionStreamRequest()
        if let userId {
            request.userID = userId
        }
        request.parties = [partyId]
        request.beginExclusive = ledgerEndBeforeExecute
        let frozenRequest = request
        return try await client.withServices { services in
            try await services.commandCompletion.completionStream(frozenRequest) { response in
                for try await message in response.messages {
                    guard case .completion(let completion)? = message.completionResponse else {
                        continue
                    }
                    guard completion.submissionID == submissionId else { continue }
                    if let userId, completion.userID != userId { continue }
                    guard !completion.hasStatus || completion.status.code == 0 else {
                        throw CantonError(completionStatus: completion.status)
                    }
                    return SubmissionCompletion(
                        updateId: completion.updateID,
                        offset: completion.offset
                    )
                }
                throw CompletionStreamEndedError(submissionId: submissionId)
            }
        }
    }
}
