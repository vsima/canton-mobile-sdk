// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// Wire codec for the CIP-0103 types, written against OpenRPC 0.5.0.
///
/// Encoding rule, applied throughout: **omit absent optionals, never emit
/// null for them.** A wallet that emits `"reason": null` is not wrong by
/// JSON-RPC, but round-tripping it changes the document, and the shared
/// vectors in `testdata/dapp/vectors.json` assert that decode-then-encode is
/// a fixpoint. Decoding is the tolerant direction — an explicit null reads as
/// absent, which is what keeps this interoperable with wallets that emit them.
public enum DappJSON {

    // ── Provider / status ──────────────────────────────────────────────

    public static func encode(_ value: DappProvider) -> JSONValue {
        var object: [String: JSONValue] = ["id": .string(value.id)]
        object.put("version", value.version)
        object.put("providerType", value.providerType?.rawValue)
        object.put("url", value.url)
        object.put("userUrl", value.userUrl)
        return .object(object)
    }

    public static func decodeProvider(_ json: JSONValue) throws -> DappProvider {
        let object = try json.requireObject("Provider")
        return DappProvider(
            id: try object.string("id"),
            version: object.stringOrNil("version"),
            providerType: try object.enumOrNil("providerType", DappProviderType.self),
            url: object.stringOrNil("url"),
            userUrl: object.stringOrNil("userUrl")
        )
    }

    public static func encode(_ value: ConnectResult) -> JSONValue {
        var object: [String: JSONValue] = ["isConnected": .bool(value.isConnected)]
        object.put("reason", value.reason)
        object["isNetworkConnected"] = .bool(value.isNetworkConnected)
        object.put("networkReason", value.networkReason)
        object.put("userUrl", value.userUrl)
        return .object(object)
    }

    public static func decodeConnectResult(_ json: JSONValue) throws -> ConnectResult {
        let object = try json.requireObject("ConnectResult")
        return ConnectResult(
            isConnected: try object.bool("isConnected"),
            isNetworkConnected: try object.bool("isNetworkConnected"),
            reason: object.stringOrNil("reason"),
            networkReason: object.stringOrNil("networkReason"),
            userUrl: object.stringOrNil("userUrl")
        )
    }

    public static func encode(_ value: DappNetwork) -> JSONValue {
        var object: [String: JSONValue] = ["networkId": .string(value.networkId)]
        object.put("ledgerApi", value.ledgerApi)
        object.put("accessToken", value.accessToken)
        return .object(object)
    }

    public static func decodeNetwork(_ json: JSONValue) throws -> DappNetwork {
        let object = try json.requireObject("Network")
        return DappNetwork(
            networkId: try object.string("networkId"),
            ledgerApi: object.stringOrNil("ledgerApi"),
            accessToken: object.stringOrNil("accessToken")
        )
    }

    public static func encode(_ value: DappSessionInfo) -> JSONValue {
        .object(["accessToken": .string(value.accessToken), "userId": .string(value.userId)])
    }

    public static func decodeSessionInfo(_ json: JSONValue) throws -> DappSessionInfo {
        let object = try json.requireObject("Session")
        return DappSessionInfo(
            accessToken: try object.string("accessToken"),
            userId: try object.string("userId")
        )
    }

    public static func encode(_ value: DappStatus) -> JSONValue {
        var object: [String: JSONValue] = [
            "provider": encode(value.provider),
            "connection": encode(value.connection),
        ]
        if let network = value.network { object["network"] = encode(network) }
        if let session = value.session { object["session"] = encode(session) }
        return .object(object)
    }

    public static func decodeStatus(_ json: JSONValue) throws -> DappStatus {
        let object = try json.requireObject("StatusEvent")
        return DappStatus(
            provider: try decodeProvider(try object.required("provider")),
            connection: try decodeConnectResult(try object.required("connection")),
            network: try object.present("network").map { try decodeNetwork($0) },
            session: try object.present("session").map { try decodeSessionInfo($0) }
        )
    }

    // ── Accounts ───────────────────────────────────────────────────────

