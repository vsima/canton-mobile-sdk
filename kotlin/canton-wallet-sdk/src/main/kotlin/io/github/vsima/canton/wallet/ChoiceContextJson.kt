package io.github.vsima.canton.wallet

import com.daml.ledger.api.v2.ValueOuterClass.Value
import io.github.vsima.canton.DamlDecodeException
import io.github.vsima.canton.DamlValues
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Bridges the registry's off-ledger JSON (Daml JSON API encoding) and the
 * gRPC Ledger API's proto values.
 *
 * Registries return `choiceContextData` as the Daml JSON encoding of
 * `Splice.Api.Token.MetadataV1.ChoiceContext` — a `TextMap` of `AnyValue`
 * variants (`{"tag": "AV_ContractId", "value": "00…"}`). The official TS SDK
 * submits through the JSON API where that encoding is native; we submit over
 * gRPC, so the context must be re-encoded as proto values. `AnyValue`'s
 * closed constructor set is what makes this translation total.
 */
internal object ChoiceContextJson {

    /** `ExtraArgs { context, meta }` ready to embed in a choice-argument record. */
    fun extraArgsValue(choiceContextData: JsonElement?, meta: Map<String, String> = emptyMap()): Value =
        DamlValues.record(
            "context" to choiceContextValue(choiceContextData),
            "meta" to metadataValue(meta),
        )

    /** Daml JSON `ChoiceContext` -> proto record. Null/absent means an empty context. */
    fun choiceContextValue(json: JsonElement?): Value {
        val values = when (json) {
            null, is JsonNull -> emptyMap()
            is JsonObject -> (json["values"] as? JsonObject)?.mapValues { anyValueToValue(it.value) }
                ?: emptyMap()
            else -> throw DamlDecodeException("choiceContextData must be an object, was $json")
        }
        return DamlValues.record("values" to textMapValue(values))
    }

    /** One `AnyValue` variant from Daml JSON to its proto encoding. */
    fun anyValueToValue(json: JsonElement): Value {
        val obj = json as? JsonObject
            ?: throw DamlDecodeException("AnyValue must be a tagged object, was $json")
        val tag = (obj["tag"] as? JsonPrimitive)?.content
            ?: throw DamlDecodeException("AnyValue object missing tag: $obj")
        val value = obj["value"] ?: JsonNull
        val payload = when (tag) {
            "AV_Text" -> DamlValues.text(value.primitiveContent(tag))
            "AV_Int" -> DamlValues.int64(value.primitiveContent(tag).toLong())
            "AV_Decimal" -> DamlValues.numeric(value.primitiveContent(tag))
            "AV_Bool" -> DamlValues.bool(value.primitiveContent(tag).toBooleanStrict())
            "AV_Date" -> DamlValues.date(LocalDate.parse(value.primitiveContent(tag)))
            "AV_Time" -> DamlValues.timestamp(Instant.parse(value.primitiveContent(tag)))
            "AV_RelTime" -> DamlValues.record(
                "microseconds" to DamlValues.int64(
                    ((value as? JsonObject)?.get("microseconds") ?: value)
                        .primitiveContent(tag).toLong()
                )
            )
            "AV_Party" -> DamlValues.party(value.primitiveContent(tag))
            "AV_ContractId" -> DamlValues.contractId(value.primitiveContent(tag))
            "AV_List" -> DamlValues.list(
                (value as? JsonArray ?: throw DamlDecodeException("AV_List value must be an array"))
                    .map { anyValueToValue(it) }
            )
            "AV_Map" -> textMapValue(
                (value as? JsonObject ?: throw DamlDecodeException("AV_Map value must be an object"))
                    .mapValues { anyValueToValue(it.value) }
            )
            else -> throw DamlDecodeException("unknown AnyValue constructor $tag")
        }
        return DamlValues.variant(tag, payload)
    }

    private fun JsonElement.primitiveContent(tag: String): String =
        (this as? JsonPrimitive)?.content
            ?: throw DamlDecodeException("$tag value must be a primitive, was $this")

    /**
     * `TransferFactory_Transfer` choice arguments in Daml JSON API encoding,
     * for `GetFactoryRequest.choiceArguments` — `extraArgs` empty per spec.
     */
    fun transferFactoryChoiceArguments(expectedAdmin: String, transfer: Transfer): JsonObject =
        buildJsonObject {
            put("expectedAdmin", expectedAdmin)
            putJsonObject("transfer") {
                put("sender", transfer.sender)
                put("receiver", transfer.receiver)
                put("amount", transfer.amount.toPlainString())
                putJsonObject("instrumentId") {
                    put("admin", transfer.instrumentId.admin)
                    put("id", transfer.instrumentId.id)
                }
                put("requestedAt", transfer.requestedAt.toString())
                put("executeBefore", transfer.executeBefore.toString())
                putJsonArray("inputHoldingCids") {
                    transfer.inputHoldingCids.forEach { add(JsonPrimitive(it)) }
                }
                putJsonObject("meta") {
                    putJsonObject("values") {
                        transfer.meta.forEach { (k, v) -> put(k, v) }
                    }
                }
            }
            putJsonObject("extraArgs") {
                putJsonObject("context") { putJsonObject("values") {} }
                putJsonObject("meta") { putJsonObject("values") {} }
            }
        }
}
