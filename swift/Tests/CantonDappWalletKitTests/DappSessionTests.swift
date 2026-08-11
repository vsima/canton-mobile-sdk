// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonDappKit
import Foundation
import Testing

@testable import CantonDappWalletKit

/// The provider engine: session lifecycle, per-peer grants, the EIP-1474 error
/// paths, and event ordering — the Swift mirror of `DappSessionTest.kt`.
///
/// The grant tests carry the weight. `listAccounts` returning the right
/// accounts proves very little on its own; it is `listAccounts` *before*
/// approval being refused, and `actAs` naming an unapproved party being
/// refused, that show the wallet is not simply doing what it is told.
@Suite struct DappSessionTests {

    // ── Fixtures ───────────────────────────────────────────────────────

    static func wallet(_ partyId: String, primary: Bool) -> DappWallet {
        DappWallet(
            primary: primary,
            partyId: partyId,
            status: .allocated,
            hint: String(partyId.split(separator: ":").first ?? ""),
            publicKey: "00",
            namespace: String(partyId.split(separator: ":").last ?? ""),
            networkId: "canton:localnet",
            signingProviderId: "software"
        )
    }

    let alice = wallet("alice::1220aa", primary: true)
    let bob = wallet("bob::1220bb", primary: false)
    let peer = DappPeer(id: "example", name: "Example dApp")
    let network = DappNetworkConfig(networkId: "canton:localnet")

    struct Accounts: DappAccountsSource {
        let available: [DappWallet]
        func accounts() async throws -> [DappWallet] { available }
    }

    /// Answers with a canned decision, chosen per request kind.
    struct Approver: DappApprovalDelegate {
        let answer: @Sendable (DappApprovalRequest) -> DappApproval
        func approve(_ request: DappApprovalRequest) async -> DappApproval { answer(request) }
    }

    struct Signer: DappMessageSigner {
        func sign(account: DappWallet, message: String) async throws -> String { "sig:\(message)" }
    }

    struct Pipeline: PrepareExecutePipeline {
        let onExecute: @Sendable (PrepareExecuteContext) -> Void
        init(onExecute: @escaping @Sendable (PrepareExecuteContext) -> Void = { _ in }) {
            self.onExecute = onExecute
        }
        func execute(_ context: PrepareExecuteContext) async throws -> TxChangedEvent {
            onExecute(context)
            return .executed(commandId: context.commandId, updateId: "update-1", completionOffset: 42)
        }
    }

    struct FailingPipeline: PrepareExecutePipeline {
        struct Boom: Error {}
        func execute(_ context: PrepareExecuteContext) async throws -> TxChangedEvent { throw Boom() }
    }

    struct Proxy: LedgerApiProxy {
        func call(_ request: LedgerApiRequest) async throws -> JSONValue { .object(["ok": .bool(true)]) }
    }

    func makeSession(
        available: [DappWallet]? = nil,
        approver: DappApprovalDelegate? = nil,
        messageSigner: DappMessageSigner? = Signer(),
        pipeline: PrepareExecutePipeline? = Pipeline(),
        ledgerApi: LedgerApiProxy? = Proxy(),
        ledgerApiPolicy: LedgerApiPolicy = .readOnly,
        signMessageMinInterval: TimeInterval = 0,
        network: DappNetworkConfig? = nil
    ) -> DappSession {
        let offered = available ?? [alice, bob]
        return DappSession(
            peer: peer,
            accounts: Accounts(available: offered),
            approver: approver ?? Approver { _ in .approved(accounts: offered) },
            network: network ?? self.network,
            messageSigner: messageSigner,
            prepareExecute: pipeline,
            ledgerApi: ledgerApi,
            ledgerApiPolicy: ledgerApiPolicy,
            signMessageMinInterval: signMessageMinInterval
        )
    }

    func request(_ method: DappMethod, params: JSONValue? = nil) -> JSONRPCRequest {
        JSONRPCRequest(method: method.rawValue, params: params, id: .int(1))
    }

    /// The stream buffers without an active iterator, so acting first and
    /// collecting after is safe — and avoids racing the subscription.
    func collect(_ stream: AsyncStream<DappEvent>, count: Int) async -> [DappEvent] {
        var collected: [DappEvent] = []
        guard count > 0 else { return collected }
        for await event in stream {
            collected.append(event)
            if collected.count == count { break }
        }
        return collected
    }

    var commands: [JSONValue] {
        [.object(["CreateCommand": .object(["templateId": .string("pkg:M:T")])])]
    }

