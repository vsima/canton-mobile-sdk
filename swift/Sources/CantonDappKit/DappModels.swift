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
    /// Identifies the wallet to dApps, e.g. `io.github.vsima.canton`. Hosts
    /// override it to name themselves.
    public var id: String
    /// The wallet's version string, if it reports one.
    public var version: String?
    /// Where the wallet runs; see ``DappProviderType``. Nil when unreported.
    public var providerType: DappProviderType?
    /// The wallet's own URL, if it has one.
    public var url: String?
    /// Where a *remote* wallet kernel wants the user sent to finish an action
    /// out of band. Native wallets on this SDK leave it nil.
    public var userUrl: String?

    /// Creates a provider record; only `id` is required.
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

/// Where a wallet runs, as advertised in ``DappProvider/providerType``.
/// OpenRPC `Provider.providerType`; a wallet on this SDK is `mobile`.
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
    /// Whether the dApp is connected to the *wallet* — it holds a grant of
    /// accounts.
    public var isConnected: Bool
    /// Whether the wallet is itself connected to a *network*. Independent of
    /// ``isConnected``.
    public var isNetworkConnected: Bool
    /// Why ``isConnected`` is false, when it is.
    public var reason: String?
    /// Why ``isNetworkConnected`` is false, when it is.
    public var networkReason: String?
    /// Where a remote wallet wants the user sent to finish connecting; see
    /// ``DappProvider/userUrl``.
    public var userUrl: String?

    /// Creates a result; the reasons and `userUrl` are optional.
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
    /// CAIP-2 network id, e.g. `canton:da-mainnet`.
    public var networkId: String
    /// Base URL of the network's JSON Ledger API, if the wallet exposes one
    /// to dApps.
    public var ledgerApi: String?
    /// In the schema, but this SDK never populates it — handing a dApp a
    /// ledger token would let it bypass the wallet entirely.
    public var accessToken: String?

    /// Creates a network description from its CAIP-2 id.
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
    /// The ledger access token for the session.
    public var accessToken: String
    /// The ledger user the token authenticates.
    public var userId: String

    /// Creates a session record.
    public init(accessToken: String, userId: String) {
        self.accessToken = accessToken
        self.userId = userId
    }
}

/// One account. OpenRPC calls this `Wallet`, which is confusing in a codebase
/// where "wallet" is the application — but the wire name is what it is, and
/// renaming the type would not rename the JSON.
public struct DappWallet: Sendable, Equatable {
    /// Whether this is the wallet's primary account: what
    /// ``DappClient/getPrimaryAccount()`` returns, and the default `actAs`
    /// when a submission names none.
    public var primary: Bool
    /// The full Canton party id, `hint::namespace`.
    public var partyId: String
    /// Where the party is in its lifecycle; see ``DappWalletStatus``.
    public var status: DappWalletStatus
    /// The party hint — the human-chosen prefix of ``partyId``, before the
    /// `::`.
    public var hint: String
    /// The party's signing public key, as a string (hex-encoded DER in the
    /// shared vectors).
    public var publicKey: String
    /// The party's namespace — the fingerprint of its signing key, the part
    /// of ``partyId`` after the `::`.
    public var namespace: String
    /// CAIP-2 id of the network the party lives on.
    public var networkId: String
    /// A wallet-defined label for the signing provider that holds the key,
    /// such as `software` or `android-keystore`.
    public var signingProviderId: String
    /// OpenRPC `externalTxId`. Optional, carried through unchanged; this SDK
    /// attaches no meaning to it.
    public var externalTxId: String?
    /// OpenRPC `topologyTransactions`. An opaque string carried through
    /// unchanged (base64 in the shared vectors); this SDK never sets it.
    public var topologyTransactions: String?
    /// Whether the wallet has disabled this account; nil when unreported.
    public var disabled: Bool?
    /// Why the account is disabled or otherwise unusable, when the wallet
    /// says.
    public var reason: String?

    /// Creates an account record; the four trailing fields are optional.
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

/// Lifecycle state of an account's party — OpenRPC `Wallet.status`,
/// spelled as on the wire.
public enum DappWalletStatus: String, Sendable, CaseIterable {
    case initialized, allocated, removed
}

/// Result of `status`. OpenRPC `StatusEvent`.
public struct DappStatus: Sendable, Equatable {
    /// The wallet answering.
    public var provider: DappProvider
    /// Connection state, as `isConnected` would report it.
    public var connection: ConnectResult
    /// The active network; nil unless connected.
    public var network: DappNetwork?
    /// The ledger session. This SDK never populates it: a dApp does not get
    /// the wallet's access token.
    public var session: DappSessionInfo?

