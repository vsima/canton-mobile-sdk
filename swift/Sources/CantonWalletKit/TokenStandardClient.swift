import CantonKit
import CantonLedgerAPI
import Foundation

/// CIP-0056 token standard client: holdings, two-step transfers, and the
/// pending-instruction inbox — the read/write surface a wallet renders.
///
/// Reads go straight to the ledger (ACS filtered by the standard's
/// interfaces, so any compliant asset shows up with no per-asset
/// integration). Writes are externally signed: registry context via
/// ``TransferRegistryClient``, then prepare → sign → execute through
/// ``InteractiveSubmissionClient``.
///
/// Tracking: CIP-0112 (Token Standard V2 — batch settlement, account-based
/// holdings) will extend this surface; V1 remains the interop baseline.
public struct TokenStandardClient: Sendable {
    private let client: CantonClient
    private let registry: TransferRegistryClient?

    public init(client: CantonClient, registry: TransferRegistryClient? = nil) {
        self.client = client
        self.registry = registry
    }

    /// Active holding UTXOs visible to `partyId`, any CIP-0056 instrument.
    public func listHoldings(partyId: String) async throws -> [Holding] {
        try await activeInterfaceViews(
            partyId: partyId,
            interfaceId: TokenStandard.holdingInterfaceID
        ).map { try Holding.fromView(contractId: $0.0, view: $0.1) }
    }

    /// Pending two-step transfers visible to `partyId` — the wallet inbox.
    /// The receiver acts on `pendingReceiverAcceptance` entries; the sender
    /// may withdraw anything still pending.
    public func pendingTransferInstructions(partyId: String) async throws -> [TransferInstruction] {
        try await activeInterfaceViews(
            partyId: partyId,
            interfaceId: TokenStandard.transferInstructionInterfaceID
        ).map { try TransferInstruction.fromView(contractId: $0.0, view: $0.1) }
    }

    /// Initiates a transfer as `party` (externally signed).
    public func createTransfer(
        driver: any SigningDriver,
        party: AllocatedExternalParty,
        receiver: String,
        instrumentId: InstrumentId,
        amount: String,
        inputHoldingCids: [String],
        synchronizerId: String,
        userId: String? = nil,
        meta: [String: String] = [:],
        requestedAt: Date = Date(),
        executeBefore: Date = Date().addingTimeInterval(24 * 60 * 60)
    ) async throws {
        let registry = try requireRegistry()
        let transfer = Transfer(
            sender: party.partyId,
            receiver: receiver,
            amount: amount,
            instrumentId: instrumentId,
            requestedAt: requestedAt,
            executeBefore: executeBefore,
            inputHoldingCids: inputHoldingCids,
            meta: meta
        )

        let factory = try await registry.transferFactory(
            choiceArguments: ChoiceContextJSON.transferFactoryChoiceArguments(
                expectedAdmin: instrumentId.admin,
                transfer: transfer
            )
        )

        var exercise = Com_Daml_Ledger_Api_V2_ExerciseCommand()
        exercise.templateID = TokenStandard.transferFactoryInterfaceID
        exercise.contractID = factory.factoryId
        exercise.choice = "TransferFactory_Transfer"
        exercise.choiceArgument = .record([
            "expectedAdmin": .party(instrumentId.admin),
            "transfer": transfer.toValue(),
            "extraArgs": try ChoiceContextJSON.extraArgsValue(
                choiceContextData: factory.choiceContext.choiceContextData
            ),
        ])

        try await signAndSubmit(
            driver: driver,
            party: party,
            exercise: exercise,
            synchronizerId: synchronizerId,
            userId: userId,
            disclosed: factory.choiceContext.disclosedContracts
        )
    }

    /// Accept/reject (receiver) or withdraw (sender) a pending instruction.
    public func exerciseTransferInstruction(
        driver: any SigningDriver,
        party: AllocatedExternalParty,
        transferInstructionId: String,
        choice: TransferInstructionChoice,
        synchronizerId: String,
        userId: String? = nil
    ) async throws {
        let registry = try requireRegistry()
        let context = try await registry.transferInstructionChoiceContext(
            transferInstructionId: transferInstructionId,
            choice: choice
        )

        var exercise = Com_Daml_Ledger_Api_V2_ExerciseCommand()
        exercise.templateID = TokenStandard.transferInstructionInterfaceID
        exercise.contractID = transferInstructionId
        exercise.choice = choice.choiceName
        exercise.choiceArgument = .record([
            "extraArgs": try ChoiceContextJSON.extraArgsValue(
                choiceContextData: context.choiceContextData
            )
        ])

        try await signAndSubmit(
            driver: driver,
            party: party,
            exercise: exercise,
            synchronizerId: synchronizerId,
            userId: userId,
            disclosed: context.disclosedContracts
        )
    }

    private func signAndSubmit(
        driver: any SigningDriver,
        party: AllocatedExternalParty,
        exercise: Com_Daml_Ledger_Api_V2_ExerciseCommand,
        synchronizerId: String,
        userId: String?,
        disclosed: [TransferRegistryClient.RegistryDisclosedContract]
    ) async throws {
        var command = Com_Daml_Ledger_Api_V2_Command()
        command.exercise = exercise

        let submission = InteractiveSubmissionClient(client: client)
        let prepared = try await submission.prepare(
            commands: [command],
            actAs: party.partyId,
            synchronizerId: synchronizerId,
            userId: userId,
            disclosedContracts: try disclosed.map { try $0.toProto() }
        )
        try await submission.signAndExecute(
            prepared: prepared,
            driver: driver,
            partyId: party.partyId,
            keyFingerprint: party.publicKeyFingerprint,
            userId: userId
        )
    }

    private func requireRegistry() throws -> TransferRegistryClient {
        guard let registry else {
            throw TransferRegistryError(
                description: "this operation needs a TransferRegistryClient; pass one to TokenStandardClient"
            )
        }
        return registry
    }

    private func activeInterfaceViews(
        partyId: String,
        interfaceId: Com_Daml_Ledger_Api_V2_Identifier
    ) async throws -> [(String, Com_Daml_Ledger_Api_V2_Record)] {
        let ledgerEnd = try await client.ledgerEnd()

        var interfaceFilter = Com_Daml_Ledger_Api_V2_InterfaceFilter()
        interfaceFilter.interfaceID = interfaceId
        interfaceFilter.includeInterfaceView = true
        var cumulative = Com_Daml_Ledger_Api_V2_CumulativeFilter()
        cumulative.interfaceFilter = interfaceFilter
        var filters = Com_Daml_Ledger_Api_V2_Filters()
        filters.cumulative = [cumulative]

        var request = Com_Daml_Ledger_Api_V2_GetActiveContractsRequest()
        request.activeAtOffset = ledgerEnd
        request.eventFormat.filtersByParty = [partyId: filters]
        request.eventFormat.verbose = false

        let frozenRequest = request
        return try await client.withServices { services in
            try await services.state.getActiveContracts(frozenRequest) { response in
                var views: [(String, Com_Daml_Ledger_Api_V2_Record)] = []
                for try await message in response.messages {
                    guard case .activeContract(let entry)? = message.contractEntry else { continue }
                    let created = entry.createdEvent
                    guard let view = created.interfaceViews.first(where: { $0.hasViewValue }) else {
                        continue
                    }
                    views.append((created.contractID, view.viewValue))
                }
                return views
            }
        }
    }
}
