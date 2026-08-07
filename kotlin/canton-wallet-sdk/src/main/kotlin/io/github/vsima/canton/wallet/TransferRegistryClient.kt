// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet

import com.daml.ledger.api.v2.CommandsOuterClass
import com.daml.ledger.api.v2.ValueOuterClass
import com.google.protobuf.ByteString
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** A registry call failed (non-2xx status or malformed payload). */
public class TransferRegistryException(message: String) : RuntimeException(message)

/**
 * Client for a CIP-0056 registry's off-ledger transfer-instruction API
 * (`/registry/transfer-instruction/v1/...`).
 *
 * Registries hand out two things a wallet cannot derive on its own: the
 * factory contract to exercise for a new transfer, and per-choice contexts
 * (referenced contracts + disclosed contracts) for accept/reject/withdraw.
 */
public class TransferRegistryClient(
    baseUrl: String,
    private val http: OkHttpClient = OkHttpClient(),
) {
    private val baseUrl = baseUrl.trimEnd('/')

    /** A contract the registry asks us to disclose with the command. */
    public data class RegistryDisclosedContract(
        val templateId: String,
        val contractId: String,
        val createdEventBlobBase64: String,
        val synchronizerId: String,
    ) {
        /** As the Ledger API `DisclosedContract` for command submission. */
        public fun toProto(): CommandsOuterClass.DisclosedContract {
            val segments = templateId.split(":", limit = 3)
            if (segments.size != 3) {
                throw TransferRegistryException("malformed registry templateId: $templateId")
            }
            return CommandsOuterClass.DisclosedContract.newBuilder()
                .setTemplateId(
                    ValueOuterClass.Identifier.newBuilder()
                        .setPackageId(segments[0])
                        .setModuleName(segments[1])
                        .setEntityName(segments[2])
                )
                .setContractId(contractId)
                .setCreatedEventBlob(
                    ByteString.copyFrom(Base64.getDecoder().decode(createdEventBlobBase64))
                )
                .setSynchronizerId(synchronizerId)
                .build()
        }
    }

    public data class RegistryChoiceContext(
        val choiceContextData: JsonElement?,
        val disclosedContracts: List<RegistryDisclosedContract>,
    )

    public data class TransferFactory(
        val factoryId: String,
        /** "self" | "direct" | "offer" — how the registry will route this transfer. */
        val transferKind: String,
        val choiceContext: RegistryChoiceContext,
    )

    /** POST `/transfer-factory`: the factory + context for a new transfer. */
    public suspend fun transferFactory(
        choiceArguments: JsonObject,
        excludeDebugFields: Boolean = true,
    ): TransferFactory {
        val body = buildJsonObject {
            put("choiceArguments", choiceArguments)
            put("excludeDebugFields", excludeDebugFields)
        }
        val response = post("$baseUrl/registry/transfer-instruction/v1/transfer-factory", body)
        return TransferFactory(
            factoryId = response.requireString("factoryId"),
            transferKind = response.requireString("transferKind"),
            choiceContext = choiceContext(response["choiceContext"]),
        )
    }

    /** POST `/{id}/choice-contexts/{accept|reject|withdraw}`. */
    public suspend fun transferInstructionChoiceContext(
        transferInstructionId: String,
        choice: TransferInstructionChoice,
        meta: Map<String, String> = emptyMap(),
    ): RegistryChoiceContext {
        val body = buildJsonObject {
            if (meta.isNotEmpty()) {
                putJsonObject("meta") { meta.forEach { (k, v) -> put(k, v) } }
            }
            put("excludeDebugFields", true)
        }
        val response = post(
            "$baseUrl/registry/transfer-instruction/v1/$transferInstructionId" +
                "/choice-contexts/${choice.registryPathSegment}",
            body,
        )
        return choiceContext(response)
    }

    private fun choiceContext(json: JsonElement?): RegistryChoiceContext {
        val obj = json as? JsonObject
            ?: throw TransferRegistryException("missing choiceContext in registry response")
        val disclosed = (obj["disclosedContracts"] as? JsonArray ?: JsonArray(emptyList())).map {
            val contract = it as? JsonObject
                ?: throw TransferRegistryException("malformed disclosedContracts entry: $it")
            RegistryDisclosedContract(
                templateId = contract.requireString("templateId"),
                contractId = contract.requireString("contractId"),
                createdEventBlobBase64 = contract.requireString("createdEventBlob"),
                synchronizerId = contract.requireString("synchronizerId"),
            )
        }
        return RegistryChoiceContext(
            choiceContextData = obj["choiceContextData"],
            disclosedContracts = disclosed,
        )
    }

    private fun JsonObject.requireString(key: String): String =
        (get(key) as? JsonPrimitive)?.content
            ?: throw TransferRegistryException("registry response missing $key")

    private suspend fun post(url: String, body: JsonObject): JsonObject {
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = http.newCall(request).await(url)
        return Json.parseToJsonElement(text) as? JsonObject
            ?: throw TransferRegistryException("registry response from $url is not a JSON object")
    }

    private suspend fun Call.await(url: String): String =
        suspendCancellableCoroutine { continuation ->
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val text = it.body?.string().orEmpty()
                        if (!it.isSuccessful) {
                            continuation.resumeWithException(
                                TransferRegistryException("HTTP ${it.code} from $url: $text")
                            )
                        } else {
                            continuation.resume(text)
                        }
                    }
                }
            })
            continuation.invokeOnCancellation { cancel() }
        }
}
