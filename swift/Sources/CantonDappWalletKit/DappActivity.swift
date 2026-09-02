// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// One thing a dApp did or tried to do, as the wallet's owner should see it.
///
/// The spend policy's hard caps refuse without a sheet, and auto-approval
/// executes without one; both are invisible at the moment they happen. This
/// feed is the counterweight: the session reports *every* notable event to
/// the host, sheet or no sheet, so the app can render an activity log and
/// notify on the silent outcomes. Nothing here feeds back into what gets
/// signed; it is a record, not a control.
public struct DappActivity: Sendable, Equatable {
    public enum Kind: Sendable, Equatable {
        /// The human shared accounts with this peer.
        case connected
        /// The human declined the connection.
        case connectionDeclined
        /// The human approved a message signature (e.g. Sign-In with Canton).
        case messageSigned
        /// The human declined a message signature.
        case messageDeclined
        /// A transaction raised the approval sheet.
        case transactionRequested
        /// The spend policy approved without a sheet.
        case transactionAutoApproved
        /// The spend policy refused without a sheet. `detail` says why.
        case transactionRefused
        /// The policy's rate limit refused a request without a sheet.
        case transactionRateLimited
        /// The human declined the transaction on the sheet.
        case transactionDeclined
        /// The transaction executed. `detail` carries the update id.
        case transactionExecuted
        /// The transaction failed after approval.
        case transactionFailed
    }

    /// ``DappPeer/id`` of the session the activity belongs to.
    public var peerId: String
    /// ``DappPeer/name``, for display without a lookup.
    public var peerName: String
    public var at: Date
    public var kind: Kind
    /// The parsed transfer, when the activity concerns one.
    public var transfer: DappTransferSummary?
    /// Refusal reason, decline reason, update id, or error detail.
    public var detail: String?

    public init(
        peerId: String,
        peerName: String,
        at: Date,
        kind: Kind,
        transfer: DappTransferSummary? = nil,
        detail: String? = nil
    ) {
        self.peerId = peerId
        self.peerName = peerName
        self.at = at
        self.kind = kind
        self.transfer = transfer
        self.detail = detail
    }
}

/// Receives every ``DappActivity`` a session produces, synchronously on the
/// session's path. Implementations must be quick; the closure cannot throw,
/// because a broken log must never break a payment. Hosts persist and
/// render; a local notification for the sheetless kinds (auto-approved,
/// refused, rate-limited) is what keeps the policy honest.
public typealias DappActivityObserver = @Sendable (DappActivity) -> Void
