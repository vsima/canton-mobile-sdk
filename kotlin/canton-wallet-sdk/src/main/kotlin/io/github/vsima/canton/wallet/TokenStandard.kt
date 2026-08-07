package io.github.vsima.canton.wallet

import com.daml.ledger.api.v2.ValueOuterClass
import com.daml.ledger.api.v2.ValueOuterClass.Value
import io.github.vsima.canton.DamlDecodeException
import io.github.vsima.canton.DamlValues
import io.github.vsima.canton.asContractId
import io.github.vsima.canton.asInt64
import io.github.vsima.canton.asList
import io.github.vsima.canton.asNumeric
import io.github.vsima.canton.asOptional
import io.github.vsima.canton.asParty
import io.github.vsima.canton.asRecord
import io.github.vsima.canton.asText
import io.github.vsima.canton.asTimestamp
import io.github.vsima.canton.asVariant
import io.github.vsima.canton.field
import io.github.vsima.canton.requireField
import java.math.BigDecimal
import java.time.Instant

/**
 * CIP-0056 token standard identifiers and Daml value codecs.
 *
 * Interface ids use the package-name reference format (`#package-name`), so
 * they resolve against whichever package version the participant has vetted —
 * exactly what a wallet talking to arbitrary registries needs.
 */
public object TokenStandard {

    public val holdingInterfaceId: ValueOuterClass.Identifier =
        interfaceId("splice-api-token-holding-v1", "Splice.Api.Token.HoldingV1", "Holding")

    public val transferInstructionInterfaceId: ValueOuterClass.Identifier =
        interfaceId(
            "splice-api-token-transfer-instruction-v1",
            "Splice.Api.Token.TransferInstructionV1",
            "TransferInstruction",
        )

    public val transferFactoryInterfaceId: ValueOuterClass.Identifier =
        interfaceId(
            "splice-api-token-transfer-instruction-v1",
            "Splice.Api.Token.TransferInstructionV1",
            "TransferFactory",
        )

    private fun interfaceId(
        packageName: String,
        module: String,
        entity: String,
    ): ValueOuterClass.Identifier =
        ValueOuterClass.Identifier.newBuilder()
            .setPackageId("#$packageName")
            .setModuleName(module)
            .setEntityName(entity)
            .build()
}

/** `Splice.Api.Token.HoldingV1.InstrumentId` — admin party + admin-unique id. */
public data class InstrumentId(val admin: String, val id: String)

/** `Splice.Api.Token.HoldingV1.Lock`. When both expiries are set, the earlier wins. */
public data class HoldingLock(
    val holders: List<String>,
    val expiresAt: Instant?,
    val expiresAfterMicros: Long?,
    val context: String?,
)

/** A holding UTXO: one contract implementing the CIP-0056 Holding interface. */
public data class Holding(
    val contractId: String,
    val owner: String,
    val instrumentId: InstrumentId,
    val amount: BigDecimal,
    val lock: HoldingLock?,
    val meta: Map<String, String>,
)

/** `Splice.Api.Token.TransferInstructionV1.Transfer` — the transfer specification. */
public data class Transfer(
    val sender: String,
    val receiver: String,
    val amount: BigDecimal,
    val instrumentId: InstrumentId,
    val requestedAt: Instant,
    val executeBefore: Instant,
    val inputHoldingCids: List<String>,
    val meta: Map<String, String>,
)

public sealed interface TransferInstructionStatus {
    /** Waiting for the receiver to accept or reject — the wallet-inbox state. */
    public data object PendingReceiverAcceptance : TransferInstructionStatus

    /** Waiting on registry-internal steps by the listed parties. */
    public data class PendingInternalWorkflow(
        val pendingActions: Map<String, String>,
    ) : TransferInstructionStatus
}

/** A pending two-step transfer (contract implementing the TransferInstruction interface). */
public data class TransferInstruction(
    val contractId: String,
    val originalInstructionCid: String?,
    val transfer: Transfer,
    val status: TransferInstructionStatus,
    val meta: Map<String, String>,
)

public enum class TransferInstructionChoice(
    internal val choiceName: String,
    internal val registryPathSegment: String,
) {
    ACCEPT("TransferInstruction_Accept", "accept"),
    REJECT("TransferInstruction_Reject", "reject"),
    WITHDRAW("TransferInstruction_Withdraw", "withdraw"),
}

// ---------------------------------------------------------------------------
// Decoding: interface view records -> typed values
// ---------------------------------------------------------------------------

internal fun holdingFromView(contractId: String, view: ValueOuterClass.Record): Holding =
    Holding(
        contractId = contractId,
        owner = view.requireField("owner").asParty(),
        instrumentId = instrumentIdFromValue(view.requireField("instrumentId")),
        amount = view.requireField("amount").asNumeric(),
        lock = view.requireField("lock").asOptional()?.let(::lockFromValue),
        meta = metadataFromValue(view.requireField("meta")),
    )

