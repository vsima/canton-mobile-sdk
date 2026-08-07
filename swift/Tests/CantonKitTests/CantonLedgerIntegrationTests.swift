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

        // Admin setup through the generated clients: allocate a fresh party
        // and make sure the CantonExamples package is on the participant.
        let dar = try Data(contentsOf: URL(fileURLWithPath: examplesDarPath!))
        let party = try await client.withServices { services in
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

        let transaction = try await client.submitAndWaitForTransaction(
            CommandSubmission(
                commands: [iouCreate(payer: party)],
                actAs: [party],
                // No auth on the test ledger, so user_id cannot be defaulted
                // from token claims and must be explicit.
                userId: "participant_admin"
            )
        )
        print("created Iou contract in update \(transaction.updateID)")
        #expect(!transaction.updateID.isEmpty)
        #expect(
            transaction.events.contains { event in
                if case .created = event.event { return true } else { return false }
            }
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
