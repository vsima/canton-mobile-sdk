package io.github.vsima.canton

import com.daml.ledger.api.v2.ValueOuterClass.Value
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Decodes every golden vector in testdata/values/vectors.txt, checks the
 * typed readers, and re-encodes it with [DamlValues] builders — keeping this
 * SDK byte-compatible with the Swift SDK, which runs the same vectors.
 */
class GoldenValueVectorsTest {

    @Test
    fun `decodes and re-encodes every golden vector`() {
        val vectors = File("../../testdata/values/vectors.txt").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> line.split(" ", limit = 2).let { it[0] to it[1] } }
        assertEquals(15, vectors.size, "vector count changed; update both SDK tests")

        for ((name, base64) in vectors) {
            val decoded = Value.parseFrom(Base64.getDecoder().decode(base64))
            val rebuilt = checkVector(name, decoded)
            assertEquals(decoded, rebuilt, "builder for '$name' does not reproduce the golden value")
        }
    }

    /** Asserts the readers for vector [name] and returns the builder-made equivalent. */
    private fun checkVector(name: String, value: Value): Value = when (name) {
        "unit" -> {
            value.asUnit()
            DamlValues.unit()
        }
        "bool_true" -> {
            assertEquals(true, value.asBool())
            DamlValues.bool(true)
        }
        "int64" -> {
            assertEquals(42L, value.asInt64())
            DamlValues.int64(42)
        }
        "date" -> {
            assertEquals(LocalDate.ofEpochDay(19700), value.asDate())
            DamlValues.date(LocalDate.ofEpochDay(19700))
        }
        "timestamp" -> {
            assertEquals(Instant.ofEpochSecond(1_700_000_000), value.asTimestamp())
            DamlValues.timestamp(Instant.ofEpochSecond(1_700_000_000))
        }
        "numeric" -> {
            assertEquals(BigDecimal("3.1415926535"), value.asNumeric())
            DamlValues.numeric("3.1415926535")
        }
        "party" -> {
            assertEquals("alice::122abc", value.asParty())
            DamlValues.party("alice::122abc")
        }
        "text" -> {
            assertEquals("hello, canton", value.asText())
            DamlValues.text("hello, canton")
        }
        "contract_id" -> {
            assertEquals("00deadbeef", value.asContractId())
            DamlValues.contractId("00deadbeef")
        }
        "optional_none" -> {
            assertNull(value.asOptional())
            DamlValues.optional()
        }
        "optional_some_text" -> {
            assertEquals("present", value.asOptional()?.asText())
            DamlValues.optional(DamlValues.text("present"))
        }
        "list_int64" -> {
            assertEquals(listOf(1L, 2L, 3L), value.asList().map { it.asInt64() })
            DamlValues.list((1L..3L).map(DamlValues::int64))
        }
        "record_amount" -> {
            val record = value.asRecord()
            assertEquals(BigDecimal("100.0"), record.requireField("value").asNumeric())
            assertEquals("USD", record.requireField("currency").asText())
            DamlValues.record(
                "value" to DamlValues.numeric("100.0"),
                "currency" to DamlValues.text("USD"),
            )
        }
        "variant_left_int64" -> {
            val variant = value.asVariant()
            assertEquals("Left", variant.constructor)
            assertEquals(1L, variant.value.asInt64())
            DamlValues.variant("Left", DamlValues.int64(1))
        }
        "enum_red" -> {
            assertEquals("Red", value.asEnumConstructor())
            DamlValues.enumValue("Red")
        }
        else -> fail("unhandled golden vector '$name' — add coverage in BOTH SDKs")
    }
}
