// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet

import com.daml.ledger.api.v2.CommandCompletionServiceGrpcKt
import com.daml.ledger.api.v2.CommandCompletionServiceOuterClass.CompletionStreamRequest
import com.daml.ledger.api.v2.CommandsOuterClass
import com.daml.ledger.api.v2.StateServiceGrpcKt
import com.daml.ledger.api.v2.StateServiceOuterClass.GetLedgerEndRequest
import com.daml.ledger.api.v2.interactive.InteractiveSubmissionServiceGrpcKt
import com.daml.ledger.api.v2.interactive.InteractiveSubmissionServiceOuterClass.ExecuteSubmissionRequest
import com.daml.ledger.api.v2.interactive.InteractiveSubmissionServiceOuterClass.PartySignatures
import com.daml.ledger.api.v2.interactive.InteractiveSubmissionServiceOuterClass.PrepareSubmissionRequest
import com.daml.ledger.api.v2.interactive.InteractiveSubmissionServiceOuterClass.PrepareSubmissionResponse
import com.daml.ledger.api.v2.interactive.InteractiveSubmissionServiceOuterClass.SinglePartySignatures
import io.github.vsima.canton.CantonError
import io.github.vsima.canton.CantonException
import io.grpc.Channel
import java.util.UUID
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull

/**
 * The ledger's completion for an executed submission — the proof that the
 * command was actually committed, not merely accepted for execution.
 */
public data class SubmissionCompletion(
    /** Update id of the resulting transaction; correlate with update streams. */
    val updateId: String,
    /** Offset of the completion on the participant. */
    val offset: Long,
)

/**
 * The prepare → sign → execute flow for externally-signed transactions
 * (Interactive Submission Service).
 *
 * Before signing, [signAndExecute] recomputes `prepared_transaction_hash`
 * locally from the raw `PreparedTransaction` proto ([PreparedTransactionHash])
 * and refuses to sign on mismatch — the node's hash is never trusted blindly.
 *
 * `ExecuteSubmission` only acknowledges that the participant accepted the
 * submission; commitment (or rejection — contention, time bounds) is
 * reported asynchronously on the completion stream. Use
 * [signAndExecuteAndWait] to await that verdict.
 *
 * TODO before 1.0:
 *  - command deduplication config parity with [io.github.vsima.canton.CommandSubmission]
 */
public class InteractiveSubmissionClient(channel: Channel) {

    private val stub =
        InteractiveSubmissionServiceGrpcKt.InteractiveSubmissionServiceCoroutineStub(channel)
    private val state = StateServiceGrpcKt.StateServiceCoroutineStub(channel)
    private val completions =
        CommandCompletionServiceGrpcKt.CommandCompletionServiceCoroutineStub(channel)

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

    /**
     * [signAndExecute], then waits for the command's completion event — the
     * only way to learn the outcome of an interactive submission, since
     * `ExecuteSubmission` acknowledges acceptance, not commitment.
     *
     * The ledger end is recorded before executing and the completion stream
     * replays from there, so the completion cannot be missed; it is matched
     * by [userId] + [submissionId]. On success returns the completion's
     * update id and offset; if the ledger rejected the command (contention,
     * time bounds), throws [CantonException] carrying the typed
     * [CantonError] decoded from the completion's `google.rpc.Status`
     * details.
     *
     * Suspends until the participant emits the completion; wrap in
     * `withTimeout` to bound the wait.
     */
    public suspend fun signAndExecuteAndWait(
        prepared: PrepareSubmissionResponse,
        driver: SigningDriver,
        partyId: String,
        keyFingerprint: String,
        userId: String? = null,
        submissionId: String = UUID.randomUUID().toString(),
        verifyHash: Boolean = true,
    ): SubmissionCompletion {
        // Recorded before executing: replaying completions from here
        // guarantees ours cannot slip past between execute and subscribe.
        val ledgerEndBeforeExecute =
            state.getLedgerEnd(GetLedgerEndRequest.getDefaultInstance()).offset

        signAndExecute(prepared, driver, partyId, keyFingerprint, userId, submissionId, verifyHash)

        val completion = completions.completionStream(
            CompletionStreamRequest.newBuilder()
                .apply { userId?.let { setUserId(it) } }
                .addParties(partyId)
                .setBeginExclusive(ledgerEndBeforeExecute)
                .build()
        )
            .mapNotNull { response -> response.takeIf { it.hasCompletion() }?.completion }
            .firstOrNull { it.submissionId == submissionId && (userId == null || it.userId == userId) }
            ?: error("completion stream ended before the completion for submission id $submissionId")

        if (completion.hasStatus() && completion.status.code != 0) { // 0 = google.rpc.Code.OK
            throw CantonException(CantonError.from(completion.status))
        }
        return SubmissionCompletion(updateId = completion.updateId, offset = completion.offset)
    }
}
