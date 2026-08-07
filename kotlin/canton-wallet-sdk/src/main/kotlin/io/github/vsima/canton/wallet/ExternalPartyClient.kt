// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet

import com.daml.ledger.api.v2.StateServiceGrpcKt
import com.daml.ledger.api.v2.StateServiceOuterClass.GetConnectedSynchronizersRequest
import com.daml.ledger.api.v2.admin.PartyManagementServiceGrpcKt
import com.daml.ledger.api.v2.admin.PartyManagementServiceOuterClass.AllocateExternalPartyRequest
import com.daml.ledger.api.v2.admin.PartyManagementServiceOuterClass.GenerateExternalPartyTopologyRequest
import io.grpc.Channel

/** An external party whose signing key never left the [SigningDriver]. */
public data class AllocatedExternalParty(
    val partyId: String,
    val publicKeyFingerprint: String,
)

/**
 * Onboards external parties: parties whose signing keys are held outside the
 * participant node (device enclave, custody provider), so the node cannot act
 * for them unilaterally.
 *
 * Flow (all heavy lifting is server-side):
 *  1. `GenerateExternalPartyTopology` — the participant builds the topology
 *     transactions and returns the multi-hash to sign plus the canonical
 *     fingerprint of the supplied public key.
 *  2. The [SigningDriver] signs the multi-hash.
 *  3. `AllocateExternalParty` — submits the transactions with the multi-hash
 *     signature; the participant waits for allocation on the synchronizer.
 */
public class ExternalPartyClient(channel: Channel) {

    private val partyManagement =
        PartyManagementServiceGrpcKt.PartyManagementServiceCoroutineStub(channel)
    private val state = StateServiceGrpcKt.StateServiceCoroutineStub(channel)

    /** Synchronizers this participant is connected to (for [allocate]'s `synchronizerId`). */
    public suspend fun connectedSynchronizers(): List<String> =
        state.getConnectedSynchronizers(GetConnectedSynchronizersRequest.getDefaultInstance())
            .connectedSynchronizersList.map { it.synchronizerId }

    public suspend fun allocate(
        driver: SigningDriver,
        synchronizerId: String,
        partyHint: String,
        userId: String? = null,
    ): AllocatedExternalParty {
        val generated = partyManagement.generateExternalPartyTopology(
            GenerateExternalPartyTopologyRequest.newBuilder()
                .setSynchronizer(synchronizerId)
                .setPartyHint(partyHint)
                .setPublicKey(driver.publicKey())
                .build()
        )

        val multiHashSignature = driver.sign(generated.multiHash.toByteArray())
            .toBuilder()
            .setSignedBy(generated.publicKeyFingerprint)
            .build()

        val request = AllocateExternalPartyRequest.newBuilder()
            .setSynchronizer(synchronizerId)
            .addAllOnboardingTransactions(
                generated.topologyTransactionsList.map {
                    AllocateExternalPartyRequest.SignedTransaction.newBuilder()
                        .setTransaction(it)
                        .build()
                }
            )
            .addMultiHashSignatures(multiHashSignature)
            .apply { userId?.let { setUserId(it) } }
            .build()

        val allocated = partyManagement.allocateExternalParty(request)
        return AllocatedExternalParty(
            partyId = allocated.partyId,
            publicKeyFingerprint = generated.publicKeyFingerprint,
        )
    }
}
