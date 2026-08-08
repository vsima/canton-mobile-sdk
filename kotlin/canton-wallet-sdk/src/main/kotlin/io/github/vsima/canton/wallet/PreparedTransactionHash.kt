// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet

import com.daml.ledger.api.v2.ValueOuterClass
import com.daml.ledger.api.v2.interactive.InteractiveSubmissionServiceOuterClass.DamlTransaction
import com.daml.ledger.api.v2.interactive.InteractiveSubmissionServiceOuterClass.HashingSchemeVersion
import com.daml.ledger.api.v2.interactive.InteractiveSubmissionServiceOuterClass.Metadata
import com.daml.ledger.api.v2.interactive.InteractiveSubmissionServiceOuterClass.PrepareSubmissionResponse
import com.daml.ledger.api.v2.interactive.InteractiveSubmissionServiceOuterClass.PreparedTransaction
import com.daml.ledger.api.v2.interactive.transaction.v1.InteractiveSubmissionDataOuterClass
import com.google.protobuf.ByteString
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** The locally recomputed hash does not match what the node asked us to sign. */
public class PreparedTransactionHashMismatchException(
    /** Hex of the hash the preparing node returned. */
    public val nodeHashHex: String,
    /** Hex of the hash recomputed locally from the PreparedTransaction proto. */
    public val computedHashHex: String,
) : PreparedTransactionHashException(
    "prepared_transaction_hash mismatch: the node returned $nodeHashHex but the " +
        "PreparedTransaction re-hashes to $computedHashHex. Refusing to sign — the " +
        "preparing participant may be malicious or running an incompatible version."
)

/** The PreparedTransaction cannot be hashed (or verified) by this client. */
public open class PreparedTransactionHashException(message: String) : RuntimeException(message)

/**
 * Client-side implementation of Canton's "hashing scheme version 2" for
 * interactive submissions: recomputes `prepared_transaction_hash` from the
 * raw [PreparedTransaction] proto so a wallet never signs a hash it did not
 * derive itself (interactive_submission_service.proto: "clients MUST
 * recompute the hash from the raw transaction if the preparing participant
 * is not trusted").
 *
 * The byte layout follows Canton's reference implementation
 * (`com.digitalasset.canton.protocol.hash`, scheme V2) and Digital Asset's
 * TypeScript wallet SDK (`@canton-network/wallet-sdk` hash encoders). The
 * full byte-level spec with citations lives in docs/prepared-tx-hash.md.
 *
 * In short: `SHA256(purpose ++ 0x02 ++ txHash ++ metadataHash)` where
 * `purpose = int32(48)` (PreparedSubmission), `txHash` covers the node
 * forest as recursively hashed subtrees, and `metadataHash` covers the
 * signed metadata (act_as, command id, transaction UUID, mediator group,
 * synchronizer, time bounds, preparation time, input contracts). All
 * integers are fixed-length big-endian; strings and byte strings are
 * int32-length-prefixed; hashes and node seeds are raw.
 */
public object PreparedTransactionHash {

    /** `HashPurpose.PreparedSubmission` in Canton (`00 00 00 30` as int32). */
    private const val HASH_PURPOSE: Int = 48

    /** Proto-encoding version byte prefixed to every hashed node (V2 scheme only). */
    private const val NODE_ENCODING_VERSION: Int = 0x01

    /** Proto-encoding version byte inside the metadata preimage (V2 scheme only). */
    private const val METADATA_ENCODING_VERSION: Int = 0x01

    private const val CREATE_NODE_TAG: Int = 0x00
    private const val EXERCISE_NODE_TAG: Int = 0x01
    private const val FETCH_NODE_TAG: Int = 0x02
    private const val ROLLBACK_NODE_TAG: Int = 0x03

    /**
     * Recomputes the hash locally and throws unless it matches
     * `prepared_transaction_hash` byte for byte.
     *
     * @throws PreparedTransactionHashMismatchException on mismatch
     * @throws PreparedTransactionHashException if the response uses a hashing
     *   scheme other than V2 or contains features V2 cannot hash
     */
    public fun verify(response: PrepareSubmissionResponse) {
        if (response.hashingSchemeVersion != HashingSchemeVersion.HASHING_SCHEME_VERSION_V2) {
            throw PreparedTransactionHashException(
                "cannot verify prepared_transaction_hash: unsupported hashing scheme " +
                    "${response.hashingSchemeVersion} (only HASHING_SCHEME_VERSION_V2 is supported)"
            )
        }
        val computed = compute(response.preparedTransaction)
        val fromNode = response.preparedTransactionHash.toByteArray()
        if (!MessageDigest.isEqual(computed, fromNode)) {
            throw PreparedTransactionHashMismatchException(
                nodeHashHex = fromNode.toHex(),
                computedHashHex = computed.toHex(),
            )
        }
    }

