// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp.wallet

import io.github.vsima.canton.dapp.DappErrorCode
import io.github.vsima.canton.dapp.DappJson
import io.github.vsima.canton.dapp.DappMethod
import io.github.vsima.canton.dapp.DappWallet
import io.github.vsima.canton.dapp.DappWalletStatus
import io.github.vsima.canton.dapp.JsonRpcRequest
import io.github.vsima.canton.dapp.JsonRpcResponse
import io.github.vsima.canton.dapp.PrepareSubmission
import io.github.vsima.canton.dapp.TxChangedEvent
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/**
 * The spend policy enforced through a real [DappSession]: auto-approval
 * skips the sheet but leaves a receipt, refusals never reach the approver,
 * the daily cap accumulates across executed transactions, and, the R3 fix,
 * two concurrent requests cannot both pass a cap only one fits under.
 */
class DappSpendEnforcementTest {

    private val party = "alice::1220aa"

    private val alice = DappWallet(
        primary = true,
        partyId = party,
        status = DappWalletStatus.ALLOCATED,
        hint = "alice",
        publicKey = "00",
        namespace = "1220aa",
        networkId = "canton:localnet",
        signingProviderId = "software",
    )

    private class Approver(
        private val answer: suspend (DappApprovalRequest) -> DappApproval,
    ) : DappApprovalDelegate {
        val seen = mutableListOf<DappApprovalRequest>()
        override suspend fun approve(request: DappApprovalRequest): DappApproval {
            seen += request
            return answer(request)
        }
    }

    private fun transferCommands(amount: String): kotlinx.serialization.json.JsonArray = buildJsonArray {
        add(
            Json.parseToJsonElement(
                """
                {
                  "ExerciseCommand": {
                    "templateId": "abc:Splice.Api.Token.TransferInstructionV1:TransferFactory",
                    "contractId": "00fac",
                    "choice": "TransferFactory_Transfer",
                    "choiceArgument": {
                      "transfer": {
                        "sender": "$party",
                        "receiver": "bob::1220bb",
                        "amount": "$amount",
                        "instrumentId": { "admin": "DSO::1220dso", "id": "Amulet" }
                      }
                    }
                  }
                }
                """,
            ),
        )
    }

    private fun session(
        approver: Approver = Approver { DappApproval.Approved(listOf(alice)) },
        policy: DappSpendPolicy?,
        ledger: SpendLedger = InMemorySpendLedger(),
        now: () -> Instant = Instant::now,
        activity: MutableList<DappActivity> = mutableListOf(),
    ) = DappSession(
        peer = DappPeer(id = "agent-1", name = "Agent"),
        accounts = { listOf(alice) },
        approver = approver,
        network = DappNetworkConfig(networkId = "canton:localnet"),
        prepareExecute = { ctx ->
            TxChangedEvent.Executed(ctx.commandId, updateId = "upd-${ctx.commandId}", completionOffset = 1)
        },
        spendPolicy = { policy },
        spendLedger = ledger,
        wallClock = now,
        activityObserver = { activity.add(it) },
    )

    private fun request(method: DappMethod, params: JsonElement? = null, id: Int = 1) =
        JsonRpcRequest(method.wire, params, JsonPrimitive(id))

    private fun paySubmission(amount: String): JsonElement =
        DappJson.encode(PrepareSubmission(commands = transferCommands(amount)))

    private suspend fun connect(session: DappSession) {
        session.handle(request(DappMethod.CONNECT))
    }

    private fun JsonRpcResponse.errorCode(): Int =
        error?.code ?: throw AssertionError("expected an error response, got result=$result")

    @Test
    fun `auto-approval skips the sheet and records an auto receipt`(): Unit = runBlocking {
        val approver = Approver { DappApproval.Approved(listOf(alice)) }
        val ledger = InMemorySpendLedger()
        val session = session(approver, DappSpendPolicy(autoApproveBelow = BigDecimal("5")), ledger)
        connect(session)
        approver.seen.clear() // the connect approval

        val response = session.handle(request(DappMethod.PREPARE_EXECUTE_AND_WAIT, paySubmission("2")))
        assertNotNull(response.result)
        assertEquals(emptyList(), approver.seen)

        val receipts = ledger.receiptsSince("agent-1", Instant.EPOCH)
        assertEquals(1, receipts.size)
        assertEquals(BigDecimal("2"), receipts[0].amount)
        assertTrue(receipts[0].autoApproved)
        assertEquals("bob::1220bb", receipts[0].receiver)
    }

