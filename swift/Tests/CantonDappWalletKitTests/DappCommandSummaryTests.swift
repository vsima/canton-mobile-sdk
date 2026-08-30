// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonDappKit
import Foundation
import Testing

@testable import CantonDappWalletKit

/// The approval-sheet summary parser, the Swift mirror of
/// `DappCommandSummaryTest.kt`: a token-standard transfer is recognised in
/// full, anything else is refused rather than half-summarised.
@Suite struct DappCommandSummaryTests {

    // The wire shape a token-standard dApp submits, as produced by the
    // official dapp SDKs and asserted end to end against LocalNet on the
    // Kotlin side.
    private let transferCommand = """
        {
          "ExerciseCommand": {
            "templateId": "abc123:Splice.Api.Token.TransferInstructionV1:TransferFactory",
            "contractId": "00fac70r",
            "choice": "TransferFactory_Transfer",
            "choiceArgument": {
              "expectedAdmin": "DSO::1220dso",
              "transfer": {
                "sender": "alice::1220aa",
                "receiver": "bob::1220bb",
                "amount": "7.0",
                "instrumentId": { "admin": "DSO::1220dso", "id": "Amulet" },
                "requestedAt": "2026-08-24T00:00:00Z",
                "executeBefore": "2026-08-25T00:00:00Z",
                "inputHoldingCids": ["00aa"],
                "meta": { "values": { "splice.lfdecentralizedtrust.org/reason": "Invoice #4021" } }
              },
              "extraArgs": {
                "context": { "values": {} },
                "meta": { "values": {} }
              }
            }
          }
        }
        """

    private func submission(_ commands: String...) throws -> PrepareSubmission {
        PrepareSubmission(commands: try commands.map { try JSONValue.parse($0) })
    }

    @Test func recognisesATokenStandardTransfer() throws {
        let summary = DappCommandSummary.transferOf(try submission(transferCommand))
        #expect(
            summary
                == DappTransferSummary(
                    receiver: "bob::1220bb",
                    amount: "7.0",
                    instrumentId: "Amulet",
                    admin: "DSO::1220dso",
                    memo: "Invoice #4021",
                    executeBefore: "2026-08-25T00:00:00Z"
                )
        )
    }

    @Test func memoAndAdminAreOptional() throws {
        let bare = """
            {
              "ExerciseCommand": {
                "choice": "TransferFactory_Transfer",
                "choiceArgument": {
                  "transfer": {
                    "receiver": "bob::1220bb",
                    "amount": "2",
                    "instrumentId": { "id": "Amulet" }
                  }
                }
              }
            }
            """
        let summary = DappCommandSummary.transferOf(try submission(bare))
        #expect(summary == DappTransferSummary(receiver: "bob::1220bb", amount: "2", instrumentId: "Amulet"))
    }

    @Test func refusesAnythingNotExactlyOneRecognisedTransfer() throws {
        // Two commands: even if one is a transfer, the sheet must not
        // summarise a submission it cannot fully vouch for.
        #expect(DappCommandSummary.transferOf(try submission(transferCommand, transferCommand)) == nil)
        // A different choice.
        #expect(
            DappCommandSummary.transferOf(
                try submission(#"{ "ExerciseCommand": { "choice": "AmuletRules_DevNet_Tap", "choiceArgument": {} } }"#)
            ) == nil
        )
        // Structurally broken shapes.
        #expect(DappCommandSummary.transferOf(try submission(#"{ "ExerciseCommand": "not an object" }"#)) == nil)
        #expect(DappCommandSummary.transferOf(try submission(#""just a string""#)) == nil)
        #expect(DappCommandSummary.transferOf(PrepareSubmission(commands: [])) == nil)
        // A transfer missing its amount.
        #expect(
            DappCommandSummary.transferOf(
                try submission(
                    """
                    {
                      "ExerciseCommand": {
                        "choice": "TransferFactory_Transfer",
                        "choiceArgument": { "transfer": { "receiver": "bob::1", "instrumentId": { "id": "Amulet" } } }
                      }
                    }
                    """
                )
            ) == nil
        )
    }

    @Test func describesArbitraryCommandsWithoutDroppingAny() throws {
        let lines = DappCommandSummary.describe(
            try submission(
                transferCommand,
                #"{ "CreateCommand": { "templateId": "p:Splice.Wallet:TransferPreapprovalProposal" } }"#,
                #"{ "ExerciseCommand": { "choice": "AmuletRules_DevNet_Tap" } }"#,
                #""garbage""#
            )
        )
        #expect(
            lines == [
                "Exercise TransferFactory_Transfer on TransferFactory",
                "Create TransferPreapprovalProposal",
                "Exercise AmuletRules_DevNet_Tap",
                "Unrecognised command",
            ]
        )
    }
}
