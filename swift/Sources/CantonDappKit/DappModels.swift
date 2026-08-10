// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import Foundation

// The CIP-0103 dApp API types, mirroring the OpenRPC document
// `hyperledger-labs/splice-wallet-kernel` `/api-specs/openrpc-dapp-api.json`
// at `info.version` 0.5.0.
//
// Field names, optionality and enum spellings come from that document rather
// than from prose: several secondary specs for this protocol invent method
// names (`canton_connect`, `canton_getAccounts`) and fields that do not
// exist. Where any other source disagrees, the OpenRPC wins — and
// ``DappMethod`` is the exhaustive list of what exists.

/// The wallet, as advertised to a dApp. OpenRPC `Provider`.
public struct DappProvider: Sendable, Equatable {
    public var id: String
    public var version: String?
    public var providerType: DappProviderType?
    public var url: String?
    /// Where a *remote* wallet kernel wants the user sent to finish an action
    /// out of band. Native wallets on this SDK leave it nil.
    public var userUrl: String?

    public init(
        id: String,
        version: String? = nil,
        providerType: DappProviderType? = nil,
        url: String? = nil,
        userUrl: String? = nil
    ) {
        self.id = id
        self.version = version
        self.providerType = providerType
        self.url = url
        self.userUrl = userUrl
    }
}

public enum DappProviderType: String, Sendable, CaseIterable {
    case browser, desktop, mobile, remote
}

/// Result of `connect` and `isConnected`.
///
/// Two independent booleans, and they really are independent: a dApp can be
/// connected to the *wallet* while the wallet is not connected to a
/// *network*. Collapsing them into one flag is what produces "connected" UIs
/// that cannot submit anything.
public struct ConnectResult: Sendable, Equatable {
    public var isConnected: Bool
    public var isNetworkConnected: Bool
    public var reason: String?
    public var networkReason: String?
    public var userUrl: String?

    public init(
        isConnected: Bool,
        isNetworkConnected: Bool,
        reason: String? = nil,
        networkReason: String? = nil,
        userUrl: String? = nil
    ) {
        self.isConnected = isConnected
        self.isNetworkConnected = isNetworkConnected
        self.reason = reason
        self.networkReason = networkReason
        self.userUrl = userUrl
    }
}

/// OpenRPC `Network`. `networkId` is CAIP-2, e.g. `canton:da-mainnet`.
public struct DappNetwork: Sendable, Equatable {
    public var networkId: String
    public var ledgerApi: String?
    /// In the schema, but this SDK never populates it — handing a dApp a
    /// ledger token would let it bypass the wallet entirely.
    public var accessToken: String?

    public init(networkId: String, ledgerApi: String? = nil, accessToken: String? = nil) {
        self.networkId = networkId
        self.ledgerApi = ledgerApi
        self.accessToken = accessToken
    }
}

/// OpenRPC `Session`. Named `…Info` because ``DappSession`` is the
/// wallet-side engine in `CantonDappWalletKit`, and one of the two had to
/// give way.
public struct DappSessionInfo: Sendable, Equatable {
    public var accessToken: String
    public var userId: String

    public init(accessToken: String, userId: String) {
        self.accessToken = accessToken
        self.userId = userId
    }
}

/// One account. OpenRPC calls this `Wallet`, which is confusing in a codebase
/// where "wallet" is the application — but the wire name is what it is, and
/// renaming the type would not rename the JSON.
public struct DappWallet: Sendable, Equatable {
    public var primary: Bool
    public var partyId: String
    public var status: DappWalletStatus
    public var hint: String
    public var publicKey: String
    public var namespace: String
    public var networkId: String
    public var signingProviderId: String
    public var externalTxId: String?
    public var topologyTransactions: String?
    public var disabled: Bool?
    public var reason: String?

    public init(
        primary: Bool,
        partyId: String,
        status: DappWalletStatus,
        hint: String,
        publicKey: String,
        namespace: String,
        networkId: String,
        signingProviderId: String,
        externalTxId: String? = nil,
        topologyTransactions: String? = nil,
        disabled: Bool? = nil,
        reason: String? = nil
    ) {
        self.primary = primary
        self.partyId = partyId
        self.status = status
        self.hint = hint
        self.publicKey = publicKey
        self.namespace = namespace
        self.networkId = networkId
        self.signingProviderId = signingProviderId
        self.externalTxId = externalTxId
        self.topologyTransactions = topologyTransactions
        self.disabled = disabled
        self.reason = reason
    }
}

public enum DappWalletStatus: String, Sendable, CaseIterable {
    case initialized, allocated, removed
}

/// Result of `status`. OpenRPC `StatusEvent`.
public struct DappStatus: Sendable, Equatable {
    public var provider: DappProvider
    public var connection: ConnectResult
    public var network: DappNetwork?
    public var session: DappSessionInfo?

    public init(
        provider: DappProvider,
        connection: ConnectResult,
        network: DappNetwork? = nil,
        session: DappSessionInfo? = nil
    ) {
        self.provider = provider
        self.connection = connection
        self.network = network
        self.session = session
    }
}

public struct SignMessageRequest: Sendable, Equatable {
    public var message: String
    public init(message: String) { self.message = message }
}

public struct SignMessageResult: Sendable, Equatable {
    public var signature: String
    public init(signature: String) { self.signature = signature }
}

