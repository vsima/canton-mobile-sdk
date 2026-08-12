// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonDappKit
import Foundation
import GRPCCore
import GRPCNIOTransportHTTP2

/// Serves the CIP-0103 provider over a LAN gRPC bidirectional stream.
///
/// The wallet is the CIP-0103 **provider** even though it is the TCP *server*
/// here — the two are independent. In practice the stationary side (a POS,
/// this) listens and shows a QR, and the mobile wallet dials in.
///
/// This slice serves **one** ``DappRequestHandler`` to whatever connects, which
/// is enough to prove a real cross-process session. A production server mints a
/// handler (a `DappSession`) per connection from the peer the transport
/// attests — a factory parameter, and later work, not this.
///
/// Security note: this slice listens plaintext on loopback. The LAN transport's
/// trust story is a self-signed cert pinned by the fingerprint in the pairing
/// QR; `transportSecurity` is a constructor seam so that drops in without
/// touching this class. Do not expose an insecure server beyond loopback.
public final class LanGrpcDappServer: @unchecked Sendable {
    private let handler: any DappRequestHandler
    private let host: String
    private let transportSecurity: HTTP2ServerTransport.Posix.TransportSecurity
    private let shutdownStream: AsyncStream<Void>
    private let shutdownContinuation: AsyncStream<Void>.Continuation
    private var runner: Task<Void, Never>?

    public init(
        handler: any DappRequestHandler,
        host: String = "127.0.0.1",
        transportSecurity: HTTP2ServerTransport.Posix.TransportSecurity = .plaintext
    ) {
        self.handler = handler
        self.host = host
        self.transportSecurity = transportSecurity
        (shutdownStream, shutdownContinuation) = AsyncStream<Void>.makeStream()
    }

    /// Binds (ephemeral port by default) and returns the port it is listening
    /// on. The server keeps running until ``shutdown()``.
    public func start(port: Int = 0) async throws -> Int {
        let handler = self.handler
        let host = self.host
        let transportSecurity = self.transportSecurity
        let shutdownStream = self.shutdownStream
        let bound = OneShot<Int>()

        runner = Task {
            do {
                try await withGRPCServer(
                    transport: .http2NIOPosix(
                        address: .ipv4(host: host, port: port),
                        transportSecurity: transportSecurity
                    ),
                    services: [TunnelService(handler: handler)]
                ) { server in
                    let listening = try await server.listeningAddress?.ipv4?.port ?? 0
                    bound.resume(returning: listening)
                    // Keep the server up until shutdown() finishes the stream.
                    for await _ in shutdownStream {}
                }
            } catch {
                bound.resume(throwing: error)
            }
        }
        return try await bound.value()
    }

    public func shutdown() {
        shutdownContinuation.finish()
        runner?.cancel()
    }
}

/// The bidi method, bound to a ``DappRequestHandler``.
///
/// A `RPCWriter` is written from one place only — the drain loop — so the
/// per-request replies and the event notifications, which both produce frames,
/// funnel through one `AsyncStream` and never race on the writer.
private struct TunnelService: RegistrableRPCService {
    let handler: any DappRequestHandler

    func registerMethods<Transport: ServerTransport>(with router: inout RPCRouter<Transport>) {
        let handler = self.handler
        router.registerHandler(
            forMethod: DappTunnel.connect,
            deserializer: ByteArrayDeserializer(),
            serializer: ByteArraySerializer()
        ) { request, _ in
            StreamingServerResponse(of: [UInt8].self) { writer in
                let (out, outContinuation) = AsyncStream<[UInt8]>.makeStream()
                try await withThrowingTaskGroup(of: Void.self) { group in
                    // Forward provider events as notification frames.
                    group.addTask {
                        for await event in handler.events {
                            if let frame = try? DappTunnel.encode(DappJSON.encodeEvent(event)) {
                                outContinuation.yield(frame)
                            }
                        }
                    }
                    // Read requests, handle each, reply — then close the funnel.
                    group.addTask {
                        do {
                            for try await frame in request.messages {
                                let decoded = try DappTunnel.decodeRequest(frame)
                                let response = await handler.handle(decoded)
                                if let frame = try? DappTunnel.encode(response) {
                                    outContinuation.yield(frame)
                                }
                            }
                        } catch {
                            // The client dropped mid-stream; fall through to close.
                        }
                        outContinuation.finish()
                    }
                    // Drain the funnel to the wire (single writer). Ends when
                    // the request reader finishes and closes `out`.
                    for await frame in out { try await writer.write(frame) }
                    group.cancelAll()
                }
                return [:]
            }
        }
    }
}

/// A continuation that resolves exactly once — so a post-bind server error in
/// the run task cannot resume the `start()` awaiter a second time.
private final class OneShot<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Value, Error>?
    private var settled: Result<Value, Error>?

    func value() async throws -> Value {
        try await withCheckedThrowingContinuation { continuation in
            lock.lock()
            if let settled {
                lock.unlock()
                continuation.resume(with: settled)
            } else {
                self.continuation = continuation
                lock.unlock()
            }
        }
    }

    func resume(returning value: Value) { settle(.success(value)) }
    func resume(throwing error: Error) { settle(.failure(error)) }

    private func settle(_ result: Result<Value, Error>) {
        lock.lock()
        if settled != nil { lock.unlock(); return }
        settled = result
        let continuation = self.continuation
        self.continuation = nil
        lock.unlock()
        continuation?.resume(with: result)
    }
}
