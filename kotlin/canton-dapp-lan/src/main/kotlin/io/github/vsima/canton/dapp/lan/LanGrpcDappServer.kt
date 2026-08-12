// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp.lan

import io.github.vsima.canton.dapp.DappEvent
import io.github.vsima.canton.dapp.DappJson
import io.github.vsima.canton.dapp.DappRequestHandler
import io.grpc.InsecureServerCredentials
import io.grpc.Server
import io.grpc.ServerCredentials
import io.grpc.ServerServiceDefinition
import io.grpc.okhttp.OkHttpServerBuilder
import io.grpc.stub.ServerCalls
import io.grpc.stub.StreamObserver
import java.net.InetSocketAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Serves the CIP-0103 provider over a LAN gRPC bidirectional stream.
 *
 * The wallet is the CIP-0103 **provider** even though it is the TCP *server*
 * here — the two are independent. In practice the stationary side (a POS,
 * this) listens and shows a QR, and the mobile wallet dials in; the roles
 * would be identical if it were the other way around.
 *
 * This slice serves **one** [DappRequestHandler] to whatever connects, which
 * is enough to prove a real cross-process session. A production server mints a
 * handler (a `DappSession`) per connection from the peer the transport
 * attests — that is a factory parameter, and F3 work, not this.
 *
 * Security note: this slice listens with [InsecureServerCredentials] on
 * loopback. The LAN transport's trust story is a self-signed cert pinned by
 * the fingerprint in the pairing QR (`TlsTrust.TrustRoots.Certificates`); the
 * credentials are a constructor seam so that drops in without touching this
 * class. Do not expose an insecure server beyond loopback.
 */
public class LanGrpcDappServer(
    private val handler: DappRequestHandler,
    private val bindAddress: InetSocketAddress = InetSocketAddress("127.0.0.1", 0),
    private val credentials: ServerCredentials = InsecureServerCredentials.create(),
) {
    private var server: Server? = null

    /** The port the server is listening on. Valid only after [start]. */
    public val port: Int get() = server?.port ?: error("server not started")

    public fun start(): LanGrpcDappServer {
        server = OkHttpServerBuilder
            .forPort(bindAddress, credentials)
            .addService(serviceDefinition())
            .build()
            .start()
        return this
    }

    public fun shutdown() {
        server?.shutdownNow()
        server = null
    }

    private fun serviceDefinition(): ServerServiceDefinition =
        ServerServiceDefinition.builder(DappTunnel.SERVICE_NAME)
            .addMethod(
                DappTunnel.CONNECT,
                ServerCalls.asyncBidiStreamingCall { responseObserver ->
                    Connection(responseObserver).requestObserver
                },
            )
            .build()

    /**
     * One live bidi stream.
     *
     * A `StreamObserver` is not safe for concurrent `onNext`, and two things
     * write to it — the per-request handlers and the event forwarder. So every
     * outbound frame funnels through one [Channel] drained by a single writer
     * coroutine, which also keeps frames ordered.
     */
    private inner class Connection(
        private val responseObserver: StreamObserver<ByteArray>,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val outbound = Channel<ByteArray>(Channel.UNLIMITED)

        val requestObserver: StreamObserver<ByteArray> = object : StreamObserver<ByteArray> {
            override fun onNext(frame: ByteArray) {
                val request = DappTunnel.decodeRequest(frame)
                // Each request is handled on its own coroutine; the writer
                // funnel is what serialises the replies back onto the stream.
                scope.launch {
                    val response = handler.handle(request)
                    outbound.send(DappTunnel.encode(response))
                }
            }

            override fun onError(t: Throwable) {
                close()
            }

            override fun onCompleted() {
                outbound.close()
            }
        }

        init {
            // Drain outbound frames to the wire, one at a time.
            scope.launch {
                try {
                    for (frame in outbound) responseObserver.onNext(frame)
                    responseObserver.onCompleted()
                } catch (_: Throwable) {
                    // The peer went away mid-write; nothing to report to it.
                } finally {
                    scope.cancel()
                }
            }
            // Forward provider events as JSON-RPC notifications.
            scope.launch {
                handler.events.collect { event: DappEvent ->
                    outbound.send(DappTunnel.encode(DappJson.encodeEvent(event)))
                }
            }
        }

        private fun close() {
            outbound.close()
            scope.cancel()
        }
    }
}
