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
 * party, ANS name resolution (name-based sending), aggregated holdings from
 * scan's server-side ACS snapshots, the AmuletRules fee configuration, open
 * mining rounds (amulet price), and per-member synchronizer traffic status.
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

    /**
     * The AmuletRules configuration effective at [asOf] (`/v0/amulet-rules`):
     * the USD transfer-fee schedule, synchronizer traffic pricing, and the
     * active synchronizer id — the reads that feed [TransferFeeEstimator]
     * and [ValidatorClient.buyTraffic].
     *
     * AmuletRules publishes a `configSchedule` (initial value plus future
     * values with effective times); this resolves it the way the ledger does
     * (`Splice.Schedule.getValueAsOf`): the last future value effective at
     * or before [asOf], else the initial value. Current networks always have
     * an empty `futureValues` (CIP-0107 forbids scheduling new ones).
     *
     * @param asOf the instant to resolve the config schedule at (default: now).
     */
    public suspend fun amuletRulesConfig(asOf: java.time.Instant? = null): AmuletRulesConfig {
        val response = post("$baseUrl/v0/amulet-rules", buildJsonObject {})
            ?: throw ScanException("amulet rules not available (HTTP 404)")
        return decodeAmuletRulesConfig(response, asOf ?: java.time.Instant.now())
    }

    /**
     * The currently open mining rounds (`/v0/open-and-issuing-mining-rounds`),
     * in ascending round order. Each round carries the USD amulet price that
     * taps and fee conversions use. Note the list includes rounds whose
     * [OpenMiningRound.opensAt] is still in the future — pick the round a
     * submission would execute against with [latestUsable].
     */
    public suspend fun openMiningRounds(): List<OpenMiningRound> {
        val body = buildJsonObject {
            putJsonArray("cached_open_mining_round_contract_ids") {}
            putJsonArray("cached_issuing_round_contract_ids") {}
        }
        val response = post("$baseUrl/v0/open-and-issuing-mining-rounds", body)
            ?: throw ScanException("open mining rounds not available (HTTP 404)")
        return decodeOpenMiningRounds(response)
    }

    /**
     * The participant hosting [partyId] on [synchronizerId]
     * (`/v0/domains/{id}/parties/{party}/participant-id`), as a sequencer
     * member id (`PAR::name::fingerprint`) — the `memberId` that
     * [memberTrafficStatus] expects. Null if scan doesn't know the party.
     */
    public suspend fun partyParticipantId(synchronizerId: String, partyId: String): String? {
        val url = "$baseUrl/v0/domains/".toHttpUrl().newBuilder()
            .addPathSegment(synchronizerId)
            .addPathSegment("parties")
            .addPathSegment(partyId)
            .addPathSegment("participant-id")
            .build()
        return get(url.toString())?.stringField("participant_id")
    }

    /**
     * A sequencer member's extra-traffic accounting, all in bytes. Purchased
     * traffic becomes spendable once the sequencer incorporates it:
     * [totalLimitBytes] catching up to [totalPurchasedBytes] means all
     * purchases are live (either can briefly lead the other, as the two
     * numbers come from the sequencer and scan's ledger ingestion
     * respectively).
     */
    public data class MemberTrafficStatus(
        /** Extra traffic the member has consumed so far. */
        val totalConsumedBytes: Long,
        /** Extra traffic the sequencer currently grants the member. */
        val totalLimitBytes: Long,
        /** Total extra traffic ever purchased for the member. */
        val totalPurchasedBytes: Long,
    )

    /**
     * The extra-traffic status of one sequencer member
     * (`/v0/domains/{id}/members/{member}/traffic-status`) — the read that
     * shows a [ValidatorClient.buyTraffic] purchase landing.
     *
     * @param synchronizerId the synchronizer to read traffic for
     *   ([AmuletRulesConfig.activeSynchronizerId]).
     * @param memberId the participant (or mediator) whose traffic to read,
     *   `PAR::name::fingerprint` — resolve a party's participant with
     *   [partyParticipantId].
     * @return the member's traffic totals, or null if the member is unknown
     *   to the synchronizer.
     */
    public suspend fun memberTrafficStatus(
        synchronizerId: String,
        memberId: String,
    ): MemberTrafficStatus? {
        val url = "$baseUrl/v0/domains/".toHttpUrl().newBuilder()
            .addPathSegment(synchronizerId)
            .addPathSegment("members")
            .addPathSegment(memberId)
            .addPathSegment("traffic-status")
            .build()
        val status = get(url.toString())?.get("traffic_status") as? JsonObject ?: return null
        return decodeMemberTrafficStatus(status)
    }

    internal companion object {
        internal fun decodeAmuletRulesConfig(response: JsonObject, asOf: java.time.Instant): AmuletRulesConfig {
            val schedule = response.objectAt("amulet_rules_update", "contract", "payload", "configSchedule")
                ?: throw ScanException("amulet rules response missing configSchedule")
            var config = schedule["initialValue"] as? JsonObject
                ?: throw ScanException("amulet rules configSchedule missing initialValue")
            // Splice.Schedule.getValueAsOf: the last future value whose
            // effective time is at or before asOf wins; futureValues are
            // sorted ascending on-ledger.
            for (entry in (schedule["futureValues"] as? kotlinx.serialization.json.JsonArray).orEmpty()) {
                val future = entry.jsonObject
                val effectiveAt = future.stringField("_1")
                    ?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
                    ?: throw ScanException("amulet rules future value missing effective time")
                if (effectiveAt > asOf) break
                config = future["_2"] as? JsonObject
                    ?: throw ScanException("amulet rules future value missing config")
            }

            val transferConfig = config["transferConfig"] as? JsonObject
                ?: throw ScanException("amulet rules config missing transferConfig")
            val transferFee = transferConfig["transferFee"] as? JsonObject
                ?: throw ScanException("amulet rules transferConfig missing transferFee")
            val synchronizer = config["decentralizedSynchronizer"] as? JsonObject
                ?: throw ScanException("amulet rules config missing decentralizedSynchronizer")
            val fees = synchronizer["fees"] as? JsonObject
                ?: throw ScanException("amulet rules decentralizedSynchronizer missing fees")
            val limits = fees["baseRateTrafficLimits"] as? JsonObject
                ?: throw ScanException("amulet rules fees missing baseRateTrafficLimits")

            return AmuletRulesConfig(
                transferFees = TransferFeeSchedule(
                    createFeeUsd = transferConfig.decimalIn("createFee", "fee"),
                    transferFee = SteppedRate(
                        initialRate = transferFee.decimalField("initialRate"),
                        steps = (transferFee["steps"] as? kotlinx.serialization.json.JsonArray).orEmpty().map { step ->
                            SteppedRate.Step(
                                boundary = step.jsonObject.decimalField("_1"),
                                rate = step.jsonObject.decimalField("_2"),
                            )
                        },
                    ),
                    holdingFeeUsdPerRound = transferConfig.decimalIn("holdingFee", "rate"),
                    lockHolderFeeUsd = transferConfig.decimalIn("lockHolderFee", "fee"),
                ),
                synchronizerFees = SynchronizerFeeConfig(
                    extraTrafficPriceUsdPerMB = fees.decimalField("extraTrafficPrice"),
                    minTopupAmountBytes = fees.longField("minTopupAmount")
                        ?: throw ScanException("amulet rules fees missing minTopupAmount"),
                    baseRateBurstAmountBytes = limits.longField("burstAmount")
                        ?: throw ScanException("amulet rules traffic limits missing burstAmount"),
                    baseRateBurstWindow = java.time.Duration.ofNanos(
                        1000 * ((limits["burstWindow"] as? JsonObject)?.longField("microseconds")
                            ?: throw ScanException("amulet rules traffic limits missing burstWindow"))
                    ),
                    readVsWriteScalingFactor = fees.longField("readVsWriteScalingFactor")
                        ?: throw ScanException("amulet rules fees missing readVsWriteScalingFactor"),
                ),
                activeSynchronizerId = synchronizer.stringField("activeSynchronizer")
                    ?: throw ScanException("amulet rules decentralizedSynchronizer missing activeSynchronizer"),
            )
        }

        internal fun decodeOpenMiningRounds(response: JsonObject): List<OpenMiningRound> =
            (response["open_mining_rounds"] as? JsonObject ?: JsonObject(emptyMap()))
                .values
                .map { round ->
                    val payload = round.jsonObject.objectAt("contract", "payload")
                        ?: throw ScanException("open mining round missing contract payload")
                    OpenMiningRound(
                        roundNumber = (payload["round"] as? JsonObject)?.longField("number")
                            ?: throw ScanException("open mining round missing round number"),
                        amuletPriceUsd = payload.decimalField("amuletPrice"),
                        opensAt = payload.instantField("opensAt"),
                        targetClosesAt = payload.instantField("targetClosesAt"),
                    )
                }
                .sortedBy { it.roundNumber }

        internal fun decodeMemberTrafficStatus(status: JsonObject): MemberTrafficStatus {
            val actual = status["actual"] as? JsonObject
                ?: throw ScanException("traffic status missing actual")
            val target = status["target"] as? JsonObject
                ?: throw ScanException("traffic status missing target")
            return MemberTrafficStatus(
                totalConsumedBytes = actual.longField("total_consumed")
                    ?: throw ScanException("traffic status missing total_consumed"),
                totalLimitBytes = actual.longField("total_limit")
                    ?: throw ScanException("traffic status missing total_limit"),
                totalPurchasedBytes = target.longField("total_purchased")
                    ?: throw ScanException("traffic status missing total_purchased"),
            )
        }

        private fun JsonObject.objectAt(vararg path: String): JsonObject? {
            var current: JsonObject = this
            for (key in path) {
                current = current[key] as? JsonObject ?: return null
            }
            return current
        }

        private fun JsonObject.decimalField(key: String): BigDecimal =
            stringField(key)?.toBigDecimalOrNull()
                ?: throw ScanException("expected a decimal at $key")

        private fun JsonObject.decimalIn(vararg path: String): BigDecimal {
            val parent = objectAt(*path.dropLast(1).toTypedArray())
                ?: throw ScanException("missing ${path.joinToString(".")}")
            return parent.decimalField(path.last())
        }

        private fun JsonObject.instantField(key: String): java.time.Instant =
            stringField(key)?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
                ?: throw ScanException("expected a timestamp at $key")

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