    /// Creates a status; `network` and `session` are optional.
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

/// Params of `signMessage`.
public struct SignMessageRequest: Sendable, Equatable {
    /// The text to sign. Wallets on this SDK sign it behind the
    /// ``DappSignMessage/domain`` prefix, never raw.
    public var message: String
    /// Creates a request for `message`.
    public init(message: String) { self.message = message }
}

/// Result of `signMessage`.
public struct SignMessageResult: Sendable, Equatable {
    /// The signature, in whatever encoding the wallet and its verifiers agree
    /// on — CIP-0103 fixes none.
    public var signature: String
    /// Creates a result carrying `signature`.
    public init(signature: String) { self.signature = signature }
}

/// Params of `ledgerApi` — the wallet acting as an authenticating proxy onto
/// the JSON Ledger API.
///
/// There is **no `headers` field** in OpenRPC 0.5.0. That is a feature, not
/// an omission to work around: headers are how a caller would smuggle its own
/// `Authorization`, and the point of proxying is that the wallet supplies it.
public struct LedgerApiRequest: Sendable, Equatable {
    /// HTTP method of the proxied call.
    public var requestMethod: LedgerApiMethod
    /// Path of the JSON Ledger API resource, e.g. `/v2/state/active-contracts`.
    /// Keep it a plain path: this SDK's wallet policy refuses percent-encoding
    /// and dot segments rather than normalising them.
    public var resource: String
    /// JSON request body, for methods that carry one.
    public var body: JSONValue?
    /// Query-string parameters; the wallet URL-encodes them.
    public var query: [String: JSONValue]?
    /// Path parameters. Carried per OpenRPC; this SDK's wallet forwards
    /// ``resource`` as given and does not substitute them.
    public var path: [String: JSONValue]?

    /// Creates a request; `body`, `query` and `path` are optional.
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

/// HTTP methods a `ledgerApi` call may use, lower-case as on the wire.
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
    /// JSON Ledger API commands, verbatim; the wallet proxies them to the
    /// participant unchanged.
    public var commands: [JSONValue]
    /// Caller-chosen command id for deduplication; the wallet generates one
    /// when absent.
    public var commandId: String?
    /// Parties the dApp asks to act as. A request, not a choice: the wallet
    /// resolves it against the user's grant and refuses anything outside it.
    public var actAs: [String]
    /// Extra parties to read as. Same rule as ``actAs``: only parties already
    /// granted to this dApp pass.
    public var readAs: [String]
    /// Contracts the dApp discloses for interpretation (JSON Ledger API
    /// `DisclosedContract` shapes), forwarded verbatim.
    public var disclosedContracts: [JSONValue]?
    /// The dApp's preferred synchronizer. This SDK's wallet ignores it and
    /// submits to the synchronizer it is configured with.
    public var synchronizerId: String?
    /// Package ids to prefer when the participant resolves `#package-name`
    /// references; forwarded verbatim.
    public var packageIdSelectionPreference: [String]

    /// Creates a submission; only `commands` is required.
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
    /// Base64 of the serialized `PreparedTransaction` protobuf.
    public var preparedTransaction: String?
    /// Base64 of the hash the participant asks the party to sign. A wallet
    /// recomputes it from ``preparedTransaction`` before signing.
    public var preparedTransactionHash: String?

    /// Creates a result; both fields optional, per OpenRPC.
    public init(preparedTransaction: String? = nil, preparedTransactionHash: String? = nil) {
        self.preparedTransaction = preparedTransaction
        self.preparedTransactionHash = preparedTransactionHash
    }
}

/// Lifecycle of one submission, as delivered by `txChanged` and returned in
/// its executed form by `prepareExecuteAndWait`.
public enum TxChangedEvent: Sendable, Equatable {
    /// Accepted by the wallet; nothing signed yet.
    case pending(commandId: String)
    /// Signed by the key `signedBy` (a fingerprint) for `party`; not yet
    /// executed.
    case signed(commandId: String, signature: String, signedBy: String, party: String)
    /// Committed: `updateId` names the ledger transaction and
    /// `completionOffset` its completion.
    case executed(commandId: String, updateId: String, completionOffset: Int64)
    /// Declined, rejected, or errored. Terminal.
    case failed(commandId: String)

    /// The command id every case carries, so an event can be matched to its
    /// submission.
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
    /// Approval requested; nothing signed yet.
    case pending(messageId: String)
    /// Signed; `signature` is the wallet-encoded result.
    case signed(messageId: String, signature: String)
    /// Declined or errored. Terminal.
    case failed(messageId: String)

    /// The message id every case carries, so an event can be matched to its
    /// request.
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
    /// The dApp's grant changed; the payload is the full current list, empty
    /// on disconnect.
    case accountsChanged([DappWallet])
    /// A submission moved through its lifecycle.
    case txChanged(TxChangedEvent)
    /// A `signMessage` request moved through its lifecycle.
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
