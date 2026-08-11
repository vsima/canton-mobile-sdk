// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp.wallet

import io.github.vsima.canton.dapp.DappErrorCode
import io.github.vsima.canton.dapp.DappException
import io.github.vsima.canton.dapp.LedgerApiMethod
import io.github.vsima.canton.dapp.LedgerApiRequest
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A minimal client for Canton's **JSON** Ledger API, used by the dApp
 * provider for the two things that must go over JSON rather than gRPC:
 * `interactive-submission/prepare` (see [JsonPrepareExecutePipeline]) and the
 * `ledgerApi` proxy method.
 *
 * Why JSON at all, in an SDK whose whole point is the gRPC Ledger API: a dApp
 * authors its commands as **JSON Ledger API** shapes, and the participant
 * must see them exactly as authored. Re-encoding them into proto would add a
 * drift surface for every Daml value shape — precisely the risk the hash
 * verification exists to catch. So the commands travel as the dApp wrote
 * them, and only the prepared *result* crosses back into proto (which it can,
 * because it crosses as bytes — see [JsonPrepareExecutePipeline]).
 *
 * ### Field requirements are stricter than the published schema
 *
 * Canton 3.5.12 serves its own OpenAPI at `/docs/openapi`, and **that
 * document under-reports required fields.** Verified live: `prepare`
 * documents `commandId`, `commands` and `actAs` as required, then rejects a
 * request carrying exactly those three because `synchronizerId` and
 * `packageIdSelectionPreference` are missing too. Do not generate a client
 * from that document and expect it to work; send every field, empty
 * collections included, as [JsonPrepareExecutePipeline] does.
 */
public class JsonLedgerApiClient(
    private val baseUrl: String,
    private val accessTokenProvider: (suspend () -> String)? = null,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * POSTs [body] to [path] and returns the decoded response.
     *
     * Errors are mapped onto CIP-0103 codes by [mapError]; the participant's
     * `traceId` rides along in [DappException.data] so a dApp can quote it.
     */
    public suspend fun post(path: String, body: JsonObject): JsonElement =
        execute(
            Request.Builder()
                .url(resolve(path, query = null))
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        )

    /** Performs a dApp's `ledgerApi` request with the wallet's credentials. */
    public suspend fun call(request: LedgerApiRequest): JsonElement {
        val url = resolve(request.resource, request.query)
        val builder = Request.Builder().url(url)
        val payload = request.body
            ?.takeIf { it !is JsonNull }
            ?.toString()
            ?.toRequestBody(JSON_MEDIA_TYPE)
        when (request.requestMethod) {
            LedgerApiMethod.GET -> builder.get()
            LedgerApiMethod.DELETE -> if (payload == null) builder.delete() else builder.delete(payload)
            LedgerApiMethod.POST -> builder.post(payload ?: EMPTY_BODY)
            LedgerApiMethod.PUT -> builder.put(payload ?: EMPTY_BODY)
            LedgerApiMethod.PATCH -> builder.patch(payload ?: EMPTY_BODY)
        }
        return execute(builder.build())
    }

    private fun resolve(resource: String, query: JsonObject?): HttpUrl {
        val base = baseUrl.trimEnd('/')
        // The same canonical form the policy judged. Building the URL from
        // anything else would let the two disagree, which is precisely how a
        // prefix allowlist gets walked out of.
        val path = canonicalLedgerApiPath(resource)
            ?: throw DappException(
                DappErrorCode.INVALID_PARAMS,
                "ledger API resource must be a plain path, was: $resource",
            )
        val url = "$base$path".toHttpUrlOrNull()
            ?: throw DappException(
                DappErrorCode.INVALID_PARAMS,
                "not a valid ledger API URL: $base$path",
            )
        if (query.isNullOrEmpty()) return url
        return url.newBuilder().apply {
            for ((key, value) in query) {
                addQueryParameter(key, (value as? JsonPrimitive)?.content ?: value.toString())
            }
        }.build()
    }

    private suspend fun execute(request: Request): JsonElement = withContext(Dispatchers.IO) {
        val authorized = accessTokenProvider?.let { provider ->
            // Minted per call rather than captured once: a token cached for
            // the life of a session outlives its own expiry.
            request.newBuilder().header("authorization", "Bearer ${provider()}").build()
        } ?: request

        val response = try {
            httpClient.newCall(authorized).execute()
        } catch (e: IOException) {
            throw DappException(
                DappErrorCode.INTERNAL,
                "could not reach the JSON Ledger API: ${e.message}",
                cause = e,
            )
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw mapError(it.code, text)
            if (text.isBlank()) JsonNull else json.parseToJsonElement(text)
        }
    }

    /**
     * Canton error → CIP-0103 error.
     *
     * Two response shapes, and telling them apart is most of the debugging
     * value: a bare `Invalid value for: body (...)` came from the JSON
     * decoding layer and means *our* envelope was malformed, never the dApp's
     * commands. Anything carrying `code` and `traceId` reached the ledger.
     *
     * The mapping key is `grpcCodeValue`, not `errorCategory` — the latter is
     * `-1` on security-redacted errors, which is exactly when you most need
     * to know what happened.
     */
    private fun mapError(status: Int, text: String): DappException {
        val body = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return DappException(
                DappErrorCode.INVALID_PARAMS,
                "ledger API rejected the request (HTTP $status): ${text.take(500)}",
            )
        val grpcCode = (body["grpcCodeValue"] as? JsonPrimitive)?.content?.toIntOrNull()
        val cause = (body["cause"] as? JsonPrimitive)?.content
        val code = (body["code"] as? JsonPrimitive)?.content
        return DappException(
            errorCode = when (grpcCode) {
                3 -> DappErrorCode.INVALID_PARAMS // INVALID_ARGUMENT
                5 -> DappErrorCode.INVALID_INPUT // NOT_FOUND
                7, 16 -> DappErrorCode.UNAUTHORIZED // PERMISSION_DENIED, UNAUTHENTICATED
                9 -> DappErrorCode.TRANSACTION_REJECTED // FAILED_PRECONDITION
                14 -> DappErrorCode.DISCONNECTED // UNAVAILABLE
                else -> if (status in 400..499) DappErrorCode.INVALID_INPUT else DappErrorCode.INTERNAL
            },
            message = listOfNotNull(code, cause).joinToString(": ").ifBlank {
                "ledger API error (HTTP $status)"
            },
            // Carries traceId/correlationId through untouched — a wallet
            // should not have to model Canton's error envelope to let a dApp
            // quote it in a support request.
            data = body,
        )
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val EMPTY_BODY = "".toRequestBody("application/json".toMediaType())
    }
}

/**
 * `ledgerApi` backed by [JsonLedgerApiClient].
 *
 * The wallet supplies the credentials, so this is the authenticating proxy
 * CIP-0103 describes. What it must *not* become is an open door: pair it with
 * a [LedgerApiPolicy] — [DappSession] applies [LedgerApiPolicy.ReadOnly] by
 * default — because the wallet's token is considerably more privileged than
 * anything a dApp should wield through it.
 */
public class HttpLedgerApiProxy(
    private val client: JsonLedgerApiClient,
) : LedgerApiProxy {
    override suspend fun call(request: LedgerApiRequest): JsonElement = client.call(request)
}
