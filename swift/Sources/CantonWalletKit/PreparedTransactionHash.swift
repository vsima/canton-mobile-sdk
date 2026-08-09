// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonLedgerAPI
import CryptoKit
import Foundation

/// The PreparedTransaction cannot be hashed (or verified) by this client —
/// unknown hashing scheme, or features hashing scheme V2 cannot encode.
public struct PreparedTransactionHashError: Error, Sendable, CustomStringConvertible {
    public let description: String

    init(_ description: String) {
        self.description = description
    }
}

/// The locally recomputed hash does not match what the node asked us to sign.
public struct PreparedTransactionHashMismatchError: Error, Sendable, CustomStringConvertible {
    /// Hex of the hash the preparing node returned.
    public let nodeHashHex: String
    /// Hex of the hash recomputed locally from the PreparedTransaction proto.
    public let computedHashHex: String

    public var description: String {
        "prepared_transaction_hash mismatch: the node returned \(nodeHashHex) but the " +
            "PreparedTransaction re-hashes to \(computedHashHex). Refusing to sign — the " +
            "preparing participant may be malicious or running an incompatible version."
    }
}

/// Client-side implementation of Canton's "hashing scheme version 2" for
/// interactive submissions: recomputes `prepared_transaction_hash` from the
/// raw `PreparedTransaction` proto so a wallet never signs a hash it did not
/// derive itself (interactive_submission_service.proto: "clients MUST
/// recompute the hash from the raw transaction if the preparing participant
/// is not trusted").
///
/// The byte layout follows Canton's reference implementation
/// (`com.digitalasset.canton.protocol.hash`, scheme V2) and Digital Asset's
/// TypeScript wallet SDK (`@canton-network/wallet-sdk` hash encoders). The
/// full byte-level spec with citations lives in docs/prepared-tx-hash.md,
/// and the Kotlin SDK's `PreparedTransactionHash` is the sibling
/// implementation — both are held to the same golden vectors in
/// `testdata/preparedtx/`.
///
/// In short: `SHA256(purpose ++ 0x02 ++ txHash ++ metadataHash)` where
/// `purpose = int32(48)` (PreparedSubmission), `txHash` covers the node
/// forest as recursively hashed subtrees, and `metadataHash` covers the
/// signed metadata (act_as, command id, transaction UUID, mediator group,
/// synchronizer, time bounds, preparation time, input contracts). All
/// integers are fixed-length big-endian; strings and byte strings are
/// int32-length-prefixed; hashes and node seeds are raw.
public enum PreparedTransactionHash {

    /// `HashPurpose.PreparedSubmission` in Canton (`00 00 00 30` as int32).
    private static let hashPurpose: Int32 = 48

    /// `HASHING_SCHEME_VERSION_V2`, hashed as a single byte at the top level.
    private static let hashingSchemeV2Byte: UInt8 = 0x02

    /// Proto-encoding version byte prefixed to every hashed node (V2 scheme only).
    private static let nodeEncodingVersion: UInt8 = 0x01

    /// Proto-encoding version byte inside the metadata preimage (V2 scheme only).
    private static let metadataEncodingVersion: UInt8 = 0x01

    private static let createNodeTag: UInt8 = 0x00
    private static let exerciseNodeTag: UInt8 = 0x01
    private static let fetchNodeTag: UInt8 = 0x02
    private static let rollbackNodeTag: UInt8 = 0x03

    /// Recomputes the hash locally and throws unless it matches
    /// `prepared_transaction_hash` byte for byte.
    ///
    /// - Throws: `PreparedTransactionHashMismatchError` on mismatch;
    ///   `PreparedTransactionHashError` if the response uses a hashing
    ///   scheme other than V2 or contains features V2 cannot hash.
    public static func verify(
        _ response: Com_Daml_Ledger_Api_V2_Interactive_PrepareSubmissionResponse
    ) throws {
        guard response.hashingSchemeVersion == .v2 else {
            throw PreparedTransactionHashError(
                "cannot verify prepared_transaction_hash: unsupported hashing scheme " +
                    "\(response.hashingSchemeVersion) (only HASHING_SCHEME_VERSION_V2 is supported)"
            )
        }
        let computed = try compute(response.preparedTransaction)
        let fromNode = response.preparedTransactionHash
        guard constantTimeEquals(computed, fromNode) else {
            throw PreparedTransactionHashMismatchError(
                nodeHashHex: hex(fromNode),
                computedHashHex: hex(computed)
            )
        }
    }

