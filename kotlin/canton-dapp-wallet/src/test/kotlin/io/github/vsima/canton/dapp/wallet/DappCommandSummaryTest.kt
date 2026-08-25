// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp.wallet

import io.github.vsima.canton.dapp.PrepareSubmission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray

class DappCommandSummaryTest {

    // The wire shape a token-standard dApp submits, as produced by the
    // official dapp SDKs and asserted end to end in
    // LocalNetDappEndToEndIntegrationTest.
    private val transferCommand = """
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

    private fun submission(vararg commands: String): PrepareSubmission =
        PrepareSubmission(
            commands = buildJsonArray {
                commands.forEach { add(Json.parseToJsonElement(it)) }
            },
        )

    @Test
    fun `recognises a token-standard transfer`() {
        val summary = DappCommandSummary.transferOf(submission(transferCommand))
        assertEquals(
            DappTransferSummary(
                receiver = "bob::1220bb",
                amount = "7.0",
                instrumentId = "Amulet",
                admin = "DSO::1220dso",
                memo = "Invoice #4021",
                executeBefore = "2026-08-25T00:00:00Z",
            ),
            summary,
        )
    }

    @Test
    fun `memo and admin are optional`() {
        val bare = """
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
        val summary = DappCommandSummary.transferOf(submission(bare))
        assertEquals(
            DappTransferSummary(receiver = "bob::1220bb", amount = "2", instrumentId = "Amulet"),
            summary,
        )
    }

    @Test
    fun `refuses anything that is not exactly one recognised transfer`() {
        // Two commands: even if one is a transfer, the sheet must not
        // summarise a submission it cannot fully vouch for.
        assertNull(DappCommandSummary.transferOf(submission(transferCommand, transferCommand)))
        // A different choice.
        assertNull(
            DappCommandSummary.transferOf(
                submission("""{ "ExerciseCommand": { "choice": "AmuletRules_DevNet_Tap", "choiceArgument": {} } }"""),
            ),
        )
        // Structurally broken shapes.
        assertNull(DappCommandSummary.transferOf(submission("""{ "ExerciseCommand": "not an object" }""")))
        assertNull(DappCommandSummary.transferOf(submission(""""just a string"""")))
        assertNull(DappCommandSummary.transferOf(PrepareSubmission(commands = JsonArray(emptyList()))))
        // A transfer missing its amount.
        assertNull(
            DappCommandSummary.transferOf(
                submission(
                    """
                    {
                      "ExerciseCommand": {
                        "choice": "TransferFactory_Transfer",
                        "choiceArgument": { "transfer": { "receiver": "bob::1", "instrumentId": { "id": "Amulet" } } }
                      }
                    }
                    """,
                ),
            ),
        )
    }

    @Test
    fun `describes arbitrary commands without dropping any`() {
        val lines = DappCommandSummary.describe(
            submission(
                transferCommand,
                """{ "CreateCommand": { "templateId": "p:Splice.Wallet:TransferPreapprovalProposal" } }""",
                """{ "ExerciseCommand": { "choice": "AmuletRules_DevNet_Tap" } }""",
                """"garbage"""",
            ),
        )
        assertEquals(
            listOf(
                "Exercise TransferFactory_Transfer on TransferFactory",
                "Create TransferPreapprovalProposal",
                "Exercise AmuletRules_DevNet_Tap",
                "Unrecognised command",
            ),
            lines,
        )
    }
}