    func submission(actAs: [String] = []) -> JSONValue {
        DappJSON.encode(PrepareSubmission(commands: commands, actAs: actAs))
    }

    // ── Connection lifecycle ───────────────────────────────────────────

    @Test func connectGrantsApprovedAccountsAndAnnouncesThem() async throws {
        let session = makeSession(approver: Approver { _ in .approved(accounts: [alice]) })

        let response = await session.handle(request(.connect))
        let result = try DappJSON.decodeConnectResult(try response.resultOrThrow())

        #expect(result.isConnected)
        #expect(result.isNetworkConnected)
        let granted = await session.grantedAccounts
        #expect(granted == [alice])
        let events = await collect(session.events, count: 1)
        #expect(events == [.accountsChanged([alice])])
    }

    @Test func aRejectedConnectReportsTheReasonInsteadOfErroring() async throws {
        let session = makeSession(approver: Approver { _ in .rejected(reason: "Not now") })

        let result = try DappJSON.decodeConnectResult(
            try await session.handle(request(.connect)).resultOrThrow()
        )

        // connect is the one place a refusal is a *result*, not a 4001: the
        // dApp asked whether it may connect and got a truthful "no".
        #expect(!result.isConnected)
        #expect(result.reason == "Not now")
        let granted = await session.grantedAccounts
        #expect(granted.isEmpty)
    }

    @Test func approvingZeroAccountsIsNotAConnection() async throws {
        let session = makeSession(approver: Approver { _ in .approved(accounts: []) })

        let result = try DappJSON.decodeConnectResult(
            try await session.handle(request(.connect)).resultOrThrow()
        )

        #expect(!result.isConnected)
    }

    @Test func anApprovalCannotInventAccountsTheWalletNeverOffered() async throws {
        let intruder = Self.wallet("mallory::1220cc", primary: false)
        let session = makeSession(
            available: [alice],
            approver: Approver { [alice] _ in .approved(accounts: [alice, intruder]) }
        )

        let response = await session.handle(request(.connect))

        #expect(response.error?.code == DappErrorCode.internalError.rawValue)
        let granted = await session.grantedAccounts
        #expect(granted.isEmpty)
    }

    @Test func disconnectClearsTheGrantAndIsIdempotent() async throws {
        let session = makeSession()
        _ = await session.handle(request(.connect))

        let first = await session.handle(request(.disconnect))
        let second = await session.handle(request(.disconnect))

        #expect(first.isOK)
        #expect(second.isOK)
        let granted = await session.grantedAccounts
        #expect(granted.isEmpty)
    }

    // ── Grants ─────────────────────────────────────────────────────────

    @Test func listAccountsBeforeApprovalIsUnauthorized() async throws {
        let response = await makeSession().handle(request(.listAccounts))

        #expect(response.error?.code == DappErrorCode.unauthorized.rawValue)
    }

    @Test func listAccountsReturnsOnlyTheAccountsGrantedToThisPeer() async throws {
        let session = makeSession(approver: Approver { [bob] _ in .approved(accounts: [bob]) })
        _ = await session.handle(request(.connect))

        let accounts = try DappJSON.decodeAccounts(
            try await session.handle(request(.listAccounts)).resultOrThrow()
        )

        // The wallet holds alice too. This peer was not granted her.
        #expect(accounts == [bob])
    }

    @Test func getPrimaryAccountFallsBackToTheFirstGrantedAccount() async throws {
        let session = makeSession(approver: Approver { [bob] _ in .approved(accounts: [bob]) })
        _ = await session.handle(request(.connect))

        let account = try DappJSON.decodeWallet(
            try await session.handle(request(.getPrimaryAccount)).resultOrThrow()
        )

        // bob is not flagged primary, but he is all this peer has.
        #expect(account == bob)
    }

    @Test func statusIsAnswerableBeforeConnectingAndHidesTheNetwork() async throws {
        let status = try DappJSON.decodeStatus(
            try await makeSession().handle(request(.status)).resultOrThrow()
        )

        #expect(!status.connection.isConnected)
        #expect(status.network == nil)
        #expect(status.session == nil)
    }

    @Test func theDappVisibleNetworkNeverCarriesAnAccessToken() async throws {
        let configured = DappNetworkConfig(
            networkId: "canton:localnet",
            jsonApiBaseUrl: "http://127.0.0.1:2975",
            accessTokenProvider: { "a-real-token" }
        )
        let session = makeSession(network: configured)
        _ = await session.handle(request(.connect))

        let network = try DappJSON.decodeNetwork(
            try await session.handle(request(.getActiveNetwork)).resultOrThrow()
        )

        #expect(network.ledgerApi == "http://127.0.0.1:2975")
        #expect(network.accessToken == nil)
    }