internal fun transferInstructionFromView(
    contractId: String,
    view: ValueOuterClass.Record,
): TransferInstruction =
    TransferInstruction(
        contractId = contractId,
        originalInstructionCid = view.requireField("originalInstructionCid").asOptional()?.asContractId(),
        transfer = transferFromValue(view.requireField("transfer")),
        status = statusFromValue(view.requireField("status")),
        meta = metadataFromValue(view.requireField("meta")),
    )

private fun instrumentIdFromValue(value: Value): InstrumentId {
    val record = value.asRecord()
    return InstrumentId(
        admin = record.requireField("admin").asParty(),
        id = record.requireField("id").asText(),
    )
}

private fun lockFromValue(value: Value): HoldingLock {
    val record = value.asRecord()
    return HoldingLock(
        holders = record.requireField("holders").asList().map { it.asParty() },
        expiresAt = record.field("expiresAt")?.asOptional()?.asTimestamp(),
        expiresAfterMicros = record.field("expiresAfter")?.asOptional()
            ?.asRecord()?.requireField("microseconds")?.asInt64(),
        context = record.field("context")?.asOptional()?.asText(),
    )
}

private fun transferFromValue(value: Value): Transfer {
    val record = value.asRecord()
    return Transfer(
        sender = record.requireField("sender").asParty(),
        receiver = record.requireField("receiver").asParty(),
        amount = record.requireField("amount").asNumeric(),
        instrumentId = instrumentIdFromValue(record.requireField("instrumentId")),
        requestedAt = record.requireField("requestedAt").asTimestamp(),
        executeBefore = record.requireField("executeBefore").asTimestamp(),
        inputHoldingCids = record.requireField("inputHoldingCids").asList().map { it.asContractId() },
        meta = metadataFromValue(record.requireField("meta")),
    )
}

private fun statusFromValue(value: Value): TransferInstructionStatus {
    val variant = value.asVariant()
    return when (variant.constructor) {
        "TransferPendingReceiverAcceptance" -> TransferInstructionStatus.PendingReceiverAcceptance
        "TransferPendingInternalWorkflow" -> TransferInstructionStatus.PendingInternalWorkflow(
            pendingActions = variant.value.asRecord().requireField("pendingActions")
                .genMapEntries()
                .associate { it.key.asParty() to it.value.asText() }
        )
        else -> throw DamlDecodeException(
            "unknown TransferInstructionStatus constructor ${variant.constructor}"
        )
    }
}

/** Decodes `Splice.Api.Token.MetadataV1.Metadata` (a record wrapping a TextMap). */
internal fun metadataFromValue(value: Value): Map<String, String> =
    value.asRecord().requireField("values").textMapEntries()
        .associate { it.key to it.value.asText() }

// TextMap/GenMap readers; candidates for promotion into DamlValues alongside
// matching golden vectors.

internal fun Value.textMapEntries(): List<ValueOuterClass.TextMap.Entry> {
    if (sumCase != Value.SumCase.TEXT_MAP) {
        throw DamlDecodeException("expected TEXT_MAP, was $sumCase")
    }
    return textMap.entriesList
}

internal fun Value.genMapEntries(): List<ValueOuterClass.GenMap.Entry> {
    if (sumCase != Value.SumCase.GEN_MAP) {
        throw DamlDecodeException("expected GEN_MAP, was $sumCase")
    }
    return genMap.entriesList
}

// ---------------------------------------------------------------------------
// Encoding: typed values -> Daml values for choice arguments
// ---------------------------------------------------------------------------

internal fun Transfer.toValue(): Value =
    DamlValues.record(
        "sender" to DamlValues.party(sender),
        "receiver" to DamlValues.party(receiver),
        "amount" to DamlValues.numeric(amount),
        "instrumentId" to DamlValues.record(
            "admin" to DamlValues.party(instrumentId.admin),
            "id" to DamlValues.text(instrumentId.id),
        ),
        "requestedAt" to DamlValues.timestamp(requestedAt),
        "executeBefore" to DamlValues.timestamp(executeBefore),
        "inputHoldingCids" to DamlValues.list(inputHoldingCids.map { DamlValues.contractId(it) }),
        "meta" to metadataValue(meta),
    )

internal fun metadataValue(meta: Map<String, String>): Value =
    DamlValues.record("values" to textMapValue(meta.mapValues { DamlValues.text(it.value) }))

internal fun textMapValue(entries: Map<String, Value>): Value =
    Value.newBuilder()
        .setTextMap(
            ValueOuterClass.TextMap.newBuilder().addAllEntries(
                entries.map { (key, value) ->
                    ValueOuterClass.TextMap.Entry.newBuilder().setKey(key).setValue(value).build()
                }
            )
        )
        .build()
