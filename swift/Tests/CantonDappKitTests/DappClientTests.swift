// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import Foundation
import Testing

@testable import CantonDappKit

/// The dApp-side client against a scripted transport — the Swift mirror of
/// `DappClientTest.kt`.
///
/// What is checked is the mapping: that each typed call becomes the method
/// name CIP-0103 actually defines, carries its params by name, and turns a
/// JSON-RPC error back into the right ``DappErrorCode``. The wire names are
/// asserted literally on purpose — a typo there is invisible in a round-trip
/// test where both ends share the same constant.
@Suite struct DappClientTests {

    /// Records requests and replies from a canned script.
    final class ScriptedTransport: DappTransport, @unchecked Sendable {
        private let reply: @Sendable (JSONRPCRequest) -> JSONRPCResponse
        private let lock = NSLock()
        private var recorded: [JSONRPCRequest] = []
        let events: AsyncStream<DappEvent>

        init(
            events: AsyncStream<DappEvent> = AsyncStream { $0.finish() },
            reply: @escaping @Sendable (JSONRPCRequest) -> JSONRPCResponse
        ) {
            self.events = events
            self.reply = reply
        }

        // `withLock` rather than lock()/unlock(): the plain pair is
        // unavailable from an async context, since a suspension while holding
        // it would park the lock on a thread that may never come back.
        func send(_ request: JSONRPCRequest) async throws -> JSONRPCResponse {
            lock.withLock { recorded.append(request) }
            return reply(request)
        }

        var sent: [JSONRPCRequest] { lock.withLock { recorded } }
    }

    let connected = JSONValue.object(["isConnected": .bool(true), "isNetworkConnected": .bool(true)])

    let walletJSON = JSONValue.object([
        "primary": .bool(true),
        "partyId": .string("alice::1220aa"),
        "status": .string("allocated"),
        "hint": .string("alice"),
        "publicKey": .string("00"),
        "namespace": .string("1220aa"),
        "networkId": .string("canton:localnet"),
        "signingProviderId": .string("software"),
    ])

    let executedJSON = JSONValue.object([
        "tx": .object([
            "status": .string("executed"),
            "commandId": .string("order-4711"),
            "payload": .object([
                "updateId": .string("update-1"),
                "completionOffset": .int(42),
            ]),
        ])
    ])

    func responding(_ result: JSONValue) -> ScriptedTransport {
        ScriptedTransport { .success(id: $0.id, result: result) }
    }

    func failing(_ code: Int, _ message: String, data: JSONValue? = nil) -> ScriptedTransport {
        ScriptedTransport { .failure(id: $0.id, error: JSONRPCErrorBody(code: code, message: message, data: data)) }
    }

    // ── Method mapping ─────────────────────────────────────────────────

