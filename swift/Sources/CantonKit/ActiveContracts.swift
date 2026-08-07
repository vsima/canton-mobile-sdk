// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonLedgerAPI

/// One contract from the active contract set.
public struct ActiveContract: Sendable {
    /// The event that created the contract (arguments, template id, contract id).
    public let createdEvent: Com_Daml_Ledger_Api_V2_CreatedEvent

    /// The synchronizer the contract is currently assigned to.
    public let synchronizerId: String

    /// Incremented each time the contract moves between synchronizers.
    public let reassignmentCounter: UInt64
}

/// The active contract set at ``offset``, the anchor for a gap-free state
/// sync: apply ``contracts`` to local state, then consume
/// `updates(UpdateSubscription(beginExclusive: offset, ...))`.
public struct ActiveContractsSnapshot: Sendable {
    public let offset: Int64
    public let contracts: [ActiveContract]
}
