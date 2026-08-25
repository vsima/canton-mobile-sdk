// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonDappKit

/// A dApp-authored transaction, summarised for an approval sheet.
///
/// Everything here is parsed from `PrepareSubmission.commands`, which is the
/// dApp's *intent*: it is what the wallet prepares, and the pipeline verifies
/// the prepared-transaction hash over exactly these commands before signing.
/// Rendering the intent is therefore honest, but it is still render-only
/// data: nothing in this file feeds back into what gets signed.
public struct DappTransferSummary: Sendable, Equatable {
    /// The receiving party.
    public var receiver: String
    /// Decimal amount, verbatim from the command.
    public var amount: String
    /// Instrument id, e.g. `Amulet`.
    public var instrumentId: String
    /// The instrument admin party the dApp expects, when present.
    public var admin: String?
    /// The token-standard reason/memo, when the dApp attached one.
    public var memo: String?
    /// ISO timestamp the transfer must execute before, when present.
    public var executeBefore: String?

    public init(
        receiver: String,
        amount: String,
        instrumentId: String,
        admin: String? = nil,
        memo: String? = nil,
        executeBefore: String? = nil
    ) {
        self.receiver = receiver
        self.amount = amount
        self.instrumentId = instrumentId
        self.admin = admin
        self.memo = memo
        self.executeBefore = executeBefore
    }
}

/// Recognises the Canton Token Standard transfer inside a dApp submission so
/// an approval sheet can show *what moves where* instead of an opaque
/// "a transaction".
///
/// The recognition is deliberately strict: ``transferOf(_:)`` returns a
/// summary only for a submission whose commands are exactly one
/// `TransferFactory_Transfer` exercise with the standard argument shape.
/// Anything else is not an error, it is simply not a transfer the sheet can
/// vouch for, and the UI should fall back to ``describe(_:)`` plus the raw
/// payload. A sheet that guessed at half-parsed commands would show the user
/// a summary the signed transaction is not obliged to match.
public enum DappCommandSummary {
    /// CIP token-standard metadata key that carries a transfer's memo.
    private static let reasonKey = "splice.lfdecentralizedtrust.org/reason"

    private static let transferChoice = "TransferFactory_Transfer"

    /// The token-standard transfer this submission asks for, or nil when the
    /// submission is anything other than exactly one recognised transfer.
    public static func transferOf(_ submission: PrepareSubmission) -> DappTransferSummary? {
        guard submission.commands.count == 1,
              let command = submission.commands.first?.objectValue,
              let exercise = command["ExerciseCommand"]?.objectValue,
              exercise["choice"]?.stringValue == transferChoice,
              let argument = exercise["choiceArgument"]?.objectValue,
              let transfer = argument["transfer"]?.objectValue,
              let receiver = transfer["receiver"]?.stringValue,
              let amount = text(transfer["amount"]),
              let instrument = transfer["instrumentId"]?.objectValue,
              let instrumentId = instrument["id"]?.stringValue
        else { return nil }
        let meta = transfer["meta"]?.objectValue?["values"]?.objectValue
        return DappTransferSummary(
            receiver: receiver,
            amount: amount,
            instrumentId: instrumentId,
            admin: instrument["admin"]?.stringValue,
            memo: meta?[reasonKey]?.stringValue,
            executeBefore: transfer["executeBefore"]?.stringValue
        )
    }

    /// One human-readable line per command, for submissions
    /// ``transferOf(_:)`` does not recognise: the command kind plus the
    /// choice and template entity, e.g.
    /// `Exercise AmuletRules_DevNet_Tap on AmuletRules`. Unknown shapes
    /// degrade to a labelled placeholder rather than being dropped, so the
    /// sheet never under-reports how many commands are being approved.
    public static func describe(_ submission: PrepareSubmission) -> [String] {
        submission.commands.map { element in
            guard let command = element.objectValue else { return "Unrecognised command" }
            guard let (kind, body) = command.first else { return "Empty command" }
            let fields = body.objectValue
            let entity = fields?["templateId"]?.stringValue
                .flatMap { $0.components(separatedBy: ":").last }
            let choice = fields?["choice"]?.stringValue
            switch (choice, entity) {
            case (let choice?, let entity?): return "\(verb(kind)) \(choice) on \(entity)"
            case (let choice?, nil): return "\(verb(kind)) \(choice)"
            case (nil, let entity?): return "\(verb(kind)) \(entity)"
            case (nil, nil): return verb(kind)
            }
        }
    }

    private static func verb(_ commandKind: String) -> String {
        switch commandKind {
        case "ExerciseCommand", "ExerciseByKeyCommand": return "Exercise"
        case "CreateCommand": return "Create"
        case "CreateAndExerciseCommand": return "Create and exercise"
        default: return commandKind
        }
    }

    /// A JSON string or number as display text; amounts may arrive as either.
    private static func text(_ value: JSONValue?) -> String? {
        switch value {
        case .string(let text): return text
        case .number(let text): return text
        default: return nil
        }
    }
}
