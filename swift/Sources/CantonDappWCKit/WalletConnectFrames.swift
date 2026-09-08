// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonDappKit
import Foundation

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
/// `method`/`params` are the CIP-0103 JSON-RPC call it carries; `expiresAt`
/// is the envelope's expiry, when the dApp stops waiting for an answer.
public struct WcRequest: Sendable, Equatable {
    /// The WalletConnect session topic the request arrived on.
    public var topic: String
    /// The envelope id the answer is posted against; duplicates are collapsed
    /// per `(topic, requestId)`.
    public var requestId: Int64
    /// The CAIP-2 chain the dApp addressed.
    public var chainId: String
    /// The wire method, `canton_`-prefixed or bare CIP-0103.
    public var method: String
    /// The JSON-RPC params, if any.
    public var params: JSONValue?
    /// When the dApp stops waiting, if the envelope said.
    public var expiresAt: Date?

    /// Creates a request; `params` and `expiresAt` are optional.
    public init(
        topic: String,
        requestId: Int64,
        chainId: String,
        method: String,
        params: JSONValue? = nil,
        expiresAt: Date? = nil
    ) {
        self.topic = topic
        self.requestId = requestId
        self.chainId = chainId
        self.method = method
        self.params = params
        self.expiresAt = expiresAt
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
    /// CAIP-2 chain ids — normally just the adapter's.
    public var chains: [String]
    /// CAIP-10 accounts shared with the dApp.
    public var accounts: [String]
    /// Request methods the wallet will answer.
    public var methods: [String]
    /// Event names the dApp may subscribe to.
    public var events: [String]

    /// Creates a namespace set.
    public init(chains: [String], accounts: [String], methods: [String], events: [String]) {
        self.chains = chains
        self.accounts = accounts
        self.methods = methods
        self.events = events
    }
}
