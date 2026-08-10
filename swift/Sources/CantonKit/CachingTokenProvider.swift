// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// Caches the token from an access-token provider until shortly before it
/// expires, so the provider is consulted once per token lifetime instead of
/// once per request.
///
/// Expiry comes from the token itself: Ledger API tokens are JWTs, and the
/// standard `exp` claim is read from the payload. A token without a
/// parseable `exp` is cached until ``refreshIfChanged(previous:)`` forces a
/// fetch — the right behaviour for non-expiring development tokens.
///
/// `refreshIfChanged` exists for the failure path: when the server rejects
/// a connection for authentication reasons, the caller fetches fresh and
/// learns whether retrying can possibly help. A provider that returns the
/// same token it returned before has nothing new to offer, and the
/// rejection is final.
public actor CachingTokenProvider {
    private let refreshLeeway: TimeInterval
    private let now: @Sendable () -> Date
    private let fetch: @Sendable () async throws -> String

    private var token: String?
    private var expiresAt: Date?
    private var inFlight: Task<String, any Error>?

    public init(
        refreshLeeway: Duration = .seconds(30),
        now: @escaping @Sendable () -> Date = { Date() },
        fetch: @escaping @Sendable () async throws -> String
    ) {
        self.refreshLeeway = Double(refreshLeeway.components.seconds) +
            Double(refreshLeeway.components.attoseconds) / 1e18
        self.now = now
        self.fetch = fetch
    }

    /// The cached token, refreshed via the provider when within the leeway
    /// of its expiry.
    public func token() async throws -> String {
        if let cached = token,
           expiresAt.map({ now() < $0.addingTimeInterval(-refreshLeeway) }) ?? true {
            return cached
        }
        return try await refresh()
    }

    /// Last token handed out, without fetching. Nil until the first
    /// ``token()`` call.
    public func cached() -> String? { token }

    /// Forces a fetch and reports whether the result differs from
    /// `previous`. `false` means the provider cannot supply anything newer —
    /// an authentication failure seen with `previous` will repeat.
    public func refreshIfChanged(previous: String?) async throws -> Bool {
        try await refresh() != previous
    }

    private func refresh() async throws -> String {
        // Single-flight: concurrent refreshes join the fetch in progress
        // rather than stampeding the provider.
        if let inFlight { return try await inFlight.value }
        let task = Task { [fetch] in try await fetch() }
        inFlight = task
        defer { inFlight = nil }
        let fresh = try await task.value
        token = fresh
        expiresAt = Self.jwtExpiry(fresh)
        return fresh
    }

    /// The `exp` claim of a JWT, or nil when `token` is not a JWT or
    /// carries none. A targeted scan rather than JSON parsing, mirroring
    /// the Kotlin SDK: `exp` is a top-level integer claim (RFC 7519).
    static func jwtExpiry(_ token: String) -> Date? {
        let segments = token.split(separator: ".")
        guard segments.count == 3 else { return nil }
        var base64 = segments[1]
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while base64.count % 4 != 0 { base64.append("=") }
        guard let data = Data(base64Encoded: base64),
              let payload = String(data: data, encoding: .utf8),
              let match = payload.firstMatch(of: /"exp"\s*:\s*(\d+)/),
              let seconds = Double(match.1)
        else { return nil }
        return Date(timeIntervalSince1970: seconds)
    }
}