    @Test
    fun `a refusal never reaches the approver and leaves no receipt`(): Unit = runBlocking {
        val approver = Approver { DappApproval.Approved(listOf(alice)) }
        val ledger = InMemorySpendLedger()
        val session = session(approver, DappSpendPolicy(maxPerTransaction = BigDecimal("5")), ledger)
        connect(session)
        approver.seen.clear()

        val response = session.handle(request(DappMethod.PREPARE_EXECUTE_AND_WAIT, paySubmission("100")))
        assertEquals(DappErrorCode.USER_REJECTED.code, response.errorCode())
        assertTrue("spend policy" in (response.error?.message ?: ""))
        assertEquals(emptyList(), approver.seen)
        assertEquals(emptyList(), ledger.receiptsSince("agent-1", Instant.EPOCH))
    }

    @Test
    fun `human-approved spends count against the daily cap`(): Unit = runBlocking {
        val session = session(policy = DappSpendPolicy(dailyCap = BigDecimal("10")))
        connect(session)

        assertNotNull(session.handle(request(DappMethod.PREPARE_EXECUTE_AND_WAIT, paySubmission("5"), id = 2)).result)
        assertNotNull(session.handle(request(DappMethod.PREPARE_EXECUTE_AND_WAIT, paySubmission("5"), id = 3)).result)
        val third = session.handle(request(DappMethod.PREPARE_EXECUTE_AND_WAIT, paySubmission("5"), id = 4))
        assertEquals(DappErrorCode.USER_REJECTED.code, third.errorCode())
        assertTrue("daily cap" in (third.error?.message ?: ""))
    }

    @Test
    fun `receipts older than the rolling window fall out of the cap`(): Unit = runBlocking {
        var now = Instant.parse("2026-08-31T12:00:00Z")
        val session = session(
            policy = DappSpendPolicy(dailyCap = BigDecimal("10")),
            now = { now },
        )
        connect(session)
        assertNotNull(session.handle(request(DappMethod.PREPARE_EXECUTE_AND_WAIT, paySubmission("8"), id = 2)).result)

        // Nine hours later the cap is still exhausted; a day later it is not.
        now = now.plusSeconds(9 * 3600)
        val blocked = session.handle(request(DappMethod.PREPARE_EXECUTE_AND_WAIT, paySubmission("8"), id = 3))
        assertEquals(DappErrorCode.USER_REJECTED.code, blocked.errorCode())
        now = now.plusSeconds(16 * 3600)
        assertNotNull(session.handle(request(DappMethod.PREPARE_EXECUTE_AND_WAIT, paySubmission("8"), id = 4)).result)
    }

    @Test
    fun `an unparsed submission asks the human and never auto-approves`(): Unit = runBlocking {
        val approver = Approver { DappApproval.Approved(listOf(alice)) }
        val ledger = InMemorySpendLedger()
        val session = session(approver, DappSpendPolicy(autoApproveBelow = BigDecimal("500")), ledger)
        connect(session)
        approver.seen.clear()

        val custom = DappJson.encode(
            PrepareSubmission(
                commands = buildJsonArray {
                    add(Json.parseToJsonElement("""{ "CreateCommand": { "templateId": "p:M:T" } }"""))
                },
            ),
        )
        assertNotNull(session.handle(request(DappMethod.PREPARE_EXECUTE_AND_WAIT, custom, id = 2)).result)
        assertEquals(1, approver.seen.size)
        // No amount to record: unparsed spends leave no receipt.
        assertEquals(emptyList(), ledger.receiptsSince("agent-1", Instant.EPOCH))
    }

