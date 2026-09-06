// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonDappKit
import Foundation
import CantonDappWalletKit
import Testing

@testable import CantonDappWCKit

/// The adapter driven against a real `DappSession` — no relay, no WalletConnect
/// client. This is the point of keeping the client out: the whole approval →
/// sign path is exercised through `handle`, so a failure here is a protocol or
/// mapping failure, deterministically. Mirrors Kotlin `CantonWalletConnectTest`.
@Suite struct CantonWalletConnectTests {

    let party = "shopper::1220b3d98dd0362a19385d6878be4bafb2f12f13531ee7abcb8f32bdb2d764bac9be"

    var account: DappWallet {
        DappWallet(
            primary: true,
            partyId: party,
            status: .allocated,
            hint: "shopper",
            publicKey: "deadbeef",
            namespace: "1220",
            networkId: "canton:localnet",
            signingProviderId: "test"
        )
    }

    struct Accounts: DappAccountsSource {
        let available: [DappWallet]
        func accounts() async throws -> [DappWallet] { available }
    }

    struct Approver: DappApprovalDelegate {
        let answer: @Sendable (DappApprovalRequest) -> DappApproval
        func approve(_ request: DappApprovalRequest) async -> DappApproval { answer(request) }
    }

    struct Signer: DappMessageSigner {
        func sign(account: DappWallet, message: String) async throws -> String { "sig:\(message)" }
    }

    func session(approver: DappApprovalDelegate) -> DappSession {
        DappSession(
            peer: DappPeer(id: "dapp1", name: "Test Shop"),
            accounts: Accounts(available: [account]),
            approver: approver,
            network: DappNetworkConfig(networkId: "canton:localnet"),
            messageSigner: Signer()
        )
    }

    var approveAll: Approver {
        Approver { request in
            if case .connection(_, _, let available) = request { return .approved(accounts: available) }
            return .approved()
        }
    }

    func req(_ id: Int64, _ method: String, _ params: JSONValue? = nil) -> WcRequest {
        WcRequest(topic: "topic", requestId: id, chainId: "canton:localnet", method: method, params: params)
    }

    @Test func sessionNamespacesProjectsAccountsMethodsAndChain() throws {
        let wc = try CantonWalletConnect(handler: session(approver: approveAll), networkId: "canton:localnet")
        let ns = wc.sessionNamespaces(accounts: [account])
        #expect(ns.chains == ["canton:localnet"])
        #expect(ns.accounts == ["canton:localnet:\(Caip.encodeParty(party))"])
        #expect(ns.methods.contains("canton_signMessage") && ns.methods.contains("canton_prepareSignExecute"))
        #expect(ns.methods.count == 7)
        #expect(ns.events == ["accountsChanged", "statusChanged"])
    }

    @Test func sessionNamespacesApprovesRequestedMethodsTheEngineCanServe() {
        // A CIP-0103-verbatim dApp proposes bare names; both WalletConnect
        // clients refuse any request outside the approved set, so the bare
        // names must be approved or the dApp can never call at all.
        let requested = [
            "connect", "listAccounts", "prepareExecuteAndWait",
            "canton_signMessage",  // ecosystem name, already advertised
            "eth_sendTransaction",  // foreign: refused
            "txChanged",  // wallet-to-dApp event: never a callable request
        ]
        let ns = CantonWalletConnect.sessionNamespaces(
            chainId: "canton:localnet", accounts: [account], requestedMethods: requested
        )
        #expect(ns.methods.contains("connect") && ns.methods.contains("listAccounts"))
        #expect(ns.methods.contains("prepareExecuteAndWait"))
        #expect(!ns.methods.contains("eth_sendTransaction"))
        #expect(!ns.methods.contains("txChanged"))
        #expect(ns.methods.filter { $0 == "canton_signMessage" }.count == 1)
        // Without a proposal the namespaces stay exactly the advertised set.
        #expect(CantonWalletConnect.sessionNamespaces(chainId: "canton:localnet", accounts: [account]).methods.count == 7)
    }

    @Test func normalizeMapsTheCantonPrefixAndThePrepareSignExecuteRename() {
        #expect(WcMethod.normalize("canton_signMessage") == "signMessage")
        #expect(WcMethod.normalize("canton_prepareSignExecute") == "prepareExecuteAndWait")
        #expect(WcMethod.normalize("canton_status") == "status")
        #expect(WcMethod.normalize("signMessage") == "signMessage")  // a bare name passes through
        #expect(WcMethod.normalize("connect") == "connect")
    }

    @Test func aCantonPrefixedRequestIsNormalizedAndAnswered() async throws {
        let wc = try CantonWalletConnect(handler: session(approver: approveAll), networkId: "canton:localnet")
        guard case .success = await wc.handle(req(1, "canton_connect")) else {
            Issue.record("canton_connect should succeed"); return
        }
        let signed = await wc.handle(req(2, "canton_signMessage", .object(["message": .string("hi")])))
        guard case .success(let result) = signed else {
            Issue.record("canton_signMessage should succeed"); return
        }
        #expect(result.objectValue?["signature"]?.stringValue == "sig:hi")
    }

