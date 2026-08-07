import CantonKit
import Foundation
import Testing

@testable import CantonWalletKit

/// Runs against a live Canton participant (see integration/run-canton.sh).
/// Skipped unless CANTON_LEDGER_PORT is set.
///
/// Swift twin of the Kotlin `ExternalPartyIntegrationTest`: proves a real
/// participant accepts external parties with both Ed25519 (Canton's
/// default) and EC P-256 — the only scheme the Secure Enclave signs, and
/// therefore the scheme mobile self-custody hangs on.
struct ExternalPartyIntegrationTests {
    private static var port: Int? {
        ProcessInfo.processInfo.environment["CANTON_LEDGER_PORT"].flatMap(Int.init)
    }

    private func allocate(
        _ driver: any SigningDriver,
        hint: String
    ) async throws -> AllocatedExternalParty {
        let client = CantonClient(
            configuration: .init(
                host: ProcessInfo.processInfo.environment["CANTON_LEDGER_HOST"] ?? "127.0.0.1",
                port: Self.port!,
                useTLS: false
            )
        )
        let partyClient = ExternalPartyClient(client: client)
        let synchronizer = try await partyClient.connectedSynchronizers().first!
        return try await partyClient.allocate(
            driver: driver,
            synchronizerId: synchronizer,
            partyHint: hint,
            userId: "participant_admin"
        )
    }

    @Test(.enabled(if: port != nil, "CANTON_LEDGER_PORT not set"))
    func allocatesEd25519ExternalParty() async throws {
        let party = try await allocate(SoftwareSigningDriver.generate(.ed25519), hint: "swifted25519")
        #expect(party.partyId.hasPrefix("swifted25519::"))
    }

    @Test(.enabled(if: port != nil, "CANTON_LEDGER_PORT not set"))
    func allocatesP256ExternalParty() async throws {
        let party = try await allocate(SoftwareSigningDriver.generate(.ecP256), hint: "swiftp256")
        #expect(party.partyId.hasPrefix("swiftp256::"))
    }
}
