// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonDappKit

// The transport-neutral shapes the WalletConnect adapter exchanges with its
// client binding.
//
// They are deliberately not WalletConnect-library types. The adapter's job is
// the Canton half — CAIP encoding and CIP-0103 frame routing — and it should
// be testable, and swappable across WalletConnect client libraries, without
// pulling one in. A Reown WalletKit delegate (in the app) maps its
// `SessionRequest`/`SessionProposal` onto these and back.

/// One inbound WalletConnect `session_request`, normalised.
///
/// `requestId` is the WalletConnect envelope id the client responds against;
/// `method`/`params` are the CIP-0103 JSON-RPC call it carries.
public struct WcRequest: Sendable, Equatable {
    public var topic: String
    public var requestId: Int64
    public var chainId: String
    public var method: String
    public var params: JSONValue?

    public init(
        topic: String,
        requestId: Int64,
        chainId: String,
        method: String,
        params: JSONValue? = nil
    ) {
        self.topic = topic
        self.requestId = requestId
        self.chainId = chainId
        self.method = method
        self.params = params
    }
}

/// The adapter's answer to a ``WcRequest`` — exactly one of success or error.
public enum WcResponse: Sendable, Equatable {
    /// The CIP-0103 result to return over the session.
    case success(result: JSONValue)
    /// A CIP-0103 / EIP-1193 error code and message to return over the session.
    case error(code: Int, message: String)
}

/// The namespaces a session is approved with: the chain(s), the CAIP-10
/// accounts shared, the methods answered, and the events emitted. A Reown
/// delegate turns this into the `Wallet.Params.SessionApprove` namespaces.
public struct WcSessionNamespaces: Sendable, Equatable {
    public var chains: [String]
    public var accounts: [String]
    public var methods: [String]
    public var events: [String]

    public init(chains: [String], accounts: [String], methods: [String], events: [String]) {
        self.chains = chains
        self.accounts = accounts
        self.methods = methods
        self.events = events
    }
}