    /** Computes the hashing-scheme-V2 hash of [prepared]. */
    public fun compute(prepared: PreparedTransaction): ByteArray {
        val transactionHash = hashTransaction(prepared.transaction)
        val metadataHash = hashMetadata(prepared.metadata)
        return sha256 {
            int32(HASH_PURPOSE)
            byte(HashingSchemeVersion.HASHING_SCHEME_VERSION_V2_VALUE)
            raw(transactionHash)
            raw(metadataHash)
        }
    }

    // -- transaction -------------------------------------------------------

    private fun hashTransaction(transaction: DamlTransaction): ByteArray {
        val nodesById = transaction.nodesList.associateBy { it.nodeId }
        val seedsByNodeId =
            transaction.nodeSeedsList.associate { it.nodeId.toString() to it.seed }
        return sha256 {
            int32(HASH_PURPOSE)
            string(transaction.version)
            repeated(transaction.rootsList) { raw(hashNode(it, nodesById, seedsByNodeId)) }
        }
    }

    private fun hashNode(
        nodeId: String,
        nodesById: Map<String, DamlTransaction.Node>,
        seedsByNodeId: Map<String, ByteString>,
    ): ByteArray {
        val node = nodesById[nodeId]
            ?: throw PreparedTransactionHashException(
                "transaction references node '$nodeId' but contains no such node"
            )
        if (node.versionedNodeCase != DamlTransaction.Node.VersionedNodeCase.V1) {
            throw PreparedTransactionHashException(
                "node '$nodeId' uses unsupported node version ${node.versionedNodeCase}"
            )
        }
        return sha256 { node(node.v1, nodeId, nodesById, seedsByNodeId) }
    }

    private fun Encoder.node(
        node: InteractiveSubmissionDataOuterClass.Node,
        nodeId: String,
        nodesById: Map<String, DamlTransaction.Node>,
        seedsByNodeId: Map<String, ByteString>,
    ): Unit = when (node.nodeTypeCase) {
        InteractiveSubmissionDataOuterClass.Node.NodeTypeCase.CREATE ->
            createNode(
                node.create,
                seed = seedsByNodeId[nodeId]
                    ?: throw PreparedTransactionHashException(
                        "missing node seed for create node '$nodeId'"
                    ),
            )
        InteractiveSubmissionDataOuterClass.Node.NodeTypeCase.EXERCISE ->
            exerciseNode(node.exercise, nodeId, nodesById, seedsByNodeId)
        InteractiveSubmissionDataOuterClass.Node.NodeTypeCase.FETCH ->
            fetchNode(node.fetch)
        InteractiveSubmissionDataOuterClass.Node.NodeTypeCase.ROLLBACK ->
            rollbackNode(node.rollback, nodesById, seedsByNodeId)
        else -> throw PreparedTransactionHashException(
            "node '$nodeId' has type ${node.nodeTypeCase}, which hashing scheme V2 cannot hash"
        )
    }

    private fun Encoder.createNode(
        create: InteractiveSubmissionDataOuterClass.Create,
        seed: ByteString?,
    ) {
        if (create.hasKey()) {
            throw PreparedTransactionHashException(
                "contract key on create node: not supported by hashing scheme V2"
            )
        }
        byte(NODE_ENCODING_VERSION)
        string(create.lfVersion)
        byte(CREATE_NODE_TAG)
        optional(seed) { raw(it) } // raw seed bytes, no length prefix
        hexString(create.contractId)
        string(create.packageName)
        identifier(create.templateId)
        value(create.argument)
        repeated(create.signatoriesList) { string(it) }
        repeated(create.stakeholdersList) { string(it) }
    }

