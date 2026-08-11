// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp.wallet

import io.github.vsima.canton.dapp.ConnectResult
import io.github.vsima.canton.dapp.DappErrorCode
import io.github.vsima.canton.dapp.DappException
import io.github.vsima.canton.dapp.DappEvent
import io.github.vsima.canton.dapp.DappJson
import io.github.vsima.canton.dapp.DappMethod
import io.github.vsima.canton.dapp.DappWallet
import io.github.vsima.canton.dapp.DappWalletStatus
import io.github.vsima.canton.dapp.JsonRpcRequest
import io.github.vsima.canton.dapp.JsonRpcResponse
import io.github.vsima.canton.dapp.LedgerApiMethod
import io.github.vsima.canton.dapp.LedgerApiRequest
import io.github.vsima.canton.dapp.MessageSignatureEvent
import io.github.vsima.canton.dapp.PrepareSubmission
import io.github.vsima.canton.dapp.TxChangedEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The provider engine: session lifecycle, per-peer grants, the EIP-1474
 * error paths, and event ordering.
 *
 * The grant tests carry the weight. `listAccounts` returning the right
 * accounts proves very little on its own — it is `listAccounts` *before*
 * approval being refused, and `actAs` naming an unapproved party being
 * refused, that show the wallet is not simply doing what it is told.
 */
class DappSessionTest {

    // ── Fixtures ───────────────────────────────────────────────────────

    private val alice = wallet("alice::1220aa", primary = true)
    private val bob = wallet("bob::1220bb", primary = false)
    private val peer = DappPeer(id = "example", name = "Example dApp", verified = false)
    private val network = DappNetworkConfig(networkId = "canton:localnet")

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

    /** Records what it was asked and answers with a canned decision. */
    private class Approver(
        private val answer: (DappApprovalRequest) -> DappApproval,
    ) : DappApprovalDelegate {
        val seen = mutableListOf<DappApprovalRequest>()
        override suspend fun approve(request: DappApprovalRequest): DappApproval {
            seen += request
            return answer(request)
        }
    }

    private fun session(
        available: List<DappWallet> = listOf(alice, bob),
        approver: DappApprovalDelegate = Approver { DappApproval.Approved(available) },
        messageSigner: DappMessageSigner? = DappMessageSigner { _, message -> "sig:$message" },
        pipeline: PrepareExecutePipeline? = PrepareExecutePipeline { ctx ->
            TxChangedEvent.Executed(ctx.commandId, updateId = "update-1", completionOffset = 42)
        },
        ledgerApi: LedgerApiProxy? = LedgerApiProxy { buildJsonObject { put("ok", true) } },
        ledgerApiPolicy: LedgerApiPolicy = LedgerApiPolicy.ReadOnly,
        signMessageMinInterval: Duration = Duration.ZERO,
    ) = DappSession(
        peer = peer,
        accounts = { available },
        approver = approver,
        network = network,
        messageSigner = messageSigner,
        prepareExecute = pipeline,
        ledgerApi = ledgerApi,
        ledgerApiPolicy = ledgerApiPolicy,
        signMessageMinInterval = signMessageMinInterval,
    )

    private fun request(method: DappMethod, params: JsonElement? = null, id: Int = 1) =
        JsonRpcRequest(method.wire, params, JsonPrimitive(id))

    // `error` names the response property here, so kotlin.error() would not
    // resolve — hence the explicit throw.
    private fun JsonRpcResponse.errorCode(): Int =
        error?.code ?: throw AssertionError("expected an error response, got result=$result")

    /**
     * Collects events emitted while [body] runs.
     *
     * `UNDISPATCHED` is what makes this deterministic: the collector runs
     * inline until it suspends inside `collect`, so it has subscribed before
     * [body] emits anything. The shared flow has no replay, so a collector
     * that started later would simply miss them and the test would pass for
     * the wrong reason.
     */
    private fun CoroutineScope.collectingEvents(
        session: DappSession,
        into: MutableList<DappEvent>,
    ): Job = launch(start = CoroutineStart.UNDISPATCHED) {
        session.events.collect { into += it }
    }