    public static func encode(_ value: DappWallet) -> JSONValue {
        var object: [String: JSONValue] = [
            "primary": .bool(value.primary),
            "partyId": .string(value.partyId),
            "status": .string(value.status.rawValue),
            "hint": .string(value.hint),
            "publicKey": .string(value.publicKey),
            "namespace": .string(value.namespace),
            "networkId": .string(value.networkId),
            "signingProviderId": .string(value.signingProviderId),
        ]
        object.put("externalTxId", value.externalTxId)
        object.put("topologyTransactions", value.topologyTransactions)
        if let disabled = value.disabled { object["disabled"] = .bool(disabled) }
        object.put("reason", value.reason)
        return .object(object)
    }

    public static func decodeWallet(_ json: JSONValue) throws -> DappWallet {
        let object = try json.requireObject("Wallet")
        guard let status = try object.enumOrNil("status", DappWalletStatus.self) else {
            throw DappError(code: .invalidParams, message: "missing required string field 'status'")
        }
        return DappWallet(
            primary: try object.bool("primary"),
            partyId: try object.string("partyId"),
            status: status,
            hint: try object.string("hint"),
            publicKey: try object.string("publicKey"),
            namespace: try object.string("namespace"),
            networkId: try object.string("networkId"),
            signingProviderId: try object.string("signingProviderId"),
            externalTxId: object.stringOrNil("externalTxId"),
            topologyTransactions: object.stringOrNil("topologyTransactions"),
            disabled: object["disabled"].flatMap { $0.boolValue },
            reason: object.stringOrNil("reason")
        )
    }

    public static func encodeAccounts(_ value: [DappWallet]) -> JSONValue {
        .array(value.map { encode($0) })
    }

    public static func decodeAccounts(_ json: JSONValue) throws -> [DappWallet] {
        guard let array = json.arrayValue else {
            throw DappError(code: .invalidParams, message: "ListAccountsResult must be a JSON array")
        }
        return try array.map { try decodeWallet($0) }
    }

    // ── signMessage ────────────────────────────────────────────────────

    public static func encode(_ value: SignMessageRequest) -> JSONValue {
        .object(["message": .string(value.message)])
    }

    public static func decodeSignMessageRequest(_ json: JSONValue) throws -> SignMessageRequest {
        SignMessageRequest(message: try json.requireObject("SignMessageRequest").string("message"))
    }

    public static func encode(_ value: SignMessageResult) -> JSONValue {
        .object(["signature": .string(value.signature)])
    }

    public static func decodeSignMessageResult(_ json: JSONValue) throws -> SignMessageResult {
        SignMessageResult(signature: try json.requireObject("SignMessageResult").string("signature"))
    }

    // ── ledgerApi ──────────────────────────────────────────────────────

    public static func encode(_ value: LedgerApiRequest) -> JSONValue {
        var object: [String: JSONValue] = [
            "requestMethod": .string(value.requestMethod.rawValue),
            "resource": .string(value.resource),
        ]
        if let body = value.body { object["body"] = body }
        if let query = value.query { object["query"] = .object(query) }
        if let path = value.path { object["path"] = .object(path) }
        return .object(object)
    }

    public static func decodeLedgerApiRequest(_ json: JSONValue) throws -> LedgerApiRequest {
        let object = try json.requireObject("LedgerApiRequest")
        guard let method = try object.enumOrNil("requestMethod", LedgerApiMethod.self) else {
            throw DappError(code: .invalidParams, message: "missing required field 'requestMethod'")
        }
        return LedgerApiRequest(
            requestMethod: method,
            resource: try object.string("resource"),
            body: try object.present("body"),
            query: try object.present("query")?.requireObject("query"),
            path: try object.present("path")?.requireObject("path")
        )
    }

    // ── prepareExecute ─────────────────────────────────────────────────

    public static func encode(_ value: PrepareSubmission) -> JSONValue {
        var object: [String: JSONValue] = [:]
        object.put("commandId", value.commandId)
        object["commands"] = .array(value.commands)
        if !value.actAs.isEmpty { object["actAs"] = .array(value.actAs.map { .string($0) }) }
        if !value.readAs.isEmpty { object["readAs"] = .array(value.readAs.map { .string($0) }) }
        if let disclosed = value.disclosedContracts { object["disclosedContracts"] = .array(disclosed) }
        object.put("synchronizerId", value.synchronizerId)
        if !value.packageIdSelectionPreference.isEmpty {
            object["packageIdSelectionPreference"] =
                .array(value.packageIdSelectionPreference.map { .string($0) })
        }
        return .object(object)
    }