    /// Computes the hashing-scheme-V2 hash of `prepared`.
    public static func compute(
        _ prepared: Com_Daml_Ledger_Api_V2_Interactive_PreparedTransaction
    ) throws -> Data {
        let transactionHash = try hashTransaction(prepared.transaction)
        let metadataHash = try hashMetadata(prepared.metadata)
        return sha256 { encoder in
            encoder.int32(hashPurpose)
            encoder.byte(hashingSchemeV2Byte)
            encoder.raw(transactionHash)
            encoder.raw(metadataHash)
        }
    }

    // MARK: - Transaction

    private static func hashTransaction(
        _ transaction: Com_Daml_Ledger_Api_V2_Interactive_DamlTransaction
    ) throws -> Data {
        // Duplicate ids must fail loudly: letting map construction pick a
        // winner would silently hash a different node forest than intended,
        // and which duplicate wins varies between implementations.
        var nodesById: [String: Com_Daml_Ledger_Api_V2_Interactive_DamlTransaction.Node] = [:]
        for node in transaction.nodes {
            guard nodesById.updateValue(node, forKey: node.nodeID) == nil else {
                throw PreparedTransactionHashError(
                    "duplicate node id '\(node.nodeID)' in prepared transaction"
                )
            }
        }
        var seedsByNodeId: [String: Data] = [:]
        for nodeSeed in transaction.nodeSeeds {
            guard seedsByNodeId.updateValue(nodeSeed.seed, forKey: String(nodeSeed.nodeID)) == nil
            else {
                throw PreparedTransactionHashError(
                    "duplicate node seed for node id '\(nodeSeed.nodeID)' in prepared transaction"
                )
            }
        }
        return try sha256 { encoder in
            encoder.int32(hashPurpose)
            encoder.string(transaction.version)
            try encoder.repeated(transaction.roots) {
                encoder.raw(try hashNode($0, nodesById, seedsByNodeId))
            }
        }
    }

    private static func hashNode(
        _ nodeId: String,
        _ nodesById: [String: Com_Daml_Ledger_Api_V2_Interactive_DamlTransaction.Node],
        _ seedsByNodeId: [String: Data]
    ) throws -> Data {
        guard let node = nodesById[nodeId] else {
            throw PreparedTransactionHashError(
                "transaction references node '\(nodeId)' but contains no such node"
            )
        }
        guard case .v1(let v1)? = node.versionedNode else {
            throw PreparedTransactionHashError(
                "node '\(nodeId)' uses unsupported node version " +
                    "\(String(describing: node.versionedNode))"
            )
        }
        return try sha256 { encoder in
            try encode(node: v1, nodeId, into: encoder, nodesById, seedsByNodeId)
        }
    }

    private static func encode(
        node: Com_Daml_Ledger_Api_V2_Interactive_Transaction_V1_Node,
        _ nodeId: String,
        into encoder: Encoder,
        _ nodesById: [String: Com_Daml_Ledger_Api_V2_Interactive_DamlTransaction.Node],
        _ seedsByNodeId: [String: Data]
    ) throws {
        switch node.nodeType {
        case .create(let create):
            guard let seed = seedsByNodeId[nodeId] else {
                throw PreparedTransactionHashError(
                    "missing node seed for create node '\(nodeId)'"
                )
            }
            try encode(create: create, seed: seed, into: encoder)
        case .exercise(let exercise):
            try encode(exercise: exercise, nodeId, into: encoder, nodesById, seedsByNodeId)
        case .fetch(let fetch):
            try encode(fetch: fetch, into: encoder)
        case .rollback(let rollback):
            try encode(rollback: rollback, into: encoder, nodesById, seedsByNodeId)
        default:
            throw PreparedTransactionHashError(
                "node '\(nodeId)' has type \(String(describing: node.nodeType)), " +
                    "which hashing scheme V2 cannot hash"
            )
        }
    }