    /**
     * Waits until [events] holds at least [count] entries.
     *
     * Subscribing is not enough. `emit` buffers into the subscriber's slot,
     * but the collector still has to be *scheduled* to move them into the
     * list — and `handle` can run start to finish without suspending, so
     * under `runBlocking`'s single-threaded event loop it never gets a turn.
     * Yielding hands it one. No wall-clock waiting, so this cannot flake on
     * a slow machine; it fails with the events it did see.
     */
    private suspend fun awaitEvents(events: List<DappEvent>, count: Int) {
        repeat(1000) {
            if (events.size >= count) return
            yield()
        }
        throw AssertionError("expected at least $count events, saw ${events.size}: $events")
    }

    // ── Connection lifecycle ───────────────────────────────────────────

    @Test
    fun `connect grants the approved accounts and announces them`(): Unit = runBlocking {
        val session = session(approver = Approver { DappApproval.Approved(listOf(alice)) })
        val events = mutableListOf<DappEvent>()
        val collector = collectingEvents(session, events)

        val response = session.handle(request(DappMethod.CONNECT))
        val result = DappJson.decodeConnectResult(response.resultOrThrow())

        assertTrue(result.isConnected)
        assertTrue(result.isNetworkConnected)
        assertEquals(listOf(alice), session.grantedAccounts())
        awaitEvents(events, 1)
        assertEquals<List<DappEvent>>(listOf(DappEvent.AccountsChanged(listOf(alice))), events)
        collector.cancel()
    }

    @Test
    fun `a rejected connect reports the reason instead of erroring`(): Unit = runBlocking {
        val session = session(approver = Approver { DappApproval.Rejected("Not now") })

        val result = DappJson.decodeConnectResult(
            session.handle(request(DappMethod.CONNECT)).resultOrThrow(),
        )

        // connect is the one place a refusal is a *result*, not a 4001: the
        // dApp asked whether it may connect and got a truthful "no".
        assertFalse(result.isConnected)
        assertEquals("Not now", result.reason)
        assertTrue(session.grantedAccounts().isEmpty())
    }

    @Test
    fun `approving zero accounts is not a connection`(): Unit = runBlocking {
        val session = session(approver = Approver { DappApproval.Approved(emptyList()) })

        val result = DappJson.decodeConnectResult(
            session.handle(request(DappMethod.CONNECT)).resultOrThrow(),
        )

        assertFalse(result.isConnected)
        assertTrue(session.grantedAccounts().isEmpty())
    }

    @Test
    fun `an approval cannot invent accounts the wallet never offered`(): Unit = runBlocking {
        val intruder = wallet("mallory::1220cc", primary = false)
        val session = session(
            available = listOf(alice),
            approver = Approver { DappApproval.Approved(listOf(alice, intruder)) },
        )

        val response = session.handle(request(DappMethod.CONNECT))

        assertEquals(DappErrorCode.INTERNAL.code, response.errorCode())
        assertTrue(session.grantedAccounts().isEmpty())
    }

    @Test
    fun `disconnect clears the grant and is idempotent`(): Unit = runBlocking {
        val session = session()
        session.handle(request(DappMethod.CONNECT))

        val first = session.handle(request(DappMethod.DISCONNECT))
        val second = session.handle(request(DappMethod.DISCONNECT))

        assertTrue(first.ok)
        assertTrue(second.ok)
        assertTrue(session.grantedAccounts().isEmpty())
    }

    // ── Grants ─────────────────────────────────────────────────────────

    @Test
    fun `listAccounts before approval is unauthorized`(): Unit = runBlocking {
        val response = session().handle(request(DappMethod.LIST_ACCOUNTS))

        assertEquals(DappErrorCode.UNAUTHORIZED.code, response.errorCode())
    }

    @Test
    fun `listAccounts returns only the accounts granted to this peer`(): Unit = runBlocking {
        val session = session(
            available = listOf(alice, bob),
            approver = Approver { DappApproval.Approved(listOf(bob)) },
        )
        session.handle(request(DappMethod.CONNECT))

        val accounts = DappJson.decodeAccounts(
            session.handle(request(DappMethod.LIST_ACCOUNTS)).resultOrThrow(),
        )

        // The wallet holds alice too. This peer was not granted her.
        assertEquals(listOf(bob), accounts)
    }

