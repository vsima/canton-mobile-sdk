// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp.wallet

import com.daml.ledger.api.v2.interactive.InteractiveSubmissionServiceOuterClass.Metadata
import com.daml.ledger.api.v2.interactive.InteractiveSubmissionServiceOuterClass.PreparedTransaction
import io.github.vsima.canton.dapp.DappErrorCode
import io.github.vsima.canton.dapp.DappException
import io.github.vsima.canton.dapp.DappWallet
import io.github.vsima.canton.dapp.DappWalletStatus
import io.github.vsima.canton.dapp.PrepareSubmission
import io.github.vsima.canton.wallet.InteractiveSubmissionClient
import io.grpc.inprocess.InProcessChannelBuilder
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The two halves of the pipeline that carry the design's weight: the envelope
 * the wallet builds around the dApp's commands, and the decode that brings
 * the prepared transaction back from JSON into protobuf.
 *
 * The signing and submission half is deliberately not re-tested here — it is
 * `InteractiveSubmissionClient.signAndExecuteAndWait`, which already has unit,
 * golden-vector and live coverage. Re-asserting it would test the mock.
 */
class JsonPrepareExecutePipelineTest {

    private val alice = DappWallet(
        primary = true,
        partyId = "alice::1220aa",
        status = DappWalletStatus.ALLOCATED,
        hint = "alice",
        publicKey = "00",
        namespace = "1220aa",
        networkId = "canton:localnet",
        signingProviderId = "software",
    )

    private val commands: JsonArray = buildJsonArray {
        add(buildJsonObject { put("CreateCommand", buildJsonObject { put("templateId", "pkg:M:T") }) })
    }

    private val network = DappNetworkConfig(
        networkId = "canton:localnet",
        jsonApiBaseUrl = "http://127.0.0.1:2975",
        synchronizerId = "global-domain::1220bb",
    )

    /**
     * The channel is never used: every test here stops before the gRPC leg.
     * An in-process channel with no server costs nothing to build.
     */
    private fun pipeline(userId: String? = null) = JsonPrepareExecutePipeline(
        ledgerApi = JsonLedgerApiClient(baseUrl = "http://127.0.0.1:2975"),
        submission = InteractiveSubmissionClient(
            InProcessChannelBuilder.forName("unused-${System.nanoTime()}").build()
        ),
        signer = object : io.github.vsima.canton.wallet.SigningDriver {
            override suspend fun publicKey() = throw UnsupportedOperationException()
            override suspend fun sign(bytes: ByteArray) = throw UnsupportedOperationException()
        },
        userId = userId,
    )

    private fun context(submission: PrepareSubmission, actAs: DappWallet = alice) =
        PrepareExecuteContext(
            commandId = "order-4711",
            actAs = actAs,
            submission = submission,
            network = network,
        )

    // ── The envelope ───────────────────────────────────────────────────

    @Test
    fun `the envelope carries every field Canton's decoder demands`() {
        val request = pipeline().buildPrepareRequest(
            context(PrepareSubmission(commands = commands)),
            network.synchronizerId!!,
        )

        // Canton's own OpenAPI lists only commandId, commands and actAs as
        // required, then rejects a body carrying exactly those three. All five
        // go every time — verified live against 3.5.12.
        for (field in listOf(
            "commandId", "commands", "actAs", "synchronizerId", "packageIdSelectionPreference",
        )) {
            assertTrue(request.containsKey(field), "envelope is missing '$field'")
        }
    }

    @Test
    fun `the dApp's commands pass through untouched`() {
        val request = pipeline().buildPrepareRequest(
            context(PrepareSubmission(commands = commands)),
            network.synchronizerId!!,
        )

        // Byte-for-byte the same JSON the dApp authored: re-encoding is the
        // drift surface the whole proxy design exists to avoid.
        assertEquals(commands, request["commands"])
    }

    @Test
    fun `actAs is the approved account, not whatever the dApp asked for`() {
        val request = pipeline().buildPrepareRequest(
            // A dApp naming someone else entirely. DappSession would already
            // have rejected this; the pipeline must not honour it either.
            context(PrepareSubmission(commands = commands, actAs = listOf("mallory::1220cc"))),
            network.synchronizerId!!,
        )

        assertEquals(
            buildJsonArray { add(JsonPrimitive(alice.partyId)) },
            request["actAs"],
            "actAs must come from the approved account",
        )
    }

    @Test
    fun `the synchronizer is the wallet's, never the dApp's`() {
        val request = pipeline().buildPrepareRequest(
            context(PrepareSubmission(commands = commands, synchronizerId = "attacker-domain::dead")),
            network.synchronizerId!!,
        )

        assertEquals(JsonPrimitive("global-domain::1220bb"), request["synchronizerId"])
    }

    @Test
    fun `readAs and disclosedContracts pass through when supplied`() {
        val disclosed = buildJsonArray { add(buildJsonObject { put("contractId", "00feed") }) }
        val request = pipeline().buildPrepareRequest(
            context(
                PrepareSubmission(
                    commands = commands,
                    readAs = listOf("bob::1220bb"),
                    disclosedContracts = disclosed,
                )
            ),
            network.synchronizerId!!,
        )

        // Neither widens who acts, so the dApp may influence both.
        assertEquals(buildJsonArray { add(JsonPrimitive("bob::1220bb")) }, request["readAs"])
        assertEquals(disclosed, request["disclosedContracts"])
    }

