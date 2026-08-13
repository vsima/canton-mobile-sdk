// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp.wc

import io.github.vsima.canton.dapp.DappMethod
import io.github.vsima.canton.dapp.DappRequestHandler
import io.github.vsima.canton.dapp.DappWallet
import io.github.vsima.canton.dapp.JsonRpcRequest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * The wallet side of the WalletConnect transport for CIP-0103.
 *
 * WalletConnect is not a new capability — it is a *transport* for the same
 * CIP-0103 JSON-RPC the wallet already answers over the in-process and LAN
 * transports. So this is the [io.github.vsima.canton.dapp.lan.LanGrpcDappServer]
 * of WalletConnect: it wraps a [DappRequestHandler] (a `DappSession` satisfies
 * it) and turns a session's traffic into `handle` calls.
 *
 * **It depends on no WalletConnect client library.** A wallet's job here has
 * exactly two touch-points, and both are pure:
 *
 * - [sessionNamespaces] — at proposal time, the CAIP-2 chain, CAIP-10 accounts,
 *   and CIP-0103 methods to approve a session with.
 * - [handle] — at request time, route one `session_request` into the engine and
 *   map its reply back.
 *
 * A Reown WalletKit delegate in the app (or an Android add-on module) does the
 * relay I/O: on `onSessionProposal` it approves with [sessionNamespaces]; on
 * `onSessionRequest` it calls [handle] and responds with the result. Keeping the
 * client out means this adapter is plain JVM and is unit-tested against a real
 * `DappSession`, no relay — the same split the reference dApp server used to
 * prove the transport headless before any device work.
 *
 * The approval, signing and prepare→sign→execute all happen inside the engine;
 * this class adds no policy. It advertises the CIP-0103 method names verbatim
 * (`signMessage`, `prepareExecute`, …) so the frames the engine dispatches are
 * exactly what crosses the session.
 */
public class CantonWalletConnect(
    private val handler: DappRequestHandler,
    networkId: String,
) {
    /** The CAIP-2 chain this session advertises (validated from `networkId`). */
    public val chainId: String = Caip.chainId(networkId)

    /** The CIP-0103 request methods advertised over a session. */
    public val methods: List<String> get() = REQUEST_METHODS

    /** Events advertised over a session — none carried yet (see the class doc). */
    public val events: List<String> get() = emptyList()

    /**
     * The namespaces to approve a session with, sharing [accounts]. Which
     * accounts to share is the wallet's decision (its connect-approval UI); this
     * only projects them into CAIP-10 form under the `canton` namespace.
     */
    public fun sessionNamespaces(accounts: List<DappWallet>): WcSessionNamespaces =
        WcSessionNamespaces(
            chains = listOf(chainId),
            accounts = accounts.map { Caip.account(chainId, it.partyId) },
            methods = REQUEST_METHODS,
            events = emptyList(),
        )

    /**
     * Routes one inbound `session_request` into the engine and maps the reply.
     *
     * The engine never throws for protocol failures — it returns a JSON-RPC
     * error — so this maps error responses to [WcResponse.Error] (carrying the
     * CIP-0103 / EIP-1193 code) and everything else to [WcResponse.Success].
     */
    public suspend fun handle(request: WcRequest): WcResponse {
        val frame = JsonRpcRequest(
            method = request.method,
            params = request.params,
            id = JsonPrimitive(request.requestId),
        )
        val response = handler.handle(frame)
        val error = response.error
        return if (error != null) {
            WcResponse.Error(error.code, error.message)
        } else {
            WcResponse.Success(response.result ?: JsonNull)
        }
    }

    public companion object {
        /**
         * The CIP-0103 **callable** methods, by wire name — the event methods
         * (`accountsChanged`, `txChanged`, `messageSignature`) are excluded
         * because they only travel wallet→dApp as notifications.
         */
        public val REQUEST_METHODS: List<String> = listOf(
            DappMethod.STATUS,
            DappMethod.CONNECT,
            DappMethod.DISCONNECT,
            DappMethod.IS_CONNECTED,
            DappMethod.GET_ACTIVE_NETWORK,
            DappMethod.LIST_ACCOUNTS,
            DappMethod.GET_PRIMARY_ACCOUNT,
            DappMethod.SIGN_MESSAGE,
            DappMethod.PREPARE_EXECUTE,
            DappMethod.PREPARE_EXECUTE_AND_WAIT,
            DappMethod.LEDGER_API,
        ).map { it.wire }
    }
}
