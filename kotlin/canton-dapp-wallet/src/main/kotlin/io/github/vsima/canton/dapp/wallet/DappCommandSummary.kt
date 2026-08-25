// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp.wallet

import io.github.vsima.canton.dapp.PrepareSubmission
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A dApp-authored transaction, summarised for an approval sheet.
 *
 * Everything here is parsed from [PrepareSubmission.commands], which is the
 * dApp's *intent*: it is what the wallet prepares, and the pipeline verifies
 * the prepared-transaction hash over exactly these commands before signing.
 * Rendering the intent is therefore honest, but it is still render-only
 * data: nothing in this file feeds back into what gets signed.
 */
public data class DappTransferSummary(
    /** The receiving party. */
    val receiver: String,
    /** Decimal amount, verbatim from the command. */
    val amount: String,
    /** Instrument id, e.g. `Amulet`. */
    val instrumentId: String,
    /** The instrument admin party the dApp expects, when present. */
    val admin: String? = null,
    /** The token-standard reason/memo, when the dApp attached one. */
    val memo: String? = null,
    /** ISO timestamp the transfer must execute before, when present. */
    val executeBefore: String? = null,
)

/**
 * Recognises the Canton Token Standard transfer inside a dApp submission so
 * an approval sheet can show *what moves where* instead of an opaque
 * "a transaction".
 *
 * The recognition is deliberately strict: [transferOf] returns a summary
 * only for a submission whose commands are exactly one
 * `TransferFactory_Transfer` exercise with the standard argument shape.
 * Anything else is not an error, it is simply not a transfer the sheet can
 * vouch for, and the UI should fall back to [describe] plus the raw
 * payload. A sheet that guessed at half-parsed commands would show the user
 * a summary the signed transaction is not obliged to match.
 */
public object DappCommandSummary {
    /** CIP token-standard metadata key that carries a transfer's memo. */
    private const val REASON_KEY: String = "splice.lfdecentralizedtrust.org/reason"

    private const val TRANSFER_CHOICE: String = "TransferFactory_Transfer"

    /**
     * The token-standard transfer this submission asks for, or null when the
     * submission is anything other than exactly one recognised transfer.
     */
    public fun transferOf(submission: PrepareSubmission): DappTransferSummary? {
        val command = submission.commands.singleOrNull() as? JsonObject ?: return null
        val exercise = command["ExerciseCommand"] as? JsonObject ?: return null
        if (exercise.text("choice") != TRANSFER_CHOICE) return null
        val argument = exercise["choiceArgument"] as? JsonObject ?: return null
        val transfer = argument["transfer"] as? JsonObject ?: return null
        val receiver = transfer.text("receiver") ?: return null
        val amount = transfer.text("amount") ?: return null
        val instrument = transfer["instrumentId"] as? JsonObject
        val instrumentId = instrument?.text("id") ?: return null
        val meta = (transfer["meta"] as? JsonObject)?.get("values") as? JsonObject
        return DappTransferSummary(
            receiver = receiver,
            amount = amount,
            instrumentId = instrumentId,
            admin = instrument.text("admin"),
            memo = meta?.text(REASON_KEY),
            executeBefore = transfer.text("executeBefore"),
        )
    }

    /**
     * One human-readable line per command, for submissions [transferOf] does
     * not recognise: the command kind plus the choice and template entity,
     * e.g. `Exercise AmuletRules_DevNet_Tap on AmuletRules`. Unknown shapes
     * degrade to a labelled placeholder rather than being dropped, so the
     * sheet never under-reports how many commands are being approved.
     */
    public fun describe(submission: PrepareSubmission): List<String> =
        submission.commands.map { element ->
            val command = element as? JsonObject
                ?: return@map "Unrecognised command"
            val (kind, body) = command.entries.firstOrNull()?.toPair()
                ?: return@map "Empty command"
            val fields = body as? JsonObject
            val entity = fields?.text("templateId")?.substringAfterLast(':')
            val choice = fields?.text("choice")
            when {
                choice != null && entity != null -> "${verb(kind)} $choice on $entity"
                choice != null -> "${verb(kind)} $choice"
                entity != null -> "${verb(kind)} $entity"
                else -> verb(kind)
            }
        }

    private fun verb(commandKind: String): String = when (commandKind) {
        "ExerciseCommand", "ExerciseByKeyCommand" -> "Exercise"
        "CreateCommand" -> "Create"
        "CreateAndExerciseCommand" -> "Create and exercise"
        else -> commandKind
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.content
}
