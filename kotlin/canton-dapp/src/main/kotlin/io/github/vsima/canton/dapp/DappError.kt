// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp

import kotlinx.serialization.json.JsonElement

/**
 * The error codes CIP-0103 inherits from EIP-1474 / EIP-1193.
 *
 * Two families share the space and mean different things. The 4xxx codes are
 * *provider* errors — the wallet understood the request and declined it. The
 * negative codes are JSON-RPC's own, for requests that were malformed or
 * failed downstream. Getting this wrong is user-visible: a dApp that treats
 * [USER_REJECTED] as a transport failure will retry a request the user just
 * declined.
 */
public enum class DappErrorCode(public val code: Int) {
    /** The user declined. Terminal: do not retry, and change no state. */
    USER_REJECTED(4001),

    /** The caller has no grant for this account or method. */
    UNAUTHORIZED(4100),

    /**
     * The method is not supported by this provider.
     *
     * Note this is *not* JSON-RPC's `-32601 Method not found`. EIP-1193
     * defines 4200 for a provider that does not implement an otherwise valid
     * method, and dApp SDKs in this ecosystem branch on 4200.
     */
    UNSUPPORTED_METHOD(4200),

    /** The provider is disconnected from the dApp. */
    DISCONNECTED(4900),

    /** The provider is connected, but not to a network. */
    CHAIN_DISCONNECTED(4901),

    /** Malformed parameters — ours or the caller's. */
    INVALID_PARAMS(-32602),

    /** An unexpected failure inside the provider. */
    INTERNAL(-32603),

    /** Well-formed parameters the ledger nonetheless rejected. */
    INVALID_INPUT(-32000),

    /** The transaction was rejected by the ledger. */
    TRANSACTION_REJECTED(-32003),
    ;

    public companion object {
        private val byCode: Map<Int, DappErrorCode> = entries.associateBy { it.code }

        public fun fromCode(code: Int): DappErrorCode? = byCode[code]
    }
}

/**
 * A CIP-0103 error, thrown by [DappClient] when the wallet returns one and
 * by the wallet-side engine to produce one.
 *
 * [data] carries the optional JSON-RPC `error.data` unchanged, so a caller
 * can surface a participant `traceId` without this SDK having to model every
 * shape a wallet might attach.
 */
public class DappException(
    public val errorCode: DappErrorCode,
    message: String,
    public val data: JsonElement? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    /** The numeric wire code, for callers that branch on it directly. */
    public val code: Int get() = errorCode.code

    /** True when the user declined — the one error a dApp should never retry. */
    public val isUserRejection: Boolean get() = errorCode == DappErrorCode.USER_REJECTED

    override fun toString(): String = "DappException(${errorCode.name}/$code): $message"
}