    private fun Encoder.exerciseNode(
        exercise: InteractiveSubmissionDataOuterClass.Exercise,
        nodeId: String,
        nodesById: Map<String, DamlTransaction.Node>,
        seedsByNodeId: Map<String, ByteString>,
    ) {
        if (exercise.hasKey() || exercise.byKey) {
            throw PreparedTransactionHashException(
                "contract key on exercise node: not supported by hashing scheme V2"
            )
        }
        val seed = seedsByNodeId[nodeId]
            ?: throw PreparedTransactionHashException(
                "missing node seed for exercise node '$nodeId'"
            )
        byte(NODE_ENCODING_VERSION)
        string(exercise.lfVersion)
        byte(EXERCISE_NODE_TAG)
        raw(seed) // required, raw seed bytes, no presence byte, no length prefix
        hexString(exercise.contractId)
        string(exercise.packageName)
        identifier(exercise.templateId)
        repeated(exercise.signatoriesList) { string(it) }
        repeated(exercise.stakeholdersList) { string(it) }
        repeated(exercise.actingPartiesList) { string(it) }
        optional(if (exercise.hasInterfaceId()) exercise.interfaceId else null) { identifier(it) }
        string(exercise.choiceId)
        value(exercise.chosenValue)
        bool(exercise.consuming)
        optional(if (exercise.hasExerciseResult()) exercise.exerciseResult else null) { value(it) }
        repeated(exercise.choiceObserversList) { string(it) }
        repeated(exercise.childrenList) { raw(hashNode(it, nodesById, seedsByNodeId)) }
    }

    private fun Encoder.fetchNode(fetch: InteractiveSubmissionDataOuterClass.Fetch) {
        if (fetch.hasKey() || fetch.byKey) {
            throw PreparedTransactionHashException(
                "contract key on fetch node: not supported by hashing scheme V2"
            )
        }
        byte(NODE_ENCODING_VERSION)
        string(fetch.lfVersion)
        byte(FETCH_NODE_TAG)
        hexString(fetch.contractId)
        string(fetch.packageName)
        identifier(fetch.templateId)
        repeated(fetch.signatoriesList) { string(it) }
        repeated(fetch.stakeholdersList) { string(it) }
        optional(if (fetch.hasInterfaceId()) fetch.interfaceId else null) { identifier(it) }
        repeated(fetch.actingPartiesList) { string(it) }
    }

    private fun Encoder.rollbackNode(
        rollback: InteractiveSubmissionDataOuterClass.Rollback,
        nodesById: Map<String, DamlTransaction.Node>,
        seedsByNodeId: Map<String, ByteString>,
    ) {
        byte(NODE_ENCODING_VERSION)
        byte(ROLLBACK_NODE_TAG) // rollback nodes carry no lf_version
        repeated(rollback.childrenList) { raw(hashNode(it, nodesById, seedsByNodeId)) }
    }

    // -- metadata ----------------------------------------------------------

    private fun hashMetadata(metadata: Metadata): ByteArray = sha256 {
        int32(HASH_PURPOSE)
        byte(METADATA_ENCODING_VERSION)
        repeated(metadata.submitterInfo.actAsList) { string(it) }
        string(metadata.submitterInfo.commandId)
        string(metadata.transactionUuid)
        int32(metadata.mediatorGroup)
        string(metadata.synchronizerId)
        optional(
            if (metadata.hasMinLedgerEffectiveTime()) metadata.minLedgerEffectiveTime else null
        ) { int64(it) }
        optional(
            if (metadata.hasMaxLedgerEffectiveTime()) metadata.maxLedgerEffectiveTime else null
        ) { int64(it) }
        int64(metadata.preparationTime)
        // max_record_time and event_blob are deliberately NOT hashed under V2.
        repeated(metadata.inputContractsList) { contract ->
            if (contract.contractCase != Metadata.InputContract.ContractCase.V1) {
                throw PreparedTransactionHashException(
                    "input contract uses unsupported version ${contract.contractCase}"
                )
            }
            int64(contract.createdAt)
            raw(sha256 { createNode(contract.v1, seed = null) })
        }
    }

    // -- values ------------------------------------------------------------

    private fun Encoder.identifier(id: ValueOuterClass.Identifier) {
        string(id.packageId)
        repeated(id.moduleName.split('.')) { string(it) }
        repeated(id.entityName.split('.')) { string(it) }
    }

