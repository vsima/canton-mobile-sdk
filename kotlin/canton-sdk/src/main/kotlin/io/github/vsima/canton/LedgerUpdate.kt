package io.github.vsima.canton

import com.daml.ledger.api.v2.ReassignmentOuterClass
import com.daml.ledger.api.v2.TopologyTransactionOuterClass
import com.daml.ledger.api.v2.TransactionOuterClass
import com.daml.ledger.api.v2.UpdateServiceOuterClass.GetUpdatesResponse

/**
 * One element of the ledger update stream. Every update carries the
 * participant-local [offset] that totally orders it; persist the offset of
 * the last update you processed and pass it as
 * [UpdateSubscription.beginExclusive] to resume without gaps or duplicates.
 */
public sealed interface LedgerUpdate {
    public val offset: Long

    /** A committed Daml transaction. */
    public data class Transaction(
        val transaction: TransactionOuterClass.Transaction,
    ) : LedgerUpdate {
        override val offset: Long get() = transaction.offset
    }

    /** A contract moving between synchronizers. */
    public data class Reassignment(
        val reassignment: ReassignmentOuterClass.Reassignment,
    ) : LedgerUpdate {
        override val offset: Long get() = reassignment.offset
    }

    /** A party (de)activation on the participant. */
    public data class TopologyTransaction(
        val topologyTransaction: TopologyTransactionOuterClass.TopologyTransaction,
    ) : LedgerUpdate {
        override val offset: Long get() = topologyTransaction.offset
    }

    /**
     * A cursor keep-alive: no matching event, but the resume offset advanced.
     */
    public data class Checkpoint(override val offset: Long) : LedgerUpdate

    public companion object {
        internal fun from(response: GetUpdatesResponse): LedgerUpdate? = when {
            response.hasTransaction() -> Transaction(response.transaction)
            response.hasReassignment() -> Reassignment(response.reassignment)
            response.hasTopologyTransaction() -> TopologyTransaction(response.topologyTransaction)
            response.hasOffsetCheckpoint() -> Checkpoint(response.offsetCheckpoint.offset)
            else -> null // unknown future update kind; skip
        }
    }
}
