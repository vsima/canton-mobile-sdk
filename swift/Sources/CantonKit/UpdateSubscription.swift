// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonLedgerAPI

/// A subscription to the ledger update stream for a set of parties.
///
/// Subscribes to all templates visible to ``parties`` (wildcard filter). For
/// template- or interface-scoped filters, use the generated update service
/// client directly.
public struct UpdateSubscription: Sendable {
    /// Parties whose visible events are streamed.
    public var parties: [String]

    /// Stream updates with offsets strictly greater than this.
    public var beginExclusive: Int64

    /// Complete the stream at this offset; stream forever if nil.
    public var endInclusive: Int64?

    /// Deliver LEDGER_EFFECTS shape (exercised events) instead of ACS_DELTA.
    public var ledgerEffects: Bool

    /// Also stream contract reassignments between synchronizers.
    public var includeReassignments: Bool

    /// Include field labels in values.
    public var verbose: Bool

    public init(
        parties: [String],
        beginExclusive: Int64,
        endInclusive: Int64? = nil,
        ledgerEffects: Bool = false,
        includeReassignments: Bool = true,
        verbose: Bool = true
    ) {
        precondition(!parties.isEmpty, "parties must not be empty")
        self.parties = parties
        self.beginExclusive = beginExclusive
        self.endInclusive = endInclusive
        self.ledgerEffects = ledgerEffects
        self.includeReassignments = includeReassignments
        self.verbose = verbose
    }

    func request(from begin: Int64) -> Com_Daml_Ledger_Api_V2_GetUpdatesRequest {
        let eventFormat = wildcardEventFormat(parties: parties, verbose: verbose)

        var transactionFormat = Com_Daml_Ledger_Api_V2_TransactionFormat()
        transactionFormat.eventFormat = eventFormat
        transactionFormat.transactionShape = ledgerEffects ? .ledgerEffects : .acsDelta

        var updateFormat = Com_Daml_Ledger_Api_V2_UpdateFormat()
        updateFormat.includeTransactions = transactionFormat
        if includeReassignments {
            updateFormat.includeReassignments = eventFormat
        }

        var request = Com_Daml_Ledger_Api_V2_GetUpdatesRequest()
        request.beginExclusive = begin
        if let endInclusive {
            request.endInclusive = endInclusive
        }
        request.updateFormat = updateFormat
        return request
    }
}

/// Wildcard (all templates) event format for `parties`.
func wildcardEventFormat(parties: [String], verbose: Bool) -> Com_Daml_Ledger_Api_V2_EventFormat {
    var wildcard = Com_Daml_Ledger_Api_V2_CumulativeFilter()
    wildcard.wildcardFilter = Com_Daml_Ledger_Api_V2_WildcardFilter()
    var filters = Com_Daml_Ledger_Api_V2_Filters()
    filters.cumulative = [wildcard]

    var eventFormat = Com_Daml_Ledger_Api_V2_EventFormat()
    eventFormat.filtersByParty = Dictionary(uniqueKeysWithValues: parties.map { ($0, filters) })
    eventFormat.verbose = verbose
    return eventFormat
}
