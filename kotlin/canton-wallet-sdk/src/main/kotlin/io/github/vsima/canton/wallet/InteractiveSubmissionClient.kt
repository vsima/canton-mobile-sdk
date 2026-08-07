// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet

import com.daml.ledger.api.v2.CommandsOuterClass
import com.daml.ledger.api.v2.interactive.InteractiveSubmissionServiceGrpcKt
import com.daml.ledger.api.v2.interactive.InteractiveSubmissionServiceOuterClass.ExecuteSubmissionRequest
import com.daml.ledger.api.v2.interactive.InteractiveSubmissionServiceOuterClass.PartySignatures
import com.daml.ledger.api.v2.interactive.InteractiveSubmissionServiceOuterClass.PrepareSubmissionRequest
import com.daml.ledger.api.v2.interactive.InteractiveSubmissionServiceOuterClass.PrepareSubmissionResponse
import com.daml.ledger.api.v2.interactive.InteractiveSubmissionServiceOuterClass.SinglePartySignatures
import io.grpc.Channel
import java.util.UUID

/**
 * The prepare → sign → execute flow for externally-signed transactions
 * (Interactive Submission Service).
 *
 * WP1 scaffold. Working: prepare, sign-and-execute round trip. TODO before
 * 1.0:
 *  - client-side re-computation and verification of `prepared_transaction_hash`
 *    (never trust the node's hash blindly — recompute per the hashing scheme
 *    in interactive_submission_common_data.proto)
 *  - completion tracking (wait for the command's completion event)
 *  - command deduplication config parity with [io.github.vsima.canton.CommandSubmission]
 */
public class InteractiveSubmissionClient(channel: Channel) {

    private val stub =
        InteractiveSubmissionServiceGrpcKt.InteractiveSubmissionServiceCoroutineStub(channel)

    public suspend fun prepare(
        commands: List<CommandsOuterClass.Command>,
        actAs: String,
        synchronizerId: String,
        userId: String? = null,
        commandId: String = UUID.randomUUID().toString(),
        disclosedContracts: List<CommandsOuterClass.DisclosedContract> = emptyList(),
    ): PrepareSubmissionResponse =
        stub.prepareSubmission(
            PrepareSubmissionRequest.newBuilder()
                .addAllCommands(commands)
                .addActAs(actAs)
                .setSynchronizerId(synchronizerId)
                .setCommandId(commandId)
                .apply { userId?.let { setUserId(it) } }
                .addAllDisclosedContracts(disclosedContracts)
                .setVerboseHashing(false)
                .build()
        )

    /**
     * Signs the prepared transaction hash with [driver] and executes.
     * The signature's `signed_by` fingerprint must identify the party's
     * registered key — pass the fingerprint returned at party onboarding.
     */
    public suspend fun signAndExecute(
        prepared: PrepareSubmissionResponse,
        driver: SigningDriver,
        partyId: String,
        keyFingerprint: String,
        userId: String? = null,
        submissionId: String = UUID.randomUUID().toString(),
    ) {
        val signature = driver.sign(prepared.preparedTransactionHash.toByteArray())
            .toBuilder()
            .setSignedBy(keyFingerprint)
            .build()

        stub.executeSubmission(
            ExecuteSubmissionRequest.newBuilder()
                .setPreparedTransaction(prepared.preparedTransaction)
                .setPartySignatures(
                    PartySignatures.newBuilder().addSignatures(
                        SinglePartySignatures.newBuilder()
                            .setParty(partyId)
                            .addSignatures(signature)
                    )
                )
                .setHashingSchemeVersion(prepared.hashingSchemeVersion)
                .setSubmissionId(submissionId)
                .apply { userId?.let { setUserId(it) } }
                .build()
        )
    }
}
