// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonKit
import CantonLedgerAPI
import CryptoKit
import Foundation
import Testing

@testable import CantonWalletKit

#if canImport(FoundationNetworking)
    import FoundationNetworking
#endif

/// The fee preview against Splice LocalNet — the Swift twin of the Kotlin
/// `LocalNetFeePreviewIntegrationTest`. Skipped unless SPLICE_LOCALNET=1.
///
/// What this proves end-to-end: `ScanClient.amuletRulesConfig` decodes the
/// live AmuletRules (zero transfer fees on LocalNet's post-CIP-0078 splice,
/// real synchronizer traffic pricing), `openMiningRounds` reads the live
/// amulet price, `TransferFeeEstimator` previews 0 for a 5 CC transfer —
/// and the honest check: a real two-step transfer's sender net equals
/// −(amount + estimated fee), i.e. exactly −amount here, read back through
/// the public history API.
struct LocalNetFeePreviewIntegrationTests {
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

    private static func client(user: String) -> CantonClient {
        CantonClient(
            configuration: .init(
                host: env("SPLICE_LOCALNET_LEDGER_HOST", "127.0.0.1"),
                port: Int(env("SPLICE_LOCALNET_LEDGER_PORT", "2901"))!,
                useTLS: false,
                accessTokenProvider: { jwt(sub: user) }
            )
        )
    }

    private static var validator: ValidatorClient {
        ValidatorClient(
            baseURL: URL(
                string: env("SPLICE_LOCALNET_VALIDATOR_URL", "http://wallet.localhost:2000/api/validator")
            )!,
            accessTokenProvider: { jwt(sub: env("SPLICE_LOCALNET_WALLET_USER", "app-user")) }
        )
    }

    private static func onboardWalletUser() async throws -> String {
        if let status = try? await validator.userStatus(),
            status.userOnboarded, !status.partyId.isEmpty
        {
            return status.partyId
        }
        return try await retryUntil("wallet user onboarding") {
            try await validator.register()
        }
    }

    private static func retryUntil<T>(
        _ what: String,
        attempts: Int = 120,
        delaySeconds: Int = 5,
        _ block: () async throws -> T?
    ) async throws -> T {
        for attempt in 1...attempts {
            do {
                if let result = try await block() { return result }
            } catch {
                print("  (\(what) attempt \(attempt): \(String(describing: error).prefix(160)))")
            }
            try await Task.sleep(for: .seconds(delaySeconds))
        }
        Issue.record("\(what): not satisfied after \(attempts) attempts")
        throw TransferRegistryError(description: "\(what): not satisfied after \(attempts) attempts")
    }

    @Test(.enabled(if: enabled, "SPLICE_LOCALNET not set"))
    func liveConfigDecodesEstimateIsZeroAndARealTransferConfirmsIt() async throws {
        let adminUser = Self.env("SPLICE_LOCALNET_ADMIN_USER", "ledger-api-user")
        let walletUser = Self.env("SPLICE_LOCALNET_WALLET_USER", "app-user")
        let registry = TransferRegistryClient(
            baseURL: URL(string: Self.env("SPLICE_LOCALNET_REGISTRY_URL", "http://scan.localhost:4000"))!
        )
        let scan = ScanClient(
            baseURL: URL(
                string: Self.env("SPLICE_LOCALNET_SCAN_URL", "http://scan.localhost:4000/api/scan")
            )!
        )
        let amount = Decimal(string: "5.0")!
        let memo = "fee preview probe \(UUID().uuidString)"

        // 1. The live AmuletRules config: LocalNet ships splice 0.7.1,
        // where CIP-0078/0107 pin every transfer fee to zero.
        let config = try await scan.amuletRulesConfig()
        print("transfer fees: \(config.transferFees)")
        print("synchronizer fees: \(config.synchronizerFees)")
        #expect(config.transferFees.createFeeUsd == 0)
        #expect(config.transferFees.transferFee.initialRate == 0)
        #expect(config.transferFees.transferFee.steps.isEmpty)
        #expect(config.transferFees.lockHolderFeeUsd == 0)
        #expect(
            config.transferFees.holdingFeeUsdPerRound > 0,
            "LocalNet still publishes a non-zero holding fee rate"
        )
        #expect(config.synchronizerFees.extraTrafficPriceUsdPerMB > 0)
        #expect(config.synchronizerFees.minTopupAmountBytes > 0)
        #expect(config.activeSynchronizerId.contains("::"))

        // 2. Open rounds carry the amulet price the estimate converts at.
        let rounds = try await scan.openMiningRounds()
        print("open rounds: \(rounds.map { "\($0.roundNumber)@\($0.amuletPriceUsd)" })")
        #expect(!rounds.isEmpty, "LocalNet must have open mining rounds")
        let usable = try #require(rounds.latestUsable(), "an open round must be usable")
        #expect(usable.amuletPriceUsd > 0)

