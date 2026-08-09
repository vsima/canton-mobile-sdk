// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure inference tests for [summarizeTransfer] — direction, counterparty,
 * signed net amounts, and memo extraction. No ledger involved; the Swift
 * twin (`TransferSemanticsTests`) runs the same scenarios.
 */
class TransferSemanticsTest {

    private val wallet = "wallet::1220aa"
    private val other = "other::1220bb"
    private val amulet = InstrumentId("dso::1220cc", "Amulet")
    private val otherInstrument = InstrumentId("dso::1220cc", "OtherToken")

    private var cidCounter = 0

    private fun holding(
        owner: String,
        amount: String,
        instrument: InstrumentId = amulet,
        meta: Map<String, String> = emptyMap(),
    ) = Holding(
        contractId = "00cid${cidCounter++}",
        owner = owner,
        instrumentId = instrument,
        amount = BigDecimal(amount),
        lock = null,
        meta = meta,
    )

    private fun instruction(
        sender: String,
        receiver: String,
        amount: String,
        transferMeta: Map<String, String> = emptyMap(),
        instructionMeta: Map<String, String> = emptyMap(),
    ) = TransferInstruction(
        contractId = "00instr${cidCounter++}",
        originalInstructionCid = null,
        transfer = Transfer(
            sender = sender,
            receiver = receiver,
            amount = BigDecimal(amount),
            instrumentId = amulet,
            requestedAt = Instant.parse("2026-08-07T10:00:00Z"),
            executeBefore = Instant.parse("2026-08-08T10:00:00Z"),
            inputHoldingCids = emptyList(),
            meta = transferMeta,
        ),
        status = TransferInstructionStatus.PendingReceiverAcceptance,
        meta = instructionMeta,
    )

    private val memoMeta = mapOf(TokenStandard.reasonMetadataKey to "Invoice #4021")

    // -- transfer view present ---------------------------------------------

    @Test
    fun `sender settling an accepted instruction is a fee-inclusive SENT debit`() {
        val summary = summarizeTransfer(
            partyId = wallet,
            // The sender's locked holding is archived; the receiver's created
            // holding is not visible to the sender.
            created = emptyList(),
            archived = listOf(holding(wallet, "5.5")),
            instructions = listOf(instruction(wallet, other, "5.0", transferMeta = memoMeta)),
        )!!
        assertEquals(TransferDirection.SENT, summary.direction)
        assertEquals(other, summary.counterparty)
        assertEquals(amulet, summary.instrumentId)
        assertEquals(0, BigDecimal("-5.5").compareTo(summary.amount))
        assertEquals("Invoice #4021", summary.memo)
    }

    @Test
    fun `receiver of an accepted instruction is a RECEIVED credit with the memo`() {
        val summary = summarizeTransfer(
            partyId = other,
            created = listOf(holding(other, "5.0")),
            archived = emptyList(),
            instructions = listOf(instruction(wallet, other, "5.0", transferMeta = memoMeta)),
        )!!
        assertEquals(TransferDirection.RECEIVED, summary.direction)
        assertEquals(wallet, summary.counterparty)
        assertEquals(0, BigDecimal("5.0").compareTo(summary.amount))
        assertEquals("Invoice #4021", summary.memo)
    }

    @Test
    fun `sender creating an offer only pays fees but is already SENT`() {
        // Offer creation: inputs archived, change + locked holdings created,
        // net is just the fees.
        val summary = summarizeTransfer(
            partyId = wallet,
            created = listOf(holding(wallet, "94.4"), holding(wallet, "5.5")),
            archived = listOf(holding(wallet, "100.0")),
            instructions = listOf(instruction(wallet, other, "5.0", transferMeta = memoMeta)),
        )!!
        assertEquals(TransferDirection.SENT, summary.direction)
        assertEquals(other, summary.counterparty)
        assertEquals(0, BigDecimal("-0.1").compareTo(summary.amount))
        assertEquals("Invoice #4021", summary.memo)
    }