/// Params of `ledgerApi` — the wallet acting as an authenticating proxy onto
/// the JSON Ledger API.
///
/// There is **no `headers` field** in OpenRPC 0.5.0. That is a feature, not
/// an omission to work around: headers are how a caller would smuggle its own
/// `Authorization`, and the point of proxying is that the wallet supplies it.
public struct LedgerApiRequest: Sendable, Equatable {
    public var requestMethod: LedgerApiMethod
    public var resource: String
    public var body: JSONValue?
    public var query: [String: JSONValue]?
    public var path: [String: JSONValue]?

    public init(
        requestMethod: LedgerApiMethod,
        resource: String,
        body: JSONValue? = nil,
        query: [String: JSONValue]? = nil,
        path: [String: JSONValue]? = nil
    ) {
        self.requestMethod = requestMethod
        self.resource = resource
        self.body = body
        self.query = query
        self.path = path
    }
}

public enum LedgerApiMethod: String, Sendable, CaseIterable {
    case get, post, patch, put, delete
}

/// Params of `prepareExecute` and `prepareExecuteAndWait`. OpenRPC
/// `JsPrepareSubmissionRequest`.
///
/// `commands` are JSON Ledger API command shapes, carried as raw JSON and
/// deliberately not modelled further: the wallet proxies them to the
/// participant unchanged, and every Daml value shape re-encoded here would be
/// a place for the transaction the user approved to drift from the one that
/// gets signed.
///
/// The envelope is a different matter. A dApp may express a preference, but
/// the wallet decides — see ``DappSession``, which overrides `actAs` with the
/// party the user actually approved.
public struct PrepareSubmission: Sendable, Equatable {
    public var commands: [JSONValue]
    public var commandId: String?
    public var actAs: [String]
    public var readAs: [String]
    public var disclosedContracts: [JSONValue]?
    public var synchronizerId: String?
    public var packageIdSelectionPreference: [String]

    public init(
        commands: [JSONValue],
        commandId: String? = nil,
        actAs: [String] = [],
        readAs: [String] = [],
        disclosedContracts: [JSONValue]? = nil,
        synchronizerId: String? = nil,
        packageIdSelectionPreference: [String] = []
    ) {
        self.commands = commands
        self.commandId = commandId
        self.actAs = actAs
        self.readAs = readAs
        self.disclosedContracts = disclosedContracts
        self.synchronizerId = synchronizerId
        self.packageIdSelectionPreference = packageIdSelectionPreference
    }
}

/// OpenRPC `JsPrepareSubmissionResponse`. Both fields optional, both strings —
/// `preparedTransaction` is base64 of the serialized `PreparedTransaction`
/// protobuf, which is what lets a wallet recompute the hash over the exact
/// bytes the participant produced.
public struct PrepareSubmissionResult: Sendable, Equatable {
    public var preparedTransaction: String?
    public var preparedTransactionHash: String?

    public init(preparedTransaction: String? = nil, preparedTransactionHash: String? = nil) {
        self.preparedTransaction = preparedTransaction
        self.preparedTransactionHash = preparedTransactionHash
    }
}

/// Lifecycle of one submission, as delivered by `txChanged` and returned in
/// its executed form by `prepareExecuteAndWait`.
public enum TxChangedEvent: Sendable, Equatable {
    case pending(commandId: String)
    case signed(commandId: String, signature: String, signedBy: String, party: String)
    case executed(commandId: String, updateId: String, completionOffset: Int64)
    case failed(commandId: String)

    public var commandId: String {
        switch self {
        case .pending(let id), .failed(let id): return id
        case .signed(let id, _, _, _): return id
        case .executed(let id, _, _): return id
        }
    }

    var statusWire: String {
        switch self {
        case .pending: return "pending"
        case .signed: return "signed"
        case .executed: return "executed"
        case .failed: return "failed"
        }
    }
}

/// Lifecycle of one `signMessage` request, as delivered by `messageSignature`.
public enum MessageSignatureEvent: Sendable, Equatable {
    case pending(messageId: String)
    case signed(messageId: String, signature: String)
    case failed(messageId: String)

    public var messageId: String {
        switch self {
        case .pending(let id), .failed(let id): return id
        case .signed(let id, _): return id
        }
    }

    var statusWire: String {
        switch self {
        case .pending: return "pending"
        case .signed: return "signed"
        case .failed: return "failed"
        }
    }
}

/// An event pushed from wallet to dApp, as a JSON-RPC notification.
///
/// Exactly the three event methods in OpenRPC 0.5.0. Note there is **no
/// `statusChanged`** — it appears in prose descriptions of this protocol but
/// not in the document, which was checked. Status is polled via `status`.
public enum DappEvent: Sendable, Equatable {
    case accountsChanged([DappWallet])
    case txChanged(TxChangedEvent)
    case messageSignature(MessageSignatureEvent)
}

/// Every method in OpenRPC 0.5.0, request and event alike.
///
/// The event methods are here too because on the wire they are ordinary
/// JSON-RPC method names — they simply travel as notifications (no `id`) in
/// the wallet-to-dApp direction.
public enum DappMethod: String, Sendable, CaseIterable {
    case status
    case connect
    case disconnect
    case isConnected
    case getActiveNetwork
    case listAccounts
    case getPrimaryAccount
    case signMessage
    case prepareExecute
    case prepareExecuteAndWait
    case ledgerApi

    case accountsChanged
    case txChanged
    case messageSignature

    /// Whether this name is only ever sent wallet-to-dApp.
    public var isEvent: Bool {
        switch self {
        case .accountsChanged, .txChanged, .messageSignature: return true
        default: return false
        }
    }
}