    public static func decodePrepareSubmission(_ json: JSONValue) throws -> PrepareSubmission {
        let object = try json.requireObject("JsPrepareSubmissionRequest")
        guard let commands = try object.required("commands").arrayValue else {
            throw DappError(code: .invalidParams, message: "'commands' must be a JSON array")
        }
        return PrepareSubmission(
            commands: commands,
            commandId: object.stringOrNil("commandId"),
            actAs: try object.stringList("actAs"),
            readAs: try object.stringList("readAs"),
            disclosedContracts: try object.present("disclosedContracts")?.arrayValue,
            synchronizerId: object.stringOrNil("synchronizerId"),
            packageIdSelectionPreference: try object.stringList("packageIdSelectionPreference")
        )
    }

    public static func encode(_ value: PrepareSubmissionResult) -> JSONValue {
        var object: [String: JSONValue] = [:]
        object.put("preparedTransaction", value.preparedTransaction)
        object.put("preparedTransactionHash", value.preparedTransactionHash)
        return .object(object)
    }

    public static func decodePrepareSubmissionResult(_ json: JSONValue) throws -> PrepareSubmissionResult {
        let object = try json.requireObject("JsPrepareSubmissionResponse")
        return PrepareSubmissionResult(
            preparedTransaction: object.stringOrNil("preparedTransaction"),
            preparedTransactionHash: object.stringOrNil("preparedTransactionHash")
        )
    }

    /// `prepareExecuteAndWaitResult` — an executed `txChanged` under `tx`.
    public static func encodeExecutedResult(_ value: TxChangedEvent) -> JSONValue {
        .object(["tx": encode(value)])
    }

    public static func decodeExecutedResult(_ json: JSONValue) throws -> TxChangedEvent {
        let object = try json.requireObject("prepareExecuteAndWaitResult")
        let tx = try decodeTxChanged(try object.required("tx"))
        guard case .executed = tx else {
            throw DappError(
                code: .invalidParams,
                message: "prepareExecuteAndWait returned a '\(tx.statusWire)' tx, expected 'executed'"
            )
        }
        return tx
    }

    // ── Events ─────────────────────────────────────────────────────────

    public static func encode(_ value: TxChangedEvent) -> JSONValue {
        var object: [String: JSONValue] = [
            "status": .string(value.statusWire),
            "commandId": .string(value.commandId),
        ]
        switch value {
        case .pending, .failed:
            break
        case .signed(_, let signature, let signedBy, let party):
            object["payload"] = .object([
                "signature": .string(signature),
                "signedBy": .string(signedBy),
                "party": .string(party),
            ])
        case .executed(_, let updateId, let completionOffset):
            object["payload"] = .object([
                "updateId": .string(updateId),
                "completionOffset": .int(completionOffset),
            ])
        }
        return .object(object)
    }

    public static func decodeTxChanged(_ json: JSONValue) throws -> TxChangedEvent {
        let object = try json.requireObject("TxChangedEvent")
        let commandId = try object.string("commandId")
        switch try object.string("status") {
        case "pending":
            return .pending(commandId: commandId)
        case "failed":
            return .failed(commandId: commandId)
        case "signed":
            let payload = try object.required("payload").requireObject("TxChangedSignedPayload")
            return .signed(
                commandId: commandId,
                signature: try payload.string("signature"),
                signedBy: try payload.string("signedBy"),
                party: try payload.string("party")
            )
        case "executed":
            let payload = try object.required("payload").requireObject("TxChangedExecutedPayload")
            return .executed(
                commandId: commandId,
                updateId: try payload.string("updateId"),
                completionOffset: try payload.int64("completionOffset")
            )
        case let other:
            throw DappError(code: .invalidParams, message: "unknown txChanged status '\(other)'")
        }
    }

