// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp.wallet

import java.time.Instant

/**
 * One thing a dApp did or tried to do, as the wallet's owner should see it.
 *
 * The spend policy's hard caps refuse without asking the approver, and auto-approval
 * executes without one; both are invisible at the moment they happen. This
 * feed is the counterweight: the session reports *every* notable event to
 * the host, approver asked or not, so the app can render an activity log and
 * notify on the silent outcomes. Nothing here feeds back into what gets
 * signed; it is a record, not a control.
 */
public data class DappActivity(
    /** [DappPeer.id] of the session the activity belongs to. */
    val peerId: String,
    /** [DappPeer.name], for display without a lookup. */
    val peerName: String,
    val at: Instant,
    val kind: Kind,
    /** The parsed transfer, when the activity concerns one. */
    val transfer: DappTransferSummary? = null,
    /** Refusal reason, decline reason, update id, or error detail. */
    val detail: String? = null,
) {
    /**
     * What happened. The kinds that never reach the approver — [TRANSACTION_AUTO_APPROVED],
     * [TRANSACTION_REFUSED], [TRANSACTION_RATE_LIMITED] — are the ones a host
     * should notify on.
     */
    public enum class Kind {
        /** The human shared accounts with this peer. */
        CONNECTED,

        /** The human declined the connection. */
        CONNECTION_DECLINED,

        /** The human approved a message signature (e.g. Sign-In with Canton). */
        MESSAGE_SIGNED,

        /** The human declined a message signature. */
        MESSAGE_DECLINED,

        /** A transaction was sent to the approver. */
        TRANSACTION_REQUESTED,

        /** The spend policy approved without asking the approver. */
        TRANSACTION_AUTO_APPROVED,

        /** The spend policy refused without asking the approver. [detail] says why. */
        TRANSACTION_REFUSED,

        /** The policy's rate limit refused a request without asking the approver. */
        TRANSACTION_RATE_LIMITED,

        /** The approver declined the transaction. */
        TRANSACTION_DECLINED,

        /** The transaction executed. [detail] carries the update id. */
        TRANSACTION_EXECUTED,

        /** The transaction failed after approval. */
        TRANSACTION_FAILED,
    }
}

/**
 * Receives every [DappActivity] a session produces, synchronously on the
 * session's path. Implementations must be quick and must not throw; the
 * session swallows observer exceptions, because a broken log must never
 * break a payment. Hosts persist and render; a local notification for the
 * kinds that never reach the approver (auto-approved, refused, rate-limited) is what keeps the
 * policy honest.
 */
public fun interface DappActivityObserver {
    /** Called once per activity, on the session's own path. Return quickly and do not throw. */
    public fun onActivity(activity: DappActivity)
}
