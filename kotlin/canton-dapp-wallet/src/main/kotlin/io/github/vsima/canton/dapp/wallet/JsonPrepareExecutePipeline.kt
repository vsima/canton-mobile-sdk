// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp.wallet

import com.daml.ledger.api.v2.interactive.InteractiveSubmissionServiceOuterClass.HashingSchemeVersion
import com.daml.ledger.api.v2.interactive.InteractiveSubmissionServiceOuterClass.PrepareSubmissionResponse
import com.daml.ledger.api.v2.interactive.InteractiveSubmissionServiceOuterClass.PreparedTransaction
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import io.github.vsima.canton.dapp.DappErrorCode
import io.github.vsima.canton.dapp.DappSignMessage
import io.github.vsima.canton.dapp.DappException
import io.github.vsima.canton.dapp.DappWallet
import io.github.vsima.canton.dapp.TxChangedEvent
import io.github.vsima.canton.wallet.InteractiveSubmissionClient
import io.github.vsima.canton.wallet.PreparedTransactionHashMismatchException
import io.github.vsima.canton.wallet.SigningDriver
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The `prepareExecute` pipeline: JSON prepare → verify → sign → gRPC execute.
 *
 * ### Why the transaction can cross between JSON and gRPC unharmed
 *
 * In the gRPC API `PrepareSubmissionResponse.prepared_transaction` is a
 * *message*; in the JSON API the same field is a *string*. Verified live
 * against Canton 3.5.12: that string is **base64 of the serialized protobuf**
 * — the server rejects non-base64 with `The string is not a valid Base64` and
 * rejects a JSON object with `wrong type, expecting string`.
 *
 * That single fact is what makes this design sound. The prepared transaction
 * is decoded straight back into its protobuf form and handed to the
 * **existing, golden-vector-tested** hash verifier and submission path; there
 * is no JSON→proto transcode anywhere, so nothing can drift between what the
 * participant prepared, what the user approved, and what gets signed.
 *
 * ### What the wallet supplies, and why
 *
 * The dApp's `commands` are proxied verbatim. Everything around them is the
 * wallet's, and `actAs` above all: it is the party the *user approved*, taken
 * from [PrepareExecuteContext.actAs], never from the dApp's request. A dApp
 * able to set it could make the wallet act as any party it names.
 * [DappSession] has already validated any requested `actAs` — and any
 * requested `readAs` — against the peer's grant before this runs, so both
 * name only parties the user approved.
 *
 * Note the envelope carries fields Canton's published OpenAPI calls optional
 * — `synchronizerId` and `packageIdSelectionPreference`. The decoder requires
 * them anyway (verified live), so they are always sent.
 */
