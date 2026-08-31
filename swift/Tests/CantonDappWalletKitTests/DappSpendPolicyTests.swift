// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonDappKit
import Foundation
import Testing

@testable import CantonDappWalletKit

/// The policy's pure decision table plus its enforcement through a real
/// ``DappSession``: the Swift mirror of `DappSpendPolicyTest.kt` and
/// `DappSpendEnforcementTest.kt`. Hard caps refuse rather than ask, unparsed
/// submissions are never auto-approved and never refused by amount,
/// auto-approval is bounded by every other limit, and, the R3 fix, two
/// concurrent requests cannot both pass a cap only one fits under.
@Suite struct DappSpendPolicyTests {

    // MARK: - Pure decisions

    private func transfer(
        amount: String = "2",
        instrument: String = "Amulet",
        receiver: String = "bob::1220bb"
    ) -> DappTransferSummary {
        DappTransferSummary(receiver: receiver, amount: amount, instrumentId: instrument)
    }

    @Test func anEmptyPolicyAsksTheHumanForEverything() {
        let policy = DappSpendPolicy()
        #expect(policy.decide(summary: transfer(), spentLast24h: 0) == .askHuman)
        #expect(policy.decide(summary: transfer(amount: "999999"), spentLast24h: 0) == .askHuman)
        #expect(policy.decide(summary: nil, spentLast24h: 0) == .askHuman)
    }

    @Test func autoApproveBelowApprovesAtOrUnderTheBoundAndAsksAboveIt() {
        let policy = DappSpendPolicy(autoApproveBelow: 5)
        #expect(policy.decide(summary: transfer(amount: "2"), spentLast24h: 0) == .autoApprove)
        #expect(policy.decide(summary: transfer(amount: "5"), spentLast24h: 0) == .autoApprove)
        #expect(policy.decide(summary: transfer(amount: "5.0000000001"), spentLast24h: 0) == .askHuman)
    }

    @Test func anUnparsedSubmissionIsNeverAutoApproved() {
        let policy = DappSpendPolicy(autoApproveBelow: 5)
        #expect(policy.decide(summary: nil, spentLast24h: 0) == .askHuman)
        #expect(policy.decide(summary: transfer(amount: "not a number"), spentLast24h: 0) == .askHuman)
        #expect(policy.decide(summary: transfer(amount: "5abc"), spentLast24h: 0) == .askHuman)
        #expect(policy.decide(summary: transfer(amount: "-3"), spentLast24h: 0) == .askHuman)
    }

    @Test func disallowedInstrumentAndReceiverRefuseWithoutAsking() {
        let policy = DappSpendPolicy(
            allowedInstruments: ["Amulet"],
            allowedReceivers: ["bob::1220bb"]
        )
        guard case .refuse = policy.decide(summary: transfer(instrument: "OtherCoin"), spentLast24h: 0) else {
            Issue.record("expected a refusal for the instrument"); return
        }
        guard case .refuse = policy.decide(summary: transfer(receiver: "mallory::1220ff"), spentLast24h: 0) else {
            Issue.record("expected a refusal for the receiver"); return
        }
        #expect(policy.decide(summary: transfer(), spentLast24h: 0) == .askHuman)
    }

    @Test func thePerTransactionCapRefusesAboveAndAllowsAtTheCap() {
        let policy = DappSpendPolicy(maxPerTransaction: 5)
        guard case .refuse(let reason) = policy.decide(summary: transfer(amount: "5.01"), spentLast24h: 0) else {
            Issue.record("expected a refusal"); return
        }
        #expect(reason.contains("per-transaction cap"))
        #expect(policy.decide(summary: transfer(amount: "5"), spentLast24h: 0) == .askHuman)
    }

    @Test func theDailyCapCountsWhatWasAlreadySpent() {
        let policy = DappSpendPolicy(dailyCap: 10)
        #expect(policy.decide(summary: transfer(amount: "4"), spentLast24h: 6) == .askHuman)
        guard case .refuse(let reason) = policy.decide(summary: transfer(amount: "4.01"), spentLast24h: 6) else {
            Issue.record("expected a refusal"); return
        }
        #expect(reason.contains("daily cap"))
    }

    @Test func autoApprovalStaysBoundedByTheCaps() {
        let policy = DappSpendPolicy(dailyCap: 10, autoApproveBelow: 5)
        #expect(policy.decide(summary: transfer(amount: "2"), spentLast24h: 8) == .autoApprove)
        guard case .refuse = policy.decide(summary: transfer(amount: "3"), spentLast24h: 8) else {
            Issue.record("expected a refusal over the daily cap"); return
        }
    }

