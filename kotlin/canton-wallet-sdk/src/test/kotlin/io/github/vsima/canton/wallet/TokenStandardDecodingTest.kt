package io.github.vsima.canton.wallet

import io.github.vsima.canton.DamlValues
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Decodes CIP-0056 interface views built with the same [DamlValues] builders
 * the golden vectors exercise. The Swift twin builds identical views from the
 * same field spec — cross-SDK parity by construction.
 */
class TokenStandardDecodingTest {

    private val requestedAt: Instant = Instant.parse("2026-08-07T10:00:00Z")
    private val executeBefore: Instant = Instant.parse("2026-08-08T10:00:00Z")

    @Test
    fun `decodes an unlocked holding view`() {
        val view = DamlValues.recordOf(
            "owner" to DamlValues.party("alice::1220aa"),
            "instrumentId" to DamlValues.record(
                "admin" to DamlValues.party("dso::1220bb"),
                "id" to DamlValues.text("Amulet"),
            ),
            "amount" to DamlValues.numeric("100.05"),
            "lock" to DamlValues.optional(),
            "meta" to metadataValue(mapOf("k" to "v")),
        )

        val holding = holdingFromView("00cid", view)
        assertEquals(
            Holding(
                contractId = "00cid",
                owner = "alice::1220aa",
                instrumentId = InstrumentId("dso::1220bb", "Amulet"),
                amount = BigDecimal("100.05"),
                lock = null,
                meta = mapOf("k" to "v"),
            ),
            holding,
        )
    }

    @Test
    fun `decodes a locked holding with expiry`() {
        val view = DamlValues.recordOf(
            "owner" to DamlValues.party("alice::1220aa"),
            "instrumentId" to DamlValues.record(
                "admin" to DamlValues.party("dso::1220bb"),
                "id" to DamlValues.text("Amulet"),
            ),
            "amount" to DamlValues.numeric("7.0"),
            "lock" to DamlValues.optional(
                DamlValues.record(
                    "holders" to DamlValues.list(DamlValues.party("validator::1220cc")),
                    "expiresAt" to DamlValues.optional(DamlValues.timestamp(requestedAt)),
                    "expiresAfter" to DamlValues.optional(
                        DamlValues.record("microseconds" to DamlValues.int64(60_000_000L))
                    ),
                    "context" to DamlValues.optional(DamlValues.text("mining round")),
                )
            ),
            "meta" to metadataValue(emptyMap()),
        )

        val lock = holdingFromView("00cid", view).lock!!
        assertEquals(listOf("validator::1220cc"), lock.holders)
        assertEquals(requestedAt, lock.expiresAt)
        assertEquals(60_000_000L, lock.expiresAfterMicros)
        assertEquals("mining round", lock.context)
    }

    @Test
    fun `decodes a transfer instruction pending receiver acceptance`() {
        val view = DamlValues.recordOf(
            "originalInstructionCid" to DamlValues.optional(),
            "transfer" to transferValue(),
            "status" to DamlValues.variant("TransferPendingReceiverAcceptance", DamlValues.unit()),
            "meta" to metadataValue(emptyMap()),
        )

        val instruction = transferInstructionFromView("00instr", view)
        assertNull(instruction.originalInstructionCid)
        assertEquals(TransferInstructionStatus.PendingReceiverAcceptance, instruction.status)
        assertEquals("alice::1220aa", instruction.transfer.sender)
        assertEquals("bob::1220dd", instruction.transfer.receiver)
        assertEquals(listOf("00in1", "00in2"), instruction.transfer.inputHoldingCids)
    }

    @Test
    fun `round-trips a transfer through its choice-argument encoding`() {
        val transfer = Transfer(
            sender = "alice::1220aa",
            receiver = "bob::1220dd",
            amount = BigDecimal("25.5"),
            instrumentId = InstrumentId("dso::1220bb", "Amulet"),
            requestedAt = requestedAt,
            executeBefore = executeBefore,
            inputHoldingCids = listOf("00in1", "00in2"),
            meta = mapOf("reason" to "invoice 7"),
        )

        assertEquals(transfer, transferValueRoundTrip(transfer))
    }

    private fun transferValueRoundTrip(transfer: Transfer): Transfer {
        val record = transfer.toValue()
        // Reuse the view decoder by wrapping the encoded record the way a
        // TransferInstructionView embeds it.
        val view = DamlValues.recordOf(
            "originalInstructionCid" to DamlValues.optional(),
            "transfer" to record,
            "status" to DamlValues.variant("TransferPendingReceiverAcceptance", DamlValues.unit()),
            "meta" to metadataValue(emptyMap()),
        )
        return transferInstructionFromView("00x", view).transfer
    }

    private fun transferValue() = Transfer(
        sender = "alice::1220aa",
        receiver = "bob::1220dd",
        amount = BigDecimal("25.5"),
        instrumentId = InstrumentId("dso::1220bb", "Amulet"),
        requestedAt = requestedAt,
        executeBefore = executeBefore,
        inputHoldingCids = listOf("00in1", "00in2"),
        meta = emptyMap(),
    ).toValue()
}
