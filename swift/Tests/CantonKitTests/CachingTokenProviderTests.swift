// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import Foundation
import Testing

@testable import CantonKit

@Suite struct CachingTokenProviderTests {

    /// Unsigned JWT-shaped token whose payload carries the given claims.
    private func jwt(_ payload: String) -> String {
        func b64(_ s: String) -> String {
            Data(s.utf8).base64EncodedString()
                .replacingOccurrences(of: "+", with: "-")
                .replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: "")
        }
        return "\(b64(#"{"alg":"none"}"#)).\(b64(payload)).sig"
    }

    private final class Counter: @unchecked Sendable {
        private let lock = NSLock()
        private var n = 0
        func next() -> Int {
            lock.lock()
            defer { lock.unlock() }
            n += 1
            return n
        }
        var value: Int {
            lock.lock()
            defer { lock.unlock() }
            return n
        }
    }

    @Test func parsesTheExpClaim() {
        #expect(
            CachingTokenProvider.jwtExpiry(jwt(#"{"sub":"u","exp":1800000000}"#))
                == Date(timeIntervalSince1970: 1_800_000_000)
        )
    }

    @Test func noExpNotAJwtOrGarbageAllMeanNoExpiry() {
        #expect(CachingTokenProvider.jwtExpiry(jwt(#"{"sub":"u"}"#)) == nil)
        #expect(CachingTokenProvider.jwtExpiry("opaque-token") == nil)
        #expect(CachingTokenProvider.jwtExpiry("a.###not-base64###.c") == nil)
    }

    @Test func cachesAFreshToken() async throws {
        let fetches = Counter()
        let jwt = self.jwt
        let provider = CachingTokenProvider {
            _ = fetches.next()
            return jwt(#"{"exp":\#(Int(Date().timeIntervalSince1970) + 3600)}"#)
        }

        let first = try await provider.token()
        let second = try await provider.token()
        #expect(first == second)
        #expect(fetches.value == 1, "a fresh token must be served from cache")
    }

    @Test func refetchesATokenNearExpiry() async throws {
        let fetches = Counter()
        let jwt = self.jwt
        // exp 10s out is inside the 30s leeway, so every call refreshes.
        let provider = CachingTokenProvider {
            jwt(#"{"exp":\#(Int(Date().timeIntervalSince1970) + 10),"n":\#(fetches.next())}"#)
        }

        _ = try await provider.token()
        _ = try await provider.token()
        #expect(fetches.value == 2, "a token near expiry must be refetched")
    }

    @Test func aTokenWithoutExpIsCachedIndefinitely() async throws {
        let fetches = Counter()
        let provider = CachingTokenProvider {
            _ = fetches.next()
            return "opaque"
        }
        for _ in 0..<50 { _ = try await provider.token() }
        #expect(fetches.value == 1)
    }

    @Test func refreshIfChangedReportsWhetherARetryCanHelp() async throws {
        let fetches = Counter()
        let provider = CachingTokenProvider {
            ["t1", "t2", "t2"][fetches.next() - 1]
        }

        #expect(try await provider.token() == "t1")
        #expect(try await provider.refreshIfChanged(previous: "t1"),
                "a different token is worth a retry")
        #expect(await provider.cached() == "t2")
        #expect(!(try await provider.refreshIfChanged(previous: "t2")),
                "the same token again means the failure is final")
    }
}
