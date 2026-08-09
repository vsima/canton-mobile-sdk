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

/// Transfer-level history semantics against Splice LocalNet — the Swift twin
/// of the Kotlin `LocalNetTransferHistoryIntegrationTest`. Skipped unless
/// SPLICE_LOCALNET=1.
///
/// The two-party scenario: fund the operator wallet party (sender) via the
/// validator API tap, allocate a P-256 external party (receiver), send a
/// token-standard transfer offer WITH a memo, accept it externally signed —
/// then `holdingsHistory` must read fee-inclusive SENT rows with the memo on
/// the sender side and the mirrored RECEIVED credit on the receiver side.
struct LocalNetTransferHistoryIntegrationTests {
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

    // MARK: - validator (wallet) API

    private static func validatorJSON(
        path: String,
        method: String,
        body: String? = nil
    ) async throws -> [String: Any]? {
        let base = env("SPLICE_LOCALNET_VALIDATOR_URL", "http://wallet.localhost:2000/api/validator")
        var request = URLRequest(url: URL(string: "\(base)/\(path)")!)
        request.httpMethod = method
        request.setValue(
            "Bearer \(jwt(sub: env("SPLICE_LOCALNET_WALLET_USER", "app-user")))",
            forHTTPHeaderField: "Authorization"
        )
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = Data(body.utf8)
        }
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            let code = (response as? HTTPURLResponse)?.statusCode ?? -1
            print("  (validator API \(code): \(String(decoding: data.prefix(200), as: UTF8.self)))")
            return nil
        }
        guard !data.isEmpty else { return [:] }
        return try JSONSerialization.jsonObject(with: data) as? [String: Any]
    }

    private static func onboardWalletUser() async throws -> String {
        if let status = try await validatorJSON(path: "v0/wallet/user-status", method: "GET"),
            let party = status["party_id"] as? String, !party.isEmpty,
            status["user_onboarded"] as? Bool == true
        {
            return party
        }
        let registered = try await retryUntil("wallet user onboarding") {
            try await validatorJSON(path: "v0/register", method: "POST", body: "{}")
        }
        return registered["party_id"] as! String
    }

    private static func tap(_ amount: String) async throws {
        _ = try await retryUntil("tap \(amount) (waits for an open mining round)") {
            try await validatorJSON(
                path: "v0/wallet/tap", method: "POST", body: #"{"amount": "\#(amount)"}"#
            )
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

    // MARK: - the scenario

    @Test(.enabled(if: enabled, "SPLICE_LOCALNET not set"))
    func twoPartyTransferWithMemoYieldsSentAndReceivedHistoryRows() async throws {
        let adminUser = Self.env("SPLICE_LOCALNET_ADMIN_USER", "ledger-api-user")
        let walletUser = Self.env("SPLICE_LOCALNET_WALLET_USER", "app-user")
        let registry = TransferRegistryClient(
            baseURL: URL(string: Self.env("SPLICE_LOCALNET_REGISTRY_URL", "http://scan.localhost:4000"))!
        )
        let amount = Decimal(string: "5.0")!
        let memo = "history probe \(UUID().uuidString)"

        // 1. Funded sender: the operator wallet party.
        let walletParty = try await Self.onboardWalletUser()
        try await Self.tap("250.0")
        let walletClient = Self.client(user: walletUser)
        let walletTokens = TokenStandardClient(client: walletClient, registry: registry)
        let holdings = try await Self.retryUntil("wallet holdings visible") {
            let unlocked = try await walletTokens.listHoldings(partyId: walletParty)
                .filter { $0.lock == nil }
            return unlocked.isEmpty ? nil : unlocked
        }
        let amulet = holdings.first!.instrumentId
        print("sender: \(walletParty) with \(holdings.count) unlocked \(amulet.id) holdings")

        // 2. Receiver: a fresh P-256 external party.
        let adminClient = Self.client(user: adminUser)
        let driver = SoftwareSigningDriver.generate(.ecP256)
        let parties = ExternalPartyClient(client: adminClient)
        let synchronizer = try await parties.connectedSynchronizers().first!
        let external = try await parties.allocate(
            driver: driver,
            synchronizerId: synchronizer,
            partyHint: "swifthistoryreceiver",
            userId: adminUser
        )
        print("receiver: \(external.partyId)")

        // Only rows past this offset belong to the scenario.
        let senderBegin = try await walletClient.ledgerEnd()

        // 3. The offer, WITH a memo riding on the standard reason key.
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
        print("factory kind=\(factory.transferKind)")

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

        // 4. Receiver accepts, externally signed.
        let externalTokens = TokenStandardClient(client: adminClient, registry: registry)
        let instruction = try await Self.retryUntil("transfer instruction in inbox") {
            try await externalTokens.pendingTransferInstructions(partyId: external.partyId).first
        }
        #expect(instruction.transfer.meta[TokenStandard.reasonMetadataKey] == memo)
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

        // 5a. Sender side: fee-inclusive SENT debit with the memo.
        let senderHistory = try await walletTokens.holdingsHistory(
            partyId: walletParty, beginExclusive: senderBegin
        )
        for row in senderHistory { print("sender row: \(Self.render(row))") }
        let senderRows = senderHistory.filter { $0.summary?.memo == memo }
        #expect(!senderRows.isEmpty, "sender history must contain rows with the memo")
        var senderNet = Decimal(0)
        for row in senderRows {
            let summary = try #require(row.summary)
            #expect(summary.direction == .sent)
            #expect(summary.counterparty == external.partyId)
            #expect(summary.instrumentId == amulet)
            #expect(
                row.archived.count == row.archivedContractIds.count,
                "every archived holding must resolve to a payload"
            )
            senderNet += try #require(Decimal(string: summary.amount))
        }
        print("sender net across memo rows: \(senderNet)")
        #expect(
            senderNet < -amount,
            "sender debit must be fee-inclusive: expected < \(-amount), was \(senderNet)"
        )

        // 5b. Receiver side: the mirrored RECEIVED credit.
        let receiverHistory = try await externalTokens.holdingsHistory(partyId: external.partyId)
        for row in receiverHistory { print("receiver row: \(Self.render(row))") }
        let receiverRows = receiverHistory.filter { $0.summary?.memo == memo }
        #expect(receiverRows.count == 1, "receiver history must contain the memo row once")
        let credit = try #require(receiverRows.first?.summary)
        #expect(credit.direction == .received)
        #expect(credit.counterparty == walletParty)
        #expect(credit.instrumentId == amulet)
        #expect(
            Decimal(string: credit.amount) == amount,
            "receiver must be credited exactly the transfer amount, was \(credit.amount)"
        )
    }

    private static func render(_ change: TokenStandardClient.HoldingsChange) -> String {
        let created = change.created.map { "\($0.owner.prefix(16)):\($0.amount)" }
        let archived = change.archived.map { "\($0.owner.prefix(16)):\($0.amount)" }
        let summary: String
        if let s = change.summary {
            summary =
                "direction=\(s.direction) counterparty=\(s.counterparty?.prefix(16).description ?? "nil") "
                + "amount=\(s.amount) memo=\(s.memo ?? "nil")"
        } else {
            summary = "summary=nil"
        }
        return "offset=\(change.offset) created=\(created) archived=\(archived) "
            + "unresolved=\(change.archivedContractIds.count - change.archived.count) \(summary)"
    }
}