    @Test func eachCallUsesTheCIP0103MethodName() async throws {
        let connected = connected
        let walletJSON = walletJSON
        let executedJSON = executedJSON
        let transport = ScriptedTransport { request in
            let result: JSONValue
            switch request.method {
            case "connect", "isConnected": result = connected
            case "status":
                result = .object([
                    "provider": .object(["id": .string("wallet")]),
                    "connection": connected,
                ])
            case "getActiveNetwork": result = .object(["networkId": .string("canton:localnet")])
            case "listAccounts": result = .array([walletJSON])
            case "getPrimaryAccount": result = walletJSON
            case "signMessage": result = .object(["signature": .string("sig")])
            case "prepareExecuteAndWait": result = executedJSON
            case "ledgerApi": result = .object(["version": .string("3.5.12")])
            default: result = .null
            }
            return .success(id: request.id, result: result)
        }
        let client = DappClient(transport: transport)

        _ = try await client.connect()
        _ = try await client.isConnected()
        _ = try await client.status()
        _ = try await client.getActiveNetwork()
        _ = try await client.listAccounts()
        _ = try await client.getPrimaryAccount()
        _ = try await client.signMessage("hello")
        try await client.prepareExecute(PrepareSubmission(commands: []))
        _ = try await client.prepareExecuteAndWait(PrepareSubmission(commands: []))
        _ = try await client.ledgerApi(LedgerApiRequest(requestMethod: .get, resource: "/v2/version"))
        try await client.disconnect()

        #expect(
            transport.sent.map(\.method) == [
                "connect", "isConnected", "status", "getActiveNetwork", "listAccounts",
                "getPrimaryAccount", "signMessage", "prepareExecute", "prepareExecuteAndWait",
                "ledgerApi", "disconnect",
            ]
        )
    }

    @Test func paramsTravelByNameNotAsAPositionalArray() async throws {
        let transport = responding(.object(["signature": .string("sig")]))

        _ = try await DappClient(transport: transport).signMessage("hello")

        let params = try #require(transport.sent.first?.params)
        #expect(params.objectValue?["message"] == .string("hello"))
        #expect(params.arrayValue == nil, "params must be an object, not a positional array")
    }

    @Test func requestsCarryADistinctId() async throws {
        let transport = responding(connected)
        let client = DappClient(transport: transport)

        _ = try await client.connect()
        _ = try await client.connect()

        let ids = transport.sent.compactMap(\.id)
        #expect(ids.count == 2)
        #expect(ids[0] != ids[1])
        #expect(transport.sent.allSatisfy { !$0.isNotification })
    }

    // ── Errors ─────────────────────────────────────────────────────────

    @Test func aUserRejectionSurfacesAs4001() async throws {
        let client = DappClient(transport: failing(4001, "User rejected the request"))

        // Hoisted out of #expect: errors thrown inside the macro are not
        // handled, a trap this repo has already hit once.
        var thrown: DappError?
        do {
            _ = try await client.prepareExecuteAndWait(PrepareSubmission(commands: []))
        } catch let error as DappError {
            thrown = error
        }

        #expect(thrown?.code == .userRejected)
        #expect(thrown?.isUserRejection == true)
    }

    @Test func anErrorKeepsItsDataPayload() async throws {
        let data = JSONValue.object(["traceId": .string("edb2e49d")])
        let client = DappClient(transport: failing(-32003, "Transaction rejected", data: data))

        var thrown: DappError?
        do {
            _ = try await client.prepareExecuteAndWait(PrepareSubmission(commands: []))
        } catch let error as DappError {
            thrown = error
        }

        #expect(thrown?.code == .transactionRejected)
        #expect(thrown?.data == data)
    }

    @Test func anUnrecognisedCodeDegradesToInternalWithoutLosingTheMessage() async throws {
        let client = DappClient(transport: failing(-31999, "Something new"))

        var thrown: DappError?
        do {
            _ = try await client.connect()
        } catch let error as DappError {
            thrown = error
        }

        // Better to keep the text and lose the exact code than to fail
        // decoding a wallet that is simply newer than this SDK.
        #expect(thrown?.code == .internalError)
        #expect(thrown?.message.contains("Something new") == true)
        #expect(thrown?.message.contains("-31999") == true)
    }

    @Test func prepareExecuteAndWaitRefusesANonExecutedTransaction() async throws {
        let pending = JSONValue.object([
            "tx": .object(["status": .string("pending"), "commandId": .string("c1")])
        ])
        let client = DappClient(transport: responding(pending))

        var thrown: DappError?
        do {
            _ = try await client.prepareExecuteAndWait(PrepareSubmission(commands: []))
        } catch let error as DappError {
            thrown = error
        }

        #expect(thrown?.code == .invalidParams)
    }

    // ── Results and events ─────────────────────────────────────────────

    @Test func prepareExecuteAndWaitDecodesTheExecutedTransaction() async throws {
        let client = DappClient(transport: responding(executedJSON))

        let executed = try await client.prepareExecuteAndWait(PrepareSubmission(commands: []))

        guard case .executed(let commandId, let updateId, let offset) = executed else {
            Issue.record("expected an executed event, got \(executed)")
            return
        }
        #expect(commandId == "order-4711")
        #expect(updateId == "update-1")
        #expect(offset == 42)
    }

    @Test func aTransportWithoutEventsYieldsAnEmptyStream() async throws {
        let client = DappClient(transport: responding(connected))

        var collected: [DappEvent] = []
        for await event in client.events { collected.append(event) }

        #expect(collected.isEmpty)
    }

    @Test func anEventNotificationDecodesBackIntoATypedEvent() throws {
        let original = DappEvent.txChanged(
            .executed(commandId: "order-4711", updateId: "update-1", completionOffset: 42)
        )

        let notification = DappJSON.encodeEvent(original)
        let decoded = try DappJSON.decodeEvent(notification)

        #expect(notification.isNotification, "events must travel without an id")
        #expect(notification.method == "txChanged")
        #expect(decoded == original)
    }

    @Test func aNonEventNotificationDecodesToNilRatherThanThrowing() throws {
        let decoded = try DappJSON.decodeEvent(
            JSONRPCRequest(method: "somethingElse", params: .object(["a": .int(1)]))
        )

        #expect(decoded == nil)
    }
}
