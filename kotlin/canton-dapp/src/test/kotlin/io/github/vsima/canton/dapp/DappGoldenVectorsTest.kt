// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The shared CIP-0103 codec vectors (testdata/dapp/vectors.json), also
 * consumed by the Swift suite — the acceptance criterion for F1 is that both
 * platforms agree on every one of them.
 *
 * The property under test is that **decode-then-encode is a fixpoint**. That
 * is stronger than "decoding works" and catches the failures that actually
 * happen: an optional silently dropped on the way out, a `null` emitted where
 * the field should have been omitted, an enum round-tripping to a different
 * spelling. A test that only decoded would pass while the wallet emitted
 * documents no dApp could read.
 */
class DappGoldenVectorsTest {

    private val document: JsonObject =
        Json.parseToJsonElement(File("../../testdata/dapp/vectors.json").readText()) as JsonObject

    private val vectors: JsonArray = document["vectors"] as JsonArray
    private val invalid: JsonArray = document["invalid"] as JsonArray

    @Test
    fun `every vector round-trips to an equal document`() {
        assertTrue(vectors.isNotEmpty(), "no vectors loaded — is testdata/dapp/vectors.json present?")
        for (entry in vectors) {
            val vector = entry as JsonObject
            val name = (vector["name"] as JsonPrimitiveLike).content
            val type = (vector["type"] as JsonPrimitiveLike).content
            val json = vector["json"]!!
            val reEncoded = roundTrip(type, json)
            assertEquals(json, reEncoded, "vector '$name' ($type) did not round-trip")
        }
    }

    @Test
    fun `every invalid vector is rejected`() {
        assertTrue(invalid.isNotEmpty(), "no negative vectors loaded")
        for (entry in invalid) {
            val vector = entry as JsonObject
            val name = (vector["name"] as JsonPrimitiveLike).content
            val type = (vector["type"] as JsonPrimitiveLike).content
            val json = vector["json"]!!
            val reason = (vector["reason"] as JsonPrimitiveLike).content
            assertFailsWith<DappException>("'$name' should have been rejected: $reason") {
                roundTrip(type, json)
            }
        }
    }

    /** Covers the full OpenRPC 0.5.0 type surface; an unknown name fails loudly. */
    private fun roundTrip(type: String, json: JsonElement): JsonElement = when (type) {
        "Provider" -> DappJson.encode(DappJson.decodeProvider(json))
        "ConnectResult" -> DappJson.encode(DappJson.decodeConnectResult(json))
        "Network" -> DappJson.encode(DappJson.decodeNetwork(json))
        "Session" -> DappJson.encode(DappJson.decodeSessionInfo(json))
        "Wallet" -> DappJson.encode(DappJson.decodeWallet(json))
        "ListAccountsResult" -> DappJson.encodeAccounts(DappJson.decodeAccounts(json))
        "StatusEvent" -> DappJson.encode(DappJson.decodeStatus(json))
        "SignMessageRequest" -> DappJson.encode(DappJson.decodeSignMessageRequest(json))
        "SignMessageResult" -> DappJson.encode(DappJson.decodeSignMessageResult(json))
        "LedgerApiRequest" -> DappJson.encode(DappJson.decodeLedgerApiRequest(json))
        "JsPrepareSubmissionRequest" -> DappJson.encode(DappJson.decodePrepareSubmission(json))
        "JsPrepareSubmissionResponse" -> DappJson.encode(DappJson.decodePrepareSubmissionResult(json))
        "TxChangedEvent" -> DappJson.encode(DappJson.decodeTxChanged(json))
        "MessageSignatureEvent" -> DappJson.encode(DappJson.decodeMessageSignature(json))
        "prepareExecuteAndWaitResult" -> DappJson.encodeExecutedResult(DappJson.decodeExecutedResult(json))
        "JsonRpcRequest" -> JsonRpcRequest.decode(json as JsonObject).encode()
        "JsonRpcResponse" -> JsonRpcResponse.decode(json as JsonObject).encode()
        else -> error("vectors.json names a type this test does not cover: '$type'")
    }
}

/**
 * kotlinx's `JsonPrimitive` under a local alias, so the casts above read as
 * intent rather than as three imports doing nothing else.
 */
private typealias JsonPrimitiveLike = kotlinx.serialization.json.JsonPrimitive
