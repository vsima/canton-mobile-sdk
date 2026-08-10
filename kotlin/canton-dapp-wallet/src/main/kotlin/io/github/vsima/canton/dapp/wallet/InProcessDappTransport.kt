// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp.wallet

import io.github.vsima.canton.dapp.DappEvent
import io.github.vsima.canton.dapp.DappTransport
import io.github.vsima.canton.dapp.JsonRpcRequest
import io.github.vsima.canton.dapp.JsonRpcResponse
import kotlinx.coroutines.flow.Flow

/**
 * Binds a `DappClient` straight to a [DappSession] in the same process.
 *
 * Two uses, and the second is the reason this ships rather than living in a
 * test source set:
 *
 * 1. **The acceptance harness.** Client and engine exercise the real codec,
 *    the real dispatch and the real approval flow with no transport in the
 *    way, so a failure is a protocol failure.
 * 2. **Embedding.** A B2B app that ships this wallet layer inside itself
 *    still talks to it through the *standard* CIP-0103 API. Moving to an
 *    external wallet later becomes a transport swap instead of a rewrite —
 *    which is only true if the embedded path uses the same frames, and this
 *    is what makes it do so.
 *
 * Frames are passed as objects without serializing to text. That is the
 * point of an in-process transport, but it does mean this path will not
 * catch a codec bug that only appears once JSON is stringified; the golden
 * vectors in `testdata/dapp/` cover that separately.
 */
public class InProcessDappTransport(
    private val session: DappSession,
) : DappTransport {

    override suspend fun send(request: JsonRpcRequest): JsonRpcResponse = session.handle(request)

    override val events: Flow<DappEvent> get() = session.events
}