    @Test
    fun `getPrimaryAccount falls back to the first granted account`(): Unit = runBlocking {
        val session = session(approver = Approver { DappApproval.Approved(listOf(bob)) })
        session.handle(request(DappMethod.CONNECT))

        val account = DappJson.decodeWallet(
            session.handle(request(DappMethod.GET_PRIMARY_ACCOUNT)).resultOrThrow(),
        )

        // bob is not flagged primary, but he is all this peer has.
        assertEquals(bob, account)
    }

    @Test
    fun `status is answerable before connecting and hides the network`(): Unit = runBlocking {
        val status = DappJson.decodeStatus(
            session().handle(request(DappMethod.STATUS)).resultOrThrow(),
        )

        assertFalse(status.connection.isConnected)
        assertEquals(null, status.network)
        assertEquals(null, status.session)
    }

    @Test
    fun `the dApp-visible network never carries an access token`(): Unit = runBlocking {
        val configured = DappNetworkConfig(
            networkId = "canton:localnet",
            jsonApiBaseUrl = "http://127.0.0.1:2975",
            accessTokenProvider = { "a-real-token" },
        )
        val session = DappSession(peer, { listOf(alice) }, Approver { DappApproval.Approved(listOf(alice)) }, configured)
        session.handle(request(DappMethod.CONNECT))

        val network = DappJson.decodeNetwork(
            session.handle(request(DappMethod.GET_ACTIVE_NETWORK)).resultOrThrow(),
        )

        assertEquals("http://127.0.0.1:2975", network.ledgerApi)
        assertEquals(null, network.accessToken)
    }

    // ── Method surface ─────────────────────────────────────────────────

    @Test
    fun `an unknown method is 4200`(): Unit = runBlocking {
        val response = session().handle(JsonRpcRequest("canton_connect", null, JsonPrimitive(1)))

        // The name a competing spec invented. It is not a Canton method.
        assertEquals(DappErrorCode.UNSUPPORTED_METHOD.code, response.errorCode())
    }

    @Test
    fun `an event name sent as a request is 4200`(): Unit = runBlocking {
        val response = session().handle(request(DappMethod.TX_CHANGED))

        assertEquals(DappErrorCode.UNSUPPORTED_METHOD.code, response.errorCode())
    }

    @Test
    fun `a method needing params without them is 32602`(): Unit = runBlocking {
        val session = session()
        session.handle(request(DappMethod.CONNECT))

        val response = session.handle(request(DappMethod.SIGN_MESSAGE))

        assertEquals(DappErrorCode.INVALID_PARAMS.code, response.errorCode())
    }

    @Test
    fun `an unimplemented collaborator makes its method 4200`(): Unit = runBlocking {
        val session = session(messageSigner = null)
        session.handle(request(DappMethod.CONNECT))

        val response = session.handle(
            request(DappMethod.SIGN_MESSAGE, buildJsonObject { put("message", "hi") }),
        )

        assertEquals(DappErrorCode.UNSUPPORTED_METHOD.code, response.errorCode())
    }

    // ── signMessage ────────────────────────────────────────────────────

    @Test
    fun `signMessage emits pending then signed`(): Unit = runBlocking {
        val session = session()
        session.handle(request(DappMethod.CONNECT))
        val events = mutableListOf<DappEvent>()
        val collector = collectingEvents(session, events)

        val result = session.handle(
            request(DappMethod.SIGN_MESSAGE, buildJsonObject { put("message", "hello") }),
        ).resultOrThrow()

        assertEquals("sig:hello", DappJson.decodeSignMessageResult(result).signature)
        awaitEvents(events, 2)
        val statuses = events.filterIsInstance<DappEvent.MessageSignature>().map { it.signature }
        assertEquals(2, statuses.size, "expected pending then signed, got $statuses")
        assertTrue(statuses[0] is MessageSignatureEvent.Pending)
        assertTrue(statuses[1] is MessageSignatureEvent.Signed)
        // The id must be stable across the pair, or a dApp cannot correlate them.
        assertEquals(statuses[0].messageId, statuses[1].messageId)
        collector.cancel()
    }

