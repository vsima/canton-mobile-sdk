// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonLedgerAPI
import GRPCCore
import GRPCProtobuf
import SwiftProtobuf
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

    /// Completion events carry the rejection as a raw `google.rpc.Status`.
    @Test func decodesCantonRichErrorDetailsFromACompletionStatusProto() throws {
        var errorInfo = Google_Rpc_ErrorInfo()
        errorInfo.reason = "CONTENTION_ON_CONTRACT"
        errorInfo.domain = "participant"

        var retryInfo = Google_Rpc_RetryInfo()
        retryInfo.retryDelay.nanos = 250_000_000

        var requestInfo = Google_Rpc_RequestInfo()
        requestInfo.requestID = "corr-123"

        var status = Google_Rpc_Status()
        status.code = 10  // ABORTED
        status.message = "CONTENTION_ON_CONTRACT: contract is locked"
        status.details = [
            try Google_Protobuf_Any(message: errorInfo),
            try Google_Protobuf_Any(message: retryInfo),
            try Google_Protobuf_Any(message: requestInfo),
        ]

        let error = CantonError(completionStatus: status)
        #expect(error.grpcCode == .aborted)
        #expect(error.errorCode == "CONTENTION_ON_CONTRACT")
        #expect(error.correlationId == "corr-123")
        #expect(error.isRetryable)
        #expect(error.retryDelay == .milliseconds(250))
        #expect(error.message == "CONTENTION_ON_CONTRACT: contract is locked")
    }
}
