package io.github.vsima.canton

import com.daml.ledger.api.v2.ValueOuterClass
import com.daml.ledger.api.v2.ValueOuterClass.Value
import com.google.protobuf.Empty
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Concise constructors for Daml [Value]s, mirroring the Swift SDK's
 * `Com_Daml_Ledger_Api_V2_Value` extensions. Both implementations are held
 * to the same golden vectors in `testdata/values/`.
 */
public object DamlValues {

    public fun unit(): Value = build { setUnit(Empty.getDefaultInstance()) }

    public fun bool(value: Boolean): Value = build { setBool(value) }

    public fun int64(value: Long): Value = build { setInt64(value) }

    /** Days since 1970-01-01. */
    public fun date(value: LocalDate): Value = build { setDate(value.toEpochDay().toInt()) }

    /** Microseconds since epoch, UTC. */
    public fun timestamp(value: Instant): Value = build {
        setTimestamp(value.epochSecond * 1_000_000L + value.nano / 1_000L)
    }

    public fun numeric(value: BigDecimal): Value = build { setNumeric(value.toPlainString()) }

    public fun numeric(value: String): Value = build { setNumeric(value) }

    public fun party(value: String): Value = build { setParty(value) }

    public fun text(value: String): Value = build { setText(value) }

    public fun contractId(value: String): Value = build { setContractId(value) }

    /** `Some(value)`, or `None` when [value] is null. */
    public fun optional(value: Value? = null): Value = build {
        setOptional(
            ValueOuterClass.Optional.newBuilder().apply { value?.let(::setValue) }
        )
    }

    public fun list(elements: List<Value>): Value = build {
        setList(ValueOuterClass.List.newBuilder().addAllElements(elements))
    }

    public fun list(vararg elements: Value): Value = list(elements.toList())

    public fun record(vararg fields: Pair<String, Value>): Value = build {
        setRecord(recordOf(*fields))
    }

    /** A bare [ValueOuterClass.Record], e.g. for `CreateCommand.create_arguments`. */
    public fun recordOf(vararg fields: Pair<String, Value>): ValueOuterClass.Record =
        ValueOuterClass.Record.newBuilder()
            .apply {
                fields.forEach { (label, value) ->
                    addFields(
                        ValueOuterClass.RecordField.newBuilder().setLabel(label).setValue(value)
                    )
                }
            }
            .build()

    public fun variant(constructor: String, value: Value): Value = build {
        setVariant(ValueOuterClass.Variant.newBuilder().setConstructor(constructor).setValue(value))
    }

    public fun enumValue(constructor: String): Value = build {
        setEnum(ValueOuterClass.Enum.newBuilder().setConstructor(constructor))
    }

    private inline fun build(block: Value.Builder.() -> Unit): Value =
        Value.newBuilder().apply(block).build()
}

/** Thrown when a [Value] does not have the shape a reader expects. */
public class DamlDecodeException(message: String) : RuntimeException(message)

private fun Value.expect(kind: Value.SumCase): Value {
    if (sumCase != kind) {
        throw DamlDecodeException("expected $kind, was $sumCase")
    }
    return this
}

public fun Value.asUnit() {
    expect(Value.SumCase.UNIT)
}

public fun Value.asBool(): Boolean = expect(Value.SumCase.BOOL).bool

public fun Value.asInt64(): Long = expect(Value.SumCase.INT64).int64

public fun Value.asDate(): LocalDate =
    LocalDate.ofEpochDay(expect(Value.SumCase.DATE).date.toLong())

public fun Value.asTimestamp(): Instant {
    val micros = expect(Value.SumCase.TIMESTAMP).timestamp
    return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L)
}

public fun Value.asNumeric(): BigDecimal = BigDecimal(expect(Value.SumCase.NUMERIC).numeric)

public fun Value.asParty(): String = expect(Value.SumCase.PARTY).party

public fun Value.asText(): String = expect(Value.SumCase.TEXT).text

public fun Value.asContractId(): String = expect(Value.SumCase.CONTRACT_ID).contractId

/** The wrapped value for `Some`, or null for `None`. */
public fun Value.asOptional(): Value? =
    expect(Value.SumCase.OPTIONAL).optional.let { if (it.hasValue()) it.value else null }

public fun Value.asList(): List<Value> = expect(Value.SumCase.LIST).list.elementsList

public fun Value.asRecord(): ValueOuterClass.Record = expect(Value.SumCase.RECORD).record

public fun Value.asVariant(): ValueOuterClass.Variant = expect(Value.SumCase.VARIANT).variant

public fun Value.asEnumConstructor(): String = expect(Value.SumCase.ENUM).enum.constructor

/** The value of the field labelled [label], or null if absent. */
public fun ValueOuterClass.Record.field(label: String): Value? =
    fieldsList.firstOrNull { it.label == label }?.value

/** The value of the field labelled [label]. */
public fun ValueOuterClass.Record.requireField(label: String): Value =
    field(label) ?: throw DamlDecodeException("missing record field '$label'")
