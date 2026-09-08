// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

/// Exponential backoff policy applied to retryable ledger errors
/// (see ``CantonError/isRetryable``). A server-suggested delay from
/// `RetryInfo` takes precedence over the computed backoff when it is longer.
public struct RetryPolicy: Sendable, Hashable {
    /// Total attempts including the first; 1 disables retries.
    public var maxAttempts: Int
    /// Delay before the second attempt.
    public var initialBackoff: Duration
    /// Growth factor per further attempt, before jitter.
    public var backoffMultiplier: Double
    /// Cap on the computed delay, applied before the ±20% jitter.
    public var maxBackoff: Duration
    /// A reconnecting stream's retry budget resets only after a connection
    /// has both delivered at least one update and stayed alive for this
    /// long; shorter-lived connections keep escalating the backoff and
    /// consuming the budget.
    public var streamHealthyWindow: Duration

    /// Four attempts, 250 ms doubling to a 5 s cap, a 10 s healthy window.
    public static let `default` = RetryPolicy()
    /// A single attempt: no retries.
    public static let none = RetryPolicy(maxAttempts: 1)

    /// Creates a policy; `maxAttempts` must be at least 1.
    public init(
        maxAttempts: Int = 4,
        initialBackoff: Duration = .milliseconds(250),
        backoffMultiplier: Double = 2.0,
        maxBackoff: Duration = .seconds(5),
        streamHealthyWindow: Duration = .seconds(10)
    ) {
        precondition(maxAttempts >= 1, "maxAttempts must be at least 1")
        self.maxAttempts = maxAttempts
        self.initialBackoff = initialBackoff
        self.backoffMultiplier = backoffMultiplier
        self.maxBackoff = maxBackoff
        self.streamHealthyWindow = streamHealthyWindow
    }

    func backoff(forAttempt attempt: Int) -> Duration {
        var backoff = initialBackoff
        for _ in 1..<max(attempt, 1) {
            backoff = min(backoff * backoffMultiplier, maxBackoff)
        }
        return backoff * Double.random(in: 0.8...1.2)
    }
}

/// Runs `body`, retrying ``CantonError``s that are retryable. The body is
/// responsible for using a stable command id across attempts (which
/// ``CommandSubmission`` guarantees), so retries are deduplicated server-side.
func withRetries<Result: Sendable>(
    _ policy: RetryPolicy,
    _ body: @Sendable () async throws -> Result
) async throws -> Result {
    var attempt = 1
    while true {
        do {
            return try await body()
        } catch let error as CantonError {
            guard error.isRetryable, attempt < policy.maxAttempts else { throw error }
            var delay = policy.backoff(forAttempt: attempt)
            if let suggested = error.retryDelay, suggested > delay {
                delay = suggested
            }
            try await Task.sleep(for: delay)
            attempt += 1
        }
    }
}