    // ── Method surface ─────────────────────────────────────────────────

    @Test func anUnknownMethodIs4200() async throws {
        // The name a competing spec invented. It is not a Canton method.
        let response = await makeSession().handle(
            JSONRPCRequest(method: "canton_connect", id: .int(1))
        )

        #expect(response.error?.code == DappErrorCode.unsupportedMethod.rawValue)
    }

    @Test func anEventNameSentAsARequestIs4200() async throws {
        let response = await makeSession().handle(request(.txChanged))

        #expect(response.error?.code == DappErrorCode.unsupportedMethod.rawValue)
    }

    @Test func aMethodNeedingParamsWithoutThemIs32602() async throws {
        let session = makeSession()
        _ = await session.handle(request(.connect))

        let response = await session.handle(request(.signMessage))

        #expect(response.error?.code == DappErrorCode.invalidParams.rawValue)
    }

    @Test func anUnimplementedCollaboratorMakesItsMethod4200() async throws {
        let session = makeSession(messageSigner: nil)
        _ = await session.handle(request(.connect))

        let response = await session.handle(
            request(.signMessage, params: .object(["message": .string("hi")]))
        )

        #expect(response.error?.code == DappErrorCode.unsupportedMethod.rawValue)
    }

    // ── signMessage ────────────────────────────────────────────────────

    @Test func signMessageEmitsPendingThenSigned() async throws {
        let session = makeSession()
        _ = await session.handle(request(.connect))

        let result = try await session.handle(
            request(.signMessage, params: .object(["message": .string("hello")]))
        ).resultOrThrow()

        #expect(try DappJSON.decodeSignMessageResult(result).signature == "sig:hello")
        // accountsChanged from connect, then pending, then signed.
        let events = await collect(session.events, count: 3)
        guard case .messageSignature(let pending) = events[1],
              case .messageSignature(let signed) = events[2]
        else {
            Issue.record("expected two messageSignature events, got \(events)")
            return
        }
        #expect(pending == .pending(messageId: pending.messageId))
        if case .signed(_, let signature) = signed {
            #expect(signature == "sig:hello")
        } else {
            Issue.record("expected a signed event, got \(signed)")
        }
        // The id must be stable across the pair, or a dApp cannot correlate them.
        #expect(pending.messageId == signed.messageId)
    }

    @Test func aDeclinedSignMessageIs4001AndEmitsFailed() async throws {
        let session = makeSession(
            approver: Approver { [alice, bob] request in
                if case .message = request { return .rejected() }
                return .approved(accounts: [alice, bob])
            }
        )
        _ = await session.handle(request(.connect))

        let response = await session.handle(
            request(.signMessage, params: .object(["message": .string("hello")]))
        )

        #expect(response.error?.code == DappErrorCode.userRejected.rawValue)
        let events = await collect(session.events, count: 3)
        #expect(events.contains { if case .messageSignature(.failed) = $0 { return true } else { return false } })
    }

    @Test func signMessageIsRateLimitedPerSession() async throws {
        let session = makeSession(signMessageMinInterval: 3600)
        _ = await session.handle(request(.connect))
        let params = JSONValue.object(["message": .string("hello")])

        let first = await session.handle(request(.signMessage, params: params))
        let second = await session.handle(request(.signMessage, params: params))

        #expect(first.isOK)
        #expect(second.error?.code == DappErrorCode.invalidInput.rawValue)
    }

    // ── prepareExecute ─────────────────────────────────────────────────

    @Test func prepareExecuteAndWaitReturnsTheExecutedTransaction() async throws {
        let session = makeSession()
        _ = await session.handle(request(.connect))

        let executed = try DappJSON.decodeExecutedResult(
            try await session.handle(
                request(.prepareExecuteAndWait, params: submission())
            ).resultOrThrow()
        )

        guard case .executed(_, let updateId, let offset) = executed else {
            Issue.record("expected an executed event, got \(executed)")
            return
        }
        #expect(updateId == "update-1")
        #expect(offset == 42)
    }

    @Test func prepareExecuteReturnsNullAndReportsThroughEvents() async throws {
        let session = makeSession()
        _ = await session.handle(request(.connect))

        let response = await session.handle(request(.prepareExecute, params: submission()))

        #expect(response.isOK)
        let events = await collect(session.events, count: 3)
        #expect(events.contains { if case .txChanged(.pending) = $0 { return true } else { return false } })
        #expect(events.contains { if case .txChanged(.executed) = $0 { return true } else { return false } })
    }

