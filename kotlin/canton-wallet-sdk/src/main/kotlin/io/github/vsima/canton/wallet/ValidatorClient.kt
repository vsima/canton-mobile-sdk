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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * A validator API call failed: non-2xx status (see [statusCode]) or a
 * malformed payload ([statusCode] is null). Taps in particular fail with
 * 400/404 until the network has an open mining round, and 429/503 under
 * load — all worth retrying.
 */
public class ValidatorException(
    public val statusCode: Int?,
    message: String,
) : RuntimeException(message)

/**
 * Client for a validator's user-facing wallet API (`.../api/validator`) —
 * the onboarding, faucet, and traffic-purchase operations a wallet app
 * drives against its own validator, authenticated as the end user.
 *
 * Every call sends a bearer token from [accessTokenProvider]; the validator
 * derives the ledger user from the token's subject claim. On LocalNet that
 * is the unsafe HS256 JWT the integration harness mints; against a real
 * validator it is the user's OAuth access token.
 *
 * Unlike [ScanClient] (public network index) and [TransferRegistryClient]
 * (registry choice contexts), this API is validator-local: it only answers
 * for users of the validator behind [baseUrl].
 */
public class ValidatorClient(
    baseUrl: String,
    private val accessTokenProvider: suspend () -> String,
    private val http: OkHttpClient = OkHttpClient(),
) {
    private val baseUrl = baseUrl.trimEnd('/')

    /** Onboarding state of the authenticated user on this validator. */
    public data class WalletUserStatus(
        /** The user's wallet party — empty until the user is onboarded. */
        val partyId: String,
        val userOnboarded: Boolean,
        val userWalletInstalled: Boolean,
    )

    /** The authenticated user's onboarding state (`/v0/wallet/user-status`). */
    public suspend fun userStatus(): WalletUserStatus {
        val response = get("$baseUrl/v0/wallet/user-status")
        return WalletUserStatus(
            partyId = response.stringField("party_id")
                ?: throw ValidatorException(null, "user status missing party_id"),
            userOnboarded = response.booleanField("user_onboarded")
                ?: throw ValidatorException(null, "user status missing user_onboarded"),
            userWalletInstalled = response.booleanField("user_wallet_installed")
                ?: throw ValidatorException(null, "user status missing user_wallet_installed"),
        )
    }

    /**
     * Onboards the authenticated user onto this validator (`/v0/register`):
     * allocates the ledger user and wallet party and installs the wallet
     * contracts. Idempotent — an already-onboarded user just gets its party
     * back.
     *
     * @return the user's wallet party id.
     */
    public suspend fun register(): String =
        post("$baseUrl/v0/register", buildJsonObject {})
            .stringField("party_id")
            ?: throw ValidatorException(null, "register response missing party_id")

    /**
     * Taps the faucet (`/v0/wallet/tap`): mints Amulet to the authenticated
     * user's wallet party. **Test networks only** — DevNet and LocalNet
     * validators expose the tap; on MainNet it fails.
     *
     * The amount is denominated in **USD**, matching the validator wallet's
     * tap: the minted Amulet quantity is `amountUsd / amuletPrice` at the
     * latest open mining round's price (rounded up). On LocalNet the price
     * is 0.005 USD/CC, so a 5 USD tap mints 1000 CC.
     *
     * Right after network bootstrap the tap fails until the first mining
     * round opens (400/404, also 429/503 under load — retry those; see
     * [ValidatorException]).
     *
     * @param amountUsd the USD value to mint as Amulet, a positive Daml
     *   Decimal (at most 10 decimal places).
     * @param commandId optional command id for deduplication; the validator
     *   generates a random one when absent.
     * @return the contract id of the minted Amulet holding — watch for it
     *   in [TokenStandardClient.listHoldings].
     */
    public suspend fun tap(amountUsd: BigDecimal, commandId: String? = null): String {
        require(amountUsd.signum() > 0) { "tap amount must be positive, got $amountUsd" }
        val body = buildJsonObject {
            put("amount", amountUsd.toPlainString())
            commandId?.let { put("command_id", it) }
        }
        return post("$baseUrl/v0/wallet/tap", body)
            .stringField("contract_id")
            ?: throw ValidatorException(null, "tap response missing contract_id")
    }

    /** A created buy-traffic request, identified for status polling. */
    public data class BuyTrafficRequest(
        /** The tracking id the request was created under — poll [buyTrafficStatus] with it. */
        val trackingId: String,
        /** Contract id of the on-ledger `BuyTrafficRequest` the wallet automation executes. */
        val requestContractId: String,
    )

    /** Where a buy-traffic request stands ([buyTrafficStatus]). */
    public sealed interface BuyTrafficStatus {
        /** Created and waiting for the validator's wallet automation to execute it. */
        public data object Created : BuyTrafficStatus

        /** The traffic has been purchased. */
        public data class Completed(
            /** Update id of the ledger transaction that purchased the traffic. */
            val transactionId: String,
        ) : BuyTrafficStatus

        /**
         * Failed permanently; no CC was spent. Retry with a *fresh* tracking
         * id — the failed one stays burned.
         */
        public data class Failed(
            val reason: FailureReason,
            /** Human-readable rejection detail, when the automation provided one. */
            val rejectionReason: String?,
        ) : BuyTrafficStatus {
            public enum class FailureReason {
                /** The wallet automation did not process the request before its expiry. */
                EXPIRED,

                /** The automation rejected it — e.g. insufficient funds or below the minimum top-up. */
                REJECTED,
            }
        }
    }

    /**
     * Requests a synchronizer extra-traffic purchase
     * (`/v0/wallet/buy-traffic-requests`), paid in Amulet from the
     * authenticated user's wallet.
     *
     * **Whose traffic:** the sequencer member that gets the bytes is the
     * *participant node hosting [receivingValidatorPartyId]* — for a wallet
     * user that is the validator's own participant, bought on the user's
     * behalf (participant-level traffic is shared by every party the
     * validator hosts). Watch it land via [ScanClient.memberTrafficStatus]
     * for that participant ([ScanClient.partyParticipantId] resolves it).
     *
     * This call only *creates* the request; the validator's wallet
     * automation executes `AmuletRules_BuyMemberTraffic` asynchronously.
     * Poll [buyTrafficStatus] with [BuyTrafficRequest.trackingId] until it
     * reports [BuyTrafficStatus.Completed] or [BuyTrafficStatus.Failed].
     *
     * The purchase burns Amulet worth `bytes × extraTrafficPrice` (USD/MB,
     * converted at the open round's amulet price) and must buy at least
     * `minTopupAmount` bytes — both published in
     * [ScanClient.amuletRulesConfig]'s
     * [SynchronizerFeeConfig][io.github.vsima.canton.wallet.SynchronizerFeeConfig].
     *
     * @param trafficAmountBytes bytes of extra traffic to buy, at least the
     *   network's `minTopupAmount` (the automation rejects smaller requests).
     * @param receivingValidatorPartyId traffic goes to the participant
     *   hosting this party — pass the user's wallet party to top up its own
     *   validator.
     * @param synchronizerId the synchronizer to buy traffic on
     *   ([AmuletRulesConfig.activeSynchronizerId]).
     * @param trackingId exactly-once key: reuse the same id when retrying a
     *   submission that may already have gone through (a duplicate answers
     *   409/429, see [ValidatorException.statusCode]); use a fresh id for a
     *   genuinely new purchase.
     * @param expiresAt when the unexecuted request lapses (compared against
     *   ledger time; default 10 minutes out).
     */
    public suspend fun buyTraffic(
        trafficAmountBytes: Long,
        receivingValidatorPartyId: String,
        synchronizerId: String,
        trackingId: String = java.util.UUID.randomUUID().toString(),
        expiresAt: java.time.Instant = java.time.Instant.now().plusSeconds(600),
    ): BuyTrafficRequest {
        require(trafficAmountBytes > 0) { "trafficAmountBytes must be positive, got $trafficAmountBytes" }
        val body = buildJsonObject {
            put("receiving_validator_party_id", receivingValidatorPartyId)
            put("domain_id", synchronizerId)
            put("traffic_amount", trafficAmountBytes)
            put("tracking_id", trackingId)
            put("expires_at", java.time.temporal.ChronoUnit.MICROS.between(java.time.Instant.EPOCH, expiresAt))
        }
        val contractId = post("$baseUrl/v0/wallet/buy-traffic-requests", body)
            .stringField("request_contract_id")
            ?: throw ValidatorException(null, "buy-traffic response missing request_contract_id")
        return BuyTrafficRequest(trackingId = trackingId, requestContractId = contractId)
    }

    /**
     * Where the buy-traffic request created under [trackingId] stands
     * (`/v0/wallet/buy-traffic-requests/{tracking_id}/status`), or null if
     * the validator knows no such request — not yet processed, or already
     * beyond the wallet's transaction-log horizon.
     */
    public suspend fun buyTrafficStatus(trackingId: String): BuyTrafficStatus? {
        val url = "$baseUrl/v0/wallet/buy-traffic-requests/".toHttpUrl().newBuilder()
            .addPathSegment(trackingId)
            .addPathSegment("status")
            .build()
        val response = try {
            post(url.toString(), buildJsonObject {})
        } catch (e: ValidatorException) {
            if (e.statusCode == 404) return null
            throw e
        }
        return when (val status = response.stringField("status")) {
            "created" -> BuyTrafficStatus.Created
            "completed" -> BuyTrafficStatus.Completed(
                transactionId = response.stringField("transaction_id")
                    ?: throw ValidatorException(null, "completed buy-traffic status missing transaction_id"),
            )
            "failed" -> BuyTrafficStatus.Failed(
                reason = when (val reason = response.stringField("failure_reason")) {
                    "expired" -> BuyTrafficStatus.Failed.FailureReason.EXPIRED
                    "rejected" -> BuyTrafficStatus.Failed.FailureReason.REJECTED
                    else -> throw ValidatorException(null, "unknown buy-traffic failure reason: $reason")
                },
                rejectionReason = response.stringField("rejection_reason"),
            )
            else -> throw ValidatorException(null, "unknown buy-traffic status: $status")
        }
    }

    private fun JsonObject.stringField(key: String): String? =
        (get(key)?.takeIf { it !is JsonNull } as? JsonPrimitive)?.content

    private fun JsonObject.booleanField(key: String): Boolean? =
        stringField(key)?.toBooleanStrictOrNull()

    private suspend fun get(url: String): JsonObject =
        execute(Request.Builder().url(url).get(), url)

    private suspend fun post(url: String, body: JsonObject): JsonObject =
        execute(
            Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody("application/json".toMediaType())),
            url,
        )

    private suspend fun execute(request: Request.Builder, url: String): JsonObject {
        val authenticated = request
            .header("Authorization", "Bearer ${accessTokenProvider()}")
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(authenticated)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val text = it.body?.string().orEmpty()
                        when {
                            !it.isSuccessful -> continuation.resumeWithException(
                                ValidatorException(it.code, "HTTP ${it.code} from $url: ${text.take(300)}")
                            )
                            text.isBlank() -> continuation.resume(JsonObject(emptyMap()))
                            else -> continuation.resume(
                                runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                                    ?: return continuation.resumeWithException(
                                        ValidatorException(null, "validator response from $url is not a JSON object")
                                    )
                            )
                        }
                    }
                }
            })
            continuation.invokeOnCancellation { call.cancel() }
        }
    }
}
