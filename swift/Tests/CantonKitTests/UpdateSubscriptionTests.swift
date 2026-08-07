import CantonLedgerAPI
import Testing
@testable import CantonKit

@Suite struct UpdateSubscriptionTests {
    @Test func buildsWildcardRequestFromCursor() {
        let subscription = UpdateSubscription(
            parties: ["alice::ns", "bob::ns"],
            beginExclusive: 7,
            endInclusive: 42
        )
        let request = subscription.request(from: 9)

        #expect(request.beginExclusive == 9) // resume cursor wins over beginExclusive
        #expect(request.endInclusive == 42)
        #expect(request.updateFormat.includeTransactions.transactionShape == .acsDelta)
        #expect(request.updateFormat.includeTransactions.eventFormat.verbose)
        #expect(
            Set(request.updateFormat.includeTransactions.eventFormat.filtersByParty.keys)
                == ["alice::ns", "bob::ns"]
        )
        #expect(request.updateFormat.hasIncludeReassignments)
    }

    @Test func ledgerEffectsShapeAndNoReassignments() {
        let subscription = UpdateSubscription(
            parties: ["alice::ns"],
            beginExclusive: 0,
            ledgerEffects: true,
            includeReassignments: false
        )
        let request = subscription.request(from: 0)
        #expect(request.updateFormat.includeTransactions.transactionShape == .ledgerEffects)
        #expect(!request.updateFormat.hasIncludeReassignments)
        #expect(!request.hasEndInclusive)
    }

    @Test func mapsUpdateResponses() {
        var transaction = Com_Daml_Ledger_Api_V2_GetUpdatesResponse()
        transaction.transaction.offset = 5
        #expect(LedgerUpdate(transaction)?.offset == 5)

        var checkpoint = Com_Daml_Ledger_Api_V2_GetUpdatesResponse()
        checkpoint.offsetCheckpoint.offset = 9
        guard case .checkpoint(let offset)? = LedgerUpdate(checkpoint) else {
            Issue.record("expected checkpoint")
            return
        }
        #expect(offset == 9)
    }
}
