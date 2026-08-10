// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The dApp side of CIP-0103: typed calls onto a [DappTransport].
 *
 * ```kotlin
 * val client = DappClient(transport)
 * if (client.connect().isConnected) {
 *     val account = client.getPrimaryAccount()
 *     val tx = client.prepareExecuteAndWait(PrepareSubmission(commands = commands))
 *     println(tx.updateId)
 * }
 * ```
 *
 * Every method throws [DappException] when the wallet returns a JSON-RPC
 * error, so `4001` (the user declined) arrives as an exception with
 * [DappException.isUserRejection] set rather than as a null that is easy to
 * mistake for "nothing happened".
 *
 * This class holds no session state. Whether a connection exists is the
 * wallet's answer to give, and caching it here would only let the two
 * disagree — call [isConnected] or [status] to ask.
 */
public class DappClient(
    private val transport: DappTransport,
) {
    private val nextId = AtomicLong(1)

    /** Events pushed by the wallet, for transports that support them. */
    public val events: Flow<DappEvent> get() = transport.events

    /** Requests a connection. The wallet decides, typically by asking the user. */
    public suspend fun connect(): ConnectResult =
        DappJson.decodeConnectResult(call(DappMethod.CONNECT))

    /** Ends the session. Idempotent by convention; wallets should not error on a repeat. */
    public suspend fun disconnect() {
        call(DappMethod.DISCONNECT)
    }

    /** Whether a connection exists, without prompting the user. */
    public suspend fun isConnected(): ConnectResult =
        DappJson.decodeConnectResult(call(DappMethod.IS_CONNECTED))

    /** Provider identity, connection state, and — if connected — network and session. */
    public suspend fun status(): DappStatus =
        DappJson.decodeStatus(call(DappMethod.STATUS))

    /** The network the wallet is currently on, as a CAIP-2 id plus optional endpoints. */
    public suspend fun getActiveNetwork(): DappNetwork =
        DappJson.decodeNetwork(call(DappMethod.GET_ACTIVE_NETWORK))

    /** Accounts the user has granted this dApp. Not necessarily every account the wallet holds. */
    public suspend fun listAccounts(): List<DappWallet> =
        DappJson.decodeAccounts(call(DappMethod.LIST_ACCOUNTS))

    /** The account the wallet treats as primary, among those granted. */
    public suspend fun getPrimaryAccount(): DappWallet =
        DappJson.decodeWallet(call(DappMethod.GET_PRIMARY_ACCOUNT))

    /** Asks the wallet to sign an arbitrary message. Subject to user approval. */
    public suspend fun signMessage(message: String): SignMessageResult =
        DappJson.decodeSignMessageResult(
            call(DappMethod.SIGN_MESSAGE, DappJson.encode(SignMessageRequest(message))),
        )

    /**
     * Submits [request] and returns as soon as the wallet accepts it.
     *
     * Returns nothing — per OpenRPC this method's result is `Null`. The
     * outcome arrives on [events] as `txChanged`, so this overload is only
     * useful on a transport that has them; otherwise use
     * [prepareExecuteAndWait].
     */
    public suspend fun prepareExecute(request: PrepareSubmission) {
        call(DappMethod.PREPARE_EXECUTE, DappJson.encode(request))
    }

    /**
     * Submits [request] and suspends until the transaction is executed.
     *
     * Throws [DappException] if the user declines (`4001`) or the ledger
     * rejects it (`-32003`); returns only on success, which is why the
     * result type is the executed event rather than a union.
     */
    public suspend fun prepareExecuteAndWait(request: PrepareSubmission): TxChangedEvent.Executed =
        DappJson.decodeExecutedResult(
            call(DappMethod.PREPARE_EXECUTE_AND_WAIT, DappJson.encode(request)),
        )

    /**
     * Calls the JSON Ledger API through the wallet, which supplies the
     * credentials. The wallet applies its own policy — expect `4100` for
     * resources outside it rather than assuming full ledger access.
     */
    public suspend fun ledgerApi(request: LedgerApiRequest): JsonElement =
        call(DappMethod.LEDGER_API, DappJson.encode(request))

    private suspend fun call(method: DappMethod, params: JsonElement? = null): JsonElement {
        val id = JsonPrimitive(nextId.getAndIncrement())
        val response = transport.send(JsonRpcRequest(method.wire, params, id))
        // Not a hard failure: some transports (a deep-link callback, a
        // multiplexed stream) legitimately reorder or synthesise ids, and
        // refusing the response would break them for no safety gain — the
        // transport is what guarantees pairing.
        return response.resultOrThrow()
    }

    /** Convenience for callers that want the raw frame — mainly tests and diagnostics. */
    public suspend fun request(method: String, params: JsonElement? = null): JsonRpcResponse =
        transport.send(JsonRpcRequest(method, params, JsonPrimitive(nextId.getAndIncrement())))
}
