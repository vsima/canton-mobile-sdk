// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp.wallet

import io.github.vsima.canton.dapp.ConnectResult
import io.github.vsima.canton.dapp.DappErrorCode
import io.github.vsima.canton.dapp.DappEvent
import io.github.vsima.canton.dapp.DappException
import io.github.vsima.canton.dapp.DappJson
import io.github.vsima.canton.dapp.DappMethod
import io.github.vsima.canton.dapp.DappProvider
import io.github.vsima.canton.dapp.DappProviderType
import io.github.vsima.canton.dapp.DappRequestContext
import io.github.vsima.canton.dapp.DappRequestHandler
import io.github.vsima.canton.dapp.DappStatus
import io.github.vsima.canton.dapp.DappWallet
import io.github.vsima.canton.dapp.JsonRpcRequest
import io.github.vsima.canton.dapp.JsonRpcResponse
import io.github.vsima.canton.dapp.LedgerApiRequest
import io.github.vsima.canton.dapp.MessageSignatureEvent
import io.github.vsima.canton.dapp.PrepareSubmission
import io.github.vsima.canton.dapp.SignMessageResult
import io.github.vsima.canton.dapp.TxChangedEvent
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * One dApp's session with the wallet: the CIP-0103 **provider** side.
 *
 * A session is per-peer and holds that peer's grant — the accounts the user
 * approved for *it*. Two dApps talking to the same wallet get two sessions
 * and cannot see each other's accounts, which is the reason grants live here
 * rather than in a global store.
 *
 * ```kotlin
 * val session = DappSession(peer, accounts, approver, network)
 * val response = session.handle(incomingFrame)
 * ```
 *
 * [handle] never throws for protocol-level failures — it returns a JSON-RPC
 * error response, because a transport needs something to send back. It will
 * propagate genuinely unexpected exceptions only after wrapping them as
 * `-32603`.
 */
