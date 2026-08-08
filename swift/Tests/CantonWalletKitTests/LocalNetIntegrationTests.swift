// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonKit
import CantonLedgerAPI
import CryptoKit
import Foundation
import Testing

@testable import CantonWalletKit

/// Runs against Splice LocalNet (see integration/run-localnet.sh). Skipped
/// unless SPLICE_LOCALNET=1.
///
/// Lighter than the Kotlin full-loop test (which drives tap + transfer):
/// this verifies the Swift stack against a real Splice deployment — JWT'd
/// ledger auth, P-256 external party allocation on the app-user participant,
/// interface-filtered token-standard reads, and ANS resolution via scan.
struct LocalNetIntegrationTests {
    private static var enabled: Bool {
        ProcessInfo.processInfo.environment["SPLICE_LOCALNET"] == "1"
    }

    private static func env(_ name: String, _ fallback: String) -> String {
        ProcessInfo.processInfo.environment[name] ?? fallback
    }

    /// Unsafe HS256 JWT matching LocalNet's `unsafe-jwt-hmac-256` auth service.
    private static func jwt(sub: String) -> String {
        func b64(_ data: Data) -> String {
            data.base64EncodedString()
                .replacingOccurrences(of: "+", with: "-")
                .replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: "")
        }
        let audience = env("SPLICE_LOCALNET_AUDIENCE", "https://canton.network.global")
        let header = b64(Data(#"{"alg":"HS256","typ":"JWT"}"#.utf8))
        let payload = b64(Data(#"{"sub":"\#(sub)","aud":"\#(audience)"}"#.utf8))
        let mac = HMAC<SHA256>.authenticationCode(
            for: Data("\(header).\(payload)".utf8),
            using: SymmetricKey(data: Data("unsafe".utf8))
        )
        return "\(header).\(payload).\(b64(Data(mac)))"
    }

    private static func adminClient() -> CantonClient {
        CantonClient(
            configuration: .init(
                host: env("SPLICE_LOCALNET_LEDGER_HOST", "127.0.0.1"),
                port: Int(env("SPLICE_LOCALNET_LEDGER_PORT", "2901"))!,
                useTLS: false,
                accessTokenProvider: { jwt(sub: env("SPLICE_LOCALNET_ADMIN_USER", "ledger-api-user")) }
            )
        )
    }

    @Test(.enabled(if: enabled, "SPLICE_LOCALNET not set"))
    func p256ExternalPartyAndTokenStandardReadsOnSplice() async throws {
        let client = Self.adminClient()
        let driver = SoftwareSigningDriver.generate(.ecP256)
        let parties = ExternalPartyClient(client: client)
        let synchronizer = try await parties.connectedSynchronizers().first!
        let party = try await parties.allocate(
            driver: driver,
            synchronizerId: synchronizer,
            partyHint: "swiftlocalnet",
            userId: Self.env("SPLICE_LOCALNET_ADMIN_USER", "ledger-api-user")
        )
        #expect(party.partyId.hasPrefix("swiftlocalnet::"))

        // Fresh party: the interface-filtered read and history paths run
        // against the Splice participant (Amulet DARs, JWT auth) and agree.
        let tokens = TokenStandardClient(client: client)
        let holdings = try await tokens.listHoldings(partyId: party.partyId)
        let history = try await tokens.holdingsHistory(partyId: party.partyId)
        #expect(holdings.isEmpty)
        #expect(history.isEmpty)
    }

    /// Swift twin of the Kotlin `LocalNetPreparedTransactionHashIntegrationTest`:
    /// every `prepare` on Splice LocalNet must re-hash locally to exactly the
    /// `prepared_transaction_hash` the node asks the party to sign. Covers a
    /// create node (TransferPreapprovalProposal) and its Archive (exercise
    /// node + input contract in the metadata); both are then executed with
    /// `signAndExecute`, whose verification is on by default — so the run
    /// also proves the verify-then-sign path end to end.
    @Test(.enabled(if: enabled, "SPLICE_LOCALNET not set"))
    func preparedTransactionHashMatchesTheNodesOnLivePrepares() async throws {
        let adminUser = Self.env("SPLICE_LOCALNET_ADMIN_USER", "ledger-api-user")
        let client = Self.adminClient()
        let driver = SoftwareSigningDriver.generate(.ecP256)
        let parties = ExternalPartyClient(client: client)
        let synchronizer = try await parties.connectedSynchronizers().first!
        let party = try await parties.allocate(
            driver: driver,
            synchronizerId: synchronizer,
            partyHint: "swifthashcheck",
            userId: adminUser
        )

        let submission = InteractiveSubmissionClient(client: client)

        // 1. Create node: the receiver proposes its own preapproval.
        // provider == receiver keeps the test self-contained; nothing
        // validates it at create time and we archive it ourselves.
        var create = Com_Daml_Ledger_Api_V2_CreateCommand()
        create.templateID = SpliceWallet.transferPreapprovalProposalTemplateID
        create.createArguments = try Com_Daml_Ledger_Api_V2_Value.record([
            "receiver": .party(party.partyId),
            "provider": .party(party.partyId),
            "expectedDso": .optional(.party(party.partyId)),
        ]).asRecord()
        var createCommand = Com_Daml_Ledger_Api_V2_Command()
        createCommand.create = create

        let preparedCreate = try await submission.prepare(
            commands: [createCommand],
            actAs: party.partyId,
            synchronizerId: synchronizer,
            userId: adminUser
        )
        try Self.expectHashMatches("create_preapproval_proposal", preparedCreate)

        // Executing runs the same verification again (on by default).
        try await submission.signAndExecute(
            prepared: preparedCreate,
            driver: driver,
            partyId: party.partyId,
            keyFingerprint: party.publicKeyFingerprint,
            userId: adminUser
        )

        // Execution is async; poll until the proposal reaches the ACS.
        var proposalId: String?
        for _ in 0..<60 where proposalId == nil {
            let snapshot = try await client.activeContractsSnapshot(parties: [party.partyId])
            proposalId = snapshot.contracts.first?.createdEvent.contractID
            if proposalId == nil {
                try await Task.sleep(for: .seconds(1))
            }
        }
        let contractId = try #require(proposalId, "proposal never reached the ACS")

        // 2. Exercise node + input contract: archive the proposal.
        var archive = Com_Daml_Ledger_Api_V2_ExerciseCommand()
        archive.templateID = SpliceWallet.transferPreapprovalProposalTemplateID
        archive.contractID = contractId
        archive.choice = "Archive"
        archive.choiceArgument = .record([:])
        var archiveCommand = Com_Daml_Ledger_Api_V2_Command()
        archiveCommand.exercise = archive

        let preparedArchive = try await submission.prepare(
            commands: [archiveCommand],
            actAs: party.partyId,
            synchronizerId: synchronizer,
            userId: adminUser
        )
        try Self.expectHashMatches("archive_preapproval_proposal", preparedArchive)

        try await submission.signAndExecute(
            prepared: preparedArchive,
            driver: driver,
            partyId: party.partyId,
            keyFingerprint: party.publicKeyFingerprint,
            userId: adminUser
        )
    }

    private static func expectHashMatches(
        _ name: String,
        _ prepared: Com_Daml_Ledger_Api_V2_Interactive_PrepareSubmissionResponse
    ) throws {
        #expect(
            prepared.hashingSchemeVersion == .v2,
            "LocalNet prepared '\(name)' with an unexpected hashing scheme"
        )
        let computed = try PreparedTransactionHash.compute(prepared.preparedTransaction)
        print(
            "golden-vector: \(name) " +
                "\((try prepared.preparedTransaction.serializedData()).base64EncodedString()) " +
                prepared.preparedTransactionHash.base64EncodedString()
        )
        #expect(
            computed == prepared.preparedTransactionHash,
            "locally recomputed hash differs from the node's for '\(name)'"
        )
    }

    @Test(.enabled(if: enabled, "SPLICE_LOCALNET not set"))
    func ansResolutionAgainstLiveScan() async throws {
        let scan = ScanClient(
            baseURL: URL(string: Self.env("SPLICE_LOCALNET_SCAN_URL", "http://scan.localhost:4000/api/scan"))!
        )
        let dso = try await scan.dsoPartyId()
        #expect(dso.hasPrefix("DSO::"))

        let entry = try await scan.lookupAnsEntryByName("dso.ans")
        #expect(entry?.party == dso)
        #expect(try await scan.lookupAnsEntryByName("definitely-not-registered.ans") == nil)
        #expect(try await scan.listAnsEntries(pageSize: 10).contains { $0.name == "dso.ans" })
    }
}
