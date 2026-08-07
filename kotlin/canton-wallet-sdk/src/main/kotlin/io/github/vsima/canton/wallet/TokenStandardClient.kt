package io.github.vsima.canton.wallet

import java.math.BigDecimal

/**
 * CIP-0056 token standard client — WP2 skeleton.
 *
 * The surface mirrors `TokenStandardController` in Digital Asset's
 * TypeScript wallet SDK so migration docs can map 1:1. Beyond the Ledger
 * API, these operations need an HTTP client for the transfer-factory
 * Registry and Scan APIs (choice contexts, instrument metadata) — that
 * layer lands with this work package.
 *
 * Tracking: CIP-0112 (Token Standard V2 — batch settlement, account-based
 * holdings) will move this surface; build against V1, absorb V2.
 */
public class TokenStandardClient {

    public data class Holding(
        val contractId: String,
        val instrumentId: String,
        val amount: BigDecimal,
        val locked: Boolean,
    )

    public data class TransferInstruction(
        val contractId: String,
        val sender: String,
        val receiver: String,
        val instrumentId: String,
        val amount: BigDecimal,
    )

    public enum class TransferInstructionChoice { ACCEPT, REJECT, WITHDRAW }

    /** Active holding UTXOs for the party, from the ACS via the holding interface. */
    public suspend fun listHoldings(partyId: String): List<Holding> =
        TODO("WP2: query ACS by holding interface id")

    /** Two-step transfer: creates a TransferInstruction the receiver must accept. */
    public suspend fun createTransfer(
        sender: String,
        receiver: String,
        instrumentId: String,
        amount: BigDecimal,
    ): TransferInstruction = TODO("WP2: registry choice context + exercise via InteractiveSubmissionClient")

    /** Pending two-step transfers awaiting this party's action — the wallet inbox. */
    public suspend fun pendingTransferInstructions(partyId: String): List<TransferInstruction> =
        TODO("WP2: ACS query on TransferInstruction interface views")

    public suspend fun exerciseTransferInstruction(
        instruction: TransferInstruction,
        choice: TransferInstructionChoice,
    ): Unit = TODO("WP2: registry choice context + exercise")

    /** DevNet faucet ("tap") — mint test instrument to the receiver. */
    public suspend fun tap(receiver: String, amount: BigDecimal): Unit =
        TODO("WP2: registry tap factory")
}