    @Test
    fun `a declined signMessage is 4001 and emits failed`(): Unit = runBlocking {
        val session = session(
            approver = Approver { req ->
                if (req is DappApprovalRequest.Message) DappApproval.Rejected() else DappApproval.Approved(listOf(alice))
            },
        )
        session.handle(request(DappMethod.CONNECT))
        val events = mutableListOf<DappEvent>()
        val collector = collectingEvents(session, events)

        val response = session.handle(
            request(DappMethod.SIGN_MESSAGE, buildJsonObject { put("message", "hello") }),
        )

        assertEquals(DappErrorCode.USER_REJECTED.code, response.errorCode())
        awaitEvents(events, 2)
        assertTrue(
            events.filterIsInstance<DappEvent.MessageSignature>()
                .any { it.signature is MessageSignatureEvent.Failed },
        )
        collector.cancel()
    }

    @Test
    fun `signMessage is rate-limited per session`(): Unit = runBlocking {
        val session = session(signMessageMinInterval = 1.hours)
        session.handle(request(DappMethod.CONNECT))
        val params = buildJsonObject { put("message", "hello") }

        val first = session.handle(request(DappMethod.SIGN_MESSAGE, params))
        val second = session.handle(request(DappMethod.SIGN_MESSAGE, params))

        assertTrue(first.ok)
        assertEquals(DappErrorCode.INVALID_INPUT.code, second.errorCode())
    }

    // ── prepareExecute ─────────────────────────────────────────────────

    private val commands: JsonArray = buildJsonArray {
        add(buildJsonObject { put("CreateCommand", buildJsonObject { put("templateId", "pkg:M:T") }) })
    }

    private fun submission(actAs: List<String> = emptyList()) =
        DappJson.encode(PrepareSubmission(commands = commands, actAs = actAs))

    @Test
    fun `prepareExecuteAndWait returns the executed transaction`(): Unit = runBlocking {
        val session = session()
        session.handle(request(DappMethod.CONNECT))

        val executed = DappJson.decodeExecutedResult(
            session.handle(request(DappMethod.PREPARE_EXECUTE_AND_WAIT, submission())).resultOrThrow(),
        )

        assertEquals("update-1", executed.updateId)
        assertEquals(42L, executed.completionOffset)
    }

    @Test
    fun `prepareExecute returns null and reports through events`(): Unit = runBlocking {
        val session = session()
        session.handle(request(DappMethod.CONNECT))
        val events = mutableListOf<DappEvent>()
        val collector = collectingEvents(session, events)

        val response = session.handle(request(DappMethod.PREPARE_EXECUTE, submission()))

        assertTrue(response.ok)
        awaitEvents(events, 2)
        val statuses = events.filterIsInstance<DappEvent.TxChanged>().map { it.tx }
        assertTrue(statuses.first() is TxChangedEvent.Pending)
        assertTrue(statuses.last() is TxChangedEvent.Executed)
        collector.cancel()
    }

    @Test
    fun `actAs naming a party outside the grant is unauthorized`(): Unit = runBlocking {
        val session = session(
            available = listOf(alice, bob),
            approver = Approver { DappApproval.Approved(listOf(alice)) },
        )
        session.handle(request(DappMethod.CONNECT))

        val response = session.handle(
            request(DappMethod.PREPARE_EXECUTE_AND_WAIT, submission(actAs = listOf(bob.partyId))),
        )

        // The heart of the proxy design: a dApp may *request* an actAs, it
        // may not choose one. bob exists in the wallet — this peer was not
        // granted him, and naming him must not be enough.
        assertEquals(DappErrorCode.UNAUTHORIZED.code, response.errorCode())
    }

    @Test
    fun `actAs within the grant selects that account`(): Unit = runBlocking {
        var actedAs: String? = null
        val session = session(
            pipeline = { ctx ->
                actedAs = ctx.actAs.partyId
                TxChangedEvent.Executed(ctx.commandId, "update-1", 42)
            },
        )
        session.handle(request(DappMethod.CONNECT))

        session.handle(request(DappMethod.PREPARE_EXECUTE_AND_WAIT, submission(actAs = listOf(bob.partyId))))

        assertEquals(bob.partyId, actedAs)
    }

