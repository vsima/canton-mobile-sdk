// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonDappKit
import Foundation

/// Binds a ``DappClient`` straight to a ``DappSession`` in the same process.
///
/// **This is not how a dApp reaches a wallet, and it is not the reference
/// example — use `LanGrpcDappTransport` (CantonDappLanKit) for that.** A real
/// dApp and a real wallet are separate apps that cross a transport; a reference
/// or an end-to-end test that ran in one process would teach the wrong shape
/// and prove nothing about the wire. This transport is deliberately for the two
/// cases where "same process" is correct:
///
/// 1. **A test harness.** Client and engine exercise the real codec, dispatch
///    and approval flow with no transport in the way, so a failure is a
///    protocol failure — fast and deterministic for engine unit tests. It is
///    *not* a wire test: it does not serialise, so it cannot catch a framing or
///    on-the-wire bug. The LAN transport tests and the live LocalNet run cover
///    that.
/// 2. **The embed pattern.** A B2B app that ships this wallet layer inside
///    itself — the "I control both ends" case — still talks to it through the
///    *standard* CIP-0103 API, so moving to an external wallet later is a
///    transport swap, not a rewrite. Here "same process" is the real
///    architecture, not a simplification of it.
///
/// Frames are passed as values without serialising to text. That is the point
/// of an in-process transport, and also why it is not a wire test; the golden
/// vectors in `testdata/dapp/` and the LAN transport cover the bytes.
public struct InProcessDappTransport: DappTransport {
    private let session: DappSession

    public init(session: DappSession) {
        self.session = session
    }

    public func send(_ request: JSONRPCRequest) async throws -> JSONRPCResponse {
        await session.handle(request)
    }

    public var events: AsyncStream<DappEvent> { session.events }
}