    private static func encode(
        create: Com_Daml_Ledger_Api_V2_Interactive_Transaction_V1_Create,
        seed: Data?,
        into encoder: Encoder
    ) throws {
        guard !create.hasKey else {
            throw PreparedTransactionHashError(
                "contract key on create node: not supported by hashing scheme V2"
            )
        }
        encoder.byte(nodeEncodingVersion)
        encoder.string(create.lfVersion)
        encoder.byte(createNodeTag)
        encoder.optional(seed) { encoder.raw($0) } // raw seed bytes, no length prefix
        try encoder.hexString(create.contractID)
        encoder.string(create.packageName)
        encode(identifier: create.templateID, into: encoder)
        try encode(value: create.argument, into: encoder)
        encoder.repeated(create.signatories) { encoder.string($0) }
        encoder.repeated(create.stakeholders) { encoder.string($0) }
    }

    private static func encode(
        exercise: Com_Daml_Ledger_Api_V2_Interactive_Transaction_V1_Exercise,
        _ nodeId: String,
        into encoder: Encoder,
        _ nodesById: [String: Com_Daml_Ledger_Api_V2_Interactive_DamlTransaction.Node],
        _ seedsByNodeId: [String: Data]
    ) throws {
        guard !exercise.hasKey, !exercise.byKey else {
            throw PreparedTransactionHashError(
                "contract key on exercise node: not supported by hashing scheme V2"
            )
        }
        guard let seed = seedsByNodeId[nodeId] else {
            throw PreparedTransactionHashError(
                "missing node seed for exercise node '\(nodeId)'"
            )
        }
        encoder.byte(nodeEncodingVersion)
        encoder.string(exercise.lfVersion)
        encoder.byte(exerciseNodeTag)
        encoder.raw(seed) // required, raw seed bytes, no presence byte, no length prefix
        try encoder.hexString(exercise.contractID)
        encoder.string(exercise.packageName)
        encode(identifier: exercise.templateID, into: encoder)
        encoder.repeated(exercise.signatories) { encoder.string($0) }
        encoder.repeated(exercise.stakeholders) { encoder.string($0) }
        encoder.repeated(exercise.actingParties) { encoder.string($0) }
        encoder.optional(exercise.hasInterfaceID ? exercise.interfaceID : nil) {
            encode(identifier: $0, into: encoder)
        }
        encoder.string(exercise.choiceID)
        try encode(value: exercise.chosenValue, into: encoder)
        encoder.bool(exercise.consuming)
        try encoder.optional(exercise.hasExerciseResult ? exercise.exerciseResult : nil) {
            try encode(value: $0, into: encoder)
        }
        encoder.repeated(exercise.choiceObservers) { encoder.string($0) }
        try encoder.repeated(exercise.children) {
            encoder.raw(try hashNode($0, nodesById, seedsByNodeId))
        }
    }

    private static func encode(
        fetch: Com_Daml_Ledger_Api_V2_Interactive_Transaction_V1_Fetch,
        into encoder: Encoder
    ) throws {
        guard !fetch.hasKey, !fetch.byKey else {
            throw PreparedTransactionHashError(
                "contract key on fetch node: not supported by hashing scheme V2"
            )
        }
        encoder.byte(nodeEncodingVersion)
        encoder.string(fetch.lfVersion)
        encoder.byte(fetchNodeTag)
        try encoder.hexString(fetch.contractID)
        encoder.string(fetch.packageName)
        encode(identifier: fetch.templateID, into: encoder)
        encoder.repeated(fetch.signatories) { encoder.string($0) }
        encoder.repeated(fetch.stakeholders) { encoder.string($0) }
        encoder.optional(fetch.hasInterfaceID ? fetch.interfaceID : nil) {
            encode(identifier: $0, into: encoder)
        }
        encoder.repeated(fetch.actingParties) { encoder.string($0) }
    }