    @Test
    fun `multi-party actAs is refused`(): Unit = runBlocking {
        val session = session()
        session.handle(request(DappMethod.CONNECT))

        val response = session.handle(
            request(
                DappMethod.PREPARE_EXECUTE_AND_WAIT,
                submission(actAs = listOf(alice.partyId, bob.partyId)),
            ),
        )

        assertEquals(DappErrorCode.INVALID_PARAMS.code, response.errorCode())
    }

    @Test
    fun `a declined transaction is 4001 and emits failed`(): Unit = runBlocking {
        val session = session(
            approver = Approver { req ->
                if (req is DappApprovalRequest.Transaction) DappApproval.Rejected() else DappApproval.Approved(listOf(alice, bob))
            },
        )
        session.handle(request(DappMethod.CONNECT))
        val events = mutableListOf<DappEvent>()
        val collector = collectingEvents(session, events)

        val response = session.handle(request(DappMethod.PREPARE_EXECUTE_AND_WAIT, submission()))

        assertEquals(DappErrorCode.USER_REJECTED.code, response.errorCode())
        awaitEvents(events, 2)
        assertTrue(events.filterIsInstance<DappEvent.TxChanged>().any { it.tx is TxChangedEvent.Failed })
        collector.cancel()
    }

    @Test
    fun `a pipeline failure becomes an error and a failed event`(): Unit = runBlocking {
        val session = session(pipeline = { error("participant unreachable") })
        session.handle(request(DappMethod.CONNECT))

        val response = session.handle(request(DappMethod.PREPARE_EXECUTE_AND_WAIT, submission()))

        assertEquals(DappErrorCode.INTERNAL.code, response.errorCode())
    }

    // ── ledgerApi ──────────────────────────────────────────────────────

    private fun ledgerApiRequest(method: LedgerApiMethod, resource: String) =
        request(DappMethod.LEDGER_API, DappJson.encode(LedgerApiRequest(method, resource)))

    @Test
    fun `the default policy allows a read and refuses a write`(): Unit = runBlocking {
        val session = session()
        session.handle(request(DappMethod.CONNECT))

        val read = session.handle(ledgerApiRequest(LedgerApiMethod.GET, "/v2/version"))
        val write = session.handle(ledgerApiRequest(LedgerApiMethod.POST, "/v2/commands/submit"))

        assertTrue(read.ok)
        assertEquals(DappErrorCode.UNAUTHORIZED.code, write.errorCode())
    }

    @Test
    fun `the default policy refuses administrative reads`(): Unit = runBlocking {
        val session = session()
        session.handle(request(DappMethod.CONNECT))

        // Read-only is not the same as harmless: user and party management
        // are how rights get granted and parties allocated.
        for (resource in listOf("/v2/users", "/v2/parties", "/v2/users/alice/rights", "/v2/idps")) {
            val response = session.handle(ledgerApiRequest(LedgerApiMethod.GET, resource))
            assertEquals(
                DappErrorCode.UNAUTHORIZED.code,
                response.errorCode(),
                "$resource should be outside the default policy",
            )
        }
    }

    @Test
    fun `the default policy allows the POST-shaped reads Canton actually uses`(): Unit = runBlocking {
        val session = session()
        session.handle(request(DappMethod.CONNECT))

        // The reason the policy is an allowlist and not a verb rule: every
        // read that matters here is a POST. A token-standard dApp cannot
        // choose input UTXOs without the first one.
        for (resource in listOf(
            "/v2/state/active-contracts",
            "/v2/state/active-contracts-page",
            "/v2/updates",
            "/v2/updates/flats",
            "/v2/events/events-by-contract-id",
        )) {
            val response = session.handle(ledgerApiRequest(LedgerApiMethod.POST, resource))
            assertTrue(response.ok, "POST $resource should be readable under the default policy")
        }
    }

