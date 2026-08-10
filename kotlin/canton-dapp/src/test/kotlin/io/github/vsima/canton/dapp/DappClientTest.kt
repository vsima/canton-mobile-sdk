// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The dApp-side client against a scripted transport.
 *
 * What is being checked is the mapping: that each typed call becomes the
 * method name CIP-0103 actually defines, carries its params by name, and
 * turns a JSON-RPC error back into the right [DappErrorCode]. The wire names
 * are asserted literally on purpose — a typo there is invisible in a
 * round-trip test where both ends share the same constant.
 */
class DappClientTest {

    /** Records requests and replies from a canned script. */
    // `events` comes first so the trailing-lambda form binds to `reply`,
    // which is what every call site below wants.
    private class ScriptedTransport(
        override val events: Flow<DappEvent> = flowOf(),
        private val reply: (JsonRpcRequest) -> JsonRpcResponse,
    ) : DappTransport {
        val sent = mutableListOf<JsonRpcRequest>()
        override suspend fun send(request: JsonRpcRequest): JsonRpcResponse {
            sent += request
            return reply(request)
        }
    }

    private fun respondingWith(result: JsonElement) = ScriptedTransport { request ->
        JsonRpcResponse.success(request.id, result)
    }

    private fun failingWith(code: Int, message: String, data: JsonElement? = null) =
        ScriptedTransport { request ->
            JsonRpcResponse.failure(request.id, JsonRpcErrorBody(code, message, data))
        }

    private val connected = buildJsonObject {
        put("isConnected", true)
        put("isNetworkConnected", true)
    }

    private val walletJson = buildJsonObject {
        put("primary", true)
        put("partyId", "alice::1220aa")
        put("status", "allocated")
        put("hint", "alice")
        put("publicKey", "00")
        put("namespace", "1220aa")
        put("networkId", "canton:localnet")
        put("signingProviderId", "software")
    }

    private val executedJson = buildJsonObject {
        put(
            "tx",
            buildJsonObject {
                put("status", "executed")
                put("commandId", "order-4711")
                put(
                    "payload",
                    buildJsonObject {
                        put("updateId", "update-1")
                        put("completionOffset", 42)
                    },
                )
            },
        )
    }

    // ── Method mapping ─────────────────────────────────────────────────

    @Test
    fun `each call uses the CIP-0103 method name`(): Unit = runBlocking {
        val transport = ScriptedTransport { request ->
            val result: JsonElement = when (request.method) {
                "connect", "isConnected" -> connected
                "status" -> buildJsonObject {
                    put("provider", buildJsonObject { put("id", "wallet") })
                    put("connection", connected)
                }
                "getActiveNetwork" -> buildJsonObject { put("networkId", "canton:localnet") }
                "listAccounts" -> buildJsonArray { add(walletJson) }
                "getPrimaryAccount" -> walletJson
                "signMessage" -> buildJsonObject { put("signature", "sig") }
                "prepareExecuteAndWait" -> executedJson
                "ledgerApi" -> buildJsonObject { put("version", "3.5.12") }
                else -> JsonPrimitive("unused")
            }
            JsonRpcResponse.success(request.id, result)
        }
        val client = DappClient(transport)

        client.connect()
        client.isConnected()
        client.status()
        client.getActiveNetwork()
        client.listAccounts()
        client.getPrimaryAccount()
        client.signMessage("hello")
        client.prepareExecute(PrepareSubmission(commands = buildJsonArray {}))
        client.prepareExecuteAndWait(PrepareSubmission(commands = buildJsonArray {}))
        client.ledgerApi(LedgerApiRequest(LedgerApiMethod.GET, "/v2/version"))
        client.disconnect()

        assertEquals(
            listOf(
                "connect", "isConnected", "status", "getActiveNetwork", "listAccounts",
                "getPrimaryAccount", "signMessage", "prepareExecute", "prepareExecuteAndWait",
                "ledgerApi", "disconnect",
            ),
            transport.sent.map { it.method },
        )
    }

    @Test
    fun `params travel by name, not as a positional array`(): Unit = runBlocking {
        val transport = respondingWith(buildJsonObject { put("signature", "sig") })

        DappClient(transport).signMessage("hello")

        val params = transport.sent.single().params
        assertTrue(params is JsonObject, "params should be an object, was $params")
        assertEquals("hello", (params["message"] as JsonPrimitive).content)
    }

