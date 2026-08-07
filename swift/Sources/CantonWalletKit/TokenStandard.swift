import CantonKit
import CantonLedgerAPI
import Foundation

/// Thrown when a registry payload or interface view doesn't decode.
struct WalletDecodeError: Error, CustomStringConvertible {
    let description: String

    init(_ description: String) {
        self.description = description
    }
}

/// CIP-0056 token standard identifiers and Daml value codecs.
///
/// Interface ids use the package-name reference format (`#package-name`), so
/// they resolve against whichever package version the participant has vetted —
/// exactly what a wallet talking to arbitrary registries needs.
public enum TokenStandard {
    public static let holdingInterfaceID = interfaceID(
        packageName: "splice-api-token-holding-v1",
        module: "Splice.Api.Token.HoldingV1",
        entity: "Holding"
    )

    public static let transferInstructionInterfaceID = interfaceID(
        packageName: "splice-api-token-transfer-instruction-v1",
        module: "Splice.Api.Token.TransferInstructionV1",
        entity: "TransferInstruction"
    )

    public static let transferFactoryInterfaceID = interfaceID(
        packageName: "splice-api-token-transfer-instruction-v1",
        module: "Splice.Api.Token.TransferInstructionV1",
        entity: "TransferFactory"
    )

    private static func interfaceID(
        packageName: String,
        module: String,
        entity: String
    ) -> Com_Daml_Ledger_Api_V2_Identifier {
        var id = Com_Daml_Ledger_Api_V2_Identifier()
        id.packageID = "#\(packageName)"
        id.moduleName = module
        id.entityName = entity
        return id
    }
}

/// `Splice.Api.Token.HoldingV1.InstrumentId` — admin party + admin-unique id.
public struct InstrumentId: Sendable, Equatable {
    public let admin: String
    public let id: String

    public init(admin: String, id: String) {
        self.admin = admin
        self.id = id
    }
}

/// `Splice.Api.Token.HoldingV1.Lock`. When both expiries are set, the earlier wins.
public struct HoldingLock: Sendable, Equatable {
    public let holders: [String]
    public let expiresAt: Date?
    public let expiresAfterMicros: Int64?
    public let context: String?
}

/// A holding UTXO: one contract implementing the CIP-0056 Holding interface.
public struct Holding: Sendable, Equatable {
    public let contractId: String
    public let owner: String
    public let instrumentId: InstrumentId
    /// Daml Decimal as its canonical string — lossless, render/convert at the edge.
    public let amount: String
    public let lock: HoldingLock?
    public let meta: [String: String]
}

/// `Splice.Api.Token.TransferInstructionV1.Transfer` — the transfer specification.
public struct Transfer: Sendable, Equatable {
    public let sender: String
    public let receiver: String
    /// Daml Decimal as its canonical string (e.g. `"25.5"`).
    public let amount: String
    public let instrumentId: InstrumentId
    public let requestedAt: Date
    public let executeBefore: Date
    public let inputHoldingCids: [String]
    public let meta: [String: String]

    public init(
        sender: String,
        receiver: String,
        amount: String,
        instrumentId: InstrumentId,
        requestedAt: Date,
        executeBefore: Date,
        inputHoldingCids: [String],
        meta: [String: String] = [:]
    ) {
        self.sender = sender
        self.receiver = receiver
        self.amount = amount
        self.instrumentId = instrumentId
        self.requestedAt = requestedAt
        self.executeBefore = executeBefore
        self.inputHoldingCids = inputHoldingCids
        self.meta = meta
    }
}

public enum TransferInstructionStatus: Sendable, Equatable {
    /// Waiting for the receiver to accept or reject — the wallet-inbox state.
    case pendingReceiverAcceptance
    /// Waiting on registry-internal steps by the listed parties.
    case pendingInternalWorkflow(pendingActions: [String: String])
}

/// A pending two-step transfer (contract implementing the TransferInstruction interface).
public struct TransferInstruction: Sendable, Equatable {
    public let contractId: String
    public let originalInstructionCid: String?
    public let transfer: Transfer
    public let status: TransferInstructionStatus
    public let meta: [String: String]
}

public enum TransferInstructionChoice: Sendable {
    case accept, reject, withdraw

    var choiceName: String {
        switch self {
        case .accept: "TransferInstruction_Accept"
        case .reject: "TransferInstruction_Reject"
        case .withdraw: "TransferInstruction_Withdraw"
        }
    }

    var registryPathSegment: String {
        switch self {
        case .accept: "accept"
        case .reject: "reject"
        case .withdraw: "withdraw"
        }
    }
}

// MARK: - Decoding: interface view records -> typed values

extension Holding {
    static func fromView(contractId: String, view: Com_Daml_Ledger_Api_V2_Record) throws -> Holding {
        Holding(
            contractId: contractId,
            owner: try view.requireField("owner").asParty(),
            instrumentId: try InstrumentId(value: view.requireField("instrumentId")),
            amount: try view.requireField("amount").asNumeric(),
            lock: try view.requireField("lock").asOptional().map(HoldingLock.init(value:)),
            meta: try metadata(from: view.requireField("meta"))
        )
    }
}

