import GRPCCore
import GRPCProtobuf
import Testing
@testable import CantonKit

@Suite struct CantonErrorTests {
    @Test func decodesCantonRichErrorDetails() throws {
        let status = GoogleRPCStatus(
            code: .aborted,
            message: "CONTENTION_ON_CONTRACT: contract is locked",
            details: [
                .errorInfo(reason: "CONTENTION_ON_CONTRACT", domain: "participant"),
                .retryInfo(delay: .milliseconds(250)),
                .requestInfo(requestID: "corr-123", servingData: ""),
            ]
        )
        let rpcError = RPCError(status)

        let error = try #require(CantonError(rpcError))
        #expect(error.grpcCode == .aborted)
        #expect(error.errorCode == "CONTENTION_ON_CONTRACT")
        #expect(error.correlationId == "corr-123")
        #expect(error.isRetryable)
        #expect(error.retryDelay == .milliseconds(250))
    }

    @Test func plainGrpcErrorsDecodeWithoutDetails() throws {
        let rpcError = RPCError(code: .unavailable, message: "connection refused")

        let error = try #require(CantonError(rpcError))
        #expect(error.grpcCode == .unavailable)
        #expect(error.errorCode == nil)
        #expect(error.isRetryable) // UNAVAILABLE is transient even without RetryInfo
        #expect(error.retryDelay == nil)
    }

    @Test func nonGrpcErrorsAreNotDecoded() {
        struct Boom: Error {}
        #expect(CantonError(Boom()) == nil)
    }
}
