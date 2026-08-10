// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonDappKit
import Foundation

/// Binds a ``DappClient`` straight to a ``DappSession`` in the same process.
///
/// Two uses, and the second is why this ships rather than living in a test
/// target:
///
/// 1. **The acceptance harness.** Client and engine exercise the real codec,
///    the real dispatch and the real approval flow with no transport in the
///    way, so a failure is a protocol failure.
/// 2. **Embedding.** A B2B app that ships this wallet layer inside itself still
///    talks to it through the *standard* CIP-0103 API. Moving to an external
///    wallet later becomes a transport swap instead of a rewrite — which is
///    only true if the embedded path uses the same frames, and this is what
///    makes it do so.
///
/// Frames are passed as values without serialising to text. That is the point
/// of an in-process transport, but it means this path will not catch a codec
/// bug that only appears once JSON is stringified; the golden vectors in
/// `testdata/dapp/` cover that separately.
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
