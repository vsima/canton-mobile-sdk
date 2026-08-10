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
    public val isNotification: Boolean get() = id == null

    public fun encode(): JsonObject = buildJsonObject {
        put("jsonrpc", JSON_RPC_VERSION)
        put("method", method)
        if (params != null) put("params", params)
        // A null id is a legal JSON-RPC id, distinct from an absent one, so
        // this cannot be collapsed into `id?.let`.
        if (id != null) put("id", id)
    }

    public companion object {
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
    public val ok: Boolean get() = error == null
    public val failed: Boolean get() = error != null

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

    public companion object {
        public fun success(id: JsonElement?, result: JsonElement?): JsonRpcResponse =
            JsonRpcResponse(id = id, result = result ?: JsonNull)

        public fun failure(id: JsonElement?, error: JsonRpcErrorBody): JsonRpcResponse =
            JsonRpcResponse(id = id, error = error)

        public fun failure(id: JsonElement?, exception: DappException): JsonRpcResponse =
            failure(id, JsonRpcErrorBody.from(exception))

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

    public companion object {
        public fun from(exception: DappException): JsonRpcErrorBody = JsonRpcErrorBody(
            code = exception.code,
            message = exception.message ?: exception.errorCode.name,
            data = exception.data,
        )

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
