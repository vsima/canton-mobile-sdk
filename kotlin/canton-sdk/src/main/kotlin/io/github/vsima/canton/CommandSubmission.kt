// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton

import com.daml.ledger.api.v2.CommandsOuterClass
import java.util.UUID
import kotlin.time.Duration
import com.google.protobuf.Duration as ProtoDuration

/**
 * A batch of commands to submit atomically to the ledger.
 *
 * [commandId] defaults to a fresh UUID and stays stable for the lifetime of
 * this instance — the SDK's retries reuse it, so the participant's command
 * deduplication prevents double execution. Reuse one [CommandSubmission] per
 * logical action; build a new one for a genuinely new action.
 */
public data class CommandSubmission(
    /** The commands to execute atomically, in order. */
    val commands: List<CommandsOuterClass.Command>,
    /** Parties on whose behalf the commands are executed. */
    val actAs: List<String>,
    /** Additional parties whose contracts may be read during interpretation. */
    val readAs: List<String> = emptyList(),
    /** The ledger user submitting the request; must match the JWT's user on authenticated ledgers. */
    val userId: String = "",
    /** Unique id for deduplication; keep stable across retries of the same action. */
    val commandId: String = UUID.randomUUID().toString(),
    val workflowId: String = "",
    /** How far back the participant rejects duplicate [commandId]s; participant maximum if null. */
    val deduplicationDuration: Duration? = null,
    /** Pin execution to a synchronizer; participant chooses if empty. */
    val synchronizerId: String = "",
) {
    init {
        require(commands.isNotEmpty()) { "commands must not be empty" }
        require(actAs.isNotEmpty()) { "actAs must contain at least one party" }
    }

    internal fun toProto(): CommandsOuterClass.Commands =
        CommandsOuterClass.Commands.newBuilder()
            .setCommandId(commandId)
            .setUserId(userId)
            .setWorkflowId(workflowId)
            .setSynchronizerId(synchronizerId)
            .addAllCommands(commands)
            .addAllActAs(actAs)
            .addAllReadAs(readAs)
            .apply {
                this@CommandSubmission.deduplicationDuration?.let { dedup ->
                    setDeduplicationDuration(
                        ProtoDuration.newBuilder()
                            .setSeconds(dedup.inWholeSeconds)
                            .setNanos((dedup.inWholeNanoseconds % 1_000_000_000L).toInt())
                            .build()
                    )
                }
            }
            .build()
}