public class DappSession(
    private val peer: DappPeer,
    private val accounts: DappAccountsSource,
    private val approver: DappApprovalDelegate,
    private val network: DappNetworkConfig,
    private val provider: DappProvider = DEFAULT_PROVIDER,
    /** Signs `signMessage` requests. Absent means the method is unsupported. */
    private val messageSigner: DappMessageSigner? = null,
    /** The prepare→verify→sign→execute pipeline. Supplied by the ledger layer. */
    private val prepareExecute: PrepareExecutePipeline? = null,
    /** Proxies `ledgerApi` calls. Absent means the method is unsupported. */
    private val ledgerApi: LedgerApiProxy? = null,
    /** Which `ledgerApi` resources this peer may reach. Defaults to read-only. */
    private val ledgerApiPolicy: LedgerApiPolicy = LedgerApiPolicy.ReadOnly,
    /** Minimum gap between `signMessage` calls; see [DappApprovalRequest.Message]. */
    private val signMessageMinInterval: Duration = 1.seconds,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /**
     * The current [DappSpendPolicy] for this peer, read fresh on every
     * transaction so the wallet's policy editor takes effect immediately.
     * Null (the default) means no policy: every transaction goes to the approver.
     */
    private val spendPolicy: () -> DappSpendPolicy? = { null },
    /** Where spends are recorded and the rolling daily cap is read from. */
    private val spendLedger: SpendLedger = InMemorySpendLedger(),
    /** Wall-clock for receipts and the rolling window; injectable for tests. */
    private val wallClock: () -> java.time.Instant = java.time.Instant::now,
    /** The wallet-facing activity feed; see [DappActivityObserver]. */
    private val activityObserver: DappActivityObserver? = null,
) : DappRequestHandler {
    private val lock = Mutex()

    /**
     * Serializes the whole transaction path: policy check, approval,
     * execution, and the spend record happen under one lock, so concurrent
     * frames cannot both pass a cap check that only one of them fits under
     * (the R3 time-of-check/time-of-use race). One transaction at a time per
     * session is also the honest UI: one request to the approver at a time, not a stack.
     */
    private val submissionLock = Mutex()
    private var granted: List<DappWallet> = emptyList()
    private var connected: Boolean = false
    private var lastSignMessageAt: TimeMark? = null
    private var lastTransactionAt: TimeMark? = null

    private val _events = MutableSharedFlow<DappEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )

    /** Events for this peer only. */
    override val events: Flow<DappEvent> = _events.asSharedFlow()

    /** The accounts this peer may currently see. Empty until [DappMethod.CONNECT] is approved. */
    public suspend fun grantedAccounts(): List<DappWallet> = lock.withLock { granted }

    /** Reports to the host's activity feed; never lets a broken log break a request. */
    private fun report(
        kind: DappActivity.Kind,
        transfer: DappTransferSummary? = null,
        detail: String? = null,
    ) {
        val observer = activityObserver ?: return
        try {
            observer.onActivity(
                DappActivity(
                    peerId = peer.id,
                    peerName = peer.name,
                    at = wallClock(),
                    kind = kind,
                    transfer = transfer,
                    detail = detail,
                ),
            )
        } catch (_: Throwable) {
            // The feed is a record, not a control; see DappActivityObserver.
        }
    }

    /**
     * Dispatches one JSON-RPC frame.
     *
     * Notifications (no `id`) are answered with a response carrying a null
     * id, which callers should drop; returning null instead would make the
     * signature awkward for every transport that only ever sends requests.
     */
    override suspend fun handle(request: JsonRpcRequest): JsonRpcResponse =
        handle(request, DappRequestContext.NONE)

    override suspend fun handle(request: JsonRpcRequest, context: DappRequestContext): JsonRpcResponse = try {
        JsonRpcResponse.success(request.id, dispatch(request, context))
    } catch (e: DappException) {
        JsonRpcResponse.failure(request.id, e)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // A provider that leaks a stack trace to a dApp leaks its internals;
        // one that returns nothing leaves the dApp hanging. Neither.
        JsonRpcResponse.failure(
            request.id,
            DappException(DappErrorCode.INTERNAL, e.message ?: e::class.simpleName ?: "internal error"),
        )
    }

    private suspend fun dispatch(request: JsonRpcRequest, context: DappRequestContext): JsonElement {
        val method = DappMethod.fromWire(request.method)
            ?: throw DappException(
                DappErrorCode.UNSUPPORTED_METHOD,
                "unknown method '${request.method}'",
            )
        return when (method) {
            DappMethod.CONNECT -> DappJson.encode(connect(context))
            DappMethod.DISCONNECT -> {
                disconnect()
                JsonNull
            }
            DappMethod.IS_CONNECTED -> DappJson.encode(connectResult())
            DappMethod.STATUS -> DappJson.encode(status())
            DappMethod.GET_ACTIVE_NETWORK -> {
                requireGrant()
                DappJson.encode(network.toDappNetwork())
            }
            DappMethod.LIST_ACCOUNTS -> DappJson.encodeAccounts(requireGrant())
            DappMethod.GET_PRIMARY_ACCOUNT -> DappJson.encode(primaryAccount())
            DappMethod.SIGN_MESSAGE -> DappJson.encode(
                signMessage(DappJson.decodeSignMessageRequest(request.paramsOrThrow()).message, context),
            )
            DappMethod.PREPARE_EXECUTE -> {
                runPrepareExecute(DappJson.decodePrepareSubmission(request.paramsOrThrow()), context)
                JsonNull
            }
            DappMethod.PREPARE_EXECUTE_AND_WAIT -> DappJson.encodeExecutedResult(
                runPrepareExecute(DappJson.decodePrepareSubmission(request.paramsOrThrow()), context),
            )
            DappMethod.LEDGER_API -> runLedgerApi(
                DappJson.decodeLedgerApiRequest(request.paramsOrThrow()),
            )
            // Event names are valid wire methods, but only wallet→dApp.
            // A dApp sending one is confused, not unauthorized.
            DappMethod.ACCOUNTS_CHANGED,
            DappMethod.TX_CHANGED,
            DappMethod.MESSAGE_SIGNATURE,
            -> throw DappException(
                DappErrorCode.UNSUPPORTED_METHOD,
                "'${request.method}' is an event, not a callable method",
            )
        }
    }

    // ── Connection ─────────────────────────────────────────────────────

    private suspend fun connect(context: DappRequestContext): ConnectResult {
        // Idempotent: agents call connect before each request to ensure they
        // have accounts, so a peer that is already connected and granted must
        // not ask the approver again to share accounts. Return the existing grant.
        val alreadyGranted = lock.withLock { connected && granted.isNotEmpty() }
        if (alreadyGranted) return connectResult()
        val available = accounts.accounts()
        val decision = approver.approve(
            DappApprovalRequest.Connection(peer, network.toDappNetwork(), available),
            context,
        )
        val approved = when (decision) {
            is DappApproval.Rejected -> {
                report(DappActivity.Kind.CONNECTION_DECLINED, detail = decision.reason)
                return ConnectResult(
                    isConnected = false,
                    isNetworkConnected = false,
                    reason = decision.reason,
                )
            }
            is DappApproval.Approved -> decision.accounts
        }
        // Approving zero accounts is a rejection wearing a different hat.
        // Treating it as success would leave a dApp "connected" to nothing.
        if (approved.isEmpty()) {
            report(DappActivity.Kind.CONNECTION_DECLINED, detail = "No accounts were shared")
            return ConnectResult(
                isConnected = false,
                isNetworkConnected = false,
                reason = "No accounts were shared",
            )
        }
        // A delegate must not be able to widen the grant beyond what the
        // wallet offered — it is UI, and UI does not get to invent accounts.
        val offered = available.associateBy { it.partyId }
        val unknown = approved.filterNot { offered.containsKey(it.partyId) }
        if (unknown.isNotEmpty()) {
            throw DappException(
                DappErrorCode.INTERNAL,
                "approval returned accounts the wallet did not offer: " +
                    unknown.joinToString { it.partyId },
            )
        }
        lock.withLock {
            granted = approved
            connected = true
        }
        report(
            DappActivity.Kind.CONNECTED,
            detail = approved.joinToString(", ") { it.partyId },
        )
        _events.emit(DappEvent.AccountsChanged(approved))
        return connectResult()
    }

    private suspend fun disconnect() {
        val wasConnected = lock.withLock {
            val was = connected
            granted = emptyList()
            connected = false
            was
        }
        // Idempotent by design: a dApp retrying disconnect after a dropped
        // transport should not get an error for succeeding twice.
        if (wasConnected) _events.emit(DappEvent.AccountsChanged(emptyList()))
    }

    private suspend fun connectResult(): ConnectResult = lock.withLock {
        ConnectResult(
            isConnected = connected,
            isNetworkConnected = connected,
            reason = if (connected) null else "Not connected",
        )
    }

    private suspend fun status(): DappStatus {
        val result = connectResult()
        return DappStatus(
            provider = provider,
            connection = result,
            network = if (result.isConnected) network.toDappNetwork() else null,
            // Session carries an access token, and dApps do not get one.
            session = null,
        )
    }

    /**
     * The peer's grant, or `4100`.
     *
     * `4100` rather than `4900`: EIP-1193 reserves 4900 for the provider
     * being disconnected from every chain, while "you have not been
     * authorized for these accounts" is exactly what 4100 means.
     */
    private suspend fun requireGrant(): List<DappWallet> = lock.withLock {
        if (!connected || granted.isEmpty()) {
            throw DappException(
                DappErrorCode.UNAUTHORIZED,
                "'${peer.name}' has no approved accounts; call connect first",
            )
        }
        granted
    }

    private suspend fun primaryAccount(): DappWallet {
        val grant = requireGrant()
        return grant.firstOrNull { it.primary } ?: grant.first()
    }

    // ── signMessage ────────────────────────────────────────────────────

    private suspend fun signMessage(message: String, context: DappRequestContext): SignMessageResult {
        val signer = messageSigner ?: throw DappException(
            DappErrorCode.UNSUPPORTED_METHOD,
            "this wallet does not implement signMessage",
        )
        val account = primaryAccount()
        rateLimitSignMessage()

        val messageId = UUID.randomUUID().toString()
        _events.emit(DappEvent.MessageSignature(MessageSignatureEvent.Pending(messageId)))

        val decision = approver.approve(DappApprovalRequest.Message(peer, account, message), context)
        if (decision is DappApproval.Rejected) {
            report(DappActivity.Kind.MESSAGE_DECLINED, detail = decision.reason)
            _events.emit(DappEvent.MessageSignature(MessageSignatureEvent.Failed(messageId)))
            throw DappException(DappErrorCode.USER_REJECTED, decision.reason)
        }

        val signature = try {
            signer.sign(account, message)
        } catch (e: DappException) {
            _events.emit(DappEvent.MessageSignature(MessageSignatureEvent.Failed(messageId)))
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _events.emit(DappEvent.MessageSignature(MessageSignatureEvent.Failed(messageId)))
            throw DappException(DappErrorCode.INTERNAL, e.message ?: "signing failed", cause = e)
        }
        report(DappActivity.Kind.MESSAGE_SIGNED)
        _events.emit(DappEvent.MessageSignature(MessageSignatureEvent.Signed(messageId, signature)))
        return SignMessageResult(signature)
    }

    /**
     * Throttles `signMessage`.
     *
     * Not about compute: an unthrottled signMessage lets a peer spray
     * approval prompts until one is confirmed by reflex. The limit is per
     * session, so it is per peer.
     */
    private suspend fun rateLimitSignMessage() {
        lock.withLock {
            val last = lastSignMessageAt
            if (last != null && last.elapsedNow() < signMessageMinInterval) {
                throw DappException(
                    DappErrorCode.INVALID_INPUT,
                    "signMessage is rate-limited to one call per $signMessageMinInterval",
                )
            }
            lastSignMessageAt = timeSource.markNow()
        }
    }

    // ── prepareExecute ─────────────────────────────────────────────────

    private suspend fun runPrepareExecute(
        submission: PrepareSubmission,
        context: DappRequestContext,
    ): TxChangedEvent.Executed {
        val pipeline = prepareExecute ?: throw DappException(
            DappErrorCode.UNSUPPORTED_METHOD,
            "this wallet does not implement prepareExecute",
        )
        val account = actAsAccount(submission)
        authorizeReadAs(submission)
        val commandId = submission.commandId ?: UUID.randomUUID().toString()

        // See [submissionLock]: everything from the policy read to the spend
        // record is one critical section per session.
        return submissionLock.withLock {
            val policy = spendPolicy()
            if (policy != null) rateLimitTransaction(policy)
            val summary = DappCommandSummary.transferOf(submission)
            val spendDecision = policy?.decide(summary, spentLast24h(summary)) ?: SpendDecision.AskHuman

            _events.emit(DappEvent.TxChanged(TxChangedEvent.Pending(commandId)))
            if (spendDecision is SpendDecision.Refuse) {
                report(DappActivity.Kind.TRANSACTION_REFUSED, summary, spendDecision.reason)
                _events.emit(DappEvent.TxChanged(TxChangedEvent.Failed(commandId)))
                throw DappException(DappErrorCode.USER_REJECTED, "spend policy: ${spendDecision.reason}")
            }
            if (spendDecision is SpendDecision.AutoApprove) {
                report(DappActivity.Kind.TRANSACTION_AUTO_APPROVED, summary)
            } else {
                report(DappActivity.Kind.TRANSACTION_REQUESTED, summary)
                val decision = approver.approve(
                    DappApprovalRequest.Transaction(peer, account, network.toDappNetwork(), submission),
                    context,
                )
                if (decision is DappApproval.Rejected) {
                    report(DappActivity.Kind.TRANSACTION_DECLINED, summary, decision.reason)
                    _events.emit(DappEvent.TxChanged(TxChangedEvent.Failed(commandId)))
                    throw DappException(DappErrorCode.USER_REJECTED, decision.reason)
                }
            }

            val executed = try {
                pipeline.execute(
                    PrepareExecuteContext(
                        commandId = commandId,
                        actAs = account,
                        submission = submission,
                        network = network,
                        emitEvent = { _events.emit(DappEvent.TxChanged(it)) },
                    ),
                ).also { _events.emit(DappEvent.TxChanged(it)) }
            } catch (e: DappException) {
                report(DappActivity.Kind.TRANSACTION_FAILED, summary, e.message)
                _events.emit(DappEvent.TxChanged(TxChangedEvent.Failed(commandId)))
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                report(DappActivity.Kind.TRANSACTION_FAILED, summary, e.message)
                _events.emit(DappEvent.TxChanged(TxChangedEvent.Failed(commandId)))
                throw DappException(DappErrorCode.INTERNAL, e.message ?: "submission failed", cause = e)
            }
            recordSpend(summary, spendDecision is SpendDecision.AutoApprove, commandId)
            report(DappActivity.Kind.TRANSACTION_EXECUTED, summary, executed.updateId)
            executed
        }
    }

    /**
     * The trailing-24h receipt total for the submission's instrument, the
     * input to [DappSpendPolicy.decide]'s daily cap. Zero when the submission
     * is not a parsed transfer: an unparsed submission is never refused by
     * amount, so the total is irrelevant to its decision.
     */
    private suspend fun spentLast24h(summary: DappTransferSummary?): java.math.BigDecimal {
        if (summary == null) return java.math.BigDecimal.ZERO
        val since = wallClock().minusSeconds(24 * 60 * 60)
        return spendLedger.receiptsSince(peer.id, since)
            .filter { it.instrumentId == summary.instrumentId }
            .fold(java.math.BigDecimal.ZERO) { total, receipt -> total + receipt.amount }
    }

    /**
     * Executed spends become receipts, the source of truth for the rolling
     * cap and the app's receipts UI. Written only after execution succeeds,
     * and only for parsed transfers: an unparsed submission has no amount to
     * record, and the human explicitly approved whatever it was.
     */
    private suspend fun recordSpend(summary: DappTransferSummary?, autoApproved: Boolean, commandId: String) {
        val amount = summary?.amount?.let { text ->
            try {
                java.math.BigDecimal(text)
            } catch (_: NumberFormatException) {
                null
            }
        } ?: return
        spendLedger.append(
            SpendReceipt(
                peerId = peer.id,
                at = wallClock(),
                instrumentId = summary.instrumentId,
                amount = amount,
                receiver = summary.receiver,
                autoApproved = autoApproved,
                commandId = commandId,
            ),
        )
    }

    /**
     * The transaction counterpart of [rateLimitSignMessage], driven by the
     * policy's [DappSpendPolicy.minRequestInterval]: an unthrottled peer can
     * spray requests at the approver until reflex confirms one.
     */
    private suspend fun rateLimitTransaction(policy: DappSpendPolicy) {
        if (policy.minRequestInterval <= Duration.ZERO) return
        lock.withLock {
            val last = lastTransactionAt
            if (last != null && last.elapsedNow() < policy.minRequestInterval) {
                report(
                    DappActivity.Kind.TRANSACTION_RATE_LIMITED,
                    detail = "more than one request per ${policy.minRequestInterval}",
                )
                throw DappException(
                    DappErrorCode.INVALID_INPUT,
                    "spend policy: transaction requests are rate-limited to one per ${policy.minRequestInterval}",
                )
            }
            lastTransactionAt = timeSource.markNow()
        }
    }

    /**
     * Resolves which account acts, enforcing the rule that makes the whole
     * proxy design safe: **a dApp may request an `actAs`, it may not choose
     * one.** A dApp that could set `actAs` freely could make the wallet act
     * as any party it names, so an unrecognised request is `4100` rather
     * than a silent substitution.
     */
    private suspend fun actAsAccount(submission: PrepareSubmission): DappWallet {
        val grant = requireGrant()
        val requested = submission.actAs
        if (requested.isEmpty()) return grant.firstOrNull { it.primary } ?: grant.first()
        if (requested.size > 1) {
            throw DappException(
                DappErrorCode.INVALID_PARAMS,
                "multi-party actAs is not supported; requested ${requested.size} parties",
            )
        }
        val partyId = requested.single()
        return grant.firstOrNull { it.partyId == partyId }
            ?: throw DappException(
                DappErrorCode.UNAUTHORIZED,
                "actAs '$partyId' is not among the accounts approved for '${peer.name}'",
            )
    }

    /**
     * The `readAs` counterpart to [actAsAccount]. A dApp may *request* extra
     * read parties, but only ones the user already approved for this peer:
     * the read authority a `readAs` draws on is the wallet's own ledger token,
     * so an unrecognised party is `4100` rather than a silent widening of what
     * the dApp can make the wallet read. Requested parties already in the
     * grant pass through unchanged.
     */
    private suspend fun authorizeReadAs(submission: PrepareSubmission) {
        if (submission.readAs.isEmpty()) return
        val granted = requireGrant().mapTo(mutableSetOf()) { it.partyId }
        val foreign = submission.readAs.filterNot { it in granted }
        if (foreign.isNotEmpty()) {
            throw DappException(
                DappErrorCode.UNAUTHORIZED,
                "readAs ${foreign.joinToString(", ") { "'$it'" }} " +
                    "is not among the accounts approved for '${peer.name}'",
            )
        }
    }

    // ── ledgerApi ──────────────────────────────────────────────────────

    private suspend fun runLedgerApi(request: LedgerApiRequest): JsonElement {
        val proxy = ledgerApi ?: throw DappException(
            DappErrorCode.UNSUPPORTED_METHOD,
            "this wallet does not implement ledgerApi",
        )
        requireGrant()
        if (!ledgerApiPolicy.allows(request)) {
            throw DappException(
                DappErrorCode.UNAUTHORIZED,
                "ledgerApi ${request.requestMethod.wire} ${request.resource} is outside this wallet's policy",
            )
        }
        return proxy.call(request)
    }

    /** Holds [DEFAULT_PROVIDER]. */
    public companion object {
        /** Identifies this SDK to dApps. Hosts override it to name themselves. */
        public val DEFAULT_PROVIDER: DappProvider = DappProvider(
            id = "io.github.vsima.canton",
            providerType = DappProviderType.MOBILE,
        )
    }
}

/** A [JsonRpcRequest]'s params, or `-32602`. */
private fun JsonRpcRequest.paramsOrThrow(): JsonElement =
    params ?: throw DappException(
        DappErrorCode.INVALID_PARAMS,
        "method '$method' requires params",
    )

