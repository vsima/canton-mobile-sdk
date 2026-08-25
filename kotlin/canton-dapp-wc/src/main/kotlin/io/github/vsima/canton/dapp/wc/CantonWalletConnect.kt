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
 * `DappSession`, with no relay.
 *
 * The approval, signing and prepare→sign→execute all happen inside the engine;
 * this class adds no policy. Over a WalletConnect session the Canton ecosystem
 * (the official `@canton-network/dapp-sdk`, PartyLayer) names CIP-0103 methods
 * with a `canton_` prefix — the convention EVM uses with `eth_` — so this
 * advertises the `canton_` wire names and normalizes each inbound method back to
 * the bare CIP-0103 name the engine answers. Bare names are still accepted, so a
 * peer that speaks CIP-0103 verbatim (the reference dApp server) keeps working.
 * See [WcMethod].
 */
public class CantonWalletConnect(
    private val handler: DappRequestHandler,
    networkId: String,
) {
    /** The CAIP-2 chain this session advertises (validated from `networkId`). */
    public val chainId: String = Caip.chainId(networkId)

    /** The `canton_` request methods advertised over a session. */
    public val methods: List<String> get() = WcMethod.ADVERTISED

    /**
     * The events a `canton_` dApp subscribes to. Advertised for interop;
     * proactive emission is a follow-up.
     */
    public val events: List<String> get() = WcMethod.EVENTS

    /**
     * The namespaces to approve a session with, sharing [accounts]. Which
     * accounts to share is the wallet's decision (its connect-approval UI); this
     * only projects them into CAIP-10 form under the `canton` namespace.
     */
    public fun sessionNamespaces(accounts: List<DappWallet>): WcSessionNamespaces =
        sessionNamespaces(chainId, accounts)

    public companion object {
        /**
         * [sessionNamespaces] without an adapter instance. A wallet that keeps
         * one adapter (and one `DappSession`) per peer needs namespaces at
         * proposal time, before any session for that peer exists; the
         * namespaces depend only on the chain and the offered accounts.
         */
        public fun sessionNamespaces(chainId: String, accounts: List<DappWallet>): WcSessionNamespaces =
            WcSessionNamespaces(
                chains = listOf(chainId),
                accounts = accounts.map { Caip.account(chainId, it.partyId) },
                methods = WcMethod.ADVERTISED,
                events = WcMethod.EVENTS,
            )
    }

    /**
     * Routes one inbound `session_request` into the engine and maps the reply.
     *
     * The engine never throws for protocol failures — it returns a JSON-RPC
     * error — so this maps error responses to [WcResponse.Error] (carrying the
     * CIP-0103 / EIP-1193 code) and everything else to [WcResponse.Success].
     */
    public suspend fun handle(request: WcRequest): WcResponse {
        val frame = JsonRpcRequest(
            method = WcMethod.normalize(request.method),
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

}

/**
 * The WalletConnect method-name convention for Canton CIP-0103.
 *
 * The Canton dApp ecosystem — the official `@canton-network/dapp-sdk` and
 * PartyLayer — carries CIP-0103 over a WalletConnect session under `canton_`-
 * prefixed method names, and names the prepare-sign-execute call
 * `canton_prepareSignExecute` (for both `prepareExecute` and
 * `prepareExecuteAndWait`). This advertises that wire set and maps an inbound
 * method back to the bare CIP-0103 name the engine dispatches.
 */
internal object WcMethod {
    const val PREFIX: String = "canton_"
    const val PREPARE_SIGN_EXECUTE: String = "canton_prepareSignExecute"

    /**
     * The `canton_` methods advertised at proposal time — the ecosystem's set.
     * `connect` / `disconnect` / `isConnected` are handled dApp-side and never
     * sent as requests, so they are not advertised; they are still accepted
     * inbound (bare or prefixed) via [normalize].
     */
    val ADVERTISED: List<String> = listOf(
        PREFIX + DappMethod.STATUS.wire,
        PREFIX + DappMethod.GET_ACTIVE_NETWORK.wire,
        PREFIX + DappMethod.LIST_ACCOUNTS.wire,
        PREFIX + DappMethod.GET_PRIMARY_ACCOUNT.wire,
        PREFIX + DappMethod.SIGN_MESSAGE.wire,
        PREPARE_SIGN_EXECUTE,
        PREFIX + DappMethod.LEDGER_API.wire,
    )

    /** The events a `canton_` dApp subscribes to. */
    val EVENTS: List<String> = listOf("accountsChanged", "statusChanged")

    /**
     * Maps an inbound wire method to the bare CIP-0103 method the engine answers:
     * `canton_prepareSignExecute` → `prepareExecuteAndWait`; any other `canton_`
     * method drops the prefix; a bare method passes through unchanged.
     */
    fun normalize(method: String): String = when {
        method == PREPARE_SIGN_EXECUTE -> DappMethod.PREPARE_EXECUTE_AND_WAIT.wire
        method.startsWith(PREFIX) -> method.removePrefix(PREFIX)
        else -> method
    }
}
