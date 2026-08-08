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
 * Before signing, [signAndExecute] recomputes `prepared_transaction_hash`
 * locally from the raw `PreparedTransaction` proto ([PreparedTransactionHash])
 * and refuses to sign on mismatch — the node's hash is never trusted blindly.
 *
 * TODO before 1.0:
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
     *
     * Unless [verifyHash] is disabled, the hash is first recomputed locally
     * from the raw `PreparedTransaction` proto and compared against the
     * node-supplied `prepared_transaction_hash`
     * ([PreparedTransactionHash.verify]); a
     * [PreparedTransactionHashMismatchException] aborts the submission
     * before anything is signed. Only disable this against a participant
     * you fully trust.
     */
    public suspend fun signAndExecute(
        prepared: PrepareSubmissionResponse,
        driver: SigningDriver,
        partyId: String,
        keyFingerprint: String,
        userId: String? = null,
        submissionId: String = UUID.randomUUID().toString(),
        verifyHash: Boolean = true,
    ) {
        if (verifyHash) PreparedTransactionHash.verify(prepared)

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