    private fun Encoder.value(value: ValueOuterClass.Value): Unit = when (value.sumCase) {
        ValueOuterClass.Value.SumCase.UNIT -> byte(0x00)
        ValueOuterClass.Value.SumCase.BOOL -> {
            byte(0x01)
            bool(value.bool)
        }
        ValueOuterClass.Value.SumCase.INT64 -> {
            byte(0x02)
            int64(value.int64)
        }
        ValueOuterClass.Value.SumCase.NUMERIC -> {
            byte(0x03)
            string(value.numeric)
        }
        ValueOuterClass.Value.SumCase.TIMESTAMP -> {
            byte(0x04)
            int64(value.timestamp)
        }
        ValueOuterClass.Value.SumCase.DATE -> {
            byte(0x05)
            int32(value.date)
        }
        ValueOuterClass.Value.SumCase.PARTY -> {
            byte(0x06)
            string(value.party)
        }
        ValueOuterClass.Value.SumCase.TEXT -> {
            byte(0x07)
            string(value.text)
        }
        ValueOuterClass.Value.SumCase.CONTRACT_ID -> {
            byte(0x08)
            hexString(value.contractId)
        }
        ValueOuterClass.Value.SumCase.OPTIONAL -> {
            byte(0x09)
            optional(if (value.optional.hasValue()) value.optional.value else null) { value(it) }
        }
        ValueOuterClass.Value.SumCase.LIST -> {
            byte(0x0a)
            repeated(value.list.elementsList) { value(it) }
        }
        ValueOuterClass.Value.SumCase.TEXT_MAP -> {
            byte(0x0b)
            repeated(value.textMap.entriesList) { entry ->
                string(entry.key)
                value(entry.value)
            }
        }
        ValueOuterClass.Value.SumCase.RECORD -> {
            byte(0x0c)
            optional(if (value.record.hasRecordId()) value.record.recordId else null) {
                identifier(it)
            }
            repeated(value.record.fieldsList) { field ->
                // proto3 cannot distinguish unset from ""; Canton decodes "" as
                // an absent label (ValueValidator), so presence == non-empty.
                optional(field.label.ifEmpty { null }) { string(it) }
                value(field.value)
            }
        }
        ValueOuterClass.Value.SumCase.VARIANT -> {
            byte(0x0d)
            optional(if (value.variant.hasVariantId()) value.variant.variantId else null) {
                identifier(it)
            }
            string(value.variant.constructor)
            value(value.variant.value)
        }
        ValueOuterClass.Value.SumCase.ENUM -> {
            byte(0x0e)
            optional(if (value.enum.hasEnumId()) value.enum.enumId else null) { identifier(it) }
            string(value.enum.constructor)
        }
        ValueOuterClass.Value.SumCase.GEN_MAP -> {
            byte(0x0f)
            repeated(value.genMap.entriesList) { entry ->
                value(entry.key)
                value(entry.value)
            }
        }
        else -> throw PreparedTransactionHashException("cannot hash a value with no sum set")
    }

    // -- primitive encoding ------------------------------------------------

    /**
     * Deterministic-encoding sink: fixed-length big-endian integers,
     * int32-length-prefixed byte strings, single presence bytes for
     * optionals, int32 count prefixes for repeated fields.
     */
    private class Encoder {
        val out = ByteArrayOutputStream()

        fun byte(b: Int) = out.write(b)

        fun bool(b: Boolean) = byte(if (b) 1 else 0)

        fun int32(v: Int) {
            byte(v ushr 24)
            byte(v ushr 16)
            byte(v ushr 8)
            byte(v)
        }

        fun int64(v: Long) {
            int32((v ushr 32).toInt())
            int32(v.toInt())
        }

        fun raw(bytes: ByteArray) = out.write(bytes)

        fun raw(bytes: ByteString) = bytes.writeTo(out)

        fun lengthPrefixed(bytes: ByteArray) {
            int32(bytes.size)
            raw(bytes)
        }

        fun string(s: String) = lengthPrefixed(s.toByteArray(Charsets.UTF_8))

        fun hexString(hex: String) = lengthPrefixed(decodeHex(hex))

        inline fun <T : Any> optional(value: T?, encode: Encoder.(T) -> Unit) {
            if (value == null) byte(0) else {
                byte(1)
                encode(value)
            }
        }

        inline fun <T> repeated(values: List<T>, encode: Encoder.(T) -> Unit) {
            int32(values.size)
            values.forEach { encode(it) }
        }
    }

    private inline fun sha256(build: Encoder.() -> Unit): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest(Encoder().apply(build).out.toByteArray())

    private fun decodeHex(hex: String): ByteArray {
        if (hex.length % 2 != 0) {
            throw PreparedTransactionHashException("odd-length hex string: '$hex'")
        }
        return ByteArray(hex.length / 2) { i ->
            val hi = Character.digit(hex[2 * i], 16)
            val lo = Character.digit(hex[2 * i + 1], 16)
            if (hi < 0 || lo < 0) {
                throw PreparedTransactionHashException("invalid hex string: '$hex'")
            }
            ((hi shl 4) or lo).toByte()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
