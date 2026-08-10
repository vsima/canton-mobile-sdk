// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp.wallet

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.vsima.canton.dapp.DappErrorCode
import io.github.vsima.canton.dapp.DappException
import io.github.vsima.canton.dapp.LedgerApiMethod
import io.github.vsima.canton.dapp.LedgerApiRequest
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The JSON Ledger API client, against a real HTTP server.
 *
 * `com.sun.net.httpserver` rather than a mock-web-server dependency: it is in
 * the JDK, it speaks real HTTP, and the point of these tests is the wire
 * behaviour — the URL that gets built, the header that gets attached, and the
 * mapping of Canton's two distinct error shapes onto CIP-0103 codes.
 */
class JsonLedgerApiClientTest {

    private class Recorded(
        val method: String,
        val path: String,
        val query: String?,
        val authorization: String?,
        val body: String,
    )

    /** Serves [status]/[response] and records what it was asked. */
    private fun withServer(
        status: Int = 200,
        response: String = "{}",
        block: (JsonLedgerApiClient, () -> Recorded?) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var recorded: Recorded? = null
        server.createContext("/") { exchange: HttpExchange ->
            recorded = Recorded(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                query = exchange.requestURI.query,
                authorization = exchange.requestHeaders.getFirst("authorization"),
                body = exchange.requestBody.readBytes().decodeToString(),
            )
            val bytes = response.encodeToByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = JsonLedgerApiClient(
                baseUrl = "http://127.0.0.1:${server.address.port}",
                accessTokenProvider = { "test-token" },
            )
            block(client) { recorded }
        } finally {
            server.stop(0)
        }
    }

    // ── Requests ───────────────────────────────────────────────────────

    @Test
    fun `post sends the body with a bearer token`(): Unit = runBlocking {
        withServer(response = """{"ok":true}""") { client, recorded ->
            val result = runBlocking { client.post("/v2/interactive-submission/prepare", buildJsonObject { put("commandId", "c1") }) }

            val request = recorded()!!
            assertEquals("POST", request.method)
            assertEquals("/v2/interactive-submission/prepare", request.path)
            assertEquals("Bearer test-token", request.authorization)
            assertTrue(request.body.contains("\"commandId\":\"c1\""))
            assertEquals(true, (result as JsonObject)["ok"]!!.jsonPrimitive.content.toBoolean())
        }
    }

    @Test
    fun `ledgerApi get carries query parameters and no body`(): Unit = runBlocking {
        withServer(response = """{"version":"3.5.12"}""") { client, recorded ->
            runBlocking {
                client.call(
                    LedgerApiRequest(
                        requestMethod = LedgerApiMethod.GET,
                        resource = "/v2/version",
                        query = buildJsonObject { put("limit", "100") },
                    )
                )
            }

            val request = recorded()!!
            assertEquals("GET", request.method)
            assertEquals("/v2/version", request.path)
            assertEquals("limit=100", request.query)
            assertEquals("", request.body)
        }
    }

    @Test
    fun `a resource without a leading slash still resolves`(): Unit = runBlocking {
        withServer { client, recorded ->
            runBlocking { client.call(LedgerApiRequest(LedgerApiMethod.GET, "v2/version")) }

            assertEquals("/v2/version", recorded()!!.path)
        }
    }

    @Test
    fun `each call mints a fresh token`(): Unit = runBlocking {
        var minted = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = "{}".encodeToByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = JsonLedgerApiClient(
                baseUrl = "http://127.0.0.1:${server.address.port}",
                accessTokenProvider = { "token-${++minted}" },
            )
            client.call(LedgerApiRequest(LedgerApiMethod.GET, "/v2/version"))
            client.call(LedgerApiRequest(LedgerApiMethod.GET, "/v2/version"))
        } finally {
            server.stop(0)
        }

        // A token captured once outlives its own expiry; the SDK learned this
        // the hard way on the gRPC side.
        assertEquals(2, minted)
    }

    // ── Error mapping ──────────────────────────────────────────────────

    @Test
    fun `the JSON decoding layer maps onto invalid params`(): Unit = runBlocking {
        // A bare string body, no JSON: this is the shape Canton returns when
        // the envelope itself failed to decode, and it means *our* request was
        // malformed — never the dApp's commands.
        withServer(
            status = 400,
            response = "Invalid value for: body (Missing required field at 'synchronizerId')",
        ) { client, _ ->
            val thrown = assertFailsWith<DappException> {
                runBlocking { client.post("/v2/interactive-submission/prepare", buildJsonObject {}) }
            }

            assertEquals(DappErrorCode.INVALID_PARAMS, thrown.errorCode)
            assertTrue(thrown.message!!.contains("synchronizerId"))
        }
    }

    @Test
    fun `a participant error maps by grpcCodeValue and keeps its traceId`(): Unit = runBlocking {
        withServer(
            status = 400,
            response = """{"code":"MISSING_FIELD","cause":"missing a mandatory field: commands",
                           "traceId":"edb2e49d","grpcCodeValue":3}""",
        ) { client, _ ->
            val thrown = assertFailsWith<DappException> {
                runBlocking { client.post("/v2/interactive-submission/prepare", buildJsonObject {}) }
            }

            assertEquals(DappErrorCode.INVALID_PARAMS, thrown.errorCode)
            assertTrue(thrown.message!!.contains("MISSING_FIELD"))
            // The traceId is what a user quotes in a support request; losing it
            // to make the error "clean" would be a bad trade.
            assertEquals(
                "edb2e49d",
                ((thrown.data as JsonObject)["traceId"] as JsonPrimitive).content,
            )
        }
    }

    @Test
    fun `a redacted permission error maps to unauthorized`(): Unit = runBlocking {
        // Exactly what LocalNet returns for `app-user` reading /v2/parties:
        // grpcCodeValue 7, errorCategory -1, and no useful message.
        withServer(
            status = 403,
            response = """{"code":"NA","cause":"A security-sensitive error has been received",
                           "errorCategory":-1,"grpcCodeValue":7}""",
        ) { client, _ ->
            val thrown = assertFailsWith<DappException> {
                runBlocking { client.call(LedgerApiRequest(LedgerApiMethod.GET, "/v2/parties")) }
            }

            assertEquals(DappErrorCode.UNAUTHORIZED, thrown.errorCode)
        }
    }

    @Test
    fun `an unreachable ledger is an internal error, not a protocol one`(): Unit = runBlocking {
        // Port 1 is reliably closed. A dApp must be able to tell "the wallet
        // said no" from "the wallet could not reach the ledger".
        val client = JsonLedgerApiClient(baseUrl = "http://127.0.0.1:1")

        val thrown = assertFailsWith<DappException> {
            client.call(LedgerApiRequest(LedgerApiMethod.GET, "/v2/version"))
        }

        assertEquals(DappErrorCode.INTERNAL, thrown.errorCode)
    }
}