    @Test func actAsNamingAPartyOutsideTheGrantIsUnauthorized() async throws {
        let session = makeSession(approver: Approver { [alice] _ in .approved(accounts: [alice]) })
        _ = await session.handle(request(.connect))

        let response = await session.handle(
            request(.prepareExecuteAndWait, params: submission(actAs: [bob.partyId]))
        )

        // The heart of the proxy design: a dApp may *request* an actAs, it may
        // not choose one. bob exists in the wallet — this peer was not granted
        // him, and naming him must not be enough.
        #expect(response.error?.code == DappErrorCode.unauthorized.rawValue)
    }

    @Test func actAsWithinTheGrantSelectsThatAccount() async throws {
        let recorded = Recorder()
        let session = makeSession(pipeline: Pipeline { context in recorded.set(context.actAs.partyId) })
        _ = await session.handle(request(.connect))

        _ = await session.handle(
            request(.prepareExecuteAndWait, params: submission(actAs: [bob.partyId]))
        )

        #expect(recorded.value == bob.partyId)
    }

    @Test func multiPartyActAsIsRefused() async throws {
        let session = makeSession()
        _ = await session.handle(request(.connect))

        let response = await session.handle(
            request(.prepareExecuteAndWait, params: submission(actAs: [alice.partyId, bob.partyId]))
        )

        #expect(response.error?.code == DappErrorCode.invalidParams.rawValue)
    }

    @Test func aDeclinedTransactionIs4001AndEmitsFailed() async throws {
        let session = makeSession(
            approver: Approver { [alice, bob] request in
                if case .transaction = request { return .rejected() }
                return .approved(accounts: [alice, bob])
            }
        )
        _ = await session.handle(request(.connect))

        let response = await session.handle(request(.prepareExecuteAndWait, params: submission()))

        #expect(response.error?.code == DappErrorCode.userRejected.rawValue)
        let events = await collect(session.events, count: 3)
        #expect(events.contains { if case .txChanged(.failed) = $0 { return true } else { return false } })
    }

    @Test func aPipelineFailureBecomesAnErrorAndAFailedEvent() async throws {
        let session = makeSession(pipeline: FailingPipeline())
        _ = await session.handle(request(.connect))

        let response = await session.handle(request(.prepareExecuteAndWait, params: submission()))

        #expect(response.error?.code == DappErrorCode.internalError.rawValue)
    }

    // ── ledgerApi ──────────────────────────────────────────────────────

    func ledgerApiRequest(_ method: LedgerApiMethod, _ resource: String) -> JSONRPCRequest {
        request(.ledgerApi, params: DappJSON.encode(LedgerApiRequest(requestMethod: method, resource: resource)))
    }

    @Test func theDefaultPolicyAllowsAReadAndRefusesAWrite() async throws {
        let session = makeSession()
        _ = await session.handle(request(.connect))

        let read = await session.handle(ledgerApiRequest(.get, "/v2/version"))
        let write = await session.handle(ledgerApiRequest(.post, "/v2/commands/submit"))

        #expect(read.isOK)
        #expect(write.error?.code == DappErrorCode.unauthorized.rawValue)
    }

