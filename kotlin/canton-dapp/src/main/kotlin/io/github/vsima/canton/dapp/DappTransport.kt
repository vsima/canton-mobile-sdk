// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Moves JSON-RPC frames between a dApp and a wallet.
 *
 * The currency is [JsonRpcRequest]/[JsonRpcResponse] rather than a typed
 * request union, because that is literally what crosses the wire and every
 * planned transport is a way of carrying those bytes somewhere:
 *
 * - `InProcessDappTransport` — same process, for tests and for apps that
 *   embed the wallet layer but still want to code against the standard API.
 * - deep link / App Link — same device, request-response only.
 * - LAN gRPC — two devices, a bidirectional stream of these same frames.
 *
 * Keeping the seam at the frame means a new transport implements two members
 * and inherits the whole protocol, and it is why the JSON-RPC document stays
 * the single schema — no transport gets to define its own.
 */
public interface DappTransport {

    /**
     * Sends a request and awaits its response.
     *
     * Implementations should throw [DappException] for protocol-level
     * failures. A transport-level failure (socket closed, app not installed)
     * may surface as any exception; [DappClient] does not translate those,
     * because a caller needs to tell "the wallet said no" apart from "the
     * wallet was never reached".
     */
    public suspend fun send(request: JsonRpcRequest): JsonRpcResponse

    /**
     * Events pushed by the wallet.
     *
     * Defaults to empty: a deep-link transport genuinely cannot deliver
     * these, and forcing every implementation to write `emptyFlow()` would
     * only obscure which ones can. A dApp that needs events should say so by
     * choosing a transport that has them.
     */
    public val events: Flow<DappEvent> get() = emptyFlow()
}

/**
 * The wallet-side counterpart of [DappTransport]: something that answers
 * JSON-RPC frames and emits events.
 *
 * This is what a *server* transport routes inbound frames into. It lives here,
 * in `canton-dapp`, rather than in the wallet module on purpose: a transport
 * that carries both roles (LAN gRPC, later WalletConnect) depends only on
 * `canton-dapp`, so it must be able to name the provider without reaching into
 * `canton-dapp-wallet`. The provider engine there satisfies this structurally.
 *
 * The mirror of [DappTransport] — `send` answers a request, this *is* the
 * thing being sent to.
 */
public interface DappRequestHandler {
    /** Handle one request and return its response. Must not throw for
     *  protocol-level failures — return a JSON-RPC error response instead. */
    public suspend fun handle(request: JsonRpcRequest): JsonRpcResponse

    /** Events to forward to the connected dApp as JSON-RPC notifications. */
    public val events: Flow<DappEvent>
}
