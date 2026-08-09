// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet

import java.io.IOException
import java.math.BigDecimal
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** A scan call failed (non-2xx status other than 404, or malformed payload). */
public class ScanException(message: String) : RuntimeException(message)

/**
 * Read layer over a Super Validator's Scan API (`.../api/scan`).
 *
 * Covers the reads a wallet needs from the network's public index: the DSO
 * party, ANS name resolution (name-based sending), and aggregated holdings
 * from scan's server-side ACS snapshots. Not yet covered — amulet rules and
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

    /** Aggregate Amulet totals for one owner party in a scan ACS snapshot. */
    public data class HoldingsSummary(
        val partyId: String,
        /** Sum of unlocked amulet initial amounts (holding fees not deducted). */
        val totalUnlockedCoin: BigDecimal,
        /** Sum of locked amulet initial amounts (holding fees not deducted). */
        val totalLockedCoin: BigDecimal,
        /** [totalUnlockedCoin] + [totalLockedCoin]. */
        val totalCoinHoldings: BigDecimal,
    )

    /** Aggregated holdings answered from one server-side ACS snapshot. */
    public data class HoldingsSummaryResult(
        /** Record time of the snapshot that answered — how stale the totals are. */
        val recordTime: java.time.Instant,
        /** The synchronizer-migration id the snapshot belongs to. */
        val migrationId: Long,
        /** One entry per queried party that held amulet; parties holding nothing are absent. */
        val summaries: List<HoldingsSummary>,
    )

    /**
     * The network's latest synchronizer-migration id (`/v0/migrations/last`)
     * — the id scan's ACS snapshots are addressed by. [holdingsSummary]
     * resolves this automatically; fetch it yourself only to pin the id
     * across many calls.
     */
    public suspend fun latestMigrationId(): Long =
        get("$baseUrl/v0/migrations/last")
            ?.longField("migration_id")
            ?: throw ScanException("missing migration_id in scan response")

    /**
     * Server-side aggregated Amulet balances for [ownerPartyIds]
     * (`/v1/holdings/summary`) — scan folds its ACS snapshot so apps don't
     * fold the full ACS client-side.
     *
     * Snapshot semantics — not real-time: scan persists ACS snapshots on a
     * fixed cadence (hours apart on typical deployments) and this read
     * answers from the most recent snapshot at or before [asOf]. Fresh taps
     * and transfers appear only once the next snapshot lands;
     * [HoldingsSummaryResult.recordTime] says exactly which snapshot
     * answered. Amounts are amulet initial amounts — holding fees accrued
     * since creation are not deducted.
     *
     * @param ownerPartyIds the owners to aggregate; must not be empty.
     *   Parties that held nothing at the snapshot are absent from the result.
     * @param asOf answer from the latest snapshot at or before this instant
     *   (default: now).
     * @param migrationId the synchronizer-migration id whose snapshots to
     *   read; defaults to the network's latest via [latestMigrationId].
     * @return the snapshot-backed totals, or null if scan has no ACS
     *   snapshot at or before [asOf] for that migration id (e.g. a
     *   freshly-bootstrapped network that hasn't taken one yet).
     */
    public suspend fun holdingsSummary(
        ownerPartyIds: List<String>,
        asOf: java.time.Instant? = null,
        migrationId: Long? = null,
    ): HoldingsSummaryResult? {
        require(ownerPartyIds.isNotEmpty()) { "ownerPartyIds must not be empty" }
        val body = buildJsonObject {
            put("migration_id", migrationId ?: latestMigrationId())
            put("record_time", (asOf ?: java.time.Instant.now()).toString())
            put("record_time_match", "at_or_before")
            putJsonArray("owner_party_ids") { ownerPartyIds.forEach { add(it) } }
        }
        return post("$baseUrl/v1/holdings/summary", body)?.let(::decodeHoldingsSummaryResult)
    }

    internal companion object {
        internal fun decodeHoldingsSummaryResult(json: JsonObject): HoldingsSummaryResult =
            HoldingsSummaryResult(
                recordTime = json.stringField("record_time")
                    ?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
                    ?: throw ScanException("holdings summary missing record_time"),
                migrationId = json.longField("migration_id")
                    ?: throw ScanException("holdings summary missing migration_id"),
                summaries = json["summaries"]?.jsonArray.orEmpty().map { entry ->
                    val summary = entry.jsonObject
                    fun amount(key: String): BigDecimal =
                        summary.stringField(key)?.toBigDecimalOrNull()
                            ?: throw ScanException("holdings summary missing $key")
                    HoldingsSummary(
                        partyId = summary.stringField("party_id")
                            ?: throw ScanException("holdings summary missing party_id"),
                        totalUnlockedCoin = amount("total_unlocked_coin"),
                        totalLockedCoin = amount("total_locked_coin"),
                        totalCoinHoldings = amount("total_coin_holdings"),
                    )
                },
            )

        private fun String.toBigDecimalOrNull(): BigDecimal? =
            runCatching { BigDecimal(this) }.getOrNull()

        private fun JsonObject.stringField(key: String): String? =
            (get(key)?.takeIf { it !is JsonNull } as? JsonPrimitive)?.content

        private fun JsonObject.longField(key: String): Long? =
            stringField(key)?.toLongOrNull()
    }

    private fun ansEntry(json: JsonObject): AnsEntry =
        AnsEntry(
            name = json.stringField("name") ?: throw ScanException("ANS entry missing name"),
            party = json.stringField("user") ?: throw ScanException("ANS entry missing user"),
            url = json.stringField("url").orEmpty(),
            description = json.stringField("description").orEmpty(),
        )

    /** GET returning the parsed body, or null on 404. */
    private suspend fun get(url: String): JsonObject? =
        execute(Request.Builder().url(url).get().build(), url)

    /** POST returning the parsed body, or null on 404. */
    private suspend fun post(url: String, body: JsonObject): JsonObject? =
        execute(
            Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build(),
            url,
        )

    private suspend fun execute(request: Request, url: String): JsonObject? =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
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
