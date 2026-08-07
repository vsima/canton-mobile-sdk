import CantonLedgerAPI

/// One element of the ledger update stream. Every update carries the
/// participant-local ``offset`` that totally orders it; persist the offset of
/// the last update you processed and pass it as
/// ``UpdateSubscription/beginExclusive`` to resume without gaps or duplicates.
public enum LedgerUpdate: Sendable {
    /// A committed Daml transaction.
    case transaction(Com_Daml_Ledger_Api_V2_Transaction)

    /// A contract moving between synchronizers.
    case reassignment(Com_Daml_Ledger_Api_V2_Reassignment)

    /// A party (de)activation on the participant.
    case topologyTransaction(Com_Daml_Ledger_Api_V2_TopologyTransaction)

    /// A cursor keep-alive: no matching event, but the resume offset advanced.
    case checkpoint(offset: Int64)

    public var offset: Int64 {
        switch self {
        case .transaction(let transaction): transaction.offset
        case .reassignment(let reassignment): reassignment.offset
        case .topologyTransaction(let topology): topology.offset
        case .checkpoint(let offset): offset
        }
    }

    init?(_ response: Com_Daml_Ledger_Api_V2_GetUpdatesResponse) {
        switch response.update {
        case .transaction(let transaction):
            self = .transaction(transaction)
        case .reassignment(let reassignment):
            self = .reassignment(reassignment)
        case .topologyTransaction(let topology):
            self = .topologyTransaction(topology)
        case .offsetCheckpoint(let checkpoint):
            self = .checkpoint(offset: checkpoint.offset)
        case .none:
            return nil // unknown future update kind; skip
        }
    }
}