    /// Counts approver calls across threads; `Approver.answer` is synchronous.
    final class Counter: @unchecked Sendable {
        private let lock = NSLock()
        private var n = 0
        func bump() { lock.withLock { n += 1 } }
        var value: Int { lock.withLock { n } }
    }

    @Test func aRedeliveredRequestReachesTheEngineOnceAndGetsTheSameAnswer() async throws {
        let asked = Counter()
        let approver = Approver { request in
            asked.bump()
            if case .connection(_, _, let available) = request { return .approved(accounts: available) }
            return .approved()
        }
        let wc = try CantonWalletConnect(handler: session(approver: approver), networkId: "canton:localnet")
        _ = await wc.handle(req(1, "connect"))
        let sign = req(2, "signMessage", .object(["message": .string("once")]))

        // The relay redelivers while the first copy is still in flight...
        async let first = wc.handle(sign)
        async let second = wc.handle(sign)
        let (a, b) = await (first, second)
        #expect(a == b)
        guard case .success(let result) = a else { Issue.record("signMessage should succeed"); return }
        #expect(result.objectValue?["signature"]?.stringValue == "sig:once")

        // ...and again after it was answered: still the same answer, no new prompt.
        let third = await wc.handle(sign)
        #expect(third == a)
        #expect(asked.value == 2, "connect once, sign once")

        // A different id on the same topic is a new request: it reaches the
        // engine (which rate-limits a second signMessage this soon) instead of
        // being served the first id's signature.
        let fresh = await wc.handle(req(3, "signMessage", .object(["message": .string("twice")])))
        #expect(fresh != a)
        guard case .error = fresh else { Issue.record("a back-to-back signMessage is rate-limited"); return }
    }

    /// Answers like `approveAll` and remembers the context it was handed.
    final class ContextApprover: DappApprovalDelegate, @unchecked Sendable {
        private let lock = NSLock()
        private var seen: [DappRequestContext] = []
        var contexts: [DappRequestContext] { lock.withLock { seen } }
        func approve(_ request: DappApprovalRequest, context: DappRequestContext) async -> DappApproval {
            lock.withLock { seen.append(context) }
            if case .connection(_, _, let available) = request { return .approved(accounts: available) }
            return .approved()
        }
    }

    @Test func theEnvelopeExpiryReachesTheApprover() async throws {
        let approver = ContextApprover()
        let wc = try CantonWalletConnect(handler: session(approver: approver), networkId: "canton:localnet")
        let deadline = Date(timeIntervalSince1970: 1_800_000_000)
        var connect = req(1, "connect")
        connect.expiresAt = deadline
        guard case .success = await wc.handle(connect) else { Issue.record("connect should succeed"); return }
        #expect(approver.contexts == [DappRequestContext(expiresAt: deadline)])

        // No expiry on the wire is no expiry in the context, not a made-up one.
        _ = await wc.handle(req(2, "signMessage", .object(["message": .string("hi")])))
        #expect(approver.contexts.last == DappRequestContext(expiresAt: nil))
    }

    @Test func connectThenSignMessageReturnsASignatureOverTheSession() async throws {
        let wc = try CantonWalletConnect(handler: session(approver: approveAll), networkId: "canton:localnet")
        guard case .success = await wc.handle(req(1, "connect")) else {
            Issue.record("connect should succeed"); return
        }
        let signed = await wc.handle(req(2, "signMessage", .object(["message": .string("hello canton")])))
        guard case .success(let result) = signed else {
            Issue.record("signMessage should succeed"); return
        }
        #expect(result.objectValue?["signature"]?.stringValue == "sig:hello canton")
    }

    @Test func anUnknownMethodMapsToUnsupportedMethod() async throws {
        let wc = try CantonWalletConnect(handler: session(approver: approveAll), networkId: "canton:localnet")
        guard case .error(let code, _) = await wc.handle(req(1, "bogus")) else {
            Issue.record("expected an error response"); return
        }
        #expect(code == DappErrorCode.unsupportedMethod.rawValue)
    }

    @Test func signMessageBeforeConnectIsUnauthorized() async throws {
        let wc = try CantonWalletConnect(handler: session(approver: approveAll), networkId: "canton:localnet")
        guard case .error(let code, _) = await wc.handle(req(1, "signMessage", .object(["message": .string("hi")]))) else {
            Issue.record("expected an error response"); return
        }
        #expect(code == DappErrorCode.unauthorized.rawValue)
    }

    @Test func aDeclinedSignMessageMapsToUserRejected() async throws {
        let approver = Approver { request in
            if case .connection(_, _, let available) = request { return .approved(accounts: available) }
            return .rejected(reason: "no thanks")
        }
        let wc = try CantonWalletConnect(handler: session(approver: approver), networkId: "canton:localnet")
        guard case .success = await wc.handle(req(1, "connect")) else {
            Issue.record("connect should succeed"); return
        }
        guard case .error(let code, _) = await wc.handle(req(2, "signMessage", .object(["message": .string("hi")]))) else {
            Issue.record("expected an error response"); return
        }
        #expect(code == DappErrorCode.userRejected.rawValue)
    }
}
