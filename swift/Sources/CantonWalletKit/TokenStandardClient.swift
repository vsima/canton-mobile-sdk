// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

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

    /// One committed update's effect on a party's holdings: created holdings
    /// carry full views (credits); archived holdings surface as contract ids,
    /// and — when their creation was seen earlier in the stream — as resolved
    /// ``archived`` holdings with amounts and owners. ``summary`` is the
    /// transfer-level reading (direction, counterparty, signed net amount,
    /// memo) when one is derivable.
    public struct HoldingsChange: Sendable {
        public let updateId: String
        public let offset: Int64
        public let recordTime: Date
        public let created: [Holding]
        public let archivedContractIds: [String]

        /// The archived holdings resolved against creations seen since ledger
        /// begin. An entry of ``archivedContractIds`` is missing here only
        /// when its creation predates the participant's retained history.
        public let archived: [Holding]

        /// Transfer-level reading of this update, or nil when none is
        /// derivable (e.g. the update touches several instruments at once).
        public let summary: TransferSummary?
    }

    /// The party's holdings history between two offsets: every committed
    /// update that created or archived one of its CIP-0056 holdings, oldest
    /// first, with transfer-level ``HoldingsChange/summary`` rows. Defaults
    /// to genesis → current ledger end (a finite read).
    ///
    /// Implementation note: archive events carry no payload, so the stream is
    /// always walked from ledger begin — creations seen along the way resolve
    /// later archives (holding amounts/owners, transfer views of accepted
    /// instructions) even when `beginExclusive` > 0; only updates past
    /// `beginExclusive` are returned. On a pruned participant the walk starts
    /// at the retained history's begin, and archives of pre-retention
    /// holdings surface as bare contract ids.
    public func holdingsHistory(
        partyId: String,
        beginExclusive: Int64 = 0,
        endInclusive: Int64? = nil
    ) async throws -> [HoldingsChange] {
        let end: Int64
        if let endInclusive {
            end = endInclusive
        } else {
            end = try await client.ledgerEnd()
        }
        guard end > beginExclusive else { return [] }

        var transactionFormat = Com_Daml_Ledger_Api_V2_TransactionFormat()
        transactionFormat.eventFormat = interfaceEventFormat(
            partyId: partyId,
            interfaceIds: [
                TokenStandard.holdingInterfaceID,
                TokenStandard.transferInstructionInterfaceID,
            ]
        )
        transactionFormat.transactionShape = .acsDelta
        var request = Com_Daml_Ledger_Api_V2_GetUpdatesRequest()
        request.beginExclusive = 0
        request.endInclusive = end
        request.updateFormat.includeTransactions = transactionFormat

        let frozenRequest = request
        return try await client.withServices { services in
            try await services.update.getUpdates(frozenRequest) { response in
                var holdingsByCid: [String: Holding] = [:]
                var instructionsByCid: [String: TransferInstruction] = [:]
                var changes: [HoldingsChange] = []
                for try await message in response.messages {
                    guard case .transaction(let transaction)? = message.update else { continue }
                    var created: [Holding] = []
                    var archived: [Holding] = []
                    var archivedCids: [String] = []
                    var instructions: [TransferInstruction] = []
                    for event in transaction.events {
                        switch event.event {
                        case .created(let event):
                            for view in event.interfaceViews where view.hasViewValue {
                                if view.interfaceID.sameEntity(TokenStandard.holdingInterfaceID) {
                                    let holding = try Holding.fromView(
                                        contractId: event.contractID, view: view.viewValue
                                    )
                                    holdingsByCid[event.contractID] = holding
                                    created.append(holding)
                                } else if view.interfaceID.sameEntity(
                                    TokenStandard.transferInstructionInterfaceID
                                ) {
                                    let instruction = try TransferInstruction.fromView(
                                        contractId: event.contractID, view: view.viewValue
                                    )
                                    instructionsByCid[event.contractID] = instruction
                                    instructions.append(instruction)
                                }
                            }
                        case .archived(let event):
                            if let holding = holdingsByCid.removeValue(forKey: event.contractID) {
                                archived.append(holding)
                                archivedCids.append(event.contractID)
                            } else if let instruction = instructionsByCid.removeValue(
                                forKey: event.contractID
                            ) {
                                instructions.append(instruction)
                            } else if !event.implementedInterfaces.isEmpty,
                                !event.implementedInterfaces.contains(where: {
                                    $0.sameEntity(TokenStandard.holdingInterfaceID)
                                })
                            {
                                // Unresolvable archive that the ledger says was
                                // solely a transfer instruction: not a holding.
                            } else {
                                archivedCids.append(event.contractID)
                            }
                        default:
                            break
                        }
                    }
                    guard transaction.offset > beginExclusive else { continue }
                    guard !created.isEmpty || !archivedCids.isEmpty else { continue }
                    changes.append(
                        HoldingsChange(
                            updateId: transaction.updateID,
                            offset: transaction.offset,
                            recordTime: Date(
                                timeIntervalSince1970: Double(transaction.recordTime.seconds)
                                    + Double(transaction.recordTime.nanos) / 1_000_000_000
                            ),
                            created: created,
                            archivedContractIds: archivedCids,
                            archived: archived,
                            summary: try summarizeTransfer(
                                partyId: partyId,
                                created: created,
                                archived: archived,
                                instructions: instructions
                            )
                        )
                    )
                }
                return changes
            }
        }
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

    /// Requests a transfer preapproval for `party` (externally signed): once
    /// `provider` — typically the party's validator operator — accepts and
    /// pays, transfers to this party settle directly with no inbox
    /// round-trip. Track acceptance via
    /// ``ScanClient/transferPreapprovalByParty(_:)``.
    public func requestTransferPreapproval(
        driver: any SigningDriver,
        party: AllocatedExternalParty,
        provider: String,
        dso: String,
        synchronizerId: String,
        userId: String? = nil
    ) async throws {
        var create = Com_Daml_Ledger_Api_V2_CreateCommand()
        create.templateID = SpliceWallet.transferPreapprovalProposalTemplateID
        create.createArguments = .of([
            "receiver": .party(party.partyId),
            "provider": .party(provider),
            "expectedDso": .optional(.party(dso)),
        ])
        var command = Com_Daml_Ledger_Api_V2_Command()
        command.create = create

        let submission = InteractiveSubmissionClient(client: client)
        let prepared = try await submission.prepare(
            commands: [command],
            actAs: party.partyId,
            synchronizerId: synchronizerId,
            userId: userId
        )
        try await submission.signAndExecute(
            prepared: prepared,
            driver: driver,
            partyId: party.partyId,
            keyFingerprint: party.publicKeyFingerprint,
            userId: userId
        )
    }

    /// Cancels the party's active preapproval — the receiver archives it
    /// unilaterally (`TransferPreapproval_Cancel`), signed on-device. No
    /// registry context needed: the receiver is a signatory, so the contract
    /// is in its ACS.
    public func cancelTransferPreapproval(
        driver: any SigningDriver,
        party: AllocatedExternalParty,
        preapprovalCid: String,
        synchronizerId: String,
        userId: String? = nil
    ) async throws {
        var exercise = Com_Daml_Ledger_Api_V2_ExerciseCommand()
        exercise.templateID = SpliceAmulet.transferPreapprovalTemplateID
        exercise.contractID = preapprovalCid
        exercise.choice = "TransferPreapproval_Cancel"
        exercise.choiceArgument = .record([
            "p": .party(party.partyId)
        ])

        try await signAndSubmit(
            driver: driver,
            party: party,
            exercise: exercise,
            synchronizerId: synchronizerId,
            userId: userId,
            disclosed: []
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

    private func interfaceEventFormat(
        partyId: String,
        interfaceIds: [Com_Daml_Ledger_Api_V2_Identifier]
    ) -> Com_Daml_Ledger_Api_V2_EventFormat {
        var filters = Com_Daml_Ledger_Api_V2_Filters()
        filters.cumulative = interfaceIds.map { interfaceId in
            var interfaceFilter = Com_Daml_Ledger_Api_V2_InterfaceFilter()
            interfaceFilter.interfaceID = interfaceId
            interfaceFilter.includeInterfaceView = true
            var cumulative = Com_Daml_Ledger_Api_V2_CumulativeFilter()
            cumulative.interfaceFilter = interfaceFilter
            return cumulative
        }

        var eventFormat = Com_Daml_Ledger_Api_V2_EventFormat()
        eventFormat.filtersByParty = [partyId: filters]
        // Non-verbose values omit record field labels, which the view
        // decoders match on.
        eventFormat.verbose = true
        return eventFormat
    }

    private func activeInterfaceViews(
        partyId: String,
        interfaceId: Com_Daml_Ledger_Api_V2_Identifier
    ) async throws -> [(String, Com_Daml_Ledger_Api_V2_Record)] {
        let ledgerEnd = try await client.ledgerEnd()

        var request = Com_Daml_Ledger_Api_V2_GetActiveContractsRequest()
        request.activeAtOffset = ledgerEnd
        request.eventFormat = interfaceEventFormat(partyId: partyId, interfaceIds: [interfaceId])

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

extension Com_Daml_Ledger_Api_V2_Identifier {
    /// Requests carry `#package-name` references while responses carry
    /// resolved package ids, so interface identity is matched on
    /// module + entity.
    func sameEntity(_ other: Com_Daml_Ledger_Api_V2_Identifier) -> Bool {
        moduleName == other.moduleName && entityName == other.entityName
    }
}
