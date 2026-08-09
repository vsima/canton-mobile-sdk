// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// How one committed update moved value relative to the wallet party — the
/// label a wallet renders next to a history row.
public enum TransferDirection: String, Sendable, Equatable {
    /// The wallet party sent value to ``TransferSummary/counterparty``.
    case sent

    /// The wallet party received value from ``TransferSummary/counterparty``.
    case received

    /// An explicit transfer whose sender and receiver are both the wallet party.
    case selfTransfer

    /// Only the wallet party's own holdings were rearranged — merges, splits,
    /// fee burns. No transfer (and no counterparty) is visible in the update.
    case `internal`

    /// The cause isn't visible from the wallet party's vantage point. Bare
    /// credits with no visible source (tap mints, direct/preapproved receives
    /// that carry no transfer view) and bare debits land here.
    case unknown
}

/// Transfer-level reading of one holdings-history update: direction,
/// counterparty, the signed net effect on the wallet party's balance, and the
/// memo when the transfer carried one.
///
/// Derived from the update's TransferInstruction interface views when present
/// (sender/receiver/memo are then explicit) and from per-owner holding deltas
/// otherwise. ``amount`` is the net of the party's created minus archived
/// holdings for ``instrumentId`` — for a sender this is more negative than
/// the transfer amount because fees ride on the same update.
public struct TransferSummary: Sendable, Equatable {
    /// How the update moved value relative to the wallet party.
    public let direction: TransferDirection

    /// The other party of the transfer — receiver when ``direction`` is
    /// ``TransferDirection/sent``, sender when ``TransferDirection/received``,
    /// nil when no counterparty is determinable.
    public let counterparty: String?

    /// The instrument whose balance the update changed.
    public let instrumentId: InstrumentId

    /// Signed net effect on the wallet party's balance for ``instrumentId``
    /// as a decimal string: credits positive, debits negative. Fee-inclusive
    /// by construction.
    public let amount: String

    /// The transfer's human-readable reason, from the
    /// `splice.lfdecentralizedtrust.org/reason` metadata key when present.
    public let memo: String?
}

extension TokenStandard {
    /// The CIP-0056 metadata key carrying a transfer's human-readable reason
    /// (memo). Set it in `Transfer.meta` when creating a transfer; surfaced
    /// as ``TransferSummary/memo`` in holdings history.
    public static let reasonMetadataKey = "splice.lfdecentralizedtrust.org/reason"
}

/// Derives the transfer-level summary of one committed update, or nil when
/// the update touches more than one instrument (the raw created/archived
/// lists still carry everything in that case).
///
/// Preference order: a TransferInstruction view involving the party (created
/// or archived in the update) pins sender/receiver/memo exactly; otherwise
/// direction is inferred from per-owner holding deltas.
func summarizeTransfer(
    partyId: String,
    created: [Holding],
    archived: [Holding],
    instructions: [TransferInstruction]
) throws -> TransferSummary? {
    var instruments: [InstrumentId] = []
    for holding in created + archived where !instruments.contains(holding.instrumentId) {
        instruments.append(holding.instrumentId)
    }
    guard instruments.count == 1, let instrument = instruments.first else { return nil }

    func decimal(_ text: String) throws -> Decimal {
        guard let value = Decimal(string: text, locale: Locale(identifier: "en_US_POSIX")) else {
            throw WalletDecodeError("malformed numeric in holding amount: \(text)")
        }
        return value
    }
    var net = Decimal(0)
    for holding in created where holding.owner == partyId { net += try decimal(holding.amount) }
    for holding in archived where holding.owner == partyId { net -= try decimal(holding.amount) }
    let holdingsMemo = (created + archived)
        .lazy.compactMap { $0.meta[TokenStandard.reasonMetadataKey] }.first

    if let instruction = instructions.first(where: {
        $0.transfer.instrumentId == instrument
            && (partyId == $0.transfer.sender || partyId == $0.transfer.receiver)
    }) {
        let transfer = instruction.transfer
        let direction: TransferDirection =
            if transfer.sender == transfer.receiver {
                .selfTransfer
            } else if partyId == transfer.sender {
                .sent
            } else {
                .received
            }
        let counterparty: String? =
            switch direction {
            case .sent: transfer.receiver
            case .received: transfer.sender
            default: nil
            }
        return TransferSummary(
            direction: direction,
            counterparty: counterparty,
            instrumentId: instrument,
            amount: "\(net)",
            memo: transfer.meta[TokenStandard.reasonMetadataKey]
                ?? instruction.meta[TokenStandard.reasonMetadataKey]
                ?? holdingsMemo
        )
    }

    // No transfer view: infer from who else appears in the update's deltas.
    var otherCreatedOwners: [String] = []
    for holding in created
    where holding.owner != partyId && !otherCreatedOwners.contains(holding.owner) {
        otherCreatedOwners.append(holding.owner)
    }
    var otherArchivedOwners: [String] = []
    for holding in archived
    where holding.owner != partyId && !otherArchivedOwners.contains(holding.owner) {
        otherArchivedOwners.append(holding.owner)
    }
    let ownCreated = created.contains { $0.owner == partyId }
    let ownArchived = archived.contains { $0.owner == partyId }
    let direction: TransferDirection =
        if net < 0, otherCreatedOwners.count == 1 {
            .sent
        } else if net > 0, otherArchivedOwners.count == 1 {
            .received
        } else if otherCreatedOwners.isEmpty, otherArchivedOwners.isEmpty,
            ownCreated, ownArchived
        {
            .internal
        } else {
            .unknown
        }
    let counterparty: String? =
        switch direction {
        case .sent: otherCreatedOwners[0]
        case .received: otherArchivedOwners[0]
        default: nil
        }
    return TransferSummary(
        direction: direction,
        counterparty: counterparty,
        instrumentId: instrument,
        amount: "\(net)",
        memo: holdingsMemo
    )
}
