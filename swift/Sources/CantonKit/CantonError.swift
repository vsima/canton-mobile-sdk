import GRPCCore
import GRPCProtobuf

/// A decoded Canton Ledger API error.
///
/// Canton attaches structured `google.rpc` error details to failed RPCs:
/// `ErrorInfo` carries the Canton error code (e.g. `CONTRACT_NOT_FOUND`,
/// `NOT_CONNECTED_TO_ANY_SYNCHRONIZER`), `RetryInfo` marks retryable errors
/// with a server-suggested backoff, and `RequestInfo` carries the correlation
/// id to quote when filing support requests against a validator operator.
public struct CantonError: Error, Sendable, Hashable {
    /// The gRPC status code of the failed call.
    public let grpcCode: RPCError.Code

    /// Canton's self-service error code from `ErrorInfo.reason`, if present.
    public let errorCode: String?

    /// Correlation id for tracing the error on the participant, if present.
    public let correlationId: String?

    /// Whether the caller should retry (RetryInfo present, or a transient gRPC code).
    public let isRetryable: Bool

    /// Server-suggested minimum backoff before retrying, if provided.
    public let retryDelay: Duration?

    /// Human-readable description from the server.
    public let message: String

    init(
        grpcCode: RPCError.Code,
        errorCode: String?,
        correlationId: String?,
        isRetryable: Bool,
        retryDelay: Duration?,
        message: String
    ) {
        self.grpcCode = grpcCode
        self.errorCode = errorCode
        self.correlationId = correlationId
        self.isRetryable = isRetryable
        self.retryDelay = retryDelay
        self.message = message
    }

    /// Decodes a `CantonError` from a gRPC failure, or returns nil if
    /// `error` is not a gRPC status error.
    public init?(_ error: any Error) {
        guard let rpcError = error as? RPCError else { return nil }

        var errorCode: String? = nil
        var correlationId: String? = nil
        var retryDelay: Duration? = nil

        if let status = try? rpcError.unpackGoogleRPCStatus() {
            for detail in status.details {
                if let info = detail.errorInfo { errorCode = info.reason }
                if let info = detail.retryInfo { retryDelay = info.delay }
                if let info = detail.requestInfo { correlationId = info.requestID }
            }
        }

        self.grpcCode = rpcError.code
        self.errorCode = errorCode
        self.correlationId = correlationId
        self.isRetryable = retryDelay != nil || rpcError.code == .unavailable
        self.retryDelay = retryDelay
        self.message = rpcError.message
    }
}

extension CantonError: CustomStringConvertible {
    public var description: String {
        var text = "\(grpcCode)"
        if let errorCode { text += "/\(errorCode)" }
        text += ": \(message)"
        if let correlationId { text += " (correlation id: \(correlationId))" }
        return text
    }
}

/// Runs `body`, rethrowing gRPC failures as ``CantonError``.
func mapCantonErrors<Result: Sendable>(
    _ body: @Sendable () async throws -> Result
) async throws -> Result {
    do {
        return try await body()
    } catch {
        throw CantonError(error) ?? error
    }
}
