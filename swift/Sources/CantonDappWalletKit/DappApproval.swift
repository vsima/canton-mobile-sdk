// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonDappKit
import Foundation

/// Who is asking. Built by the transport from what it can actually attest —
/// a WalletConnect session's peer metadata, a LAN pairing's certificate, the
/// host app itself for the in-process case.
///
/// **Never construct this from a request payload.** A peer that names itself
/// in its own request is a peer that can name itself anything, and this is
/// what gets rendered on the approval sheet.
public struct DappPeer: Sendable, Equatable {
    public var id: String
    public var name: String
    public var url: String?
    public var iconUrl: String?
    /// Whether the transport could verify `name`/`url` cryptographically or
    /// out of band. False means the UI must say so: an unverified peer name is
    /// a claim, not an identity.
    public var verified: Bool

    public init(
        id: String,
        name: String,
        url: String? = nil,
        iconUrl: String? = nil,
        verified: Bool = false
    ) {
        self.id = id
        self.name = name
        self.url = url
        self.iconUrl = iconUrl
        self.verified = verified
    }
}

/// What the user is being asked to approve.
public enum DappApprovalRequest: Sendable {
    /// Connect, and share accounts. `available` is what the wallet could
    /// offer; the user chooses a subset.
    case connection(peer: DappPeer, network: DappNetwork, available: [DappWallet])

    /// Sign and submit a transaction.
    ///
    /// `submission` is what the dApp asked for, shown for context only: the
    /// wallet re-derives the envelope, and the transaction the user finally
    /// confirms is rendered from the *verified* prepared transaction.
    case transaction(peer: DappPeer, actAs: DappWallet, network: DappNetwork, submission: PrepareSubmission)

    /// Sign an arbitrary message with the account's key.
    case message(peer: DappPeer, signWith: DappWallet, message: String)

    public var peer: DappPeer {
        switch self {
        case .connection(let peer, _, _): return peer
        case .transaction(let peer, _, _, _): return peer
        case .message(let peer, _, _): return peer
        }
    }
}

/// The user's answer. Anything other than `approved` becomes a `4001`.
public enum DappApproval: Sendable, Equatable {
    /// Approved. For a connection request, `accounts` is the subset the user
    /// chose to share — an empty list is a rejection, not an approval of
    /// nothing.
    case approved(accounts: [DappWallet] = [])
    case rejected(reason: String = "User rejected the request")
}

/// The wallet UI, from the engine's point of view.
///
/// Implementations suspend until the user answers. There is deliberately no
/// timeout and no auto-approve: an implementation that returns `.approved`
/// without asking turns the wallet into a custodial signer for whoever holds
/// the transport, which is a different product with a different security
/// review.
public protocol DappApprovalDelegate: Sendable {
    func approve(_ request: DappApprovalRequest) async -> DappApproval
}

/// The accounts a wallet could offer a dApp.
///
/// Separate from `WalletStore` on purpose: the store holds signing identities
/// in the wallet's own terms, while this returns the CIP-0103 projection of
/// them. A host maps between the two and decides what is eligible to share.
public protocol DappAccountsSource: Sendable {
    func accounts() async throws -> [DappWallet]
}

/// The network a session operates on, and how to reach its JSON Ledger API.
public struct DappNetworkConfig: Sendable {
    /// CAIP-2 network id, e.g. `canton:da-mainnet`.
    public var networkId: String
    /// Base URL of the JSON Ledger API, e.g. `http://127.0.0.1:2975`.
    ///
    /// Configured, never hard-coded: LocalNet's 2975/3975/4975 are a compose
    /// port mapping, not a Canton default, and a real deployment puts it
    /// wherever the operator chose.
    public var jsonApiBaseUrl: String?
    /// The synchronizer the wallet submits to. Supplied by the wallet, never
    /// by a dApp.
    public var synchronizerId: String?
    /// Mints a ledger access token. Its value never reaches a dApp.
    public var accessTokenProvider: (@Sendable () async throws -> String)?

    public init(
        networkId: String,
        jsonApiBaseUrl: String? = nil,
        synchronizerId: String? = nil,
        accessTokenProvider: (@Sendable () async throws -> String)? = nil
    ) {
        self.networkId = networkId
        self.jsonApiBaseUrl = jsonApiBaseUrl
        self.synchronizerId = synchronizerId
        self.accessTokenProvider = accessTokenProvider
    }

    /// The dApp-visible view. `accessToken` is deliberately absent: handing a
    /// dApp a ledger token would let it act without the wallet, which defeats
    /// every approval in this file.
    public var dappNetwork: DappNetwork {
        DappNetwork(networkId: networkId, ledgerApi: jsonApiBaseUrl, accessToken: nil)
    }
}
