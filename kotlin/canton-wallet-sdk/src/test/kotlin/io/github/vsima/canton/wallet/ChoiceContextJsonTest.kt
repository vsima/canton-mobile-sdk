package io.github.vsima.canton.wallet

import com.daml.ledger.api.v2.ValueOuterClass.Value
import io.github.vsima.canton.DamlValues
import io.github.vsima.canton.asRecord
import io.github.vsima.canton.requireField
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Maps the shared fixture (testdata/tokenstandard/choice-context.json) —
 * every AnyValue constructor a registry can emit — to proto values. The
 * Swift twin consumes the same fixture; both SDKs must agree.
 */
class ChoiceContextJsonTest {

    private val fixture: JsonObject =
        Json.parseToJsonElement(File("../../testdata/tokenstandard/choice-context.json").readText())
            .jsonObject

    @Test
    fun `maps every AnyValue constructor in the shared fixture`() {
        val context = ChoiceContextJson.choiceContextValue(fixture)
        val entries = context.asRecord().requireField("values").textMapEntries()
            .associate { it.key to it.value }

        assertEquals(12, entries.size)

        fun variant(key: String): Pair<String, Value> {
            val v = entries.getValue(key).variant
            return v.constructor to v.value
        }

        assertEquals(
            "AV_ContractId" to DamlValues.contractId(
                "00aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899"
            ),
            variant("amulet-rules"),
        )
        assertEquals("AV_Text" to DamlValues.text("token-standard choice context"), variant("note"))
        assertEquals("AV_Int" to DamlValues.int64(42), variant("count"))
        assertEquals("AV_Decimal" to DamlValues.numeric("1.5"), variant("fee"))
        assertEquals("AV_Bool" to DamlValues.bool(true), variant("featured"))
        assertEquals(
            "AV_Time" to DamlValues.timestamp(Instant.parse("2026-08-07T12:00:00Z")),
            variant("as-of"),
        )
        assertEquals(
            "AV_RelTime" to DamlValues.record("microseconds" to DamlValues.int64(3_600_000_000L)),
            variant("timeout"),
        )
        assertEquals("AV_Party" to DamlValues.party("operator::1220aabbcc"), variant("operator"))

        val (listTag, listValue) = variant("extra-cids")
        assertEquals("AV_List", listTag)
        assertEquals(1, listValue.list.elementsCount)

        val (mapTag, mapValue) = variant("nested")
        assertEquals("AV_Map", mapTag)
        assertEquals("inner", mapValue.textMap.getEntries(0).key)
    }

    @Test
    fun `empty and absent contexts encode as an empty ChoiceContext record`() {
        val empty = ChoiceContextJson.choiceContextValue(null)
        assertEquals(0, empty.asRecord().requireField("values").textMapEntries().size)
    }

    @Test
    fun `factory choice arguments follow the Daml JSON API encoding`() {
        val transfer = Transfer(
            sender = "alice::1220aa",
            receiver = "bob::1220dd",
            amount = BigDecimal("25.5"),
            instrumentId = InstrumentId("dso::1220bb", "Amulet"),
            requestedAt = Instant.parse("2026-08-07T10:00:00Z"),
            executeBefore = Instant.parse("2026-08-08T10:00:00Z"),
            inputHoldingCids = listOf("00in1"),
            meta = mapOf("reason" to "invoice 7"),
        )

        val args = ChoiceContextJson.transferFactoryChoiceArguments("dso::1220bb", transfer)

        assertEquals("dso::1220bb", args.getValue("expectedAdmin").jsonPrimitive.content)
        val transferJson = args.getValue("transfer").jsonObject
        assertEquals("25.5", transferJson.getValue("amount").jsonPrimitive.content)
        assertEquals("2026-08-07T10:00:00Z", transferJson.getValue("requestedAt").jsonPrimitive.content)
        assertEquals(
            "invoice 7",
            transferJson.getValue("meta").jsonObject
                .getValue("values").jsonObject
                .getValue("reason").jsonPrimitive.content,
        )
        // extraArgs must be present-but-empty per the registry spec.
        val extraArgs = args.getValue("extraArgs").jsonObject
        assertTrue(extraArgs.getValue("context").jsonObject.getValue("values").jsonObject.isEmpty())
        assertTrue(extraArgs.getValue("meta").jsonObject.getValue("values").jsonObject.isEmpty())
    }
}
