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
 * the onboarding and faucet operations a wallet app drives against its own
 * validator, authenticated as the end user.
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
     * Taps the faucet (`/v0/wallet/tap`): mints [amount] Amulet to the
     * authenticated user's wallet party. **Test networks only** — DevNet
     * and LocalNet validators expose the tap; on MainNet it fails.
     *
     * Right after network bootstrap the tap fails until the first mining
     * round opens (400/404, also 429/503 under load — retry those; see
     * [ValidatorException]).
     *
     * @param amount the Amulet amount to mint, a positive Daml Decimal
     *   (at most 10 decimal places).
     * @param commandId optional command id for deduplication; the validator
     *   generates a random one when absent.
     * @return the contract id of the minted Amulet holding — watch for it
     *   in [TokenStandardClient.listHoldings].
     */
    public suspend fun tap(amount: BigDecimal, commandId: String? = null): String {
        require(amount.signum() > 0) { "tap amount must be positive, got $amount" }
        val body = buildJsonObject {
            put("amount", amount.toPlainString())
            commandId?.let { put("command_id", it) }
        }
        return post("$baseUrl/v0/wallet/tap", body)
            .stringField("contract_id")
            ?: throw ValidatorException(null, "tap response missing contract_id")
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