    @Test func theDefaultPolicyRefusesAdministrativeReads() async throws {
        let session = makeSession()
        _ = await session.handle(request(.connect))

        // Read-only is not the same as harmless: user and party management are
        // how rights get granted and parties allocated.
        for resource in ["/v2/users", "/v2/parties", "/v2/users/alice/rights", "/v2/idps"] {
            let response = await session.handle(ledgerApiRequest(.get, resource))
            #expect(
                response.error?.code == DappErrorCode.unauthorized.rawValue,
                "\(resource) should be outside the default policy"
            )
        }
    }

    @Test func theDefaultPolicyAllowsThePostShapedReadsCantonActuallyUses() async throws {
        let session = makeSession()
        _ = await session.handle(request(.connect))

        // The reason the policy is an allowlist and not a verb rule: every
        // read that matters here is a POST. A token-standard dApp cannot
        // choose input UTXOs without the first one.
        for resource in [
            "/v2/state/active-contracts",
            "/v2/state/active-contracts-page",
            "/v2/updates",
            "/v2/updates/flats",
            "/v2/events/events-by-contract-id",
        ] {
            let response = await session.handle(ledgerApiRequest(.post, resource))
            #expect(response.isOK, "POST \(resource) should be readable under the default policy")
        }
    }

    @Test func theDefaultPolicyStillRefusesTheWritesThatShareThosePrefixes() async throws {
        let session = makeSession()
        _ = await session.handle(request(.connect))

        let denied: [(LedgerApiMethod, String)] = [
            // A DAR upload — the case that makes "GET is safe, POST is not"
            // wrong in the other direction too.
            (.post, "/v2/packages"),
            (.post, "/v2/package-vetting/update"),
            (.post, "/v2/commands/submit-and-wait"),
            // Reachable only through prepareExecute, where it is approved
            // and hash-verified.
            (.post, "/v2/interactive-submission/prepare"),
            (.post, "/v2/interactive-submission/execute"),
        ]
        for (method, resource) in denied {
            let response = await session.handle(ledgerApiRequest(method, resource))
            #expect(
                response.error?.code == DappErrorCode.unauthorized.rawValue,
                "\(method.rawValue) \(resource) must stay outside the default policy"
            )
        }
    }

    @Test func aPolicyPrefixCannotBeEscapedByPathTraversal() async throws {
        let session = makeSession()
        _ = await session.handle(request(.connect))

        // Percent-encoded forms matter as much as literal ones: on the Kotlin
        // side OkHttp decodes %2e and *then* resolves dot segments, so before
        // the canonical-form check these reached /v2/users while the policy
        // was still reading them as /v2/state/…. Both platforms refuse the
        // same spellings so the two SDKs cannot drift apart on it.
        for resource in [
            "/v2/state/../users",
            "/v2/state/%2e%2e/users",
            "/v2/state/%2E%2E/%2E%2E/users",
            "/v2/state/%2e%2e%2f%2e%2e/users",
            "/v2/state/./../users",
            "/v2/state\\..\\users",
        ] {
            let response = await session.handle(ledgerApiRequest(.get, resource))
            #expect(
                response.error?.code == DappErrorCode.unauthorized.rawValue,
                "'\(resource)' must not escape the allowed prefix"
            )
        }
    }

    @Test func theClientRefusesANonCanonicalResourceEvenWithoutAPolicy() async throws {
        // The policy is the security boundary, but JSONLedgerAPIClient is
        // public: a host calling it directly must not be able to build a URL
        // the policy would never have approved.
        let client = JSONLedgerAPIClient(baseURL: "http://127.0.0.1:1")

        var thrown: DappError?
        do {
            _ = try await client.call(LedgerApiRequest(requestMethod: .get, resource: "/v2/state/%2e%2e/users"))
        } catch let error as DappError {
            thrown = error
        }

        #expect(thrown?.code == .invalidParams)
    }

    @Test func aHostCanWidenThePolicyWithoutRestatingTheReadSurface() async throws {
        let widened = LedgerApiPolicy.allowing(
            LedgerApiPolicy.readOnlyRules + [.init(.post, "/v2/commands/submit-and-wait")]
        )
        let session = makeSession(ledgerApiPolicy: widened)
        _ = await session.handle(request(.connect))

        let allowed = await session.handle(ledgerApiRequest(.post, "/v2/commands/submit-and-wait"))
        #expect(allowed.isOK)
        // Widening one resource must not quietly open the rest.
        let users = await session.handle(ledgerApiRequest(.get, "/v2/users"))
        #expect(users.error?.code == DappErrorCode.unauthorized.rawValue)
        let version = await session.handle(ledgerApiRequest(.get, "/v2/version"))
        #expect(version.isOK)
    }

    @Test func ledgerApiBeforeApprovalIsUnauthorized() async throws {
        let response = await makeSession().handle(ledgerApiRequest(.get, "/v2/version"))

        #expect(response.error?.code == DappErrorCode.unauthorized.rawValue)
    }

    // ── Transport binding ──────────────────────────────────────────────

    @Test func theInProcessTransportCarriesAFullClientRoundTrip() async throws {
        let session = makeSession()
        let client = DappClient(transport: InProcessDappTransport(session: session))

        let connected = try await client.connect()
        let accounts = try await client.listAccounts()
        let executed = try await client.prepareExecuteAndWait(
            PrepareSubmission(commands: commands)
        )

        #expect(connected.isConnected)
        #expect(accounts == [alice, bob])
        #expect(executed.commandId.isEmpty == false)
        guard case .executed(_, let updateId, _) = executed else {
            Issue.record("expected an executed event")
            return
        }
        #expect(updateId == "update-1")
    }
}

/// A `Sendable` box for the one value a pipeline stub needs to report back.
final class Recorder: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: String?

    func set(_ value: String) { lock.withLock { stored = value } }

    var value: String? { lock.withLock { stored } }
}
