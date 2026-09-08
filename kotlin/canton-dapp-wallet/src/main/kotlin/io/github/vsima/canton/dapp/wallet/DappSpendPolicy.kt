// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp.wallet

import java.math.BigDecimal
import java.time.Instant
import kotlin.time.Duration
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-peer limits a wallet enforces on a dApp's transactions, checked before
 * anything reaches the approval sheet.
 *
 * Every field is off by default, so a session with no policy behaves exactly
 * as before: each transaction asks the human. The caps are *hard*: a request
 * outside them is refused without raising the sheet, which is what makes a
 * policy a pre-commitment ("this dApp can never move more than X") and what
 * stops an agent from farming approvals until reflex taps one through.
 * The wallet's owner can always widen the policy in the app.
 *
 * Amount limits are per instrument: `dailyCap = 25` means at most 25 of
 * *each* allowed instrument in any rolling 24h window, tracked from the
 * [SpendLedger]'s receipts. Only transactions the wallet can parse as a
 * single token-standard transfer ([DappCommandSummary.transferOf]) count
 * against the caps or qualify for auto-approval; anything unrecognised is
 * never refused by amount and never auto-approved. It goes to the human,
 * flagged, and the human is the gate.
 *
 * [autoApproveBelow] is the one field that *removes* a sheet: a parsed
 * transfer at or under it (and inside every other limit) is approved without
 * asking, and the receipt records that it was. Off by default; a wallet UI
 * should treat enabling it as an explicit, per-peer opt-in.
 */
public data class DappSpendPolicy(
    /** Largest single transfer, per request. Null = no cap. */
    val maxPerTransaction: BigDecimal? = null,
    /** Largest total per instrument in any rolling 24h window. Null = no cap. */
    val dailyCap: BigDecimal? = null,
    /** Instruments this peer may move. Null = any. */
    val allowedInstruments: Set<String>? = null,
    /** Receiving parties this peer may pay. Null = any. */
    val allowedReceivers: Set<String>? = null,
    /** Minimum gap between transaction requests. ZERO = no rate limit. */
    val minRequestInterval: Duration = Duration.ZERO,
    /**
     * Auto-approve parsed transfers at or below this amount, without the
     * sheet. Null = off: every transaction asks the human. Always bounded by
     * the caps above.
     */
    val autoApproveBelow: BigDecimal? = null,
) {
    /**
     * What the policy says about one submission.
     *
     * [summary] is null when the submission is not a single recognisable
     * token-standard transfer; [spentLast24h] is the instrument's receipt
     * total for the trailing window, from the session's [SpendLedger].
     */
    public fun decide(summary: DappTransferSummary?, spentLast24h: BigDecimal): SpendDecision {
        // Unrecognised, or a transfer whose amount does not parse as a
        // positive number: the caps cannot see it, so the human must.
        // Never auto-approved.
        if (summary == null) return SpendDecision.AskHuman
        val amount = summary.amount.toBigDecimalOrNull() ?: return SpendDecision.AskHuman
        if (amount.signum() <= 0) return SpendDecision.AskHuman
        if (allowedInstruments != null && summary.instrumentId !in allowedInstruments) {
            return SpendDecision.Refuse(
                "instrument ${summary.instrumentId} is not allowed for this dApp",
            )
        }
        if (allowedReceivers != null && summary.receiver !in allowedReceivers) {
            return SpendDecision.Refuse(
                "receiver ${summary.receiver} is not allowed for this dApp",
            )
        }
        if (maxPerTransaction != null && amount > maxPerTransaction) {
            return SpendDecision.Refuse(
                "amount $amount exceeds the per-transaction cap of ${maxPerTransaction.toPlainString()}",
            )
        }
        if (dailyCap != null && spentLast24h + amount > dailyCap) {
            return SpendDecision.Refuse(
                "amount $amount would exceed the daily cap of ${dailyCap.toPlainString()} " +
                    "(${spentLast24h.toPlainString()} already spent in the last 24h)",
            )
        }
        if (autoApproveBelow != null && amount <= autoApproveBelow) {
            return SpendDecision.AutoApprove
        }
        return SpendDecision.AskHuman
    }

    private fun String.toBigDecimalOrNull(): BigDecimal? = try {
        BigDecimal(this)
    } catch (_: NumberFormatException) {
        null
    }
}

/** The policy's verdict on one transaction request. */
public sealed interface SpendDecision {
    /** Within policy; raise the approval sheet as usual. */
    public data object AskHuman : SpendDecision

    /** Within policy and under the auto-approve bound; skip the sheet. */
    public data object AutoApprove : SpendDecision

    /** Outside policy; refuse without asking. [reason] goes back to the dApp. */
    public data class Refuse(val reason: String) : SpendDecision
}

/**
 * One approved, executed spend by one peer. The ledger's receipts are the
 * source of truth for the rolling daily cap and for the app's receipts UI,
 * which is why they are written only after execution succeeds.
 */
public data class SpendReceipt(
    /** [DappPeer.id] of the session that spent. */
    val peerId: String,
    val at: Instant,
    val instrumentId: String,
    val amount: BigDecimal,
    val receiver: String,
    /** True when the policy approved without a sheet. */
    val autoApproved: Boolean,
    val commandId: String,
)

/**
 * Where a wallet persists [SpendReceipt]s.
 *
 * The session writes and reads through this seam; the app owns the store.
 * The same fail-loud principle as the wallet store applies to
 * implementations: an unreadable store must throw, never return an empty
 * list, because an empty list resets every cap.
 */
public interface SpendLedger {
    /**
     * Records one executed spend. The session calls this after execution
     * succeeds, never before.
     */
    public suspend fun append(receipt: SpendReceipt)

    /** Receipts for [peerId] with `at >= since`, oldest first. */
    public suspend fun receiptsSince(peerId: String, since: Instant): List<SpendReceipt>
}

/**
 * A process-lifetime [SpendLedger], the default when a host wires a policy
 * without persistence. Fine for tests and demos; a real wallet must persist,
 * or every restart resets the daily cap.
 */
public class InMemorySpendLedger : SpendLedger {
    private val receipts = mutableListOf<SpendReceipt>()
    private val lock = Mutex()

    override suspend fun append(receipt: SpendReceipt) {
        lock.withLock { receipts.add(receipt) }
    }

    override suspend fun receiptsSince(peerId: String, since: Instant): List<SpendReceipt> =
        lock.withLock {
            receipts.filter { it.peerId == peerId && !it.at.isBefore(since) }
        }
}
