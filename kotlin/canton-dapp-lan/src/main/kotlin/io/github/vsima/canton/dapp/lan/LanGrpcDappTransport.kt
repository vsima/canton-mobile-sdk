// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp.lan

import io.github.vsima.canton.dapp.DappEvent
import io.github.vsima.canton.dapp.DappJson
import io.github.vsima.canton.dapp.DappTransport
import io.github.vsima.canton.dapp.JsonRpcRequest
import io.github.vsima.canton.dapp.JsonRpcResponse
import io.grpc.CallOptions
import io.grpc.ChannelCredentials
import io.grpc.InsecureChannelCredentials
import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.ClientCalls
import io.grpc.stub.StreamObserver
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The dApp side of the LAN transport: a [DappTransport] over a gRPC
 * bidirectional stream to a [LanGrpcDappServer].
 *
 * The stream opens on construction and stays open for the session. Requests go
 * out as frames; responses come back correlated by their JSON-RPC id, and
 * event notifications arrive on [events] — the reason to hold a stream open
 * rather than dial per request.
 *
 * Correlation is explicit because a bidi stream interleaves responses with
 * notifications and does not promise response order. Each in-flight request
 * parks a [CompletableDeferred] keyed by its id; the receive side completes it
 * when the matching response arrives.
 *
 * Security note: this slice dials with [InsecureChannelCredentials]. The LAN
 * trust story pins the peer's self-signed certificate via the QR fingerprint
 * (`TlsTrust.TrustRoots.Certificates`); the credentials are a constructor seam
 * so that drops in unchanged. Loopback and pinned LAN only — never plaintext
 * across an untrusted network.
 */
public class LanGrpcDappTransport(
    host: String,
    port: Int,
    credentials: ChannelCredentials = InsecureChannelCredentials.create(),
) : DappTransport, AutoCloseable {

    private val channel: ManagedChannel =
        OkHttpChannelBuilder.forAddress(host, port, credentials).build()

    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonRpcResponse>>()
    private val sendLock = Mutex()

    private val _events = MutableSharedFlow<DappEvent>(replay = 0, extraBufferCapacity = 64)
    override val events: Flow<DappEvent> = _events.asSharedFlow()

    private val requestObserver: StreamObserver<ByteArray> =
        ClientCalls.asyncBidiStreamingCall(
            channel.newCall(DappTunnel.CONNECT, CallOptions.DEFAULT),
            object : StreamObserver<ByteArray> {
                override fun onNext(frame: ByteArray) {
                    when (val decoded = DappTunnel.decodeServerFrame(frame)) {
                        is DappTunnel.ServerFrame.Response -> {
                            val key = decoded.response.id?.toString()
                            // A response for an id we are not waiting on is a
                            // protocol error by the peer, not something to
                            // crash the stream over — drop it.
                            key?.let { pending.remove(it)?.complete(decoded.response) }
                        }
                        is DappTunnel.ServerFrame.Notification -> {
                            DappJson.decodeEvent(decoded.notification)?.let { _events.tryEmit(it) }
                        }
                    }
                }

                override fun onError(t: Throwable) {
                    failAllPending(t)
                }

                override fun onCompleted() {
                    failAllPending(IllegalStateException("the wallet closed the connection"))
                }
            },
        )

    override suspend fun send(request: JsonRpcRequest): JsonRpcResponse {
        val key = request.id?.toString()
            ?: throw IllegalArgumentException("a request sent over the tunnel must carry an id")
        val deferred = CompletableDeferred<JsonRpcResponse>()
        pending[key] = deferred
        try {
            // onNext is not concurrency-safe; one sender at a time onto the wire.
            sendLock.withLock { requestObserver.onNext(DappTunnel.encode(request)) }
        } catch (t: Throwable) {
            pending.remove(key)
            throw t
        }
        return deferred.await()
    }

    override fun close() {
        runCatching { requestObserver.onCompleted() }
        channel.shutdownNow()
        failAllPending(IllegalStateException("transport closed"))
    }

    private fun failAllPending(cause: Throwable) {
        val snapshot = pending.keys.toList()
        for (key in snapshot) pending.remove(key)?.completeExceptionally(cause)
    }
}
