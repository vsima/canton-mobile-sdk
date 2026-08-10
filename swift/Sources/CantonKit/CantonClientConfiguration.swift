// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

/// Configuration for connecting to a Canton participant's Ledger API.
public struct CantonClientConfiguration: Sendable {
    /// Hostname of the participant node exposing the gRPC Ledger API.
    public var host: String

    /// Ledger API port. Canton's conventional default is 6865.
    public var port: Int

    /// Whether to use TLS for the connection. Only disable this for local
    /// development against a sandbox.
    public var useTLS: Bool

    /// Called before requests to produce a JWT access token for the
    /// Ledger API's `authorization: Bearer <token>` header. Return `nil`
    /// provider for unauthenticated (development) ledgers.
    public var accessTokenProvider: (@Sendable () async throws -> String)?

    /// Which certificates to trust when ``useTLS`` is on. Defaults to the
    /// platform trust store; pin an operator's CA to reject interception by
    /// a certificate authority the device happens to trust.
    public var tlsTrust: TLSTrust

    /// Backoff applied to retryable ledger errors.
    public var retryPolicy: RetryPolicy

    public init(
        host: String,
        port: Int = 6865,
        useTLS: Bool = true,
        accessTokenProvider: (@Sendable () async throws -> String)? = nil,
        tlsTrust: TLSTrust = TLSTrust(),
        retryPolicy: RetryPolicy = .default
    ) {
        self.host = host
        self.port = port
        self.useTLS = useTLS
        self.accessTokenProvider = accessTokenProvider
        self.tlsTrust = tlsTrust
        self.retryPolicy = retryPolicy
    }
}