    private static func encode(
        rollback: Com_Daml_Ledger_Api_V2_Interactive_Transaction_V1_Rollback,
        into encoder: Encoder,
        _ nodesById: [String: Com_Daml_Ledger_Api_V2_Interactive_DamlTransaction.Node],
        _ seedsByNodeId: [String: Data]
    ) throws {
        encoder.byte(nodeEncodingVersion)
        encoder.byte(rollbackNodeTag) // rollback nodes carry no lf_version
        try encoder.repeated(rollback.children) {
            encoder.raw(try hashNode($0, nodesById, seedsByNodeId))
        }
    }

    // MARK: - Metadata

    private static func hashMetadata(
        _ metadata: Com_Daml_Ledger_Api_V2_Interactive_Metadata
    ) throws -> Data {
        try sha256 { encoder in
            encoder.int32(hashPurpose)
            encoder.byte(metadataEncodingVersion)
            encoder.repeated(metadata.submitterInfo.actAs) { encoder.string($0) }
            encoder.string(metadata.submitterInfo.commandID)
            encoder.string(metadata.transactionUuid)
            encoder.int32(Int32(bitPattern: metadata.mediatorGroup))
            encoder.string(metadata.synchronizerID)
            encoder.optional(
                metadata.hasMinLedgerEffectiveTime ? metadata.minLedgerEffectiveTime : nil
            ) { encoder.int64(Int64(bitPattern: $0)) }
            encoder.optional(
                metadata.hasMaxLedgerEffectiveTime ? metadata.maxLedgerEffectiveTime : nil
            ) { encoder.int64(Int64(bitPattern: $0)) }
            encoder.int64(Int64(bitPattern: metadata.preparationTime))
            // max_record_time and event_blob are deliberately NOT hashed under V2.
            try encoder.repeated(metadata.inputContracts) { contract in
                guard case .v1(let create)? = contract.contract else {
                    throw PreparedTransactionHashError(
                        "input contract uses unsupported version " +
                            "\(String(describing: contract.contract))"
                    )
                }
                encoder.int64(Int64(bitPattern: contract.createdAt))
                encoder.raw(
                    try sha256 { try encode(create: create, seed: nil, into: $0) }
                )
            }
        }
    }

    // MARK: - Values

    private static func encode(
        identifier id: Com_Daml_Ledger_Api_V2_Identifier,
        into encoder: Encoder
    ) {
        encoder.string(id.packageID)
        encoder.repeated(id.moduleName.split(separator: ".", omittingEmptySubsequences: false)) {
            encoder.string(String($0))
        }
        encoder.repeated(id.entityName.split(separator: ".", omittingEmptySubsequences: false)) {
            encoder.string(String($0))
        }
    }

    private static func encode(
        value: Com_Daml_Ledger_Api_V2_Value,
        into encoder: Encoder
    ) throws {
        switch value.sum {
        case .unit:
            encoder.byte(0x00)
        case .bool(let bool):
            encoder.byte(0x01)
            encoder.bool(bool)
        case .int64(let int64):
            encoder.byte(0x02)
            encoder.int64(int64)
        case .numeric(let numeric):
            encoder.byte(0x03)
            encoder.string(numeric)
        case .timestamp(let timestamp):
            encoder.byte(0x04)
            encoder.int64(timestamp)
        case .date(let date):
            encoder.byte(0x05)
            encoder.int32(date)
        case .party(let party):
            encoder.byte(0x06)
            encoder.string(party)
        case .text(let text):
            encoder.byte(0x07)
            encoder.string(text)
        case .contractID(let contractId):
            encoder.byte(0x08)
            try encoder.hexString(contractId)
        case .optional(let optional):
            encoder.byte(0x09)
            try encoder.optional(optional.hasValue ? optional.value : nil) {
                try encode(value: $0, into: encoder)
            }
        case .list(let list):
            encoder.byte(0x0a)
            try encoder.repeated(list.elements) { try encode(value: $0, into: encoder) }
        case .textMap(let textMap):
            encoder.byte(0x0b)
            try encoder.repeated(textMap.entries) { entry in
                encoder.string(entry.key)
                try encode(value: entry.value, into: encoder)
            }
        case .record(let record):
            encoder.byte(0x0c)
            encoder.optional(record.hasRecordID ? record.recordID : nil) {
                encode(identifier: $0, into: encoder)
            }
            try encoder.repeated(record.fields) { field in
                // proto3 cannot distinguish unset from ""; Canton decodes ""
                // as an absent label (ValueValidator), so presence == non-empty.
                encoder.optional(field.label.isEmpty ? nil : field.label) {
                    encoder.string($0)
                }
                try encode(value: field.value, into: encoder)
            }
        case .variant(let variant):
            encoder.byte(0x0d)
            encoder.optional(variant.hasVariantID ? variant.variantID : nil) {
                encode(identifier: $0, into: encoder)
            }
            encoder.string(variant.constructor)
            try encode(value: variant.value, into: encoder)
        case .enum(let enumeration):
            encoder.byte(0x0e)
            encoder.optional(enumeration.hasEnumID ? enumeration.enumID : nil) {
                encode(identifier: $0, into: encoder)
            }
            encoder.string(enumeration.constructor)
        case .genMap(let genMap):
            encoder.byte(0x0f)
            try encoder.repeated(genMap.entries) { entry in
                try encode(value: entry.key, into: encoder)
                try encode(value: entry.value, into: encoder)
            }
        case .none:
            throw PreparedTransactionHashError("cannot hash a value with no sum set")
        }
    }

