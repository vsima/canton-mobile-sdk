// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import Foundation
import Testing

@testable import CantonWalletKit

/// Pure inference tests for `summarizeTransfer` — direction, counterparty,
/// signed net amounts, and memo extraction. No ledger involved; the Kotlin
/// twin (`TransferSemanticsTest`) runs the same scenarios.
struct TransferSemanticsTests {

    private let wallet = "wallet::1220aa"
    private let other = "other::1220bb"
    private let amulet = InstrumentId(admin: "dso::1220cc", id: "Amulet")
    private let otherInstrument = InstrumentId(admin: "dso::1220cc", id: "OtherToken")
    private let memoMeta = [TokenStandard.reasonMetadataKey: "Invoice #4021"]

    private func holding(
        _ owner: String,
        _ amount: String,
        instrument: InstrumentId? = nil,
        meta: [String: String] = [:]
    ) -> Holding {
        Holding(
            contractId: "00cid\(UUID().uuidString)",
            owner: owner,
            instrumentId: instrument ?? amulet,
            amount: amount,
            lock: nil,
            meta: meta
        )
    }

    private func instruction(
        sender: String,
        receiver: String,
        amount: String,
        transferMeta: [String: String] = [:],
        instructionMeta: [String: String] = [:]
    ) -> TransferInstruction {
        TransferInstruction(
            contractId: "00instr\(UUID().uuidString)",
            originalInstructionCid: nil,
            transfer: Transfer(
                sender: sender,
                receiver: receiver,
                amount: amount,
                instrumentId: amulet,
                requestedAt: Date(timeIntervalSince1970: 1_786_096_800),
                executeBefore: Date(timeIntervalSince1970: 1_786_183_200),
                inputHoldingCids: [],
                meta: transferMeta
            ),
            status: .pendingReceiverAcceptance,
            meta: instructionMeta
        )
    }

    // MARK: - transfer view present