    @Test
    fun `requests carry a distinct id`(): Unit = runBlocking {
        val transport = respondingWith(connected)
        val client = DappClient(transport)

        client.connect()
        client.connect()

        val ids = transport.sent.map { it.id }
        assertEquals(2, ids.toSet().size, "ids should differ, were $ids")
        assertTrue(transport.sent.none { it.isNotification }, "requests must not be notifications")
    }

    // ── Errors ─────────────────────────────────────────────────────────

    @Test
    fun `a user rejection surfaces as 4001`(): Unit = runBlocking {
        val client = DappClient(failingWith(4001, "User rejected the request"))

        val thrown = assertFailsWith<DappException> {
            client.prepareExecuteAndWait(PrepareSubmission(commands = buildJsonArray {}))
        }

        assertEquals(DappErrorCode.USER_REJECTED, thrown.errorCode)
        assertTrue(thrown.isUserRejection)
    }

    @Test
    fun `an error keeps its data payload`(): Unit = runBlocking {
        val data = buildJsonObject { put("traceId", "edb2e49d") }
        val client = DappClient(failingWith(-32003, "Transaction rejected", data))

        val thrown = assertFailsWith<DappException> {
            client.prepareExecuteAndWait(PrepareSubmission(commands = buildJsonArray {}))
        }

        assertEquals(DappErrorCode.TRANSACTION_REJECTED, thrown.errorCode)
        assertEquals(data, thrown.data)
    }

    @Test
    fun `an unrecognised code degrades to internal without losing the message`(): Unit = runBlocking {
        val client = DappClient(failingWith(-31999, "Something new"))

        val thrown = assertFailsWith<DappException> { client.connect() }

        // Better to keep the text and lose the exact code than to fail
        // decoding a wallet that is simply newer than this SDK.
        assertEquals(DappErrorCode.INTERNAL, thrown.errorCode)
        assertTrue(thrown.message!!.contains("Something new"))
        assertTrue(thrown.message!!.contains("-31999"))
    }

    @Test
    fun `prepareExecuteAndWait refuses a non-executed transaction`(): Unit = runBlocking {
        val pending = buildJsonObject {
            put("tx", buildJsonObject { put("status", "pending"); put("commandId", "c1") })
        }
        val client = DappClient(respondingWith(pending))

        val thrown = assertFailsWith<DappException> {
            client.prepareExecuteAndWait(PrepareSubmission(commands = buildJsonArray {}))
        }

        assertEquals(DappErrorCode.INVALID_PARAMS, thrown.errorCode)
    }

    // ── Results and events ─────────────────────────────────────────────

    @Test
    fun `prepareExecuteAndWait decodes the executed transaction`(): Unit = runBlocking {
        val client = DappClient(respondingWith(executedJson))

        val executed = client.prepareExecuteAndWait(PrepareSubmission(commands = buildJsonArray {}))

        assertEquals("order-4711", executed.commandId)
        assertEquals("update-1", executed.updateId)
        assertEquals(42L, executed.completionOffset)
    }

    @Test
    fun `events pass through from the transport`(): Unit = runBlocking {
        val event = DappEvent.TxChanged(TxChangedEvent.Pending("c1"))
        val client = DappClient(ScriptedTransport(flowOf(event)) { JsonRpcResponse.success(it.id, connected) })

        assertEquals(listOf(event), client.events.toList())
    }

    @Test
    fun `a transport without events yields an empty flow`(): Unit = runBlocking {
        val bare = object : DappTransport {
            override suspend fun send(request: JsonRpcRequest) = JsonRpcResponse.success(request.id, connected)
        }

        assertEquals(emptyList(), DappClient(bare).events.toList())
    }

    @Test
    fun `an event notification decodes back into a typed event`(): Unit = runBlocking {
        val original = DappEvent.TxChanged(
            TxChangedEvent.Executed("order-4711", "update-1", 42),
        )

        val notification = DappJson.encodeEvent(original)
        val decoded = DappJson.decodeEvent(notification)

        assertTrue(notification.isNotification, "events must travel without an id")
        assertEquals("txChanged", notification.method)
        assertEquals(original, decoded)
    }

    @Test
    fun `a non-event notification decodes to null rather than throwing`(): Unit = runBlocking {
        val decoded = DappJson.decodeEvent(
            JsonRpcRequest("somethingElse", buildJsonObject { put("a", 1) }),
        )

        assertEquals(null, decoded)
    }
}