    // MARK: - Enforcement through the session

    static func wallet(_ partyId: String) -> DappWallet {
        DappWallet(
            primary: true,
            partyId: partyId,
            status: .allocated,
            hint: String(partyId.split(separator: ":").first ?? ""),
            publicKey: "00",
            namespace: String(partyId.split(separator: ":").last ?? ""),
            networkId: "canton:localnet",
            signingProviderId: "software"
        )
    }

    let alice = wallet("alice::1220aa")

    struct Accounts: DappAccountsSource {
        let available: [DappWallet]
        func accounts() async throws -> [DappWallet] { available }
    }

    /// Counts transaction approvals; connect approvals share every account.
    actor CountingApprover: DappApprovalDelegate {
        var transactionsSeen = 0
        let gate: (@Sendable () async -> Void)?
        init(gate: (@Sendable () async -> Void)? = nil) { self.gate = gate }
        func approve(_ request: DappApprovalRequest) async -> DappApproval {
            if case .transaction = request {
                transactionsSeen += 1
                if let gate { await gate() }
                return .approved()
            }
            if case .connection(_, _, let available) = request { return .approved(accounts: available) }
            return .approved()
        }
    }

    struct Pipeline: PrepareExecutePipeline {
        func execute(_ context: PrepareExecuteContext) async throws -> TxChangedEvent {
            .executed(commandId: context.commandId, updateId: "upd", completionOffset: 1)
        }
    }

    private func transferSubmission(_ amount: String) throws -> JSONValue {
        try DappJSON.encode(
            PrepareSubmission(
                commands: [
                    try JSONValue.parse(
                        """
                        {
                          "ExerciseCommand": {
                            "choice": "TransferFactory_Transfer",
                            "choiceArgument": {
                              "transfer": {
                                "receiver": "bob::1220bb",
                                "amount": "\(amount)",
                                "instrumentId": { "id": "Amulet" }
                              }
                            }
                          }
                        }
                        """
                    )
                ]
            )
        )
    }

    private func makeSession(
        approver: DappApprovalDelegate,
        policy: DappSpendPolicy?,
        ledger: any SpendLedger = InMemorySpendLedger(),
        now: @escaping @Sendable () -> Date = { Date() }
    ) -> DappSession {
        DappSession(
            peer: DappPeer(id: "agent-1", name: "Agent"),
            accounts: Accounts(available: [alice]),
            approver: approver,
            network: DappNetworkConfig(networkId: "canton:localnet"),
            prepareExecute: Pipeline(),
            spendPolicy: { policy },
            spendLedger: ledger,
            wallClock: now
        )
    }

    private func req(_ method: DappMethod, _ params: JSONValue? = nil, id: Int64 = 1) -> JSONRPCRequest {
        JSONRPCRequest(method: method.rawValue, params: params, id: .int(id))
    }

    @Test func autoApprovalSkipsTheSheetAndRecordsAnAutoReceipt() async throws {
        let approver = CountingApprover()
        let ledger = InMemorySpendLedger()
        let session = makeSession(approver: approver, policy: DappSpendPolicy(autoApproveBelow: 5), ledger: ledger)
        _ = await session.handle(req(.connect))

        let response = await session.handle(req(.prepareExecuteAndWait, try transferSubmission("2"), id: 2))
        #expect(response.error == nil)
        #expect(await approver.transactionsSeen == 0)

        let receipts = try await ledger.receiptsSince(peerId: "agent-1", since: .distantPast)
        #expect(receipts.count == 1)
        #expect(receipts.first?.amount == 2)
        #expect(receipts.first?.autoApproved == true)
        #expect(receipts.first?.receiver == "bob::1220bb")
    }

    @Test func aRefusalNeverReachesTheApproverAndLeavesNoReceipt() async throws {
        let approver = CountingApprover()
        let ledger = InMemorySpendLedger()
        let session = makeSession(approver: approver, policy: DappSpendPolicy(maxPerTransaction: 5), ledger: ledger)
        _ = await session.handle(req(.connect))

        let response = await session.handle(req(.prepareExecuteAndWait, try transferSubmission("100"), id: 2))
        #expect(response.error?.code == DappErrorCode.userRejected.rawValue)
        #expect(response.error?.message.contains("spend policy") == true)
        #expect(await approver.transactionsSeen == 0)
        #expect(try await ledger.receiptsSince(peerId: "agent-1", since: .distantPast).isEmpty)
    }