extension InstrumentId {
    init(value: Com_Daml_Ledger_Api_V2_Value) throws {
        let record = try value.asRecord()
        self.init(
            admin: try record.requireField("admin").asParty(),
            id: try record.requireField("id").asText()
        )
    }
}

extension HoldingLock {
    init(value: Com_Daml_Ledger_Api_V2_Value) throws {
        let record = try value.asRecord()
        self.init(
            holders: try record.requireField("holders").asList().map { try $0.asParty() },
            expiresAt: try record.field("expiresAt")?.asOptional()?.asTimestamp(),
            expiresAfterMicros: try record.field("expiresAfter")?.asOptional()?
                .asRecord().requireField("microseconds").asInt64(),
            context: try record.field("context")?.asOptional()?.asText()
        )
    }
}

extension Transfer {
    init(value: Com_Daml_Ledger_Api_V2_Value) throws {
        let record = try value.asRecord()
        self.init(
            sender: try record.requireField("sender").asParty(),
            receiver: try record.requireField("receiver").asParty(),
            amount: try record.requireField("amount").asNumeric(),
            instrumentId: try InstrumentId(value: record.requireField("instrumentId")),
            requestedAt: try record.requireField("requestedAt").asTimestamp(),
            executeBefore: try record.requireField("executeBefore").asTimestamp(),
            inputHoldingCids: try record.requireField("inputHoldingCids").asList()
                .map { try $0.asContractId() },
            meta: try metadata(from: record.requireField("meta"))
        )
    }
}

extension TransferInstruction {
    static func fromView(
        contractId: String,
        view: Com_Daml_Ledger_Api_V2_Record
    ) throws -> TransferInstruction {
        TransferInstruction(
            contractId: contractId,
            originalInstructionCid: try view.requireField("originalInstructionCid")
                .asOptional()?.asContractId(),
            transfer: try Transfer(value: view.requireField("transfer")),
            status: try TransferInstructionStatus(value: view.requireField("status")),
            meta: try metadata(from: view.requireField("meta"))
        )
    }
}

extension TransferInstructionStatus {
    init(value: Com_Daml_Ledger_Api_V2_Value) throws {
        let variant = try value.asVariant()
        switch variant.constructor {
        case "TransferPendingReceiverAcceptance":
            self = .pendingReceiverAcceptance
        case "TransferPendingInternalWorkflow":
            var actions: [String: String] = [:]
            for entry in try variant.value.asRecord().requireField("pendingActions").genMapEntries() {
                actions[try entry.key.asParty()] = try entry.value.asText()
            }
            self = .pendingInternalWorkflow(pendingActions: actions)
        default:
            throw WalletDecodeError(
                "unknown TransferInstructionStatus constructor \(variant.constructor)"
            )
        }
    }
}

/// Decodes `Splice.Api.Token.MetadataV1.Metadata` (a record wrapping a TextMap).
func metadata(from value: Com_Daml_Ledger_Api_V2_Value) throws -> [String: String] {
    var result: [String: String] = [:]
    for entry in try value.asRecord().requireField("values").textMapEntries() {
        result[entry.key] = try entry.value.asText()
    }
    return result
}

// TextMap/GenMap readers; candidates for promotion into DamlValue alongside
// matching golden vectors.

extension Com_Daml_Ledger_Api_V2_Value {
    func textMapEntries() throws -> [Com_Daml_Ledger_Api_V2_TextMap.Entry] {
        guard case .textMap(let map)? = sum else {
            throw WalletDecodeError( "expected textMap, was \(String(describing: sum))")
        }
        return map.entries
    }

    func genMapEntries() throws -> [Com_Daml_Ledger_Api_V2_GenMap.Entry] {
        guard case .genMap(let map)? = sum else {
            throw WalletDecodeError( "expected genMap, was \(String(describing: sum))")
        }
        return map.entries
    }

    static func textMap(_ entries: [(String, Com_Daml_Ledger_Api_V2_Value)]) -> Self {
        var map = Com_Daml_Ledger_Api_V2_TextMap()
        map.entries = entries.map { key, value in
            var entry = Com_Daml_Ledger_Api_V2_TextMap.Entry()
            entry.key = key
            entry.value = value
            return entry
        }
        var result = Self()
        result.sum = .textMap(map)
        return result
    }
}

// MARK: - Encoding: typed values -> Daml values for choice arguments

extension Transfer {
    func toValue() -> Com_Daml_Ledger_Api_V2_Value {
        .record([
            "sender": .party(sender),
            "receiver": .party(receiver),
            "amount": .numeric(amount),
            "instrumentId": .record([
                "admin": .party(instrumentId.admin),
                "id": .text(instrumentId.id),
            ]),
            "requestedAt": .timestamp(requestedAt),
            "executeBefore": .timestamp(executeBefore),
            "inputHoldingCids": .list(inputHoldingCids.map { .contractId($0) }),
            "meta": metadataValue(meta),
        ])
    }
}

func metadataValue(_ meta: [String: String]) -> Com_Daml_Ledger_Api_V2_Value {
    .record(["values": .textMap(meta.sorted { $0.key < $1.key }.map { ($0.key, .text($0.value)) })])
}
