// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonDappKit
import Foundation
import GRPCCore
import GRPCNIOTransportHTTP2

/// The dApp side of the LAN transport: a ``DappTransport`` over a gRPC
/// bidirectional stream to a ``LanGrpcDappServer``.
///
/// The stream opens on construction and stays open for the session. Requests
/// go out as frames; responses come back correlated by their JSON-RPC id, and
/// event notifications arrive on ``events`` — the reason to hold a stream open
/// rather than dial per request.
///
/// Correlation is explicit because a bidi stream interleaves responses with
/// notifications and does not promise response order. Each in-flight request
/// parks a continuation keyed by its id; the receive side resumes it when the
/// matching response arrives.
///
/// Security note: this slice dials plaintext. The LAN trust story pins the
/// peer's self-signed certificate via the fingerprint in the pairing QR
/// (`TLSTrust`); `transportSecurity` is a constructor seam so that drops in
/// unchanged. Loopback and pinned LAN only — never plaintext across an
/// untrusted network.
public final class LanGrpcDappTransport: DappTransport, @unchecked Sendable {

    /// Lock-guarded map of in-flight requests, keyed by JSON-RPC id.
    private final class Pending: @unchecked Sendable {
        private let lock = NSLock()
        private var map: [String: CheckedContinuation<JSONRPCResponse, Error>] = [:]
        private var closed = false

        func register(_ key: String, _ continuation: CheckedContinuation<JSONRPCResponse, Error>) -> Bool {
            lock.lock(); defer { lock.unlock() }
            if closed { return false }
            map[key] = continuation
            return true
        }

        func complete(_ key: String, with response: JSONRPCResponse) {
            lock.lock(); let continuation = map.removeValue(forKey: key); lock.unlock()
            continuation?.resume(returning: response)
        }

        func failAll(_ error: Error) {
            lock.lock(); let all = map; map.removeAll(); closed = true; lock.unlock()
            for continuation in all.values { continuation.resume(throwing: error) }
        }
    }

    private let pending = Pending()
    private let outbound: AsyncStream<[UInt8]>
    private let outboundContinuation: AsyncStream<[UInt8]>.Continuation
    private let eventStream: AsyncStream<DappEvent>
    private let eventContinuation: AsyncStream<DappEvent>.Continuation
    private let runner: Task<Void, Never>

    public nonisolated var events: AsyncStream<DappEvent> { eventStream }

    public init(
        host: String,
        port: Int,
        transportSecurity: HTTP2ClientTransport.Posix.TransportSecurity = .plaintext
    ) {
        (outbound, outboundContinuation) = AsyncStream<[UInt8]>.makeStream()
        (eventStream, eventContinuation) = AsyncStream<DappEvent>.makeStream()

        let pending = self.pending
        let outbound = self.outbound
        let eventContinuation = self.eventContinuation

        runner = Task {
            do {
                let transport = try HTTP2ClientTransport.Posix(
                    target: .ipv4(host: host, port: port),
                    transportSecurity: transportSecurity
                )
                try await withGRPCClient(transport: transport) { client in
                    // The producer drains outbound frames onto the stream and
                    // returns (half-closing) when the transport is closed.
                    let request = StreamingClientRequest(of: [UInt8].self) { writer in
                        for await frame in outbound { try await writer.write(frame) }
                    }
                    try await client.bidirectionalStreaming(
                        request: request,
                        descriptor: DappTunnel.connect,
                        serializer: ByteArraySerializer(),
                        deserializer: ByteArrayDeserializer(),
                        options: .defaults
                    ) { response in
                        for try await frame in response.messages {
                            switch try DappTunnel.decodeServerFrame(frame) {
                            case .response(let response):
                                // A response for an id we are not waiting on is
                                // a peer protocol error, not something to crash
                                // the stream over — drop it.
                                if let key = DappTunnel.idKey(response.id) {
                                    pending.complete(key, with: response)
                                }
                            case .notification(let notification):
                                if let event = try DappJSON.decodeEvent(notification) {
                                    eventContinuation.yield(event)
                                }
                            }
                        }
                    }
                }
                pending.failAll(LanTransportClosed())
                eventContinuation.finish()
            } catch {
                pending.failAll(error)
                eventContinuation.finish()
            }
        }
    }

    public func send(_ request: JSONRPCRequest) async throws -> JSONRPCResponse {
        guard let key = DappTunnel.idKey(request.id) else {
            throw DappError(code: .invalidParams, message: "a request sent over the tunnel must carry an id")
        }
        let frame = try DappTunnel.encode(request)
        return try await withCheckedThrowingContinuation { continuation in
            if pending.register(key, continuation) {
                outboundContinuation.yield(frame)
            } else {
                continuation.resume(throwing: LanTransportClosed())
            }
        }
    }

    /// Closes the stream and fails any in-flight requests. Idempotent.
    public func close() {
        outboundContinuation.finish()
        runner.cancel()
        pending.failAll(LanTransportClosed())
        eventContinuation.finish()
    }
}