    // MARK: - Primitive encoding

    /// Deterministic-encoding sink: fixed-length big-endian integers,
    /// int32-length-prefixed byte strings, single presence bytes for
    /// optionals, int32 count prefixes for repeated fields.
    private final class Encoder {
        var out = Data()

        func byte(_ b: UInt8) {
            out.append(b)
        }

        func bool(_ b: Bool) {
            byte(b ? 1 : 0)
        }

        func int32(_ v: Int32) {
            withUnsafeBytes(of: v.bigEndian) { out.append(contentsOf: $0) }
        }

        func int64(_ v: Int64) {
            withUnsafeBytes(of: v.bigEndian) { out.append(contentsOf: $0) }
        }

        func raw(_ bytes: Data) {
            out.append(bytes)
        }

        func lengthPrefixed(_ bytes: Data) {
            int32(Int32(bytes.count))
            raw(bytes)
        }

        func string(_ s: String) {
            lengthPrefixed(Data(s.utf8))
        }

        func hexString(_ hex: String) throws {
            lengthPrefixed(try decodeHex(hex))
        }

        func optional<T>(_ value: T?, _ encode: (T) throws -> Void) rethrows {
            if let value {
                byte(1)
                try encode(value)
            } else {
                byte(0)
            }
        }

        func repeated<S: Collection>(_ values: S, _ encode: (S.Element) throws -> Void) rethrows {
            int32(Int32(values.count))
            for value in values {
                try encode(value)
            }
        }
    }

    private static func sha256(_ build: (Encoder) throws -> Void) rethrows -> Data {
        let encoder = Encoder()
        try build(encoder)
        return Data(SHA256.hash(data: encoder.out))
    }

    private static func decodeHex(_ hex: String) throws -> Data {
        let chars = Array(hex.utf8)
        guard chars.count % 2 == 0 else {
            throw PreparedTransactionHashError("odd-length hex string: '\(hex)'")
        }
        var bytes = Data(capacity: chars.count / 2)
        for i in stride(from: 0, to: chars.count, by: 2) {
            guard let hi = chars[i].hexDigitValue, let lo = chars[i + 1].hexDigitValue else {
                throw PreparedTransactionHashError("invalid hex string: '\(hex)'")
            }
            bytes.append(hi << 4 | lo)
        }
        return bytes
    }

    private static func constantTimeEquals(_ lhs: Data, _ rhs: Data) -> Bool {
        guard lhs.count == rhs.count else { return false }
        return zip(lhs, rhs).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }

    private static func hex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }
}

extension UInt8 {
    fileprivate var hexDigitValue: UInt8? {
        switch self {
        case UInt8(ascii: "0")...UInt8(ascii: "9"): self - UInt8(ascii: "0")
        case UInt8(ascii: "a")...UInt8(ascii: "f"): self - UInt8(ascii: "a") + 10
        case UInt8(ascii: "A")...UInt8(ascii: "F"): self - UInt8(ascii: "A") + 10
        default: nil
        }
    }
}