    @Test func senderSettlingAnAcceptedInstructionIsAFeeInclusiveSentDebit() throws {
        let summary = try #require(
            try summarizeTransfer(
                partyId: wallet,
                // The sender's locked holding is archived; the receiver's
                // created holding is not visible to the sender.
                created: [],
                archived: [holding(wallet, "5.5")],
                instructions: [
                    instruction(sender: wallet, receiver: other, amount: "5.0", transferMeta: memoMeta)
                ]
            )
        )
        #expect(summary.direction == .sent)
        #expect(summary.counterparty == other)
        #expect(summary.instrumentId == amulet)
        #expect(Decimal(string: summary.amount) == Decimal(string: "-5.5"))
        #expect(summary.memo == "Invoice #4021")
    }

    @Test func receiverOfAnAcceptedInstructionIsAReceivedCreditWithTheMemo() throws {
        let summary = try #require(
            try summarizeTransfer(
                partyId: other,
                created: [holding(other, "5.0")],
                archived: [],
                instructions: [
                    instruction(sender: wallet, receiver: other, amount: "5.0", transferMeta: memoMeta)
                ]
            )
        )
        #expect(summary.direction == .received)
        #expect(summary.counterparty == wallet)
        #expect(Decimal(string: summary.amount) == Decimal(string: "5.0"))
        #expect(summary.memo == "Invoice #4021")
    }

    @Test func senderCreatingAnOfferOnlyPaysFeesButIsAlreadySent() throws {
        // Offer creation: inputs archived, change + locked holdings created,
        // net is just the fees.
        let summary = try #require(
            try summarizeTransfer(
                partyId: wallet,
                created: [holding(wallet, "94.4"), holding(wallet, "5.5")],
                archived: [holding(wallet, "100.0")],
                instructions: [
                    instruction(sender: wallet, receiver: other, amount: "5.0", transferMeta: memoMeta)
                ]
            )
        )
        #expect(summary.direction == .sent)
        #expect(summary.counterparty == other)
        #expect(Decimal(string: summary.amount) == Decimal(string: "-0.1"))
        #expect(summary.memo == "Invoice #4021")
    }

    @Test func explicitSelfTransferIsSelfTransferWithNoCounterparty() throws {
        let summary = try #require(
            try summarizeTransfer(
                partyId: wallet,
                created: [holding(wallet, "4.9")],
                archived: [holding(wallet, "5.0")],
                instructions: [instruction(sender: wallet, receiver: wallet, amount: "5.0")]
            )
        )
        #expect(summary.direction == .selfTransfer)
        #expect(summary.counterparty == nil)
        #expect(Decimal(string: summary.amount) == Decimal(string: "-0.1"))
    }

    @Test func memoFallsBackToTheInstructionMetaWhenTheTransferCarriesNone() throws {
        let summary = try #require(
            try summarizeTransfer(
                partyId: wallet,
                created: [],
                archived: [holding(wallet, "5.0")],
                instructions: [
                    instruction(
                        sender: wallet, receiver: other, amount: "5.0", instructionMeta: memoMeta
                    )
                ]
            )
        )
        #expect(summary.memo == "Invoice #4021")
    }

    @Test func anInstructionBetweenTwoOtherPartiesDoesNotPinTheDirection() throws {
        // The wallet only observes a transfer between others (e.g. as a lock
        // holder); its own deltas still decide.
        let summary = try #require(
            try summarizeTransfer(
                partyId: wallet,
                created: [holding(wallet, "4.0")],
                archived: [holding(wallet, "5.0")],
                instructions: [instruction(sender: other, receiver: "third::1220dd", amount: "5.0")]
            )
        )
        #expect(summary.direction == .internal)
        #expect(summary.counterparty == nil)
    }

    // MARK: - delta inference

    @Test func negativeNetWithOneOtherPartyCreditedInfersSent() throws {
        let summary = try #require(
            try summarizeTransfer(
                partyId: wallet,
                created: [holding(other, "5.0"), holding(wallet, "94.9")],
                archived: [holding(wallet, "100.0")],
                instructions: []
            )
        )
        #expect(summary.direction == .sent)
        #expect(summary.counterparty == other)
        #expect(Decimal(string: summary.amount) == Decimal(string: "-5.1"))
        #expect(summary.memo == nil)
    }

    @Test func positiveNetWithOneOtherPartyDebitedInfersReceived() throws {
        let summary = try #require(
            try summarizeTransfer(
                partyId: wallet,
                created: [holding(wallet, "5.0")],
                archived: [holding(other, "5.5")],
                instructions: []
            )
        )
        #expect(summary.direction == .received)
        #expect(summary.counterparty == other)
        #expect(Decimal(string: summary.amount) == Decimal(string: "5.0"))
    }

    @Test func ownOnlyMergeIsInternalWithTheFeeAsNet() throws {
        let summary = try #require(
            try summarizeTransfer(
                partyId: wallet,
                created: [holding(wallet, "9.9")],
                archived: [holding(wallet, "4.0"), holding(wallet, "6.0")],
                instructions: []
            )
        )
        #expect(summary.direction == .internal)
        #expect(summary.counterparty == nil)
        #expect(Decimal(string: summary.amount) == Decimal(string: "-0.1"))
    }

    @Test func aBareCreditWithNoVisibleSourceIsUnknown() throws {
        // Tap mints and direct/preapproved receives look identical from the
        // receiver's vantage point: one created holding, nothing else.
        let summary = try #require(
            try summarizeTransfer(
                partyId: wallet,
                created: [holding(wallet, "777.0")],
                archived: [],
                instructions: []
            )
        )
        #expect(summary.direction == .unknown)
        #expect(summary.counterparty == nil)
        #expect(Decimal(string: summary.amount) == Decimal(string: "777.0"))
    }

    @Test func aMemoRidingOnACreatedHoldingSurfacesWithoutATransferView() throws {
        let summary = try #require(
            try summarizeTransfer(
                partyId: wallet,
                created: [holding(wallet, "5.0", meta: memoMeta)],
                archived: [],
                instructions: []
            )
        )
        #expect(summary.direction == .unknown)
        #expect(summary.memo == "Invoice #4021")
    }

    @Test func updatesSpanningSeveralInstrumentsYieldNoSummary() throws {
        #expect(
            try summarizeTransfer(
                partyId: wallet,
                created: [holding(wallet, "5.0"), holding(wallet, "1.0", instrument: otherInstrument)],
                archived: [],
                instructions: []
            ) == nil
        )
    }
}
