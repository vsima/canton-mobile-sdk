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
) {
    private val lock = Mutex()
    private var granted: List<DappWallet> = emptyList()
    private var connected: Boolean = false
    private var lastSignMessageAt: TimeMark? = null

    private val _events = MutableSharedFlow<DappEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )

    /** Events for this peer only. */
    public val events: Flow<DappEvent> = _events.asSharedFlow()

    /** The accounts this peer may currently see. Empty until [DappMethod.CONNECT] is approved. */
    public suspend fun grantedAccounts(): List<DappWallet> = lock.withLock { granted }

    /**
     * Dispatches one JSON-RPC frame.
     *
     * Notifications (no `id`) are answered with a response carrying a null
     * id, which callers should drop; returning null instead would make the
     * signature awkward for every transport that only ever sends requests.
     */
    public suspend fun handle(request: JsonRpcRequest): JsonRpcResponse = try {
        JsonRpcResponse.success(request.id, dispatch(request))
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

    private suspend fun dispatch(request: JsonRpcRequest): JsonElement {
        val method = DappMethod.fromWire(request.method)
            ?: throw DappException(
                DappErrorCode.UNSUPPORTED_METHOD,
                "unknown method '${request.method}'",
            )
        return when (method) {
            DappMethod.CONNECT -> DappJson.encode(connect())
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
                signMessage(DappJson.decodeSignMessageRequest(request.paramsOrThrow()).message),
            )
            DappMethod.PREPARE_EXECUTE -> {
                runPrepareExecute(DappJson.decodePrepareSubmission(request.paramsOrThrow()))
                JsonNull
            }
            DappMethod.PREPARE_EXECUTE_AND_WAIT -> DappJson.encodeExecutedResult(
                runPrepareExecute(DappJson.decodePrepareSubmission(request.paramsOrThrow())),
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

    private suspend fun connect(): ConnectResult {
        val available = accounts.accounts()
        val decision = approver.approve(
            DappApprovalRequest.Connection(peer, network.toDappNetwork(), available),
        )
        val approved = when (decision) {
            is DappApproval.Rejected -> return ConnectResult(
                isConnected = false,
                isNetworkConnected = false,
                reason = decision.reason,
            )
            is DappApproval.Approved -> decision.accounts
        }
        // Approving zero accounts is a rejection wearing a different hat.
        // Treating it as success would leave a dApp "connected" to nothing.
        if (approved.isEmpty()) {
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

    private suspend fun signMessage(message: String): SignMessageResult {
        val signer = messageSigner ?: throw DappException(
            DappErrorCode.UNSUPPORTED_METHOD,
            "this wallet does not implement signMessage",
        )
        val account = primaryAccount()
        rateLimitSignMessage()

        val messageId = UUID.randomUUID().toString()
        _events.emit(DappEvent.MessageSignature(MessageSignatureEvent.Pending(messageId)))

        val decision = approver.approve(DappApprovalRequest.Message(peer, account, message))
        if (decision is DappApproval.Rejected) {
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

    private suspend fun runPrepareExecute(submission: PrepareSubmission): TxChangedEvent.Executed {
        val pipeline = prepareExecute ?: throw DappException(
            DappErrorCode.UNSUPPORTED_METHOD,
            "this wallet does not implement prepareExecute",
        )
        val account = actAsAccount(submission)
        val commandId = submission.commandId ?: UUID.randomUUID().toString()

        _events.emit(DappEvent.TxChanged(TxChangedEvent.Pending(commandId)))
        val decision = approver.approve(
            DappApprovalRequest.Transaction(peer, account, network.toDappNetwork(), submission),
        )
        if (decision is DappApproval.Rejected) {
            _events.emit(DappEvent.TxChanged(TxChangedEvent.Failed(commandId)))
            throw DappException(DappErrorCode.USER_REJECTED, decision.reason)
        }

        return try {
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
            _events.emit(DappEvent.TxChanged(TxChangedEvent.Failed(commandId)))
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _events.emit(DappEvent.TxChanged(TxChangedEvent.Failed(commandId)))
            throw DappException(DappErrorCode.INTERNAL, e.message ?: "submission failed", cause = e)
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

