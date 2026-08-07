// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton

import com.daml.ledger.api.v2.EventOuterClass

/**
 * One contract from the active contract set.
 *
 * @property createdEvent the event that created the contract (arguments,
 *   template id, contract id).
 * @property synchronizerId the synchronizer the contract is currently
 *   assigned to.
 * @property reassignmentCounter incremented each time the contract moves
 *   between synchronizers.
 */
public data class ActiveContract(
    val createdEvent: EventOuterClass.CreatedEvent,
    val synchronizerId: String,
    val reassignmentCounter: Long,
)

/**
 * The active contract set at [offset], the anchor for a gap-free state sync:
 * apply [contracts] to local state, then consume
 * `updates(UpdateSubscription(beginExclusive = offset, ...))`.
 */
public data class ActiveContractsSnapshot(
    val offset: Long,
    val contracts: List<ActiveContract>,
)
