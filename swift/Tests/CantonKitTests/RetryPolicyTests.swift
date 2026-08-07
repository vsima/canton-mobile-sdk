import Testing
@testable import CantonKit

private func retryableError(delay: Duration? = .milliseconds(1)) -> CantonError {
    CantonError(
        grpcCode: .aborted,
        errorCode: "CONTENTION",
        correlationId: nil,
        isRetryable: true,
        retryDelay: delay,
        message: "contention"
    )
}

private func fatalError_() -> CantonError {
    CantonError(
        grpcCode: .invalidArgument,
        errorCode: "BAD_COMMAND",
        correlationId: nil,
        isRetryable: false,
        retryDelay: nil,
        message: "bad command"
    )
}

private actor Counter {
    var count = 0
    func increment() -> Int {
        count += 1
        return count
    }
}

@Suite struct RetryPolicyTests {
    private let fastPolicy = RetryPolicy(
        maxAttempts: 4,
        initialBackoff: .milliseconds(1),
        maxBackoff: .milliseconds(2)
    )

    @Test func retriesRetryableErrorsUntilSuccess() async throws {
        let counter = Counter()
        let result = try await withRetries(fastPolicy) {
            if await counter.increment() < 3 { throw retryableError() }
            return "ok"
        }
        #expect(result == "ok")
        #expect(await counter.count == 3)
    }

    @Test func givesUpAfterMaxAttempts() async throws {
        let counter = Counter()
        await #expect(throws: CantonError.self) {
            try await withRetries(fastPolicy) { () -> String in
                _ = await counter.increment()
                throw retryableError()
            }
        }
        #expect(await counter.count == 4)
    }

    @Test func doesNotRetryNonRetryableErrors() async throws {
        let counter = Counter()
        await #expect(throws: CantonError.self) {
            try await withRetries(fastPolicy) { () -> String in
                _ = await counter.increment()
                throw fatalError_()
            }
        }
        #expect(await counter.count == 1)
    }

    @Test func backoffIsCappedAndJittered() {
        let policy = RetryPolicy(
            maxAttempts: 10,
            initialBackoff: .milliseconds(100),
            backoffMultiplier: 2.0,
            maxBackoff: .milliseconds(300)
        )
        let backoff = policy.backoff(forAttempt: 8)
        #expect(backoff <= .milliseconds(360)) // cap 300ms + 20% jitter
        #expect(backoff >= .milliseconds(240)) // cap 300ms - 20% jitter
    }
}
