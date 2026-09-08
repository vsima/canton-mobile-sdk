// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// Per-peer limits a wallet enforces on a dApp's transactions, checked before
/// anything reaches the approval sheet.
///
/// Every field is off by default, so a session with no policy behaves exactly
/// as before: each transaction asks the human. The caps are *hard*: a request
/// outside them is refused without raising the sheet, which is what makes a
/// policy a pre-commitment ("this dApp can never move more than X") and what
/// stops an agent from farming approvals until reflex taps one through.
/// The wallet's owner can always widen the policy in the app.
///
/// Amount limits are per instrument: `dailyCap = 25` means at most 25 of
/// *each* allowed instrument in any rolling 24h window, tracked from the
/// ``SpendLedger``'s receipts. Only transactions the wallet can parse as a
/// single token-standard transfer (``DappCommandSummary/transferOf(_:)``)
/// count against the caps or qualify for auto-approval; anything unrecognised
/// is never refused by amount and never auto-approved. It goes to the human,
/// flagged, and the human is the gate.
///
/// ``autoApproveBelow`` is the one field that *removes* a sheet: a parsed
/// transfer at or under it (and inside every other limit) is approved without
/// asking, and the receipt records that it was. Off by default; a wallet UI
/// should treat enabling it as an explicit, per-peer opt-in.
public struct DappSpendPolicy: Sendable, Equatable {
    /// Largest single transfer, per request. Nil = no cap.
    public var maxPerTransaction: Decimal?
    /// Largest total per instrument in any rolling 24h window. Nil = no cap.
    public var dailyCap: Decimal?
    /// Instruments this peer may move. Nil = any.
    public var allowedInstruments: Set<String>?
    /// Receiving parties this peer may pay. Nil = any.
    public var allowedReceivers: Set<String>?
    /// Minimum gap between transaction requests, seconds. 0 = no rate limit.
    public var minRequestInterval: TimeInterval
    /// Auto-approve parsed transfers at or below this amount, without the
    /// sheet. Nil = off: every transaction asks the human. Always bounded by
    /// the caps above.
    public var autoApproveBelow: Decimal?

    /// Creates a policy; every limit is off unless set.
    public init(
        maxPerTransaction: Decimal? = nil,
        dailyCap: Decimal? = nil,
        allowedInstruments: Set<String>? = nil,
        allowedReceivers: Set<String>? = nil,
        minRequestInterval: TimeInterval = 0,
        autoApproveBelow: Decimal? = nil
    ) {
        self.maxPerTransaction = maxPerTransaction
        self.dailyCap = dailyCap
        self.allowedInstruments = allowedInstruments
        self.allowedReceivers = allowedReceivers
        self.minRequestInterval = minRequestInterval
        self.autoApproveBelow = autoApproveBelow
    }

    /// What the policy says about one submission.
    ///
    /// `summary` is nil when the submission is not a single recognisable
    /// token-standard transfer; `spentLast24h` is the instrument's receipt
    /// total for the trailing window, from the session's ``SpendLedger``.
    public func decide(summary: DappTransferSummary?, spentLast24h: Decimal) -> SpendDecision {
        // Unrecognised, or a transfer whose amount does not parse as a
        // positive number: the caps cannot see it, so the human must.
        // Never auto-approved.
        guard let summary, let amount = Self.strictDecimal(summary.amount), amount > 0 else {
            return .askHuman
        }
        if let allowedInstruments, !allowedInstruments.contains(summary.instrumentId) {
            return .refuse(reason: "instrument \(summary.instrumentId) is not allowed for this dApp")
        }
        if let allowedReceivers, !allowedReceivers.contains(summary.receiver) {
            return .refuse(reason: "receiver \(summary.receiver) is not allowed for this dApp")
        }
        if let maxPerTransaction, amount > maxPerTransaction {
            return .refuse(reason: "amount \(amount) exceeds the per-transaction cap of \(maxPerTransaction)")
        }
        if let dailyCap, spentLast24h + amount > dailyCap {
            return .refuse(
                reason: "amount \(amount) would exceed the daily cap of \(dailyCap) "
                    + "(\(spentLast24h) already spent in the last 24h)"
            )
        }
        if let autoApproveBelow, amount <= autoApproveBelow {
            return .autoApprove
        }
        return .askHuman
    }

    /// `Decimal(string:)` accepts trailing garbage ("5abc" parses as 5), so
    /// amounts are validated to the token standard's decimal shape first;
    /// anything else is not a number the caps can reason about.
    static func strictDecimal(_ text: String) -> Decimal? {
        guard text.wholeMatch(of: /-?\d+(\.\d+)?/) != nil else { return nil }
        return Decimal(string: text)
    }
}

/// The policy's verdict on one transaction request.
public enum SpendDecision: Sendable, Equatable {
    /// Within policy; raise the approval sheet as usual.
    case askHuman
    /// Within policy and under the auto-approve bound; skip the sheet.
    case autoApprove
    /// Outside policy; refuse without asking. The reason goes back to the dApp.
    case refuse(reason: String)
}

/// One approved, executed spend by one peer. The ledger's receipts are the
/// source of truth for the rolling daily cap and for the app's receipts UI,
/// which is why they are written only after execution succeeds.
public struct SpendReceipt: Sendable, Equatable {
    /// ``DappPeer/id`` of the session that spent.
    public var peerId: String
    /// When the spend executed, on the session's wall clock.
    public var at: Date
    /// The instrument moved, as the dApp named it (e.g. `Amulet`).
    public var instrumentId: String
    /// The amount moved, parsed strictly from the command.
    public var amount: Decimal
    /// The receiving party.
    public var receiver: String
    /// True when the policy approved without a sheet.
    public var autoApproved: Bool
    /// The submission's command id, for matching against the ledger.
    public var commandId: String

    /// Creates a receipt.
    public init(
        peerId: String,
        at: Date,
        instrumentId: String,
        amount: Decimal,
        receiver: String,
        autoApproved: Bool,
        commandId: String
    ) {
        self.peerId = peerId
        self.at = at
        self.instrumentId = instrumentId
        self.amount = amount
        self.receiver = receiver
        self.autoApproved = autoApproved
        self.commandId = commandId
    }
}

/// Where a wallet persists ``SpendReceipt``s.
///
/// The session writes and reads through this seam; the app owns the store.
/// The same fail-loud principle as the wallet store applies to
/// implementations: an unreadable store must throw, never return an empty
/// list, because an empty list resets every cap.
public protocol SpendLedger: Sendable {
    /// Records an executed spend.
    func append(_ receipt: SpendReceipt) async throws
    /// Receipts for `peerId` with `at >= since`, oldest first.
    func receiptsSince(peerId: String, since: Date) async throws -> [SpendReceipt]
}

/// A process-lifetime ``SpendLedger``, the default when a host wires a policy
/// without persistence. Fine for tests and demos; a real wallet must persist,
/// or every restart resets the daily cap.
public actor InMemorySpendLedger: SpendLedger {
    private var receipts: [SpendReceipt] = []

    /// An empty ledger.
    public init() {}

    /// Appends in memory.
    public func append(_ receipt: SpendReceipt) {
        receipts.append(receipt)
    }

    /// The in-memory receipts for `peerId` with `at >= since`, in insertion
    /// order.
    public func receiptsSince(peerId: String, since: Date) -> [SpendReceipt] {
        receipts.filter { $0.peerId == peerId && $0.at >= since }
    }
}
