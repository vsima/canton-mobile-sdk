// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet

import com.daml.ledger.api.v2.CommandsOuterClass
import com.daml.ledger.api.v2.StateServiceGrpcKt
import com.daml.ledger.api.v2.StateServiceOuterClass.GetActiveContractsRequest
import com.daml.ledger.api.v2.StateServiceOuterClass.GetActiveContractsResponse
import com.daml.ledger.api.v2.StateServiceOuterClass.GetLedgerEndRequest
import com.daml.ledger.api.v2.TransactionFilterOuterClass
import com.daml.ledger.api.v2.UpdateServiceGrpcKt
import com.daml.ledger.api.v2.UpdateServiceOuterClass.GetUpdatesRequest
import com.daml.ledger.api.v2.ValueOuterClass
import io.github.vsima.canton.DamlValues
import io.grpc.Channel
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.toList

/**
 * CIP-0056 token standard client: holdings, two-step transfers, and the
 * pending-instruction inbox — the read/write surface a wallet renders.
 *
 * Reads go straight to the ledger (ACS filtered by the standard's
 * interfaces, so any compliant asset shows up with no per-asset
 * integration). Writes are externally signed: registry context via
 * [TransferRegistryClient], then prepare → sign → execute through
 * [InteractiveSubmissionClient].
 *
 * Tracking: CIP-0112 (Token Standard V2 — batch settlement, account-based
 * holdings) will extend this surface; V1 remains the interop baseline.
 */
