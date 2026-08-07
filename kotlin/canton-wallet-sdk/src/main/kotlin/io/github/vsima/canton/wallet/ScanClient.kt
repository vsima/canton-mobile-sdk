// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** A scan call failed (non-2xx status other than 404, or malformed payload). */
public class ScanException(message: String) : RuntimeException(message)

/**
 * Read layer over a Super Validator's Scan API (`.../api/scan`).
 *
 * Covers the reads a wallet needs from the network's public index: the DSO
 * party and ANS name resolution (name-based sending). Not yet covered —
 * holdings summaries (require server-side ACS snapshots), amulet rules and
 * mining rounds (arrive with traffic-purchase support).
 *
 * Note the base URL differs from [TransferRegistryClient]'s: registry
 * endpoints mount at the vhost root (`/registry/...`), scan endpoints under
 * `/api/scan`.
 */
public class ScanClient(
    baseUrl: String,
    private val http: OkHttpClient = OkHttpClient(),
) {
    private val baseUrl = baseUrl.trimEnd('/')

    /** One ANS (Amulet Name Service) entry — a name bound to a party. */
    public data class AnsEntry(
        val name: String,
        val party: String,
        val url: String,
        val description: String,
    )

    /** The party id of the DSO — the instrument admin for Amulet. */
    public suspend fun dsoPartyId(): String =
        get("$baseUrl/v0/dso-party-id")
            ?.stringField("dso_party_id")
            ?: throw ScanException("missing dso_party_id in scan response")

    /** Resolves an ANS name to its entry, or null if unregistered. */
    public suspend fun lookupAnsEntryByName(name: String): AnsEntry? {
        val url = "$baseUrl/v0/ans-entries/by-name/".toHttpUrl()
            .newBuilder().addPathSegment(name).build()
        val response = get(url.toString()) ?: return null
        return (response["entry"] as? JsonObject)?.let(::ansEntry)
    }

    /** An active TransferPreapproval: senders can transfer to [receiver] directly. */
    public data class TransferPreapprovalInfo(
        val contractId: String,
        val receiver: String?,
        val provider: String?,
        val expiresAt: java.time.Instant?,
    )

    /**
     * The active preapproval for [partyId], or null if none — the signal
     * that transfers to this party settle in one step ("direct") instead of
     * the two-step offer flow.
     */
    public suspend fun transferPreapprovalByParty(partyId: String): TransferPreapprovalInfo? {
        val url = "$baseUrl/v0/transfer-preapprovals/by-party/".toHttpUrl()
            .newBuilder().addPathSegment(partyId).build()
        val response = get(url.toString()) ?: return null
        val contract = (response["transfer_preapproval"] as? JsonObject)
            ?.let { it["contract"] as? JsonObject }
            ?: return null
        val payload = contract["payload"] as? JsonObject
        return TransferPreapprovalInfo(
            contractId = contract.stringField("contract_id")
                ?: throw ScanException("preapproval contract missing contract_id"),
            receiver = payload?.stringField("receiver"),
            provider = payload?.stringField("provider"),
            expiresAt = payload?.stringField("expiresAt")?.let {
                runCatching { java.time.Instant.parse(it) }.getOrNull()
            },
        )
    }

    /** Lists ANS entries, optionally filtered by a name prefix. */
    public suspend fun listAnsEntries(pageSize: Int = 100, namePrefix: String? = null): List<AnsEntry> {
        val url = "$baseUrl/v0/ans-entries".toHttpUrl().newBuilder()
            .addQueryParameter("page_size", pageSize.toString())
            .apply { namePrefix?.let { addQueryParameter("name_prefix", it) } }
            .build()
        val response = get(url.toString()) ?: return emptyList()
        return response["entries"]?.jsonArray.orEmpty()
            .map { ansEntry(it.jsonObject) }
    }

    private fun ansEntry(json: JsonObject): AnsEntry =
        AnsEntry(
            name = json.stringField("name") ?: throw ScanException("ANS entry missing name"),
            party = json.stringField("user") ?: throw ScanException("ANS entry missing user"),
            url = json.stringField("url").orEmpty(),
            description = json.stringField("description").orEmpty(),
        )

    private fun JsonObject.stringField(key: String): String? =
        (get(key)?.takeIf { it !is JsonNull } as? JsonPrimitive)?.content

    /** GET returning the parsed body, or null on 404. */
    private suspend fun get(url: String): JsonObject? =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(Request.Builder().url(url).get().build())
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val text = it.body?.string().orEmpty()
                        when {
                            it.code == 404 -> continuation.resume(null)
                            !it.isSuccessful -> continuation.resumeWithException(
                                ScanException("HTTP ${it.code} from $url: ${text.take(300)}")
                            )
                            else -> continuation.resume(
                                Json.parseToJsonElement(text) as? JsonObject
                                    ?: return continuation.resumeWithException(
                                        ScanException("scan response from $url is not a JSON object")
                                    )
                            )
                        }
                    }
                }
            })
            continuation.invokeOnCancellation { call.cancel() }
        }
}