    @Test
    fun `explicit self-transfer is SELF_TRANSFER with no counterparty`() {
        val summary = summarizeTransfer(
            partyId = wallet,
            created = listOf(holding(wallet, "4.9")),
            archived = listOf(holding(wallet, "5.0")),
            instructions = listOf(instruction(wallet, wallet, "5.0")),
        )!!
        assertEquals(TransferDirection.SELF_TRANSFER, summary.direction)
        assertNull(summary.counterparty)
        assertEquals(0, BigDecimal("-0.1").compareTo(summary.amount))
    }

    @Test
    fun `memo falls back to the instruction meta when the transfer carries none`() {
        val summary = summarizeTransfer(
            partyId = wallet,
            created = emptyList(),
            archived = listOf(holding(wallet, "5.0")),
            instructions = listOf(instruction(wallet, other, "5.0", instructionMeta = memoMeta)),
        )!!
        assertEquals("Invoice #4021", summary.memo)
    }

    @Test
    fun `an instruction between two other parties does not pin the direction`() {
        // The wallet only observes a transfer between others (e.g. as a lock
        // holder); its own deltas still decide.
        val summary = summarizeTransfer(
            partyId = wallet,
            created = listOf(holding(wallet, "4.0")),
            archived = listOf(holding(wallet, "5.0")),
            instructions = listOf(instruction(other, "third::1220dd", "5.0")),
        )!!
        assertEquals(TransferDirection.INTERNAL, summary.direction)
        assertNull(summary.counterparty)
    }

    // -- delta inference ----------------------------------------------------

    @Test
    fun `negative net with one other party credited infers SENT`() {
        val summary = summarizeTransfer(
            partyId = wallet,
            created = listOf(holding(other, "5.0"), holding(wallet, "94.9")),
            archived = listOf(holding(wallet, "100.0")),
            instructions = emptyList(),
        )!!
        assertEquals(TransferDirection.SENT, summary.direction)
        assertEquals(other, summary.counterparty)
        assertEquals(0, BigDecimal("-5.1").compareTo(summary.amount))
        assertNull(summary.memo)
    }

    @Test
    fun `positive net with one other party debited infers RECEIVED`() {
        val summary = summarizeTransfer(
            partyId = wallet,
            created = listOf(holding(wallet, "5.0")),
            archived = listOf(holding(other, "5.5")),
            instructions = emptyList(),
        )!!
        assertEquals(TransferDirection.RECEIVED, summary.direction)
        assertEquals(other, summary.counterparty)
        assertEquals(0, BigDecimal("5.0").compareTo(summary.amount))
    }

    @Test
    fun `own-only merge is INTERNAL with the fee as net`() {
        val summary = summarizeTransfer(
            partyId = wallet,
            created = listOf(holding(wallet, "9.9")),
            archived = listOf(holding(wallet, "4.0"), holding(wallet, "6.0")),
            instructions = emptyList(),
        )!!
        assertEquals(TransferDirection.INTERNAL, summary.direction)
        assertNull(summary.counterparty)
        assertEquals(0, BigDecimal("-0.1").compareTo(summary.amount))
    }

    @Test
    fun `a bare credit with no visible source is UNKNOWN`() {
        // Tap mints and direct/preapproved receives look identical from the
        // receiver's vantage point: one created holding, nothing else.
        val summary = summarizeTransfer(
            partyId = wallet,
            created = listOf(holding(wallet, "777.0")),
            archived = emptyList(),
            instructions = emptyList(),
        )!!
        assertEquals(TransferDirection.UNKNOWN, summary.direction)
        assertNull(summary.counterparty)
        assertEquals(0, BigDecimal("777.0").compareTo(summary.amount))
    }

    @Test
    fun `a memo riding on a created holding surfaces without a transfer view`() {
        val summary = summarizeTransfer(
            partyId = wallet,
            created = listOf(holding(wallet, "5.0", meta = memoMeta)),
            archived = emptyList(),
            instructions = emptyList(),
        )!!
        assertEquals(TransferDirection.UNKNOWN, summary.direction)
        assertEquals("Invoice #4021", summary.memo)
    }

    @Test
    fun `updates spanning several instruments yield no summary`() {
        assertNull(
            summarizeTransfer(
                partyId = wallet,
                created = listOf(holding(wallet, "5.0"), holding(wallet, "1.0", otherInstrument)),
                archived = emptyList(),
                instructions = emptyList(),
            )
        )
    }
}
