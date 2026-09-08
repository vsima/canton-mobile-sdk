// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

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

    /** Daml `()`. */
    public fun unit(): Value = build { setUnit(Empty.getDefaultInstance()) }

    /** A Daml `Bool`. */
    public fun bool(value: Boolean): Value = build { setBool(value) }

    /** A Daml `Int`. */
    public fun int64(value: Long): Value = build { setInt64(value) }

    /** Days since 1970-01-01. */
    public fun date(value: LocalDate): Value = build { setDate(value.toEpochDay().toInt()) }

    /** Microseconds since epoch, UTC. */
    public fun timestamp(value: Instant): Value = build {
        setTimestamp(value.epochSecond * 1_000_000L + value.nano / 1_000L)
    }

    /**
     * A Daml `Numeric`, rendered with [BigDecimal.toPlainString] so no exponent
     * appears on the wire.
     */
    public fun numeric(value: BigDecimal): Value = build { setNumeric(value.toPlainString()) }

    /** A Daml `Numeric` from an already-formatted decimal string; passed through unchecked. */
    public fun numeric(value: String): Value = build { setNumeric(value) }

    /** A Daml `Party` from its party id. */
    public fun party(value: String): Value = build { setParty(value) }

    /** A Daml `Text`. */
    public fun text(value: String): Value = build { setText(value) }

    /** A Daml `ContractId`. */
    public fun contractId(value: String): Value = build { setContractId(value) }

    /** `Some(value)`, or `None` when [value] is null. */
    public fun optional(value: Value? = null): Value = build {
        setOptional(
            ValueOuterClass.Optional.newBuilder().apply { value?.let(::setValue) }
        )
    }

    /**
     * A Daml `List`; the elements are not required to share a type here, the
     * ledger checks that.
     */
    public fun list(elements: List<Value>): Value = build {
        setList(ValueOuterClass.List.newBuilder().addAllElements(elements))
    }

    /** [list] over varargs. */
    public fun list(vararg elements: Value): Value = list(elements.toList())

    /** A record [Value] with the given labelled fields, in the order given. */
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

    /** A Daml variant: [constructor] applied to [value]. */
    public fun variant(constructor: String, value: Value): Value = build {
        setVariant(ValueOuterClass.Variant.newBuilder().setConstructor(constructor).setValue(value))
    }

    /** A Daml enum constructor. Named `enumValue` because `enum` is a Kotlin soft keyword. */
    public fun enumValue(constructor: String): Value = build {
        setEnum(ValueOuterClass.Enum.newBuilder().setConstructor(constructor))
    }

    private inline fun build(block: Value.Builder.() -> Unit): Value =
        Value.newBuilder().apply(block).build()
}

/**
 * Thrown when a [Value] does not have the shape a reader expects. Every
 * `Value.as…` extension below throws it on a sum-case mismatch (the
 * message names both the expected and the actual case), and
 * [ValueOuterClass.Record.requireField] throws it for a missing field.
 */
public class DamlDecodeException(message: String) : RuntimeException(message)

private fun Value.expect(kind: Value.SumCase): Value {
    if (sumCase != kind) {
        throw DamlDecodeException("expected $kind, was $sumCase")
    }
    return this
}

/** Asserts this is Daml `()`; the only reader that returns nothing. */
public fun Value.asUnit() {
    expect(Value.SumCase.UNIT)
}

/** This value as a Daml `Bool`. */
public fun Value.asBool(): Boolean = expect(Value.SumCase.BOOL).bool

/** This value as a Daml `Int`. */
public fun Value.asInt64(): Long = expect(Value.SumCase.INT64).int64

/** This value as a Daml `Date`, from days since 1970-01-01. */
public fun Value.asDate(): LocalDate =
    LocalDate.ofEpochDay(expect(Value.SumCase.DATE).date.toLong())

/**
 * This value as a Daml `Time`, from microseconds since the epoch in UTC.
 * Negative timestamps floor-divide correctly.
 */
public fun Value.asTimestamp(): Instant {
    val micros = expect(Value.SumCase.TIMESTAMP).timestamp
    return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L)
}

/** This value as a Daml `Numeric`, parsed exactly. */
public fun Value.asNumeric(): BigDecimal = BigDecimal(expect(Value.SumCase.NUMERIC).numeric)

/** This value's party id. */
public fun Value.asParty(): String = expect(Value.SumCase.PARTY).party

/** This value as a Daml `Text`. */
public fun Value.asText(): String = expect(Value.SumCase.TEXT).text

/** This value's contract id. */
public fun Value.asContractId(): String = expect(Value.SumCase.CONTRACT_ID).contractId

/** The wrapped value for `Some`, or null for `None`. */
public fun Value.asOptional(): Value? =
    expect(Value.SumCase.OPTIONAL).optional.let { if (it.hasValue()) it.value else null }

/** The elements of this Daml `List`. */
public fun Value.asList(): List<Value> = expect(Value.SumCase.LIST).list.elementsList

/** This value's record, for reading fields with [field] or [requireField]. */
public fun Value.asRecord(): ValueOuterClass.Record = expect(Value.SumCase.RECORD).record

/** This value's variant, exposing its constructor and payload. */
public fun Value.asVariant(): ValueOuterClass.Variant = expect(Value.SumCase.VARIANT).variant

/** The constructor name of this Daml enum value. */
public fun Value.asEnumConstructor(): String = expect(Value.SumCase.ENUM).enum.constructor

/** The value of the field labelled [label], or null if absent. */
public fun ValueOuterClass.Record.field(label: String): Value? =
    fieldsList.firstOrNull { it.label == label }?.value

/** The value of the field labelled [label]. */
public fun ValueOuterClass.Record.requireField(label: String): Value =
    field(label) ?: throw DamlDecodeException("missing record field '$label'")
