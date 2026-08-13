// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp.wc

import kotlinx.serialization.json.JsonElement

/**
 * The transport-neutral shapes the WalletConnect adapter exchanges with its
 * client binding.
 *
 * They are deliberately not WalletConnect-library types. The adapter's job is
 * the Canton half — CAIP encoding and CIP-0103 frame routing — and it should
 * be testable, and swappable across WalletConnect client libraries, without
 * pulling one in. A Reown WalletKit delegate (in the app / an Android add-on)
 * maps its `SessionRequest`/`SessionProposal` onto these and back.
 */

/**
 * One inbound WalletConnect `session_request`, normalised.
 *
 * [requestId] is the WalletConnect envelope id the client responds against;
 * [method]/[params] are the CIP-0103 JSON-RPC call it carries.
 */
public data class WcRequest(
    val topic: String,
    val requestId: Long,
    val chainId: String,
    val method: String,
    val params: JsonElement? = null,
)

/** The adapter's answer to a [WcRequest] — exactly one of success or error. */
public sealed interface WcResponse {
    /** The CIP-0103 result to return over the session. */
    public data class Success(val result: JsonElement) : WcResponse

    /** A CIP-0103 / EIP-1193 error code and message to return over the session. */
    public data class Error(val code: Int, val message: String) : WcResponse
}

/**
 * The namespaces a session is approved with: the chain(s), the CAIP-10
 * accounts shared, the methods answered, and the events emitted. A Reown
 * delegate turns this into the `Wallet.Params.SessionApprove` namespaces.
 */
public data class WcSessionNamespaces(
    val chains: List<String>,
    val accounts: List<String>,
    val methods: List<String>,
    val events: List<String>,
)
