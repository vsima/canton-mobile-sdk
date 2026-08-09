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

    /// Swift twin of the Kotlin `LocalNetCompletionTrackingIntegrationTest`:
    /// `signAndExecuteAndWait` must surface the ledger's completion for a
    /// live interactive submission — a real update id on success, and the
    /// typed rejection when the ledger refuses the command. Because the
    /// awaited variant returns only once the command is committed, the
    /// created contract must already be in the ACS — read once, no polling.
    /// The rejection leg manufactures contention: two archives of the same
    /// contract are prepared while it is still active, then executed one
    /// after the other — the second can only fail asynchronously, in its
    /// completion event.
    @Test(.enabled(if: enabled, "SPLICE_LOCALNET not set"))
    func signAndExecuteAndWaitReturnsTheLiveUpdateIdAndRaisesTypedRejections() async throws {
        let adminUser = Self.env("SPLICE_LOCALNET_ADMIN_USER", "ledger-api-user")
        let client = Self.adminClient()
        let driver = SoftwareSigningDriver.generate(.ecP256)
        let parties = ExternalPartyClient(client: client)
        let synchronizer = try await parties.connectedSynchronizers().first!
        let party = try await parties.allocate(
            driver: driver,
            synchronizerId: synchronizer,
            partyHint: "swiftcompletion",
            userId: adminUser
        )
        print("external party: \(party.partyId)")

        let submission = InteractiveSubmissionClient(client: client)

        // Create a TransferPreapprovalProposal (provider == receiver keeps
        // it self-contained), awaited: the returned completion proves
        // commitment.
        var create = Com_Daml_Ledger_Api_V2_CreateCommand()
        create.templateID = SpliceWallet.transferPreapprovalProposalTemplateID
        create.createArguments = try Com_Daml_Ledger_Api_V2_Value.record([
            "receiver": .party(party.partyId),
            "provider": .party(party.partyId),
            "expectedDso": .optional(.party(party.partyId)),
        ]).asRecord()
        var createCommand = Com_Daml_Ledger_Api_V2_Command()
        createCommand.create = create

        let created = try await submission.signAndExecuteAndWait(
            prepared: submission.prepare(
                commands: [createCommand],
                actAs: party.partyId,
                synchronizerId: synchronizer,
                userId: adminUser
            ),
            driver: driver,
            partyId: party.partyId,
            keyFingerprint: party.publicKeyFingerprint,
            userId: adminUser
        )
        print("create completion: updateId=\(created.updateId) offset=\(created.offset)")
        #expect(!created.updateId.isEmpty, "completion must carry a real update id")
        #expect(created.offset > 0, "completion must carry a real offset")

        // Committed means visible: one ACS read, no polling.
        let snapshot = try await client.activeContractsSnapshot(parties: [party.partyId])
        let proposalId = try #require(
            snapshot.contracts.first?.createdEvent.contractID,
            "awaited create must already be in the ACS"
        )
        print("proposal contract: \(proposalId)")

        // Prepare TWO archives of the live proposal, then execute both: the
        // second passes interpretation but can only be refused at commit
        // time — asynchronously, in its completion event.
        func preparedArchive() async throws
            -> Com_Daml_Ledger_Api_V2_Interactive_PrepareSubmissionResponse
        {
            var archive = Com_Daml_Ledger_Api_V2_ExerciseCommand()
            archive.templateID = SpliceWallet.transferPreapprovalProposalTemplateID
            archive.contractID = proposalId
            archive.choice = "Archive"
            archive.choiceArgument = .record([:])
            var archiveCommand = Com_Daml_Ledger_Api_V2_Command()
            archiveCommand.exercise = archive
            return try await submission.prepare(
                commands: [archiveCommand],
                actAs: party.partyId,
                synchronizerId: synchronizer,
                userId: adminUser
            )
        }
        let firstArchive = try await preparedArchive()
        let secondArchive = try await preparedArchive()

        let archived = try await submission.signAndExecuteAndWait(
            prepared: firstArchive,
            driver: driver,
            partyId: party.partyId,
            keyFingerprint: party.publicKeyFingerprint,
            userId: adminUser
        )
        print("archive completion: updateId=\(archived.updateId) offset=\(archived.offset)")
        #expect(!archived.updateId.isEmpty)
        #expect(archived.offset > created.offset)
        #expect(archived.updateId != created.updateId)

        do {
            _ = try await submission.signAndExecuteAndWait(
                prepared: secondArchive,
                driver: driver,
                partyId: party.partyId,
                keyFingerprint: party.publicKeyFingerprint,
                userId: adminUser
            )
            Issue.record("second archive of the same contract must be rejected")
        } catch let rejection as CantonError {
            print(
                "typed rejection: grpcCode=\(rejection.grpcCode) " +
                    "errorCode=\(rejection.errorCode ?? "nil") " +
                    "message=\(rejection.message.prefix(120))"
            )
            #expect(
                rejection.errorCode != nil,
                "completion rejection must decode Canton's typed error code"
            )
        }
    }

    /// Scan answers holdings summaries from periodic ACS snapshots (hours
    /// apart on LocalNet), so this deliberately does NOT tap-and-expect
    /// instant consistency. It asserts against the validator operator's
    /// wallet party, whose holdings long predate the latest snapshot —
    /// polling briefly in case scan is still taking its first snapshot.
    @Test(.enabled(if: enabled, "SPLICE_LOCALNET not set"))
    func holdingsSummariesAgainstLiveScan() async throws {
        let scan = ScanClient(
            baseURL: URL(string: Self.env("SPLICE_LOCALNET_SCAN_URL", "http://scan.localhost:4000/api/scan"))!
        )
        let walletParty = try await Self.operatorWalletParty()
        let dso = try await scan.dsoPartyId()
        print("wallet party: \(walletParty)")

        // Poll with a deadline: tolerate a scan that hasn't taken its first
        // snapshot yet, never instant consistency.
        var result: ScanClient.HoldingsSummaryResult?
        for attempt in 1...24 {
            result = try await scan.holdingsSummary(ownerPartyIds: [walletParty, dso])
            if result?.summaries.contains(where: { $0.partyId == walletParty }) == true {
                break
            }
            print("  (attempt \(attempt): no snapshot summary for the wallet party yet)")
            try await Task.sleep(for: .seconds(5))
        }
        let summaries = try #require(result, "scan never produced an ACS snapshot")
        print(
            "summary: record_time=\(summaries.recordTime) migration=\(summaries.migrationId) "
                + summaries.summaries.map { "\($0.partyId.prefix(24))…=\($0.totalCoinHoldings)" }
                .joined(separator: " ")
        )

        // The snapshot is server-side state: it must not postdate now.
        #expect(summaries.recordTime <= Date())
        #expect(try await scan.latestMigrationId() == summaries.migrationId)

        let operatorSummary = try #require(
            summaries.summaries.first { $0.partyId == walletParty },
            "operator wallet party missing from the snapshot summary"
        )
        let total = try #require(Decimal(string: operatorSummary.totalCoinHoldings))
        #expect(total > 0, "operator wallet must show positive holdings, got \(total)")
        let unlocked = try #require(Decimal(string: operatorSummary.totalUnlockedCoin))
        let locked = try #require(Decimal(string: operatorSummary.totalLockedCoin))
        #expect(unlocked + locked == total)

        // Pinning the snapshot and migration id explicitly answers identically.
        let pinned = try #require(
            try await scan.holdingsSummary(
                ownerPartyIds: [walletParty],
                asOf: summaries.recordTime,
                migrationId: summaries.migrationId
            ),
            "pinned re-read must find the same snapshot"
        )
        #expect(pinned.recordTime == summaries.recordTime)
        #expect(pinned.summaries == [operatorSummary])

        // No snapshot can exist before genesis: the read reports that as nil.
        let preGenesis = try await scan.holdingsSummary(
            ownerPartyIds: [walletParty],
            asOf: ISO8601DateFormatter().date(from: "2000-01-01T00:00:00Z")!
        )
        #expect(preGenesis == nil)
    }

    /// The validator operator's wallet party for the app-user, from the
    /// validator's wallet API — the party the LocalNet faucet taps to.
    private static func operatorWalletParty() async throws -> String {
        let base = env("SPLICE_LOCALNET_VALIDATOR_URL", "http://wallet.localhost:2000/api/validator")
        var request = URLRequest(url: URL(string: "\(base)/v0/wallet/user-status")!)
        request.setValue(
            "Bearer \(jwt(sub: env("SPLICE_LOCALNET_WALLET_USER", "app-user")))",
            forHTTPHeaderField: "Authorization"
        )
        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        let body = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        guard status == 200, let party = body?["party_id"] as? String, !party.isEmpty else {
            throw ScanError(description: "validator user-status failed (HTTP \(status)): \(body ?? [:])")
        }
        return party
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
