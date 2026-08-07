import CantonLedgerAPI
import GRPCCore
import GRPCNIOTransportHTTP2

/// A client for the Canton Ledger API.
///
/// `CantonClient` owns connection configuration and exposes scoped access to
/// the generated Ledger API service clients:
///
/// ```swift
/// let client = CantonClient(
///     configuration: .init(host: "validator.example.com", useTLS: true)
/// )
/// let version = try await client.ledgerApiVersion()
/// ```
public struct CantonClient: Sendable {
    public typealias Transport = HTTP2ClientTransport.Posix

    public let configuration: CantonClientConfiguration

    public init(configuration: CantonClientConfiguration) {
        self.configuration = configuration
    }

    /// Ledger API service clients bound to an open connection.
    public struct Services: Sendable {
        /// The underlying gRPC client; wrap any generated service client
        /// around this for APIs not yet surfaced by CantonKit.
        public let grpc: GRPCClient<Transport>

        public var version: Com_Daml_Ledger_Api_V2_VersionService.Client<Transport> {
            .init(wrapping: grpc)
        }

        public var command: Com_Daml_Ledger_Api_V2_CommandService.Client<Transport> {
            .init(wrapping: grpc)
        }

        public var state: Com_Daml_Ledger_Api_V2_StateService.Client<Transport> {
            .init(wrapping: grpc)
        }

        public var update: Com_Daml_Ledger_Api_V2_UpdateService.Client<Transport> {
            .init(wrapping: grpc)
        }
    }

    /// Opens a connection, runs `body` against the Ledger API services, and
    /// shuts the connection down when `body` returns.
    public func withServices<Result: Sendable>(
        _ body: @Sendable (Services) async throws -> Result
    ) async throws -> Result {
        var interceptors: [any ClientInterceptor] = []
        if let tokenProvider = configuration.accessTokenProvider {
            interceptors.append(BearerTokenInterceptor(tokenProvider: tokenProvider))
        }
        return try await withGRPCClient(
            transport: try .http2NIOPosix(
                target: .dns(host: configuration.host, port: configuration.port),
                transportSecurity: configuration.useTLS ? .tls : .plaintext
            ),
            interceptors: interceptors
        ) { grpc in
            try await body(Services(grpc: grpc))
        }
    }

    /// Convenience: fetches the Ledger API version from the participant.
    ///
    /// - Throws: ``CantonError`` if the call fails with a gRPC error.
    public func ledgerApiVersion() async throws -> String {
        try await withRetries(configuration.retryPolicy) {
            try await mapCantonErrors {
                try await withServices { services in
                    try await services.version.getLedgerApiVersion(.init()).version
                }
            }
        }
    }

    /// Submits `submission` and waits for it to be committed, returning the
    /// update id. Retryable failures are retried with the same command id,
    /// so the participant deduplicates re-executions.
    ///
    /// - Throws: ``CantonError`` if the submission ultimately fails.
    public func submitAndWait(_ submission: CommandSubmission) async throws -> String {
        try await withRetries(configuration.retryPolicy) {
            try await mapCantonErrors {
                try await withServices { services in
                    var request = Com_Daml_Ledger_Api_V2_SubmitAndWaitRequest()
                    request.commands = submission.proto
                    return try await services.command.submitAndWait(request).updateID
                }
            }
        }
    }

    /// The participant's current ledger end offset — the natural
    /// ``UpdateSubscription/beginExclusive`` for a fresh subscription.
    ///
    /// - Throws: ``CantonError`` if the call fails with a gRPC error.
    public func ledgerEnd() async throws -> Int64 {
        try await withRetries(configuration.retryPolicy) {
            try await mapCantonErrors {
                try await withServices { services in
                    try await services.state.getLedgerEnd(.init()).offset
                }
            }
        }
    }

    /// Streams ledger updates for `subscription`, transparently reconnecting
    /// on retryable failures and resuming from the offset of the last
    /// received update — consumers see one uninterrupted, gap-free stream.
    /// The retry budget resets whenever an update is received.
    ///
    /// The stream finishes normally when the server ends it (only for
    /// subscriptions with ``UpdateSubscription/endInclusive`` set) and throws
    /// ``CantonError`` on non-retryable failures.
    public func updates(_ subscription: UpdateSubscription) -> AsyncThrowingStream<LedgerUpdate, any Error> {
        let policy = configuration.retryPolicy

        func retryDelay(for error: any Error, attempt: Int) -> Duration? {
            guard let canton = CantonError(error), canton.isRetryable, attempt < policy.maxAttempts else {
                return nil
            }
            var delay = policy.backoff(forAttempt: attempt)
            if let suggested = canton.retryDelay, suggested > delay {
                delay = suggested
            }
            return delay
        }

        return AsyncThrowingStream { continuation in
            let task = Task {
                var cursor = subscription.beginExclusive
                var attempt = 1
                while !Task.isCancelled {
                    let begin = cursor
                    do {
                        let (last, progressed, streamError) = try await self.withServices { services in
                            try await services.update.getUpdates(subscription.request(from: begin)) { response in
                                var last = begin
                                var progressed = false
                                do {
                                    for try await message in response.messages {
                                        guard let update = LedgerUpdate(message) else { continue }
                                        last = update.offset
                                        progressed = true
                                        continuation.yield(update)
                                    }
                                } catch {
                                    return (last, progressed, error as (any Error)?)
                                }
                                return (last, progressed, nil)
                            }
                        }
                        cursor = last
                        if progressed { attempt = 1 }
                        guard let streamError else {
                            continuation.finish() // server completed (finite subscription)
                            return
                        }
                        guard !(streamError is CancellationError),
                              let delay = retryDelay(for: streamError, attempt: attempt)
                        else {
                            continuation.finish(throwing: CantonError(streamError) ?? streamError)
                            return
                        }
                        try await Task.sleep(for: delay)
                        attempt += 1
                    } catch is CancellationError {
                        break
                    } catch {
                        guard let delay = retryDelay(for: error, attempt: attempt) else {
                            continuation.finish(throwing: CantonError(error) ?? error)
                            return
                        }
                        do {
                            try await Task.sleep(for: delay)
                        } catch {
                            break
                        }
                        attempt += 1
                    }
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// Submits `submission`, waits for it to be committed, and returns the
    /// resulting transaction (flat/ACS-delta shape, filtered to the
    /// submitting parties). Retries reuse the same command id.
    ///
    /// - Throws: ``CantonError`` if the submission ultimately fails.
    public func submitAndWaitForTransaction(
        _ submission: CommandSubmission
    ) async throws -> Com_Daml_Ledger_Api_V2_Transaction {
        try await withRetries(configuration.retryPolicy) {
            try await mapCantonErrors {
                try await withServices { services in
                    var request = Com_Daml_Ledger_Api_V2_SubmitAndWaitForTransactionRequest()
                    request.commands = submission.proto
                    return try await services.command.submitAndWaitForTransaction(request).transaction
                }
            }
        }
    }
}

/// Injects `authorization: Bearer <token>` into every request, fetching the
/// token from the configured provider.
struct BearerTokenInterceptor: ClientInterceptor {
    let tokenProvider: @Sendable () async throws -> String

    func intercept<Input: Sendable, Output: Sendable>(
        request: StreamingClientRequest<Input>,
        context: ClientContext,
        next: (StreamingClientRequest<Input>, ClientContext) async throws -> StreamingClientResponse<Output>
    ) async throws -> StreamingClientResponse<Output> {
        var request = request
        let token = try await tokenProvider()
        request.metadata.replaceOrAddString("Bearer \(token)", forKey: "authorization")
        return try await next(request, context)
    }
}
