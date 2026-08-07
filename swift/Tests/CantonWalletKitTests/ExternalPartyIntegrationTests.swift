import CantonKit
import CantonLedgerAPI
import Foundation
import Testing

@testable import CantonWalletKit

/// Runs against a live Canton participant (see integration/run-canton.sh).
/// Skipped unless CANTON_LEDGER_PORT is set.
///
/// Swift twin of the Kotlin `ExternalPartyIntegrationTest`: proves a real
/// participant accepts external parties with both Ed25519 (Canton's
/// default) and EC P-256 — the only scheme the Secure Enclave signs, and
/// therefore the scheme mobile self-custody hangs on.
struct ExternalPartyIntegrationTests {
    private static var port: Int? {
        ProcessInfo.processInfo.environment["CANTON_LEDGER_PORT"].flatMap(Int.init)
    }

    private func allocate(
        _ driver: any SigningDriver,
        hint: String
    ) async throws -> AllocatedExternalParty {
        let client = CantonClient(
            configuration: .init(
                host: ProcessInfo.processInfo.environment["CANTON_LEDGER_HOST"] ?? "127.0.0.1",
                port: Self.port!,
                useTLS: false
            )
        )
        let partyClient = ExternalPartyClient(client: client)
        let synchronizer = try await partyClient.connectedSynchronizers().first!
        return try await partyClient.allocate(
            driver: driver,
            synchronizerId: synchronizer,
            partyHint: hint,
            userId: "participant_admin"
        )
    }

    @Test(.enabled(if: port != nil, "CANTON_LEDGER_PORT not set"))
    func allocatesEd25519ExternalParty() async throws {
        let party = try await allocate(SoftwareSigningDriver.generate(.ed25519), hint: "swifted25519")
        #expect(party.partyId.hasPrefix("swifted25519::"))
    }

    @Test(.enabled(if: port != nil, "CANTON_LEDGER_PORT not set"))
    func allocatesP256ExternalParty() async throws {
        let party = try await allocate(SoftwareSigningDriver.generate(.ecP256), hint: "swiftp256")
        #expect(party.partyId.hasPrefix("swiftp256::"))
    }

    /// The full self-custody loop: onboard a P-256 external party, then
    /// create a contract acting as that party via prepare → sign → execute.
    /// Every signature comes from the driver — the participant never holds
    /// the key.
    @Test(.enabled(
        if: port != nil && ProcessInfo.processInfo.environment["CANTON_EXAMPLES_DAR"] != nil,
        "CANTON_LEDGER_PORT or CANTON_EXAMPLES_DAR not set"
    ))
    func p256ExternalPartyCreatesContract() async throws {
        let client = CantonClient(
            configuration: .init(
                host: ProcessInfo.processInfo.environment["CANTON_LEDGER_HOST"] ?? "127.0.0.1",
                port: Self.port!,
                useTLS: false
            )
        )

        let darPath = ProcessInfo.processInfo.environment["CANTON_EXAMPLES_DAR"]!
        let dar = try Data(contentsOf: URL(fileURLWithPath: darPath))
        try await client.withServices { services in
            var upload = Com_Daml_Ledger_Api_V2_Admin_UploadDarFileRequest()
            upload.darFile = dar
            let packageManagement = Com_Daml_Ledger_Api_V2_Admin_PackageManagementService
                .Client<CantonClient.Transport>(wrapping: services.grpc)
            _ = try await packageManagement.uploadDarFile(upload)
        }

        let driver = SoftwareSigningDriver.generate(.ecP256)
        let partyClient = ExternalPartyClient(client: client)
        let synchronizer = try await partyClient.connectedSynchronizers().first!
        let party = try await partyClient.allocate(
            driver: driver,
            synchronizerId: synchronizer,
            partyHint: "swiftp256signer",
            userId: "participant_admin"
        )

        let submission = InteractiveSubmissionClient(client: client)
        let prepared = try await submission.prepare(
            commands: [Self.iouCreate(payer: party.partyId)],
            actAs: party.partyId,
            synchronizerId: synchronizer,
            userId: "participant_admin"
        )
        try await submission.signAndExecute(
            prepared: prepared,
            driver: driver,
            partyId: party.partyId,
            keyFingerprint: party.publicKeyFingerprint,
            userId: "participant_admin"
        )

        // Execution is async; the contract is committed once it shows up in
        // the party's ACS.
        for _ in 0..<30 {
            let snapshot = try await client.activeContractsSnapshot(parties: [party.partyId])
            if let contract = snapshot.contracts.first {
                #expect(!contract.createdEvent.contractID.isEmpty)
                return
            }
            try await Task.sleep(for: .milliseconds(500))
        }
        Issue.record("contract signed by the P-256 external party never reached the ACS")
    }

    private static func iouCreate(payer: String) -> Com_Daml_Ledger_Api_V2_Command {
        func value(_ build: (inout Com_Daml_Ledger_Api_V2_Value) -> Void) -> Com_Daml_Ledger_Api_V2_Value {
            var v = Com_Daml_Ledger_Api_V2_Value()
            build(&v)
            return v
        }

        func field(_ label: String, _ v: Com_Daml_Ledger_Api_V2_Value) -> Com_Daml_Ledger_Api_V2_RecordField {
            var f = Com_Daml_Ledger_Api_V2_RecordField()
            f.label = label
            f.value = v
            return f
        }

        var amount = Com_Daml_Ledger_Api_V2_Record()
        amount.fields = [
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
            field("amount", value { $0.record = amount }),
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