    public static func encode(_ value: MessageSignatureEvent) -> JSONValue {
        var object: [String: JSONValue] = [
            "status": .string(value.statusWire),
            "messageId": .string(value.messageId),
        ]
        if case .signed(_, let signature) = value { object["signature"] = .string(signature) }
        return .object(object)
    }

    public static func decodeMessageSignature(_ json: JSONValue) throws -> MessageSignatureEvent {
        let object = try json.requireObject("MessageSignatureEvent")
        let messageId = try object.string("messageId")
        switch try object.string("status") {
        case "pending": return .pending(messageId: messageId)
        case "failed": return .failed(messageId: messageId)
        case "signed": return .signed(messageId: messageId, signature: try object.string("signature"))
        case let other:
            throw DappError(code: .invalidParams, message: "unknown messageSignature status '\(other)'")
        }
    }

    /// A wallet-to-dApp event as a JSON-RPC notification. The event name is
    /// the notification's `method`, and its payload is `params`.
    public static func encodeEvent(_ event: DappEvent) -> JSONRPCRequest {
        switch event {
        case .accountsChanged(let accounts):
            return JSONRPCRequest(method: DappMethod.accountsChanged.rawValue, params: encodeAccounts(accounts))
        case .txChanged(let tx):
            return JSONRPCRequest(method: DappMethod.txChanged.rawValue, params: encode(tx))
        case .messageSignature(let signature):
            return JSONRPCRequest(method: DappMethod.messageSignature.rawValue, params: encode(signature))
        }
    }

    /// Decodes a notification into a ``DappEvent``, or nil when it names a
    /// method that is not an event. Nil rather than a throw: an unknown
    /// notification is something to ignore, not to fail a session over.
    public static func decodeEvent(_ notification: JSONRPCRequest) throws -> DappEvent? {
        guard let params = notification.params,
              let method = DappMethod(rawValue: notification.method)
        else { return nil }
        switch method {
        case .accountsChanged: return .accountsChanged(try decodeAccounts(params))
        case .txChanged: return .txChanged(try decodeTxChanged(params))
        case .messageSignature: return .messageSignature(try decodeMessageSignature(params))
        default: return nil
        }
    }
}

// ── Decoding helpers ───────────────────────────────────────────────────
//
// Every failure is invalidParams carrying the field name: a dApp debugging a
// rejected call needs to know which field, and "expected object" alone has
// cost enough time elsewhere in this codebase.

extension [String: JSONValue] {
    /// Present and not JSON null.
    func present(_ name: String) throws -> JSONValue? {
        guard let value = self[name], !value.isNull else { return nil }
        return value
    }

    func required(_ name: String) throws -> JSONValue {
        guard let value = try present(name) else {
            throw DappError(code: .invalidParams, message: "missing required field '\(name)'")
        }
        return value
    }

    func stringOrNil(_ name: String) -> String? {
        guard let value = self[name], !value.isNull else { return nil }
        return value.stringValue
    }

    func string(_ name: String) throws -> String {
        guard let value = stringOrNil(name) else {
            throw DappError(code: .invalidParams, message: "missing required string field '\(name)'")
        }
        return value
    }

    func bool(_ name: String) throws -> Bool {
        guard let value = self[name]?.boolValue else {
            throw DappError(code: .invalidParams, message: "missing required boolean field '\(name)'")
        }
        return value
    }

    func int64(_ name: String) throws -> Int64 {
        guard let value = self[name]?.int64Value else {
            throw DappError(code: .invalidParams, message: "field '\(name)' must be an integer")
        }
        return value
    }

    func stringList(_ name: String) throws -> [String] {
        guard let array = try present(name)?.arrayValue else { return [] }
        return try array.map {
            guard let text = $0.stringValue else {
                throw DappError(code: .invalidParams, message: "'\(name)' must contain only strings")
            }
            return text
        }
    }

    func enumOrNil<T: RawRepresentable>(_ name: String, _ type: T.Type) throws -> T?
    where T.RawValue == String {
        guard let raw = stringOrNil(name) else { return nil }
        guard let value = T(rawValue: raw) else {
            throw DappError(code: .invalidParams, message: "unknown \(name) '\(raw)'")
        }
        return value
    }

    mutating func put(_ name: String, _ value: String?) {
        if let value { self[name] = .string(value) }
    }
}