    @Test
    fun `concurrent requests cannot both pass a cap only one fits under`(): Unit = runBlocking {
        // R3: both frames arrive before either executes. The gate holds the
        // first approval open until the second request is queued behind the
        // submission lock, then releases; the second must see the first's
        // receipt and refuse.
        val firstSheetRaised = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val approver = Approver { request ->
            if (request is DappApprovalRequest.Transaction) {
                firstSheetRaised.complete(Unit)
                releaseFirst.await()
            }
            DappApproval.Approved(listOf(alice))
        }
        val session = session(approver, DappSpendPolicy(dailyCap = BigDecimal("10")))
        connect(session)

        val first = async { session.handle(request(DappMethod.PREPARE_EXECUTE_AND_WAIT, paySubmission("6"), id = 2)) }
        firstSheetRaised.await()
        val second = async { session.handle(request(DappMethod.PREPARE_EXECUTE_AND_WAIT, paySubmission("6"), id = 3)) }
        // The second frame is in flight; nothing has executed yet. Release.
        releaseFirst.complete(Unit)
        val (r1, r2) = awaitAll(first, second)

        assertNotNull(r1.result, "the first request should execute")
        assertEquals(DappErrorCode.USER_REJECTED.code, r2.errorCode())
        assertTrue("daily cap" in (r2.error?.message ?: ""))
    }

    @Test
    fun `every outcome reaches the activity feed, sheet or no sheet`(): Unit = runBlocking {
        val activity = mutableListOf<DappActivity>()
        val session = session(
            policy = DappSpendPolicy(maxPerTransaction = BigDecimal("10"), autoApproveBelow = BigDecimal("2")),
            activity = activity,
        )
        connect(session)
        // Auto-approved (no sheet), human-approved (sheet), refused (no sheet).
        session.handle(request(DappMethod.PREPARE_EXECUTE_AND_WAIT, paySubmission("1"), id = 2))
        session.handle(request(DappMethod.PREPARE_EXECUTE_AND_WAIT, paySubmission("5"), id = 3))
        session.handle(request(DappMethod.PREPARE_EXECUTE_AND_WAIT, paySubmission("50"), id = 4))

        assertEquals(
            listOf(
                DappActivity.Kind.CONNECTED,
                DappActivity.Kind.TRANSACTION_AUTO_APPROVED,
                DappActivity.Kind.TRANSACTION_EXECUTED,
                DappActivity.Kind.TRANSACTION_REQUESTED,
                DappActivity.Kind.TRANSACTION_EXECUTED,
                DappActivity.Kind.TRANSACTION_REFUSED,
            ),
            activity.map { it.kind },
        )
        val refused = activity.last()
        assertEquals("Agent", refused.peerName)
        assertEquals("50", refused.transfer?.amount)
        assertTrue("per-transaction cap" in (refused.detail ?: ""))
    }

    @Test
    fun `a throwing observer never breaks the payment`(): Unit = runBlocking {
        val session = DappSession(
            peer = DappPeer(id = "agent-1", name = "Agent"),
            accounts = { listOf(alice) },
            approver = Approver { DappApproval.Approved(listOf(alice)) },
            network = DappNetworkConfig(networkId = "canton:localnet"),
            prepareExecute = { ctx ->
                TxChangedEvent.Executed(ctx.commandId, updateId = "upd", completionOffset = 1)
            },
            spendPolicy = { DappSpendPolicy(autoApproveBelow = BigDecimal("5")) },
            activityObserver = { throw IllegalStateException("broken log") },
        )
        connect(session)
        val response = session.handle(request(DappMethod.PREPARE_EXECUTE_AND_WAIT, paySubmission("2"), id = 2))
        assertNotNull(response.result)
    }

    @Test
    fun `the policy rate limit refuses rapid-fire requests`(): Unit = runBlocking {
        val session = session(
            policy = DappSpendPolicy(minRequestInterval = kotlin.time.Duration.parse("1h")),
        )
        connect(session)
        assertNotNull(session.handle(request(DappMethod.PREPARE_EXECUTE_AND_WAIT, paySubmission("1"), id = 2)).result)
        val second = session.handle(request(DappMethod.PREPARE_EXECUTE_AND_WAIT, paySubmission("1"), id = 3))
        assertEquals(DappErrorCode.INVALID_INPUT.code, second.errorCode())
        assertTrue("rate-limited" in (second.error?.message ?: ""))
    }
}