        // 3. The preview: zero-fee schedule → zero, whatever the price.
        let estimate = TransferFeeEstimator.estimate(
            schedule: config.transferFees,
            amuletPriceUsd: usable.amuletPriceUsd,
            amountCc: amount
        )
        print("estimate for \(amount) CC: \(estimate)")
        #expect(estimate.feeCc == 0)
        #expect(estimate.feeUsd == 0)

        // 4. The honest check: send 5 CC for real; the sender's net must
        // equal −(amount + estimate) — exactly −5 CC here.
        let walletParty = try await Self.onboardWalletUser()
        _ = try await Self.retryUntil("tap 50 USD (waits for an open mining round)") {
            try await Self.validator.tap(amountUsd: "50.0")
        }
        let walletClient = Self.client(user: walletUser)
        let walletTokens = TokenStandardClient(client: walletClient, registry: registry)
        let holdings = try await Self.retryUntil("wallet holdings visible") {
            let unlocked = try await walletTokens.listHoldings(partyId: walletParty)
                .filter { $0.lock == nil }
            return unlocked.isEmpty ? nil : unlocked
        }
        let amulet = holdings.first!.instrumentId

        let adminClient = Self.client(user: adminUser)
        let driver = SoftwareSigningDriver.generate(.ecP256)
        let parties = ExternalPartyClient(client: adminClient)
        let synchronizer = try await parties.connectedSynchronizers().first!
        let external = try await parties.allocate(
            driver: driver,
            synchronizerId: synchronizer,
            partyHint: "swiftfeepreviewreceiver",
            userId: adminUser
        )

        let senderBegin = try await walletClient.ledgerEnd()

        let transfer = Transfer(
            sender: walletParty,
            receiver: external.partyId,
            amount: "5.0",
            instrumentId: amulet,
            requestedAt: Date(),
            executeBefore: Date().addingTimeInterval(24 * 60 * 60),
            inputHoldingCids: holdings.map { $0.contractId },
            meta: [TokenStandard.reasonMetadataKey: memo]
        )
        let factory = try await registry.transferFactory(
            choiceArguments: ChoiceContextJSON.transferFactoryChoiceArguments(
                expectedAdmin: amulet.admin,
                transfer: transfer
            )
        )

        var exercise = Com_Daml_Ledger_Api_V2_ExerciseCommand()
        exercise.templateID = TokenStandard.transferFactoryInterfaceID
        exercise.contractID = factory.factoryId
        exercise.choice = "TransferFactory_Transfer"
        exercise.choiceArgument = .record([
            "expectedAdmin": .party(amulet.admin),
            "transfer": transfer.toValue(),
            "extraArgs": try ChoiceContextJSON.extraArgsValue(
                choiceContextData: factory.choiceContext.choiceContextData
            ),
        ])
        var command = Com_Daml_Ledger_Api_V2_Command()
        command.exercise = exercise
        var commands = Com_Daml_Ledger_Api_V2_Commands()
        commands.commandID = UUID().uuidString
        commands.userID = walletUser
        commands.actAs = [walletParty]
        commands.commands = [command]
        commands.disclosedContracts = try factory.choiceContext.disclosedContracts.map {
            try $0.toProto()
        }
        var submitRequest = Com_Daml_Ledger_Api_V2_SubmitAndWaitRequest()
        submitRequest.commands = commands
        let frozenRequest = submitRequest
        _ = try await walletClient.withServices { services in
            try await services.command.submitAndWait(frozenRequest)
        }

        let externalTokens = TokenStandardClient(client: adminClient, registry: registry)
        let instruction = try await Self.retryUntil("transfer instruction in inbox") {
            try await externalTokens.pendingTransferInstructions(partyId: external.partyId).first
        }
        try await externalTokens.exerciseTransferInstruction(
            driver: driver,
            party: external,
            transferInstructionId: instruction.contractId,
            choice: .accept,
            synchronizerId: synchronizer,
            userId: adminUser
        )
        _ = try await Self.retryUntil("received holdings visible") {
            let received = try await externalTokens.listHoldings(partyId: external.partyId)
            return received.isEmpty ? nil : received
        }

        let senderRows = try await walletTokens.holdingsHistory(
            partyId: walletParty, beginExclusive: senderBegin
        ).filter { $0.summary?.memo == memo }
        #expect(!senderRows.isEmpty, "sender history must contain the memo rows")
        var senderNet = Decimal(0)
        for row in senderRows {
            let summary = try #require(row.summary)
            senderNet += try #require(Decimal(string: summary.amount))
        }
        let expectedNet = -(amount + estimate.feeCc)
        print("sender net: \(senderNet), expected −(amount + estimate) = \(expectedNet)")
        #expect(
            senderNet == expectedNet,
            "sender net must equal −(amount + estimated fee): expected \(expectedNet), was \(senderNet)"
        )
    }
}
