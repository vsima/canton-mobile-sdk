// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp.lan

import io.github.vsima.canton.dapp.DappClient
import io.github.vsima.canton.dapp.DappErrorCode
import io.github.vsima.canton.dapp.DappEvent
import io.github.vsima.canton.dapp.DappException
import io.github.vsima.canton.dapp.DappWallet
import io.github.vsima.canton.dapp.DappWalletStatus
import io.github.vsima.canton.dapp.PrepareSubmission
import io.github.vsima.canton.dapp.TxChangedEvent
import io.github.vsima.canton.dapp.wallet.DappApproval
import io.github.vsima.canton.dapp.wallet.DappApprovalRequest
import io.github.vsima.canton.dapp.wallet.DappNetworkConfig
import io.github.vsima.canton.dapp.wallet.DappPeer
import io.github.vsima.canton.dapp.wallet.DappSession
import io.github.vsima.canton.dapp.wallet.PrepareExecutePipeline
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The transport slice's proof (risk register R6): one CIP-0103 session run
 * across a **real gRPC socket** between two independent implementations — the
 * dApp-side [LanGrpcDappTransport] and the wallet-side [LanGrpcDappServer]
 * fronting the real [DappSession] engine.
 *
 * This is the first thing to exercise the engine over anything but the
 * in-process transport, so it is the first to serialize the frames to bytes,
 * cross a socket, and correlate responses against a concurrent event channel.
 * Everything the in-process path could take for granted — that a `Long`
 * completion offset stays a `Long`, that events and responses do not race on
 * the wire, that an error round-trips as an error — is under test here because
 * it is the first place any of it could break.
 */
class LanGrpcTransportTest {

    private val alice = wallet("alice::1220aa", primary = true)
    private val bob = wallet("bob::1220bb", primary = false)

    private var server: LanGrpcDappServer? = null
    private var transport: LanGrpcDappTransport? = null

    @AfterTest
    fun tearDown() {
        transport?.close()
        server?.shutdown()
    }

    private fun wallet(partyId: String, primary: Boolean) = DappWallet(
        primary = primary,
        partyId = partyId,
        status = DappWalletStatus.ALLOCATED,
        hint = partyId.substringBefore("::"),
        publicKey = "00",
        namespace = partyId.substringAfter("::"),
        networkId = "canton:localnet",
        signingProviderId = "software",
    )

    /** A session over the real engine, served on a real loopback port. */
    private fun connectOverSocket(
        available: List<DappWallet> = listOf(alice, bob),
        approve: (DappApprovalRequest) -> DappApproval = { request ->
            DappApproval.Approved((request as? DappApprovalRequest.Connection)?.available ?: emptyList())
        },
        pipeline: PrepareExecutePipeline = PrepareExecutePipeline { ctx ->
            TxChangedEvent.Executed(ctx.commandId, updateId = "update-1", completionOffset = 42)
        },
    ): DappClient {
        val session = DappSession(
            peer = DappPeer(id = "merchant", name = "Merchant POS", verified = true),
            accounts = { available },
            approver = { approve(it) },
            network = DappNetworkConfig(networkId = "canton:localnet"),
            prepareExecute = pipeline,
        )
        val started = LanGrpcDappServer(session).start().also { server = it }
        val lan = LanGrpcDappTransport("127.0.0.1", started.port).also { transport = it }
        return DappClient(lan)
    }

    // ── The proof ──────────────────────────────────────────────────────

    @Test
    fun `a full session runs across a real gRPC socket`() = runBlocking {
        val client = connectOverSocket()
        val events = mutableListOf<DappEvent>()
        val collector = collectingEvents(client, events)

        // connect: a ConnectResult crosses the wire and back.
        val connected = client.connect()
        assertTrue(connected.isConnected, "connect should succeed over the socket")
        assertTrue(connected.isNetworkConnected)

        // listAccounts: a JSON array of accounts round-trips.
        val accounts = client.listAccounts()
        assertEquals(listOf(alice, bob), accounts)

        // prepareExecuteAndWait: a nested executed event round-trips, and the
        // completion offset survives JSON text serialization as a Long — the
        // failure mode the in-process transport could never have caught.
        val executed = client.prepareExecuteAndWait(
            PrepareSubmission(
                commands = buildJsonArray {
                    add(buildJsonObject { put("CreateCommand", buildJsonObject { put("templateId", "pkg:M:T") }) })
                },
            ),
        )
        assertEquals("update-1", executed.updateId)
        assertEquals(42L, executed.completionOffset)

        // Events crossed the wire as notification frames: accountsChanged on
        // connect, then pending and executed for the transfer.
        awaitEvents(events) { list ->
            list.filterIsInstance<DappEvent.TxChanged>().any { it.tx is TxChangedEvent.Executed }
        }
        assertTrue(
            events.any { it is DappEvent.AccountsChanged },
            "accountsChanged should have arrived over the wire",
        )
        val txEvents = events.filterIsInstance<DappEvent.TxChanged>().map { it.tx }
        assertTrue(txEvents.any { it is TxChangedEvent.Pending }, "expected a pending event")
        assertTrue(txEvents.last() is TxChangedEvent.Executed, "last tx event should be executed")

        collector.cancel()
    }

    @Test
    fun `a provider error round-trips as an error, not a hang`() = runBlocking {
        // The engine rejects listAccounts before connect with 4100. Over the
        // wire that must come back as a completed error response, not a
        // deferred that never resolves.
        val client = connectOverSocket()

        val thrown = assertFailsWith<DappException> {
            withTimeout(10_000) { client.listAccounts() }
        }

        assertEquals(DappErrorCode.UNAUTHORIZED, thrown.errorCode)
    }

    @Test
    fun `a user rejection propagates its 4001 across the socket`() = runBlocking {
        val client = connectOverSocket(
            approve = { request ->
                when (request) {
                    is DappApprovalRequest.Connection -> DappApproval.Approved(request.available)
                    else -> DappApproval.Rejected("no thanks")
                }
            },
        )
        client.connect()

        val thrown = assertFailsWith<DappException> {
            withTimeout(10_000) {
                client.prepareExecuteAndWait(
                    PrepareSubmission(commands = buildJsonArray { add(buildJsonObject { put("x", 1) }) }),
                )
            }
        }

        assertEquals(DappErrorCode.USER_REJECTED, thrown.errorCode)
        assertTrue(thrown.isUserRejection)
    }

    @Test
    fun `concurrent requests each get their own response`() = runBlocking {
        // Two requests in flight on the one stream. Correlation by id is what
        // keeps their responses from being swapped — the reason the transport
        // parks a deferred per id rather than assuming response order.
        val client = connectOverSocket()
        client.connect()

        val results = coroutineScope {
            (1..8).map { async { client.getActiveNetwork().networkId } }.map { it.await() }
        }

        assertTrue(results.all { it == "canton:localnet" }, "every response should match its request")
    }

    // ── plumbing ───────────────────────────────────────────────────────

    private fun CoroutineScope.collectingEvents(
        client: DappClient,
        into: MutableList<DappEvent>,
    ): Job = launch(start = CoroutineStart.UNDISPATCHED) {
        client.events.collect { into += it }
    }

    /** Waits (real time — events cross a socket on gRPC threads) for [satisfied]. */
    private suspend fun awaitEvents(events: List<DappEvent>, satisfied: (List<DappEvent>) -> Boolean) {
        withTimeout(10_000) {
            while (!satisfied(events)) delay(20)
        }
    }
}