    @Test func humanApprovedSpendsCountAgainstTheDailyCap() async throws {
        let approver = CountingApprover()
        let session = makeSession(approver: approver, policy: DappSpendPolicy(dailyCap: 10))
        _ = await session.handle(req(.connect))

        #expect(await session.handle(req(.prepareExecuteAndWait, try transferSubmission("5"), id: 2)).error == nil)
        #expect(await session.handle(req(.prepareExecuteAndWait, try transferSubmission("5"), id: 3)).error == nil)
        let third = await session.handle(req(.prepareExecuteAndWait, try transferSubmission("5"), id: 4))
        #expect(third.error?.code == DappErrorCode.userRejected.rawValue)
        #expect(third.error?.message.contains("daily cap") == true)
    }

    @Test func receiptsOlderThanTheRollingWindowFallOutOfTheCap() async throws {
        // A shared clock the test advances; @unchecked because the mutation
        // points are all awaited in sequence.
        final class Clock: @unchecked Sendable { var now = Date(timeIntervalSince1970: 1_800_000_000) }
        let clock = Clock()
        let session = makeSession(
            approver: CountingApprover(),
            policy: DappSpendPolicy(dailyCap: 10),
            now: { clock.now }
        )
        _ = await session.handle(req(.connect))
        #expect(await session.handle(req(.prepareExecuteAndWait, try transferSubmission("8"), id: 2)).error == nil)

        clock.now = clock.now.addingTimeInterval(9 * 3600)
        let blocked = await session.handle(req(.prepareExecuteAndWait, try transferSubmission("8"), id: 3))
        #expect(blocked.error?.code == DappErrorCode.userRejected.rawValue)

        clock.now = clock.now.addingTimeInterval(16 * 3600)
        #expect(await session.handle(req(.prepareExecuteAndWait, try transferSubmission("8"), id: 4)).error == nil)
    }

    @Test func anUnparsedSubmissionAsksTheHumanAndNeverAutoApproves() async throws {
        let approver = CountingApprover()
        let ledger = InMemorySpendLedger()
        let session = makeSession(approver: approver, policy: DappSpendPolicy(autoApproveBelow: 500), ledger: ledger)
        _ = await session.handle(req(.connect))

        let custom = try DappJSON.encode(
            PrepareSubmission(commands: [try JSONValue.parse(#"{ "CreateCommand": { "templateId": "p:M:T" } }"#)])
        )
        #expect(await session.handle(req(.prepareExecuteAndWait, custom, id: 2)).error == nil)
        #expect(await approver.transactionsSeen == 1)
        #expect(try await ledger.receiptsSince(peerId: "agent-1", since: .distantPast).isEmpty)
    }

    @Test func concurrentRequestsCannotBothPassACapOnlyOneFitsUnder() async throws {
        // R3: both frames are in flight before either executes. The first
        // approval waits on a gate; the second request queues behind the
        // submission lock and must see the first's receipt when it runs.
        let gate = AsyncGate()
        let approver = CountingApprover(gate: { await gate.wait() })
        let session = makeSession(approver: approver, policy: DappSpendPolicy(dailyCap: 10))
        _ = await session.handle(req(.connect))

        let sub = try transferSubmission("6")
        async let first = session.handle(req(.prepareExecuteAndWait, sub, id: 2))
        // Wait until the first request's sheet is up, then race the second.
        while await approver.transactionsSeen == 0 { await Task.yield() }
        async let second = session.handle(req(.prepareExecuteAndWait, sub, id: 3))
        await Task.yield()
        await gate.open()
        let (r1, r2) = await (first, second)

        #expect(r1.error == nil)
        #expect(r2.error?.code == DappErrorCode.userRejected.rawValue)
        #expect(r2.error?.message.contains("daily cap") == true)
    }

    @Test func thePolicyRateLimitRefusesRapidFireRequests() async throws {
        let session = makeSession(
            approver: CountingApprover(),
            policy: DappSpendPolicy(minRequestInterval: 3600)
        )
        _ = await session.handle(req(.connect))
        #expect(await session.handle(req(.prepareExecuteAndWait, try transferSubmission("1"), id: 2)).error == nil)
        let second = await session.handle(req(.prepareExecuteAndWait, try transferSubmission("1"), id: 3))
        #expect(second.error?.code == DappErrorCode.invalidInput.rawValue)
        #expect(second.error?.message.contains("rate-limited") == true)
    }
}

/// A one-shot async gate: `wait()` suspends until `open()`.
actor AsyncGate {
    private var opened = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func wait() async {
        if opened { return }
        await withCheckedContinuation { waiters.append($0) }
    }

    func open() {
        opened = true
        for waiter in waiters { waiter.resume() }
        waiters.removeAll()
    }
}
