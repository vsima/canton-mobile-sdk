// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet

import com.daml.ledger.api.v2.interactive.InteractiveSubmissionServiceOuterClass.HashingSchemeVersion
import com.daml.ledger.api.v2.interactive.InteractiveSubmissionServiceOuterClass.PrepareSubmissionResponse
import com.daml.ledger.api.v2.interactive.InteractiveSubmissionServiceOuterClass.PreparedTransaction
import com.google.protobuf.ByteString
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Recomputes the hash of real PreparedTransactions captured from a live
 * Splice LocalNet participant (testdata/preparedtx/vectors.txt, written
 * from [LocalNetPreparedTransactionHashIntegrationTest] output) — so plain
 * CI exercises the full hashing scheme V2 encoder without a ledger. The
 * vectors cover a create node and an exercise node with an input contract.
 */
class PreparedTransactionHashGoldenTest {

    private fun vectors(): List<Triple<String, PreparedTransaction, ByteArray>> =
        File("../../testdata/preparedtx/vectors.txt").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val (name, txB64, hashB64) = line.split(" ")
                Triple(
                    name,
                    PreparedTransaction.parseFrom(Base64.getDecoder().decode(txB64)),
                    Base64.getDecoder().decode(hashB64),
                )
            }

    @Test
    fun `recomputes every golden vector's hash byte-for-byte`() {
        val vectors = vectors()
        assertEquals(2, vectors.size, "vector count changed; update the SDK tests")
        for ((name, prepared, expected) in vectors) {
            assertContentEquals(
                expected,
                PreparedTransactionHash.compute(prepared),
                "recomputed hash differs from the node's for vector '$name'",
            )
            // The verify() path the client uses before signing.
            PreparedTransactionHash.verify(response(prepared, expected))
        }
    }

    @Test
    fun `a tampered transaction fails verification`() {
        val (_, prepared, expected) = vectors().first()
        val tampered = prepared.toBuilder()
            .setMetadata(
                prepared.metadata.toBuilder().setSubmitterInfo(
                    prepared.metadata.submitterInfo.toBuilder()
                        .setCommandId("attacker-swapped-command")
                )
            )
            .build()
        assertFailsWith<PreparedTransactionHashMismatchException> {
            PreparedTransactionHash.verify(response(tampered, expected))
        }
    }

    @Test
    fun `a duplicated node id fails hashing instead of resolving silently`() {
        val (_, prepared, _) = vectors().first()
        val transaction = prepared.transaction
        val duplicated = prepared.toBuilder()
            .setTransaction(transaction.toBuilder().addNodes(transaction.getNodes(0)))
            .build()
        val failure = assertFailsWith<PreparedTransactionHashException> {
            PreparedTransactionHash.compute(duplicated)
        }
        assertTrue("duplicate node id" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `a duplicated node seed id fails hashing instead of resolving silently`() {
        val (_, prepared, _) = vectors().first()
        val transaction = prepared.transaction
        val duplicated = prepared.toBuilder()
            .setTransaction(transaction.toBuilder().addNodeSeeds(transaction.getNodeSeeds(0)))
            .build()
        val failure = assertFailsWith<PreparedTransactionHashException> {
            PreparedTransactionHash.compute(duplicated)
        }
        assertTrue("duplicate node seed" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `an unsupported hashing scheme is rejected rather than trusted`() {
        val (_, prepared, expected) = vectors().first()
        val v3 = response(prepared, expected).toBuilder()
            .setHashingSchemeVersion(HashingSchemeVersion.HASHING_SCHEME_VERSION_V3)
            .build()
        val failure = assertFailsWith<PreparedTransactionHashException> {
            PreparedTransactionHash.verify(v3)
        }
        assertEquals(false, failure is PreparedTransactionHashMismatchException)
    }

    private fun response(prepared: PreparedTransaction, hash: ByteArray): PrepareSubmissionResponse =
        PrepareSubmissionResponse.newBuilder()
            .setPreparedTransaction(prepared)
            .setPreparedTransactionHash(ByteString.copyFrom(hash))
            .setHashingSchemeVersion(HashingSchemeVersion.HASHING_SCHEME_VERSION_V2)
            .build()
}