public class TokenStandardClient(
    channel: Channel,
    private val registry: TransferRegistryClient? = null,
) {
    private val state = StateServiceGrpcKt.StateServiceCoroutineStub(channel)
    private val update = UpdateServiceGrpcKt.UpdateServiceCoroutineStub(channel)
    private val submission = InteractiveSubmissionClient(channel)

    /**
     * One committed update's effect on a party's holdings: created holdings
     * carry full views (credits); archived holdings surface as contract ids —
     * resolve amounts against previously-seen state (the wallet's local UTXO
     * set), which ACS-delta streams keep consistent by construction.
     */
    public data class HoldingsChange(
        val updateId: String,
        val offset: Long,
        val recordTime: Instant,
        val created: List<Holding>,
        val archivedContractIds: List<String>,
    )

    /** Active holding UTXOs visible to [partyId], any CIP-0056 instrument. */
    public suspend fun listHoldings(partyId: String): List<Holding> =
        activeInterfaceViews(partyId, TokenStandard.holdingInterfaceId)
            .map { (contractId, view) -> holdingFromView(contractId, view) }

    /**
     * Pending two-step transfers visible to [partyId] — the wallet inbox.
     * The receiver acts on `TransferPendingReceiverAcceptance` entries; the
     * sender may withdraw anything still pending.
     */
    public suspend fun pendingTransferInstructions(partyId: String): List<TransferInstruction> =
        activeInterfaceViews(partyId, TokenStandard.transferInstructionInterfaceId)
            .map { (contractId, view) -> transferInstructionFromView(contractId, view) }

    /**
     * The party's holdings history between two offsets: every committed
     * update that created or archived one of its CIP-0056 holdings, oldest
     * first. Defaults to genesis → current ledger end (a finite read).
     */
    public suspend fun holdingsHistory(
        partyId: String,
        beginExclusive: Long = 0,
        endInclusive: Long? = null,
    ): List<HoldingsChange> {
        val end = endInclusive
            ?: state.getLedgerEnd(GetLedgerEndRequest.getDefaultInstance()).offset
        if (end <= beginExclusive) return emptyList()

        val request = GetUpdatesRequest.newBuilder()
            .setBeginExclusive(beginExclusive)
            .setEndInclusive(end)
            .setUpdateFormat(
                TransactionFilterOuterClass.UpdateFormat.newBuilder()
                    .setIncludeTransactions(
                        TransactionFilterOuterClass.TransactionFormat.newBuilder()
                            .setEventFormat(interfaceEventFormat(partyId, TokenStandard.holdingInterfaceId))
                            .setTransactionShape(
                                TransactionFilterOuterClass.TransactionShape.TRANSACTION_SHAPE_ACS_DELTA
                            )
                    )
            )
            .build()

        return update.getUpdates(request).toList().mapNotNull { response ->
            if (!response.hasTransaction()) return@mapNotNull null
            val transaction = response.transaction
            val created = mutableListOf<Holding>()
            val archived = mutableListOf<String>()
            for (event in transaction.eventsList) {
                when {
                    event.hasCreated() -> {
                        val view = event.created.interfaceViewsList.firstOrNull { it.hasViewValue() }
                            ?: continue
                        created += holdingFromView(event.created.contractId, view.viewValue)
                    }
                    event.hasArchived() -> archived += event.archived.contractId
                }
            }
            if (created.isEmpty() && archived.isEmpty()) return@mapNotNull null
            HoldingsChange(
                updateId = transaction.updateId,
                offset = transaction.offset,
                recordTime = Instant.ofEpochSecond(
                    transaction.recordTime.seconds,
                    transaction.recordTime.nanos.toLong(),
                ),
                created = created,
                archivedContractIds = archived,
            )
        }
    }

    /**
     * Initiates a transfer as [party] (externally signed). Returns the
     * update id is not yet surfaced — track completion via the instruction
     * appearing in the receiver's inbox or the sender's ACS delta.
     */
    public suspend fun createTransfer(
        driver: SigningDriver,
        party: AllocatedExternalParty,
        receiver: String,
        instrumentId: InstrumentId,
        amount: BigDecimal,
        inputHoldingCids: List<String>,
        synchronizerId: String,
        userId: String? = null,
        meta: Map<String, String> = emptyMap(),
        requestedAt: Instant = Instant.now(),
        executeBefore: Instant = Instant.now().plus(Duration.ofHours(24)),
    ) {
        val registry = requireRegistry()
        val transfer = Transfer(
            sender = party.partyId,
            receiver = receiver,
            amount = amount,
            instrumentId = instrumentId,
            requestedAt = requestedAt,
            executeBefore = executeBefore,
            inputHoldingCids = inputHoldingCids,
            meta = meta,
        )

        val factory = registry.transferFactory(
            ChoiceContextJson.transferFactoryChoiceArguments(instrumentId.admin, transfer)
        )

        val exercise = CommandsOuterClass.Command.newBuilder()
            .setExercise(
                CommandsOuterClass.ExerciseCommand.newBuilder()
                    .setTemplateId(TokenStandard.transferFactoryInterfaceId)
                    .setContractId(factory.factoryId)
                    .setChoice("TransferFactory_Transfer")
                    .setChoiceArgument(
                        DamlValues.record(
                            "expectedAdmin" to DamlValues.party(instrumentId.admin),
                            "transfer" to transfer.toValue(),
                            "extraArgs" to ChoiceContextJson.extraArgsValue(
                                factory.choiceContext.choiceContextData
                            ),
                        )
                    )
            )
            .build()

        signAndSubmit(
            driver, party, exercise, synchronizerId, userId,
            factory.choiceContext.disclosedContracts,
        )
    }

    /** Accept/reject (receiver) or withdraw (sender) a pending instruction. */
    public suspend fun exerciseTransferInstruction(
        driver: SigningDriver,
        party: AllocatedExternalParty,
        transferInstructionId: String,
        choice: TransferInstructionChoice,
        synchronizerId: String,
        userId: String? = null,
    ) {
        val registry = requireRegistry()
        val context = registry.transferInstructionChoiceContext(transferInstructionId, choice)

        val exercise = CommandsOuterClass.Command.newBuilder()
            .setExercise(
                CommandsOuterClass.ExerciseCommand.newBuilder()
                    .setTemplateId(TokenStandard.transferInstructionInterfaceId)
                    .setContractId(transferInstructionId)
                    .setChoice(choice.choiceName)
                    .setChoiceArgument(
                        DamlValues.record(
                            "extraArgs" to ChoiceContextJson.extraArgsValue(context.choiceContextData)
                        )
                    )
            )
            .build()

        signAndSubmit(driver, party, exercise, synchronizerId, userId, context.disclosedContracts)
    }

    private suspend fun signAndSubmit(
        driver: SigningDriver,
        party: AllocatedExternalParty,
        command: CommandsOuterClass.Command,
        synchronizerId: String,
        userId: String?,
        disclosed: List<TransferRegistryClient.RegistryDisclosedContract>,
    ) {
        val prepared = submission.prepare(
            commands = listOf(command),
            actAs = party.partyId,
            synchronizerId = synchronizerId,
            userId = userId,
            disclosedContracts = disclosed.map { it.toProto() },
        )
        submission.signAndExecute(
            prepared = prepared,
            driver = driver,
            partyId = party.partyId,
            keyFingerprint = party.publicKeyFingerprint,
            userId = userId,
        )
    }

    private fun requireRegistry(): TransferRegistryClient =
        registry ?: throw IllegalStateException(
            "this operation needs a TransferRegistryClient; pass one to TokenStandardClient"
        )

    private fun interfaceEventFormat(
        partyId: String,
        interfaceId: ValueOuterClass.Identifier,
    ): TransactionFilterOuterClass.EventFormat {
        val filters = TransactionFilterOuterClass.Filters.newBuilder()
            .addCumulative(
                TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                    .setInterfaceFilter(
                        TransactionFilterOuterClass.InterfaceFilter.newBuilder()
                            .setInterfaceId(interfaceId)
                            .setIncludeInterfaceView(true)
                    )
            )
            .build()
        return TransactionFilterOuterClass.EventFormat.newBuilder()
            .putFiltersByParty(partyId, filters)
            // Non-verbose values omit record field labels, which the view
            // decoders match on.
            .setVerbose(true)
            .build()
    }

    private suspend fun activeInterfaceViews(
        partyId: String,
        interfaceId: ValueOuterClass.Identifier,
    ): List<Pair<String, ValueOuterClass.Record>> {
        val ledgerEnd = state.getLedgerEnd(GetLedgerEndRequest.getDefaultInstance()).offset
        val request = GetActiveContractsRequest.newBuilder()
            .setActiveAtOffset(ledgerEnd)
            .setEventFormat(interfaceEventFormat(partyId, interfaceId))
            .build()

        return state.getActiveContracts(request).toList().mapNotNull { response ->
            if (response.contractEntryCase !=
                GetActiveContractsResponse.ContractEntryCase.ACTIVE_CONTRACT
            ) {
                return@mapNotNull null
            }
            val created = response.activeContract.createdEvent
            val view = created.interfaceViewsList.firstOrNull { it.hasViewValue() }
                ?: return@mapNotNull null
            created.contractId to view.viewValue
        }
    }
}
