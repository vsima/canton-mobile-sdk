// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonDappKit
import Foundation
import GRPCCore

/// The gRPC tunnel that carries CIP-0103 JSON-RPC frames between a dApp and a
/// wallet on a LAN.
///
/// There is deliberately **no `.proto`**. The OpenRPC document is the one
/// canonical schema for this protocol; a parallel proto would be a second
/// source of truth to keep in sync forever, for a transport whose only job is
/// to move opaque bytes. So the method is hand-registered with a raw-byte
/// serializer.
///
/// Each frame is one JSON document, UTF-8 encoded. A single bidirectional
/// stream carries the whole session: requests dApp→wallet, responses and event
/// notifications wallet→dApp. The bidi shape is what gives the event channel
/// (`txChanged`, `accountsChanged`) for free — a request/response transport
/// like a deep link cannot deliver those.
enum DappTunnel {

    static let serviceName = "io.github.vsima.canton.dapp.v1.DappTunnel"

    /// The one bidirectional method the session runs over.
    static let connect = MethodDescriptor(fullyQualifiedService: serviceName, method: "Connect")

    // ── Frame codec ────────────────────────────────────────────────────

    static func encode(_ request: JSONRPCRequest) throws -> [UInt8] {
        Array(try request.encoded().serialized())
    }

    static func encode(_ response: JSONRPCResponse) throws -> [UInt8] {
        Array(try response.encoded().serialized())
    }

    /// A wallet→dApp frame is either a response (`result`/`error`) or an event
    /// notification (`method`, no `id`). The discriminator is the presence of
    /// `method`: a response never carries one.
    enum ServerFrame {
        case response(JSONRPCResponse)
        case notification(JSONRPCRequest)
    }

    static func decodeServerFrame(_ frame: [UInt8]) throws -> ServerFrame {
        let value = try JSONValue.parse(Data(frame))
        guard let object = value.objectValue else {
            throw DappError(code: .internalError, message: "tunnel frame is not a JSON object")
        }
        if object["method"] != nil {
            return .notification(try JSONRPCRequest.decode(value))
        }
        return .response(try JSONRPCResponse.decode(value))
    }

    /// A dApp→wallet frame is always a request.
    static func decodeRequest(_ frame: [UInt8]) throws -> JSONRPCRequest {
        try JSONRPCRequest.decode(try JSONValue.parse(Data(frame)))
    }

    /// A stable string key for a JSON-RPC id, so in-flight requests can be
    /// correlated with their responses across a stream that does not promise
    /// order. The type tag keeps a string `"1"` from colliding with a number
    /// `1`.
    static func idKey(_ id: JSONValue?) -> String? {
        guard let id else { return nil }
        switch id {
        case .number(let text): return "n:\(text)"
        case .string(let text): return "s:\(text)"
        case .bool(let value): return "b:\(value)"
        case .null: return "null"
        default: return (try? id.serialized()).map { String(decoding: $0, as: UTF8.self) }
        }
    }
}

/// Identity serializer: a frame is its own bytes, no framing beyond gRPC's own.
struct ByteArraySerializer: MessageSerializer {
    func serialize<Bytes: GRPCContiguousBytes>(_ message: [UInt8]) throws -> Bytes {
        Bytes(message)
    }
}

struct ByteArrayDeserializer: MessageDeserializer {
    func deserialize<Bytes: GRPCContiguousBytes>(_ serializedMessageBytes: Bytes) throws -> [UInt8] {
        serializedMessageBytes.withUnsafeBytes { Array($0) }
    }
}

/// Raised when the tunnel is torn down with requests still in flight.
struct LanTransportClosed: Error {}
