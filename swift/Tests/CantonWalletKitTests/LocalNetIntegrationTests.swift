import CantonKit
import CryptoKit
import Foundation
import Testing

@testable import CantonWalletKit

/// Runs against Splice LocalNet (see integration/run-localnet.sh). Skipped
/// unless SPLICE_LOCALNET=1.
///
/// Lighter than the Kotlin full-loop test (which drives tap + transfer):
/// this verifies the Swift stack against a real Splice deployment — JWT'd
/// ledger auth, P-256 external party allocation on the app-user participant,
/// interface-filtered token-standard reads, and ANS resolution via scan.
struct LocalNetIntegrationTests {
    private static var enabled: Bool {
        ProcessInfo.processInfo.environment["SPLICE_LOCALNET"] == "1"
    }

    private static func env(_ name: String, _ fallback: String) -> String {
        ProcessInfo.processInfo.environment[name] ?? fallback
    }

    /// Unsafe HS256 JWT matching LocalNet's `unsafe-jwt-hmac-256` auth service.
    private static func jwt(sub: String) -> String {
        func b64(_ data: Data) -> String {
            data.base64EncodedString()
                .replacingOccurrences(of: "+", with: "-")
                .replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: "")
        }
        let audience = env("SPLICE_LOCALNET_AUDIENCE", "https://canton.network.global")
        let header = b64(Data(#"{"alg":"HS256","typ":"JWT"}"#.utf8))
        let payload = b64(Data(#"{"sub":"\#(sub)","aud":"\#(audience)"}"#.utf8))
        let mac = HMAC<SHA256>.authenticationCode(
            for: Data("\(header).\(payload)".utf8),
            using: SymmetricKey(data: Data("unsafe".utf8))
        )
        return "\(header).\(payload).\(b64(Data(mac)))"
    }

    private static func adminClient() -> CantonClient {
        CantonClient(
            configuration: .init(
                host: env("SPLICE_LOCALNET_LEDGER_HOST", "127.0.0.1"),
                port: Int(env("SPLICE_LOCALNET_LEDGER_PORT", "2901"))!,
                useTLS: false,
                accessTokenProvider: { jwt(sub: env("SPLICE_LOCALNET_ADMIN_USER", "ledger-api-user")) }
            )
        )
    }

    @Test(.enabled(if: enabled, "SPLICE_LOCALNET not set"))
    func p256ExternalPartyAndTokenStandardReadsOnSplice() async throws {
        let client = Self.adminClient()
        let driver = SoftwareSigningDriver.generate(.ecP256)
        let parties = ExternalPartyClient(client: client)
        let synchronizer = try await parties.connectedSynchronizers().first!
        let party = try await parties.allocate(
            driver: driver,
            synchronizerId: synchronizer,
            partyHint: "swiftlocalnet",
            userId: Self.env("SPLICE_LOCALNET_ADMIN_USER", "ledger-api-user")
        )
        #expect(party.partyId.hasPrefix("swiftlocalnet::"))

        // Fresh party: the interface-filtered read and history paths run
        // against the Splice participant (Amulet DARs, JWT auth) and agree.
        let tokens = TokenStandardClient(client: client)
        let holdings = try await tokens.listHoldings(partyId: party.partyId)
        let history = try await tokens.holdingsHistory(partyId: party.partyId)
        #expect(holdings.isEmpty)
        #expect(history.isEmpty)
    }

    @Test(.enabled(if: enabled, "SPLICE_LOCALNET not set"))
    func ansResolutionAgainstLiveScan() async throws {
        let scan = ScanClient(
            baseURL: URL(string: Self.env("SPLICE_LOCALNET_SCAN_URL", "http://scan.localhost:4000/api/scan"))!
        )
        let dso = try await scan.dsoPartyId()
        #expect(dso.hasPrefix("DSO::"))

        let entry = try await scan.lookupAnsEntryByName("dso.ans")
        #expect(entry?.party == dso)
        #expect(try await scan.lookupAnsEntryByName("definitely-not-registered.ans") == nil)
        #expect(try await scan.listAnsEntries(pageSize: 10).contains { $0.name == "dso.ans" })
    }
}
