// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet

import java.math.BigDecimal

/**
 * How one committed update moved value relative to the wallet party — the
 * label a wallet renders next to a history row.
 */
public enum class TransferDirection {
    /** The wallet party sent value to [TransferSummary.counterparty]. */
    SENT,

    /** The wallet party received value from [TransferSummary.counterparty]. */
    RECEIVED,

    /** An explicit transfer whose sender and receiver are both the wallet party. */
    SELF_TRANSFER,

    /**
     * Only the wallet party's own holdings were rearranged — merges, splits,
     * fee burns. No transfer (and no counterparty) is visible in the update.
     */
    INTERNAL,

    /**
     * The cause isn't visible from the wallet party's vantage point. Bare
     * credits with no visible source (tap mints, direct/preapproved receives
     * that carry no transfer view) and bare debits land here.
     */
    UNKNOWN,
}

/**
 * Transfer-level reading of one holdings-history update: direction,
 * counterparty, the signed net effect on the wallet party's balance, and the
 * memo when the transfer carried one.
 *
 * Derived from the update's TransferInstruction interface views when present
 * (sender/receiver/memo are then explicit) and from per-owner holding deltas
 * otherwise. [amount] is the net of the party's created minus archived
 * holdings for [instrumentId] — for a sender this is more negative than the
 * transfer amount because fees ride on the same update.
 */
public data class TransferSummary(
    /** How the update moved value relative to the wallet party. */
    val direction: TransferDirection,
    /**
     * The other party of the transfer — receiver when [direction] is
     * [TransferDirection.SENT], sender when [TransferDirection.RECEIVED],
     * null when no counterparty is determinable.
     */
    val counterparty: String?,
    /** The instrument whose balance the update changed. */
    val instrumentId: InstrumentId,
    /**
     * Signed net effect on the wallet party's balance for [instrumentId]:
     * credits positive, debits negative. Fee-inclusive by construction.
     */
    val amount: BigDecimal,
    /**
     * The transfer's human-readable reason, from the
     * `splice.lfdecentralizedtrust.org/reason` metadata key when present.
     */
    val memo: String?,
)

/**
 * Derives the transfer-level summary of one committed update, or null when
 * the update touches more than one instrument (the raw created/archived
 * lists still carry everything in that case).
 *
 * Preference order: a TransferInstruction view involving the party (created
 * or archived in the update) pins sender/receiver/memo exactly; otherwise
 * direction is inferred from per-owner holding deltas.
 */
internal fun summarizeTransfer(
    partyId: String,
    created: List<Holding>,
    archived: List<Holding>,
    instructions: List<TransferInstruction>,
): TransferSummary? {
    val instrument = (created + archived).map { it.instrumentId }.distinct().singleOrNull()
        ?: return null
    val net = created.filter { it.owner == partyId }.sumOf { it.amount } -
        archived.filter { it.owner == partyId }.sumOf { it.amount }
    val holdingsMemo = (created + archived)
        .firstNotNullOfOrNull { it.meta[TokenStandard.reasonMetadataKey] }

    val instruction = instructions.firstOrNull {
        it.transfer.instrumentId == instrument &&
            (partyId == it.transfer.sender || partyId == it.transfer.receiver)
    }
    if (instruction != null) {
        val transfer = instruction.transfer
        val direction = when {
            transfer.sender == transfer.receiver -> TransferDirection.SELF_TRANSFER
            partyId == transfer.sender -> TransferDirection.SENT
            else -> TransferDirection.RECEIVED
        }
        return TransferSummary(
            direction = direction,
            counterparty = when (direction) {
                TransferDirection.SENT -> transfer.receiver
                TransferDirection.RECEIVED -> transfer.sender
                else -> null
            },
            instrumentId = instrument,
            amount = net,
            memo = transfer.meta[TokenStandard.reasonMetadataKey]
                ?: instruction.meta[TokenStandard.reasonMetadataKey]
                ?: holdingsMemo,
        )
    }

    // No transfer view: infer from who else appears in the update's deltas.
    val otherCreatedOwners = created.map { it.owner }.filter { it != partyId }.distinct()
    val otherArchivedOwners = archived.map { it.owner }.filter { it != partyId }.distinct()
    val ownCreated = created.any { it.owner == partyId }
    val ownArchived = archived.any { it.owner == partyId }
    val direction = when {
        net.signum() < 0 && otherCreatedOwners.size == 1 -> TransferDirection.SENT
        net.signum() > 0 && otherArchivedOwners.size == 1 -> TransferDirection.RECEIVED
        otherCreatedOwners.isEmpty() && otherArchivedOwners.isEmpty() &&
            ownCreated && ownArchived -> TransferDirection.INTERNAL
        else -> TransferDirection.UNKNOWN
    }
    return TransferSummary(
        direction = direction,
        counterparty = when (direction) {
            TransferDirection.SENT -> otherCreatedOwners.single()
            TransferDirection.RECEIVED -> otherArchivedOwners.single()
            else -> null
        },
        instrumentId = instrument,
        amount = net,
        memo = holdingsMemo,
    )
}
