import Foundation
import Testing
import CantonKit

/// Runs against a live Canton participant (see integration/run-canton.sh).
/// Skipped unless CANTON_LEDGER_PORT is set.
private var liveLedgerPort: Int? {
    ProcessInfo.processInfo.environment["CANTON_LEDGER_PORT"].flatMap(Int.init)
}

@Suite struct CantonLedgerIntegrationTests {
    @Test(.enabled(if: liveLedgerPort != nil))
    func fetchesVersionFromLiveParticipant() async throws {
        let host = ProcessInfo.processInfo.environment["CANTON_LEDGER_HOST"] ?? "127.0.0.1"
        let client = CantonClient(
            configuration: .init(host: host, port: liveLedgerPort!, useTLS: false)
        )
        let version = try await client.ledgerApiVersion()
        print("live canton ledger api version: \(version)")
        #expect(!version.isEmpty)
    }
}
