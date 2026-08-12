// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp.lan

import io.github.vsima.canton.dapp.JsonRpcRequest
import io.github.vsima.canton.dapp.JsonRpcResponse
import io.grpc.MethodDescriptor
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The gRPC tunnel that carries CIP-0103 JSON-RPC frames between a dApp and a
 * wallet on a LAN.
 *
 * There is deliberately **no `.proto`**. The OpenRPC document is the one
 * canonical schema for this protocol; a parallel proto would be a second
 * source of truth to keep in sync forever, for a transport whose only job is
 * to move opaque bytes. So the method is hand-registered here with a raw-byte
 * marshaller, exactly as the codegen setup steers you toward: `buf.gen.yaml`
 * sets `Server=false`, so a generated service would give no server anyway.
 *
 * Each frame is one JSON document, UTF-8 encoded. A single bidirectional
 * stream carries the whole session: requests dApp→wallet, responses and event
 * notifications wallet→dApp. The bidi shape is what gives the event channel
 * (`txChanged`, `accountsChanged`) for free — a request/response transport
 * like a deep link cannot deliver those.
 */
internal object DappTunnel {

    /** A frame is its own bytes — no framing beyond what gRPC already does. */
    val FRAME_MARSHALLER: MethodDescriptor.Marshaller<ByteArray> =
        object : MethodDescriptor.Marshaller<ByteArray> {
            override fun stream(value: ByteArray): InputStream = ByteArrayInputStream(value)
            override fun parse(stream: InputStream): ByteArray = stream.readBytes()
        }

    const val SERVICE_NAME: String = "io.github.vsima.canton.dapp.v1.DappTunnel"

    /** The one bidirectional method the session runs over. */
    val CONNECT: MethodDescriptor<ByteArray, ByteArray> =
        MethodDescriptor.newBuilder(FRAME_MARSHALLER, FRAME_MARSHALLER)
            .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, "Connect"))
            .build()

    private val json = Json { ignoreUnknownKeys = true }

    // ── Frame codec ────────────────────────────────────────────────────

    fun encode(request: JsonRpcRequest): ByteArray = request.encode().toString().encodeToByteArray()

    fun encode(response: JsonRpcResponse): ByteArray = response.encode().toString().encodeToByteArray()

    /**
     * Decodes a wallet→dApp frame, which is either a response (has `result`
     * or `error`) or an event notification (has `method`, no `id`). The
     * discriminator is the presence of `method`: a response never carries one.
     */
    fun decodeServerFrame(frame: ByteArray): ServerFrame {
        val obj = json.parseToJsonElement(frame.decodeToString()) as JsonObject
        return if (obj.containsKey("method")) {
            ServerFrame.Notification(JsonRpcRequest.decode(obj))
        } else {
            ServerFrame.Response(JsonRpcResponse.decode(obj))
        }
    }

    /** A dApp→wallet frame is always a request. */
    fun decodeRequest(frame: ByteArray): JsonRpcRequest =
        JsonRpcRequest.decode(json.parseToJsonElement(frame.decodeToString()) as JsonObject)

    sealed interface ServerFrame {
        data class Response(val response: JsonRpcResponse) : ServerFrame
        data class Notification(val notification: JsonRpcRequest) : ServerFrame
    }
}