    @Test
    fun `the default policy still refuses the writes that share those prefixes`(): Unit = runBlocking {
        val session = session()
        session.handle(request(DappMethod.CONNECT))

        val denied = listOf(
            // A DAR upload — the case that makes "GET is safe, POST is not"
            // wrong in the other direction too.
            LedgerApiMethod.POST to "/v2/packages",
            LedgerApiMethod.POST to "/v2/package-vetting/update",
            LedgerApiMethod.POST to "/v2/commands/submit-and-wait",
            // Reachable only through prepareExecute, where it is approved
            // and hash-verified.
            LedgerApiMethod.POST to "/v2/interactive-submission/prepare",
            LedgerApiMethod.POST to "/v2/interactive-submission/execute",
        )
        for ((method, resource) in denied) {
            val response = session.handle(ledgerApiRequest(method, resource))
            assertEquals(
                DappErrorCode.UNAUTHORIZED.code,
                response.errorCode(),
                "${method.wire} $resource must stay outside the default policy",
            )
        }
    }

    @Test
    fun `a policy prefix cannot be escaped by path traversal`(): Unit = runBlocking {
        val session = session()
        session.handle(request(DappMethod.CONNECT))

        // Percent-encoded forms matter as much as literal ones: OkHttp
        // decodes %2e and *then* resolves dot segments, so before the
        // canonical-form check these reached /v2/users and /users
        // respectively while the policy was still reading them as
        // /v2/state/… — verified against a real server.
        for (resource in listOf(
            "/v2/state/../users",
            "/v2/state/%2e%2e/users",
            "/v2/state/%2E%2E/%2E%2E/users",
            "/v2/state/%2e%2e%2f%2e%2e/users",
            "/v2/state/./../users",
            "/v2/state\\..\\users",
        )) {
            val response = session.handle(ledgerApiRequest(LedgerApiMethod.GET, resource))
            assertEquals(
                DappErrorCode.UNAUTHORIZED.code,
                response.errorCode(),
                "'$resource' must not escape the allowed prefix",
            )
        }
    }

    @Test
    fun `the client refuses a non-canonical resource even without a policy`(): Unit = runBlocking {
        // The policy is the security boundary, but JsonLedgerApiClient is
        // public: a host calling it directly must not be able to build a URL
        // the policy would never have approved.
        val client = JsonLedgerApiClient("http://127.0.0.1:1")

        val thrown = assertFailsWith<DappException> {
            runBlocking { client.call(LedgerApiRequest(LedgerApiMethod.GET, "/v2/state/%2e%2e/users")) }
        }

        assertEquals(DappErrorCode.INVALID_PARAMS, thrown.errorCode)
    }

    @Test
    fun `a host can widen the policy without restating the read surface`(): Unit = runBlocking {
        val widened = LedgerApiPolicy.allowing(
            *LedgerApiPolicy.ReadOnlyRules,
            LedgerApiMethod.POST to "/v2/commands/submit-and-wait",
        )
        val session = session(ledgerApiPolicy = widened)
        session.handle(request(DappMethod.CONNECT))

        assertTrue(session.handle(ledgerApiRequest(LedgerApiMethod.POST, "/v2/commands/submit-and-wait")).ok)
        // Widening one resource must not quietly open the rest.
        assertEquals(
            DappErrorCode.UNAUTHORIZED.code,
            session.handle(ledgerApiRequest(LedgerApiMethod.GET, "/v2/users")).errorCode(),
        )
        assertTrue(session.handle(ledgerApiRequest(LedgerApiMethod.GET, "/v2/version")).ok)
    }

    @Test
    fun `ledgerApi before approval is unauthorized`(): Unit = runBlocking {
        val response = session().handle(ledgerApiRequest(LedgerApiMethod.GET, "/v2/version"))

        assertEquals(DappErrorCode.UNAUTHORIZED.code, response.errorCode())
    }

    // ── Transport binding ──────────────────────────────────────────────

    @Test
    fun `the in-process transport carries a full client round trip`(): Unit = runBlocking {
        val session = session()
        val client = io.github.vsima.canton.dapp.DappClient(InProcessDappTransport(session))

        val connected: ConnectResult = client.connect()
        val accounts = client.listAccounts()
        val executed = client.prepareExecuteAndWait(PrepareSubmission(commands = commands))

        assertTrue(connected.isConnected)
        assertEquals(listOf(alice, bob), accounts)
        assertEquals("update-1", executed.updateId)
    }
}