    @Test
    fun `readAs is omitted rather than sent empty`() {
        val request = pipeline().buildPrepareRequest(
            context(PrepareSubmission(commands = commands)),
            network.synchronizerId!!,
        )

        assertTrue("readAs" !in request)
        assertTrue("userId" !in request)
    }

    @Test
    fun `userId rides along when the participant scopes by user`() {
        val request = pipeline(userId = "app-user").buildPrepareRequest(
            context(PrepareSubmission(commands = commands)),
            network.synchronizerId!!,
        )

        assertEquals(JsonPrimitive("app-user"), request["userId"])
    }

    // ── The decode ─────────────────────────────────────────────────────

    private fun preparedResponse(
        transaction: PreparedTransaction,
        hash: ByteArray = byteArrayOf(1, 2, 3),
        scheme: String? = "HASHING_SCHEME_VERSION_V2",
    ): JsonObject = buildJsonObject {
        put("preparedTransaction", Base64.getEncoder().encodeToString(transaction.toByteArray()))
        put("preparedTransactionHash", Base64.getEncoder().encodeToString(hash))
        scheme?.let { put("hashingSchemeVersion", it) }
    }

    @Test
    fun `a base64 prepared transaction decodes back into the exact proto`() {
        // The linchpin: JSON hands back base64 of the serialized protobuf, so
        // the bytes reach the hash verifier without a transcode. If Canton
        // ever changes this field to a structured object, this test is what
        // fails.
        val original = PreparedTransaction.newBuilder()
            .setMetadata(Metadata.newBuilder().setSubmitterInfo(
                Metadata.SubmitterInfo.newBuilder().addActAs("alice::1220aa")
            ))
            .build()

        val decoded = pipeline().decodePrepared(preparedResponse(original))

        assertEquals(original, decoded.preparedTransaction)
        assertEquals(
            listOf<Byte>(1, 2, 3),
            decoded.preparedTransactionHash.toByteArray().toList(),
        )
    }

    @Test
    fun `the hashing scheme is carried through`() {
        val decoded = pipeline().decodePrepared(
            preparedResponse(PreparedTransaction.getDefaultInstance(), scheme = "HASHING_SCHEME_VERSION_V3")
        )

        assertEquals("HASHING_SCHEME_VERSION_V3", decoded.hashingSchemeVersion.name)
    }

    @Test
    fun `an absent hashing scheme defaults to V2`() {
        val decoded = pipeline().decodePrepared(
            preparedResponse(PreparedTransaction.getDefaultInstance(), scheme = null)
        )

        // What Canton documents as its default; guessing UNSPECIFIED would
        // make execute fail with a far less obvious error.
        assertEquals("HASHING_SCHEME_VERSION_V2", decoded.hashingSchemeVersion.name)
    }

    @Test
    fun `a non-base64 prepared transaction fails legibly`() {
        val thrown = assertFailsWith<DappException> {
            pipeline().decodePrepared(
                buildJsonObject {
                    put("preparedTransaction", "!!! not base64 !!!")
                    put("preparedTransactionHash", "AQID")
                }
            )
        }

        assertEquals(DappErrorCode.INTERNAL, thrown.errorCode)
        assertTrue(thrown.message!!.contains("base64"))
    }

    @Test
    fun `a missing prepared transaction fails legibly`() {
        val thrown = assertFailsWith<DappException> {
            pipeline().decodePrepared(buildJsonObject { put("preparedTransactionHash", "AQID") })
        }

        assertEquals(DappErrorCode.INTERNAL, thrown.errorCode)
        assertTrue(thrown.message!!.contains("preparedTransaction"))
    }

    @Test
    fun `a missing synchronizer is refused before anything is sent`() {
        val pipeline = JsonPrepareExecutePipeline(
            ledgerApi = JsonLedgerApiClient(baseUrl = "http://127.0.0.1:2975"),
            submission = InteractiveSubmissionClient(
                InProcessChannelBuilder.forName("unused-sync").build()
            ),
            signer = object : io.github.vsima.canton.wallet.SigningDriver {
                override suspend fun publicKey() = throw UnsupportedOperationException()
                override suspend fun sign(bytes: ByteArray) = throw UnsupportedOperationException()
            },
        )
        val ctx = PrepareExecuteContext(
            commandId = "c1",
            actAs = alice,
            submission = PrepareSubmission(commands = commands),
            network = DappNetworkConfig(networkId = "canton:localnet"), // no synchronizerId
        )

        val thrown = assertFailsWith<DappException> {
            kotlinx.coroutines.runBlocking { pipeline.execute(ctx) }
        }

        // Defaulting it from the dApp's request would hand a dApp the choice
        // of synchronizer, so this fails rather than guesses.
        assertEquals(DappErrorCode.INTERNAL, thrown.errorCode)
        assertTrue(thrown.message!!.contains("synchronizerId"))
    }
}
