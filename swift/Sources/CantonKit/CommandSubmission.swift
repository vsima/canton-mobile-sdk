import CantonLedgerAPI
import Foundation
import SwiftProtobuf

/// A batch of commands to submit atomically to the ledger.
///
/// ``commandId`` defaults to a fresh UUID and stays stable for the lifetime
/// of this instance — the SDK's retries reuse it, so the participant's
/// command deduplication prevents double execution. Reuse one
/// `CommandSubmission` per logical action; build a new one for a genuinely
/// new action.
public struct CommandSubmission: Sendable {
    /// The commands to execute atomically, in order.
    public var commands: [Com_Daml_Ledger_Api_V2_Command]

    /// Parties on whose behalf the commands are executed.
    public var actAs: [String]

    /// Additional parties whose contracts may be read during interpretation.
    public var readAs: [String]

    /// The ledger user submitting the request; must match the JWT's user on
    /// authenticated ledgers.
    public var userId: String

    /// Unique id for deduplication; keep stable across retries of the same action.
    public var commandId: String

    public var workflowId: String

    /// How far back the participant rejects duplicate ``commandId``s;
    /// participant maximum if nil.
    public var deduplicationDuration: Duration?

    /// Pin execution to a synchronizer; participant chooses if empty.
    public var synchronizerId: String

    public init(
        commands: [Com_Daml_Ledger_Api_V2_Command],
        actAs: [String],
        readAs: [String] = [],
        userId: String = "",
        commandId: String = UUID().uuidString,
        workflowId: String = "",
        deduplicationDuration: Duration? = nil,
        synchronizerId: String = ""
    ) {
        precondition(!commands.isEmpty, "commands must not be empty")
        precondition(!actAs.isEmpty, "actAs must contain at least one party")
        self.commands = commands
        self.actAs = actAs
        self.readAs = readAs
        self.userId = userId
        self.commandId = commandId
        self.workflowId = workflowId
        self.deduplicationDuration = deduplicationDuration
        self.synchronizerId = synchronizerId
    }

    var proto: Com_Daml_Ledger_Api_V2_Commands {
        var proto = Com_Daml_Ledger_Api_V2_Commands()
        proto.commandID = commandId
        proto.userID = userId
        proto.workflowID = workflowId
        proto.synchronizerID = synchronizerId
        proto.commands = commands
        proto.actAs = actAs
        proto.readAs = readAs
        if let dedup = deduplicationDuration {
            var duration = Google_Protobuf_Duration()
            duration.seconds = dedup.components.seconds
            duration.nanos = Int32(dedup.components.attoseconds / 1_000_000_000)
            proto.deduplicationDuration = duration
        }
        return proto
    }
}
