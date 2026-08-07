// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton

import com.daml.ledger.api.v2.TransactionFilterOuterClass
import com.daml.ledger.api.v2.UpdateServiceOuterClass.GetUpdatesRequest

/**
 * A subscription to the ledger update stream for a set of parties.
 *
 * Subscribes to all templates visible to [parties] (wildcard filter). For
 * template- or interface-scoped filters, use the generated
 * `UpdateServiceGrpcKt` stub directly.
 */
public data class UpdateSubscription(
    /** Parties whose visible events are streamed. */
    val parties: List<String>,
    /** Stream updates with offsets strictly greater than this. */
    val beginExclusive: Long,
    /** Complete the stream at this offset; stream forever if null. */
    val endInclusive: Long? = null,
    /** Deliver LEDGER_EFFECTS shape (exercised events) instead of ACS_DELTA. */
    val ledgerEffects: Boolean = false,
    /** Also stream contract reassignments between synchronizers. */
    val includeReassignments: Boolean = true,
    /** Include field labels in values. */
    val verbose: Boolean = true,
) {
    init {
        require(parties.isNotEmpty()) { "parties must not be empty" }
    }

    internal fun toRequest(begin: Long): GetUpdatesRequest {
        val eventFormat = wildcardEventFormat(parties, verbose)
        val updateFormat = TransactionFilterOuterClass.UpdateFormat.newBuilder()
            .setIncludeTransactions(
                TransactionFilterOuterClass.TransactionFormat.newBuilder()
                    .setEventFormat(eventFormat)
                    .setTransactionShape(
                        if (ledgerEffects) {
                            TransactionFilterOuterClass.TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS
                        } else {
                            TransactionFilterOuterClass.TransactionShape.TRANSACTION_SHAPE_ACS_DELTA
                        }
                    )
            )
            .apply {
                if (this@UpdateSubscription.includeReassignments) setIncludeReassignments(eventFormat)
            }
            .build()
        return GetUpdatesRequest.newBuilder()
            .setBeginExclusive(begin)
            .apply { this@UpdateSubscription.endInclusive?.let { setEndInclusive(it) } }
            .setUpdateFormat(updateFormat)
            .build()
    }
}

/** Wildcard (all templates) event format for [parties]. */
internal fun wildcardEventFormat(
    parties: List<String>,
    verbose: Boolean,
): TransactionFilterOuterClass.EventFormat {
    val wildcard = TransactionFilterOuterClass.Filters.newBuilder()
        .addCumulative(
            TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                .setWildcardFilter(TransactionFilterOuterClass.WildcardFilter.getDefaultInstance())
        )
        .build()
    return TransactionFilterOuterClass.EventFormat.newBuilder()
        .putAllFiltersByParty(parties.associateWith { wildcard })
        .setVerbose(verbose)
        .build()
}