public class JsonPrepareExecutePipeline(
    private val ledgerApi: JsonLedgerApiClient,
    private val submission: InteractiveSubmissionClient,
    private val signer: SigningDriver,
    /**
     * The fingerprint of the party's registered signing key, which the
     * signature must carry as `signed_by`.
     *
     * Defaults to the namespace of the party id — correct for external
     * parties whose namespace *is* their signing key, which is how
     * `ExternalPartyClient` onboards them. Override for any other topology.
     */
    private val keyFingerprint: (DappWallet) -> String = { it.namespace },
    /** Ledger API user id, when the participant scopes submissions by user. */
    private val userId: String? = null,
) : PrepareExecutePipeline {

    override suspend fun execute(context: PrepareExecuteContext): TxChangedEvent.Executed {
        val synchronizerId = context.network.synchronizerId
            ?: throw DappException(
                DappErrorCode.INTERNAL,
                "DappNetworkConfig.synchronizerId is not set; the wallet must choose the " +
                    "synchronizer, so this cannot be defaulted from the dApp's request",
            )

        val prepared = prepare(context, synchronizerId)

        // signAndExecuteAndWait recomputes the hash from the prepared bytes
        // and refuses to sign on mismatch, then awaits the ledger completion.
        // Reused rather than reimplemented: it is the path the golden vectors
        // and the live suites already hold to.
        val completion = try {
            submission.signAndExecuteAndWait(
                prepared = prepared,
                driver = signer,
                partyId = context.actAs.partyId,
                keyFingerprint = keyFingerprint(context.actAs),
                userId = userId,
            )
        } catch (e: PreparedTransactionHashMismatchException) {
            // The participant's prepared bytes do not hash to the hash it
            // reported. Nothing was signed. This is the check existing for
            // exactly one reason, so say so plainly rather than as -32603.
            throw DappException(
                DappErrorCode.TRANSACTION_REJECTED,
                "prepared transaction failed hash verification; refusing to sign",
                cause = e,
            )
        }

        return TxChangedEvent.Executed(
            commandId = context.commandId,
            updateId = completion.updateId,
            completionOffset = completion.offset,
        )
    }

    /** POSTs the envelope and decodes the result back into protobuf. */
    private suspend fun prepare(
        context: PrepareExecuteContext,
        synchronizerId: String,
    ): PrepareSubmissionResponse = decodePrepared(
        ledgerApi.post(PREPARE_PATH, buildPrepareRequest(context, synchronizerId)),
    )

    /**
     * The prepare envelope: the dApp's `commands`, everything else ours.
     *
     * `internal` so the test source set can assert on it directly — the
     * property that matters (actAs comes from the approved account, not the
     * request) is not observable through a mocked HTTP round trip without
     * also standing up a gRPC channel.
     */
    internal fun buildPrepareRequest(
        context: PrepareExecuteContext,
        synchronizerId: String,
    ): JsonObject {
        return buildJsonObject {
            put("commandId", context.commandId)
            // The dApp's payload, untouched.
            put("commands", context.submission.commands)
            put("actAs", buildJsonArray { add(JsonPrimitive(context.actAs.partyId)) })
            put("synchronizerId", synchronizerId)
            put(
                "packageIdSelectionPreference",
                buildJsonArray {
                    for (id in context.submission.packageIdSelectionPreference) add(JsonPrimitive(id))
                },
            )
            // readAs is the dApp's, but [DappSession] has already checked every
            // requested party against the peer's grant — like actAs — so this
            // only ever forwards parties the user approved. disclosedContracts
            // are on-ledger contracts the dApp discloses; they widen neither
            // actAs nor readAs.
            if (context.submission.readAs.isNotEmpty()) {
                put(
                    "readAs",
                    buildJsonArray { for (p in context.submission.readAs) add(JsonPrimitive(p)) },
                )
            }
            context.submission.disclosedContracts?.let { put("disclosedContracts", it) }
            userId?.let { put("userId", it) }
        }
    }

    internal fun decodePrepared(response: JsonElement): PrepareSubmissionResponse {
        val obj = response as? JsonObject
            ?: throw DappException(
                DappErrorCode.INTERNAL,
                "prepare returned ${response::class.simpleName}, expected an object",
            )
        val transactionBytes = obj.base64("preparedTransaction")
        val hashBytes = obj.base64("preparedTransactionHash")
        val scheme = (obj["hashingSchemeVersion"] as? JsonPrimitive)?.content

        val transaction = try {
            PreparedTransaction.parseFrom(transactionBytes)
        } catch (e: InvalidProtocolBufferException) {
            throw DappException(
                DappErrorCode.INTERNAL,
                "prepare returned a preparedTransaction that is not a PreparedTransaction proto " +
                    "(${transactionBytes.size} bytes). If Canton has changed this field from " +
                    "base64 protobuf to a structured object, this pipeline needs revisiting.",
                cause = e,
            )
        }

        return PrepareSubmissionResponse.newBuilder()
            .setPreparedTransaction(transaction)
            .setPreparedTransactionHash(ByteString.copyFrom(hashBytes))
            .setHashingSchemeVersion(
                scheme?.let { runCatching { HashingSchemeVersion.valueOf(it) }.getOrNull() }
                    ?: HashingSchemeVersion.HASHING_SCHEME_VERSION_V2,
            )
            .build()
    }

    private fun JsonObject.base64(field: String): ByteArray {
        val text = (this[field] as? JsonPrimitive)?.content
            ?: throw DappException(
                DappErrorCode.INTERNAL,
                "prepare response is missing '$field'",
            )
        return try {
            Base64.getDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            throw DappException(
                DappErrorCode.INTERNAL,
                "prepare response field '$field' is not base64",
                cause = e,
            )
        }
    }

    private companion object {
        const val PREPARE_PATH = "/v2/interactive-submission/prepare"
    }
}

/**
 * `signMessage` backed by a [SigningDriver].
 *
 * The signature is over [DappSignMessage.signingBytes] — the message behind a
 * fixed domain-separation prefix — not the raw message, so a `signMessage`
 * signature can never be mistaken for a signature over a transaction hash the
 * same key also produces. The signature is returned base64-encoded in the
 * driver's native format (DER for ECDSA, raw for Ed25519). A dApp verifying it
 * must reconstruct the same signing bytes; see the sign-in reference example.
 *
 * [DappSession] additionally gates every call behind user approval and a rate
 * limit.
 */
public class SigningDriverMessageSigner(
    private val signer: SigningDriver,
) : DappMessageSigner {
    override suspend fun sign(account: DappWallet, message: String): String {
        // Domain-separated, not the raw message — see DappSignMessage. A dApp
        // verifying this signature must apply DappSignMessage.signingBytes too.
        val signature = signer.sign(DappSignMessage.signingBytes(message))
        return Base64.getEncoder().encodeToString(signature.signature.toByteArray())
    }
}
