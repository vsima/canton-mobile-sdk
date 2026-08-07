import CantonLedgerAPI
import Foundation
import Testing
import CantonKit

/// Runs against a live Canton participant (see integration/run-canton.sh).
/// Skipped unless CANTON_LEDGER_PORT is set.
private var liveLedgerPort: Int? {
    ProcessInfo.processInfo.environment["CANTON_LEDGER_PORT"].flatMap(Int.init)
}

private var examplesDarPath: String? {
    ProcessInfo.processInfo.environment["CANTON_EXAMPLES_DAR"]
}

@Suite struct CantonLedgerIntegrationTests {
    private var host: String {
        ProcessInfo.processInfo.environment["CANTON_LEDGER_HOST"] ?? "127.0.0.1"
    }

    @Test(.enabled(if: liveLedgerPort != nil))
    func fetchesVersionFromLiveParticipant() async throws {
        let client = CantonClient(
            configuration: .init(host: host, port: liveLedgerPort!, useTLS: false)
        )
        let version = try await client.ledgerApiVersion()
        print("live canton ledger api version: \(version)")
        #expect(!version.isEmpty)
    }

    @Test(.enabled(if: liveLedgerPort != nil && examplesDarPath != nil))
    func allocatesPartyUploadsDarAndCreatesContract() async throws {
        let client = CantonClient(
            configuration: .init(host: host, port: liveLedgerPort!, useTLS: false)
        )
        let party = try await allocatePartyAndUploadDar(client: client)

        let transaction = try await client.submitAndWaitForTransaction(iouSubmission(party))
        print("created Iou contract in update \(transaction.updateID)")
        #expect(!transaction.updateID.isEmpty)
        #expect(
            transaction.events.contains { event in
                if case .created = event.event { return true } else { return false }
            }
        )
    }

    @Test(.enabled(if: liveLedgerPort != nil && examplesDarPath != nil))
    func streamsCommittedTransactionsAndResumes() async throws {
        let client = CantonClient(
            configuration: .init(host: host, port: liveLedgerPort!, useTLS: false)
        )
        let party = try await allocatePartyAndUploadDar(client: client)

        let before = try await client.ledgerEnd()
        _ = try await client.submitAndWait(iouSubmission(party))
        _ = try await client.submitAndWait(iouSubmission(party))
        let after = try await client.ledgerEnd()

        var transactions: [Com_Daml_Ledger_Api_V2_Transaction] = []
        for try await update in client.updates(
            .init(parties: [party], beginExclusive: before, endInclusive: after)
        ) {
            if case .transaction(let transaction) = update {
                transactions.append(transaction)
            }
        }
        print("streamed \(transactions.count) transactions between offsets \(before)...\(after)")
        #expect(transactions.count == 2)
        #expect(transactions.allSatisfy { $0.offset > before && $0.offset <= after })

        // Resume mid-window: only the second transaction remains.
        var resumed: [Com_Daml_Ledger_Api_V2_Transaction] = []
        for try await update in client.updates(
            .init(parties: [party], beginExclusive: transactions[0].offset, endInclusive: after)
        ) {
            if case .transaction(let transaction) = update {
                resumed.append(transaction)
            }
        }
        #expect(resumed.count == 1)
        #expect(resumed.first?.updateID == transactions.last?.updateID)
    }

    @Test(.enabled(if: liveLedgerPort != nil && examplesDarPath != nil))
    func bootstrapsFromAcsSnapshotAndFollowsWithUpdates() async throws {
        let client = CantonClient(
            configuration: .init(host: host, port: liveLedgerPort!, useTLS: false)
        )
        let party = try await allocatePartyAndUploadDar(client: client)
        _ = try await client.submitAndWait(iouSubmission(party))
        _ = try await client.submitAndWait(iouSubmission(party))

        let snapshot = try await client.activeContractsSnapshot(parties: [party])
        print("acs snapshot at offset \(snapshot.offset): \(snapshot.contracts.count) contracts")
        #expect(snapshot.contracts.count == 2)
        #expect(snapshot.contracts.allSatisfy { !$0.createdEvent.contractID.isEmpty })
        #expect(snapshot.contracts.allSatisfy { !$0.synchronizerId.isEmpty })

        // Deltas after the snapshot offset: exactly the third create.
        _ = try await client.submitAndWait(iouSubmission(party))
        let after = try await client.ledgerEnd()
        var transactions = 0
        for try await update in client.updates(
            .init(parties: [party], beginExclusive: snapshot.offset, endInclusive: after)
        ) {
            if case .transaction = update { transactions += 1 }
        }
        #expect(transactions == 1)
    }

    /// Admin setup through the generated clients: allocate a fresh party and
    /// make sure the CantonExamples package is on the participant.
    private func allocatePartyAndUploadDar(client: CantonClient) async throws -> String {
        let dar = try Data(contentsOf: URL(fileURLWithPath: examplesDarPath!))
        return try await client.withServices { services in
            let partyManagement = Com_Daml_Ledger_Api_V2_Admin_PartyManagementService.Client(
                wrapping: services.grpc
            )
            let party = try await partyManagement
                .allocateParty(Com_Daml_Ledger_Api_V2_Admin_AllocatePartyRequest())
                .partyDetails.party

            let packageManagement = Com_Daml_Ledger_Api_V2_Admin_PackageManagementService.Client(
                wrapping: services.grpc
            )
            var upload = Com_Daml_Ledger_Api_V2_Admin_UploadDarFileRequest()
            upload.darFile = dar
            _ = try await packageManagement.uploadDarFile(upload)
            return party
        }
    }

    private func iouSubmission(_ party: String) -> CommandSubmission {
        CommandSubmission(
            commands: [iouCreate(payer: party)],
            actAs: [party],
            // No auth on the test ledger, so user_id cannot be defaulted
            // from token claims and must be explicit.
            userId: "participant_admin"
        )
    }

    private func iouCreate(payer: String) -> Com_Daml_Ledger_Api_V2_Command {
        func value(
            _ configure: (inout Com_Daml_Ledger_Api_V2_Value) -> Void
        ) -> Com_Daml_Ledger_Api_V2_Value {
            var v = Com_Daml_Ledger_Api_V2_Value()
            configure(&v)
            return v
        }

        func field(_ label: String, _ v: Com_Daml_Ledger_Api_V2_Value) -> Com_Daml_Ledger_Api_V2_RecordField {
            var f = Com_Daml_Ledger_Api_V2_RecordField()
            f.label = label
            f.value = v
            return f
        }

        var amountRecord = Com_Daml_Ledger_Api_V2_Record()
        amountRecord.fields = [
            field("value", value { $0.numeric = "100.0" }),
            field("currency", value { $0.text = "USD" }),
        ]

        var templateId = Com_Daml_Ledger_Api_V2_Identifier()
        templateId.packageID = "#CantonExamples"
        templateId.moduleName = "Iou"
        templateId.entityName = "Iou"

        var arguments = Com_Daml_Ledger_Api_V2_Record()
        arguments.fields = [
            field("payer", value { $0.party = payer }),
            field("owner", value { $0.party = payer }),
            field("amount", value { $0.record = amountRecord }),
            field("viewers", value { $0.list = Com_Daml_Ledger_Api_V2_List() }),
        ]

        var create = Com_Daml_Ledger_Api_V2_CreateCommand()
        create.templateID = templateId
        create.createArguments = arguments

        var command = Com_Daml_Ledger_Api_V2_Command()
        command.create = create
        return command
    }
}
