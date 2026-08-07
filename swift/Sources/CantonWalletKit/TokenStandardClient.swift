import Foundation

/// CIP-0056 token standard client — WP2 skeleton.
///
/// The surface mirrors `TokenStandardController` in Digital Asset's
/// TypeScript wallet SDK so migration docs can map 1:1. Beyond the Ledger
/// API, these operations need an HTTP client for the transfer-factory
/// Registry and Scan APIs (choice contexts, instrument metadata) — that
/// layer lands with this work package.
///
/// Tracking: CIP-0112 (Token Standard V2 — batch settlement, account-based
/// holdings) will move this surface; build against V1, absorb V2.
public struct TokenStandardClient: Sendable {
    public struct Holding: Sendable {
        public let contractId: String
        public let instrumentId: String
        public let amount: Decimal
        public let locked: Bool
    }

    public struct TransferInstruction: Sendable {
        public let contractId: String
        public let sender: String
        public let receiver: String
        public let instrumentId: String
        public let amount: Decimal
    }

    public enum TransferInstructionChoice: Sendable {
        case accept, reject, withdraw
    }

    public init() {}

    /// Active holding UTXOs for the party, from the ACS via the holding interface.
    public func listHoldings(partyId: String) async throws -> [Holding] {
        fatalError("WP2: query ACS by holding interface id")
    }

    /// Two-step transfer: creates a TransferInstruction the receiver must accept.
    public func createTransfer(
        sender: String,
        receiver: String,
        instrumentId: String,
        amount: Decimal
    ) async throws -> TransferInstruction {
        fatalError("WP2: registry choice context + exercise via InteractiveSubmissionClient")
    }

    /// Pending two-step transfers awaiting this party's action — the wallet inbox.
    public func pendingTransferInstructions(partyId: String) async throws -> [TransferInstruction] {
        fatalError("WP2: ACS query on TransferInstruction interface views")
    }

    public func exerciseTransferInstruction(
        _ instruction: TransferInstruction,
        choice: TransferInstructionChoice
    ) async throws {
        fatalError("WP2: registry choice context + exercise")
    }

    /// DevNet faucet ("tap") — mint test instrument to the receiver.
    public func tap(receiver: String, amount: Decimal) async throws {
        fatalError("WP2: registry tap factory")
    }
}
