// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The only value of the `jsonrpc` member this protocol accepts. */
public const val JSON_RPC_VERSION: String = "2.0"

/**
 * A JSON-RPC 2.0 request or notification.
 *
 * [id] absent means a **notification**: no response is expected, which is how
 * the wallet pushes `accountsChanged`, `txChanged` and `messageSignature`.
 *
 * [params] is a bare object, not a positional array. That is what the
 * reference TypeScript client sends — `provider.request({ method, params })`
 * passes the params object straight through — and OpenRPC 0.5.0 leaves
 * `paramStructure` unset on every method, so the by-name form is the one
 * with an implementation behind it.
 */
public data class JsonRpcRequest(
    val method: String,
    val params: JsonElement? = null,
    val id: JsonElement? = null,
) {
    /** True when [id] is absent, so no response is expected. */
    public val isNotification: Boolean get() = id == null

    /** The frame as a JSON object: `jsonrpc` is always set, `params` and `id` only when present. */
    public fun encode(): JsonObject = buildJsonObject {
        put("jsonrpc", JSON_RPC_VERSION)
        put("method", method)
        if (params != null) put("params", params)
        // A null id is a legal JSON-RPC id, distinct from an absent one, so
        // this cannot be collapsed into `id?.let`.
        if (id != null) put("id", id)
    }

    /** Decoder for inbound request frames. */
    public companion object {
        /**
         * Parses one request frame.
         *
         * @throws DappException with [DappErrorCode.INVALID_PARAMS] when `jsonrpc`
         *   is not `"2.0"` or `method` is not a string.
         */
        public fun decode(json: JsonObject): JsonRpcRequest {
            json.requireVersion()
            val method = (json["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw DappException(
                    DappErrorCode.INVALID_PARAMS,
                    "JSON-RPC request has no string 'method': $json",
                )
            return JsonRpcRequest(method = method, params = json["params"], id = json["id"])
        }
    }
}

/**
 * A JSON-RPC 2.0 response. Exactly one of [result] and [error] is set;
 * [ok] and [failed] are the honest way to ask which.
 *
 * `result` may legitimately be JSON `null` — `disconnect` and
 * `prepareExecute` both return the OpenRPC `Null` schema — so "result is
 * null" cannot stand in for "this is an error".
 */
public data class JsonRpcResponse(
    val id: JsonElement?,
    val result: JsonElement? = null,
    val error: JsonRpcErrorBody? = null,
) {
    /** True when the response carries no [error]. */
    public val ok: Boolean get() = error == null
    /** True when the response carries an [error]. */
    public val failed: Boolean get() = error != null

    /**
     * The frame as a JSON object. `id` is emitted as JSON `null` when absent,
     * and when there is no error a null [result] is emitted as JSON `null` too —
     * both are what the spec requires of a response, unlike a request.
     */
    public fun encode(): JsonObject = buildJsonObject {
        put("jsonrpc", JSON_RPC_VERSION)
        put("id", id ?: JsonNull)
        if (error != null) put("error", error.encode()) else put("result", result ?: JsonNull)
    }

    /** The [result], or throws the [error] as a [DappException]. */
    public fun resultOrThrow(): JsonElement {
        error?.let { throw it.toException() }
        return result ?: JsonNull
    }

    /** Constructors for the two shapes a response can take, plus the decoder. */
    public companion object {
        /** A successful response. A null [result] is emitted as JSON `null`. */
        public fun success(id: JsonElement?, result: JsonElement?): JsonRpcResponse =
            JsonRpcResponse(id = id, result = result ?: JsonNull)

        /** A failed response carrying [error] verbatim. */
        public fun failure(id: JsonElement?, error: JsonRpcErrorBody): JsonRpcResponse =
            JsonRpcResponse(id = id, error = error)

        /**
         * A failed response whose error body is built from [exception] by
         * [JsonRpcErrorBody.from].
         */
        public fun failure(id: JsonElement?, exception: DappException): JsonRpcResponse =
            failure(id, JsonRpcErrorBody.from(exception))

        /**
         * Parses one response frame. Only the `error` member is validated here;
         * `result` is kept raw for the typed decoders in [DappJson].
         *
         * @throws DappException with [DappErrorCode.INVALID_PARAMS] when `jsonrpc`
         *   is not `"2.0"`, or [DappErrorCode.INTERNAL] when an `error` member has no
         *   numeric `code`.
         */
        public fun decode(json: JsonObject): JsonRpcResponse {
            json.requireVersion()
            val error = (json["error"] as? JsonObject)?.let { JsonRpcErrorBody.decode(it) }
            return JsonRpcResponse(id = json["id"], result = json["result"], error = error)
        }
    }
}

/** The `error` member of a JSON-RPC response. */
public data class JsonRpcErrorBody(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
) {
    /** The body as a JSON object; `data` is omitted when null. */
    public fun encode(): JsonObject = buildJsonObject {
        put("code", code)
        put("message", message)
        if (data != null) put("data", data)
    }

    /**
     * Maps onto [DappException]. An unrecognised code becomes
     * [DappErrorCode.INTERNAL] rather than throwing: a wallet is free to use
     * codes this SDK predates, and losing the message would be worse than
     * losing the exact code.
     */
    public fun toException(): DappException = DappException(
        errorCode = DappErrorCode.fromCode(code) ?: DappErrorCode.INTERNAL,
        message = if (DappErrorCode.fromCode(code) != null) message else "$message (code $code)",
        data = data,
    )

    /** Conversions between the wire body and [DappException]. */
    public companion object {
        /**
         * The wire body for [exception]. Falls back to the error code's name
         * when the exception has no message.
         */
        public fun from(exception: DappException): JsonRpcErrorBody = JsonRpcErrorBody(
            code = exception.code,
            message = exception.message ?: exception.errorCode.name,
            data = exception.data,
        )

        /**
         * Parses an `error` member. A missing `message` reads as empty rather
         * than failing: the code is the part a caller branches on.
         *
         * @throws DappException with [DappErrorCode.INTERNAL] when `code` is not an
         *   integer.
         */
        public fun decode(json: JsonObject): JsonRpcErrorBody = JsonRpcErrorBody(
            code = (json["code"] as? JsonPrimitive)?.content?.toIntOrNull()
                ?: throw DappException(
                    DappErrorCode.INTERNAL,
                    "JSON-RPC error has no numeric 'code': $json",
                ),
            message = (json["message"] as? JsonPrimitive)?.content ?: "",
            data = json["data"],
        )
    }
}

private fun JsonObject.requireVersion() {
    val version = (this["jsonrpc"] as? JsonPrimitive)?.jsonPrimitive?.content
    if (version != JSON_RPC_VERSION) {
        throw DappException(
            DappErrorCode.INVALID_PARAMS,
            "expected jsonrpc '$JSON_RPC_VERSION', was '$version'",
        )
    }
}
